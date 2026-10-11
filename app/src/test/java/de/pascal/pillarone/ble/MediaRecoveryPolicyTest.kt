package de.pascal.pillarone.ble

import org.junit.Assert.*
import org.junit.Test

class MediaRecoveryPolicyTest {
    @Test fun rejectedAttemptWaitsForHeartbeatThenSuccessResets() {
        val policy = MediaRecoveryPolicy()
        policy.reject()
        assertFalse(policy.canAttempt(true, true))
        policy.heartbeat()
        assertTrue(policy.canAttempt(true, true))
        policy.reset()
        assertEquals(0, policy.failures)
    }

    @Test fun absentCharacteristicOrCccdIsPermanentUntilReconnect() {
        val policy = MediaRecoveryPolicy()
        policy.unsupported()
        repeat(10) { policy.heartbeat() }
        assertFalse(policy.canAttempt(true, true))
        policy.reset()
        assertTrue(policy.canAttempt(true, true))
    }

    @Test fun allowsOnlyThreeHeartbeatRetries() {
        val policy = MediaRecoveryPolicy()
        repeat(3) {
            policy.reject()
            assertFalse(policy.canAttempt(true, true))
            policy.heartbeat()
            assertTrue(policy.canAttempt(true, true))
        }
        policy.reject()
        repeat(10) { policy.heartbeat() }
        assertFalse(policy.canAttempt(true, true))
    }

    @Test fun disabledOrDisconnectedNeverRetries() {
        val policy = MediaRecoveryPolicy()
        policy.reject()
        policy.heartbeat()
        assertFalse(policy.canAttempt(false, true))
        assertFalse(policy.canAttempt(true, false))
    }

    @Test fun securityRejectionRequiresReconnectNotHeartbeatRetry() {
        val policy = MediaRecoveryPolicy()
        policy.reject(terminal = true)
        repeat(10) { policy.heartbeat() }
        assertFalse(policy.canAttempt(true, true))
        policy.reset()
        assertTrue(policy.canAttempt(true, true))
    }

    @Test fun reconnectResetsExhaustedBudget() {
        val policy = MediaRecoveryPolicy()
        repeat(4) { policy.reject(); policy.heartbeat() }
        assertFalse(policy.canAttempt(true, true))
        policy.reset()
        assertTrue(policy.canAttempt(true, true))
        assertEquals(0, policy.failures)
    }
}