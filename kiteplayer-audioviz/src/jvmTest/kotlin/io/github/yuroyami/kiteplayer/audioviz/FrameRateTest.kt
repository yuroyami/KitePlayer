package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A drawing looks and moves the same on a 30 Hz, a 60 Hz and a 120 Hz screen.
 *
 * A constant that steps once per frame, such as a fade of 0.9 a frame or a move of two pixels a
 * frame, makes a picture twice as bright, twice as busy or twice as fast on a screen twice as fast.
 * Each drawing plays the same drum loop for the same seconds at each rate, and two things are
 * measured over the last seconds. The mean brightness must stay close to the 60 Hz picture. So must
 * the mean change between two pictures 0.2 seconds apart, which is how fast the drawing moves. Both
 * are averages, so a drawing can pass and still differ in where its marks are.
 */
class FrameRateTest {

    init { useSkiaGraphics() }

    private fun luma(pixel: Int): Float =
        (0.2126f * ((pixel shr 16) and 255) + 0.7152f * ((pixel shr 8) and 255) + 0.0722f * (pixel and 255)) / 255f

    /** What one run measured: the mean brightness, and the mean change 0.2 seconds apart, both 0 to 1. */
    private class Measure(val brightness: Float, val motion: Float)

    private fun measure(index: Int, hertz: Int): Measure {
        val drawing = DriverProbe.drawing(index)
        val (width, height) = if (drawing is ShaderPreset) {
            DriverProbe.SHADER_WIDTH to DriverProbe.SHADER_HEIGHT
        } else {
            DriverProbe.WIDTH to DriverProbe.HEIGHT
        }
        val delta = 1f / hertz
        val frames = (RUN_SECONDS * hertz).toInt()
        val from = frames - (WATCH_SECONDS * hertz).toInt()
        val gap = hertz / 5
        // The player hears three seconds before the first frame, and the song must outlast the run.
        val player = RenderHarness.player(RenderHarness.Song.Lively, RUN_SECONDS + 5f)
        var brightness = 0f
        var counted = 0
        var motion = 0f
        var compared = 0
        var earlier: FloatArray? = null
        RenderHarness.forEachFrameOf(
            drawing, width, height, frames, VizPalette.Prism,
            source = { player.next(delta) },
            delta = delta,
        ) { bitmap, step ->
            if (step < from) return@forEachFrameOf
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
            val lumas = FloatArray(pixels.size) { luma(pixels[it]) }
            brightness += lumas.sum() / lumas.size
            counted++
            if ((step - from) % gap == 0) {
                earlier?.let { before ->
                    var change = 0f
                    for (pixel in lumas.indices) change += abs(lumas[pixel] - before[pixel])
                    motion += change / lumas.size
                    compared++
                }
                earlier = lumas
            }
        }
        return Measure(brightness / counted, motion / compared)
    }

    @Test
    fun everyDrawingKeepsItsBrightnessAndItsSpeedAtEveryRefreshRate() {
        val indices = VizCatalog.create().indices.toList()
        val rows = RenderHarness.inParallel(indices) { index ->
            DriverProbe.drawing(index).name to HERTZ.map { measure(index, it) }
        }
        val reference = HERTZ.indexOf(REFERENCE_HERTZ)
        val moved = ArrayList<String>()
        for ((name, runs) in rows) {
            fun worst(pick: (Measure) -> Float): Float {
                val base = pick(runs[reference])
                return runs.maxOf { abs(pick(it) - base) / max(base, FLOOR) }
            }
            val brighter = worst { it.brightness }
            val faster = worst { it.motion }
            println(
                "rate $name: brightness ${runs.joinToString { "%.4f".format(it.brightness) }}, " +
                    "motion ${runs.joinToString { "%.4f".format(it.motion) }}, worst change $brighter and $faster",
            )
            if (name in BY_DESIGN) continue
            if (brighter > MOST_CHANGE) moved += "$name: brightness changes by $brighter of ${runs.joinToString { "${it.brightness}" }}"
            if (faster > MOST_CHANGE) moved += "$name: motion changes by $faster of ${runs.joinToString { "${it.motion}" }}"
        }
        assertTrue(
            moved.isEmpty(),
            "drawings that depend on the refresh rate (limit $MOST_CHANGE at ${HERTZ.joinToString()} Hz):\n" + moved.joinToString("\n"),
        )
    }

    private companion object {
        val HERTZ = listOf(30, 60, 120)
        const val REFERENCE_HERTZ = 60

        /** Long enough for every trail and spring to settle. */
        const val RUN_SECONDS = 7f

        /** The part at the end that is measured. */
        const val WATCH_SECONDS = 3f

        /** A measure below this is compared against this, so a near black picture is not judged by a ratio. */
        const val FLOOR = 0.005f

        /** The share of the 60 Hz figure that another rate may add or lose. *Judgement.* */
        const val MOST_CHANGE = 0.25f

        /** Drawings exempt from the rate check. None is. */
        val BY_DESIGN = emptySet<String>()
    }
}
