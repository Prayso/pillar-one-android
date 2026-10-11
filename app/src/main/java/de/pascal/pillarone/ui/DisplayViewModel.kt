package de.pascal.pillarone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.pascal.pillarone.PillarApplication
import de.pascal.pillarone.ble.BleDevice
import de.pascal.pillarone.ble.ConnectionPhase
import de.pascal.pillarone.protocol.MenuConfiguration
import de.pascal.pillarone.media.PhoneMediaSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DisplayViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = (application as PillarApplication).ble
    val state = controller.state
    val media = PhoneMediaSessions.state
    private val mutableMenuEditor = MutableStateFlow(MenuEditorState())
    val menuEditor = mutableMenuEditor.asStateFlow()
    private val deviceEditors = mutableMapOf<String, MenuEditorState>()

    init {
        viewModelScope.launch {
            var lastMenu: MenuConfiguration? = null
            state.collect { ble ->
                val configuration = ble.menu
                val deviceId = ble.capabilities?.deviceId
                if (deviceId != null && mutableMenuEditor.value.deviceId != deviceId) {
                    mutableMenuEditor.value.deviceId?.let { deviceEditors[it] = mutableMenuEditor.value }
                    mutableMenuEditor.value = deviceEditors[deviceId] ?: MenuEditorState(deviceId = deviceId)
                }
                if (configuration != null && deviceId != null && configuration !== lastMenu) {
                    mutableMenuEditor.value = mutableMenuEditor.value.loaded(deviceId, configuration)
                }
                lastMenu = configuration
            }
        }
    }

    fun loadMenuIfNeeded() {
        val ble = state.value
        val editor = mutableMenuEditor.value
        if (ble.phase == ConnectionPhase.READY && ble.capabilities?.supportsMenu == true &&
            !ble.busy && (editor.source == null || editor.deviceId != ble.capabilities.deviceId ||
                (!editor.dirty && ble.menu == null))
        ) controller.readMenu()
    }

    fun readMenu() = controller.readMenu()
    fun moveMenuPage(id: Int, direction: Int) {
        mutableMenuEditor.value = mutableMenuEditor.value.move(id, direction)
    }
    fun setMenuPageVisible(id: Int, visible: Boolean) {
        mutableMenuEditor.value = mutableMenuEditor.value.visibility(id, visible)
    }
    fun setMenuStartupPage(id: Int) {
        mutableMenuEditor.value = mutableMenuEditor.value.startup(id)
    }
    fun saveMenu() {
        val editor = mutableMenuEditor.value
        if (editor.deviceId == state.value.capabilities?.deviceId && editor.dirty) {
            editor.draft?.let(controller::saveMenu)
        }
    }

    fun refreshAvailability() = controller.refreshAvailability()
    fun onAppVisible() {
        PhoneMediaSessions.refresh(getApplication())
        controller.onAppVisible()
    }
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

}
