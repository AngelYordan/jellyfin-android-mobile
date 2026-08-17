package org.jellyfin.mobile.bridge

import kotlin.math.roundToLong

enum class ExternalPlayerPlaybackStatus {
    PLAYING,
    PAUSED,
    BUFFERING,
    STOPPED,
    UNKNOWN,
}

data class ExternalPlayerObservedPlaybackState(
    val status: ExternalPlayerPlaybackStatus,
    val positionMilliseconds: Long?,
    val playbackSpeed: Float,
    val lastPositionUpdateRealtimeMilliseconds: Long,
)

data class ExternalPlayerPlaybackReport(
    val positionTicks: Long,
    val isPaused: Boolean,
    val playbackSpeed: Float,
)

/**
 * Converts the state published by an external player's MediaSession into Jellyfin progress data.
 *
 * Android's position is the value at [lastPositionUpdateRealtimeMilliseconds]. While playing, the
 * current position can therefore be projected using the monotonic clock and published speed.
 */
fun ExternalPlayerObservedPlaybackState.toPlaybackReport(
    nowRealtimeMilliseconds: Long,
    fallbackPositionTicks: Long,
    durationTicks: Long?,
): ExternalPlayerPlaybackReport? {
    if (status == ExternalPlayerPlaybackStatus.UNKNOWN) return null

    val basePositionMilliseconds = positionMilliseconds?.takeIf { it >= 0L }
    val projectedPositionMilliseconds = basePositionMilliseconds?.let { position ->
        val elapsedMilliseconds = (nowRealtimeMilliseconds - lastPositionUpdateRealtimeMilliseconds)
            .coerceAtLeast(0L)
        val projectedElapsedMilliseconds = when {
            status != ExternalPlayerPlaybackStatus.PLAYING -> 0L
            lastPositionUpdateRealtimeMilliseconds <= 0L -> 0L
            !playbackSpeed.isFinite() -> 0L
            else -> (elapsedMilliseconds * playbackSpeed).roundToLong()
        }
        (position + projectedElapsedMilliseconds).coerceAtLeast(0L)
    }
    val positionTicks = projectedPositionMilliseconds
        ?.times(TICKS_PER_MILLISECOND)
        ?: fallbackPositionTicks
    val boundedPositionTicks = durationTicks?.let(positionTicks::coerceAtMost) ?: positionTicks

    return ExternalPlayerPlaybackReport(
        positionTicks = boundedPositionTicks,
        isPaused = status != ExternalPlayerPlaybackStatus.PLAYING,
        playbackSpeed = playbackSpeed,
    )
}

private const val TICKS_PER_MILLISECOND = 10_000L
