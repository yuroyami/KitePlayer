@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The cues an application can see.
 *
 * They existed only inside the engine: the active set went to the renderer as a bitmap overlay and
 * nowhere else, so an application that wanted to draw its own subtitles, read them to a screen
 * reader, log them, or show them beside the video rather than over it had nothing to read.
 *
 * They are published from the same branch that decides the overlay, so what an application sees and
 * what the renderer draws cannot be two different sets, and they are withdrawn together too: a flow
 * still holding the last line after the overlay came off would tell an application that text is on
 * screen when none is.
 */
class SubtitleCuesFlowTest {

    private fun cue(start: Long, end: Long, text: String): SubtitleCue =
        SubtitleCue.Text(start, end, listOf(StyledSpan(text)))

    private val script = MediaScript(
        durationUs = 6_000_000,
        subtitleCues = listOf(
            cue(500_000, 1_500_000, "first"),
            cue(2_000_000, 3_000_000, "second"),
        ),
    )

    private fun texts(cues: List<SubtitleCue>): List<String> =
        cues.filterIsInstance<SubtitleCue.Text>().map { it.plainText }

    @Test
    fun `the flow starts empty and follows the cues as they come and go`() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        assertEquals(emptyList(), harness.core.subtitleCues.value, "cues before anything played")

        harness.core.play()
        harness.run(800.milliseconds)
        assertEquals(listOf("first"), texts(harness.core.subtitleCues.value), "the first cue never appeared")

        harness.run(900.milliseconds)
        assertEquals(emptyList(), texts(harness.core.subtitleCues.value), "the first cue never went away")

        harness.run(800.milliseconds)
        assertEquals(listOf("second"), texts(harness.core.subtitleCues.value), "the second cue never appeared")
        harness.close()
    }

    @Test
    fun `what the application reads is what the renderer was given`() = runTest {
        // The two are published from one decision, so this is a structural guarantee rather than a
        // coincidence of timing. If they ever diverge, an application drawing its own text would
        // disagree with the picture behind it.
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(800.milliseconds)

        val published = harness.core.subtitleCues.value
        assertTrue(published.isNotEmpty(), "nothing was published, so there is nothing to compare")
        assertTrue(
            harness.output.rasterizedCueTexts.isNotEmpty(),
            "the rasterizer was never asked to draw, so the overlay and the flow cannot be compared",
        )
        assertEquals(
            texts(published),
            harness.output.rasterizedCueTexts.last(),
            "the application and the renderer were given different cues",
        )
        harness.close()
    }

    @Test
    fun `closing the player takes the cues down with it`() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(800.milliseconds)
        assertTrue(harness.core.subtitleCues.value.isNotEmpty(), "nothing to withdraw")

        harness.close()
        assertEquals(
            emptyList(),
            harness.core.subtitleCues.value,
            "a closed player still reported subtitles on screen",
        )
    }

    @Test
    fun `a seek past a cue leaves nothing showing`() = runTest {
        // The flow is derived from the position on every pass, so a seek needs no reconstruction:
        // asking for the new time IS the reconstruction, in both directions.
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(800.milliseconds)
        assertEquals(listOf("first"), texts(harness.core.subtitleCues.value))

        harness.core.seekLater(Pts(4_500_000), SeekMode.Precise)
        harness.run(600.milliseconds)
        assertEquals(
            emptyList(),
            texts(harness.core.subtitleCues.value),
            "a seek past every cue left one showing",
        )
        harness.close()
    }
}
