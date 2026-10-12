package de.pascal.pillarone.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AccelerationTelemetryTest {
    private fun payload(flags: Int = 7, zero: Int = 4_500, hundred: Int = 8_000) =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x12345678).putInt(2).putInt(50_000).putShort(200)
            .put(flags.toByte()).put(0).putInt(zero).putInt(hundred).array()

    @Test fun exactWireShapeAndFragmentedUnsolicitedMessage() {
        val bytes = payload()
        val frames = V1Protocol.frames(V1Message(MessageType.ACCELERATION_SAMPLE, 5, 123, bytes))
        assertEquals(3, frames.size)
        val assembler = V1Reassembler(5)
        assertNull(assembler.accept(frames[0], 0))
        assertNull(assembler.accept(frames[1], 1))
        val message = assembler.accept(frames[2], 2)!!
        val sample = AccelerationTelemetryCodec.decode(message.payload)
        assertEquals(0x12345678L, sample.bootId)
        assertEquals(2L, sample.runId)
        assertEquals(50_000L, sample.sampleTimeMs)
        assertEquals(200, sample.speedKmh)
        assertTrue(sample.valid && sample.zeroReady && sample.hundredReady)
        assertEquals(4_500L, sample.zeroDurationMs)
        assertEquals(8_000L, sample.hundredDurationMs)
    }

    @Test fun invalidWireDataIsRejected() {
        val cases = listOf(payload().copyOf(23), payload(flags = 64), payload(flags = 2),
            payload(zero = 0), payload(hundred = 120_001),
            payload(flags = 1), payload().also { it[15] = 1 },
            payload().also { (0..3).forEach { index -> it[index] = 0 } })
        cases.forEach {
            assertThrows(ProtocolException::class.java) { AccelerationTelemetryCodec.decode(it) }
        }

        assertFalse(AccelerationTelemetryCodec.decode(payload(0, 0, 0)).valid)
    }

    @Test fun pageStatusCanBeActiveWithoutValidSpeed() {
        val sample = AccelerationTelemetryCodec.decode(payload(8, 0, 0))
        assertTrue(sample.pageActive)
        assertFalse(sample.valid)
        assertFalse(sample.zeroRunning)
        val running = AccelerationTelemetryCodec.decode(payload(57, 0, 0))
        assertTrue(running.pageActive && running.zeroRunning && running.hundredRunning)
        assertThrows(ProtocolException::class.java) { AccelerationTelemetryCodec.decode(payload(24, 0, 0)) }
        assertTrue(DeviceCapabilities("device", 31, 192, 30).supportsAutomaticAcceleration)
        assertFalse(DeviceCapabilities("device", 15, 192, 30).supportsAutomaticAcceleration)
    }

    @Test fun telemetryCannotCompleteACommandExchange() {
        val exchange = V1Exchange(V1Message(MessageType.NAV_END, 5, 123, byteArrayOf()))
        assertFalse(exchange.accept(V1Message(MessageType.ACCELERATION_SAMPLE, 5, 123, payload())))
    }
}
