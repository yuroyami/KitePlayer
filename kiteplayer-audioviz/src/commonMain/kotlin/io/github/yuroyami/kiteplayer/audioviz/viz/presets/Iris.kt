package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Fracture.Companion.euclideanModulo
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Fracture.Companion.hueToRgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Iris, a port of the Iris visualiser of Vissonance by Tariq Soliman, with credit.
 *
 * - Title: Iris, one of the seven visualisers of Vissonance
 * - Author: Tariq Soliman (GitHub `tariqksoliman`)
 * - URL: https://tariqksoliman.github.io/Vissonance/
 * - Repository: https://github.com/tariqksoliman/Vissonance (`scripts/visualizers/Iris.js` and `scripts/Spectrum.js`)
 * - Licence as found: MIT. `LICENSE` reads "Copyright (c) 2020 Tariq Soliman" (the file was added on 2020-04-17).
 * - Year: 2017. The code was written from 2017-03-11 to 2017-03-17, on three.js r84.
 *
 * Deviations from the original:
 * - [WebAudioAnalyser] rebuilds the page's analyser from the player's spectrum, with the page's settings: an FFT
 *   of 4096 on a 44.1 kHz layout, smoothing 0.8, -100 to -30 dB.
 * - The analyser is read 60 times per second of heard music, the page's frame rate, so the iris stands still while
 *   the music is paused or silent.
 * - Each spoke is drawn as a strip of 16 steps whose colours follow the true depth, where the page shaded every
 *   pixel by its depth. Between two steps the colour is blended on the screen, which is within a few percent.
 * - The page's radial background is drawn as one cached gradient, and every colour is multiplied by the flash
 *   guard's light share.
 *
 * 128 thin spokes stand in a full circle round a dark pupil, mirrored left and right, the bass at the top and both
 * halves running down to meet at the bottom. Each spoke is brightest where it leaves the pupil and widens and fades
 * to black past the edge of the frame. The pupil grows with loudness and each spoke's inner end is pushed out by
 * its band, so the pupil's edge is a spiky ring of the spectrum. Every spoke shares one hue that moves from
 * violet-blue when quiet through green to orange when loud.
 */
internal class Iris : Visualization {

