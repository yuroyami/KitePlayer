package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Playback follows a clock the caller owns (#91): a slow drift closes through a small speed change
 * with no seek, a jump is one seek, and a clock that falls silent or stops moving leaves playback
 * running on its own and warns once. Play and pause stay with the commands.
 */
class ExternalClockTest {

    /** A scripted external clock: [rate] media seconds a second from [startUs] at [originNanos]. */
    private class ScriptedClock(var originNanos: Long, var startUs: Long, var rate: Double = 1.0) : ExternalClock {
        var silent: Boolean = false
        var frozenAtUs: Long? = null
        var asked: Int = 0

        override fun positionAt(atNanos: Long): Duration? {
            asked++
            if (silent) return null
            frozenAtUs?.let { return it.microseconds }
            return (startUs + ((atNanos - originNanos) / 1_000.0 * rate).toLong()).microseconds
        }

        fun at(nanos: Long): Long = startUs + ((nanos - originNanos) / 1_000.0 * rate).toLong()
    }

    private suspend fun started(harness: CoreHarness): ScriptedClock {
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        val now = harness.clock.nanos()
        return ScriptedClock(originNanos = now, startUs = harness.core.position().inWholeMicroseconds)
    }

    private fun CoreHarness.error(clock: ScriptedClock): Long =
        clock.at(this.clock.nanos()) - core.position().inWholeMicroseconds

    @Test
    fun aSlowDriftClosesThroughTheSpeedWithNoSeek() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000))
        val external = started(harness)
        external.rate = 1.003
        harness.core.setExternalClock(external)
        val seeksBefore = harness.source.seeks
        harness.run(10.seconds)
        var worst = 0L
        repeat(20) {
            harness.run(250.milliseconds)
            worst = maxOf(worst, abs(harness.error(external)))
        }
        assertTrue(worst <= 20_000L, "a 0.3 percent drift was followed within ${worst / 1000} ms")
        assertEquals(seeksBefore, harness.source.seeks, "a drift needs no seek")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aJumpOfTheClockIsOneSeek() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000))
        val external = started(harness)
        harness.core.setExternalClock(external)
        harness.run(2.seconds)
        val seeksBefore = harness.source.seeks
        external.startUs += 2_000_000L
        harness.run(5.seconds)
        assertEquals(seeksBefore + 1, harness.source.seeks, "a 2 s jump is exactly one seek")
        assertTrue(abs(harness.error(external)) <= 60_000L, "after the jump the player is ${harness.error(external) / 1000} ms off")
        harness.close()
    }

    @Test
    fun aSilentClockLeavesPlaybackRunningAndWarnsOnce() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000))
        val external = started(harness)
        harness.core.setExternalClock(external)
        harness.run(1.seconds)
        external.silent = true
        val before = harness.core.position()
        harness.run(5.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "silence is not a pause")
        assertTrue(harness.core.position() - before >= 4.seconds, "playback runs on its own clock")
        val silent = harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.ExternalClockSilent>()
        assertEquals(1, silent.size, "one warning for one silence: $silent")
        harness.close()
    }

    @Test
    fun aClockThatStopsMovingIsNotFollowedBackward() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000))
        val external = started(harness)
        harness.core.setExternalClock(external)
        harness.run(1.seconds)
        external.frozenAtUs = external.at(harness.clock.nanos())
        val seeksBefore = harness.source.seeks
        harness.run(5.seconds)
        assertEquals(seeksBefore, harness.source.seeks, "a stopped clock is not chased with seeks")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "a stopped clock is not a pause")
        assertTrue(
            harness.core.warningHistory().any { it.warning is PlaybackWarning.ExternalClockSilent },
            "a stopped clock is warned as silent",
        )
        harness.close()
    }

    @Test
    fun theExternalModeWithNoClockPlaysOnItsAudioClockAndSaysSo() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 60_000_000),
            config = PlayerConfig(syncMode = SyncMode.ExternalMaster),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(4.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        val silent = harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.ExternalClockSilent>()
        assertEquals(1, silent.size, "the missing clock is said once: $silent")
        harness.close()
    }

    @Test
    fun aPauseStaysAPauseWhileTheClockRuns() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000))
        val external = started(harness)
        harness.core.setExternalClock(external)
        harness.run(1.seconds)
        harness.core.pause()
        harness.run(3.seconds)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "the clock does not resume a pause")
        val askedWhilePaused = external.asked
        harness.run(1.seconds)
        assertEquals(askedWhilePaused, external.asked, "a paused player does not ask the clock")
        harness.close()
    }
}
