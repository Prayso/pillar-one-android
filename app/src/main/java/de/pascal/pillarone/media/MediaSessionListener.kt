package de.pascal.pillarone.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import de.pascal.pillarone.protocol.MediaSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaSessionStatus(
    val accessGranted: Boolean = false,
    val connected: Boolean = false,
    val snapshot: MediaSnapshot = MediaSnapshot(),
    val playerPackage: String? = null,
    val rebindAttempts: Int = 0,
    val listenerError: String? = null,
)

object PhoneMediaSessions {
    private val mutableState = MutableStateFlow(MediaSessionStatus())
    val state = mutableState.asStateFlow()
    internal var listener: MediaSessionListener? = null
    private val rebindPolicy = ListenerRebindPolicy()

    fun hasAccess(context: Context): Boolean {
        val component = ComponentName(context, MediaSessionListener::class.java)
        return if (Build.VERSION.SDK_INT >= 27) {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }

    fun refresh(context: Context) {
        val granted = hasAccess(context)
        val connected = listener != null && state.value.connected
        val decision = MediaAccessDecision.decide(granted, connected, state.value.playerPackage != null)
        if (rebindPolicy.request(granted, connected, SystemClock.elapsedRealtime())) {
            try {
                NotificationListenerService.requestRebind(ComponentName(context, MediaSessionListener::class.java))
                Log.i("PillarOneMedia", "Listener rebind request=${rebindPolicy.attempts} accessGranted=$granted connected=$connected")
            } catch (error: SecurityException) {
                publish(MediaSessionStatus(accessGranted = granted, rebindAttempts = rebindPolicy.attempts,
                    listenerError = "listener_rebind_security_error"))
                Log.w("PillarOneMedia", "Listener rebind rejected", error)
                return
            }
        }
        when (decision) {
            MediaAccessDecision.PERMISSION_MISSING -> {
                listener?.refresh()
                publish(MediaSessionStatus())
            }
            MediaAccessDecision.LISTENER_DISCONNECTED -> publish(MediaSessionStatus(accessGranted = true,
                rebindAttempts = rebindPolicy.attempts, listenerError = state.value.listenerError))
            else -> listener?.refresh()
        }
    }

    fun settingsIntent(context: Context) = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, MediaSessionListener::class.java).flattenToString())
    }

    internal fun publish(status: MediaSessionStatus) {
        val previous = state.value
        if (status.connected) rebindPolicy.reset()
        if (previous.accessGranted != status.accessGranted || previous.connected != status.connected ||
            previous.playerPackage != status.playerPackage || previous.listenerError != status.listenerError) {
            Log.i("PillarOneMedia", "Listener enabled=${status.accessGranted} connected=${status.connected} session=${status.playerPackage} error=${status.listenerError}")
        }
        mutableState.value = status
    }

    fun dispatch(action: Int) {
        val service = listener
        if (service == null) Log.w("PillarOneMedia", "Control ignored: listener unavailable")
        else service.dispatch(action)
    }
}

class MediaSessionListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(MediaSessionManager::class.java) }
    private var connected = false
    private var registered = false
    private var selected: MediaController? = null
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { replace(it.orEmpty()) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        PhoneMediaSessions.listener = this
        try {
            if (!registered) {
                manager.addOnActiveSessionsChangedListener(sessionsChanged,
                    ComponentName(this, MediaSessionListener::class.java), handler)
                registered = true
            }
            refresh()
        } catch (error: SecurityException) { accessLost(error) }
    }

    override fun onListenerDisconnected() {
        stopListening()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    private fun stopListening() {
        connected = false
        try { if (registered) manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
        catch (error: SecurityException) { Log.w("PillarOneMedia", "Listener access already removed", error) }
        registered = false
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        selected = null
        if (PhoneMediaSessions.listener === this) {
            PhoneMediaSessions.listener = null
            PhoneMediaSessions.publish(MediaSessionStatus(accessGranted = PhoneMediaSessions.hasAccess(this)))
        }
    }

    private fun accessLost(error: SecurityException) {
        Log.w("PillarOneMedia", "Media session access lost", error)
        stopListening()
        PhoneMediaSessions.publish(MediaSessionStatus(accessGranted = PhoneMediaSessions.hasAccess(this),
            listenerError = "media_session_access_security_error"))
    }

    fun refresh() {
        if (!connected) return
        if (!PhoneMediaSessions.hasAccess(this)) { stopListening(); return }
        try {
            replace(manager.getActiveSessions(ComponentName(this, MediaSessionListener::class.java)))
        } catch (error: SecurityException) { accessLost(error) }
    }

    private fun replace(controllers: List<MediaController>) {
        if (!connected) return
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
                override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
                override fun onSessionDestroyed() { refresh() }
            }
            callbacks[controller] = callback
            controller.registerCallback(callback, handler)
        }
        publish()
    }

    private fun active(controller: MediaController): Boolean = controller.playbackState?.state in setOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )

    private fun publish() {
        if (!connected || !PhoneMediaSessions.hasAccess(this)) { stopListening(); return }
        val controllers = callbacks.keys.toList()
        selected = controllers.firstOrNull { active(it) && it.sessionToken == selected?.sessionToken }
            ?: controllers.firstOrNull(::active)
            ?: controllers.firstOrNull { it.sessionToken == selected?.sessionToken }
            ?: controllers.firstOrNull()
        val controller = selected
        val metadata = controller?.metadata
        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.toString().orEmpty()
        val artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString().orEmpty()
        PhoneMediaSessions.publish(MediaSessionStatus(true, true,
            MediaSnapshot(title, artist, controller?.playbackState?.state == PlaybackState.STATE_PLAYING),
            controller?.packageName))
    }

    fun dispatch(action: Int) {
        if (Looper.myLooper() != handler.looper) { handler.post { dispatch(action) }; return }
        if (!connected || !PhoneMediaSessions.hasAccess(this)) { stopListening(); return }
        publish()
        val controller = selected
        if (controller == null) { Log.w("PillarOneMedia", "Control ignored: no active session"); return }
        val actions = controller.playbackState?.actions ?: 0L
        val controls = controller.transportControls
        val supported = when (action) {
            1 -> {
                val playing = active(controller)
                val required = if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
                if (actions and (required or PlaybackState.ACTION_PLAY_PAUSE) == 0L) false
                else { if (playing) controls.pause() else controls.play(); true }
            }
            2 -> if (actions and PlaybackState.ACTION_SKIP_TO_NEXT == 0L) false else { controls.skipToNext(); true }
            3 -> if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS == 0L) false else { controls.skipToPrevious(); true }
            4, 5 -> if (controller.playbackInfo?.volumeControl !in setOf(
                VolumeProvider.VOLUME_CONTROL_RELATIVE,
                VolumeProvider.VOLUME_CONTROL_ABSOLUTE)) false
                else { controller.adjustVolume(if (action == 4) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0); true }
            else -> false
        }
        Log.d("PillarOneMedia", "Control action=$action package=${controller.packageName} dispatched=$supported (execution unconfirmed)")
    }
}