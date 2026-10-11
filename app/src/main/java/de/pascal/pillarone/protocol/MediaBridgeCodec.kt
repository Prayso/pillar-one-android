package de.pascal.pillarone.protocol

data class MediaSnapshot(val title: String = "", val artist: String = "", val playing: Boolean = false)

object MediaBridgeCodec {
    const val UUID = "9a8b0005-7e4f-4c21-9d63-2b51a670c8e0"
    data class Control(val action: Int, val commandId: Int)

    fun frames(snapshot: MediaSnapshot, messageId: Int): List<ByteArray> {
        require(messageId in 0..65535)
        val title = truncate(snapshot.title)
        val artist = truncate(snapshot.artist)
        val payload = title + artist
        val count = maxOf(1, (payload.size + 11) / 12)
        return List(count) { index ->
            byteArrayOf(1, if (snapshot.playing) 1 else 0,
                messageId.toByte(), (messageId ushr 8).toByte(), index.toByte(), count.toByte(),
                title.size.toByte(), artist.size.toByte()) +
                payload.copyOfRange(index * 12, minOf(payload.size, (index + 1) * 12))
        }
    }

    fun control(bytes: ByteArray): Control? {
        if (bytes.size != 4 || bytes[0].toInt() != 1 || bytes[1].toInt() !in 1..5) return null
        return Control(bytes[1].toInt(), (bytes[2].toInt() and 255) or ((bytes[3].toInt() and 255) shl 8))
    }

    private fun truncate(text: String): ByteArray {
        val clean = text.filterNot { it == '\u0000' }
        val bytes = clean.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 63) return bytes
        var end = 63
        while ((bytes[end].toInt() and 0xc0) == 0x80) end--
        return bytes.copyOf(end)
    }
}

class MediaControlDeduplicator {
    private var previous: Int? = null

    fun accept(control: MediaBridgeCodec.Control): Boolean {
        val last = previous
        if (last != null && ((control.commandId - last) and 65535) !in 1..32767) return false
        previous = control.commandId
        return true
    }

    fun reset() { previous = null }
}