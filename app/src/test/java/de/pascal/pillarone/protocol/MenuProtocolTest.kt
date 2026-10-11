package de.pascal.pillarone.protocol

import org.junit.Assert.*
import org.junit.Test

class MenuProtocolTest {
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val fixture = hex("01 78 56 34 12 01 02 00 01 03 04 4d 61 70 73")
    private val configuration = MenuConfiguration(
        0x12345678L, 1, listOf(MenuPage(2, "Music", false), MenuPage(1, "Maps", true)),
    )

    private fun invalid(action: () -> Unit) {
        try {
            action()
            fail("Expected ProtocolException")
        } catch (_: ProtocolException) {
            // Expected.
        }
    }

    @Test fun sharedEntryAndSetFixtures() {
        assertEquals(MenuEntry(0x12345678L, 1, 2, 0, MenuPage(1, "Maps", true)), MenuProtocol.decodeEntry(fixture))
        assertArrayEquals(hex("01 78 56 34 12 01 02 02 00 01 01"), MenuProtocol.encodeSet(configuration))
    }

    @Test fun readsWholeCatalogInOrder() {
        val read = MenuRead()
        assertEquals(0, read.nextIndex)
        assertNull(read.accept(fixture))
        assertEquals(1, read.nextIndex)
        val second = fixture.copyOf().apply { this[7] = 1; this[8] = 2; this[9] = 2 }
        assertEquals(
            configuration.copy(pages = configuration.pages.reversed().map { if (it.id == 2) it.copy(name = "Maps") else it }),
            read.accept(second),
        )
    }

    @Test fun rejectsChangingOrDuplicateSnapshots() {
        for (offset in listOf(1, 5, 6, 8)) {
            val read = MenuRead()
            read.accept(fixture)
            val second = fixture.copyOf().apply {
                this[7] = 1
                if (offset != 8) this[8] = 2
                if (offset != 8) this[offset] = (this[offset] + 1).toByte()
            }
            invalid { read.accept(second) }
        }
        invalid { MenuRead().accept(fixture.copyOf().apply { this[7] = 1 }) }
        val read = MenuRead()
        read.accept(fixture)
        invalid { read.accept(fixture.copyOf().apply { this[7] = 1; this[8] = 2; this[5] = 3 }) }
    }

    @Test fun malformedEntriesAreRejected() {
        for ((offset, value) in listOf(0 to 2, 5 to 0, 6 to 0, 6 to 33, 7 to 2, 8 to 0, 9 to 4, 10 to 0, 10 to 65, 11 to 0xff, 11 to 0)) {
            invalid { MenuProtocol.decodeEntry(fixture.copyOf().apply { this[offset] = value.toByte() }) }
        }
        invalid { MenuProtocol.decodeEntry(fixture.copyOf().apply { (1..4).forEach { this[it] = 0 } }) }
        invalid { MenuProtocol.decodeEntry(fixture.copyOf(11)) }
        invalid { MenuProtocol.decodeEntry(fixture + 0) }
        invalid { MenuProtocol.decodeEntry(fixture.copyOf().apply { (11..14).forEach { this[it] = 32 } }) }
    }

    @Test fun utf8AndByteLimits() {
        val name = "ä".repeat(32).toByteArray()
        val entry = fixture.copyOf(11).apply { this[10] = name.size.toByte() } + name
        assertEquals("ä".repeat(32), MenuProtocol.decodeEntry(entry).page.name)
        invalid { MenuProtocol.decodeEntry(entry.copyOf().apply { this[10] = 65 } + 65) }
        invalid { MenuProtocol.decodeEntry(entry.copyOf().apply { this[11] = 0xc0.toByte(); this[12] = 0xaf.toByte() }) }
    }

