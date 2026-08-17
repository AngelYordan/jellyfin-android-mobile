package org.jellyfin.mobile.bridge

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.RepeatMode
import timber.log.Timber

class ExternalPlayerTrackingController(
    private val apiClient: ApiClient,
    private val apiClientController: ApiClientController,
    jellyfin: Jellyfin,
    private val mediaSessionObserver: VlcMediaSessionObserver,
) {
    // The web player obtains the base Android device id before user authentication. Use that same
    // identity for external-player reports so Jellyfin updates the web playback session instead of
    // creating a second session with the user id appended by ApiClientController.
    private val reportingApiClient = jellyfin.createApi(deviceInfo = requireNotNull(jellyfin.options.deviceInfo))
    private val playStateApi = reportingApiClient.playStateApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker = ExternalPlaybackTracker(
        scope = scope,
        onHeartbeat = ::reportHeartbeat,
        onHeartbeatError = { heartbeat, error ->
            Timber.tag(LOG_TAG).w(
                error,
                "PlaybackProgress failed; tracker remains active " +
                    "[itemId=%s, playSessionId=%s, sequence=%d, elapsedMs=%d]",
                heartbeat.session.itemId,
                heartbeat.session.playSessionId,
                heartbeat.sequence,
                heartbeat.elapsedMilliseconds,
            )
        },
    )
    private var lastKnownPositionTicks = 0L

    init {
        mediaSessionObserver.setPlaybackStateListener { status ->
            val shouldReport = status == ExternalPlayerPlaybackStatus.PLAYING ||
                status == ExternalPlayerPlaybackStatus.PAUSED ||
                status == ExternalPlayerPlaybackStatus.BUFFERING
            if (shouldReport && tracker.requestHeartbeat()) {
                Timber.tag(LOG_TAG).d("Immediate PlaybackProgress requested for VLC state transition")
            }
        }
    }

    val activeSession: ExternalPlaybackTrackingSession?
        get() = tracker.activeSession

    fun start(session: ExternalPlaybackTrackingSession): ExternalPlaybackTrackerStartResult {
        val result = tracker.start(session)
        if (result != ExternalPlaybackTrackerStartResult.ALREADY_RUNNING) {
            lastKnownPositionTicks = session.initialPositionTicks
            mediaSessionObserver.start(session.playerPackageName)
        }
        Timber.tag(LOG_TAG).d(
            "Tracker start [result=%s, itemId=%s, playSessionId=%s, mediaSourceId=%s, " +
                "initialPositionTicks=%d, durationTicks=%s, playerPackage=%s]",
            result,
            session.itemId,
            session.playSessionId,
            session.mediaSourceId,
            session.initialPositionTicks,
            session.durationTicks,
            session.playerPackageName,
        )
        return result
    }

    fun stop(
        reason: String,
        returnedPositionMilliseconds: Long? = null,
        returnedDurationMilliseconds: Long? = null,
    ): ExternalPlaybackReconciliation? {
        val stop = tracker.stop(reason) ?: run {
            Timber.tag(LOG_TAG).d("Tracker stop ignored; no active tracker [reason=%s]", reason)
            return null
        }
        mediaSessionObserver.stop()
        val reconciliation = ExternalPlaybackTracker.reconcile(
            stop = stop,
            returnedPositionMilliseconds = returnedPositionMilliseconds,
            returnedDurationMilliseconds = returnedDurationMilliseconds,
        )
        Timber.tag(LOG_TAG).d(
            TRACKER_STOPPED_LOG,
            reason,
            stop.session.itemId,
            stop.session.playSessionId,
            stop.elapsedMilliseconds,
            lastKnownPositionTicks,
            reconciliation.estimatedPositionTicks,
            reconciliation.returnedPositionTicks,
            reconciliation.returnedDurationTicks,
            reconciliation.differenceTicks,
        )
        return reconciliation
    }

    private suspend fun reportHeartbeat(heartbeat: ExternalPlaybackHeartbeat) {
        val session = heartbeat.session

        // A foreground service can be recreated without MainActivity after Android kills the app
        // process. Restore the shared API client before its first redelivered heartbeat.
        if (apiClient.baseUrl == null || apiClient.accessToken == null) {
            apiClientController.loadSavedServerUser()
            check(apiClient.baseUrl != null && apiClient.accessToken != null) {
                "Unable to restore the saved Jellyfin server session"
            }
            Timber.tag(LOG_TAG).d("Restored API client for redelivered tracking service")
        }
        reportingApiClient.update(
            baseUrl = requireNotNull(apiClient.baseUrl),
            accessToken = requireNotNull(apiClient.accessToken),
            deviceInfo = reportingApiClient.deviceInfo.copy(id = session.webDeviceId),
        )

        val observedState = mediaSessionObserver.currentState()
        val mediaSessionReport = observedState?.toPlaybackReport(
            nowRealtimeMilliseconds = SystemClock.elapsedRealtime(),
            fallbackPositionTicks = lastKnownPositionTicks,
            durationTicks = session.durationTicks,
        )
        val positionTicks = mediaSessionReport?.positionTicks ?: lastKnownPositionTicks
        val isPaused = mediaSessionReport?.isPaused ?: false
        val playbackSpeed = mediaSessionReport?.playbackSpeed ?: 1f
        val stateSource = if (mediaSessionReport == null) "fallback-last-known" else "vlc-media-session"
        lastKnownPositionTicks = positionTicks

        withContext(Dispatchers.IO) {
            playStateApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = session.itemId,
                    mediaSourceId = session.mediaSourceId,
                    playMethod = session.playMethod,
                    playSessionId = session.playSessionId,
                    liveStreamId = session.liveStreamId,
                    audioStreamIndex = session.audioStreamIndex,
                    subtitleStreamIndex = session.subtitleStreamIndex,
                    isPaused = isPaused,
                    isMuted = false,
                    canSeek = true,
                    positionTicks = positionTicks,
                    volumeLevel = 100,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        }

        Timber.tag(LOG_TAG).d(
            "PlaybackProgress sent [itemId=%s, playSessionId=%s, sequence=%d, elapsedMs=%d, " +
                "positionTicks=%d, paused=%s, speed=%s, source=%s, observedStatus=%s]",
            session.itemId,
            session.playSessionId,
            heartbeat.sequence,
            heartbeat.elapsedMilliseconds,
            positionTicks,
            isPaused,
            playbackSpeed,
            stateSource,
            observedState?.status,
        )
    }

    companion object {
        const val LOG_TAG = "ExternalPlayerTracking"
        private const val TRACKER_STOPPED_LOG =
            "Tracker stopped [reason=%s, itemId=%s, playSessionId=%s, elapsedMs=%d, " +
                "lastKnownPositionTicks=%d, wallClockEstimateTicks=%d, returnedPositionTicks=%s, " +
                "returnedDurationTicks=%s, differenceTicks=%s]"
    }
}
