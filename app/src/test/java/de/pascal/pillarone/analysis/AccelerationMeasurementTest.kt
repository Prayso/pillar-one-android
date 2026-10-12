package de.pascal.pillarone.analysis

import org.junit.Assert.*
import org.junit.Test

class AccelerationMeasurementTest {
    private val location = MeasurementLocation(50.0, 8.0, 5f)

    @Test fun gpsDetectsBothIntervalsWithInterpolatedCrossings() {
        val detector = GpsAccelerationDetector()
        val results = listOf(0.0, 10.0, 60.0, 110.0, 160.0, 210.0)
            .flatMapIndexed { index, speed -> detector.sample(speed, index * 1_000L, location) }
        assertEquals(2, results.size)
        assertEquals(AccelerationInterval.ZERO_TO_HUNDRED, results[0].interval)
        assertEquals(2_800L, results[0].durationMs)
        assertEquals(AccelerationInterval.HUNDRED_TO_TWO_HUNDRED, results[1].interval)
        assertEquals(2_000L, results[1].durationMs)
        assertEquals(location, results[0].location)
        assertEquals(location, results[0].endLocation)
        assertTrue(detector.sample(220.0, 6_000, location).isEmpty())
    }

    @Test fun gpsRetainsStationaryStartAndTargetFix() {
        val detector = GpsAccelerationDetector()
        val target = MeasurementLocation(50.002, 8.003, 8f)
        detector.sample(0.0, 0, location)
        detector.sample(10.0, 1_000, MeasurementLocation(50.001, 8.001, 6f))
        val result = detector.sample(110.0, 2_000, target).single()
        assertEquals(location, result.location)
        assertEquals(target, result.endLocation)
        assertEquals(0L, result.startElapsedMs)
    }

    @Test fun gapsInvalidSamplesAndDecelerationDiscardIncompleteGpsRuns() {
        for (fault in listOf("gap", "invalid", "deceleration")) {
            val detector = GpsAccelerationDetector()
            detector.sample(0.0, 0, location)
            detector.sample(10.0, 1_000, location)
            detector.sample(60.0, 2_000, location)
            when (fault) {
                "gap" -> assertTrue(detector.sample(110.0, 5_000, location).isEmpty())
                "invalid" -> {
                    detector.sample(Double.NaN, 2_500, location)
                    assertTrue(detector.sample(110.0, 3_000, location).isEmpty())
                }
                else -> {
                    detector.sample(20.0, 2_500, location)
                    assertTrue(detector.sample(110.0, 3_000, location).isEmpty())
                }
            }
        }
    }

    @Test fun gpsStartingAboveZeroDoesNotInventZeroToHundred() {
        val detector = GpsAccelerationDetector()
        detector.sample(80.0, 0, location)
        assertTrue(detector.sample(110.0, 1_000, location).isEmpty())
        val result = detector.sample(210.0, 2_000, location).single()
        assertEquals(AccelerationInterval.HUNDRED_TO_TWO_HUNDRED, result.interval)
    }

}
