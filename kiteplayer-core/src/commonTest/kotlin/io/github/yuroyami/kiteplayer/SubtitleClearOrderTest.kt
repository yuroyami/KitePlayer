package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A clear of the subtitle overlay lands after any text publish it replaces (#223).
 *
 * The renderer here takes 300 ms to publish text and cannot be cancelled while it does, like the
 * Compose renderer that builds its images first. Each case clears while such a publish is in
 * flight, and the renderer must end with the clear, not with the old text.
 */
class SubtitleClearOrderTest {

    private val config = PlayerConfig(
        subtitles = SubtitleConfig(preferredLanguages = listOf("eng")),
        progressInterval = 50.milliseconds,
    )

    private fun cue(startMicros: Long, endMicros: Long, text: String): SubtitleCue.Text =
        SubtitleCue.Text(startMicros = startMicros, endMicros = endMicros, spans = listOf(StyledSpan(text)))

    private fun harness(scope: kotlinx.coroutines.test.TestScope, cues: List<SubtitleCue.Text>) = CoreHarness(
        scope,
        script = MediaScript(durationUs = 4_000_000, subtitleCues = cues),
        config = config,
        renderer = RecordingRenderer(overlayPublishDuration = 300.milliseconds),
    )

    private fun RecordingRenderer.showsText(): Boolean =
        overlays.filterNotNull().lastOrNull()?.images?.isNotEmpty() == true

    private fun RecordingRenderer.everShowedText(): Boolean =
        overlays.filterNotNull().any { it.images.isNotEmpty() }

    @Test
    fun aCueEndClearLandsAfterTheTextItReplaces() = runTest {
        // The cue ends at 700 ms, while its text publish runs until about 800 ms.
        val harness = harness(this, listOf(cue(500_000, 700_000, "short")))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(2.seconds)
        val renderer = harness.renderer!!
        assertTrue(renderer.everShowedText(), "the text never reached the renderer, so this proves nothing")
        assertTrue(!renderer.showsText(), "the cue ended but its text landed after the clear: ${renderer.overlays}")
        harness.close()
    }

    @Test
    fun turningSubtitlesOffClearsAfterATextPublishInFlight() = runTest {
        val harness = harness(this, listOf(cue(500_000, 3_500_000, "long")))
        harness.openWithRenderer()
        harness.core.play()
        // The text publish starts at about 500 ms and runs until about 800 ms.
        harness.run(600.milliseconds)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, null))
        harness.run(2.seconds)
        val renderer = harness.renderer!!
        assertTrue(renderer.everShowedText(), "the text never reached the renderer, so this proves nothing")
        assertTrue(!renderer.showsText(), "subtitles are off but the old text landed after the clear")
        harness.close()
    }

    @Test
    fun stoppingClearsAfterATextPublishInFlight() = runTest {
        val harness = harness(this, listOf(cue(500_000, 3_500_000, "long")))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(600.milliseconds)
        harness.core.stop()
        harness.run(1.seconds)
        val renderer = harness.renderer!!
        assertTrue(renderer.everShowedText(), "the text never reached the renderer, so this proves nothing")
        assertTrue(!renderer.showsText(), "the session was released but its text landed after the clear")
        harness.close()
    }
}
