package de.pascal.pillarone.protocol

import org.junit.Assert.*
import org.junit.Test

class V1ProtocolTest {
    private val session = 0x12345678L
    private val helloPayload = hex("00 01 02 03 04 05 06 07 08 03 00 c0 00 1e 00")
    private val hint = V1Protocol.testTurn()

    private fun hex(text: String) = text.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private fun message(type: MessageType, payload: ByteArray = byteArrayOf(), sequence: Int = 1) =
        V1Message(type, session, sequence, payload)

    private fun invalid(action: () -> Unit) {
        try {
            action()
            fail("Expected ProtocolException")
        } catch (_: ProtocolException) {
            // Expected validation failure, not a replacement/default value.
        }
    }

    @Test fun helloFixture() {
        assertArrayEquals(
            hex("50 31 01 01 78 56 34 12 00 00 00 01"),
            V1Protocol.frames(message(MessageType.HELLO, sequence = 0)).single(),
        )
    }

    @Test fun helloAckFixtures() {
        val frames = V1Protocol.frames(message(MessageType.HELLO_ACK, helloPayload, 0))
        assertArrayEquals(hex("50 31 01 02 78 56 34 12 00 00 00 02 00 01 02 03 04 05 06 07"), frames[0])
        assertArrayEquals(hex("50 31 01 02 78 56 34 12 00 00 01 02 08 03 00 c0 00 1e 00"), frames[1])
        val assembler = V1Reassembler(session)
        assertNull(assembler.accept(frames[0], 0))
        val ack = V1Protocol.helloAck(assembler.accept(frames[1], 1)!!.payload)
        assertEquals(AckStatus.OK, ack.status)
        assertEquals(DeviceCapabilities("0102030405060708", 3, 192, 30), ack.capabilities)
    }

    @Test fun navigationFixtures() {
        val payload = V1Protocol.navigation(hint)
        assertArrayEquals(hex("05 00 c8 00 00 00 02 00 06 00 52 65 63 68 74 73"), payload)
        val frames = V1Protocol.frames(message(MessageType.NAV_UPDATE, payload))
        assertArrayEquals(hex("50 31 01 10 78 56 34 12 01 00 00 02 05 00 c8 00 00 00 02 00"), frames[0])
        assertArrayEquals(hex("50 31 01 10 78 56 34 12 01 00 01 02 06 00 52 65 63 68 74 73"), frames[1])
        assertEquals(hint, V1Protocol.decodeNavigation(payload))
    }

    @Test fun ackFixture() {
        assertArrayEquals(
            hex("50 31 01 80 78 56 34 12 01 00 00 01 10 00"),
            V1Protocol.frames(message(MessageType.ACK, hex("10 00"))).single(),
        )
        assertEquals(CommandAck(MessageType.NAV_UPDATE, AckStatus.OK), V1Protocol.commandAck(hex("10 00")))
    }

    @Test fun endFixtures() {
        assertArrayEquals(
            hex("50 31 01 11 78 56 34 12 02 00 00 01"),
            V1Protocol.frames(message(MessageType.NAV_END, sequence = 2)).single(),
        )
        assertArrayEquals(
            hex("50 31 01 80 78 56 34 12 02 00 00 01 11 00"),
            V1Protocol.frames(message(MessageType.ACK, hex("11 00"), 2)).single(),
        )
    }

    @Test fun integerEnvelopeBounds() {
        for (id in listOf(0L, -1L, 0x1_0000_0000L)) {
            invalid { V1Protocol.frames(message(MessageType.HELLO).copy(session = id)) }
        }
        for (seq in listOf(-1, 65536)) invalid { V1Protocol.frames(message(MessageType.HELLO, sequence = seq)) }
        val frame = V1Protocol.frames(message(MessageType.NAV_END).copy(session = 0xffff_ffffL, sequence = 65535)).single()
        assertEquals(0xffff_ffffL, V1Protocol.decodeFrame(frame).session)
        assertEquals(65535, V1Protocol.decodeFrame(frame).sequence)
    }

