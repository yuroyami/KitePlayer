package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The engine's side of a file still being written (#430). Its length follows the file ahead of
 * playback, its reader rather than the stall limit calls its end, and an item that nothing can
 * read as it grows says so and plays as it stands.
 */
class GrowingFileTest {

    @Test
    fun theLengthFollowsTheFileAheadOfPlayback() = runTest {
        var writtenUs = 10_000_000L
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 60_000_000, durationIsEstimate = true, declaredDurationNowUs = { writtenUs }),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        assertEquals(10.seconds, harness.core.snapshots.value.duration)
        // The writer moves on while playback is still far behind it.
        writtenUs = 25_000_000L
        harness.run(1.seconds)
        assertEquals(25.seconds, harness.core.snapshots.value.duration, "the seek bar stayed at the length from the open")
        assertTrue(harness.core.snapshots.value.durationIsEstimate)
        harness.close()
    }

    @Test
    fun theStallLimitWaitsForTheFileAsLongAsItsItemSays() = runTest(timeout = 20.seconds) {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 60_000_000),
            faults = FaultPlan().apply { readWedgesAfter = 300 },
            config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 1.seconds)),
        )
        harness.attachRenderer()
        harness.core.open(MediaItem("scripted://media", growth = FileGrowth(endsAfter = 3.seconds)))
        harness.core.play()
        val wedgedAt = awaitWedge(harness)
        // Past the stall limit and past the item's own wait, within the margin the reader is given.
        runUntil(harness, wedgedAt + 7.seconds)
        assertNotEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status, "${harness.core.snapshots.value.error}")
        runUntil(harness, wedgedAt + 8_300.milliseconds)
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status, "a reader that never answers still ends")
        harness.close()
    }

    @Test
    fun aLocalFileNothingCanReadAsItGrowsSaysSoAndPlays() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.attachRenderer()
        // No provider serves local files in the engine's own tests, so the path is left to the backend.
        harness.core.open(MediaItem("/recordings/live.ts?token=secret", growth = FileGrowth()))
        harness.core.play()
        harness.run(500.milliseconds)
        val warnings = harness.core.warningHistory().map { it.warning }
        assertEquals(listOf(PlaybackWarning.GrowthUnavailable("live.ts")), warnings.filterIsInstance<PlaybackWarning.GrowthUnavailable>())
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aCompleteFileSaysNothing() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.attachRenderer()
        harness.core.open(MediaItem("/recordings/done.ts"))
        harness.core.play()
        harness.run(500.milliseconds)
        assertTrue(harness.core.warningHistory().none { it.warning is PlaybackWarning.GrowthUnavailable })
        harness.close()
    }

    private suspend fun awaitWedge(harness: CoreHarness): Duration {
        repeat(3_000) {
            harness.source.wedgedAtNanos?.let { return it.nanoseconds }
            harness.run(10.milliseconds)
        }
        error("the scripted source never wedged")
    }

    private suspend fun runUntil(harness: CoreHarness, at: Duration) {
        val left = at - harness.clock.nanos().nanoseconds
        if (left > Duration.ZERO) harness.run(left)
    }
}
