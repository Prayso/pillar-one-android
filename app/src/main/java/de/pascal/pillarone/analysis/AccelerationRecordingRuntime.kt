package de.pascal.pillarone.analysis

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationCompat
import de.pascal.pillarone.R
import de.pascal.pillarone.ble.BleController
import de.pascal.pillarone.ble.ConnectionPhase
import de.pascal.pillarone.protocol.AccelerationTelemetry
import de.pascal.pillarone.ui.AnalysisState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/** Lives with the application, not an activity or a screen. Only a visible activity prepares the FGS. */
class AccelerationRecordingRuntime internal constructor(
    private val context: Context,
    private val ble: BleController,
) : LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("acceleration_recording", Context.MODE_PRIVATE)
    private val locations = context.getSystemService(LocationManager::class.java)
    private val store = AccelerationStore(context)
    private val mutableState = MutableStateFlow(AnalysisState(
        automaticEnabled = preferences.getBoolean("automatic_enabled", false),
    ))
    val state = mutableState.asStateFlow()
    private var visible = false
    private var service: AccelerationRecordingService? = null
    private var pendingServiceRequest: Long? = null
    private var nextServiceRequest = 0L
    private var stoppingService = false
    private var pageDevice: String? = null
    private var pageBoot: Long? = null
    private var lastHeartbeatAt = 0L
    private var lastGpsAt = 0L
    private var gpsRequested = false
    private val gps = GpsAccelerationDetector()
    private var car: AutomaticCarRunPolicy? = null
    private val fixes = ArrayDeque<Pair<Long, MeasurementLocation>>()
    private var epochOffset = 0L
    private var zoneId = ""
    private var pageId = ""
    private data class Pending(
        val result: CompletedMeasurement,
        val offset: Long,
        val zone: String,
        val recordingId: String,
    )
    private val pendingStorage = ArrayDeque<Pending>()
    private val pendingCar = mutableListOf<CompletedMeasurement>()
    private val pendingGps = mutableListOf<CompletedMeasurement>()
    private val persistence = Mutex()

    init {
        retryStorage()
        scope.launch { ble.state.collect { updateConnection() } }
        scope.launch {
            while (true) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                if (state.value.automaticEnabled && !hasPreciseLocation()) {
                    stopService()
                    locationDenied()
                }
                if (state.value.automaticEnabled && !hasNotifications()) {
                    stopService()
                    notificationDenied()
                }
                if (state.value.recording) {
                    if (now - lastHeartbeatAt > FRESH_CAR_MS) {
                        leavePage()
                    } else {
                        if (now - lastGpsAt > FRESH_GPS_MS) {
                            gps.reset()
                            mutableState.value = state.value.copy(gpsReady = false)
                        }
                        if (car?.isSpeedFresh(now) != true) {
                            car?.resetContinuity()
                            mutableState.value = state.value.copy(carReady = false)
                        }
                    }
                }
                flushCompleted(now)
            }
        }
    }

    fun onAppVisible() {
        visible = true
        prepareService()
        updateConnection()
        requestGps()
    }

    fun onAppHidden() {
        visible = false
    }

    fun enableAutomatic() {
        if (!hasPreciseLocation()) {
            locationDenied()
            return
        }
        if (!hasNotifications()) {
            notificationDenied()
            return
        }
        preferences.edit().putBoolean("automatic_enabled", true).apply()
        mutableState.value = state.value.copy(automaticEnabled = true, error = null)
        prepareService()
        updateConnection()
    }

    fun disableAutomatic() {
        preferences.edit().putBoolean("automatic_enabled", false).apply()
        mutableState.value = state.value.copy(automaticEnabled = false, error = null)
        stopService()
    }

    fun locationDenied() {
        mutableState.value = state.value.copy(error = R.string.analysis_location_denied)
    }

    fun notificationDenied() {
        mutableState.value = state.value.copy(error = R.string.analysis_notification_denied)
    }

    private fun hasNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(AccelerationRecordingService.CHANNEL)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun hasPreciseLocation() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasConnectionSession() = ble.state.value.phase in setOf(
        ConnectionPhase.CONNECTING, ConnectionPhase.DISCOVERING, ConnectionPhase.BONDING,
        ConnectionPhase.SUBSCRIBING, ConnectionPhase.HANDSHAKING, ConnectionPhase.READY,
        ConnectionPhase.RECONNECTING,
    )

    private fun prepareService() {
        if (!visible || !state.value.automaticEnabled || !hasConnectionSession() ||
            service != null || pendingServiceRequest != null || stoppingService
        ) return
        if (!hasPreciseLocation()) {
            locationDenied()
            return
        }
        if (!hasNotifications()) {
            notificationDenied()
            return
        }
        val request = ++nextServiceRequest
        pendingServiceRequest = request
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, AccelerationRecordingService::class.java)
                    .putExtra(AccelerationRecordingService.REQUEST, request))
        } catch (error: RuntimeException) {
            Log.w("PillarOneAnalysis", "Visible location service start rejected", error)
            serviceStartFailed(request)
        }
    }

    internal fun acceptsServiceRequest(request: Long) =
        pendingServiceRequest == request && state.value.automaticEnabled &&
            hasPreciseLocation() && hasNotifications() && hasConnectionSession() && !stoppingService

    internal fun attachService(owner: AccelerationRecordingService, request: Long): Boolean {
        if (!acceptsServiceRequest(request) || service != null) return false
        pendingServiceRequest = null
        service = owner
        mutableState.value = state.value.copy(serviceReady = true,
            error = state.value.error.takeUnless { it == R.string.analysis_service_required })
        updateConnection()
        return true
    }

    internal fun serviceStartFailed(request: Long) {
        if (pendingServiceRequest != request) return
        pendingServiceRequest = null
        mutableState.value = state.value.copy(serviceReady = false, error = R.string.analysis_service_required)
    }

    internal fun detachService(owner: AccelerationRecordingService) {
        if (service !== owner) return
        val expectedStop = stoppingService
        leavePage()
        service = null
        stoppingService = false
        mutableState.value = state.value.copy(serviceReady = false,
            error = when {
                state.value.automaticEnabled && !hasPreciseLocation() -> R.string.analysis_location_denied
                state.value.automaticEnabled && !hasNotifications() -> R.string.analysis_notification_denied
                state.value.automaticEnabled && hasConnectionSession() -> R.string.analysis_service_required
                else -> state.value.error
            })
        // An unexpected destruction never starts another FGS from a background callback.
        if (expectedStop && visible) prepareService()
    }

    private fun stopService() {
        leavePage()
        pendingServiceRequest = null
        mutableState.value = state.value.copy(serviceReady = false)
        service?.let {
            stoppingService = true
            it.finishSession()
        }
    }

    private fun updateConnection() {
        val connection = ble.state.value
        if (!hasConnectionSession()) {
            stopService()
            return
        }
        if (state.value.automaticEnabled && !hasPreciseLocation()) {
            stopService()
            locationDenied()
            return
        }
        if (state.value.automaticEnabled && !hasNotifications()) {
            stopService()
            notificationDenied()
            return
        }
        prepareService()
        val capable = connection.phase == ConnectionPhase.READY &&
            connection.capabilities?.supportsAutomaticAcceleration == true
        val sample = connection.acceleration
        val now = SystemClock.elapsedRealtime()
        val receivedAt = connection.accelerationReceivedAt
        val active = state.value.automaticEnabled && capable && sample?.pageActive == true &&
            receivedAt > 0 && now - receivedAt in 0..FRESH_CAR_MS
        if (!active) {
            leavePage()
            if (state.value.automaticEnabled && connection.phase == ConnectionPhase.READY && !capable) {
                mutableState.value = state.value.copy(error = R.string.analysis_auto_firmware_required)
            }
            return
        }
        if (!state.value.serviceReady || stoppingService) {
            leavePage()
            mutableState.value = state.value.copy(error = R.string.analysis_service_required)
            return
        }
        val deviceId = connection.capabilities.deviceId
        if (!state.value.recording || pageDevice != deviceId || pageBoot != sample.bootId) {
            leavePage()
            enterPage(deviceId, sample.bootId, now)
        }
        if (receivedAt <= lastHeartbeatAt) return
        lastHeartbeatAt = receivedAt
        val detector = car ?: return
        val completed = detector.sample(sample, receivedAt, deviceId)
        mutableState.value = state.value.copy(carReady = sample.valid && detector.isSpeedFresh(now))
        pendingCar.addAll(completed)
        flushCompleted(now)
    }

    private fun enterPage(deviceId: String, bootId: Long, now: Long) {
        pageDevice = deviceId
        pageBoot = bootId
        epochOffset = System.currentTimeMillis() - now
        zoneId = ZoneId.systemDefault().id
        pageId = UUID.randomUUID().toString()
        lastHeartbeatAt = 0
        lastGpsAt = 0
        fixes.clear()
        pendingGps.clear()
        gps.reset()
        car = AutomaticCarRunPolicy(now)
        mutableState.value = state.value.copy(recording = true, gpsEnabled = true,
            gpsReady = false, carReady = false,
            error = state.value.error.takeIf { it == R.string.analysis_storage_failed })
        requestGps()
    }

    @SuppressLint("MissingPermission")
    private fun requestGps() {
        if (gpsRequested || !state.value.recording || !state.value.serviceReady) return
        if (!hasPreciseLocation()) {
            locationDenied()
            return
        }
        if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            mutableState.value = state.value.copy(error = R.string.analysis_location_disabled)
            return
        }
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, this, Looper.getMainLooper())
            gpsRequested = true
            mutableState.value = state.value.copy(error =
                state.value.error.takeUnless { it == R.string.analysis_location_disabled })
        } catch (error: SecurityException) {
            Log.w("PillarOneAnalysis", "Precise location no longer available", error)
            locationDenied()
        } catch (error: IllegalArgumentException) {
            Log.w("PillarOneAnalysis", "GPS provider unavailable", error)
            mutableState.value = state.value.copy(error = R.string.analysis_location_disabled)
        }
    }

    private fun leavePage() {
        if (gpsRequested) locations.removeUpdates(this)
        gpsRequested = false
        // Enrich completed runs before dropping the page's location/timing context.
        flushCompleted(SystemClock.elapsedRealtime(), force = true)
        pendingGps.clear()
        fixes.clear()
        gps.reset()
        car = null
        pageDevice = null
        pageBoot = null
        mutableState.value = state.value.copy(recording = false, gpsEnabled = false,
            gpsReady = false, carReady = false)
    }

    override fun onLocationChanged(location: Location) {
        if (!state.value.recording || !state.value.serviceReady || !gpsRequested) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeatAt > FRESH_CAR_MS) {
            leavePage()
            return
        }
        val time = location.elapsedRealtimeNanos / 1_000_000
        val valid = !LocationCompat.isMock(location) && location.latitude.isFinite() &&
            location.longitude.isFinite() && location.hasAccuracy() &&
            location.accuracy in 0f..30f && now - time in 0..FRESH_GPS_MS &&
            (fixes.isEmpty() || time > fixes.last().first)
        if (!valid) {
            gps.reset()
            mutableState.value = state.value.copy(gpsReady = false)
            return
        }
        val fix = MeasurementLocation(location.latitude, location.longitude, location.accuracy)
        fixes.addLast(time to fix)
        while (fixes.isNotEmpty() && time - fixes.first().first > 125_000) fixes.removeFirst()
        if (!location.hasSpeed() || !location.hasSpeedAccuracy() ||
            location.speedAccuracyMetersPerSecond !in 0f..3f ||
            !location.speed.isFinite() || location.speed !in 0f..(400f / 3.6f)
        ) {
            gps.reset()
            mutableState.value = state.value.copy(gpsReady = false)
            flushCompleted(now)
            return
        }
        lastGpsAt = time
        mutableState.value = state.value.copy(gpsReady = true)
        pendingGps.addAll(gps.sample(location.speed * 3.6, time, fix))
        flushCompleted(now)
    }

    private fun locationAt(time: Long): MeasurementLocation? =
        fixes.minByOrNull { abs(it.first - time) }
            ?.takeIf { abs(it.first - time) <= FRESH_GPS_MS }?.second

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER && state.value.recording) {
            gps.reset()
            mutableState.value = state.value.copy(gpsReady = false, error = R.string.analysis_location_disabled)
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER && state.value.recording) {
            requestGps()
            mutableState.value = state.value.copy(error =
                state.value.error.takeUnless { it == R.string.analysis_location_disabled })
        }
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun flushCompleted(now: Long, force: Boolean = false) {
        val ready = pendingCar.filter { force || now - it.endElapsedMs >= FRESH_GPS_MS }
        for (result in ready) {
            val matches = pendingGps.filter { matchesRun(result, it) }
            val matched = matches.singleOrNull()?.takeIf { gpsResult ->
                pendingCar.count { matchesRun(it, gpsResult) } == 1
            }
            val recordingId = "$pageId:${result.key}"
            pendingStorage.addLast(Pending(result.copy(location = locationAt(result.startElapsedMs),
                endLocation = locationAt(result.endElapsedMs)), epochOffset, zoneId, recordingId))
            if (matched != null) {
                pendingStorage.addLast(Pending(matched.copy(key = "${result.key}:GPS"),
                    epochOffset, zoneId, recordingId))
                pendingGps.remove(matched)
            }
        }
        pendingCar.removeAll(ready.toSet())
        pendingGps.removeAll { now - it.endElapsedMs > 125_000 }
        if (ready.isNotEmpty()) retryStorage()
    }

    fun retryStorage() {
        scope.launch {
            persistence.withLock {
                try {
                    while (pendingStorage.isNotEmpty()) {
                        val next = pendingStorage.first()
                        withContext(Dispatchers.IO) {
                            store.save(next.result, next.offset, next.zone, next.recordingId)
                        }
                        pendingStorage.removeFirst()
                    }
                    val records = withContext(Dispatchers.IO) { store.records() }
                    mutableState.value = state.value.copy(records = records, loading = false,
                        error = state.value.error.takeUnless { it == R.string.analysis_storage_failed })
                } catch (error: SQLiteException) {
                    Log.e("PillarOneAnalysis", "Acceleration storage unavailable", error)
                    mutableState.value = state.value.copy(loading = false, error = R.string.analysis_storage_failed)
                }
            }
        }
    }

    fun deleteRecord(recordId: Long) {
        scope.launch {
            persistence.withLock {
                try {
                    withContext(Dispatchers.IO) { store.delete(recordId) }
                    mutableState.value = state.value.copy(
                        records = state.value.records.filterNot { it.id == recordId },
                        error = state.value.error.takeUnless { it == R.string.analysis_delete_failed },
                    )
                } catch (error: SQLiteException) {
                    Log.e("PillarOneAnalysis", "Acceleration deletion failed", error)
                    mutableState.value = state.value.copy(error = R.string.analysis_delete_failed)
                }
            }
        }
    }

    companion object {
        internal const val FRESH_CAR_MS = 3_000L
        internal const val FRESH_GPS_MS = 2_000L

        internal fun matchesRun(car: CompletedMeasurement, gps: CompletedMeasurement) =
            car.interval == gps.interval && abs(car.startElapsedMs - gps.startElapsedMs) <= FRESH_GPS_MS &&
                abs(car.endElapsedMs - gps.endElapsedMs) <= FRESH_GPS_MS
    }
}

