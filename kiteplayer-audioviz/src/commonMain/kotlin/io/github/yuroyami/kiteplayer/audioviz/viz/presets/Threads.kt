package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import io.github.yuroyami.kiteplayer.audioviz.viz.VizNeed
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizQualityControl
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Threads, a port of the Silk visualiser of Vissonance by Tariq Soliman, with credit. It is named Threads
 * because the catalogue's Silk is the port of Matt DesLauriers' piece.
 *
 * - Title: Silk, one of the seven visualisers of Vissonance
 * - Author: Tariq Soliman (GitHub `tariqksoliman`)
 * - URL: https://tariqksoliman.github.io/Vissonance/
 * - Repository: https://github.com/tariqksoliman/Vissonance (`scripts/visualizers/Silk.js` and `scripts/Spectrum.js`)
 * - Licence as found: MIT. `LICENSE` reads "Copyright (c) 2020 Tariq Soliman" (the file was added on 2020-04-17).
 * - Year: 2017. The code was written from 2017-03-11 to 2017-03-17, on three.js r84.
 *
 * Deviations from the original:
 * - [WebAudioAnalyser] rebuilds the page's analyser from the player's spectrum, with the page's settings: an FFT
 *   of 4096 on a 44.1 kHz layout, smoothing 0.8, -100 to -30 dB. The player's bins are about twice as wide as
 *   the page's, so neighbouring bass bins are interpolated.
 * - The analyser is read 60 times a second, the page's frame rate, whatever the screen's rate.
 * - The climb of each hexagon is the page's per-frame step scaled by frame time, on heard time, so the hexagons
 *   stand still while the music is paused.
 * - The page fades its canvas 20 percent toward white every frame. Here that is a half life of 52 ms, so the
 *   trails are as long on any screen.
 * - The fade goes toward #fdfdfd, the page's clear colour, not pure white. On the page's 8-bit buffer a 20
 *   percent step toward white cannot lift a channel past 253, so the page settles on #fdfdfd as well.
 * - The ground and the hexagons are multiplied by the flash guard's light scale. It is 1 unless the picture
 *   would flash.
 * - The page puts every hexagon back on the centre line when a new song loads. Here that happens when the
 *   drawing is shown.
 * - The page's visualiser menu, song title and drag-and-drop upload are left out.
 *
 * On an off-white page, small hexagons stream up and down away from a horizontal centre line, mirrored into
 * all four quarters, and leave short trails that fade into the white. The spectrum runs out from the middle,
 * bass near the vertical centre line and treble at the sides. Only loud bands get through the page's gate:
 * a loud band draws a large, fast hexagon in green, yellow or orange that climbs 30 units and snaps back to
 * the centre line, so it draws the same thread again and again. Quiet bands stay small and nearly white.
 */
internal class Threads : Visualization {

    override val name: String = "Threads"
    override val bucket: VizEnergy = VizEnergy.Mid
    override val post: PostSpec = PostSpec.Off
    override val paintsWholeScreen: Boolean = true

