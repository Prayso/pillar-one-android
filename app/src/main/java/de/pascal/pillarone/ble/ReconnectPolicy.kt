package de.pascal.pillarone.ble

internal class ReconnectPolicy {
    var target: BleDevice? = null
        private set
    var attempts: Int = 0
        private set

    fun established(device: BleDevice) {
        target = device
        attempts = 0
    }

    fun stop() {
        target = null
        attempts = 0
    }

    fun nextDelay(issue: BleIssue?): Long? {
        if (target == null || issue !in setOf(
                BleIssue.CONNECTION_LOST, BleIssue.CONNECTION_FAILED, BleIssue.CONNECTION_TIMEOUT,
            ) || attempts >= DELAYS.size
        ) return null
        return DELAYS[attempts++]
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private val DELAYS = longArrayOf(2_000, 5_000, 10_000)
    }
}
