package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Going to the start of a subtitle line, and shifting the delay to one (#491), with lines at 1 s,
 * 5 s and 9 s, each two seconds long, as the issue's own example has them.
 */
class SubtitleLineTest {

    private val lines = listOf(1, 5, 9).map { second ->
        SubtitleCue.Text(second * 1_000_000L, (second + 2) * 1_000_000L, listOf(StyledSpan("line at $second s")))
    }

    private suspend fun CoreHarness.playedTo(seconds: Int): KitePlayer {
        val player = KitePlayer(core)
        openWithRenderer()
        player.play()
        run(seconds.seconds)
        return player
    }

    @Test
    fun eachOffsetGoesToItsLine() = runTest {
        for ((offset, expected) in listOf(0 to 5.seconds, -1 to 1.seconds, 1 to 9.seconds)) {
            val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000, subtitleCues = lines))
            val player = harness.playedTo(6)
            assertEquals(expected, player.seekToSubtitleLine(offset), "offset $offset")
            harness.run(100.milliseconds)
            val landed = player.position()
            assertEquals(true, landed >= expected && landed < expected + 100.milliseconds, "offset $offset landed at $landed")
            harness.close()
        }
    }

    /** Pressed again and again while paused, each press moves one line and skips none (mpv issue 11445). */
    @Test
    fun repeatedPressesWhilePausedMoveOneLineEach() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000, subtitleCues = lines))
        val player = harness.playedTo(10)
        player.pause()
        harness.run(200.milliseconds)
        assertEquals(9.seconds, player.seekToSubtitleLine(0))
        assertEquals(5.seconds, player.seekToSubtitleLine(-1))
        assertEquals(1.seconds, player.seekToSubtitleLine(-1))
        assertFailsWith<IllegalStateException> { player.seekToSubtitleLine(-1) }
        assertEquals(5.seconds, player.seekToSubtitleLine(1))
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aDelayStepStartsTheChosenLineNow() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000, subtitleCues = lines))
        val player = harness.playedTo(6)
        player.pause()
        harness.run(100.milliseconds)
        val now = player.position()
        val delay = player.stepSubtitleDelay(1)
        assertEquals(now - 9.seconds, delay, "the next line does not start now")
        assertEquals(delay, player.state.value.subtitleDelay)
        // With that delay, the line at 9 s shows now, so a step of 0 changes nothing.
        assertEquals(delay, player.stepSubtitleDelay(0))
        harness.close()
    }

    @Test
    fun withNoSubtitleBothRefuse() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        val player = harness.playedTo(2)
        assertFailsWith<IllegalStateException> { player.seekToSubtitleLine(0) }
        assertFailsWith<IllegalStateException> { player.stepSubtitleDelay(1) }
        harness.close()
    }

    @Test
    fun aLinePastTheLastIsRefused() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000, subtitleCues = lines))
        val player = harness.playedTo(10)
        assertFailsWith<IllegalStateException> { player.seekToSubtitleLine(1) }
        harness.close()
    }
}
