package de.pascal.pillarone.ble

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import de.pascal.pillarone.protocol.MenuConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BleRuntime internal constructor(private val context: Context) : BleController {
    private val preferences = context.getSharedPreferences("display_connection", Context.MODE_PRIVATE)
    private val lifetime = BleLifetimePolicy(preferences.getBoolean("user_disconnected", false))
    private val mutableState = MutableStateFlow(BleState())
    override val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: AndroidBleController? = null
    private var stateJob: Job? = null
    private var service: BleConnectionService? = null
    private var stopping = false
    private var nextRequest = 0L
    private var pending: Pair<Long, BleDevice>? = null
    private var afterStop: BleDevice? = null

    private fun current(): AndroidBleController {
        controller?.let { return it }
        val created = AndroidBleController(context, lifetime, ::requestService, ::sessionIdle)
        controller = created
        stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.state.collect { mutableState.value = it }
        }
        return created
    }

    private fun requestService(device: BleDevice) {
        if (!lifetime.canStartService || !BlePermissions.hasPermissions(context)) return
        if (stopping) {
            afterStop = device
            return
        }
        if (pending != null) return
        val request = ++nextRequest
        pending = request to device
        lifetime.startPending = true
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, BleConnectionService::class.java).putExtra(BleConnectionService.REQUEST, request))
        } catch (error: RuntimeException) {
            Log.w("PillarOneBLE", "Foreground service start rejected", error)
            pending = null
            lifetime.startPending = false
            current().serviceStartFailed()
        }
    }

    internal fun pendingDevice(request: Long): BleDevice? =
        pending?.takeIf { it.first == request && !lifetime.userDisconnected && !stopping }?.second

    internal fun attach(owner: BleConnectionService, request: Long): AndroidBleController? {
        if (pendingDevice(request) == null || service != null) return null
        pending = null
        lifetime.startPending = false
        service = owner
        lifetime.serviceActive = true
        return current()
    }

    internal fun startFailed(request: Long) {
        if (pending?.first != request) return
        pending = null
        lifetime.startPending = false
        current().serviceStartFailed()
    }

    private fun sessionIdle() {
        mutableState.value = controller?.state?.value ?: mutableState.value
        if (service != null && !stopping) {
            stopping = true
            service?.finishSession()
        }
    }

    internal fun detached(owner: BleConnectionService) {
        if (service !== owner) return
        stopping = true
        lifetime.serviceActive = false
        val finalState = controller?.state?.value
        controller?.close()
        stateJob?.cancel()
        mutableState.value = if (finalState?.phase in setOf(
                ConnectionPhase.ERROR, ConnectionPhase.INCOMPATIBLE, ConnectionPhase.DISCONNECTED,
            )) finalState!! else controller?.state?.value ?: mutableState.value
        stateJob = null
        controller = null
        service = null
        stopping = false
        val queued = afterStop
        afterStop = null
        if (queued != null && lifetime.canStartService) requestService(queued)
    }

    override fun connect(device: BleDevice) {
        if (!lifetime.visible || !BlePermissions.hasPermissions(context)) return
        lifetime.explicitConnect()
        preferences.edit().putBoolean("user_disconnected", false).apply()
        if (stopping) afterStop = device else if (pending == null) current().connect(device)
    }

    override fun disconnect() {
        lifetime.explicitDisconnect()
        preferences.edit().putBoolean("user_disconnected", true).apply()
        pending = null
        afterStop = null
        controller?.disconnect()
    }

    override fun onAppVisible() {
        lifetime.visible = true
        if (stopping || pending != null) return
        current().onAppVisible()
    }
    override fun onAppHidden() {
        lifetime.visible = false
        afterStop = null
        controller?.onAppHidden()
    }
    override fun onPairingDialogCovered() = onAppHidden()
    override fun isPairingUiActive() = controller?.isPairingUiActive() == true
    override fun refreshAvailability() = current().refreshAvailability()
    override fun startScan() = current().startScan()
    override fun stopScan() { controller?.stopScan() }
    override fun sendTestTurn() = current().sendTestTurn()
    override fun sendOff() = current().sendOff()
    override fun readMenu() = current().readMenu()
    override fun saveMenu(configuration: MenuConfiguration) = current().saveMenu(configuration)
    override fun close() = disconnect()
}