/** Heartbeats carry page/run flags, but only a changed uint32 sample timestamp refreshes car speed. */
internal class AutomaticCarRunPolicy(private val armedAt: Long) {
    private data class Run(val boot: Long, val run: Long, val interval: AccelerationInterval)
    private val observed = mutableSetOf<Run>()
    private val completed = mutableSetOf<Run>()
    private var boot: Long? = null
    private var firmwareTime: Long? = null
    private var speedAt = 0L
    private var previousReceivedAt = 0L

    fun isSpeedFresh(now: Long) = firmwareTime != null && now - speedAt in 0..3_000

    fun resetContinuity() {
        observed.clear()
        previousReceivedAt = 0
    }

    fun sample(sample: AccelerationTelemetry, receivedAt: Long, deviceId: String): List<CompletedMeasurement> {
        if (!sample.valid || !sample.pageActive || sample.speedKmh !in 0..400) {
            resetContinuity()
            return emptyList()
        }
        if (boot != sample.bootId || previousReceivedAt != 0L && receivedAt - previousReceivedAt !in 1..3_000) {
            resetContinuity()
            if (boot != sample.bootId) {
                completed.clear()
                firmwareTime = null
                speedAt = 0
            }
        }
        boot = sample.bootId
        val oldTime = firmwareTime
        if (oldTime == null) {
            firmwareTime = sample.sampleTimeMs
            speedAt = receivedAt
        } else if (oldTime != sample.sampleTimeMs) {
            val delta = (sample.sampleTimeMs - oldTime) and 0xffff_ffffL
            if (delta !in 1..3_000 || receivedAt - speedAt !in 1..3_000) observed.clear()
            firmwareTime = sample.sampleTimeMs
            speedAt = receivedAt
        }
        previousReceivedAt = receivedAt
        if (!isSpeedFresh(receivedAt)) {
            observed.clear()
            return emptyList()
        }
        observed.removeAll { it.boot != sample.bootId || it.run != sample.runId }
        return AccelerationInterval.entries.mapNotNull { interval ->
            val run = Run(sample.bootId, sample.runId, interval)
            val running = if (interval == AccelerationInterval.ZERO_TO_HUNDRED)
                sample.zeroRunning else sample.hundredRunning
            val ready = if (interval == AccelerationInterval.ZERO_TO_HUNDRED)
                sample.zeroReady else sample.hundredReady
            if (running && !ready) observed.add(run)
            if (!running && !ready) observed.remove(run)
            if (!ready || run !in observed || run in completed) return@mapNotNull null
            completed.add(run)
            observed.remove(run)
            val duration = if (interval == AccelerationInterval.ZERO_TO_HUNDRED)
                sample.zeroDurationMs else sample.hundredDurationMs
            val end = speedAt
            val start = end - duration
            if (duration !in 1..120_000 || start < armedAt) return@mapNotNull null
            CompletedMeasurement("$deviceId:${sample.bootId}:${sample.runId}:${interval.name}",
                interval, MeasurementSource.CAR, start, end, duration, null, deviceId)
        }
    }
}
