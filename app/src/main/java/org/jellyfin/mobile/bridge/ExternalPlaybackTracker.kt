package org.jellyfin.mobile.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ExternalPlaybackTrackingSession(
    val itemId: UUID,
    val title: String,
    val playSessionId: String,
    val mediaSourceId: String,
    val playMethod: PlayMethod,
    val liveStreamId: String?,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
    val initialPositionTicks: Long,
    val durationTicks: Long?,
    val playerPackageName: String?,
    val webDeviceId: String,
)

data class ExternalPlaybackHeartbeat(
    val session: ExternalPlaybackTrackingSession,
    val sequence: Long,
    val elapsedMilliseconds: Long,
)

data class ExternalPlaybackTrackingStop(
    val session: ExternalPlaybackTrackingSession,
    val elapsedMilliseconds: Long,
    val reason: String,
)

data class ExternalPlaybackReconciliation(
    val estimatedPositionTicks: Long,
    val returnedPositionTicks: Long?,
    val returnedDurationTicks: Long?,
) {
    val differenceTicks: Long?
        get() = returnedPositionTicks?.minus(estimatedPositionTicks)
}

enum class ExternalPlaybackTrackerStartResult {
    STARTED,
    ALREADY_RUNNING,
    REPLACED,
}

/**
 * Schedules server check-ins independently of the Activity and WebView lifecycle.
 */
class ExternalPlaybackTracker(
    private val scope: CoroutineScope,
    private val updateInterval: Duration = DEFAULT_UPDATE_INTERVAL,
    private val monotonicTimeMilliseconds: () -> Long = { System.nanoTime() / NANOSECONDS_PER_MILLISECOND },
    private val onHeartbeat: suspend (ExternalPlaybackHeartbeat) -> Unit,
    private val onHeartbeatError: (ExternalPlaybackHeartbeat, Throwable) -> Unit,
) {
    private data class ActiveTracking(
        val session: ExternalPlaybackTrackingSession,
        val startedAtMilliseconds: Long,
        val reportingScope: CoroutineScope,
        val job: Job,
        var sequence: Long = 0L,
    )

    private val lock = Any()
    private var activeTracking: ActiveTracking? = null

    init {
        require(updateInterval.isPositive()) { "updateInterval must be positive" }
    }

    val activeSession: ExternalPlaybackTrackingSession?
        get() = synchronized(lock) { activeTracking?.session }

    @Suppress("TooGenericExceptionCaught") // The injected reporting boundary may fail with any non-fatal exception.
    fun start(
        session: ExternalPlaybackTrackingSession,
    ): ExternalPlaybackTrackerStartResult = synchronized(lock) {
        val previous = activeTracking
        if (previous?.session == session && previous.job.isActive) {
            return ExternalPlaybackTrackerStartResult.ALREADY_RUNNING
        }

        previous?.job?.cancel()
        val startedAtMilliseconds = monotonicTimeMilliseconds()
        val job = SupervisorJob(scope.coroutineContext[Job])
        val reportingScope = CoroutineScope(scope.coroutineContext + job)
        reportingScope.launch {
            while (isActive) {
                delay(updateInterval)
                requestHeartbeat()
            }
        }
        activeTracking = ActiveTracking(session, startedAtMilliseconds, reportingScope, job)

        if (previous == null) {
            ExternalPlaybackTrackerStartResult.STARTED
        } else {
            ExternalPlaybackTrackerStartResult.REPLACED
        }
    }

    /**
     * Requests an immediate check-in for the active session.
     *
     * VLC state transitions use this in addition to the periodic schedule. Playback Reporting
     * throttles progress events, so reporting both the transition and subsequent short heartbeats
     * prevents a pause event that lands inside its throttle window from being lost for a long time.
     */
    fun requestHeartbeat(): Boolean {
        val (tracking, heartbeat) = synchronized(lock) {
            val tracking = activeTracking ?: return false
            tracking.sequence++
            tracking to ExternalPlaybackHeartbeat(
                session = tracking.session,
                sequence = tracking.sequence,
                elapsedMilliseconds = elapsedSince(tracking.startedAtMilliseconds),
            )
        }
        tracking.reportingScope.launch {
            try {
                onHeartbeat(heartbeat)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onHeartbeatError(heartbeat, error)
            }
        }
        return true
    }

    fun stop(reason: String): ExternalPlaybackTrackingStop? = synchronized(lock) {
        val tracking = activeTracking ?: return null
        activeTracking = null
        tracking.job.cancel()
        ExternalPlaybackTrackingStop(
            session = tracking.session,
            elapsedMilliseconds = elapsedSince(tracking.startedAtMilliseconds),
            reason = reason,
        )
    }

    private fun elapsedSince(startedAtMilliseconds: Long): Long =
        (monotonicTimeMilliseconds() - startedAtMilliseconds).coerceAtLeast(0L)

    companion object {
        val DEFAULT_UPDATE_INTERVAL: Duration = 10.seconds
        private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
        private const val TICKS_PER_MILLISECOND = 10_000L

        fun reconcile(
            stop: ExternalPlaybackTrackingStop,
            returnedPositionMilliseconds: Long?,
            returnedDurationMilliseconds: Long?,
        ): ExternalPlaybackReconciliation {
            val returnedDurationTicks = returnedDurationMilliseconds
                ?.takeIf { it >= 0L }
                ?.times(TICKS_PER_MILLISECOND)
            val durationTicks = returnedDurationTicks ?: stop.session.durationTicks
            val estimatedPositionTicks = (
                stop.session.initialPositionTicks + stop.elapsedMilliseconds * TICKS_PER_MILLISECOND
                ).let { position -> durationTicks?.let(position::coerceAtMost) ?: position }

            return ExternalPlaybackReconciliation(
                estimatedPositionTicks = estimatedPositionTicks,
                returnedPositionTicks = returnedPositionMilliseconds
                    ?.takeIf { it >= 0L }
                    ?.times(TICKS_PER_MILLISECOND),
                returnedDurationTicks = returnedDurationTicks,
            )
        }
    }
}
