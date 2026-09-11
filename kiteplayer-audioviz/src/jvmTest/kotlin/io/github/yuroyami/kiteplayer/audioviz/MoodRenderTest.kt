package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every drawing has to look different under calm music and under lively music.
 *
 * This is the test the whole mood system exists to pass. A drawing can be beautiful and still be
 * broken in the way that matters: if a ballad and a drum track produce the same picture, the
 * drawing is decoration rather than a visualiser, and no amount of looking at one screenshot will
 * tell you which it is.
 *
 * Two things are measured. How much the picture MOVES, taken as the difference between one frame
 * and the next, which is what a viewer reads as energy. And how BRIGHT it is on average, which
 * covers the drawings whose answer to loud music is to light up rather than to speed up. A
 * drawing passes on either count, because both are honest ways to answer music.
 */
class MoodRenderTest {

    init { useSkiaGraphics() }

    private val width = 240
    private val height = 150
    private val frames = 220

    // A shader is drawn a pixel at a time by the processor here rather than by the graphics card,
    // so it gets a shorter run. What is measured is a ratio between three songs. The canvas stays
    // this size because a drawing that blooms renders into a buffer at under half of it, and any
    // smaller would leave it a few dozen pixels across, where a smear and a sharp figure look alike.
    private val shaderWidth = 240
    private val shaderHeight = 150
    private val shaderFrames = 40

    private class Look(val motion: Float, val brightness: Float)

    @Test
    fun everyVisualizationTellsCalmFromLively() {
        val selected = System.getenv("AUDIOVIZ_MOOD_PRESETS")?.split(',')?.toSet()
        val all = VizCatalog.create()
        val indices = all.indices.filter { selected == null || all[it].name in selected }
        assertTrue(indices.isNotEmpty(), "no matching presets")
        // A drawing owns its state, so each worker takes its own fresh set, as the survey does.
        val rows = RenderHarness.inParallel(indices) { index ->
            val visualization = VizCatalog.create()[index]
            val quiet = look(visualization, RenderHarness.Song.Silence)
            val calm = look(visualization, RenderHarness.Song.Calm)
            val lively = look(visualization, RenderHarness.Song.Lively)
            Row(visualization, quiet, calm, lively)
        }

        println("how much each drawing changes with the music (motion, brightness)")
        println("  ${"drawing".padEnd(20)} ${"silence".padEnd(17)} ${"calm".padEnd(17)} lively")
        rows.sortedBy { minOf(it.liveliness, it.wakefulness) }.forEach { row ->
            println(
                "  ${row.visualization.name.padEnd(20)} " +
                    "${row.quiet.show().padEnd(17)} ${row.calm.show().padEnd(17)} ${row.lively.show()}" +
                    "   lively x${(row.liveliness * 100).toInt() / 100.0}" +
                    " awake x${(row.wakefulness * 100).toInt() / 100.0}",
            )
        }

        val deaf = rows.filter { it.liveliness < LIVELY_MULTIPLE }
        assertTrue(
            deaf.isEmpty(),
            "these look the same whatever plays, so nothing about them says the music got busier: " +
                deaf.joinToString { "${it.visualization.name} at x${(it.liveliness * 100).toInt() / 100.0}" },
        )

        val asleep = rows.filter { it.wakefulness < AWAKE_MULTIPLE }
        assertTrue(
            asleep.isEmpty(),
            "these look the same whether music is playing or not: " +
                asleep.joinToString { "${it.visualization.name} at x${(it.wakefulness * 100).toInt() / 100.0}" },
        )
    }

