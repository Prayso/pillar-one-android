package de.pascal.pillarone.ble

import org.junit.Assert.*
import org.junit.Test

class BleLifetimePolicyTest {
    @Test fun backgroundCannotStartButActiveServiceCanReconnect() {
        val policy = BleLifetimePolicy()
        assertFalse(policy.canStartService)
        policy.visible = true
        assertTrue(policy.canStartService)
        policy.serviceActive = true
        policy.visible = false
        assertFalse(policy.canStartService)
        assertTrue(policy.canReconnect)
        assertFalse(policy.canRestore)
    }

    @Test fun explicitDisconnectBlocksResumeAndPendingServiceUntilUserConnects() {
        val policy = BleLifetimePolicy()
        policy.visible = true
        policy.serviceActive = true
        policy.explicitDisconnect()
        policy.visible = false
        policy.visible = true
        assertFalse(policy.canStartService)
        assertFalse(policy.canReconnect)
        assertFalse(policy.canRestore)
        policy.serviceActive = false
        assertFalse(policy.canRestore)
        policy.explicitConnect()
        assertTrue(policy.canStartService)
        assertTrue(policy.canRestore)
    }

    @Test fun serviceEndNeverRestoresInBackground() {
        val policy = BleLifetimePolicy()
        policy.serviceActive = true
        policy.serviceActive = false
        assertFalse(policy.canReconnect)
        assertFalse(policy.canRestore)
        policy.visible = true
        assertTrue(policy.canRestore)
    }

    @Test fun visibleStartSurvivesHomeBeforeServiceAttaches() {
        val policy = BleLifetimePolicy()
        policy.visible = true
        policy.startPending = true
        policy.visible = false
        assertTrue(policy.keepSessionWhenHidden)
        assertFalse(policy.canStartService)
        assertFalse(policy.canReconnect)
        policy.serviceActive = true
        policy.startPending = false
        assertTrue(policy.keepSessionWhenHidden)
        assertTrue(policy.canReconnect)
    }

    @Test fun cancelPendingStartImmediatelyDisallowsContinuation() {
        val policy = BleLifetimePolicy()
        policy.startPending = true
        policy.explicitDisconnect()
        assertFalse(policy.startPending)
        assertFalse(policy.keepSessionWhenHidden)
        policy.serviceActive = true
        assertFalse(policy.canReconnect)
    }

    @Test fun backgroundRetriesUseTheExistingBoundedBudget() {
        val lifetime = BleLifetimePolicy().apply { serviceActive = true }
        val reconnect = ReconnectPolicy()
        reconnect.established(BleDevice("00:11:22:33:44:55", "Pillar One", paired = true))
        assertTrue(lifetime.canReconnect)
        assertEquals(2_000L, reconnect.nextDelay(BleIssue.CONNECTION_LOST))
        assertEquals(5_000L, reconnect.nextDelay(BleIssue.CONNECTION_FAILED))
        assertEquals(10_000L, reconnect.nextDelay(BleIssue.CONNECTION_TIMEOUT))
        assertNull(reconnect.nextDelay(BleIssue.CONNECTION_FAILED))
        lifetime.serviceActive = false
        assertFalse(lifetime.canReconnect)
        assertFalse(lifetime.canRestore)
    }

    @Test fun disconnectDuringBackgroundRetryClearsTargetAndBlocksResume() {
        val lifetime = BleLifetimePolicy().apply { serviceActive = true }
        val reconnect = ReconnectPolicy()
        reconnect.established(BleDevice("00:11:22:33:44:55", "Pillar One", paired = true))
        reconnect.nextDelay(BleIssue.CONNECTION_LOST)
        lifetime.explicitDisconnect()
        reconnect.stop()
        lifetime.visible = true
        assertFalse(lifetime.canReconnect)
        assertFalse(lifetime.canRestore)
        assertNull(reconnect.nextDelay(BleIssue.CONNECTION_FAILED))
    }

    @Test fun restoredUserDisconnectRequiresAnotherExplicitConnect() {
        val policy = BleLifetimePolicy(initiallyDisconnected = true)
        policy.visible = true
        assertFalse(policy.canRestore)
        assertFalse(policy.canStartService)
        policy.explicitConnect()
        assertTrue(policy.canRestore)
        assertTrue(policy.canStartService)
    }
}