    @Test fun invalidConfigurationsCannotBeSent() {
        invalid { MenuProtocol.encodeSet(configuration.copy(revision = 0)) }
        invalid { MenuProtocol.encodeSet(configuration.copy(revision = 0x1_0000_0000L)) }
        invalid { MenuProtocol.encodeSet(configuration.copy(startupPageId = 2)) }
        invalid { MenuProtocol.encodeSet(configuration.copy(startupPageId = 3)) }
        invalid { MenuProtocol.encodeSet(configuration.copy(pages = emptyList())) }
        invalid { MenuProtocol.encodeSet(configuration.copy(pages = listOf(configuration.pages[1], configuration.pages[1]))) }
        invalid { MenuProtocol.encodeSet(configuration.copy(pages = listOf(MenuPage(0, "Invalid", true)))) }
        val maximum = MenuConfiguration(0xffff_ffffL, 1, (1..32).map { MenuPage(it, "Page", true) })
        assertEquals(71, MenuProtocol.encodeSet(maximum).size)
        invalid { MenuProtocol.encodeSet(maximum.copy(pages = maximum.pages + MenuPage(33, "Extra", true))) }
    }

    @Test fun startMustExistAndBeVisibleInCompleteRead() {
        val read = MenuRead()
        invalid { read.accept(fixture.copyOf().apply { this[6] = 1; this[9] = 0 }) }
        invalid { MenuRead().accept(fixture.copyOf().apply { this[6] = 1; this[5] = 2 }) }
        invalid { MenuRead().accept(fixture.copyOf().apply { this[6] = 1; this[9] = 1 }) }
        val notEligible = configuration.copy(
            pages = configuration.pages.map { if (it.id == 1) it.copy(startupAllowed = false) else it },
        )
        invalid { MenuProtocol.encodeSet(notEligible) }
        assertFalse(MenuProtocol.decodeEntry(fixture.copyOf().apply { this[9] = 1 }).page.startupAllowed)
    }

    @Test fun exchangesMatchMenuResponsesAndEarlyNotifications() {
        val request = V1Message(MessageType.GET_MENU, 5, 7, byteArrayOf(0))
        val response = V1Message(MessageType.MENU_STATE, 5, 7, fixture)
        val exchange = V1Exchange(request)
        assertFalse(exchange.accept(response.copy(sequence = 8)))
        assertFalse(exchange.accept(response.copy(session = 6)))
        assertFalse(exchange.accept(response.copy(payload = fixture.copyOf().apply { this[7] = 1 })))
        assertTrue(exchange.accept(response))
        assertFalse(exchange.complete)
        exchange.wroteFrame()
        assertTrue(exchange.complete)
        invalid { V1Exchange(request).accept(V1Message(MessageType.ACK, 5, 7, byteArrayOf(0x20, 0))) }
        assertTrue(V1Exchange(request).accept(V1Message(MessageType.ACK, 5, 7, byteArrayOf(0x20, 8))))
        val set = V1Exchange(V1Message(MessageType.SET_MENU, 5, 8, MenuProtocol.encodeSet(configuration)))
        assertFalse(set.accept(V1Message(MessageType.ACK, 5, 8, byteArrayOf(0x20, 8))))
        assertTrue(set.accept(V1Message(MessageType.ACK, 5, 8, byteArrayOf(0x22, 9))))
        while (!set.writesComplete) set.wroteFrame()
        assertTrue(set.complete)
    }

    @Test fun menuCapabilityIsOptionalAndMenuStatusesAreScoped() {
        val hello = hex("00 01 02 03 04 05 06 07 08 03 00 c0 00 1e 00")
        assertFalse(V1Protocol.helloAck(hello).capabilities!!.supportsMenu)
        assertTrue(V1Protocol.helloAck(hello.copyOf().apply { this[9] = 7 }).capabilities!!.supportsMenu)
        invalid { V1Protocol.commandAck(byteArrayOf(0x10, 8)) }
        invalid { V1Protocol.commandAck(byteArrayOf(0x11, 9)) }
        invalid { V1Protocol.commandAck(byteArrayOf(0x22, 10)) }
        assertEquals(AckStatus.STORAGE_FAILED, V1Protocol.commandAck(byteArrayOf(0x22, 9)).status)
    }
}
