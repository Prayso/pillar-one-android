package de.pascal.pillarone.analysis

import de.pascal.pillarone.protocol.AccelerationTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelerationRecordingPolicyTest {
    private fun sample(
        time: Long,
        run: Long = 1,
        running: Boolean = false,
        ready: Boolean = false,
        valid: Boolean = true,
        duration: Long = 0,
        boot: Long = 1,
    ) = AccelerationTelemetry(boot, run, time, 100, valid, ready, false,
        duration, 0, pageActive = true, zeroRunning = running)

    @Test fun observedRunningFinishesOnFirstReadyHeartbeatAndOnlyOnce() {
        val policy = AutomaticCarRunPolicy(1_000)
        assertTrue(policy.sample(sample(100, running = true), 1_500, "car").isEmpty())
        val result = policy.sample(sample(600, ready = true, duration = 500), 2_000, "car").single()
        assertEquals(1_500, result.startElapsedMs)
        assertEquals(2_000, result.endElapsedMs)
        assertTrue(policy.sample(sample(600, ready = true, duration = 500), 2_500, "car").isEmpty())
    }

    @Test fun cachedReadyWithoutObservedAttemptNeverCreatesRecord() {
        val policy = AutomaticCarRunPolicy(1_000)
        assertTrue(policy.sample(sample(600, ready = true, duration = 500), 2_000, "car").isEmpty())
        assertTrue(policy.sample(sample(700, ready = true, duration = 500), 2_100, "car").isEmpty())
    }

    @Test fun firstArrivalTimestampAnchorDoesNotMoveWhenReadyAppearsOnDuplicateHeartbeat() {
        val policy = AutomaticCarRunPolicy(1_000)
        policy.sample(sample(100, running = true), 1_500, "car")
        policy.sample(sample(600, running = true), 2_000, "car")
        val result = policy.sample(sample(600, ready = true, duration = 500), 2_700, "car").single()
        assertEquals(1_500, result.startElapsedMs)
        assertEquals(2_000, result.endElapsedMs)
    }

    @Test fun sameTimestampHeartbeatsDoNotRefreshSpeedOrRestoreStaleBaseline() {
        val policy = AutomaticCarRunPolicy(1_000)
        policy.sample(sample(100, running = true), 1_500, "car")
        policy.sample(sample(100, running = true), 2_500, "car")
        policy.sample(sample(100, running = true), 3_500, "car")
        policy.sample(sample(100, running = true), 4_500, "car")
        assertFalse(policy.isSpeedFresh(4_501))
        policy.resetContinuity()
        assertTrue(policy.sample(sample(100, ready = true, duration = 500), 4_600, "car").isEmpty())
        assertFalse(policy.isSpeedFresh(4_600))
    }

    @Test fun invalidSampleAndNewRunDiscardPreviousAttempt() {
        val policy = AutomaticCarRunPolicy(1_000)
        policy.sample(sample(100, running = true), 1_500, "car")
        policy.sample(sample(200, valid = false), 1_600, "car")
        assertTrue(policy.sample(sample(600, ready = true, duration = 500), 2_000, "car").isEmpty())
        policy.sample(sample(700, running = true), 2_100, "car")
        assertTrue(policy.sample(sample(800, run = 2, ready = true, duration = 500), 2_200, "car").isEmpty())
    }

    @Test fun unsignedTimestampWrapIsContinuous() {
        val policy = AutomaticCarRunPolicy(1_000)
        policy.sample(sample(0xffff_ff00L, running = true), 1_500, "car")
        val result = policy.sample(sample(244, ready = true, duration = 500), 2_000, "car").single()
        assertEquals(500, result.durationMs)
    }

    @Test fun runsStartedBeforePageWasArmedAreDiscarded() {
        val policy = AutomaticCarRunPolicy(1_800)
        policy.sample(sample(100, running = true), 1_900, "car")
        assertTrue(policy.sample(sample(200, ready = true, duration = 500), 2_000, "car").isEmpty())
    }

    @Test fun backwardsTimestampAndHeartbeatGapResetAttempt() {
        val policy = AutomaticCarRunPolicy(1_000)
        policy.sample(sample(1_000, running = true), 1_500, "car")
        assertTrue(policy.sample(sample(500, ready = true, duration = 500), 2_000, "car").isEmpty())
        policy.sample(sample(600, running = true), 2_100, "car")
        assertTrue(policy.sample(sample(1_100, ready = true, duration = 500), 6_000, "car").isEmpty())
    }

    @Test fun bootChangeDoesNotCarryAttemptAcrossDeviceRestart() {
        val policy = AutomaticCarRunPolicy(1_000)
        policy.sample(sample(100, running = true), 1_500, "car")
        assertTrue(policy.sample(sample(600, boot = 2, ready = true, duration = 500), 2_000, "car").isEmpty())
    }

    @Test fun pairingRequiresBothEndpointsAndSameInterval() {
        val car = CompletedMeasurement("car", AccelerationInterval.ZERO_TO_HUNDRED,
            MeasurementSource.CAR, 10_000, 20_000, 10_000, null)
        val gps = car.copy(key = "gps", source = MeasurementSource.GPS,
            startElapsedMs = 11_500, endElapsedMs = 21_500)
        assertTrue(AccelerationRecordingRuntime.matchesRun(car, gps))
        assertFalse(AccelerationRecordingRuntime.matchesRun(car, gps.copy(endElapsedMs = 22_001)))
        assertFalse(AccelerationRecordingRuntime.matchesRun(car, gps.copy(startElapsedMs = 12_001)))
        assertFalse(AccelerationRecordingRuntime.matchesRun(car,
            gps.copy(interval = AccelerationInterval.HUNDRED_TO_TWO_HUNDRED)))
    }
}
