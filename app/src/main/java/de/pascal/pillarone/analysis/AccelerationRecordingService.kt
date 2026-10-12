package de.pascal.pillarone.analysis

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AccelerationRecordingService : Service() {
    private val runtime get() = (application as PillarApplication).acceleration
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var attached = false
    private var finishing = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.analysis_notification_channel),
                NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISABLE) {
            runtime.disableAutomatic()
            finishSession()
            return START_NOT_STICKY
        }
        val request = intent?.getLongExtra(REQUEST, -1) ?: -1
        if (!runtime.acceptsServiceRequest(request)) {
            runtime.serviceStartFailed(request)
            if (!attached) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val notification = notification(recording = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (error: RuntimeException) {
            Log.w("PillarOneAnalysis", "Location foreground service promotion rejected", error)
            runtime.serviceStartFailed(request)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!runtime.attachService(this, request)) {
            finishSession()
            return START_NOT_STICKY
        }
        attached = true
        scope.launch {
            runtime.state.map { it.recording }.distinctUntilChanged().collect { recording ->
                if (!finishing) getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(recording))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(recording: Boolean): Notification {
        val content = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disable = PendingIntent.getService(this, 3,
            Intent(this, AccelerationRecordingService::class.java).setAction(DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_analysis)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (recording) R.string.analysis_notification_recording
                else R.string.analysis_notification_waiting))
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.analysis_notification_disable), disable)
            .build()
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
        runtime.detachService(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val CHANNEL = "pillar_acceleration_recording"
        private const val NOTIFICATION_ID = 1002
        internal const val REQUEST = "acceleration_service_request"
        private const val DISABLE = "de.pascal.pillarone.DISABLE_AUTOMATIC_ACCELERATION"
    }
}
