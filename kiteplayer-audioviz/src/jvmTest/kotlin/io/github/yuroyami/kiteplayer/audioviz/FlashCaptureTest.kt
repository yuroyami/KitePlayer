package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.viz.FlashGuard
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every drawing captured through the real surface, with the finishing pass on, and counted.
 *
 * This is the captured-output half of the flash policy. The guard in the surface is the runtime
 * half; neither replaces the other. What is measured here is the composed frame, after the glow,
 * the grain and the rest of the finishing pass, which is what a viewer sees.
 *
 * The fixture is 200 beats a minute with hits on the half beat, which is the fastest ordinary
 * music a drawing will meet, plus the drum loop for a second reading.
 */
class FlashCaptureTest {

    init { useSkiaGraphics() }

    private val width = 160
    private val height = 100
    private val frames = 300

    @Test
    fun noDrawingFlashesMoreThanThePolicyAllows() {
        val problems = ArrayList<String>()
        println("flashes in the busiest second, fast fixture then drum loop")
        // AUDIOVIZ_SURVEY names the drawings to render, comma separated, for checking one of them.
        val chosen = System.getenv("AUDIOVIZ_SURVEY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        for (drawing in VizCatalog.create().filter { chosen == null || it.name in chosen }) {
            val fast = busiest(drawing, SyntheticSong.drumLoop(frames / 60f + 4f, beatsPerMinute = 200f))
            val fastSwing = swing
            val loop = busiest(drawing, SyntheticSong.drumLoop(frames / 60f + 4f))
            println("  ${drawing.name.padEnd(20)} $fast $loop   swing ${(fastSwing * 100).toInt()} ${(swing * 100).toInt()}")
            if (fast > MOST || loop > MOST) problems += "${drawing.name}: $fast and $loop"
        }
        assertTrue(problems.isEmpty(), "drawings whose finished picture flashes too often:\n" + problems.joinToString("\n"))
    }

    /** The largest swing of the whole picture's light seen in the last run. Diagnostics. */
    private var swing = 0f

    /** Flashes in the busiest rolling second of a run of [drawing] against [samples]. */
    private fun busiest(drawing: Visualization, samples: FloatArray): Int {
        val player = SongPlayer(samples)
        var current = player.latest
        val scene = ImageComposeScene(width, height, Density(1f), content = {
            VisualizerSurface(
                visualization = drawing,
                frame = { current },
                palette = VizPalette.Prism,
                modifier = Modifier.fillMaxSize(),
            )
        })
        val counter = FlashGuard(mostPerSecond = 1_000)
        var most = 0
        var low = 1f
        var high = 0f
        var nanos = 0L
        val pixels = IntArray(width * height)
        repeat(frames) {
            current = player.next(1f / 60f)
            nanos += 16_666_667L
            val image = scene.render(nanos).toComposeImageBitmap()
            image.readPixels(pixels)
            val light = luminance(pixels)
            if (light < low) low = light
            if (light > high) high = light
            counter.limit(light, redShare(pixels), 1f / 60f)
            if (counter.recent > most) most = counter.recent
        }
        scene.close()
        swing = high - low
        return most
    }

    /** Mean relative luminance of the frame, 0 to 1. */
    private fun luminance(pixels: IntArray): Float {
        var sum = 0f
        for (pixel in pixels) {
            sum += 0.2126f * (pixel shr 16 and 0xFF) / 255f +
                0.7152f * (pixel shr 8 and 0xFF) / 255f +
                0.0722f * (pixel and 0xFF) / 255f
        }
        return sum / pixels.size
    }

    /** The share of the picture in a saturated red, which may not flash at all. */
    private fun redShare(pixels: IntArray): Float {
        var count = 0
        for (pixel in pixels) {
            val red = (pixel shr 16 and 0xFF) / 255f
            val green = (pixel shr 8 and 0xFF) / 255f
            val blue = (pixel and 0xFF) / 255f
            if (red > 0.5f && red > green * 2.2f && red > blue * 2.2f) count++
        }
        return count.toFloat() / pixels.size
    }

    private companion object {
        /** The project's policy: at most three in any rolling second, with no area exception. */
        const val MOST = 3
    }
}