    @Test fun payloadLimitAndBoundedFragments() {
        val frames = V1Protocol.frames(message(MessageType.NAV_UPDATE, ByteArray(192)))
        assertEquals(24, frames.size)
        assertTrue(frames.all { it.size == 20 })
        invalid { V1Protocol.frames(message(MessageType.NAV_UPDATE, ByteArray(193))) }
    }

    @Test fun allPayloadLengthsRoundTrip() {
        for (length in 0..192) {
            val payload = ByteArray(length) { it.toByte() }
            val assembler = V1Reassembler(session)
            var complete: V1Message? = null
            V1Protocol.frames(message(MessageType.NAV_UPDATE, payload)).forEachIndexed { index, bytes ->
                complete = assembler.accept(bytes, index.toLong())
            }
            assertArrayEquals(payload, complete!!.payload)
            assertNull(assembler.expiresAt)
        }
    }

    @Test fun malformedHeadersAreRejected() {
        val original = V1Protocol.frames(message(MessageType.NAV_END)).single()
        for ((offset, value) in listOf(0 to 0, 1 to 0, 2 to 2, 3 to 0x33, 10 to 1, 11 to 0, 11 to 25)) {
            invalid { V1Protocol.decodeFrame(original.copyOf().apply { this[offset] = value.toByte() }) }
        }
        invalid { V1Protocol.decodeFrame(original.copyOf().apply { (4..7).forEach { this[it] = 0 } }) }
        invalid { V1Protocol.decodeFrame(ByteArray(11)) }
        invalid { V1Protocol.decodeFrame(ByteArray(21)) }
    }

    @Test fun fragmentLengthsAreStrict() {
        val frames = V1Protocol.frames(message(MessageType.NAV_UPDATE, ByteArray(9)))
        invalid { V1Protocol.decodeFrame(frames[0].copyOf(19)) }
        invalid { V1Protocol.decodeFrame(frames[1].copyOf(12)) }
        assertEquals(0, V1Protocol.decodeFrame(V1Protocol.frames(message(MessageType.HELLO)).single()).payload.size)
    }

    @Test fun unknownAckTypeStatusAndLengths() {
        for (payload in listOf(hex("01 00"), hex("33 00"), hex("10 08"), hex("10"), hex("10 00 00"))) {
            invalid { V1Protocol.commandAck(payload) }
        }
    }

    @Test fun allDefinedStatusesDecode() {
        AckStatus.entries.forEach {
            assertEquals(it, V1Protocol.commandAck(byteArrayOf(0x10, it.wire.toByte())).status)
            if (it != AckStatus.OK) {
                val ack = V1Protocol.helloAck(ByteArray(15).apply { this[0] = it.wire.toByte() })
                assertEquals(it, ack.status)
                assertNull(ack.capabilities)
            }
        }
    }

    @Test fun helloAckBoundsAndReservedCapabilities() {
        for (length in listOf(0, 14, 16)) invalid { V1Protocol.helloAck(ByteArray(length)) }
        invalid { V1Protocol.helloAck(helloPayload.copyOf().apply { this[0] = 8 }) }
        invalid { V1Protocol.helloAck(helloPayload.copyOf().apply { (1..8).forEach { this[it] = 0 } }) }
        for (bits in listOf(0, 1, 2)) invalid { V1Protocol.helloAck(helloPayload.copyOf().apply { this[9] = bits.toByte() }) }
        for (maximum in listOf(9, 193)) invalid { V1Protocol.helloAck(helloPayload.copyOf().apply { this[11] = maximum.toByte() }) }
        for (ttl in listOf(0, 31)) invalid { V1Protocol.helloAck(helloPayload.copyOf().apply { this[13] = ttl.toByte() }) }
        invalid { V1Protocol.helloAck(helloPayload.copyOf().apply { this[0] = 1 }) }
        val reserved = helloPayload.copyOf().apply { this[10] = 0x80.toByte() }
        assertEquals(0x8003, V1Protocol.helloAck(reserved).capabilities!!.bits)
    }