    private class Row(
        val visualization: Visualization,
        val quiet: Look,
        val calm: Look,
        val lively: Look,
    ) {
        /** The best of the two ways a drawing can answer busier music. */
        val liveliness: Float = maxOf(
            ratio(lively.motion, calm.motion),
            ratio(lively.brightness, calm.brightness),
        )

        /**
         * And the difference between nothing playing and something clearly playing.
         *
         * Measured against the drum loop rather than against the pad. Every drawing here is meant
         * to keep moving in a silence rather than freeze, at about a quarter speed, and the pad is
         * only a little above silence on that scale, so holding a slow drawing to a visible
         * difference between the two would be asking it to break its own rule. Against real music
         * there is no excuse.
         */
        val wakefulness: Float = maxOf(
            ratio(lively.motion, quiet.motion),
            ratio(lively.brightness, quiet.brightness),
        )

        private fun ratio(bigger: Float, smaller: Float): Float =
            if (smaller <= 1e-5f) if (bigger > 1e-5f) 99f else 1f else bigger / smaller
    }

    private fun Look.show(): String =
        "${(motion * 1000).toInt() / 1000.0}/${(brightness * 1000).toInt() / 1000.0}"

    /** Runs one drawing against one song and measures how much it moves and how bright it is. */
    private fun look(visualization: Visualization, song: RenderHarness.Song): Look {
        var previous: BufferedImage? = null
        var motion = 0f
        var brightness = 0f
        var samples = 0
        val shader = visualization is ShaderPreset || visualization.warp != null
        val runFor = if (shader) shaderFrames else frames
        val runWidth = if (shader) shaderWidth else width
        val runHeight = if (shader) shaderHeight else height
        val from = runFor / 2
        var ground: BufferedImage? = null
        val sampled: (Int) -> Boolean = { step -> step >= from && step % 2 == 0 }
        RenderHarness.forEachFrame(
            visualization, runWidth, runHeight, runFor, VizPalette.Prism, song,
            groundAt = sampled,
            onGround = { bitmap, _ -> ground = with(RenderHarness) { bitmap.toBufferedImage() } },
        ) { bitmap, step ->
            // Sampled rather than every frame: reading the pixels back costs more than the drawing.
            if (!sampled(step)) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            brightness += meanLuminance(image, ground)
            previous?.let { motion += difference(it, image) }
            previous = image
            samples++
        }
        if (samples == 0) return Look(0f, 0f)
        return Look(motion / samples, brightness / samples)
    }

    /**
     * How much light the drawing put down, over and above the ground it was painted on.
     *
     * Counting the background as brightness hides everything. A palette's ground is most of the
     * screen, so a drawing that doubles the light it emits moves the average by a few percent and
     * looks unresponsive when it is not. What matters is the part the drawing added.
     */
    private fun meanLuminance(image: BufferedImage, groundAlone: BufferedImage?): Float {
        val background = VizPalette.Prism.background.toArgb().let {
            (it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF)
        }
        var total = 0L
        var counted = 0
        for (y in 0 until image.height step 3) {
            for (x in 0 until image.width step 3) {
                val pixel = image.getRGB(x, y)
                val light = (pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)
                // The ground is taken off pixel by pixel, so a bright ground is not the drawing lighting up.
                val under = groundAlone?.getRGB(x, y)?.let { (it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF) } ?: background
                total += (light - under).coerceAtLeast(0)
                counted++
            }
        }
        return if (counted == 0) 0f else total.toFloat() / counted / 765f
    }

    private fun difference(left: BufferedImage, right: BufferedImage): Float {
        if (left.width != right.width || left.height != right.height) return 0f
        var total = 0L
        var counted = 0
        for (y in 0 until left.height step 3) {
            for (x in 0 until left.width step 3) {
                val a = left.getRGB(x, y)
                val b = right.getRGB(x, y)
                total += abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
                    abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
                    abs((a and 0xFF) - (b and 0xFF))
                counted++
            }
        }
        return if (counted == 0) 0f else total.toFloat() / counted / 765f
    }

    private companion object {
        /** How much busier a drawing has to look under a drum loop than under a pad. */
        const val LIVELY_MULTIPLE = 2f

        /** And how much more alive it has to look than it does in silence. */
        const val AWAKE_MULTIPLE = 1.6f
    }
}
