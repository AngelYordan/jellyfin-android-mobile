package org.jellyfin.mobile.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.createMediaNotificationChannel
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber

class ExternalPlayerTrackingService : Service() {
    private val trackingController: ExternalPlayerTrackingController by inject()
    private val notificationManager: NotificationManager by lazy { requireNotNull(getSystemService()) }

    override fun onCreate() {
        super.onCreate()
        createMediaNotificationChannel(notificationManager)
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d("Tracking service created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = intent?.takeIf { it.action == ACTION_START }?.toTrackingSession()
        if (session == null) {
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).w(
                "Tracking service received invalid start [action=%s]",
                intent?.action,
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startAsForeground(session)
        val isRedelivery = flags and START_FLAG_REDELIVERY != 0
        val currentSession = trackingController.activeSession
        if (currentSession != session && !isRedelivery) {
            // The Activity may return before this asynchronously-started service receives its
            // initial command. Never resurrect a tracker that was already stopped before the web
            // client sends PlaybackStop. A genuine process restart is marked as a redelivery.
            Timber.tag(ExternalPlayerTrackingController.LOG_TAG).w(
                "Ignoring stale tracking service start [itemId=%s, playSessionId=%s]",
                session.itemId,
                session.playSessionId,
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        trackingController.start(session)
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d(
            "Tracking service active [itemId=%s, playSessionId=%s]",
            session.itemId,
            session.playSessionId,
        )
        return START_REDELIVER_INTENT
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        trackingController.stop("tracking_service_destroyed")
        stopForeground(true)
        Timber.tag(ExternalPlayerTrackingController.LOG_TAG).d("Tracking service destroyed")
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // Required for the pre-Android O notification compatibility path.
    private fun startAsForeground(session: ExternalPlaybackTrackingSession) {
        val contentIntent = PendingIntent.getActivity(
            this,
            Constants.EXTERNAL_PLAYER_TRACKING_CONTENT_INTENT_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            Constants.PENDING_INTENT_FLAGS,
        )
        val notification = Notification.Builder(this).apply {
            if (AndroidVersion.isAtLeastO) setChannelId(Constants.MEDIA_NOTIFICATION_CHANNEL_ID)
            setContentTitle(getString(R.string.external_player_tracking_notification_title))
            setContentText(session.title)
            setSmallIcon(R.drawable.ic_notification)
            setCategory(Notification.CATEGORY_SERVICE)
            setContentIntent(contentIntent)
            setOngoing(true)
            setOnlyAlertOnce(true)
            if (!AndroidVersion.isAtLeastO) setPriority(Notification.PRIORITY_LOW)
        }.build()

        if (AndroidVersion.isAtLeastQ) {
            startForeground(
                Constants.EXTERNAL_PLAYER_TRACKING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(Constants.EXTERNAL_PLAYER_TRACKING_NOTIFICATION_ID, notification)
        }
    }

    private fun Intent.toTrackingSession(): ExternalPlaybackTrackingSession? {
        val itemId = getStringExtra(EXTRA_ITEM_ID)?.toUUIDOrNull() ?: return null
        val playSessionId = getStringExtra(EXTRA_PLAY_SESSION_ID) ?: return null
        val mediaSourceId = getStringExtra(EXTRA_MEDIA_SOURCE_ID) ?: return null
        val playMethodName = getStringExtra(EXTRA_PLAY_METHOD) ?: return null
        val playMethod = PlayMethod.entries.firstOrNull { it.name == playMethodName } ?: return null

        return ExternalPlaybackTrackingSession(
            itemId = itemId,
            title = getStringExtra(EXTRA_TITLE).orEmpty(),
            playSessionId = playSessionId,
            mediaSourceId = mediaSourceId,
            playMethod = playMethod,
            liveStreamId = getStringExtra(EXTRA_LIVE_STREAM_ID),
            audioStreamIndex = getOptionalIntExtra(EXTRA_AUDIO_STREAM_INDEX),
            subtitleStreamIndex = getOptionalIntExtra(EXTRA_SUBTITLE_STREAM_INDEX),
            initialPositionTicks = getLongExtra(EXTRA_INITIAL_POSITION_TICKS, 0L),
            durationTicks = getOptionalLongExtra(EXTRA_DURATION_TICKS),
            playerPackageName = getStringExtra(EXTRA_PLAYER_PACKAGE_NAME),
            webDeviceId = getStringExtra(EXTRA_WEB_DEVICE_ID) ?: return null,
        )
    }

    private fun Intent.getOptionalIntExtra(name: String): Int? =
        if (hasExtra(name)) getIntExtra(name, 0) else null

    private fun Intent.getOptionalLongExtra(name: String): Long? =
        if (hasExtra(name)) getLongExtra(name, 0L) else null

    companion object {
        private const val ACTION_START = "org.jellyfin.mobile.intent.action.START_EXTERNAL_PLAYER_TRACKING"
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAY_SESSION_ID = "playSessionId"
        private const val EXTRA_MEDIA_SOURCE_ID = "mediaSourceId"
        private const val EXTRA_PLAY_METHOD = "playMethod"
        private const val EXTRA_LIVE_STREAM_ID = "liveStreamId"
        private const val EXTRA_AUDIO_STREAM_INDEX = "audioStreamIndex"
        private const val EXTRA_SUBTITLE_STREAM_INDEX = "subtitleStreamIndex"
        private const val EXTRA_INITIAL_POSITION_TICKS = "initialPositionTicks"
        private const val EXTRA_DURATION_TICKS = "durationTicks"
        private const val EXTRA_PLAYER_PACKAGE_NAME = "playerPackageName"
        private const val EXTRA_WEB_DEVICE_ID = "webDeviceId"

        fun start(context: Context, session: ExternalPlaybackTrackingSession) {
            val intent = Intent(context, ExternalPlayerTrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ITEM_ID, session.itemId.toString())
                putExtra(EXTRA_TITLE, session.title)
                putExtra(EXTRA_PLAY_SESSION_ID, session.playSessionId)
                putExtra(EXTRA_MEDIA_SOURCE_ID, session.mediaSourceId)
                putExtra(EXTRA_PLAY_METHOD, session.playMethod.name)
                putExtra(EXTRA_LIVE_STREAM_ID, session.liveStreamId)
                session.audioStreamIndex?.let { putExtra(EXTRA_AUDIO_STREAM_INDEX, it) }
                session.subtitleStreamIndex?.let { putExtra(EXTRA_SUBTITLE_STREAM_INDEX, it) }
                putExtra(EXTRA_INITIAL_POSITION_TICKS, session.initialPositionTicks)
                session.durationTicks?.let { putExtra(EXTRA_DURATION_TICKS, it) }
                putExtra(EXTRA_PLAYER_PACKAGE_NAME, session.playerPackageName)
                putExtra(EXTRA_WEB_DEVICE_ID, session.webDeviceId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ExternalPlayerTrackingService::class.java))
        }
    }
}