    @Test fun ttlBoundsAndNegotiation() {
        for (ttl in listOf(-1, 0, 31, 65536)) invalid { V1Protocol.navigation(hint.copy(ttlSeconds = ttl)) }
        invalid { V1Protocol.navigation(hint, maxTtl = 4) }
        invalid { V1Protocol.navigation(hint, maxTtl = 31) }
        for (ttl in listOf(1, 30)) assertEquals(ttl, V1Protocol.decodeNavigation(V1Protocol.navigation(hint.copy(ttlSeconds = ttl))).ttlSeconds)
    }

    @Test fun validSmallCapabilitiesCannotSilentlyAdjustManualTest() {
        for ((maximum, ttl) in listOf(10 to 1, 15 to 30, 192 to 4)) {
            val capabilities = V1Protocol.helloAck(helloPayload.copyOf().apply {
                this[11] = maximum.toByte()
                this[13] = ttl.toByte()
            }).capabilities!!
            assertEquals(maximum, capabilities.maxMessageBytes)
            assertEquals(ttl, capabilities.maxTtlSeconds)
            invalid {
                V1Protocol.navigation(hint, capabilities.maxMessageBytes, capabilities.maxTtlSeconds)
            }
            // Explicit end remains available even when this particular test hint is unsupported.
            assertEquals(12, V1Protocol.frames(message(MessageType.NAV_END)).single().size)
        }
        assertEquals(16, V1Protocol.navigation(hint, maximum = 16, maxTtl = 5).size)
    }

    @Test fun distanceBounds() {
        for (distance in listOf(-1L, 1_000_001L, 0x1_0000_0000L)) invalid { V1Protocol.navigation(hint.copy(distanceMetres = distance)) }
        for (distance in listOf(0L, 1_000_000L)) assertEquals(distance, V1Protocol.decodeNavigation(V1Protocol.navigation(hint.copy(distanceMetres = distance))).distanceMetres)
    }

    @Test fun maneuverAndExitBounds() {
        for (maneuver in listOf(-1, 8, 256)) invalid { V1Protocol.navigation(hint.copy(maneuver = maneuver)) }
        invalid { V1Protocol.navigation(hint.copy(roundaboutExit = 1)) }
        for (exit in listOf(0, 17, 256)) invalid { V1Protocol.navigation(hint.copy(maneuver = 3, roundaboutExit = exit)) }
        for (maneuver in 0..7) {
            val nav = hint.copy(maneuver = maneuver, roundaboutExit = if (maneuver == 3) 16 else 0)
            assertEquals(nav, V1Protocol.decodeNavigation(V1Protocol.navigation(nav)))
        }
    }

    @Test fun textByteLimitsNotCharacterLimits() {
        val nav = hint.copy(instruction = "ä".repeat(48), street = "é".repeat(32))
        assertEquals(nav, V1Protocol.decodeNavigation(V1Protocol.navigation(nav)))
        invalid { V1Protocol.navigation(nav.copy(instruction = nav.instruction + "a")) }
        invalid { V1Protocol.navigation(nav.copy(street = nav.street + "a")) }
        invalid { V1Protocol.navigation(hint, maximum = 15) }
        invalid { V1Protocol.navigation(hint, maximum = 193) }
    }

    @Test fun emptyWhitespaceControlsAndMalformedUnicode() {
        for (instruction in listOf("", " \t", "   ", "\u0000", "a\nb", "\u007f", "\u0085", "\ud800", "\udc00")) {
            invalid { V1Protocol.navigation(hint.copy(instruction = instruction)) }
        }
        for (street in listOf("\u0000", "\u001f", "\u009f", "\ud800")) invalid { V1Protocol.navigation(hint.copy(street = street)) }
    }

