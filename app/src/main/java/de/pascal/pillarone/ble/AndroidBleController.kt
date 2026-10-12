package de.pascal.pillarone.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import de.pascal.pillarone.R
import de.pascal.pillarone.protocol.LegacyNavigationProtocol
import de.pascal.pillarone.protocol.AckStatus
import de.pascal.pillarone.protocol.AccelerationTelemetryCodec
import de.pascal.pillarone.protocol.MessageType
import de.pascal.pillarone.protocol.MenuConfiguration
import de.pascal.pillarone.protocol.MenuChangedException
import de.pascal.pillarone.protocol.MenuProtocol
import de.pascal.pillarone.protocol.MenuRead
import de.pascal.pillarone.protocol.ProtocolException
import de.pascal.pillarone.protocol.V1Exchange
import de.pascal.pillarone.protocol.V1Message
import de.pascal.pillarone.protocol.V1Protocol
import de.pascal.pillarone.protocol.V1Reassembler
import de.pascal.pillarone.protocol.MediaBridgeCodec
import de.pascal.pillarone.protocol.MediaSnapshot
import de.pascal.pillarone.protocol.MediaControlDeduplicator
import de.pascal.pillarone.media.PhoneMediaSessions
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidBleController internal constructor(
    context: Context,
    private val lifetime: BleLifetimePolicy,
    private val requestService: (BleDevice) -> Unit,
    private val onSessionIdle: () -> Unit,
) : BleController {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter = if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
        manager?.adapter
    } else {
        null
    }
    private val mutableState = MutableStateFlow(BleState())
    override val state: StateFlow<BleState> = mutableState.asStateFlow()

    private var closed = false
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var session = 0L
    private var gatt: BluetoothGatt? = null
    private var writable: BluetoothGattCharacteristic? = null
    private var connectionTimeout: Runnable? = null
    private var writeTimeout: Runnable? = null
    private var pendingWrite: TestCommand? = null
    private val random = SecureRandom()
    private var wireSession = 0L
    private var sequence = 0
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingDescriptor: BluetoothGattDescriptor? = null
    private var exchange: V1Exchange? = null
    private var assembler: V1Reassembler? = null
    private var responseTimeout: Runnable? = null
    private var assemblyTimeout: Runnable? = null
    private var awaitingWrite = false
    private var bondTimeout: Runnable? = null
    private val preferences = appContext.getSharedPreferences("display_connection", Context.MODE_PRIVATE)
    private var visible: Boolean
        get() = lifetime.visible
        set(value) { lifetime.visible = value }
    private var restorationAttempted = true
    private var pairingUiActive = false
    private var foregroundReturnTimeout: Runnable? = null
    private val reconnectPolicy = ReconnectPolicy()
    private var reconnectTimeout: Runnable? = null
    private var menuRead: MenuRead? = null
    private var menuToSave: MenuConfiguration? = null
    private var platformCharacteristic: BluetoothGattCharacteristic? = null
    private var mediaCharacteristic: BluetoothGattCharacteristic? = null
    private var platformPending = false
    private var bridgeEnabled = false
    private var bridgeDesired = false
    private val mediaRecovery = MediaRecoveryPolicy()
    private var optionalDescriptor = false
    private var optionalDescriptorEnable = false
    private var optionalWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var optionalFrames: List<ByteArray> = emptyList()
    private var optionalFrameIndex = 0
    private val mediaQueue = MediaMetadataQueue()
    private var mediaMessageId = 0
    private val controlDeduplicator = MediaControlDeduplicator()
    private var mediaHeartbeat: Runnable? = null
    private var disconnectRequested = false
    private var disconnectUnsubscribeAttempted = false

    fun updateMedia(snapshot: MediaSnapshot, enabled: Boolean) = onMain {
        mediaQueue.update(if (enabled) snapshot else MediaSnapshot())
        bridgeDesired = enabled
        pumpOptionalWrites()
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onMain {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> refreshAvailability()
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        handleBondChange(intent)
                        refreshAvailability()
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> refreshAvailability()
                    Intent.ACTION_SCREEN_OFF -> {
                        if (!closed) onAppHidden()
                    }
                }
            }
        }
    }

    init {
        // Bluetooth broadcasts may originate from a privileged non-system UID.
        ContextCompat.registerReceiver(
            appContext,
            adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        refreshAvailability()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    override fun refreshAvailability() = onMain {
        if (!closed && checkAvailability()) {
            refreshKnownDevices()
            restoreConnection()
        }
    }

    override fun onAppVisible() = onMain {
        if (closed) return@onMain
        restorationAttempted = false
        visible = true
        if (state.value.phase != ConnectionPhase.BONDING) pairingUiActive = false
        cancelForegroundReturnTimeout()
        refreshAvailability()
    }

    override fun onAppHidden() = onMain {
        visible = false
        pairingUiActive = false
        stopScan()
        if (!lifetime.keepSessionWhenHidden && gatt != null) disconnect()
        restorationAttempted = false
    }

    override fun isPairingUiActive(): Boolean = pairingUiActive

    override fun onPairingDialogCovered() = onMain {
        if (lifetime.serviceActive) {
            onAppHidden()
            return@onMain
        }
        visible = false
        if (closed || !pairingUiActive || gatt == null) {
            onAppHidden()
            return@onMain
        }
        if (foregroundReturnTimeout == null) {
            val token = session
            // System pairing can launch an Activity, including onUserLeaveHint, on vendor Android.
            foregroundReturnTimeout = Runnable {
                if (!visible && token == session) onAppHidden()
            }.also { handler.postDelayed(it, BOND_MS) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshKnownDevices() {
        try {
            val bonded = adapter?.bondedDevices.orEmpty()
            val connected = manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
            val selected = preferences.getString("address", null)
            val known = (bonded + connected).distinctBy { it.address }.filter {
                it.address == selected || it.name?.equals("Pillar One", ignoreCase = true) == true
            }.map { device ->
                BleDevice(
                    address = device.address,
                    name = device.name?.takeIf { it.isNotBlank() }
                        ?: preferences.getString("name", null)
                        ?: appContext.getString(R.string.unnamed_device),
                    paired = device in bonded,
                    systemConnected = device in connected,
                )
            }
            val merged = mergeKnownDevices(state.value.devices, known)
            mutableState.value = state.value.copy(
                devices = merged,
                device = state.value.device?.let { active ->
                    merged.firstOrNull { it.address == active.address }
                        ?: active.copy(paired = false, systemConnected = false)
                },
            )
        } catch (_: SecurityException) {
            permissionLost()
        }
    }

    private fun restoreConnection() {
        if (!lifetime.canRestore || restorationAttempted || gatt != null || reconnectTimeout != null || state.value.scanning ||
            !state.value.permissionsGranted || !state.value.bluetoothEnabled
        ) return
        val selected = preferences.getString("address", null)
        val candidate = restorationCandidate(state.value.devices, selected) ?: return
        restorationAttempted = true
        connect(candidate)
    }

    private fun rememberDisplay() {
        state.value.device?.let {
            preferences.edit().putString("address", it.address).putString("name", it.name).apply()
            reconnectPolicy.established(it)
        }
        mutableState.value = state.value.copy(reconnectAttempt = 0, reconnectDelaySeconds = 0)
    }

    @SuppressLint("MissingPermission")
    private fun checkAvailability(): Boolean {
        val permissions = BlePermissions.hasPermissions(appContext)
        val enabled = try {
            permissions && adapter?.isEnabled == true
        } catch (_: SecurityException) {
            permissionLost()
            return false
        }
        mutableState.value = state.value.copy(
            bluetoothAvailable = adapter != null,
            bluetoothEnabled = enabled,
            permissionsGranted = permissions,
        )
        val issue = when {
            adapter == null -> BleIssue.BLUETOOTH_UNAVAILABLE
            !permissions -> BleIssue.PERMISSION_REQUIRED
            !enabled -> BleIssue.BLUETOOTH_DISABLED
            else -> null
        }
        if (issue != null) {
            val phase = if (gatt != null) ConnectionPhase.DISCONNECTED else state.value.phase
            terminate(phase, issue)
            mutableState.value = state.value.copy(devices = emptyList())
            return false
        }
        if (state.value.issue in AVAILABILITY_ISSUES) {
            mutableState.value = state.value.copy(issue = null)
        }
        return true
    }

    override fun startScan() = onMain {
        if (closed || !visible || !checkAvailability()) return@onMain
        if (lifetime.serviceActive || lifetime.startPending || state.value.scanning || gatt != null) {
            reportBusy()
            return@onMain
        }
        stopReconnecting()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationEnabled()) {
            if (state.value.permissionsGranted) {
                mutableState.value = state.value.copy(issue = BleIssue.LOCATION_DISABLED)
            }
            return@onMain
        }
        startScanInternal()
    }

    private fun locationEnabled(): Boolean {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: SecurityException) {
            permissionLost()
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal() {
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onMain {
                if (!closed && scanCallback === this) acceptScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) = onMain {
                if (!closed && scanCallback === this) results.forEach(::acceptScanResult)
            }

            override fun onScanFailed(errorCode: Int) = onMain {
                if (!closed && scanCallback === this) {
                    terminate(ConnectionPhase.ERROR, BleIssue.SCAN_FAILED)
                }
            }
        }
        try {
            scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                terminate(ConnectionPhase.ERROR, BleIssue.SCAN_FAILED)
                return
            }
            scanCallback = callback
            mutableState.value = state.value.copy(
                scanning = true, devices = state.value.devices.filter { it.paired || it.systemConnected },
                phase = ConnectionPhase.IDLE,
                device = null, issue = null, lastWrite = null, delivery = null,
                protocol = null, capabilities = null, rejection = null,
            )
            scanner?.startScan(
                listOf(SERVICE_UUID, V1_SERVICE_UUID).map {
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
                },
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            scanTimeout = Runnable { stopScan() }.also { handler.postDelayed(it, SCAN_MS) }
        } catch (_: SecurityException) {
            permissionLost()
        } catch (_: IllegalStateException) {
            if (checkAvailability()) terminate(ConnectionPhase.ERROR, BleIssue.SCAN_FAILED)
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptScanResult(result: ScanResult) {
        if (!checkAvailability() || scanCallback == null) return
        try {
            val address = result.device.address
            val device = BleDevice(
                address,
                result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                    ?: result.device.name?.takeIf { it.isNotBlank() }
                    ?: appContext.getString(R.string.unnamed_device),
                result.rssi,
            )
            val devices = state.value.devices.toMutableList()
            val index = devices.indexOfFirst { it.address == address }
                if (index < 0) {
                    devices.add(device)
                } else {
                    devices[index] = device.copy(
                        paired = state.value.devices[index].paired,
                        systemConnected = state.value.devices[index].systemConnected,
                    )
                }
            mutableState.value = state.value.copy(devices = devices)
        } catch (_: SecurityException) {
            permissionLost()
        }
    }

    override fun stopScan() = onMain {
        if (!closed && !releaseScan()) permissionLost()
    }

    override fun connect(device: BleDevice) = onMain {
        if (closed || !checkAvailability()) return@onMain
        if (gatt != null || state.value.busy) {
            reportBusy()
            return@onMain
        }
        stopReconnecting()
        restorationAttempted = true
        if (!releaseScan()) {
            permissionLost()
            return@onMain
        }
        if (!BluetoothAdapter.checkBluetoothAddress(device.address)) {
            terminate(ConnectionPhase.ERROR, BleIssue.CONNECTION_FAILED)
            return@onMain
        }
        if (!lifetime.serviceActive) {
            if (lifetime.canStartService) requestService(device)
            return@onMain
        }
        if (!lifetime.canReconnect) return@onMain
        connectInternal(device)
    }

    internal fun serviceStartFailed() = onMain {
        terminate(ConnectionPhase.ERROR, BleIssue.CONNECTION_FAILED)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(device: BleDevice) {
        val token = ++session
        do {
            wireSession = random.nextInt().toLong() and 0xffff_ffffL
        } while (wireSession == 0L)
        sequence = 0
        assembler = V1Reassembler(wireSession)
        mutableState.value = state.value.copy(
            phase = ConnectionPhase.CONNECTING, device = device,
            busy = true, issue = null, lastWrite = null,
            protocol = null, capabilities = null, delivery = null, rejection = null,
            reconnectDelaySeconds = 0,
            menu = null,
            acceleration = null, accelerationReceivedAt = 0,
            menuOperation = if (state.value.menuOperation == MenuOperation.ERROR) MenuOperation.ERROR else MenuOperation.IDLE,
        )
        try {
            gatt = adapter?.getRemoteDevice(device.address)?.connectGatt(
                appContext, false, callbackFor(token), android.bluetooth.BluetoothDevice.TRANSPORT_LE,
            )
            if (gatt == null) {
                terminate(ConnectionPhase.ERROR, BleIssue.CONNECTION_FAILED)
            } else {
                armConnectionTimeout(token)
            }
        } catch (_: SecurityException) {
            permissionLost()
        }
    }

    private fun callbackFor(token: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) =
            onMain {
                if (!isCurrent(client, token)) return@onMain
                if (!checkAvailability()) return@onMain
                when {
                    status != BluetoothGatt.GATT_SUCCESS ||
                        newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        val issue = if (state.value.phase == ConnectionPhase.READY) {
                            BleIssue.CONNECTION_LOST
                        } else {
                            BleIssue.CONNECTION_FAILED
                        }
                        terminate(ConnectionPhase.ERROR, issue)
                    }
                    newState == BluetoothProfile.STATE_CONNECTED &&
                        state.value.phase == ConnectionPhase.CONNECTING -> discover(client, token)
                }
            }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) = onMain {
            if (!isCurrent(client, token) || state.value.phase != ConnectionPhase.DISCOVERING) {
                return@onMain
            }
            if (!checkAvailability()) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) {
                terminate(ConnectionPhase.ERROR, BleIssue.CONNECTION_FAILED)
                return@onMain
            }
            cancelConnectionTimeout()
            val v1 = client.getService(V1_SERVICE_UUID)
            if (v1 != null) {
                mutableState.value = state.value.copy(protocol = ProtocolMode.V1)
                val command = v1.getCharacteristic(V1_COMMAND_UUID)
                val response = v1.getCharacteristic(V1_RESPONSE_UUID)
                when {
                    command == null || response == null ->
                        terminate(ConnectionPhase.INCOMPATIBLE, BleIssue.CHARACTERISTIC_MISSING)
                    command.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ->
                        terminate(ConnectionPhase.INCOMPATIBLE, BleIssue.WRITE_UNSUPPORTED)
                    response.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 ->
                        terminate(ConnectionPhase.INCOMPATIBLE, BleIssue.NOTIFY_UNSUPPORTED)
                    else -> {
                        writable = command
                        responseCharacteristic = response
                        platformCharacteristic = v1.getCharacteristic(PLATFORM_UUID)?.takeIf {
                            it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                        }
                        val detectedMedia = v1.getCharacteristic(UUID.fromString(MediaBridgeCodec.UUID))
                        mediaCharacteristic = detectedMedia?.takeIf {
                            it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 &&
                                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                        }
                        val cccdFound = detectedMedia?.getDescriptor(CCCD_UUID) != null
                        val discoveryError = when {
                            detectedMedia == null -> "missing_media_characteristic"
                            mediaCharacteristic == null -> "unsupported_media_properties"
                            !cccdFound -> "missing_media_cccd"
                            else -> null
                        }
                        if (discoveryError != null) mediaRecovery.unsupported()
                        mutableState.value = state.value.copy(
                            mediaCharacteristicFound = detectedMedia != null,
                            mediaCccdFound = cccdFound,
                            mediaBridgeStatus = when {
                                detectedMedia == null -> MediaBridgeStatus.MISSING_CHARACTERISTIC
                                discoveryError != null -> MediaBridgeStatus.UNSUPPORTED
                                else -> MediaBridgeStatus.IDLE
                            },
                            mediaBridgeError = discoveryError,
                        )
                        Log.i("PillarOneBLE", "Discovery platform=${platformCharacteristic != null} media=${detectedMedia != null} mediaCCCD=$cccdFound properties=${detectedMedia?.properties}")
                        ensureBonded(client, token)
                    }
                }
                return@onMain
            }
            val service = client.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            when {
                service == null -> terminate(ConnectionPhase.INCOMPATIBLE, BleIssue.SERVICE_MISSING)
                characteristic == null ->
                    terminate(ConnectionPhase.INCOMPATIBLE, BleIssue.CHARACTERISTIC_MISSING)
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ->
                    terminate(ConnectionPhase.INCOMPATIBLE, BleIssue.WRITE_UNSUPPORTED)
                else -> {
                    writable = characteristic
                    mutableState.value = state.value.copy(
                        phase = ConnectionPhase.READY, busy = false, issue = null,
                        protocol = ProtocolMode.LEGACY,
                        mediaBridgeStatus = MediaBridgeStatus.MISSING_CHARACTERISTIC,
                        mediaBridgeError = "missing_media_characteristic",
                    )
                    mediaRecovery.unsupported()
                    Log.i("PillarOneBLE", "Discovery legacy=true platform=false media=false mediaCCCD=false")
                    rememberDisplay()
                }
            }
        }

        override fun onCharacteristicWrite(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = onMain {
            if (!isCurrent(client, token)) return@onMain
            if (characteristic === optionalWriteCharacteristic && awaitingWrite) {
                if (!checkAvailability()) return@onMain
                cancelWriteTimeout()
                awaitingWrite = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failOptionalWrite("metadata_callback_error", status)
                } else {
                    ++optionalFrameIndex
                    if (optionalFrameIndex < optionalFrames.size) writeOptionalFrame()
                    else {
                        if (characteristic === mediaCharacteristic) {
                            mediaQueue.complete()
                            mediaRecovery.reset()
                            mutableState.value = state.value.copy(
                                mediaBridgeStatus = MediaBridgeStatus.ACTIVE,
                                mediaBridgeError = null, mediaBridgeGattStatus = null,
                                mediaBridgeRetryCount = 0,
                                lastMetadataGattCompleteAt = SystemClock.elapsedRealtime(),
                            )
                            Log.d("PillarOneBLE", "Metadata GATT complete frames=${optionalFrames.size} bytes=${optionalFrames.sumOf { it.size }} (firmware acceptance unconfirmed)")
                        }
                        optionalWriteCharacteristic = null
                        optionalFrames = emptyList()
                        mutableState.value = state.value.copy(busy = false)
                        pumpOptionalWrites()
                    }
                }
                return@onMain
            }
            if (characteristic !== writable) return@onMain
            if (!checkAvailability()) return@onMain
            if (!awaitingWrite) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) {
                terminate(ConnectionPhase.ERROR, BleIssue.WRITE_FAILED)
            } else {
                cancelWriteTimeout()
                awaitingWrite = false
                val operation = exchange
                if (operation != null) {
                    operation.wroteFrame()
                    if (operation.writesComplete) {
                        mutableState.value = state.value.copy(delivery = CommandDelivery.BLE_WRITTEN)
                    }
                    when {
                        !operation.writesComplete -> writeNextFrame()
                        operation.complete -> finishExchange()
                        else -> armResponseTimeout(client, token)
                    }
                } else {
                    val command = pendingWrite ?: return@onMain
                    pendingWrite = null
                    mutableState.value = state.value.copy(
                        busy = false, issue = null, lastWrite = command,
                        delivery = CommandDelivery.BLE_WRITTEN,
                    )
                }
            }
        }

        override fun onDescriptorWrite(
            client: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) = onMain {
            if (!isCurrent(client, token) || descriptor !== pendingDescriptor) return@onMain
            if (optionalDescriptor) {
                if (!checkAvailability()) return@onMain
                cancelWriteTimeout()
                Log.i("PillarOneBLE", "Media CCCD enable=$optionalDescriptorEnable callback status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failOptionalWrite("cccd_callback_error", status)
                } else {
                    bridgeEnabled = optionalDescriptorEnable
                    pendingDescriptor = null
                    optionalDescriptor = false
                    optionalDescriptorEnable = false
                    if (bridgeEnabled) mediaRecovery.reset()
                    mutableState.value = state.value.copy(
                        busy = false, mediaBridgeEnabled = bridgeEnabled,
                        mediaBridgeStatus = if (bridgeEnabled) MediaBridgeStatus.ACTIVE else MediaBridgeStatus.IDLE,
                        mediaBridgeError = null, mediaBridgeGattStatus = null, mediaBridgeRetryCount = 0,
                    )
                    pumpOptionalWrites()
                }
                return@onMain
            }
            if (state.value.phase != ConnectionPhase.SUBSCRIBING) return@onMain
            if (!checkAvailability()) return@onMain
            cancelWriteTimeout()
            pendingDescriptor = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                terminate(ConnectionPhase.ERROR, BleIssue.SUBSCRIBE_FAILED)
            } else {
                mutableState.value = state.value.copy(phase = ConnectionPhase.HANDSHAKING)
                beginExchange(V1Message(MessageType.HELLO, wireSession, 0, byteArrayOf()))
            }
        }

        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val snapshot = value.copyOf()
            onMain { receive(client, token, characteristic, snapshot) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val snapshot = characteristic.value?.copyOf() ?: return
            onMain { receive(client, token, characteristic, snapshot) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureBonded(client: BluetoothGatt, token: Long) {
        try {
            if (client.device.bondState == BluetoothDevice.BOND_BONDED) {
                subscribeBonded(client, token)
                return
            }
            mutableState.value = state.value.copy(phase = ConnectionPhase.BONDING)
            pairingUiActive = true
            bondTimeout = Runnable {
                if (isCurrent(client, token) && state.value.phase == ConnectionPhase.BONDING &&
                    checkAvailability()
                ) finishBondWait(client, token)
            }.also { handler.postDelayed(it, BOND_MS) }
            if (client.device.bondState != BluetoothDevice.BOND_BONDING &&
                !client.device.createBond()
            ) {
                // Another system pairing request may have won the race.
                when (client.device.bondState) {
                    BluetoothDevice.BOND_BONDED -> subscribeBonded(client, token)
                    BluetoothDevice.BOND_BONDING -> Unit
                    else -> terminate(ConnectionPhase.ERROR, BleIssue.BOND_FAILED)
                }
            }
        } catch (_: SecurityException) {
            permissionLost()
        }
    }

    @SuppressLint("MissingPermission")
    private fun finishBondWait(client: BluetoothGatt, token: Long) {
        try {
            if (client.device.bondState == BluetoothDevice.BOND_BONDED) {
                subscribeBonded(client, token)
            } else {
                terminate(ConnectionPhase.ERROR, BleIssue.BOND_TIMEOUT)
            }
        } catch (_: SecurityException) {
            permissionLost()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBondChange(intent: Intent) {
        val client = gatt ?: return
        if (closed || state.value.protocol != ProtocolMode.V1 || !checkAvailability()) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        if (device != client.device) return
        try {
            // Verify the system's current state, rather than trusting broadcast extras.
            when (client.device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    if (state.value.phase == ConnectionPhase.BONDING) subscribeBonded(client, session)
                }
                BluetoothDevice.BOND_NONE -> {
                    if (intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) in
                        setOf(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED)
                    ) terminate(ConnectionPhase.ERROR, BleIssue.BOND_FAILED)
                }
            }
        } catch (_: SecurityException) {
            permissionLost()
        }
    }

    private fun subscribeBonded(client: BluetoothGatt, token: Long) {
        if (!isCurrent(client, token)) return
        cancelBondTimeout()
        val response = responseCharacteristic
        if (response == null) {
            terminate(ConnectionPhase.ERROR, BleIssue.PROTOCOL_INVALID)
            return
        }
        subscribe(client, response, token)
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(client: BluetoothGatt, response: BluetoothGattCharacteristic, token: Long) {
        mutableState.value = state.value.copy(phase = ConnectionPhase.SUBSCRIBING)
        val descriptor = response.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            terminate(ConnectionPhase.INCOMPATIBLE, BleIssue.SUBSCRIBE_FAILED)
            return
        }
        try {
            if (!client.setCharacteristicNotification(response, true)) {
                terminate(ConnectionPhase.ERROR, BleIssue.SUBSCRIBE_FAILED)
                return
            }
            pendingDescriptor = descriptor
            val value = GattWriteValues.notifications(descriptor)
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                client.writeDescriptor(descriptor, value) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                writeDescriptorLegacy(client, descriptor)
            }
            if (!accepted) {
                terminate(ConnectionPhase.ERROR, BleIssue.SUBSCRIBE_FAILED)
                return
            }
            writeTimeout = Runnable {
                if (isCurrent(client, token) && pendingDescriptor != null && checkAvailability()) {
                    terminate(ConnectionPhase.ERROR, BleIssue.SUBSCRIBE_TIMEOUT)
                }
            }.also { handler.postDelayed(it, WRITE_MS) }
        } catch (_: SecurityException) {
            permissionLost()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeDescriptorLegacy(client: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        return client.writeDescriptor(descriptor)
    }

    private fun receive(
        client: BluetoothGatt,
        token: Long,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ) {
        if (isCurrent(client, token) && characteristic === mediaCharacteristic) {
            if (!bridgeEnabled || !bridgeDesired || disconnectRequested || !checkAvailability()) return
            val control = MediaBridgeCodec.control(bytes) ?: return
            val accepted = controlDeduplicator.accept(control)
            Log.d("PillarOneBLE", "Media inbound action=${control.action} id=${control.commandId} dispatch=$accepted")
            if (!accepted) return
            PhoneMediaSessions.dispatch(control.action)
            return
        }
        if (!isCurrent(client, token) || characteristic !== responseCharacteristic ||
            state.value.phase !in setOf(ConnectionPhase.HANDSHAKING, ConnectionPhase.READY)
        ) return
        if (!checkAvailability()) return
        val now = SystemClock.elapsedRealtime()
        val message = assembler?.accept(bytes, now)
        assemblyTimeout?.let(handler::removeCallbacks)
        assemblyTimeout = null
        if (message == null) {
            Log.d("PillarOneBLE", "Incomplete or discarded v1 response frame")
            assembler?.expiresAt?.let { deadline ->
                assemblyTimeout = Runnable {
                    assemblyTimeout = null
                    if (isCurrent(client, token)) assembler?.expire(SystemClock.elapsedRealtime())
                }.also { handler.postDelayed(it, maxOf(0, deadline - now)) }
            }
            return
        }
        try {
            if (state.value.phase == ConnectionPhase.READY &&
                message.payload.size > (state.value.capabilities?.maxMessageBytes ?: V1Protocol.MAX_PAYLOAD)
            ) throw ProtocolException("Response exceeds negotiated payload maximum")
            if (message.type == MessageType.ACCELERATION_SAMPLE) {
                if (state.value.phase != ConnectionPhase.READY ||
                    state.value.capabilities?.supportsAcceleration != true
                ) throw ProtocolException("Unadvertised acceleration telemetry")
                mutableState.value = state.value.copy(
                    acceleration = AccelerationTelemetryCodec.decode(message.payload),
                    accelerationReceivedAt = now,
                )
                return
            }
            val operation = exchange ?: return
            if (!operation.accept(message)) {
                Log.d("PillarOneBLE", "Discarded unmatched v1 response")
                return
            }
            if (operation.complete) finishExchange()
        } catch (_: ProtocolException) {
            terminate(ConnectionPhase.ERROR, BleIssue.PROTOCOL_INVALID)
        }
    }

    private fun beginExchange(message: V1Message) {
        exchange = V1Exchange(message)
        writeNextFrame()
    }

    private fun writeNextFrame() {
        val operation = exchange ?: return
        writePayload(operation.frames[operation.writtenFrames])
    }

    private fun armResponseTimeout(client: BluetoothGatt, token: Long) {
        responseTimeout = Runnable {
            if (isCurrent(client, token) && exchange != null && checkAvailability()) {
                terminate(ConnectionPhase.ERROR, BleIssue.APPLICATION_TIMEOUT)
            }
        }.also { handler.postDelayed(it, V1Protocol.RESPONSE_MS) }
    }

    private fun finishExchange() {
        if (disconnectRequested && exchange?.complete == true) {
            responseTimeout?.let(handler::removeCallbacks)
            responseTimeout = null
            exchange = null
            pumpOptionalWrites()
            return
        }
        try {
            completeExchange()
            pumpOptionalWrites()
        } catch (error: MenuChangedException) {
            Log.w("PillarOneBLE", "Menu snapshot changed during transfer", error)
            menuFailure(AckStatus.CONFLICT)
        } catch (error: ProtocolException) {
            Log.w("PillarOneBLE", "Invalid application response", error)
            terminate(ConnectionPhase.ERROR, BleIssue.PROTOCOL_INVALID)
        }
    }

    private fun completeExchange() {
        val operation = exchange ?: return
        val response = operation.response ?: return
        responseTimeout?.let(handler::removeCallbacks)
        responseTimeout = null
        exchange = null
        if (operation.request.type == MessageType.HELLO) {
            val ack = V1Protocol.helloAck(response.payload)
            if (ack.status != AckStatus.OK) {
                terminate(ConnectionPhase.ERROR, BleIssue.APPLICATION_REJECTED)
                mutableState.value = state.value.copy(rejection = ack.status)
                return
            }
            mutableState.value = state.value.copy(
                phase = ConnectionPhase.READY, busy = false, issue = null,
                capabilities = ack.capabilities, delivery = null,
            )
            rememberDisplay()
            platformPending = platformCharacteristic != null
            mutableState.value = state.value.copy(mediaBridgeAvailable = mediaCharacteristic != null)
            startMediaHeartbeat()
            return
        }
        if (operation.request.type == MessageType.GET_MENU && response.type == MessageType.MENU_STATE) {
            val read = menuRead ?: throw ProtocolException("Unexpected menu response")
            val configuration = read.accept(response.payload)
            if (configuration == null) {
                requestMenuEntry(read.nextIndex)
            } else {
                val expected = menuToSave
                if (expected != null && (expected.pages != configuration.pages ||
                        expected.startupPageId != configuration.startupPageId)
                ) {
                    menuFailure(AckStatus.CONFLICT)
                    return
                }
                menuRead = null
                menuToSave = null
                mutableState.value = state.value.copy(
                    busy = false, issue = null, rejection = null, menu = configuration,
                    menuOperation = if (expected != null) MenuOperation.SAVED else MenuOperation.IDLE,
                    delivery = null, lastWrite = null,
                )
            }
            return
        }
        val ack = V1Protocol.commandAck(response.payload)
        if (operation.request.type == MessageType.GET_MENU || operation.request.type == MessageType.SET_MENU) {
            if (ack.status == AckStatus.OK) {
                menuRead = MenuRead()
                requestMenuEntry(0)
            } else if (ack.status in setOf(
                    AckStatus.INVALID_PAYLOAD, AckStatus.BUSY, AckStatus.UNSUPPORTED_COMMAND,
                    AckStatus.UNSUPPORTED_VERSION, AckStatus.CONFLICT, AckStatus.STORAGE_FAILED,
                )
            ) {
                menuFailure(ack.status)
            } else {
                terminate(ConnectionPhase.ERROR, BleIssue.APPLICATION_REJECTED)
                mutableState.value = state.value.copy(rejection = ack.status)
            }
            return
        }
        val command = pendingWrite
        pendingWrite = null
        if (ack.status == AckStatus.OK) {
            mutableState.value = state.value.copy(
                busy = false, issue = null, rejection = null,
                lastWrite = command, delivery = CommandDelivery.APP_ACCEPTED,
            )
        } else if (ack.status in setOf(
                AckStatus.INVALID_PAYLOAD, AckStatus.BUSY, AckStatus.UNSUPPORTED_COMMAND,
            )
        ) {
            // These rejections consume the sequence but do not invalidate an established session.
            mutableState.value = state.value.copy(
                busy = false, issue = BleIssue.APPLICATION_REJECTED, rejection = ack.status,
                lastWrite = null, delivery = null,
            )
        } else {
            terminate(ConnectionPhase.ERROR, BleIssue.APPLICATION_REJECTED)
            mutableState.value = state.value.copy(rejection = ack.status)
        }
    }

    private fun isCurrent(client: BluetoothGatt, token: Long): Boolean =
        !closed && token == session && client === gatt

    @SuppressLint("MissingPermission")
    private fun discover(client: BluetoothGatt, token: Long) {
        mutableState.value = state.value.copy(phase = ConnectionPhase.DISCOVERING)
        try {
            if (!client.discoverServices()) {
                terminate(ConnectionPhase.ERROR, BleIssue.CONNECTION_FAILED)
            } else {
                armConnectionTimeout(token)
            }
        } catch (_: SecurityException) {
            permissionLost()
        }
    }

    private fun armConnectionTimeout(token: Long) {
        cancelConnectionTimeout()
        connectionTimeout = Runnable {
            if (!closed && token == session && checkAvailability()) {
                terminate(ConnectionPhase.ERROR, BleIssue.CONNECTION_TIMEOUT)
            }
        }.also { handler.postDelayed(it, CONNECTION_MS) }
    }

    override fun sendTestTurn() = onMain {
        send(TestCommand.TURN_RIGHT)
    }

    override fun sendOff() = onMain {
        send(TestCommand.OFF)
    }

    override fun readMenu() = onMain {
        if (!canUseMenu()) return@onMain
        menuToSave = null
        menuRead = MenuRead()
        mutableState.value = state.value.copy(
            busy = true, issue = null, rejection = null, menu = null,
            menuOperation = MenuOperation.READING, delivery = null, lastWrite = null,
        )
        requestMenuEntry(0)
    }

    override fun saveMenu(configuration: MenuConfiguration) = onMain {
        if (!canUseMenu()) return@onMain
        val payload = try {
            MenuProtocol.encodeSet(configuration)
        } catch (_: ProtocolException) {
            menuFailure(AckStatus.INVALID_PAYLOAD)
            return@onMain
        }
        if (payload.size > (state.value.capabilities?.maxMessageBytes ?: 0)) {
            menuFailure(AckStatus.UNSUPPORTED_COMMAND)
            return@onMain
        }
        menuToSave = configuration
        mutableState.value = state.value.copy(
            busy = true, issue = null, rejection = null, menuOperation = MenuOperation.SAVING,
            delivery = null, lastWrite = null,
        )
        sendMenuMessage(MessageType.SET_MENU, payload)
    }

    private fun canUseMenu(): Boolean {
        if (closed || !checkAvailability()) return false
        if (state.value.busy) {
            reportBusy()
            return false
        }
        if (state.value.phase != ConnectionPhase.READY || gatt == null || writable == null) {
            mutableState.value = state.value.copy(issue = BleIssue.CONNECTION_FAILED, menuOperation = MenuOperation.ERROR)
            return false
        }
        if (state.value.protocol != ProtocolMode.V1 || state.value.capabilities?.supportsMenu != true) {
            menuFailure(AckStatus.UNSUPPORTED_COMMAND)
            return false
        }
        return true
    }

    private fun requestMenuEntry(index: Int) {
        sendMenuMessage(MessageType.GET_MENU, byteArrayOf(index.toByte()))
    }

    private fun sendMenuMessage(type: MessageType, payload: ByteArray) {
        if (sequence == 65535) {
            terminate(ConnectionPhase.ERROR, BleIssue.SEQUENCE_EXHAUSTED)
            return
        }
        beginExchange(V1Message(type, wireSession, ++sequence, payload))
    }

    private fun menuFailure(status: AckStatus) {
        menuRead = null
        menuToSave = null
        mutableState.value = state.value.copy(
            busy = false, issue = BleIssue.APPLICATION_REJECTED, rejection = status,
            menuOperation = MenuOperation.ERROR, delivery = null, lastWrite = null,
        )
    }

    @SuppressLint("MissingPermission")
    private fun send(command: TestCommand) {
        if (closed || !checkAvailability()) return
        if (state.value.busy) {
            reportBusy()
            return
        }
        val client = gatt
        val characteristic = writable
        if (state.value.phase != ConnectionPhase.READY || client == null || characteristic == null) {
            mutableState.value = state.value.copy(issue = BleIssue.CONNECTION_FAILED)
            return
        }
        val payload: ByteArray
        if (state.value.protocol == ProtocolMode.V1) {
            val capabilities = state.value.capabilities
            if (capabilities == null) {
                terminate(ConnectionPhase.ERROR, BleIssue.PROTOCOL_INVALID)
                return
            }
            if (sequence == 65535) {
                terminate(ConnectionPhase.ERROR, BleIssue.SEQUENCE_EXHAUSTED)
                return
            }
            payload = try {
                if (command == TestCommand.OFF) byteArrayOf() else
                    V1Protocol.navigation(V1Protocol.testTurn(), capabilities.maxMessageBytes, capabilities.maxTtlSeconds)
            } catch (_: ProtocolException) {
                mutableState.value = state.value.copy(
                    issue = BleIssue.COMMAND_INVALID, lastWrite = null, delivery = null, rejection = null,
                )
                return
            }
        } else if (state.value.protocol == ProtocolMode.LEGACY) {
            payload = if (command == TestCommand.OFF) LegacyNavigationProtocol.off() else LegacyNavigationProtocol.testTurn()
        } else {
            terminate(ConnectionPhase.ERROR, BleIssue.PROTOCOL_INVALID)
            return
        }
        pendingWrite = command
        mutableState.value = state.value.copy(
            busy = true, issue = null, lastWrite = null, delivery = null, rejection = null,
            menuOperation = MenuOperation.IDLE,
        )
        if (state.value.protocol == ProtocolMode.V1) {
            beginExchange(V1Message(
                if (command == TestCommand.OFF) MessageType.NAV_END else MessageType.NAV_UPDATE,
                wireSession, ++sequence, payload,
            ))
        } else {
            writePayload(payload)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writePayload(payload: ByteArray) {
        val client = gatt ?: return
        val characteristic = writable ?: return
        awaitingWrite = true
        try {
            val value = GattWriteValues.command(characteristic, payload)
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                client.writeCharacteristic(
                    characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                writeLegacy(client, characteristic)
            }
            if (!accepted) {
                terminate(ConnectionPhase.ERROR, BleIssue.WRITE_FAILED)
                return
            }
            val token = session
            writeTimeout = Runnable {
                if (isCurrent(client, token) && awaitingWrite && checkAvailability()) {
                    // Invalidate the GATT before another command can receive a late callback.
                    terminate(ConnectionPhase.ERROR, BleIssue.WRITE_TIMEOUT)
                }
            }.also { handler.postDelayed(it, WRITE_MS) }
        } catch (_: SecurityException) {
            permissionLost()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeLegacy(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        return client.writeCharacteristic(characteristic)
    }

    private fun reportBusy() {
        mutableState.value = state.value.copy(issue = BleIssue.BUSY)
    }

    private fun startMediaHeartbeat() {
        mediaHeartbeat?.let(handler::removeCallbacks)
        val token = session
        mediaHeartbeat = object : Runnable {
            override fun run() {
                if (closed || token != session || !lifetime.canReconnect || state.value.phase != ConnectionPhase.READY) return
                PhoneMediaSessions.refresh(appContext)
                val media = PhoneMediaSessions.state.value
                mediaRecovery.heartbeat()
                updateMedia(media.snapshot, media.accessGranted && media.connected)
                handler.postDelayed(this, 5000)
            }
        }.also { handler.postDelayed(it, 5000) }
    }

    private fun pumpOptionalWrites() {
        if (closed || exchange != null || awaitingWrite || pendingDescriptor != null ||
            optionalWriteCharacteristic != null) return
        if (disconnectRequested) {
            if (bridgeEnabled && !disconnectUnsubscribeAttempted && mediaCharacteristic != null) {
                disconnectUnsubscribeAttempted = true
                writeBridgeSubscription(false)
            } else {
                terminate(ConnectionPhase.DISCONNECTED, null)
            }
            return
        }
        if (!lifetime.serviceActive || state.value.phase != ConnectionPhase.READY || state.value.busy) return
        if (platformPending) {
            platformPending = false
            platformCharacteristic?.let { beginOptionalWrite(it, listOf(byteArrayOf(2))) }
            return
        }
        if (mediaCharacteristic == null) return
        if (!mediaRecovery.canAttempt(bridgeDesired || bridgeEnabled,
            lifetime.serviceActive && state.value.phase == ConnectionPhase.READY)) return
        if (bridgeEnabled != bridgeDesired) { writeBridgeSubscription(bridgeDesired); return }
        if (bridgeEnabled) {
            val frames = mediaQueue.begin(mediaMessageId) ?: return
            Log.d("PillarOneBLE", "Metadata message=$mediaMessageId frames=${frames.size} frameLengths=${frames.map { it.size }} titleBytes=${frames.first()[6].toInt() and 255} artistBytes=${frames.first()[7].toInt() and 255}")
            mediaMessageId = (mediaMessageId + 1) and 65535
            beginOptionalWrite(mediaCharacteristic!!, frames)
        }
    }

    private fun beginOptionalWrite(characteristic: BluetoothGattCharacteristic, frames: List<ByteArray>) {
        optionalWriteCharacteristic = characteristic
        optionalFrames = frames
        optionalFrameIndex = 0
        mutableState.value = state.value.copy(busy = true)
        writeOptionalFrame()
    }

    @SuppressLint("MissingPermission")
    private fun writeOptionalFrame() {
        val client = gatt ?: return
        val characteristic = optionalWriteCharacteristic ?: return
        awaitingWrite = true
        try {
            val value = GattWriteValues.command(characteristic, optionalFrames[optionalFrameIndex])
            val result: Int?
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result = client.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                result == BluetoothStatusCodes.SUCCESS
            } else {
                result = null
                writeLegacy(client, characteristic)
            }
            if (!accepted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    result == BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION) {
                    optionalPermissionLost("metadata_write_permission_denied", result)
                } else failOptionalWrite("metadata_write_rejected", result,
                    securityFailure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        result == BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED)
                return
            }
            armOptionalTimeout(client)
        } catch (_: SecurityException) { optionalPermissionLost("metadata_write_security_exception") }
    }

    @SuppressLint("MissingPermission")
    private fun writeBridgeSubscription(enable: Boolean) {
        val client = gatt ?: return
        val characteristic = mediaCharacteristic ?: return
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            mediaRecovery.unsupported()
            mutableState.value = state.value.copy(mediaBridgeStatus = MediaBridgeStatus.UNSUPPORTED,
                mediaBridgeError = "missing_media_cccd", busy = false)
            if (disconnectRequested) pumpOptionalWrites()
            return
        }
        try {
            optionalDescriptorEnable = enable
            mutableState.value = state.value.copy(mediaBridgeStatus = MediaBridgeStatus.SUBSCRIBING)
            if (!client.setCharacteristicNotification(characteristic, enable)) {
                failOptionalWrite("notification_registration_rejected")
                return
            }
            optionalDescriptor = true
            optionalDescriptorEnable = enable
            pendingDescriptor = descriptor
            mutableState.value = state.value.copy(busy = true)
            val value = GattWriteValues.notifications(descriptor, enable)
            val result: Int?
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result = client.writeDescriptor(descriptor, value)
                result == BluetoothStatusCodes.SUCCESS
            } else {
                result = null
                writeDescriptorLegacy(client, descriptor)
            }
            Log.i("PillarOneBLE", "Media CCCD enable=$enable write accepted=$accepted status=$result")
            if (!accepted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    result == BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION) {
                    optionalPermissionLost("cccd_write_permission_denied", result)
                } else failOptionalWrite("cccd_write_rejected", result,
                    securityFailure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        result == BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED)
                return
            }
            armOptionalTimeout(client)
        } catch (_: SecurityException) { optionalPermissionLost("cccd_security_exception") }
    }

    private fun armOptionalTimeout(client: BluetoothGatt) {
        val token = session
        writeTimeout = Runnable {
            if (isCurrent(client, token)) {
                Log.w("PillarOneBLE", "Optional media/platform write timeout; closing ambiguous GATT")
                terminate(ConnectionPhase.ERROR, BleIssue.WRITE_TIMEOUT)
                mutableState.value = state.value.copy(mediaBridgeStatus = MediaBridgeStatus.FAILED,
                    mediaBridgeError = "ambiguous_gatt_timeout")
            }
        }.also { handler.postDelayed(it, WRITE_MS) }
    }

    private fun failOptionalWrite(reason: String, status: Int? = null,
        securityFailure: Boolean = reason.endsWith("callback_error") && status in setOf(5, 8, 15)) {
        val mediaFailure = optionalWriteCharacteristic == null || optionalWriteCharacteristic !== platformCharacteristic
        Log.w("PillarOneBLE", "Optional operation rejected reason=$reason status=$status media=$mediaFailure")
        if (mediaFailure) {
            mediaRecovery.reject(terminal = securityFailure)
            mediaQueue.reject()
            mutableState.value = state.value.copy(
                mediaBridgeStatus = if (mediaRecovery.permanent || mediaRecovery.failures > MediaRecoveryPolicy.MAX_RETRIES)
                    MediaBridgeStatus.FAILED else MediaBridgeStatus.RETRY_WAIT,
                mediaBridgeError = if (securityFailure) "secure_reconnection_required:$reason" else reason,
                mediaBridgeGattStatus = status, mediaBridgeRetryCount = mediaRecovery.failures,
            )
        }
        cancelWriteTimeout()
        pendingDescriptor = null
        optionalDescriptor = false
        optionalDescriptorEnable = false
        awaitingWrite = false
        optionalWriteCharacteristic = null
        optionalFrames = emptyList()
        optionalFrameIndex = 0
        mutableState.value = state.value.copy(busy = false)
        if (disconnectRequested) pumpOptionalWrites()
    }

    private fun optionalPermissionLost(reason: String, status: Int? = null) {
        Log.w("PillarOneBLE", "Optional operation permission lost reason=$reason status=$status")
        permissionLost()
        mutableState.value = state.value.copy(mediaBridgeStatus = MediaBridgeStatus.FAILED,
            mediaBridgeError = reason, mediaBridgeGattStatus = status)
    }

    override fun disconnect() = onMain {
        if (closed) return@onMain
        lifetime.explicitDisconnect()
        val waiting = reconnectTimeout != null
        stopReconnecting()
        restorationAttempted = true
        if (gatt != null || waiting) {
            if (gatt != null && (bridgeEnabled || optionalDescriptor ||
                    optionalWriteCharacteristic === mediaCharacteristic && mediaCharacteristic != null)) {
                disconnectRequested = true
                mediaQueue.reset()
                mutableState.value = state.value.copy(busy = true)
                pumpOptionalWrites()
            } else terminate(ConnectionPhase.DISCONNECTED, null)
        } else if (!releaseScan()) {
            permissionLost()
        } else onSessionIdle()
    }

    private fun permissionLost() {
        mutableState.value = state.value.copy(permissionsGranted = false, bluetoothEnabled = false)
        terminate(ConnectionPhase.ERROR, BleIssue.PERMISSION_REQUIRED)
    }

    private fun terminate(phase: ConnectionPhase, issue: BleIssue?) {
        val menuInterrupted = state.value.menuOperation in setOf(MenuOperation.READING, MenuOperation.SAVING)
        cancelReconnectTimeout()
        val scanReleased = releaseScan()
        val gattReleased = releaseGatt()
        val revoked = !scanReleased || !gattReleased
        mutableState.value = state.value.copy(
            phase = phase, scanning = false, busy = false, lastWrite = null,
            capabilities = null, delivery = null, rejection = null,
            issue = if (revoked) BleIssue.PERMISSION_REQUIRED else issue,
            permissionsGranted = if (revoked) false else state.value.permissionsGranted,
            bluetoothEnabled = if (revoked) false else state.value.bluetoothEnabled,
            reconnectDelaySeconds = 0,
            menu = null,
            menuOperation = if (menuInterrupted) MenuOperation.ERROR else state.value.menuOperation,
        )
        if (!revoked && lifetime.canReconnect && !closed && state.value.permissionsGranted &&
            state.value.bluetoothEnabled
        ) {
            scheduleReconnect(issue)
        } else {
            stopReconnecting()
        }
        if (gatt == null && reconnectTimeout == null) onSessionIdle()
    }

    private fun scheduleReconnect(issue: BleIssue?) {
        val delay = reconnectPolicy.nextDelay(issue)
        if (delay == null) {
            if (issue !in setOf(
                    BleIssue.CONNECTION_LOST, BleIssue.CONNECTION_FAILED, BleIssue.CONNECTION_TIMEOUT,
                )
            ) stopReconnecting()
            return
        }
        val target = reconnectPolicy.target ?: return
        val token = session
        mutableState.value = state.value.copy(
            phase = ConnectionPhase.RECONNECTING,
            reconnectAttempt = reconnectPolicy.attempts,
            reconnectDelaySeconds = (delay / 1000).toInt(),
        )
        reconnectTimeout = Runnable {
            reconnectTimeout = null
            if (closed || !lifetime.canReconnect || token != session || !checkAvailability()) return@Runnable
            refreshKnownDevices()
            if (closed || !state.value.permissionsGranted) return@Runnable
            val device = state.value.devices.firstOrNull { it.address == target.address && it.paired }
            if (device == null) {
                terminate(ConnectionPhase.ERROR, BleIssue.BOND_FAILED)
                return@Runnable
            }
            // Keep the retry budget; public connect() intentionally resets it for a user action.
            connectInternal(device)
        }.also { handler.postDelayed(it, delay) }
    }

    private fun cancelReconnectTimeout() {
        reconnectTimeout?.let(handler::removeCallbacks)
        reconnectTimeout = null
    }

    private fun stopReconnecting() {
        cancelReconnectTimeout()
        reconnectPolicy.stop()
        mutableState.value = state.value.copy(reconnectAttempt = 0, reconnectDelaySeconds = 0)
    }

    @SuppressLint("MissingPermission")
    private fun releaseScan(): Boolean {
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null
        val callback = scanCallback
        val activeScanner = scanner
        scanCallback = null
        scanner = null
        mutableState.value = state.value.copy(scanning = false)
        return try {
            if (callback != null) activeScanner?.stopScan(callback)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            // An adapter-off race already stops the platform scan; still clear our own lifecycle.
            Log.d("PillarOneBLE", "Scanner unavailable while stopping scan")
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun releaseGatt(): Boolean {
        val media = mediaCharacteristic
        ++session
        pairingUiActive = false
        cancelForegroundReturnTimeout()
        cancelBondTimeout()
        cancelConnectionTimeout()
        cancelWriteTimeout()
        responseTimeout?.let(handler::removeCallbacks)
        responseTimeout = null
        assemblyTimeout?.let(handler::removeCallbacks)
        assemblyTimeout = null
        assembler?.reset()
        assembler = null
        exchange = null
        menuRead = null
        menuToSave = null
        pendingDescriptor = null
        optionalDescriptor = false
        optionalWriteCharacteristic = null
        optionalFrames = emptyList()
        platformCharacteristic = null
        platformPending = false
        mediaCharacteristic = null
        bridgeEnabled = false
        mediaRecovery.reset()
        mediaQueue.reset()
        mediaMessageId = 0
        controlDeduplicator.reset()
        mediaHeartbeat?.let(handler::removeCallbacks)
        mediaHeartbeat = null
        disconnectRequested = false
        disconnectUnsubscribeAttempted = false
        optionalDescriptorEnable = false
        mutableState.value = state.value.copy(
            mediaBridgeAvailable = false, mediaBridgeEnabled = false,
            mediaBridgeStatus = MediaBridgeStatus.IDLE, mediaBridgeError = null,
            mediaBridgeGattStatus = null, mediaBridgeRetryCount = 0,
            mediaCharacteristicFound = false, mediaCccdFound = false,
            lastMetadataGattCompleteAt = null,
        )
        awaitingWrite = false
        wireSession = 0
        sequence = 0
        pendingWrite = null
        writable = null
        val response = responseCharacteristic
        responseCharacteristic = null
        val client = gatt
        gatt = null
        var released = true
        try {
            if (client != null && response != null) client.setCharacteristicNotification(response, false)
            if (client != null && media != null) client.setCharacteristicNotification(media, false)
        } catch (_: SecurityException) {
            released = false
        }
        try {
            client?.disconnect()
        } catch (_: SecurityException) {
            released = false
        }
        try {
            client?.close()
        } catch (_: SecurityException) {
            released = false
        }
        return released
    }

    private fun cancelConnectionTimeout() {
        connectionTimeout?.let(handler::removeCallbacks)
        connectionTimeout = null
    }

    private fun cancelWriteTimeout() {
        writeTimeout?.let(handler::removeCallbacks)
        writeTimeout = null
    }

    private fun cancelBondTimeout() {
        bondTimeout?.let(handler::removeCallbacks)
        bondTimeout = null
    }

    private fun cancelForegroundReturnTimeout() {
        foregroundReturnTimeout?.let(handler::removeCallbacks)
        foregroundReturnTimeout = null
    }

    override fun close() = onMain {
        if (!closed) {
            closed = true
            terminate(ConnectionPhase.DISCONNECTED, null)
            appContext.unregisterReceiver(adapterReceiver)
        }
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val V1_SERVICE_UUID: UUID = UUID.fromString("9a8b0001-7e4f-4c21-9d63-2b51a670c8e0")
        val V1_COMMAND_UUID: UUID = UUID.fromString("9a8b0002-7e4f-4c21-9d63-2b51a670c8e0")
        val V1_RESPONSE_UUID: UUID = UUID.fromString("9a8b0003-7e4f-4c21-9d63-2b51a670c8e0")
        val PLATFORM_UUID: UUID = UUID.fromString("9a8b0004-7e4f-4c21-9d63-2b51a670c8e0")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val AVAILABILITY_ISSUES = setOf(
            BleIssue.PERMISSION_REQUIRED, BleIssue.BLUETOOTH_UNAVAILABLE, BleIssue.BLUETOOTH_DISABLED,
        )
        const val SCAN_MS = 10_000L
        const val CONNECTION_MS = 12_000L
        const val WRITE_MS = 5_000L
        const val BOND_MS = 60_000L
    }
}
