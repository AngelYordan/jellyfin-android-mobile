package org.jellyfin.mobile.bridge

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.PlayMethod
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlaybackTrackerTest {
    @Test
    fun `sends periodic heartbeats and stops cleanly`() = runTest {
        val heartbeats = mutableListOf<ExternalPlaybackHeartbeat>()
        val tracker = createTracker(heartbeats)

        tracker.start(SESSION) shouldBe ExternalPlaybackTrackerStartResult.STARTED
        runCurrent()
        advanceTimeBy(9.seconds.inWholeMilliseconds)
        runCurrent()
        heartbeats shouldContainExactly emptyList()

        advanceTimeBy(1.seconds.inWholeMilliseconds)
        runCurrent()
        heartbeats.map { it.sequence } shouldContainExactly listOf(1L)
        heartbeats.single().elapsedMilliseconds shouldBe 10.seconds.inWholeMilliseconds

        tracker.stop("player_result")?.session shouldBe SESSION
        advanceTimeBy(20.seconds.inWholeMilliseconds)
        runCurrent()
        heartbeats.map { it.sequence } shouldContainExactly listOf(1L)
        tracker.stop("duplicate_stop") shouldBe null
    }

    @Test
    fun `does not create a duplicate tracker for the same session`() = runTest {
        val heartbeats = mutableListOf<ExternalPlaybackHeartbeat>()
        val tracker = createTracker(heartbeats)

        tracker.start(SESSION) shouldBe ExternalPlaybackTrackerStartResult.STARTED
        tracker.start(SESSION) shouldBe ExternalPlaybackTrackerStartResult.ALREADY_RUNNING
        runCurrent()
        advanceTimeBy(10.seconds.inWholeMilliseconds)
        runCurrent()

        heartbeats.map { it.sequence } shouldContainExactly listOf(1L)
        tracker.stop("test_complete")
    }

    @Test
    fun `replaces a previous external playback session`() = runTest {
        val heartbeats = mutableListOf<ExternalPlaybackHeartbeat>()
        val tracker = createTracker(heartbeats)
        val replacement = SESSION.copy(itemId = UUID.randomUUID(), playSessionId = "replacement")

        tracker.start(SESSION) shouldBe ExternalPlaybackTrackerStartResult.STARTED
        runCurrent()
        advanceTimeBy(5.seconds.inWholeMilliseconds)
        tracker.start(replacement) shouldBe ExternalPlaybackTrackerStartResult.REPLACED
        runCurrent()
        advanceTimeBy(10.seconds.inWholeMilliseconds)
        runCurrent()

        heartbeats.map { it.session } shouldContainExactly listOf(replacement)
        tracker.stop("test_complete")
    }

    @Test
    fun `continues after a reporting error`() = runTest {
        val heartbeats = mutableListOf<ExternalPlaybackHeartbeat>()
        val errors = mutableListOf<Throwable>()
        var reports = 0
        val tracker = ExternalPlaybackTracker(
            scope = this,
            updateInterval = 10.seconds,
            monotonicTimeMilliseconds = { testScheduler.currentTime },
            onHeartbeat = { heartbeat ->
                reports++
                if (reports == 1) error("network failure")
                heartbeats += heartbeat
            },
            onHeartbeatError = { _, error -> errors += error },
        )

        tracker.start(SESSION)
        runCurrent()
        advanceTimeBy(20.seconds.inWholeMilliseconds)
        runCurrent()

        errors.size shouldBe 1
        heartbeats.map { it.sequence } shouldContainExactly listOf(2L)
        tracker.activeSession shouldBe SESSION
        tracker.stop("test_complete")
    }

    @Test
    fun `sends an immediate heartbeat for a player state transition`() = runTest {
        val heartbeats = mutableListOf<ExternalPlaybackHeartbeat>()
        val tracker = createTracker(heartbeats)

        tracker.start(SESSION)
        runCurrent()
        advanceTimeBy(3.seconds.inWholeMilliseconds)

        tracker.requestHeartbeat() shouldBe true
        runCurrent()

        heartbeats.map { it.sequence } shouldContainExactly listOf(1L)
        heartbeats.single().elapsedMilliseconds shouldBe 3.seconds.inWholeMilliseconds

        advanceTimeBy(7.seconds.inWholeMilliseconds)
        runCurrent()
        heartbeats.map { it.sequence } shouldContainExactly listOf(1L, 2L)

        tracker.stop("test_complete")
        tracker.requestHeartbeat() shouldBe false
    }

    @Test
    fun `reconciles wall clock estimate with returned VLC position`() {
        val stop = ExternalPlaybackTrackingStop(
            session = SESSION,
            elapsedMilliseconds = 90.seconds.inWholeMilliseconds,
            reason = "vlc_result",
        )

        val result = ExternalPlaybackTracker.reconcile(
            stop = stop,
            returnedPositionMilliseconds = 70.seconds.inWholeMilliseconds,
            returnedDurationMilliseconds = 120.seconds.inWholeMilliseconds,
        )

        result.estimatedPositionTicks shouldBe 100.seconds.inWholeMilliseconds * TICKS_PER_MILLISECOND
        result.returnedPositionTicks shouldBe 70.seconds.inWholeMilliseconds * TICKS_PER_MILLISECOND
        result.differenceTicks shouldBe -30.seconds.inWholeMilliseconds * TICKS_PER_MILLISECOND
    }

    @Test
    fun `caps diagnostic estimate at known duration`() {
        val stop = ExternalPlaybackTrackingStop(
            session = SESSION,
            elapsedMilliseconds = 10_000.seconds.inWholeMilliseconds,
            reason = "vlc_result",
        )

        val result = ExternalPlaybackTracker.reconcile(stop, null, null)

        result.estimatedPositionTicks shouldBe SESSION.durationTicks
        result.returnedPositionTicks shouldBe null
    }

    private fun kotlinx.coroutines.test.TestScope.createTracker(
        heartbeats: MutableList<ExternalPlaybackHeartbeat>,
    ) = ExternalPlaybackTracker(
        scope = this,
        updateInterval = 10.seconds,
        monotonicTimeMilliseconds = { testScheduler.currentTime },
        onHeartbeat = heartbeats::add,
        onHeartbeatError = { _, error -> throw error },
    )

    companion object {
        private const val TICKS_PER_MILLISECOND = 10_000L
        private val SESSION = ExternalPlaybackTrackingSession(
            itemId = UUID.randomUUID(),
            title = "Test movie",
            playSessionId = "play-session",
            mediaSourceId = "media-source",
            playMethod = PlayMethod.DIRECT_PLAY,
            liveStreamId = null,
            audioStreamIndex = 1,
            subtitleStreamIndex = 2,
            initialPositionTicks = 10.seconds.inWholeMilliseconds * TICKS_PER_MILLISECOND,
            durationTicks = 120.seconds.inWholeMilliseconds * TICKS_PER_MILLISECOND,
            playerPackageName = "org.videolan.vlc",
            webDeviceId = "web-device-id",
        )
    }
}