    @Test fun malformedNavigationBytePayloads() {
        val original = V1Protocol.navigation(hint)
        invalid { V1Protocol.decodeNavigation(original.copyOf(9)) }
        invalid { V1Protocol.decodeNavigation(original + 0) }
        for ((offset, value) in listOf(0 to 0, 6 to 8, 7 to 1, 8 to 0, 9 to 1, 10 to 0, 10 to 0xff)) {
            invalid { V1Protocol.decodeNavigation(original.copyOf().apply { this[offset] = value.toByte() }) }
        }
        val overlong = hint.copy(instruction = "ab")
        invalid { V1Protocol.decodeNavigation(V1Protocol.navigation(overlong).apply { this[10] = 0xc0.toByte(); this[11] = 0xaf.toByte() }) }
    }

    @Test fun utf8CodepointMaySpanFragments() {
        val nav = hint.copy(instruction = "abcde🚲 Straße", street = "École")
        val payload = V1Protocol.navigation(nav)
        val frames = V1Protocol.frames(message(MessageType.NAV_UPDATE, payload))
        // Emoji begins at offset 15: its bytes straddle the second/third payload fragments.
        assertEquals(0xf0, frames[1].last().toInt() and 255)
        val assembler = V1Reassembler(session)
        var result: V1Message? = null
        frames.forEachIndexed { index, bytes -> result = assembler.accept(bytes, index.toLong()) }
        assertEquals(nav, V1Protocol.decodeNavigation(result!!.payload))
    }

    @Test fun outOfOrderAndDuplicateFragmentsDiscardAssembly() {
        val frames = V1Protocol.frames(message(MessageType.NAV_UPDATE, ByteArray(24)))
        val assembler = V1Reassembler(session)
        assertNull(assembler.accept(frames[1], 0))
        assertNull(assembler.accept(frames[0], 1))
        assertNull(assembler.accept(frames[2], 2))
        assertNull(assembler.accept(frames[1], 3))
        assertNull(assembler.expiresAt)
        assembler.accept(frames[0], 4)
        assembler.accept(frames[1], 5)
        assertNull(assembler.accept(frames[1], 6))
        assertNull(assembler.accept(frames[2], 7))
    }

    @Test fun mismatchedMetadataDiscardsAssembly() {
        val frames = V1Protocol.frames(message(MessageType.NAV_UPDATE, ByteArray(16)))
        for ((offset, value) in listOf(3 to 0x11, 4 to 0x79, 8 to 2, 11 to 3)) {
            val assembler = V1Reassembler(session)
            assembler.accept(frames[0], 0)
            assertNull(assembler.accept(frames[1].copyOf().apply { this[offset] = value.toByte() }, 1))
            assertNull(assembler.accept(frames[1], 2))
        }
    }

    @Test fun assemblyTimeoutIsFromFirstFragment() {
        val frames = V1Protocol.frames(message(MessageType.NAV_UPDATE, ByteArray(24)))
        val assembler = V1Reassembler(session)
        assembler.accept(frames[0], 100)
        assembler.accept(frames[1], 2099)
        assertEquals(2100L, assembler.expiresAt)
        assertNull(assembler.accept(frames[2], 2100))
        assertNull(assembler.expiresAt)
    }

    @Test fun timerCanExpireWithoutAnotherFragment() {
        val assembler = V1Reassembler(session)
        val frames = V1Protocol.frames(message(MessageType.HELLO_ACK, helloPayload, 0))
        assembler.accept(frames[0], 0)
        assertFalse(assembler.expire(1999))
        assertTrue(assembler.expire(2000))
        assertFalse(assembler.expire(2001))
        assertNull(assembler.accept(frames[1], 2001))
    }

