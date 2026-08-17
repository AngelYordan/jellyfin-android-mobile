package org.jellyfin.mobile.bridge

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import org.jellyfin.mobile.settings.ExternalPlayerPackage
import timber.log.Timber

class VlcMediaSessionObserver(context: Context) {
    private val applicationContext = context.applicationContext
    private val mediaSessionManager: MediaSessionManager = requireNotNull(applicationContext.getSystemService())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationListenerComponent = ComponentName(
        applicationContext,
        ExternalPlayerNotificationListenerService::class.java,
    )

    private var targetPackageName: String? = null
    private var sessionsListenerRegistered = false
    private var mediaController: MediaController? = null
    private var lastLoggedState: Pair<Int, Float>? = null
    private var lastNotifiedStatus: ExternalPlayerPlaybackStatus? = null
    private var playbackStateListener: ((ExternalPlayerPlaybackStatus) -> Unit)? = null

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        selectController(controllers.orEmpty())
    }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            logStateChange(state)
        }

        override fun onSessionDestroyed() {
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).w("VLC MediaSession was destroyed")
            detachController()
            refreshActiveSessions()
        }
    }

    fun start(packageName: String?): Boolean {
        if (packageName != ExternalPlayerPackage.VLC_PLAYER) {
            stop()
            return false
        }
        if (targetPackageName == packageName && sessionsListenerRegistered) {
            refreshActiveSessions()
            return true
        }

        stop()
        targetPackageName = packageName
        return connect()
    }

    fun stop() {
        targetPackageName = null
        disconnect()
    }

    fun setPlaybackStateListener(listener: ((ExternalPlayerPlaybackStatus) -> Unit)?) {
        playbackStateListener = listener
    }

    @Suppress("TooGenericExceptionCaught") // A dead remote MediaSession must fall back without losing the heartbeat.
    fun currentState(): ExternalPlayerObservedPlaybackState? {
        ensureController()
        return try {
            val state = mediaController?.playbackState ?: return null
            val status = state.toExternalStatus()
            if (status == ExternalPlayerPlaybackStatus.UNKNOWN) return null

            ExternalPlayerObservedPlaybackState(
                status = status,
                positionMilliseconds = state.position.takeUnless { it == PlaybackState.PLAYBACK_POSITION_UNKNOWN },
                playbackSpeed = state.playbackSpeed,
                lastPositionUpdateRealtimeMilliseconds = state.lastPositionUpdateTime,
            )
        } catch (error: RuntimeException) {
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).e(
                error,
                "VLC MediaSession became unavailable; using heartbeat fallback",
            )
            detachController()
            refreshActiveSessions()
            null
        }
    }

    fun onNotificationListenerConnected() {
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d("Notification listener connected")
        if (targetPackageName != null) {
            if (sessionsListenerRegistered) refreshActiveSessions() else connect()
        }
    }

    fun onNotificationListenerDisconnected() {
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).w("Notification listener disconnected")
        disconnect()
    }

    private fun connect(): Boolean {
        if (!hasNotificationListenerAccess(applicationContext)) {
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).w(
                "VLC MediaSession unavailable: notification access not granted",
            )
            return false
        }

        return try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                notificationListenerComponent,
                mainHandler,
            )
            sessionsListenerRegistered = true
            refreshActiveSessions()
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d("VLC MediaSession observation started")
            true
        } catch (error: SecurityException) {
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).e(
                error,
                "Unable to observe VLC MediaSession: notification access was rejected",
            )
            false
        }
    }

    private fun disconnect() {
        detachController()
        if (sessionsListenerRegistered) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            } catch (error: SecurityException) {
                Timber.tag(ExternalPlayerTrackingController.LOG_TAG).w(
                    error,
                    "VLC MediaSession listener access was already revoked",
                )
            }
            sessionsListenerRegistered = false
        }
    }

    private fun refreshActiveSessions() {
        if (!sessionsListenerRegistered) return
        try {
            selectController(mediaSessionManager.getActiveSessions(notificationListenerComponent))
        } catch (error: SecurityException) {
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).e(
                error,
                "Unable to refresh VLC MediaSession",
            )
            disconnect()
        }
    }

    private fun ensureController() {
        if (targetPackageName == null || mediaController != null) return
        if (sessionsListenerRegistered) refreshActiveSessions() else connect()
    }

    private fun selectController(controllers: List<MediaController>) {
        val packageName = targetPackageName ?: return
        val matches = controllers.filter { controller -> controller.packageName == packageName }
        val selected = matches.firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: matches.firstOrNull()

        if (selected?.sessionToken == mediaController?.sessionToken) return
        detachController()
        if (selected == null) {
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d("Waiting for VLC MediaSession")
            return
        }

        mediaController = selected
        selected.registerCallback(controllerCallback, mainHandler)
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
            "Attached to VLC MediaSession [package=%s]",
            selected.packageName,
        )
        logStateChange(selected.playbackState)
    }

    private fun detachController() {
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        lastLoggedState = null
        lastNotifiedStatus = null
    }

    private fun logStateChange(state: PlaybackState?) {
        if (state == null) return
        val status = state.toExternalStatus()
        val stateKey = state.state to state.playbackSpeed
        if (stateKey == lastLoggedState) return
        lastLoggedState = stateKey
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
            "VLC MediaSession state [status=%s, positionMs=%d, speed=%s, updateRealtimeMs=%d]",
            status,
            state.position,
            state.playbackSpeed,
            state.lastPositionUpdateTime,
        )
        if (status != ExternalPlayerPlaybackStatus.UNKNOWN && status != lastNotifiedStatus) {
            lastNotifiedStatus = status
            playbackStateListener?.invoke(status)
        }
    }

    private fun PlaybackState.toExternalStatus(): ExternalPlayerPlaybackStatus = when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        -> ExternalPlayerPlaybackStatus.PLAYING
        PlaybackState.STATE_PAUSED -> ExternalPlayerPlaybackStatus.PAUSED
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> ExternalPlayerPlaybackStatus.BUFFERING
        PlaybackState.STATE_STOPPED -> ExternalPlayerPlaybackStatus.STOPPED
        else -> ExternalPlayerPlaybackStatus.UNKNOWN
    }

    companion object {
        fun hasNotificationListenerAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
