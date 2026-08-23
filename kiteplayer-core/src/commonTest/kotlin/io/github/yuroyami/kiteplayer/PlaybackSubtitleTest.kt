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
    fun cuesAreRasterisedOntoTheSurfaceRatherThanTheVideo() = runTest {
        // A phone showing a film smaller than its own screen. Rasterising on the VIDEO's canvas and
        // letting the renderer stretch it is one resampling pass before the text is ever seen, which
        // is the soft, ragged lettering the owner reported on 2026-08-23. The scripted video is
        // 1920x1080; the surface here is deliberately neither that nor a multiple of it.
        val surface = io.github.yuroyami.kiteplayer.VideoSize(2436, 1125)
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 4_000_000,
                subtitleCues = listOf(cue(1_000, 3_000, "hello")),
            ),
            config = subtitleConfig(),
        )
        harness.renderer!!.outputSizeOverride = surface
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)

        val shown = harness.renderer.overlays.filterNotNull().first { it.images.isNotEmpty() }
        assertEquals(
            surface.width to surface.height,
            shown.viewportWidth to shown.viewportHeight,
            "the text was rasterised onto the video's canvas and left for the renderer to stretch",
        )

        // A surface that changes size must redraw the same text, which a video-sized canvas never
        // had to do. Without this the first raster would be stretched for the rest of the session.
        harness.renderer.outputSizeOverride = io.github.yuroyami.kiteplayer.VideoSize(1125, 2436)
        harness.run(300.milliseconds)
        val after = harness.renderer.overlays.filterNotNull().last { it.images.isNotEmpty() }
        assertEquals(
            1125 to 2436,
            after.viewportWidth to after.viewportHeight,
            "a rotation left the text rasterised for the old surface",
        )
        harness.close()
    }

    @Test
    fun aRendererThatCannotSayItsSizeKeepsTheVideoCanvas() = runTest {
        // The fallback every other renderer still relies on: no answer means the video's own size,
        // exactly as before, so nothing that cannot measure itself is made worse.
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 4_000_000,
                subtitleCues = listOf(cue(1_000, 3_000, "hello")),
            ),
            config = subtitleConfig(),
        )
        harness.renderer!!.outputSizeOverride = null
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)

        val shown = harness.renderer.overlays.filterNotNull().first { it.images.isNotEmpty() }
        assertEquals(
            1920 to 1080,
            shown.viewportWidth to shown.viewportHeight,
            "a renderer with no size must leave the canvas on the video, as it was",
        )
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
    fun subtitledMediaShowsItsSubtitlesByDefault() = runTest {
        // The contract turned around with SubtitleConfig.autoSelect: a viewer who opens
        // subtitled media expects subtitles, so with no language preference the default track
        // is selected rather than none.
        val harness = CoreHarness(
            this,
            script = MediaScript(subtitleCues = listOf(cue(100, 200, "x"))),
            config = PlayerConfig(),
        )
        harness.openWithRenderer()
        assertTrue(
            harness.core.snapshots.value.tracks.selectedSubtitle != null,
            "the default configuration selects the subtitle track the media carries",
        )
        harness.close()
    }

    @Test
    fun autoSelectOffRestoresNoPreferenceNoSubtitles() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(subtitleCues = listOf(cue(100, 200, "x"))),
            config = PlayerConfig(subtitles = SubtitleConfig(autoSelect = false)),
        )
        harness.openWithRenderer()
        assertEquals(null, harness.core.snapshots.value.tracks.selectedSubtitle)
        harness.close()
    }
}
