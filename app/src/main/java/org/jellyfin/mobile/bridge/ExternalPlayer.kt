package org.jellyfin.mobile.bridge

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.source.ExternalSubtitleStream
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.settings.ExternalPlayerPackage
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.isPackageInstalled
import org.jellyfin.mobile.utils.toast
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.api.operations.VideosApi
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber

@Suppress("TooManyFunctions")
class ExternalPlayer(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
    registry: ActivityResultRegistry,
) : KoinComponent, DefaultLifecycleObserver {
    private val coroutinesScope = MainScope()

    private val appPreferences: AppPreferences by inject()
    private val webappFunctionChannel: WebappFunctionChannel by inject()
    private val mediaSourceResolver: MediaSourceResolver by inject()
    private val deviceProfileBuilder: DeviceProfileBuilder by inject()
    private val externalPlayerProfile: DeviceProfile = deviceProfileBuilder.getExternalPlayerProfile()
    private val apiClient: ApiClient = get()
    private val videosApi: VideosApi = apiClient.videosApi
    private val trackingController: ExternalPlayerTrackingController by inject()

    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    private val playerContract = registry.register(
        "externalplayer",
        lifecycleOwner,
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val resultCode = result.resultCode
        val intent = result.data
        val returnedState = getReturnedPlaybackState(intent)
        val action = intent?.action
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
            "External player returned [action=%s, resultCode=%d, positionMs=%s, durationMs=%s]",
            action,
            resultCode,
            returnedState.positionMilliseconds,
            returnedState.durationMilliseconds,
        )
        trackingController.stop(
            reason = "activity_result:${action ?: "none"}:$resultCode",
            returnedPositionMilliseconds = returnedState.positionMilliseconds,
            returnedDurationMilliseconds = returnedState.durationMilliseconds,
        )
        ExternalPlayerTrackingService.stop(context)

        when (action) {
            Constants.MPV_PLAYER_RESULT_ACTION -> handleMPVPlayer(resultCode, intent)
            Constants.MX_PLAYER_RESULT_ACTION -> handleMXPlayer(resultCode, intent)
            Constants.VLC_PLAYER_RESULT_ACTION -> handleVLCPlayer(resultCode, intent)
            Constants.MPVKT_PLAYER_RESULT_ACTION -> handleMPVKTPlayer(resultCode, intent)
            else -> {
                if (action != null && resultCode != Activity.RESULT_CANCELED) {
                    Timber.d("Unknown action $action [resultCode=$resultCode]")
                    notifyEvent(Constants.EVENT_CANCELED)
                    context.toast(R.string.external_player_not_supported_yet, Toast.LENGTH_LONG)
                } else {
                    Timber.d("Playback canceled: no player selected or player without action result")
                    notifyEvent(Constants.EVENT_CANCELED)
                    context.toast(R.string.external_player_invalid_player, Toast.LENGTH_LONG)
                }
            }
        }
    }

    @JavascriptInterface
    fun isEnabled() = appPreferences.videoPlayerType == VideoPlayerType.EXTERNAL_PLAYER

    @JavascriptInterface
    fun initPlayer(args: String, webDeviceId: String) {
        val playOptions = PlayOptions.fromJson(args)
        val itemId = playOptions?.run {
            ids.firstOrNull() ?: mediaSourceId?.toUUIDOrNull() // fallback if ids is empty
        }
        if (playOptions == null || itemId == null) {
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).w("Invalid external playback options")
            context.toast(R.string.player_error_invalid_play_options)
            return
        }

        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
            "External playback requested [itemId=%s, requestedStartTicks=%s, configuredPackage=%s]",
            itemId,
            playOptions.startPosition?.inWholeTicks,
            appPreferences.externalPlayerApp,
        )

        coroutinesScope.launch {
            // Resolve media source to query info about external (subtitle) streams
            mediaSourceResolver.resolveMediaSource(
                itemId = itemId,
                mediaSourceId = playOptions.mediaSourceId,
                deviceProfile = externalPlayerProfile,
                startTime = playOptions.startPosition,
                audioStreamIndex = playOptions.audioStreamIndex,
                subtitleStreamIndex = playOptions.subtitleStreamIndex,
                maxStreamingBitrate = Int.MAX_VALUE, // ensure we always direct play
                autoOpenLiveStream = false,
            ).onSuccess { jellyfinMediaSource ->
                playMediaSource(playOptions, jellyfinMediaSource, webDeviceId)
            }.onFailure { error ->
                Timber.tag(ExternalPlayerTrackingController.LOG_TAG).e(
                    error,
                    "External media source resolution failed [itemId=%s]",
                    itemId,
                )
                when (error as? PlayerException) {
                    is PlayerException.InvalidPlayOptions -> context.toast(R.string.player_error_invalid_play_options)
                    is PlayerException.NetworkFailure -> context.toast(R.string.player_error_network_failure)
                    is PlayerException.UnsupportedContent -> context.toast(R.string.player_error_unsupported_content)
                    null -> throw error // Unknown error, rethrow from here
                }
            }
        }
    }

    private fun playMediaSource(
        playOptions: PlayOptions,
        source: RemoteJellyfinMediaSource,
        webDeviceId: String,
    ) {
        // Select correct subtitle
        val selectedSubtitleStream = playOptions.subtitleStreamIndex?.let { index ->
            source.mediaStreams.getOrNull(index)
        }
        source.selectSubtitleStream(selectedSubtitleStream)

        val title = source.getName(context)
        val playerIntent = createPlayerIntent(source, title)
        val trackingSession = createTrackingSession(source, title, webDeviceId)
        startExternalTracking(trackingSession)
        launchExternalPlayer(playerIntent, source, title, trackingSession)
    }

    private fun createPlayerIntent(source: RemoteJellyfinMediaSource, title: String): Intent {
        val url = videosApi.getVideoStreamUrl(
            itemId = source.itemId,
            static = true,
            mediaSourceId = source.id,
            playSessionId = source.playSessionId,
        )

        return Intent(Intent.ACTION_VIEW).apply {
            if (context.packageManager.isPackageInstalled(appPreferences.externalPlayerApp)) {
                component = getComponent(appPreferences.externalPlayerApp)
            }
            setDataAndType(url.toUri(), "video/*")
            putExtra("title", title)
            putExtra("position", source.startTime.inWholeMilliseconds.toInt())
            putExtra("return_result", true)
            putExtra("secure_uri", true)

            val externalSubs = source.externalSubtitleStreams
            val enabledSubUrl = when {
                source.selectedSubtitleStream != null -> {
                    externalSubs.find { stream -> stream.index == source.selectedSubtitleStream?.index }?.let { sub ->
                        apiClient.createUrl(sub.deliveryUrl)
                    }
                }
                else -> null
            }

            // MX Player API / MPV
            val subtitleUris = externalSubs.map { stream ->
                apiClient.createUrl(stream.deliveryUrl).toUri()
            }
            putExtra("subs", subtitleUris.toTypedArray())
            putExtra("subs.name", externalSubs.map(ExternalSubtitleStream::displayTitle).toTypedArray())
            putExtra("subs.filename", externalSubs.map(ExternalSubtitleStream::language).toTypedArray())
            putExtra("subs.enable", enabledSubUrl?.let { url -> arrayOf(url.toUri()) } ?: emptyArray())

            // VLC
            if (enabledSubUrl != null) putExtra("subtitles_location", enabledSubUrl)
        }
    }

    private fun createTrackingSession(
        source: RemoteJellyfinMediaSource,
        title: String,
        webDeviceId: String,
    ) =
        ExternalPlaybackTrackingSession(
            itemId = source.itemId,
            title = title,
            playSessionId = source.playSessionId,
            mediaSourceId = source.id,
            playMethod = source.playMethod,
            liveStreamId = source.liveStreamId,
            audioStreamIndex = source.selectedAudioStreamIndex,
            subtitleStreamIndex = source.selectedSubtitleStream?.index,
            initialPositionTicks = source.startTime.inWholeTicks,
            durationTicks = source.runTime.inWholeTicks.takeIf { it > 0L },
            // This personal build uses VLC through either the explicit VLC option or Android's
            // system chooser. The chooser does not reveal the selected package to the caller, so
            // optimistically observe VLC; if another player is chosen there will be no matching
            // MediaSession and the regular heartbeat fallback remains active.
            playerPackageName = ExternalPlayerPackage.VLC_PLAYER.takeIf {
                appPreferences.externalPlayerApp == ExternalPlayerPackage.VLC_PLAYER ||
                    appPreferences.externalPlayerApp == ExternalPlayerPackage.SYSTEM_DEFAULT
            },
            // The web layer may use either the base Android id or the user-qualified id depending
            // on when its WebView was created. Capture the exact identity used for PlaybackStart.
            webDeviceId = webDeviceId.trim().takeIf { it.isNotEmpty() } ?: apiClient.deviceInfo.id,
        )

    @Suppress("TooGenericExceptionCaught") // Tracking failure must never prevent external playback.
    private fun startExternalTracking(session: ExternalPlaybackTrackingSession) {
        trackingController.start(session)
        try {
            ExternalPlayerTrackingService.start(context, session)
        } catch (error: RuntimeException) {
            // Keep the in-process tracker running as a fallback. Without the service Android may
            // eventually suspend it, but playback itself should not fail solely due to tracking.
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).e(
                error,
                "Unable to start tracking foreground service [itemId=%s, playSessionId=%s]",
                session.itemId,
                session.playSessionId,
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // Activity launch failures are reported through the web bridge.
    private fun launchExternalPlayer(
        playerIntent: Intent,
        source: RemoteJellyfinMediaSource,
        title: String,
        trackingSession: ExternalPlaybackTrackingSession,
    ) {
        try {
            playerContract.launch(playerIntent)
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
                "External player launched [itemId=%s, playSessionId=%s, package=%s, " +
                    "startPositionTicks=%d, durationTicks=%s]",
                source.itemId,
                source.playSessionId,
                playerIntent.component?.packageName ?: "chooser",
                trackingSession.initialPositionTicks,
                trackingSession.durationTicks,
            )
            Timber.d(
                "Starting playback [id=${source.itemId}, title=$title, " +
                    "playMethod=${source.playMethod}, startTime=${source.startTime}]",
            )
        } catch (error: RuntimeException) {
            trackingController.stop("external_player_launch_failed")
            ExternalPlayerTrackingService.stop(context)
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).e(
                error,
                "External player launch failed [itemId=%s, playSessionId=%s]",
                source.itemId,
                source.playSessionId,
            )
            notifyEvent(Constants.EVENT_CANCELED)
            context.toast(R.string.external_player_invalid_player, Toast.LENGTH_LONG)
        }
    }

    private fun notifyEvent(event: String, parameters: String = "") {
        if (event in ALLOWED_EVENTS && parameters.isDigitsOnly()) {
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
                "Dispatching web player event [event=%s, parameter=%s%s]",
                event,
                parameters.ifEmpty { "none" },
                if (event == Constants.EVENT_ENDED) ", next=PlaybackStop" else "",
            )
            webappFunctionChannel.call("window.ExtPlayer.notify$event($parameters)")
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
            "External player bridge lifecycle ON_START [trackingActive=%s]",
            trackingController.activeSession != null,
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
            "External player bridge lifecycle ON_STOP [trackingActive=%s]",
            trackingController.activeSession != null,
        )
    }

    override fun onDestroy(owner: LifecycleOwner) {
        coroutinesScope.cancel()
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
            "External player bridge lifecycle ON_DESTROY [trackingActive=%s]",
            trackingController.activeSession != null,
        )
    }

    private fun getReturnedPlaybackState(data: Intent?): ReturnedPlaybackState {
        if (data == null) return ReturnedPlaybackState(null, null)
        return when (data.action) {
            Constants.VLC_PLAYER_RESULT_ACTION -> ReturnedPlaybackState(
                positionMilliseconds = data.getLongExtra("extra_position", -1L).takeIf { it >= 0L },
                durationMilliseconds = data.getLongExtra("extra_duration", -1L).takeIf { it >= 0L },
            )
            Constants.MX_PLAYER_RESULT_ACTION -> ReturnedPlaybackState(
                positionMilliseconds = data.getIntExtra("position", -1).takeIf { it >= 0 }?.toLong(),
                durationMilliseconds = data.getIntExtra("duration", -1).takeIf { it >= 0 }?.toLong(),
            )
            Constants.MPV_PLAYER_RESULT_ACTION, Constants.MPVKT_PLAYER_RESULT_ACTION -> ReturnedPlaybackState(
                positionMilliseconds = data.getIntExtra("position", -1).takeIf { it >= 0 }?.toLong(),
                durationMilliseconds = null,
            )
            else -> ReturnedPlaybackState(null, null)
        }
    }

    // https://github.com/mpv-android/mpv-android/commit/f70298fe23c4872ea04fe4f2a8b378b986460d98
    private fun handleMPVPlayer(resultCode: Int, data: Intent) {
        val player = "MPV Player"
        when (resultCode) {
            Activity.RESULT_OK -> {
                val position = data.getIntExtra("position", -1)
                when {
                    position > 0 -> {
                        Timber.d("Playback stopped [player=$player, position=$position]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE, "$position")
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    position == 0 -> {
                        Timber.d("Playback canceled [player=$player]")
                        notifyEvent(Constants.EVENT_CANCELED)
                    }
                    else -> {
                        Timber.d("Playback completed [player=$player]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE)
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                }
            }
            Activity.RESULT_CANCELED -> {
                Timber.d("Playback stopped by unknown error [player=$player]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
            else -> {
                Timber.d("Invalid state [player=$player, resultCode=$resultCode]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
        }
    }

    // https://sites.google.com/site/mxvpen/api
    private fun handleMXPlayer(resultCode: Int, data: Intent) {
        val player = "MX Player"
        when (resultCode) {
            Activity.RESULT_OK -> {
                when (val endBy = data.getStringExtra("end_by")) {
                    "playback_completion" -> {
                        Timber.d("Playback completed [player=$player]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE)
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    "user" -> {
                        val position = data.getIntExtra("position", -1)
                        val duration = data.getIntExtra("duration", -1)
                        when {
                            position > 0 -> {
                                Timber.d("Playback stopped [player=$player, position=$position, duration=$duration]")
                                notifyEvent(Constants.EVENT_TIME_UPDATE, "$position")
                                notifyEvent(Constants.EVENT_ENDED)
                            }
                            position == 0 -> {
                                Timber.d("Playback canceled [player=$player, position=$position, duration=$duration]")
                                notifyEvent(Constants.EVENT_CANCELED)
                            }
                            else -> {
                                Timber.d("Invalid state [player=$player, position=$position, duration=$duration]")
                                notifyEvent(Constants.EVENT_CANCELED)
                                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
                            }
                        }
                    }
                    else -> {
                        Timber.d("Invalid state [player=$player, endBy=$endBy]")
                        notifyEvent(Constants.EVENT_CANCELED)
                        context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
                    }
                }
            }
            Activity.RESULT_CANCELED -> {
                Timber.d("Playback stopped by user [player=$player]")
                notifyEvent(Constants.EVENT_CANCELED)
            }
            Activity.RESULT_FIRST_USER -> {
                Timber.d("Playback stopped by unknown error [player=$player]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
            else -> {
                Timber.d("Invalid state [player=$player, resultCode=$resultCode]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
        }
    }

    // https://wiki.videolan.org/Android_Player_Intents/
    private fun handleVLCPlayer(resultCode: Int, data: Intent) {
        val player = "VLC Player"
        when (resultCode) {
            Activity.RESULT_OK -> {
                val extraPosition = data.getLongExtra("extra_position", -1L)
                val extraDuration = data.getLongExtra("extra_duration", -1L)
                when {
                    extraDuration == extraPosition -> {
                        Timber.d("Playback completed [player=$player]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE)
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    extraPosition > 0L -> {
                        Timber.d(
                            "Playback stopped [player=$player, extraPosition=$extraPosition, " +
                                "extraDuration=$extraDuration]",
                        )
                        notifyEvent(Constants.EVENT_TIME_UPDATE, "$extraPosition")
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    else -> {
                        Timber.d(
                            "Playback canceled [player=$player, extraPosition=$extraPosition, " +
                                "extraDuration=$extraDuration]",
                        )
                        notifyEvent(Constants.EVENT_CANCELED)
                        if (extraPosition == -1L) {
                            context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
                        }
                    }
                }
            }
            else -> {
                Timber.d("Playback failed [player=$player, resultCode=$resultCode]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
        }
    }

    private fun handleMPVKTPlayer(resultCode: Int, data: Intent) {
        val player = "mpvKt Player"
        when (resultCode) {
            Activity.RESULT_OK -> {
                val position = data.getIntExtra("position", -1)
                when {
                    position > 0 -> {
                        Timber.d("Playback stopped [player=$player, position=$position]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE, "$position")
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    position == 0 -> {
                        Timber.d("Playback canceled [player=$player]")
                        notifyEvent(Constants.EVENT_CANCELED)
                    }
                    else -> {
                        Timber.d("Playback completed [player=$player]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE)
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                }
            }
            else -> {
                Timber.d("Invalid state [player=$player, resultCode=$resultCode]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
        }
    }

    /**
     * To ensure that the correct activity is called.
     */
    private fun getComponent(@ExternalPlayerPackage packageName: String): ComponentName? {
        return when (packageName) {
            ExternalPlayerPackage.MPV_PLAYER -> {
                ComponentName(packageName, "$packageName.MPVActivity")
            }
            ExternalPlayerPackage.MX_PLAYER_FREE, ExternalPlayerPackage.MX_PLAYER_PRO -> {
                ComponentName(packageName, "$packageName.ActivityScreen")
            }
            ExternalPlayerPackage.VLC_PLAYER -> {
                ComponentName(packageName, "$packageName.StartActivity")
            }
            ExternalPlayerPackage.MPVKT_PLAYER -> {
                ComponentName(packageName, "$packageName.ui.player.PlayerActivity")
            }
            else -> null
        }
    }

    companion object {
        private val ALLOWED_EVENTS = arrayOf(
            Constants.EVENT_CANCELED,
            Constants.EVENT_ENDED,
            Constants.EVENT_TIME_UPDATE,
        )
    }

    private data class ReturnedPlaybackState(
        val positionMilliseconds: Long?,
        val durationMilliseconds: Long?,
    )
}
