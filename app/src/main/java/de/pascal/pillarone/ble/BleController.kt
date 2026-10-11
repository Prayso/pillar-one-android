package de.pascal.pillarone.ble

import kotlinx.coroutines.flow.StateFlow
import de.pascal.pillarone.protocol.AckStatus
import de.pascal.pillarone.protocol.DeviceCapabilities
import de.pascal.pillarone.protocol.MenuConfiguration

enum class ConnectionPhase { IDLE, CONNECTING, DISCOVERING, BONDING, SUBSCRIBING, HANDSHAKING, READY, RECONNECTING, DISCONNECTED, INCOMPATIBLE, ERROR }
enum class ProtocolMode { LEGACY, V1 }
enum class CommandDelivery { BLE_WRITTEN, APP_ACCEPTED }
enum class MediaBridgeStatus { IDLE, MISSING_CHARACTERISTIC, UNSUPPORTED, SUBSCRIBING, RETRY_WAIT, FAILED, ACTIVE }

enum class BleIssue {
    PERMISSION_REQUIRED, BLUETOOTH_UNAVAILABLE, BLUETOOTH_DISABLED, LOCATION_DISABLED,
    SCAN_FAILED, CONNECTION_FAILED, CONNECTION_TIMEOUT, SERVICE_MISSING,
    CHARACTERISTIC_MISSING, WRITE_UNSUPPORTED, WRITE_FAILED, WRITE_TIMEOUT,
    CONNECTION_LOST, BUSY,
    NOTIFY_UNSUPPORTED, SUBSCRIBE_FAILED, SUBSCRIBE_TIMEOUT, PROTOCOL_INVALID,
    APPLICATION_TIMEOUT, APPLICATION_REJECTED, COMMAND_INVALID, SEQUENCE_EXHAUSTED,
    BOND_FAILED, BOND_TIMEOUT,
}

enum class TestCommand { TURN_RIGHT, OFF }
enum class MenuOperation { IDLE, READING, SAVING, SAVED, ERROR }

data class BleDevice(
    val address: String,
    val name: String,
    val rssi: Int? = null,
    val paired: Boolean = false,
    val systemConnected: Boolean = false,
)

data class BleState(
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val device: BleDevice? = null,
    val busy: Boolean = false,
    val issue: BleIssue? = null,
    val lastWrite: TestCommand? = null,
    val protocol: ProtocolMode? = null,
    val capabilities: DeviceCapabilities? = null,
    val delivery: CommandDelivery? = null,
    val rejection: AckStatus? = null,
    val reconnectAttempt: Int = 0,
    val reconnectDelaySeconds: Int = 0,
    val menu: MenuConfiguration? = null,
    val menuOperation: MenuOperation = MenuOperation.IDLE,
    val mediaBridgeAvailable: Boolean = false,
    val mediaBridgeEnabled: Boolean = false,
    val mediaBridgeStatus: MediaBridgeStatus = MediaBridgeStatus.IDLE,
    val mediaBridgeError: String? = null,
    val mediaBridgeGattStatus: Int? = null,
    val mediaBridgeRetryCount: Int = 0,
    val mediaCharacteristicFound: Boolean = false,
    val mediaCccdFound: Boolean = false,
    val lastMetadataGattCompleteAt: Long? = null,
)

interface BleController {
    val state: StateFlow<BleState>
    fun refreshAvailability()
    fun onAppVisible()
    fun onAppHidden()
    fun onPairingDialogCovered()
    fun isPairingUiActive(): Boolean
    fun startScan()
    fun stopScan()
    fun connect(device: BleDevice)
    fun disconnect()
    fun sendTestTurn()
    fun sendOff()
    fun readMenu()
    fun saveMenu(configuration: MenuConfiguration)
    fun close()
}
