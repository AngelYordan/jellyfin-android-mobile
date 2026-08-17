package org.jellyfin.mobile.bridge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExternalPlayerPlaybackStateTest {
    @Test
    fun `projects a playing VLC position using monotonic time`() {
        val report = observed(
            status = ExternalPlayerPlaybackStatus.PLAYING,
            positionMilliseconds = 30_000L,
            speed = 1f,
            updatedAtMilliseconds = 10_000L,
        ).toPlaybackReport(
            nowRealtimeMilliseconds = 20_000L,
            fallbackPositionTicks = 0L,
            durationTicks = null,
        )

        report?.positionTicks shouldBe 40_000L * TICKS_PER_MILLISECOND
        report?.isPaused shouldBe false
    }

    @Test
    fun `does not advance a paused VLC position`() {
        val report = observed(
            status = ExternalPlayerPlaybackStatus.PAUSED,
            positionMilliseconds = 30_000L,
            speed = 0f,
            updatedAtMilliseconds = 10_000L,
        ).toPlaybackReport(
            nowRealtimeMilliseconds = 60_000L,
            fallbackPositionTicks = 0L,
            durationTicks = null,
        )

        report?.positionTicks shouldBe 30_000L * TICKS_PER_MILLISECOND
        report?.isPaused shouldBe true
    }

    @Test
    fun `uses the playback speed published by VLC`() {
        val report = observed(
            status = ExternalPlayerPlaybackStatus.PLAYING,
            positionMilliseconds = 30_000L,
            speed = 2f,
            updatedAtMilliseconds = 10_000L,
        ).toPlaybackReport(
            nowRealtimeMilliseconds = 20_000L,
            fallbackPositionTicks = 0L,
            durationTicks = null,
        )

        report?.positionTicks shouldBe 50_000L * TICKS_PER_MILLISECOND
        report?.playbackSpeed shouldBe 2f
    }

    @Test
    fun `uses the latest VLC position after a seek`() {
        val report = observed(
            status = ExternalPlayerPlaybackStatus.PLAYING,
            positionMilliseconds = 300_000L,
            speed = 1f,
            updatedAtMilliseconds = 99_000L,
        ).toPlaybackReport(
            nowRealtimeMilliseconds = 100_000L,
            fallbackPositionTicks = 10_000L * TICKS_PER_MILLISECOND,
            durationTicks = null,
        )

        report?.positionTicks shouldBe 301_000L * TICKS_PER_MILLISECOND
    }

    @Test
    fun `preserves pause state when VLC position is unknown`() {
        val fallback = 42_000L * TICKS_PER_MILLISECOND
        val report = observed(
            status = ExternalPlayerPlaybackStatus.PAUSED,
            positionMilliseconds = null,
            speed = 0f,
            updatedAtMilliseconds = 10_000L,
        ).toPlaybackReport(
            nowRealtimeMilliseconds = 20_000L,
            fallbackPositionTicks = fallback,
            durationTicks = null,
        )

        report?.positionTicks shouldBe fallback
        report?.isPaused shouldBe true
    }

    @Test
    fun `caps the projected position at the media duration`() {
        val durationTicks = 100_000L * TICKS_PER_MILLISECOND
        val report = observed(
            status = ExternalPlayerPlaybackStatus.PLAYING,
            positionMilliseconds = 95_000L,
            speed = 1f,
            updatedAtMilliseconds = 10_000L,
        ).toPlaybackReport(
            nowRealtimeMilliseconds = 20_000L,
            fallbackPositionTicks = 0L,
            durationTicks = durationTicks,
        )

        report?.positionTicks shouldBe durationTicks
    }

    @Test
    fun `ignores an unknown MediaSession state`() {
        val report = observed(
            status = ExternalPlayerPlaybackStatus.UNKNOWN,
            positionMilliseconds = 30_000L,
            speed = 1f,
            updatedAtMilliseconds = 10_000L,
        ).toPlaybackReport(
            nowRealtimeMilliseconds = 20_000L,
            fallbackPositionTicks = 0L,
            durationTicks = null,
        )

        report shouldBe null
    }

    private fun observed(
        status: ExternalPlayerPlaybackStatus,
        positionMilliseconds: Long?,
        speed: Float,
        updatedAtMilliseconds: Long,
    ) = ExternalPlayerObservedPlaybackState(
        status = status,
        positionMilliseconds = positionMilliseconds,
        playbackSpeed = speed,
        lastPositionUpdateRealtimeMilliseconds = updatedAtMilliseconds,
    )

    companion object {
        private const val TICKS_PER_MILLISECOND = 10_000L
    }
}
