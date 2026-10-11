package de.pascal.pillarone.ble

import de.pascal.pillarone.protocol.MediaBridgeCodec
import de.pascal.pillarone.protocol.MediaSnapshot

internal class MediaMetadataQueue {
    private var latest = MediaSnapshot()
    private var pending = false
    var inFlight = false
        private set

    fun update(snapshot: MediaSnapshot) {
        latest = snapshot
        pending = true
    }

    fun begin(messageId: Int): List<ByteArray>? {
        if (!pending || inFlight) return null
        pending = false
        inFlight = true
        return MediaBridgeCodec.frames(latest, messageId)
    }

    fun complete() { inFlight = false }

    fun reject() {
        inFlight = false
        pending = true
    }

    fun reset() {
        inFlight = false
        pending = false
    }
}