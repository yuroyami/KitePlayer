@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Two subtitle tracks at once: the primary where the author put it, the secondary forced to the
 * top of the picture. Learners watch with two languages; one slot existed.
 */
class SecondarySubtitleTest {

    private fun cue(start: Long, end: Long, text: String): SubtitleCue =
        SubtitleCue.Text(start, end, listOf(StyledSpan(text)))

    private val script = MediaScript(
        durationUs = 6_000_000,
        subtitleCues = listOf(cue(500_000, 4_000_000, "primary line")),
        additionalSubtitleTracks = listOf(
            ScriptedSubtitleTrack(
                index = 3,
                cues = listOf(cue(500_000, 4_000_000, "secondary line")),
                language = "jpn",
                title = "scripted subtitle B",
            ),
        ),
    )

    private fun texts(cues: List<SubtitleCue>) =
        cues.filterIsInstance<SubtitleCue.Text>().map { it.plainText }

    @Test
    fun `both tracks show at once and the secondary sits at the top`() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)

        val change = harness.core.selectSecondarySubtitle(TrackId(3))
        assertTrue(change is TrackChange.Applied, "the secondary selection was $change")
        harness.run(700.milliseconds)

        val showing = harness.core.subtitleCues.value
        assertEquals(listOf("primary line", "secondary line"), texts(showing))
        val secondary = showing.filterIsInstance<SubtitleCue.Text>().last()
        assertEquals(
            CueAlignment.TopCenter,
            secondary.layout.alignment,
            "the secondary track must be forced to the top of the picture",
        )
        assertEquals(
            TrackId(3),
            harness.core.snapshots.value.tracks.selectedSecondarySubtitle,
            "the snapshot must name the secondary selection",
        )

        harness.core.selectSecondarySubtitle(null)
        harness.run(300.milliseconds)
        assertEquals(
            listOf("primary line"),
            texts(harness.core.subtitleCues.value),
            "clearing the secondary must drop its cues and keep the primary's",
        )
        harness.close()
    }

    @Test
    fun `the same track on both slots refuses`() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)
        val primary = harness.core.snapshots.value.tracks.selectedSubtitle
        assertTrue(primary != null, "auto-select never picked the default subtitle track")

        assertFailsWith<IllegalArgumentException> {
            harness.core.selectSecondarySubtitle(primary)
        }
        harness.run(200.milliseconds)
        assertEquals(
            listOf("primary line"),
            texts(harness.core.subtitleCues.value),
            "a refused selection must change nothing on screen",
        )
        harness.close()
    }

    @Test
    fun `the refusal to use one track in both slots names the track`() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)

        assertTrue(harness.core.selectSecondarySubtitle(TrackId(3)) is TrackChange.Applied)
        harness.run(300.milliseconds)

        val refusal = assertFailsWith<IllegalArgumentException> {
            harness.core.selectTrack(TrackKind.Subtitle, TrackId(3))
        }
        // A refusal that cannot say WHICH track is the whole message wasted: the caller is holding
        // several and has to guess which one it was told about.
        assertTrue(
            refusal.message?.contains("TrackId(value=3)") == true ||
                refusal.message?.contains("3") == true,
            "the refusal must name the track, it said: ${refusal.message}",
        )
        assertTrue(
            refusal.message?.contains("\${command") != true,
            "the refusal printed an uninterpolated placeholder: ${refusal.message}",
        )
        harness.close()
    }

}
