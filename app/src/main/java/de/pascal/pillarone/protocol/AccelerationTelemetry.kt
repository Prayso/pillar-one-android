package de.pascal.pillarone.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AccelerationTelemetry(
    val bootId: Long,
    val runId: Long,
    val sampleTimeMs: Long,
    val speedKmh: Int,
    val valid: Boolean,
    val zeroReady: Boolean,
    val hundredReady: Boolean,
    val zeroDurationMs: Long,
    val hundredDurationMs: Long,
    val pageActive: Boolean = false,
    val zeroRunning: Boolean = false,
    val hundredRunning: Boolean = false,
)

object AccelerationTelemetryCodec {
    fun decode(payload: ByteArray): AccelerationTelemetry {
        if (payload.size != 24) throw ProtocolException("Invalid acceleration payload length")
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        fun u32() = buffer.int.toLong() and 0xffff_ffffL
        val boot = u32()
        val run = u32()
        val time = u32()
        val speed = buffer.short.toInt() and 65535
        val flags = buffer.get().toInt() and 255
        val reserved = buffer.get().toInt() and 255
        val zero = u32()
        val hundred = u32()
        if (boot == 0L || run == 0L || speed > 400 || flags and 63 != flags || reserved != 0 ||
            (flags and 1 == 0 && flags and 55 != 0) ||
            (if (flags and 2 != 0) zero !in 1..120_000 else zero != 0L) ||
            (if (flags and 4 != 0) hundred !in 1..120_000 else hundred != 0L)
        ) throw ProtocolException("Invalid acceleration sample")
        return AccelerationTelemetry(boot, run, time, speed, flags and 1 != 0,
            flags and 2 != 0, flags and 4 != 0, zero, hundred,
            flags and 8 != 0, flags and 16 != 0, flags and 32 != 0)
    }
}
