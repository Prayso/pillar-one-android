package de.pascal.pillarone.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

data class MenuPage(val id: Int, val name: String, val visible: Boolean, val startupAllowed: Boolean = true)
data class MenuConfiguration(val revision: Long, val startupPageId: Int, val pages: List<MenuPage>)
data class MenuEntry(
    val revision: Long,
    val startupPageId: Int,
    val count: Int,
    val index: Int,
    val page: MenuPage,
)

class MenuChangedException : ProtocolException("Menu changed while reading")

object MenuProtocol {
    const val MAX_PAGES = 32

    private fun valid(condition: Boolean, message: String) {
        if (!condition) throw ProtocolException(message)
    }

    private fun ByteArray.u8(offset: Int) = this[offset].toInt() and 255
    private fun ByteArray.u32(offset: Int): Long =
        (0..3).fold(0L) { value, index -> value or (u8(offset + index).toLong() shl (8 * index)) }

    fun decodeEntry(payload: ByteArray): MenuEntry {
        valid(payload.size >= 12, "Short menu entry")
        valid(payload.u8(0) == 1, "Unsupported menu schema")
        val revision = payload.u32(1)
        val count = payload.u8(6)
        val index = payload.u8(7)
        val nameLength = payload.u8(10)
        valid(revision != 0L, "Zero menu revision")
        valid(count in 1..MAX_PAGES && index < count, "Invalid menu coordinates")
        valid(payload.u8(5) != 0 && payload.u8(8) != 0, "Zero page ID")
        valid(payload.u8(9) in 0..3, "Invalid page flags")
        valid(nameLength in 1..64 && payload.size == 11 + nameLength, "Invalid menu label length")
        val name = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload, 11, nameLength)).toString()
        } catch (_: CharacterCodingException) {
            throw ProtocolException("Invalid menu UTF-8")
        }
        valid(name.isNotBlank() && name.none { it.code in 0..31 || it.code in 127..159 }, "Invalid menu label")
        val flags = payload.u8(9)
        return MenuEntry(
            revision, payload.u8(5), count, index,
            MenuPage(payload.u8(8), name, flags and 1 != 0, flags and 2 != 0),
        )
    }

    fun validate(configuration: MenuConfiguration) {
        valid(configuration.revision in 1..0xffff_ffffL, "Invalid menu revision")
        valid(configuration.pages.size in 1..MAX_PAGES, "Invalid page count")
        valid(configuration.pages.all { it.id in 1..255 }, "Invalid page ID")
        valid(configuration.pages.map { it.id }.distinct().size == configuration.pages.size, "Duplicate page ID")
        valid(
            configuration.pages.any { it.id == configuration.startupPageId && it.visible && it.startupAllowed },
            "Start page must be visible and eligible",
        )
    }

    fun encodeSet(configuration: MenuConfiguration): ByteArray {
        validate(configuration)
        return ByteArray(7 + 2 * configuration.pages.size).apply {
            this[0] = 1
            repeat(4) { this[1 + it] = (configuration.revision ushr (8 * it)).toByte() }
            this[5] = configuration.startupPageId.toByte()
            this[6] = configuration.pages.size.toByte()
            configuration.pages.forEachIndexed { index, page ->
                this[7 + 2 * index] = page.id.toByte()
                this[8 + 2 * index] = if (page.visible) 1 else 0
            }
        }
    }
}

class MenuRead {
    private val entries = mutableListOf<MenuEntry>()
    val nextIndex: Int get() = entries.size

    fun accept(payload: ByteArray): MenuConfiguration? {
        val entry = MenuProtocol.decodeEntry(payload)
        if (entry.index != nextIndex) throw ProtocolException("Unexpected menu index")
        entries.firstOrNull()?.let { first ->
            if (entry.revision != first.revision || entry.count != first.count ||
                entry.startupPageId != first.startupPageId
            ) throw MenuChangedException()
        }
        if (entries.any { it.page.id == entry.page.id }) throw ProtocolException("Duplicate menu page")
        entries += entry
        if (entries.size != entry.count) return null
        return MenuConfiguration(entry.revision, entry.startupPageId, entries.map { it.page }).also(MenuProtocol::validate)
    }
}
