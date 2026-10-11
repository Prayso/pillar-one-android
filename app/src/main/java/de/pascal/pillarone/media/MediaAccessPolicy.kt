package de.pascal.pillarone.media

enum class MediaAccessDecision {
    PERMISSION_MISSING, LISTENER_DISCONNECTED, NO_SESSION, SESSION_AVAILABLE;

    companion object {
        fun decide(granted: Boolean, connected: Boolean, hasSession: Boolean): MediaAccessDecision = when {
            !granted -> PERMISSION_MISSING
            !connected -> LISTENER_DISCONNECTED
            !hasSession -> NO_SESSION
            else -> SESSION_AVAILABLE
        }
    }
}

internal class ListenerRebindPolicy {
    var attempts = 0
        private set
    private var nextRequestAt = 0L

    fun request(granted: Boolean, connected: Boolean, now: Long): Boolean {
        if (!granted || connected) {
            reset()
            return false
        }
        if (attempts >= 3 || now < nextRequestAt) return false
        attempts++
        nextRequestAt = now + 15_000
        return true
    }

    fun reset() {
        attempts = 0
        nextRequestAt = 0
    }
}