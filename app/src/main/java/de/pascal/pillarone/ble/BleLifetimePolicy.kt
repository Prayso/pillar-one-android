package de.pascal.pillarone.ble

internal class BleLifetimePolicy(initiallyDisconnected: Boolean = false) {
    var visible = false
    var serviceActive = false
    var startPending = false
    var userDisconnected = initiallyDisconnected
        private set

    val canStartService get() = visible && !userDisconnected
    val canReconnect get() = serviceActive && !userDisconnected
    val canRestore get() = visible && !serviceActive && !startPending && !userDisconnected
    val keepSessionWhenHidden get() = (serviceActive || startPending) && !userDisconnected

    fun explicitConnect() {
        userDisconnected = false
    }

    fun explicitDisconnect() {
        userDisconnected = true
        startPending = false
    }
}