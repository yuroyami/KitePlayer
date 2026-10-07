package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The time of day of a stream's positions (#444): published with the progress, mapped both ways,
 * sought to, and followed by an external clock. The scripted stream states that its start was
 * broadcast at [ORIGIN].
 */
class TimeOfDayTest {

    private val dated = MediaScript(durationUs = 60_000_000, timeOfDayOriginMillis = ORIGIN)

    @Test
    fun theProgressCarriesTheTimeOfDayOfThePositionAndOfTheListedMoments() = runTest {
        val harness = CoreHarness(this, script = dated)
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://dated"))
        player.play()
        harness.run(3.seconds)
        val progress = player.progress.value
        assertTrue(progress.position > 2.seconds, "playback did not move: ${progress.position}")
        assertEquals(ORIGIN + progress.position.inWholeMilliseconds, progress.timeOfDayMillis)
        assertEquals(ORIGIN, progress.firstTimeOfDayMillis)
        assertEquals(ORIGIN + 60_000, progress.lastTimeOfDayMillis)
        harness.close()
    }

    @Test
    fun aPositionAndItsTimeOfDayMapBothWays() = runTest {
        val harness = CoreHarness(this, script = dated)
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://dated"))
        harness.run(100.milliseconds)
        assertEquals(ORIGIN + 12_500, player.timeOfDayAt(12_500.milliseconds))
        assertEquals(12_500.milliseconds, player.positionAtTimeOfDay(ORIGIN + 12_500))
        assertNull(player.positionAtTimeOfDay(ORIGIN - 1_000), "a moment before the stream has no position")
        harness.close()
    }

    @Test
    fun aClippedItemCountsItsTimesFromItsOwnStart() = runTest {
        val harness = CoreHarness(this, script = dated)
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://dated", clip = MediaClip(start = 20.seconds, end = 40.seconds)))
        harness.run(100.milliseconds)
        assertEquals(ORIGIN + 25_000, player.timeOfDayAt(5.seconds), "the item's 5 s is the file's 25 s")
        assertEquals(5.seconds, player.positionAtTimeOfDay(ORIGIN + 25_000))
        harness.close()
    }

    @Test
    fun aSeekToATimeOfDayLandsOnItsPosition() = runTest {
        val harness = CoreHarness(this, script = dated)
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://dated"))
        player.seekToTimeOfDay(ORIGIN + 42_000)
        // Past one progress interval, so the flow has published the landing.
        harness.run(500.milliseconds)
        assertEquals(42.seconds, player.position())
        assertEquals(ORIGIN + 42_000, player.progress.value.timeOfDayMillis)
        assertFailsWith<IllegalArgumentException> { player.seekToTimeOfDay(ORIGIN + 3_600_000) }
        harness.close()
    }

    @Test
    fun theTimeOfDayClockAnswersThePositionOfTheMomentItIsGiven() = runTest {
        val harness = CoreHarness(this, script = dated)
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://dated"))
        harness.run(100.milliseconds)
        val clock = player.timeOfDayClock { atNanos -> ORIGIN + atNanos / 1_000_000 }
        assertEquals(30.seconds, clock.positionAt(30_000_000_000))
        assertNull(clock.positionAt(-5_000_000_000), "a moment before the stream has no position")
        assertNull(player.timeOfDayClock { null }.positionAt(0))
        harness.close()
    }

    @Test
    fun aStreamThatStatesNoTimePublishesNone() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000))
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://undated"))
        player.play()
        harness.run(2.seconds)
        val progress = player.progress.value
        assertNull(progress.timeOfDayMillis)
        assertNull(progress.firstTimeOfDayMillis)
        assertNull(progress.lastTimeOfDayMillis)
        assertNull(player.timeOfDayAt(Duration.ZERO))
        assertNull(player.positionAtTimeOfDay(ORIGIN))
        assertFailsWith<IllegalArgumentException> { player.seekToTimeOfDay(ORIGIN) }
        harness.close()
    }

    @Test
    fun noTimeOutlivesTheStreamThatStatedIt() = runTest {
        val harness = CoreHarness(this, script = dated)
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://dated"))
        harness.run(100.milliseconds)
        assertNotNull(player.timeOfDayAt(Duration.ZERO))
        player.stop()
        harness.run(500.milliseconds)
        assertNull(player.timeOfDayAt(Duration.ZERO), "a stopped player still answered for the stream it closed")
        assertNull(player.progress.value.timeOfDayMillis)
        harness.close()
    }

    private companion object {
        /** 2026-10-07T21:34:00Z. */
        const val ORIGIN = 1_791_408_840_000L
    }
}
