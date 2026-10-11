package de.pascal.pillarone.ble

import de.pascal.pillarone.protocol.MediaSnapshot
import org.junit.Assert.*
import org.junit.Test

class MediaMetadataQueueTest {
    @Test fun partialFrameRejectionReleasesBusyAndRetriesLatestAtIndexZero() {
        val queue = MediaMetadataQueue()
        val recovery = MediaRecoveryPolicy()
        queue.update(MediaSnapshot("Old long title", "Artist"))
        assertTrue(queue.begin(0)!!.size > 1)
        assertTrue(queue.inFlight)
        queue.update(MediaSnapshot("Latest", "New"))
        assertNull(queue.begin(1))
        queue.reject()
        recovery.reject()
        assertFalse(queue.inFlight)
        assertFalse(recovery.canAttempt(true, true))
        recovery.heartbeat()
        assertTrue(recovery.canAttempt(true, true))
        val retried = queue.begin(1)!!
        assertEquals(0, retried.first()[4].toInt())
        assertEquals("LatestNew", retried.flatMap { it.drop(8) }.toByteArray().toString(Charsets.UTF_8))
        queue.complete()
        recovery.reset()
        assertFalse(queue.inFlight)
        assertNull(queue.begin(2))
    }

    @Test fun completionPreservesNewSnapshotQueuedWhileWriting() {
        val queue = MediaMetadataQueue()
        queue.update(MediaSnapshot("First"))
        queue.begin(0)
        queue.update(MediaSnapshot("Second"))
        queue.complete()
        assertNotNull(queue.begin(1))
    }

    @Test fun disconnectDiscardsPendingAndInFlightMetadata() {
        val queue = MediaMetadataQueue()
        queue.update(MediaSnapshot("First"))
        queue.begin(0)
        queue.reject()
        queue.reset()
        assertFalse(queue.inFlight)
        assertNull(queue.begin(1))
    }
}