    /** The page's white plane at alpha 0.2 keeps 80 percent of the picture a frame: a half life of 51.8 ms. */
    override val trail: Float = TRAIL

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // Each band sets its hexagon's size. The gate leaves a band below half of the byte range under
            // a tenth of a unit, about one pixel at 1080p.
            VizDrive(VizDriver.Bands, VizProperty.Size, VizCurve.Range(0.5f, 1f), VizResponse.envelope(ANALYSER_SECONDS)),
            // ...its climb, the same value added to the height every page frame...
            VizDrive(VizDriver.Bands, VizProperty.Speed, VizCurve.Range(0.5f, 1f), VizResponse.Rate),
            // ...and its colour, from near white through blue and green to orange.
            VizDrive(VizDriver.Bands, VizProperty.Colour, VizCurve.Range(0.5f, 1f), VizResponse.envelope(ANALYSER_SECONDS)),
        ),
        needs = setOf(VizNeed.EchoBuffer),
        // In silence every band is at the floor, so the hexagons shrink away and the trails fade into the ground.
        silence = VizSilence.Fade,
        quality = listOf(VizQualityControl.EchoResolution),
    )

    /** The page's analyser: `fftSize = 4096`, the Web Audio defaults for the rest, bins laid out for 44.1 kHz. */
    internal val analyser = WebAudioAnalyser(
        fftSize = FFT_SIZE, smoothing = 0.8f, minDecibels = -100f, maxDecibels = -30f, sampleRate = PAGE_SAMPLE_RATE,
    )

    /** `spectrum.GetVisualBins(dataArray, 512, 6, 1300)`. */
    internal val bins = VissonanceBins(NUM_BARS, SPECTRUM_START, SPECTRUM_END)

    /** `visualArray` from the last read, 1 to 255 per band. */
    internal val visual = FloatArray(NUM_BARS)

    /** Each band's hexagon scale, which is also its climb per page frame: `4 log10(1 + v / 255 / 7)`. */
    internal val scale = FloatArray(NUM_BARS)

    /**
     * How far each band's hexagon has climbed from the centre line, in world units. The four groups share it:
     * two climb by it and two fall by it, and all four snap back together.
     */
    internal val climb = FloatArray(NUM_BARS)

    /** Each band's colour as 0 to 1 channels, `hsl((250 - 0.9 v) mod 360, 90%, (100 - min(40, v))%)`. */
    private val red = FloatArray(NUM_BARS)
    private val green = FloatArray(NUM_BARS)
    private val blue = FloatArray(NUM_BARS)

    /** The smoothed loudness of the last read. At 1 or below, every hexagon sits on the centre line. */
    internal var loudness = 0f
        private set
    private var lastLoudness = 0f

    /** How many page frames have read the analyser since the last reset. */
    internal var reads = 0L
        private set

    private val step = DisplayStep()
    private var owed = 0f
    private var started = false

    /** True while [draw] paints the echo layer, which holds the frame before; false on a plain canvas. */
    private var echoing = false

    private val mesh = TriangleMesh(maxVertices = GROUPS * NUM_BARS * SIDES, maxIndices = GROUPS * NUM_BARS * FAN_INDICES)

    override fun onPreviousFrame(picture: ImageBitmap?) {
        echoing = true
    }

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state)
        val light = state.lightScale.coerceIn(0f, 1f)
        val grey = GROUND * light
        val ground = Color(grey, grey, grey)
        if (echoing) {
            // Behind the frame before, which the surface has already laid back at 80 percent: 20 percent of it
            // becomes the ground. The page drew its white plane behind this frame's hexagons for the same result.
            drawRect(ground, blendMode = BlendMode.DstOver)
        } else {
            drawRect(ground)
        }
        echoing = false
        if (size.width <= 0f || size.height <= 0f) return

        val unit = pixelsPerUnit(size.height)
        val middleX = size.width / 2f
        val middleY = size.height / 2f
        mesh.clear()
        // Band order, as the page's renderer sorted the shared materials: where two hexagons overlap at the same
        // depth, the later band covers the earlier one.
        for (i in 0 until NUM_BARS) {
            // The page scales a band at the floor to 0.0001, a fiftieth of a pixel.
            if (visual[i] <= 1f) continue
            val radius = RADIUS * scale[i] * unit
            val x = BAR_X[i] * unit
            if (x - radius > middleX) continue
            val y = climb[i] * unit
            val argb = packed(i, light)
            addHexagon(middleX + x, middleY - y, radius, argb)
            addHexagon(middleX - x, middleY + y, radius, argb)
            addHexagon(middleX - x, middleY - y, radius, argb)
            addHexagon(middleX + x, middleY + y, radius, argb)
        }
        drawMesh(mesh)
    }

    /** Runs the page frames this display frame owes, then climbs by heard time. */
    private fun advance(state: VizRenderState) {
        val dt = step.of(state) ?: return
        val due = if (!started) {
            // The page renders once as soon as the visualiser is made, whatever is playing.
            started = true
            1
        } else {
            owed = min(owed + dt, MAX_OWED)
            var count = 0
            // Rounded to the nearest page frame, so a jittery 60 Hz screen still reads once per frame.
            while (owed >= READ_PERIOD / 2f) {
                owed -= READ_PERIOD
                count++
            }
            count
        }
        if (due > 0) read(state.frame, due)
        climbBy(dt * state.frame.audible * PAGE_RATE)
    }

    /** `render()` up to the motion, for [count] page frames. A paused player's frame is smoothed once. */
    private fun read(frame: SpectrumFrame, count: Int) {
        analyser.update(frame)
        listen(analyser.frequencyBytes, count)
    }

    /** The bands, the scales and the colours from one row of `getByteFrequencyData`, and [count] loudness steps. */
    internal fun listen(bytes: IntArray, count: Int = 1) {
        bins.visualBins(bytes, visual)
        val mean = VissonanceBins.loudness(bytes)
        repeat(count) {
            loudness = (mean + lastLoudness) / 2f
            lastLoudness = loudness
        }
        for (i in 0 until NUM_BARS) {
            scale[i] = scaleOf(visual[i])
            setColour(i, visual[i])
        }
        reads += count
    }

    /** Every hexagon climbs by its scale per page frame, for [frames] page frames, and snaps back past 30 units. */
    internal fun climbBy(frames: Float) {
        for (i in 0 until NUM_BARS) {
            var height = climb[i] + scale[i] * frames
            if (height > MAX_CLIMB || loudness <= 1f) height = 0f
            climb[i] = height
        }
    }

    /** `setUniformColor`, through `THREE.Color('hsl(h, 90%, l%)')` and `setHSL` of three.js r84. */
    private fun setColour(band: Int, value: Float) {
        val h = modn(250.0 - value * 0.9, 360.0) / 360.0
        // `parseInt` of the value, and the CSS parser's whole percentages.
        val l = (100 - min(40, floor(value.toDouble()).toInt())) / 100.0
        val s = SATURATION
        val p = if (l <= 0.5) l * (1.0 + s) else l + s - l * s
        val q = 2.0 * l - p
        red[band] = hueToRgb(q, p, h + 1.0 / 3.0).toFloat()
        green[band] = hueToRgb(q, p, h).toFloat()
        blue[band] = hueToRgb(q, p, h - 1.0 / 3.0).toFloat()
    }

    /** Band [band]'s colour as packed ARGB, written to 8 bits the way the page's framebuffer takes it. */
    internal fun packed(band: Int, light: Float = 1f): Int =
        (0xFF shl 24) or (channel(red[band] * light) shl 16) or (channel(green[band] * light) shl 8) or
            channel(blue[band] * light)

    /** A hexagon of `CircleBufferGeometry(10, 6)`: a corner on each side along x, flat top and bottom. */
    private fun addHexagon(x: Float, y: Float, radius: Float, argb: Int) {
        val first = mesh.vertex(x + radius, y, argb)
        if (first < 0) return
        for (corner in 1 until SIDES) mesh.vertex(x + CORNER_X[corner] * radius, y + CORNER_Y[corner] * radius, argb)
        for (corner in 1 until SIDES - 1) mesh.triangle(first, first + corner, first + corner + 1)
    }

    override fun reset() {
        analyser.reset()
        visual.fill(0f)
        scale.fill(0f)
        climb.fill(0f)
        red.fill(0f)
        green.fill(0f)
        blue.fill(0f)
        loudness = 0f
        lastLoudness = 0f
        reads = 0L
        step.reset()
        owed = 0f
        started = false
        echoing = false
    }

    internal companion object {
        const val FFT_SIZE = 4096
        const val PAGE_SAMPLE_RATE = 44_100

        /** `numBars`, and the range of FFT bins handed to `GetVisualBins`. */
        const val NUM_BARS = 512
        const val SPECTRUM_START = 6
        const val SPECTRUM_END = 1300

        /** The page's frame rate: `requestAnimationFrame` behind a `setTimeout` of 1000 / 60. */
        const val PAGE_RATE = 60f
        const val READ_PERIOD = 1f / PAGE_RATE
        private const val MAX_OWED = 6f * READ_PERIOD

        /** The smoothing's time constant at 60 reads a second: -1 / (60 ln 0.8), about 75 ms. */
        val ANALYSER_SECONDS: Float = (-1.0 / (PAGE_RATE * ln(0.8))).toFloat()

        /** The share of the picture the page keeps each frame: its plane is white at alpha 0.2. */
        const val TRAIL = 0.8f

        /** The page's clear colour, `0xfdfdfd`, and where its fade settles. */
        const val GROUND = 253f / 255f

        /** `CircleBufferGeometry( 10, 6 )`: the radius at scale 1, in world units. */
        const val RADIUS = 10f
        const val SIDES = 6
        private const val FAN_INDICES = (SIDES - 2) * 3

        /** The page's four groups: as made, turned half a turn twice, and as made again. */
        const val GROUPS = 4

        /** A hexagon snaps back to the centre line once it has climbed past this many units. */
        const val MAX_CLIMB = 30f

        /** `hsl(h, 90%, l%)`. */
        private const val SATURATION = 0.9

        /** The hexagons sit 50 units in front of the camera. */
        const val DEPTH = 50.0

        /** `PerspectiveCamera( 70, ... )`: the vertical field of view, in degrees. */
        const val FOV_DEGREES = 70.0

        /** The height of the view at the hexagons' depth, in world units: 2 x 50 x tan(35 degrees), about 70.02. */
        val VIEW_HEIGHT: Float = (2.0 * DEPTH * tan(FOV_DEGREES / 2.0 * PI / 180.0)).toFloat()

        /**
         * Each band's x, `posX` from 3 in steps of `barGap = 0.12`, added up as the page did. At 16:9 the view is
         * 62.2 units each side of the middle, so the last bands, out to 64.3, run just past the edge, as on the page.
         */
        val BAR_X: FloatArray = run {
            val x = FloatArray(NUM_BARS)
            var posX = 3.0
            for (i in 0 until NUM_BARS) {
                x[i] = posX.toFloat()
                posX += 0.12
            }
            x
        }

        /** The hexagon's corners at scale 1: at 0, 60, 120, 180, 240 and 300 degrees. */
        private val CORNER_X = FloatArray(SIDES) { cos(it * PI / 3.0).toFloat() }
        private val CORNER_Y = FloatArray(SIDES) { sin(it * PI / 3.0).toFloat() }

        /** Pixels per world unit at the hexagons' depth, on a canvas [height] pixels high. */
        fun pixelsPerUnit(height: Float): Float = height / VIEW_HEIGHT

        /** The hexagon scale and the climb per page frame for a band at [value]: `4 log10(1 + v / 255 / 7)`. */
        fun scaleOf(value: Float): Float =
            if (value <= 1f) FLOOR_SCALE else (4.0 * log10(1.0 + value / 255.0 / 7.0)).toFloat()

        /** The page's scale for a band at the floor. */
        private const val FLOOR_SCALE = 0.0001f

        /** `modn`: a remainder that is never negative. */
        private fun modn(n: Double, m: Double): Double = ((n % m) + m) % m

        /** `hue2rgb` inside `setHSL` of three.js r84. */
        private fun hueToRgb(p: Double, q: Double, tIn: Double): Double {
            var t = tIn
            if (t < 0.0) t += 1.0
            if (t > 1.0) t -= 1.0
            if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t
            if (t < 1.0 / 2.0) return q
            if (t < 2.0 / 3.0) return p + (q - p) * 6.0 * (2.0 / 3.0 - t)
            return p
        }

        private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}