    @Test fun newFirstFragmentReplacesAndResetsDeadline() {
        val assembler = V1Reassembler(session)
        val old = V1Protocol.frames(message(MessageType.NAV_UPDATE, ByteArray(16), 1))
        val fresh = V1Protocol.frames(message(MessageType.NAV_UPDATE, ByteArray(16) { 7 }, 2))
        assembler.accept(old[0], 0)
        assembler.accept(fresh[0], 1900)
        assertEquals(3900L, assembler.expiresAt)
        assertFalse(assembler.expire(2000))
        assertArrayEquals(ByteArray(16) { 7 }, assembler.accept(fresh[1], 2100)!!.payload)
    }

    @Test fun malformedFrameAndExplicitResetClearAssembly() {
        val assembler = V1Reassembler(session)
        val frames = V1Protocol.frames(message(MessageType.HELLO_ACK, helloPayload, 0))
        assembler.accept(frames[0], 0)
        assertNull(assembler.accept(byteArrayOf(1), 1))
        assertNull(assembler.accept(frames[1], 2))
        assembler.accept(frames[0], 3)
        assembler.reset()
        assertNull(assembler.accept(frames[1], 4))
        assertNull(assembler.expiresAt)
    }

    @Test fun staleSessionCannotReassemble() {
        val assembler = V1Reassembler(session + 1)
        V1Protocol.frames(message(MessageType.HELLO_ACK, helloPayload, 0)).forEach {
            assertNull(assembler.accept(it, 0))
        }
    }

    @Test fun responseBeforeFinalCallbackIsRetained() {
        val exchange = V1Exchange(message(MessageType.NAV_UPDATE, V1Protocol.navigation(hint)))
        exchange.wroteFrame()
        assertTrue(exchange.accept(message(MessageType.ACK, hex("10 00"))))
        assertFalse(exchange.complete)
        exchange.wroteFrame()
        assertTrue(exchange.complete)
    }

    @Test fun writeCompletionAloneDoesNotMeanAppAcceptance() {
        val exchange = V1Exchange(message(MessageType.NAV_END))
        exchange.wroteFrame()
        assertTrue(exchange.writesComplete)
        assertFalse(exchange.complete)
        assertTrue(exchange.accept(message(MessageType.ACK, hex("11 00"))))
        assertTrue(exchange.complete)
    }

    @Test fun staleSequenceSessionTypeAndRequestAreIgnored() {
        val exchange = V1Exchange(message(MessageType.NAV_END, sequence = 2))
        val ack = message(MessageType.ACK, hex("11 00"), 2)
        assertFalse(exchange.accept(ack.copy(sequence = 1)))
        assertFalse(exchange.accept(ack.copy(sequence = 3)))
        assertFalse(exchange.accept(ack.copy(session = session + 1)))
        assertFalse(exchange.accept(ack.copy(type = MessageType.HELLO_ACK)))
        assertFalse(exchange.accept(ack.copy(payload = hex("10 00"))))
        assertNull(exchange.response)
    }

    @Test fun handshakeRequiresValidatedHelloAckAtSequenceZero() {
        val exchange = V1Exchange(message(MessageType.HELLO, sequence = 0))
        exchange.wroteFrame()
        assertFalse(exchange.complete)
        assertFalse(exchange.accept(message(MessageType.ACK, hex("10 00"), 0)))
        assertFalse(exchange.accept(message(MessageType.HELLO_ACK, helloPayload, 1)))
        invalid { exchange.accept(message(MessageType.HELLO_ACK, ByteArray(15), 0)) }
        assertFalse(exchange.complete)
        assertTrue(exchange.accept(message(MessageType.HELLO_ACK, helloPayload, 0)))
        assertTrue(exchange.complete)
    }

    @Test fun rejectionRetainedButNeverTurnedIntoOk() {
        val exchange = V1Exchange(message(MessageType.NAV_END))
        exchange.accept(message(MessageType.ACK, hex("11 05")))
        exchange.accept(message(MessageType.ACK, hex("11 00")))
        exchange.wroteFrame()
        assertEquals(AckStatus.BUSY, V1Protocol.commandAck(exchange.response!!.payload).status)
    }
}
