package de.pascal.pillarone.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import de.pascal.pillarone.MainActivity
import de.pascal.pillarone.PillarApplication
import de.pascal.pillarone.R
import de.pascal.pillarone.media.PhoneMediaSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BleConnectionService : Service() {
    private val runtime get() = (application as PillarApplication).ble
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var attached = false
    private var finishing = false

    override fun onCreate() {
        super.onCreate()
        BleConnectionNotification.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) {
            runtime.disconnect()
            if (!attached) stopSelf(startId)
            return START_NOT_STICKY
        }
        val request = intent?.getLongExtra(REQUEST, -1) ?: -1
        val device = runtime.pendingDevice(request)
        if (device == null || !BlePermissions.hasPermissions(this)) {
            runtime.startFailed(request)
            if (!attached) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val notification = BleConnectionNotification.build(this, ConnectionPhase.CONNECTING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (error: RuntimeException) {
            Log.w("PillarOneBLE", "Cannot promote Bluetooth service", error)
            runtime.startFailed(request)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val controller = runtime.attach(this, request)
        if (controller == null) {
            finishSession()
            return START_NOT_STICKY
        }
        attached = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            PhoneMediaSessions.state.collect {
                controller.updateMedia(it.snapshot, it.accessGranted && it.connected)
            }
        }
        scope.launch {
            controller.state.collect {
                if (!finishing) getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, BleConnectionNotification.build(this@BleConnectionService, it.phase))
            }
        }
        controller.connect(device)
        return START_NOT_STICKY
    }

    internal fun finishSession() {
        if (finishing) return
        finishing = true
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finishing = true
        scope.cancel()
        runtime.detached(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val CHANNEL = "pillar_ble_connection"
        internal const val NOTIFICATION_ID = 1001
        internal const val REQUEST = "connection_request"
        internal const val DISCONNECT = "de.pascal.pillarone.DISCONNECT"
    }
}

internal object BleConnectionNotification {
    fun createChannel(context: android.content.Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(BleConnectionService.CHANNEL, context.getString(R.string.ble_notification_channel),
                NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun build(context: android.content.Context, phase: ConnectionPhase): Notification {
        val content = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(context, 1,
            Intent(context, BleConnectionService::class.java).setAction(BleConnectionService.DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = when (phase) {
            ConnectionPhase.READY -> R.string.ble_notification_connected
            ConnectionPhase.RECONNECTING -> R.string.phase_reconnecting
            else -> R.string.phase_connecting
        }
        return NotificationCompat.Builder(context, BleConnectionService.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(text))
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.disconnect), disconnect)
            .build()
    }
}