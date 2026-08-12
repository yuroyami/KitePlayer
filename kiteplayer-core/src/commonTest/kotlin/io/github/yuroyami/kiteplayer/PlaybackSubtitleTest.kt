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
 * Cue timing in the engine loop (S4.c), in virtual time: a cue appears at its start, disappears
 * at its end, a seek rebuilds the visible set by redelivery, and nothing is published while the
 * set is unchanged.
 */
class PlaybackSubtitleTest {

    private fun cue(startMs: Long, endMs: Long, text: String) = SubtitleCue.Text(
        startMicros = startMs * 1000,
        endMicros = endMs * 1000,
        spans = listOf(StyledSpan(text)),
    )

    private fun subtitleConfig() = PlayerConfig(
        subtitles = SubtitleConfig(preferredLanguages = listOf("eng")),
        progressInterval = 50.milliseconds,
    )

    @Test
    fun aCueAppearsAtItsStartAndDisappearsAtItsEnd() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 4_000_000,
                subtitleCues = listOf(cue(1_000, 2_000, "hello")),
            ),
            config = subtitleConfig(),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        val renderer = harness.renderer!!
        val shown = renderer.overlays.filterNotNull().filter { it.images.isNotEmpty() }
        assertTrue(shown.isNotEmpty(), "the cue never reached the renderer")
        assertEquals(1, shown.first().images.size)

        harness.run(1.seconds)
        val last = renderer.overlays.filterNotNull().last()
        assertTrue(last.images.isEmpty(), "the cue did not clear after its end")
        harness.close()
    }

    @Test
    fun anUnchangedActiveSetPublishesNothingNew() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 4_000_000,
                subtitleCues = listOf(cue(500, 3_500, "steady")),
            ),
            config = subtitleConfig(),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(2.seconds)
        val renderer = harness.renderer!!
        val publications = renderer.overlays.size
        harness.run(1.seconds)
        // Still inside the same cue: at most bookkeeping-free stability, no per-pass republish.
        assertEquals(
            publications,
            renderer.overlays.size,
            "the engine republished an unchanged cue set",
        )
        harness.close()
    }

    @Test
    fun aSeekBackRebuildsTheCueByRedelivery() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 6_000_000,
                subtitleCues = listOf(cue(2_000, 3_000, "again")),
            ),
            config = subtitleConfig(),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3500.milliseconds)
        val renderer = harness.renderer!!
        val shownBefore = renderer.overlays.filterNotNull().count { it.images.isNotEmpty() }
        assertTrue(shownBefore > 0, "the cue never showed before the seek")

        harness.core.seek(Pts(500_000), SeekMode.Precise)
        harness.core.play()
        harness.run(2500.milliseconds)
        val shownAfter = renderer.overlays.filterNotNull().count { it.images.isNotEmpty() }
        assertTrue(
            shownAfter > shownBefore,
            "the seek back did not rebuild the cue (shown $shownBefore before, $shownAfter after)",
        )
        harness.close()
    }

    @Test
    fun theSubtitleTrackIsSelectedByLanguageAndReported() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(subtitleCues = listOf(cue(100, 200, "x"))),
            config = subtitleConfig(),
        )
        harness.openWithRenderer()
        val selected = harness.core.snapshots.value.tracks.selectedSubtitle
        assertEquals(TrackId(harness.script.subtitleIndex), selected, "the subtitle track was not auto-selected")
        harness.close()
    }

    @Test
    fun noPreferredLanguageMeansNoAutomaticSubtitles() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(subtitleCues = listOf(cue(100, 200, "x"))),
            config = PlayerConfig(),
        )
        harness.openWithRenderer()
        assertEquals(null, harness.core.snapshots.value.tracks.selectedSubtitle)
        harness.close()
    }
}
