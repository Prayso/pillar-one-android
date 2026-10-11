package de.pascal.pillarone.ble

internal class MediaRecoveryPolicy {
    var failures = 0
        private set
    var permanent = false
        private set
    var waitingForHeartbeat = false
        private set

    fun canAttempt(desired: Boolean, connected: Boolean): Boolean =
        desired && connected && !permanent && !waitingForHeartbeat && failures <= MAX_RETRIES

    fun reject(terminal: Boolean = false) {
        failures++
        waitingForHeartbeat = true
        if (terminal) permanent = true
    }

    fun unsupported() { permanent = true }

    fun heartbeat() {
        if (failures <= MAX_RETRIES) waitingForHeartbeat = false
    }

    fun reset() {
        failures = 0
        permanent = false
        waitingForHeartbeat = false
    }

    companion object { const val MAX_RETRIES = 3 }
}