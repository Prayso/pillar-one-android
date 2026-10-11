package de.pascal.pillarone.ble

import org.junit.Assert.*
import org.junit.Test

class ReconnectPolicyTest {
    private val device = BleDevice("00:11:22:33:44:55", "Pillar One", paired = true)

    @Test fun neverRetriesBeforeAnEstablishedSession() {
        assertNull(ReconnectPolicy().nextDelay(BleIssue.CONNECTION_FAILED))
    }

    @Test fun delaysIncreaseAndStopAfterThreeAttempts() {
        val policy = ReconnectPolicy()
        policy.established(device)
        assertEquals(2_000L, policy.nextDelay(BleIssue.CONNECTION_LOST))
        assertEquals(5_000L, policy.nextDelay(BleIssue.CONNECTION_FAILED))
        assertEquals(10_000L, policy.nextDelay(BleIssue.CONNECTION_TIMEOUT))
        assertNull(policy.nextDelay(BleIssue.CONNECTION_FAILED))
        assertEquals(3, policy.attempts)
    }

    @Test fun securityProtocolAndWriteFailuresDoNotRetry() {
        BleIssue.entries.filterNot {
            it in setOf(BleIssue.CONNECTION_LOST, BleIssue.CONNECTION_FAILED, BleIssue.CONNECTION_TIMEOUT)
        }.forEach { issue ->
            val policy = ReconnectPolicy()
            policy.established(device)
            assertNull(issue.name, policy.nextDelay(issue))
            assertEquals(0, policy.attempts)
        }
    }

    @Test fun explicitStopClearsTargetAndBudget() {
        val policy = ReconnectPolicy()
        policy.established(device)
        policy.nextDelay(BleIssue.CONNECTION_LOST)
        policy.stop()
        assertNull(policy.target)
        assertNull(policy.nextDelay(BleIssue.CONNECTION_FAILED))
        assertEquals(0, policy.attempts)
    }

    @Test fun successfulHandshakeResetsBudget() {
        val policy = ReconnectPolicy()
        policy.established(device)
        repeat(3) { policy.nextDelay(BleIssue.CONNECTION_FAILED) }
        policy.established(device)
        assertEquals(2_000L, policy.nextDelay(BleIssue.CONNECTION_LOST))
    }
}
