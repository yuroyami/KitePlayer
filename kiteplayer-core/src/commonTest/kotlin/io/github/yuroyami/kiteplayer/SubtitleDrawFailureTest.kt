@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Subtitles that cannot be drawn cost the subtitles, never the player.
 *
 * The drawing runs on its own lane, and a failure there has no caller to reach. So the engine
 * catches it, takes the old text off the screen, warns once and keeps playing.
 */
class SubtitleDrawFailureTest {

    private fun cue(start: Long, end: Long, text: String): SubtitleCue =
        SubtitleCue.Text(start, end, listOf(StyledSpan(text)))

    // The first two overlap, so the second arrives while the first is on screen.
    private val script = MediaScript(
        durationUs = 6_000_000,
        subtitleCues = listOf(
            cue(500_000, 2_500_000, "first"),
            cue(1_500_000, 3_000_000, "second"),
            cue(4_000_000, 5_000_000, "third"),
        ),
    )

    private fun CoreHarness.notDrawnWarnings(): List<PlaybackWarning.SubtitlesNotDrawn> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.SubtitlesNotDrawn>()

    @Test
    fun aRasterizerThatThrowsClearsTheTextWarnsOnceAndPlaybackGoesOn() = runTest {
        val harness = CoreHarness(this, script = script)
        val renderer = checkNotNull(harness.renderer)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(800.milliseconds)
        assertEquals(1, renderer.overlays.last()?.images?.size, "the first line was never drawn")

        harness.output.rasterizeFailure = IllegalStateException("the font engine went away")
        harness.run(1_000.milliseconds)
        assertEquals(
            emptyList(),
            renderer.overlays.last()?.images,
            "the first line stayed on screen after the drawing of the next overlay failed",
        )
        // A second change fails the same way, and warns no more.
        harness.run(1_000.milliseconds)
        val warnings = harness.notDrawnWarnings()
        assertEquals(1, warnings.size, "one broken rasterizer warned $warnings")
        assertTrue("the font engine went away" in warnings.single().detail, warnings.single().detail)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        assertTrue(harness.core.progress.value.position >= 2_500.milliseconds, "playback stopped with the subtitles")

        // A screenshot takes the same road: no subtitles on it, and no failure out of it.
        assertNull(harness.core.captureFrame(withSubtitles = true).overlay)

        // Once the rasterizer works again, the next line draws.
        harness.output.rasterizeFailure = null
        harness.run(1_500.milliseconds)
        assertEquals(1, renderer.overlays.last()?.images?.size, "the line after the failure was never drawn")
        harness.close()
    }

    @Test
    fun aRendererThatRefusesTheOverlayCostsTheSubtitlesAndNotThePlayer() = runTest {
        val harness = CoreHarness(this, script = script)
        val renderer = checkNotNull(harness.renderer)
        renderer.overlayFailure = IllegalStateException("texture upload failed")
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3_000.milliseconds)
        val warnings = harness.notDrawnWarnings()
        assertEquals(1, warnings.size, "one refusing renderer warned $warnings")
        assertTrue("texture upload failed" in warnings.single().detail, warnings.single().detail)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    private fun CoreHarness.notReleasedWarnings(): List<String> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.ResourcesNotReleased>().map { it.detail }

    /** Plays until the first line is on screen, then makes the renderer refuse to take it down. */
    private suspend fun CoreHarness.firstLineOnARendererThatCannotWithdraw() {
        openWithRenderer()
        core.play()
        run(800.milliseconds)
        val renderer = checkNotNull(renderer)
        assertEquals(1, renderer.overlays.last()?.images?.size, "the first line was never drawn")
        renderer.withdrawalFailure = IllegalStateException("the surface is gone")
    }

    @Test
    fun aRendererThatCannotWithdrawTheOverlayStillLetsCloseReleaseTheSession() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.firstLineOnARendererThatCannotWithdraw()
        val session = harness.session
        // Close reports no failure: the steps after the withdrawal ran, and its refusal is a warning.
        harness.close()
        assertEquals(1, session.closeCount, "the backend session was never closed (#472)")
        assertTrue(
            harness.notReleasedWarnings().any { "subtitle overlay withdrawal: the surface is gone" in it },
            "the refused withdrawal left no trace: ${harness.notReleasedWarnings()}",
        )
    }

    @Test
    fun aRendererThatCannotWithdrawTheOverlayStillLetsStopReleaseTheSession() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.firstLineOnARendererThatCannotWithdraw()
        val session = harness.session
        harness.core.stop()
        harness.run(100.milliseconds)
        assertEquals(1, session.closeCount, "the backend session was never closed (#472)")
        assertEquals(0, harness.ledger.liveCount, "a packet or a frame of the stopped session leaked")
        checkNotNull(harness.renderer).withdrawalFailure = null
        harness.close()
    }
}
