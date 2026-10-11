package de.pascal.pillarone.media

import org.junit.Assert.*
import org.junit.Test

class MediaAccessPolicyTest {
    @Test fun missingAccessNeverClaimsConnectedSession() {
        assertEquals(MediaAccessDecision.PERMISSION_MISSING, MediaAccessDecision.decide(false, true, true))
    }

    @Test fun permissionIsNotListenerConnection() {
        assertEquals(MediaAccessDecision.LISTENER_DISCONNECTED, MediaAccessDecision.decide(true, false, true))
    }

    @Test fun listenerConnectionIsNotPlayerSession() {
        assertEquals(MediaAccessDecision.NO_SESSION, MediaAccessDecision.decide(true, true, false))
        assertEquals(MediaAccessDecision.SESSION_AVAILABLE, MediaAccessDecision.decide(true, true, true))
    }

    @Test fun rebindIsSpacedAndBoundedAcrossFrequentRefreshes() {
        val policy = ListenerRebindPolicy()
        assertTrue(policy.request(true, false, 0))
        assertFalse(policy.request(true, false, 5_000))
        assertFalse(policy.request(true, false, 10_000))
        assertTrue(policy.request(true, false, 15_000))
        assertTrue(policy.request(true, false, 30_000))
        assertFalse(policy.request(true, false, 45_000))
        assertFalse(policy.request(true, false, 1_000_000))
        assertEquals(3, policy.attempts)
    }

    @Test fun connectionResetsRebindAndDoesNotRequestAgain() {
        val policy = ListenerRebindPolicy()
        policy.request(true, false, 0)
        assertFalse(policy.request(true, true, 1))
        assertEquals(0, policy.attempts)
        assertTrue(policy.request(true, false, 2))
    }

    @Test fun deniedPermissionDoesNotRebindAndResetsBudget() {
        val policy = ListenerRebindPolicy()
        policy.request(true, false, 0)
        assertFalse(policy.request(false, false, 20_000))
        assertEquals(0, policy.attempts)
        assertTrue(policy.request(true, false, 20_001))
    }
}