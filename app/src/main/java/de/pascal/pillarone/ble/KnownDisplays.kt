package de.pascal.pillarone.ble

internal fun mergeKnownDevices(scanned: List<BleDevice>, known: List<BleDevice>): List<BleDevice> {
    val devices = scanned.filter { it.rssi != null }.associateByTo(linkedMapOf()) {
        it.address
    }.mapValuesTo(linkedMapOf()) { (_, device) ->
        device.copy(paired = false, systemConnected = false)
    }
    known.forEach { device ->
        devices[device.address] = device.copy(rssi = devices[device.address]?.rssi)
    }
    return devices.values.sortedWith(
        compareByDescending<BleDevice> { it.systemConnected }.thenByDescending { it.paired },
    )
}

internal fun restorationCandidate(devices: List<BleDevice>, selectedAddress: String?): BleDevice? {
    devices.firstOrNull {
        it.address == selectedAddress && (it.paired || it.systemConnected)
    }?.let { return it }
    return devices.filter { it.systemConnected }.singleOrNull()
}
