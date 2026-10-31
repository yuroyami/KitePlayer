@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * A screenshot of a subtitled film with its subtitles on it.
 *
 * The capture path takes the decoded frame before any renderer sees it, and renderers are what
 * composite the overlay, so a screenshot never had the text. Asking for it lays the overlay out
 * for the FRAME's own size rather than reusing the one drawn for the screen, because a screenshot
 * is the frame's shape and the display is usually a different one.
 */
class CaptureWithSubtitlesTest {

    private fun script() = MediaScript(
        durationUs = 6_000_000,
        subtitleCues = listOf(
            SubtitleCue.Text(500_000, 3_000_000, listOf(StyledSpan("a closing line"))),
        ),
    )

    @Test
    fun `a capture that asks for subtitles carries them`() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(800.milliseconds)

        val captured = harness.core.captureFrame(withSubtitles = true)
        val overlay = assertNotNull(captured.overlay, "the capture came back with no subtitles on it")
        assertEquals(
            captured.size.displayWidth,
            overlay.viewportWidth,
            "the overlay was laid out for a screen and not for the frame",
        )
        assertEquals(captured.size.height, overlay.viewportHeight)
        harness.close()
    }

    @Test
    fun `a capture that does not ask keeps its pixels alone`() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(800.milliseconds)

        assertNull(
            harness.core.captureFrame().overlay,
            "subtitles arrived on a screenshot that never asked for them",
        )
        harness.close()
    }

    @Test
    fun `a moment with nothing showing carries no overlay`() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.core.play()
        // Past the only cue's end, so there is nothing to draw.
        harness.run(3500.milliseconds)

        assertNull(
            harness.core.captureFrame(withSubtitles = true).overlay,
            "an empty overlay was attached where nothing was on screen",
        )
        harness.close()
    }

    @Test
    fun aCaptureIsTurnedAsTheViewerTurnedThePictureAndItsSubtitlesUpright() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        val turn = VideoTransform(rotationDegrees = 90, mirrorHorizontal = true)
        harness.core.post(io.github.yuroyami.kiteplayer.internal.CoreCommand.SetVideoTransform(turn, CompletableDeferred()))
        harness.core.play()
        harness.run(800.milliseconds)

        val captured = harness.core.captureFrame(withSubtitles = true)
        assertEquals(90, captured.rotationDegrees, "the capture lost the viewer's turn")
        assertEquals(true, captured.mirrored, "the capture lost the viewer's mirror")
        val overlay = assertNotNull(captured.overlay)
        // Laid out on the turned picture, so its width is the frame's height (#428).
        assertEquals(captured.size.height, overlay.viewportWidth)
        assertEquals(captured.size.displayWidth, overlay.viewportHeight)
        harness.close()
    }

    /** A dispatcher that holds what it is given until [release], then hands it on to [next]. */
    private class Holding(private val next: CoroutineDispatcher) : CoroutineDispatcher() {
        private val held = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        private var open = false
        val holding: Int get() = held.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (open) next.dispatch(context, block) else held += context to block
        }

        fun release() {
            open = true
            while (held.isNotEmpty()) held.removeFirst().let { (context, block) -> next.dispatch(context, block) }
        }
    }

    /**
     * A capture carries one moment: the pixels, the subtitles and the viewer's turn of the frame it
     * copied, however late its caller resumes (#73). The turn and the text were read when the
     * caller resumed, so a caller held across a cue's end and a turn got the earlier picture with
     * no text and the later rotation.
     */
    @Test
    fun aCallerThatResumesLateGetsTheSubtitlesAndTurnOfItsFrame() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(800.milliseconds)

        val holding = Holding(StandardTestDispatcher(testScheduler))
        val capture = async(holding, start = CoroutineStart.UNDISPATCHED) { harness.core.captureFrame(withSubtitles = true) }
        harness.run(200.milliseconds)
        assertTrue(holding.holding > 0, "the frame was not copied, so the caller's resume is not held")
        // Past the cue's end, and turned, before the caller resumes.
        harness.run(2_600.milliseconds)
        assertTrue(harness.core.position().inWholeMilliseconds > 3_000, "playback did not pass the cue end: ${harness.core.position()}")
        harness.core.post(
            io.github.yuroyami.kiteplayer.internal.CoreCommand.SetVideoTransform(VideoTransform(rotationDegrees = 90), CompletableDeferred()),
        )
        harness.run(100.milliseconds)
        holding.release()
        harness.run(100.milliseconds)

        val captured = capture.await()
        assertTrue(captured.pts.micros in 800_000L..1_100_000L, "the picture is from ${captured.pts}")
        assertEquals(0, captured.rotationDegrees, "the picture carries a turn made after it was copied")
        assertNotNull(captured.overlay, "the subtitle that showed with the picture is missing")
        harness.close()
    }
}

