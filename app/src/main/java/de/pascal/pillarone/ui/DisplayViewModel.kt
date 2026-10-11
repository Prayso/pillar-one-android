package de.pascal.pillarone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.pascal.pillarone.ble.AndroidBleController
import de.pascal.pillarone.ble.BleController
import de.pascal.pillarone.ble.BleDevice

class DisplayViewModel(application: Application) : AndroidViewModel(application) {
    private val controller: BleController = AndroidBleController(application)
    val state = controller.state

    fun refreshAvailability() = controller.refreshAvailability()
    fun onAppVisible() = controller.onAppVisible()
    fun isPairingUiActive() = controller.isPairingUiActive()
    fun onPairingDialogCovered() = controller.onPairingDialogCovered()
    fun startScan() = controller.startScan()
    fun stopScan() = controller.stopScan()
    fun connect(device: BleDevice) = controller.connect(device)
    fun disconnect() = controller.disconnect()
    fun sendTestTurn() = controller.sendTestTurn()
    fun sendOff() = controller.sendOff()

    fun onAppHidden() {
        controller.onAppHidden()
    }

    override fun onCleared() {
        controller.close()
    }
}
