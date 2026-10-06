@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * A subtitle decoder hears that its stream has ended (#480). The SPI lets a decoder hold a cue
 * until the next packet, and FFmpeg's caption decoder holds the caption on screen until it is
 * drained, so the engine sends it the null packet once the queue has run dry at the end of the
 * stream. Without that, the last caption of a file was never shown.
 */
class SubtitleDrainTest {

    private fun cue(start: Long, end: Long, text: String): SubtitleCue =
        SubtitleCue.Text(start, end, listOf(StyledSpan(text)))

    private fun script(holdsLastCue: Boolean = true, refusesDrain: Boolean = false) = MediaScript(
        durationUs = 6_000_000,
        subtitleCues = listOf(cue(1_000_000, 2_000_000, "first"), cue(4_500_000, 5_500_000, "last")),
        subtitleHoldsLastCue = holdsLastCue,
        subtitleRefusesDrain = refusesDrain,
    )

    private fun CoreHarness.showing(): List<String> =
        core.subtitleCues.value.filterIsInstance<SubtitleCue.Text>().map { it.plainText }

    @Test
    fun aCueTheDecoderHoldsUntilTheEndIsShownOnceItIsDrained() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1_500.milliseconds)
        assertEquals(listOf("first"), harness.showing())

        harness.run(3_400.milliseconds)
        assertEquals(listOf("last"), harness.showing(), "the held last cue was never drained (#480)")
        assertEquals(1, harness.script.subtitleProbe.drains, "the decoder was drained ${harness.script.subtitleProbe.drains} times")

        harness.run(2_000.milliseconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aDecoderThatRefusesTheDrainForEverStillLetsTheMediaEnd() = runTest {
        val harness = CoreHarness(this, script = script(refusesDrain = true))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(7_000.milliseconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        assertEquals(0, harness.script.subtitleProbe.drains)
        harness.close()
    }

    @Test
    fun aSeekBackAfterTheDrainDrainsAgainAtTheNextEnd() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(5_000.milliseconds)
        assertEquals(listOf("last"), harness.showing())
        harness.core.seek(Pts(4_000_000), SeekMode.Precise)
        harness.run(800.milliseconds)
        assertEquals(listOf("last"), harness.showing(), "after a seek the held cue was not drained again")
        assertEquals(2, harness.script.subtitleProbe.drains)
        harness.close()
    }
}
