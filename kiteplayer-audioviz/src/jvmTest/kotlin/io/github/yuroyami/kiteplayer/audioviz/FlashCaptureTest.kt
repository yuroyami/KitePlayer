package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
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
        val chosen = System.getenv("AUDIOVIZ_SURVEY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }
        for (drawing in VizCatalog.create().filter { chosen == null || it.name in chosen }) {
            val fast = busiest(drawing, SyntheticSong.drumLoop(frames / 60f + 4f, beatsPerMinute = 200f))
            val fastSwing = swing
            val loop = busiest(drawing, SyntheticSong.drumLoop(frames / 60f + 4f))
            val calm = busiest(drawing, SyntheticSong.drumLoop(frames / 60f + 4f, beatsPerMinute = 200f), reducedMotion = true)
            println("  ${drawing.name.padEnd(20)} $fast $loop calm $calm   swing ${(fastSwing * 100).toInt()} ${(swing * 100).toInt()}")
            if (fast > MOST || loop > MOST) problems += "${drawing.name}: $fast and $loop"
            // Reduced motion has to bring the picture inside the policy and never make it worse.
            // It slows the reading a drawing sees rather than stopping the drawing, so it cannot
            // promise none at all: a drawing with its own fast actors still moves them.
            if (calm > MOST || calm > fast) problems += "${drawing.name}: $calm with reduced motion, $fast without"
        }
        assertTrue(problems.isEmpty(), "drawings whose finished picture flashes too often:\n" + problems.joinToString("\n"))
    }

    /** The largest share of the picture that swung together in the last run. Diagnostics. */
    private var swing = 0f

    /**
     * Counts flashes the way a flash analyser does: by area, not by the picture's average.
     *
     * A flash is a pair of opposing changes of at least 0.10 in relative luminance over enough of
     * the screen. Taking the average of the whole frame instead reads a drawing whose bars grow on
     * the beat as a flash, because more of the screen is covered, which is a size change and not a
     * flash. This keeps the last turning frame and asks how much of the picture has since moved
     * the same way by at least the step.
     *
     * The area that counts here is [AREA], a tenth of the picture. WCAG excuses a flash under a
     * quarter of a ten degree field and the project's policy refuses that excuse, so this sits well
     * below it rather than at zero.
     */
    internal class AreaFlashes {
        // A ring of frames from the last [QUICK] seconds, written round rather than allocated: a
        // fresh copy every frame is four gigabytes of rubbish over one catalogue.
        private var ring: Array<FloatArray>? = null
        private var written = 0
        private var seconds = 0f
        private var lastLegRising: Boolean? = null
        private var halfway = false
        private val flashes = ArrayDeque<Float>()
        var widest = 0f
            private set

        val recent: Int get() = flashes.size

        /**
         * Reads one frame and records a leg when the picture has swung since [QUICK] ago.
         *
         * Comparing with the picture a fixed time back is the definition itself: a change of at
         * least the step, over at least the area, inside the time a flash takes. Comparing with the
         * last turning point instead cannot tell a jump after a long rest from a slow drift, and
         * gets both wrong.
         */
        fun add(light: FloatArray, deltaSeconds: Float) {
            seconds += deltaSeconds
            while (flashes.isNotEmpty() && seconds - flashes.first() >= SECOND) flashes.removeFirst()
            val depth = (QUICK / deltaSeconds).toInt().coerceAtLeast(1)
            val frames = ring ?: Array(depth + 1) { FloatArray(light.size) }.also { ring = it }
            light.copyInto(frames[written % frames.size])
            written++
            if (written <= depth) return
            val then = frames[(written - 1 - depth) % frames.size]
            var up = 0
            var down = 0
            for (index in light.indices) {
                val was = then[index]
                val now = light[index]
                if (minOf(was, now) >= DARK) continue
                if (now - was >= STEP) up++ else if (was - now >= STEP) down++
            }
            // The net, not the larger side. A pattern travelling across the screen darkens as many
            // pixels as it brightens, and its direction flips as the crests move, which counts as a
            // flash train if only the larger side is read. A flash brightens or darkens the picture,
            // so the two sides have to be far apart.
            val share = (up - down).toFloat() / light.size
            val size = if (share < 0f) -share else share
            if (size > widest) widest = size
            if (size < AREA) return
            val rising = share > 0f
            // The same swing stays true for several frames, so only a turn is a new leg. A flash is
            // a pair of opposing legs, so it takes two turns to make one.
            if (lastLegRising == rising) return
            val first = lastLegRising == null
            lastLegRising = rising
            if (first) return
            if (halfway) {
                flashes.addLast(seconds)
                halfway = false
            } else {
                halfway = true
            }
        }
    }

    /** Flashes in the busiest rolling second of a run of [drawing] against [samples]. */
    private fun busiest(drawing: Visualization, samples: FloatArray, reducedMotion: Boolean = false): Int {
        val player = SongPlayer(samples)
        var current = player.latest
        val scene = ImageComposeScene(width, height, Density(1f), content = {
            VisualizerSurface(
                visualization = drawing,
                frame = { current },
                palette = VizPalette.Prism,
                modifier = Modifier.fillMaxSize(),
                reducedMotion = reducedMotion,
            )
        })
        val counter = AreaFlashes()
        var most = 0
        val light = FloatArray(width * height)
        var nanos = 0L
        val pixels = IntArray(width * height)
        repeat(frames) {
            current = player.next(1f / 60f)
            nanos += 16_666_667L
            val image = scene.render(nanos).toComposeImageBitmap()
            image.readPixels(pixels)
            for (index in pixels.indices) light[index] = luma(pixels[index])
            counter.add(light, 1f / 60f)
            if (counter.recent > most) most = counter.recent
        }
        scene.close()
        swing = counter.widest
        return most
    }



    internal companion object {
        /** The project's policy: at most three in any rolling second, with no area exception. */
        const val MOST = 3

        /** The opposing change in relative luminance that makes a leg of a flash. WCAG 2.2. */
        const val STEP = 0.10f

        /** The state above which a change of [STEP] does not count. WCAG 2.2. */
        const val DARK = 0.80f

        /** How much of the picture has to swing together. A tenth. *Judgement.* */
        const val AREA = 0.10f

        /**
         * The rolling window, a hair under a second.
         *
         * A sixtieth of a second adds up to 0.99999994 over sixty frames, so a window of exactly
         * one second still holds the flash from a second ago and a six a second train counts seven.
         */
        const val SECOND = 0.999f

        /**
         * How quickly the swing has to cross the step to be a flash rather than a drift, in
         * seconds. Three flashes a second is six crossings a second, so anything slower than this
         * is slower than the policy counts. *Judgement.*
         */
        const val QUICK = 0.3f

        /** Relative luminance of one pixel. */
        fun luma(pixel: Int): Float =
            0.2126f * (pixel shr 16 and 0xFF) / 255f +
                0.7152f * (pixel shr 8 and 0xFF) / 255f +
                0.0722f * (pixel and 0xFF) / 255f
    }
}
