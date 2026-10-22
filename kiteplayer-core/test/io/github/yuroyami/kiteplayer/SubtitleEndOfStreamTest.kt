@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The last line of dialogue in a film gets its full time on screen.
 *
 * The end-of-stream gate waited for the video and audio lanes and not for the subtitle one, so a
 * cue still showing when the pictures ran out went away with the session. On real media that is
 * the closing line of a film disappearing a moment early, or a closing caption never appearing at
 * all.
 */
class SubtitleEndOfStreamTest {

    private fun cue(start: Long, end: Long, text: String): SubtitleCue =
        SubtitleCue.Text(start, end, listOf(StyledSpan(text)))

    private fun texts(cues: List<SubtitleCue>): List<String> =
        cues.filterIsInstance<SubtitleCue.Text>().map { it.plainText }

    /** Four seconds of pictures and a closing line that runs a second and a half past them. */
    private fun script() = MediaScript(
        durationUs = 4_000_000,
        subtitleCues = listOf(
            cue(500_000, 1_500_000, "early"),
            cue(3_500_000, 5_500_000, "closing"),
        ),
    )

    @Test
    fun `a cue outliving the last frame is still on screen when the pictures run out`() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.core.play()
        // Past the last frame, and still inside the closing cue's own time.
        harness.run(4500.milliseconds)
        assertEquals(
            listOf("closing"),
            texts(harness.core.subtitleCues.value),
            "the closing line went away with the last frame",
        )
        harness.close()
    }

    @Test
    fun `the session does not end while a cue is still due`() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(4500.milliseconds)
        assertTrue(
            harness.core.snapshots.value.status != PlaybackStatus.Ended,
            "the session ended while a line of dialogue was still meant to be showing",
        )
        harness.close()
    }

    @Test
    fun `the session still ends once the last cue is done`() = runTest {
        // The other half of the same rule: holding the end open must not wedge the player.
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(8.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status, "the player never ended")
        harness.close()
    }

    @Test
    fun `a file whose cues end before the pictures do ends on time`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 4_000_000,
                subtitleCues = listOf(cue(500_000, 1_500_000, "early")),
            ),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(5.seconds)
        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "an ordinary file was held open by a subtitle lane with nothing left to show",
        )
        harness.close()
    }
}
