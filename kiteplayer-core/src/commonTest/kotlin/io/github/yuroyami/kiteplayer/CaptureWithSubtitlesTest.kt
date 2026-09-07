@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
}