    override val name: String = "Iris"
    override val bucket: VizEnergy = VizEnergy.Mid
    override val post: PostSpec = PostSpec.Off

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // Each spoke's inner end: half its bar, in world units at the far edge.
            VizDrive(VizDriver.Bands, VizProperty.Shape, VizCurve.Range(0.5f, 1f), VizResponse.envelope(ANALYSER_SECONDS)),
            // The loudness, the mean of the page's 2048 bytes, widens the pupil...
            VizDrive(VizDriver.Level, VizProperty.Size, VizCurve.Linear, VizResponse.envelope(ANALYSER_SECONDS)),
            // ...and sets the one hue of every spoke.
            VizDrive(VizDriver.Level, VizProperty.Colour, VizCurve.Linear, VizResponse.envelope(ANALYSER_SECONDS)),
        ),
        silence = VizSilence.Still,
    )

    /** The page's analyser: `fftSize = 4096`, the Web Audio defaults for the rest, bins laid out for 44.1 kHz. */
    internal val analyser = WebAudioAnalyser(
        fftSize = FFT_SIZE, smoothing = 0.8f, minDecibels = -100f, maxDecibels = -30f, sampleRate = PAGE_SAMPLE_RATE,
    )

    /** `spectrum.GetVisualBins(dataArray, 128, 4, 1300)`. Only the first 64 bars are drawn. */
    internal val bins = VissonanceBins(NUM_BARS, SPECTRUM_START, SPECTRUM_END)

    /** `visualArray` from the last read. */
    internal val visual = FloatArray(NUM_BARS)

    /** The loudness of the last read: the plain mean of every byte. Iris does not smooth it. */
    internal var loudness = 0f
        private set

    // The spoke colour, `hsl(h, 100%, 50%)`. The page makes its material with hue 250 before the first read.
    internal var red = 0f
        private set
    internal var green = 0f
        private set
    internal var blue = 0f
        private set

    private val step = DisplayStep()
    private var owed = 0f
    private var started = false

    private val mesh = TriangleMesh(maxVertices = MESH_VERTICES, maxIndices = MESH_INDICES)

    // The cached page background, rebuilt when the frame's size changes.
    private var backgroundSize = -1f
    private var backgroundBrush: Brush? = null

    init {
        setHue(INITIAL_HUE)
    }

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state)
        val light = state.lightScale.coerceIn(0f, 1f)
        val longest = max(size.width, size.height)
        if (longest != backgroundSize) {
            backgroundSize = longest
            // `radial-gradient(circle, #060606, #010101)`: a circle reaching the farthest corner.
            val reach = sqrt(size.width * size.width + size.height * size.height) / 2f
            backgroundBrush = Brush.radialGradient(
                colors = listOf(Color(0xFF060606), Color(0xFF010101)),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = reach.coerceAtLeast(1f),
            )
        }
        backgroundBrush?.let { drawRect(it, alpha = light) }
    }

    override fun DrawScope.drawFront(state: VizRenderState) {
        advance(state)
        if (size.width <= 0f || size.height <= 0f) return
        val light = state.lightScale.coerceIn(0f, 1f)
        val middleX = size.width / 2f
        val middleY = size.height / 2f
        val focal = middleY * FOCAL
        // A spoke must run past the farthest corner: the page's strips always do.
        val corner = sqrt(middleX * middleX + middleY * middleY)
        mesh.clear()
        for (bar in 0 until NUM_BARS / 2) {
            val inner = visual[bar] / 2f + INNER_BASE + loudness / LOUDNESS_DIVISOR
            val angle = bar * (2.0 * PI / NUM_BARS) + PI / NUM_BARS
            for (side in 0..1) {
                val turn = if (side == 0) angle else -angle
                addSpoke(inner, turn, middleX, middleY, focal, corner, light)
            }
        }
        drawMesh(mesh)
    }

    /**
     * One strip, 3 units wide, from its inner end [inner] units off the axis at the far edge to 16.6 units off the
     * axis 3.8 units in front of the camera, turned [turn] radians about the axis. Its steps are spaced evenly on
     * the screen, from the pupil to beyond [corner], so the colour, which follows the depth, is sampled where it
     * shows.
     */
    private fun addSpoke(inner: Float, turn: Double, middleX: Float, middleY: Float, focal: Float, corner: Float, light: Float) {
        val cosTurn = cos(turn).toFloat()
        val sinTurn = sin(turn).toFloat()
        val first = mesh.vertexCount
        val start = focal * inner / FAR_DEPTH
        val end = max(corner * OVERSHOOT, start * 2f)
        for (index in 0 until STEPS) {
            val radius = start + (end - start) * index / (STEPS - 1)
            val t = along(radius, inner, focal)
            val y = inner + t * (NEAR_Y - inner)
            val depth = FAR_DEPTH - t * (FAR_DEPTH - NEAR_DEPTH)
            val colour = colourAt(depth, red, green, blue, light)
            // The strip's two edges, 1.5 units either side of its centre line, turned with it.
            for (edge in 0..1) {
                val x = if (edge == 0) -HALF_WIDTH else HALF_WIDTH
                val worldX = x * cosTurn - y * sinTurn
                val worldY = x * sinTurn + y * cosTurn
                mesh.vertex(middleX + focal * worldX / depth, middleY - focal * worldY / depth, colour)
            }
        }
        for (index in 0 until STEPS - 1) {
            val a = first + index * 2
            mesh.quad(a, a + 1, a + 3, a + 2)
        }
    }

    /** Runs the page's frames that this display frame owes: once per sixtieth of a heard second. */
    private fun advance(state: VizRenderState) {
        val dt = step.of(state) ?: return
        val heard = dt * state.frame.audible
        val reads = if (!started) {
            // The page renders once as soon as the visualiser is made, whatever is playing.
            started = true
            1
        } else {
            owed = minOf(owed + heard, MAX_OWED)
            var due = 0
            while (owed >= READ_PERIOD / 2f) {
                owed -= READ_PERIOD
                due++
            }
            due
        }
        if (reads > 0) read(state.frame)
    }

    /** `render()`: the analyser, the loudness, the bars and the colour. */
    private fun read(frame: SpectrumFrame) {
        analyser.update(frame)
        val bytes = analyser.frequencyBytes
        loudness = VissonanceBins.loudness(bytes)
        bins.visualBins(bytes, visual)
        setHue(hueFor(loudness))
    }

    /** `THREE.Color('hsl(h, 100%, 50%)')` in three.js r84, through its `setHSL`. */
    private fun setHue(degrees: Double) {
        val h = euclideanModulo(degrees / 360.0, 1.0)
        // With a saturation of 1 and a lightness of 0.5, `p` is 1 and `q` is 0.
        red = hueToRgb(0.0, 1.0, h + 1.0 / 3.0).toFloat()
        green = hueToRgb(0.0, 1.0, h).toFloat()
        blue = hueToRgb(0.0, 1.0, h - 1.0 / 3.0).toFloat()
    }

    override fun reset() {
        step.reset()
        analyser.reset()
        visual.fill(0f)
        loudness = 0f
        setHue(INITIAL_HUE)
        owed = 0f
        started = false
    }

    internal companion object {
        const val FFT_SIZE = 4096
        const val PAGE_SAMPLE_RATE = 44_100

        /** `numBars`: bars per read. Only the first half are drawn, each as two mirrored spokes. */
        const val NUM_BARS = 128
        const val SPECTRUM_START = 4
        const val SPECTRUM_END = 1300

        /** The far edge's y: `visualArray[i] / 2 + (65 + loudness / 1.5)`. */
        const val INNER_BASE = 65f
        const val LOUDNESS_DIVISOR = 1.5f

        /**
         * The strip's far and near edges after `rotateX(PI / 1.8)` and the 60-unit lift, seen from the camera at
         * z = 250: the far edge 496.2 units away, the near edge 3.8 units away and 16.6 units off the axis.
         */
        val FAR_DEPTH: Float = (250.0 + 250.0 * sin(PI / 1.8)).toFloat()
        val NEAR_DEPTH: Float = (250.0 - 250.0 * sin(PI / 1.8)).toFloat()
        val NEAR_Y: Float = (60.0 + 250.0 * cos(PI / 1.8)).toFloat()

        /** Half of `PlaneBufferGeometry(3, 500)`'s width. */
        const val HALF_WIDTH = 1.5f

        /** The fragment shader's `-pos.z / 180.0`: a spoke is full colour 180 units away and brighter beyond. */
        const val BRIGHTNESS_DEPTH = 180f

        /** The page's hue for its material before the first read. */
        const val INITIAL_HUE = 250.0

        /** `1 / tan(35 degrees)` for the page's vertical field of view of 70 degrees. */
        val FOCAL: Float = (1.0 / tan(35.0 * PI / 180.0)).toFloat()

        /** Steps along a spoke, and how far past the farthest corner the last one lies. */
        const val STEPS = 16
        const val OVERSHOOT = 1.25f

        const val PAGE_RATE = 60f
        const val READ_PERIOD = 1f / PAGE_RATE
        private const val MAX_OWED = 6f * READ_PERIOD

        /** The analyser's smoothing of 0.8 per read, as a time constant: about 75 ms. */
        val ANALYSER_SECONDS: Float = (-1.0 / (PAGE_RATE * ln(0.8))).toFloat()

        private const val MESH_VERTICES = NUM_BARS * STEPS * 2 + 16
        private const val MESH_INDICES = NUM_BARS * (STEPS - 1) * 6 + 16

        /** `modn(250 - loudness * 2.2, 360)`, in degrees. */
        fun hueFor(loudness: Float): Double = euclideanModulo(250.0 - loudness * 2.2, 360.0)

        /**
         * How far along a spoke, 0 at its far edge and 1 at its near edge, lies the point that lands [radius] pixels
         * from the middle, for an inner end [inner] units off the axis and a lens of [focal] pixels.
         */
        fun along(radius: Float, inner: Float, focal: Float): Float {
            val t = (radius * FAR_DEPTH - focal * inner) / (radius * (FAR_DEPTH - NEAR_DEPTH) + focal * (NEAR_Y - inner))
            return t.coerceIn(0f, 1f)
        }

        /** A step's colour at [depth]: `-viewZ / 180` times the spoke colour, clamped as the frame buffer does. */
        fun colourAt(depth: Float, red: Float, green: Float, blue: Float, light: Float): Int {
            val shade = depth / BRIGHTNESS_DEPTH
            return (0xFF shl 24) or (channel(shade * red, light) shl 16) or
                (channel(shade * green, light) shl 8) or channel(shade * blue, light)
        }

        private fun channel(value: Float, light: Float): Int = (value.coerceIn(0f, 1f) * light * 255f + 0.5f).toInt()
    }
}
