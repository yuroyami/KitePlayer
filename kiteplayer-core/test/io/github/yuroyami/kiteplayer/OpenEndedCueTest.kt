@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * An image cue with no end, as a Blu-ray subtitle is, stays on screen until the next cue of its track
 * starts: a new image replaces it, and a cue with no image clears it.
 */
class OpenEndedCueTest {

    private fun image(startMicros: Long, width: Int, endMicros: Long = SubtitleCue.OPEN_END): SubtitleCue =
        SubtitleCue.Bitmap(
            startMicros = startMicros,
            endMicros = endMicros,
            regions = listOf(
                BitmapRegion(0, 0, width, 1, canvasWidth = 1920, canvasHeight = 1080, bitmap = RgbaBitmap(width, 1, ByteArray(width * 4))),
            ),
        )

    private fun clear(startMicros: Long): SubtitleCue =
        SubtitleCue.Bitmap(startMicros, SubtitleCue.OPEN_END, regions = emptyList())

    /** The widths of the images on screen, which tell the scripted images apart. */
    private fun shown(harness: CoreHarness): List<Int> =
        harness.core.subtitleCues.value.filterIsInstance<SubtitleCue.Bitmap>().flatMap { cue -> cue.regions.map { it.width } }

    @Test
    fun `an open image lasts until the next cue replaces or clears it`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 6_000_000,
                subtitleCues = listOf(
                    image(500_000, width = 1),
                    image(1_500_000, width = 2),
                    clear(2_500_000),
                    image(3_500_000, width = 3, endMicros = 4_000_000),
                ),
            ),
        )
        harness.openWithRenderer()
        harness.core.play()

        harness.run(1_000.milliseconds)
        assertEquals(listOf(1), shown(harness), "the first image at 1.0 s")
        harness.run(1_000.milliseconds)
        assertEquals(listOf(2), shown(harness), "at 2.0 s the second image replaced the first")
        harness.run(1_000.milliseconds)
        assertEquals(emptyList(), shown(harness), "at 3.0 s the clear took the image off")
        harness.run(700.milliseconds)
        assertEquals(listOf(3), shown(harness), "at 3.7 s the image with its own end")
        harness.run(600.milliseconds)
        assertEquals(emptyList(), shown(harness), "at 4.3 s that image had ended by itself")
        harness.close()
    }
}
