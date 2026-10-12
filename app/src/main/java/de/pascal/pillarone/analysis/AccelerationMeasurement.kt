package de.pascal.pillarone.analysis

import java.util.UUID

enum class MeasurementSource { GPS, CAR }
enum class AccelerationInterval(val fromKmh: Int, val toKmh: Int) {
    ZERO_TO_HUNDRED(0, 100), HUNDRED_TO_TWO_HUNDRED(100, 200),
}

data class MeasurementLocation(val latitude: Double, val longitude: Double, val accuracyMetres: Float)
data class CompletedMeasurement(
    val key: String,
    val interval: AccelerationInterval,
    val source: MeasurementSource,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val durationMs: Long,
    val location: MeasurementLocation?,
    val deviceId: String? = null,
    val endLocation: MeasurementLocation? = null,
)

data class AccelerationRecord(
    val id: Long,
    val interval: AccelerationInterval,
    val startedAt: Long,
    val zoneId: String,
    val location: MeasurementLocation?,
    val gpsDurationMs: Long?,
    val carDurationMs: Long?,
    val deviceId: String?,
    val endLocation: MeasurementLocation? = null,
)

class GpsAccelerationDetector {
    private data class Sample(val speed: Double, val time: Long, val location: MeasurementLocation)
    private var previous: Sample? = null
    private var zeroStart: Sample? = null
    private var hundredStart: Sample? = null

    fun reset() {
        previous = null
        zeroStart = null
        hundredStart = null
    }

    fun sample(speedKmh: Double, elapsedMs: Long, location: MeasurementLocation): List<CompletedMeasurement> {
        if (!speedKmh.isFinite() || speedKmh !in 0.0..400.0) {
            reset()
            return emptyList()
        }
        val current = Sample(speedKmh, elapsedMs, location)
        val old = previous
        if (old == null || elapsedMs - old.time !in 1..2_000) {
            reset()
            previous = current
            return emptyList()
        }
        val completed = mutableListOf<CompletedMeasurement>()
        if (speedKmh <= 1.0 || speedKmh < old.speed - 3.0 ||
            zeroStart?.let { elapsedMs - it.time > 120_000 } == true ||
            hundredStart?.let { elapsedMs - it.time > 120_000 } == true
        ) {
            zeroStart = null
            hundredStart = null
        }
        if (old.speed <= 1.0 && speedKmh > 1.0) zeroStart = old
        fun crossing(threshold: Int): Sample {
            val fraction = (threshold - old.speed) / (speedKmh - old.speed)
            return Sample(threshold.toDouble(), old.time + ((elapsedMs - old.time) * fraction).toLong(), location)
        }
        fun finish(interval: AccelerationInterval, start: Sample?, end: Sample) {
            if (start != null && end.time > start.time) completed += CompletedMeasurement(
                UUID.randomUUID().toString(), interval, MeasurementSource.GPS,
                start.time, end.time, end.time - start.time, start.location,
                endLocation = end.location,
            )
        }
        if (old.speed < 100 && speedKmh >= 100) {
            val crossing = crossing(100)
            finish(AccelerationInterval.ZERO_TO_HUNDRED, zeroStart, crossing)
            zeroStart = null
            hundredStart = crossing
        }
        if (old.speed < 200 && speedKmh >= 200) {
            finish(AccelerationInterval.HUNDRED_TO_TWO_HUNDRED, hundredStart, crossing(200))
            hundredStart = null
        }
        previous = current
        return completed
    }
}
