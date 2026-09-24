package io.github.yuroyami.kiteplayer.audioviz.viz.presets

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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Fracture, a port of the Fracture visualiser of Vissonance by Tariq Soliman, with credit.
 *
 * - Title: Fracture, one of the seven visualisers of Vissonance
 * - Author: Tariq Soliman (GitHub `tariqksoliman`)
 * - URL: https://tariqksoliman.github.io/Vissonance/
 * - Repository: https://github.com/tariqksoliman/Vissonance (`scripts/visualizers/Fracture.js` and `scripts/Spectrum.js`)
 * - Licence as found: MIT. `LICENSE` reads "Copyright (c) 2020 Tariq Soliman" (the file was added on 2020-04-17).
 * - Year: 2017. The code was written from 2017-03-11 to 2017-03-17, on three.js r84.
 *
 * Deviations from the original:
 * - [WebAudioAnalyser] rebuilds the page's analyser from the player's spectrum, with the page's settings: an FFT
 *   of 4096 on a 44.1 kHz layout, smoothing 0.8, -100 to -30 dB. The player's bins are about twice as wide as
 *   the page's, so neighbouring bass bins are interpolated.
 * - The page's frame (the analyser read and the loudness average) runs 60 times per second of heard music, not
 *   once per browser frame, and the roll and the flight advance by heard time. So the corridor stands still
 *   while the music is paused or silent.
 * - The camera roll and the flight speed are multiplied by the reduced-motion setting.
 * - Each strip keeps its 128 lifted columns, and its 128 flat columns become one flat band under the strip, for
 *   phones at 1080p. The surface is the same, with about half the corners.
 * - The strips are painted far to near with a colour at each corner, where the page used a depth buffer and a
 *   colour per pixel. A ridge is cut where it passes the opposite surface, which is where the depth buffer hid it.
 * - The white clear colour and the strips are multiplied by the flash guard's light scale. It is 1 unless the
 *   picture would flash.
 *
 * A flight down an endless corridor on a white void. The floor and the ceiling are 64 thin strips each, and the
 * spectrum lifts the far edge of every strip into a jagged ridge, with the bass at the outer edge. Strips near
 * the camera are black, and far strips glow in one hue that follows the loudness: violet when quiet, green in
 * the middle, orange when loud. Loud music pushes the floor and the ceiling toward eye level, speeds the flight
 * and rolls the camera.
 */
internal class Fracture : Visualization {

    override val name: String = "Fracture"
    override val bucket: VizEnergy = VizEnergy.High
    override val post: PostSpec = PostSpec.Off

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // Ridge heights, a tenth of a unit per step of a bar. The gate leaves under 3 percent of a ridge
            // below half of the byte range.
            VizDrive(VizDriver.Bands, VizProperty.Shape, VizCurve.Range(0.5f, 1f), VizResponse.envelope(ANALYSER_SECONDS)),
            // The loudness is the mean of the page's 2048 bytes. It sets the flight and the roll as rates...
            VizDrive(VizDriver.Level, VizProperty.Speed, VizCurve.Linear, VizResponse.Rate),
            VizDrive(VizDriver.Level, VizProperty.Camera, VizCurve.Linear, VizResponse.Rate),
            // ...the one hue of every strip...
            VizDrive(VizDriver.Level, VizProperty.Colour, VizCurve.Linear, VizResponse.envelope(ANALYSER_SECONDS)),
            // ...and how far the floor and the ceiling close in, all the way at a mean byte of 125.
            VizDrive(
                VizDriver.Level, VizProperty.Size, VizCurve.Range(0f, SQUEEZE_MAX / SQUEEZE_SCALE),
                VizResponse.envelope(ANALYSER_SECONDS),
            ),
        ),
        silence = VizSilence.Still,
    )

    /** The page's analyser: `fftSize = 4096`, the Web Audio defaults for the rest, bins laid out for 44.1 kHz. */
    internal val analyser = WebAudioAnalyser(
        fftSize = FFT_SIZE, smoothing = 0.8f, minDecibels = -100f, maxDecibels = -30f, sampleRate = PAGE_SAMPLE_RATE,
    )

    /** `spectrum.GetVisualBins(dataArray, 128, -200, 1300)`. */
    internal val bins = VissonanceBins(NUM_BARS, SPECTRUM_START, SPECTRUM_END)

    /** `visualArray` from the last read. Every strip shows it. Before the first read the strips are flat. */
    internal val visual = FloatArray(NUM_BARS)

    /** The smoothed loudness of the last read. */
    internal var loudness = 0f
        private set
    private var lastLoudness = 0f

    /** How far the floor and the ceiling have closed in toward eye level, in world units. */
    internal var squeeze = 0f
        private set

    /** How far the corridor has flown, 0 to 640 units. Every strip sits at a fixed step from this. */
    internal var travel = 0.0
        private set

    /** `camera.rotation.z`, in radians, kept within one turn. */
    internal var roll = 0.0
        private set

    // The strip colour, `hsl(h, 100%, 50%)`. The page makes the strips with hue 240.
    private var red = 0f
    private var green = 0f
    private var blue = 1f

    private val step = DisplayStep()
    private var owed = 0f
    private var started = false

    private val mesh = TriangleMesh(maxVertices = MESH_VERTICES, maxIndices = MESH_INDICES)

    /** The x of the page's columns 0 to 128: `ix * 300 / 255 - 150`. */
    private val columnX = FloatArray(NUM_BARS + 1) { (it * (BAR_LEN * 2.0) / (COLUMNS - 1) - BAR_LEN).toFloat() }

    // One ridge's corners: local corner 2j is column j on the near edge and 2j + 1 the same column on the far edge.
    private val cornerX = FloatArray(RIDGE_CORNERS)
    private val cornerY = FloatArray(RIDGE_CORNERS)
    private val cornerDepth = FloatArray(RIDGE_CORNERS)
    private val screenX = FloatArray(RIDGE_CORNERS)
    private val screenY = FloatArray(RIDGE_CORNERS)
    private val clipCorners = IntArray(3)
    private val polygon = IntArray(4)

    // This frame's view: the canvas middle, the lens, the roll, the light, and the two planes.
    private var frameWidth = 0f
    private var frameHeight = 0f
    private var middleX = 0f
    private var middleY = 0f
    private var focal = 0f
    private var cosRoll = 1f
    private var sinRoll = 0f
    private var light = 1f
    private var floorY = 0f
    private var ceilingY = 0f

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state)
        val grey = state.lightScale.coerceIn(0f, 1f)
        // The renderer clears to `hsl(0, 0%, 100%)` before every frame.
        drawRect(Color(grey, grey, grey))
    }

    override fun DrawScope.drawFront(state: VizRenderState) {
        advance(state)
        if (size.width <= 0f || size.height <= 0f) return
        frameWidth = size.width
        frameHeight = size.height
        middleX = frameWidth / 2f
        middleY = frameHeight / 2f
        focal = middleY * FOCAL
        cosRoll = cos(roll).toFloat()
        sinRoll = sin(roll).toFloat()
        light = state.lightScale.coerceIn(0f, 1f)
        // group.position.y is -10 plus the squeeze and each strip sits 10 lower; the ceiling is the same turned over.
        floorY = PLANE_Y - GROUP_Y + squeeze
        ceilingY = GROUP_Y - PLANE_Y - squeeze

        // Slot m holds strip (steps + m) mod 64, its near edge 10m - 15 - offset units ahead of the camera. Both
        // edges come from one expression, so neighbouring strips share their corners exactly.
        val steps = (travel / BAR_GAP).toInt().coerceIn(0, NUM_BANDS - 1)
        val offset = (travel - steps * BAR_GAP).toFloat()

        mesh.clear()
        // The flat ground first: one band per strip on each surface. Nothing on a plane can hide a ridge.
        for (slot in NUM_BANDS - 1 downTo 0) {
            val far = (BAR_GAP_UNITS * slot - FAR_EDGE).toFloat() - offset
            if (far <= NEAR_CLIP) continue
            val near = max((BAR_GAP_UNITS * slot - NEAR_EDGE).toFloat() - offset, NEAR_CLIP)
            addBand(floorY, near, far)
            addBand(ceilingY, near, far)
        }
        // Then the ridges, far to near: the depth ranges of two strips never overlap, so the nearer one wins.
        for (slot in NUM_BANDS - 1 downTo 0) {
            val far = (BAR_GAP_UNITS * slot - FAR_EDGE).toFloat() - offset
            if (far <= NEAR_CLIP) continue
            val nearEdge = (BAR_GAP_UNITS * slot - NEAR_EDGE).toFloat() - offset
            val near = max(nearEdge, NEAR_CLIP)
            // A strip that reaches behind the camera starts where it crosses the clip, part way up its slope.
            val reach = if (nearEdge < NEAR_CLIP) (NEAR_CLIP - nearEdge) / STRIP_DEPTH else 0f
            val odd = (steps + slot) % NUM_BANDS % 2 != 0
            if (mesh.vertexCount + PAIR_VERTICES > mesh.maxVertices || mesh.indexCount + PAIR_INDICES > mesh.maxIndices) {
                drawMesh(mesh)
                mesh.clear()
            }
            addRidge(floor = true, odd = odd, near = near, far = far, reach = reach)
            addRidge(floor = false, odd = odd, near = near, far = far, reach = reach)
        }
        drawMesh(mesh)
    }

    /** Runs the page's frames that this display frame owes, then moves the corridor on by heard time. */
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
            // Rounded to the nearest page frame, so a jittery 60 Hz screen still reads once per frame.
            while (owed >= READ_PERIOD / 2f) {
                owed -= READ_PERIOD
                due++
            }
            due
        }
        if (reads > 0) read(state.frame, reads)
        fly(heard * PAGE_RATE * state.motionScale)
    }

    /** `render()` up to the motion: the analyser, the bars, the loudness, the squeeze and the colour. */
    private fun read(frame: SpectrumFrame, reads: Int) {
        analyser.update(frame)
        val bytes = analyser.frequencyBytes
        bins.visualBins(bytes, visual)
        val mean = VissonanceBins.loudness(bytes)
        // Reads that share one analysis find the same mean, and the page's average still runs once per read.
        repeat(reads) {
            loudness = (mean + lastLoudness) / 2f
            lastLoudness = loudness
        }
        squeeze = squeezeFor(loudness)
        setHue(hueFor(loudness))
    }

    /** The roll and the flight for [frames] page frames, which may be a fraction of one. */
    private fun fly(frames: Float) {
        if (frames <= 0f || loudness <= 1f) return
        roll = (roll - rollStep(loudness) * frames) % FULL_TURN
        travel = (travel + flightStep(loudness) * frames) % CORRIDOR
    }

    /** `THREE.Color('hsl(h, 100%, 50%)')` in three.js r84, through its `setHSL`. */
    private fun setHue(degrees: Double) {
        val h = euclideanModulo(degrees / 360.0, 1.0)
        val saturation = 1.0
        val lightness = 0.5
        val p = if (lightness <= 0.5) lightness * (1.0 + saturation) else lightness + saturation - lightness * saturation
        val q = 2.0 * lightness - p
        red = hueToRgb(q, p, h + 1.0 / 3.0).toFloat()
        green = hueToRgb(q, p, h).toFloat()
        blue = hueToRgb(q, p, h - 1.0 / 3.0).toFloat()
    }

    /** One flat band of the floor or the ceiling at height [y], from depth [near] to depth [far], 300 wide. */
    private fun addBand(y: Float, near: Float, far: Float) {
        val nearColour = colourAt(near, red, green, blue, light)
        val farColour = colourAt(far, red, green, blue, light)
        val a = mesh.vertex(projectX(-BAR_LEN_F, y, near), projectY(-BAR_LEN_F, y, near), nearColour)
        val b = mesh.vertex(projectX(BAR_LEN_F, y, near), projectY(BAR_LEN_F, y, near), nearColour)
        val c = mesh.vertex(projectX(BAR_LEN_F, y, far), projectY(BAR_LEN_F, y, far), farColour)
        val d = mesh.vertex(projectX(-BAR_LEN_F, y, far), projectY(-BAR_LEN_F, y, far), farColour)
        if (a < 0 || d < 0) return
        val p = mesh.positions
        if (offScreen(p[a * 2], p[b * 2], p[c * 2], p[d * 2], frameWidth) ||
            offScreen(p[a * 2 + 1], p[b * 2 + 1], p[c * 2 + 1], p[d * 2 + 1], frameHeight)
        ) {
            return
        }
        mesh.quad(a, b, c, d)
    }

    /**
     * The lifted half of one strip on the floor or the ceiling: columns 0 to 128 of the page's geometry, bar
     * 0 at the outer edge and the flat column 128 by the middle. Odd strips lift the page's vertices 0 to 127
     * and even strips its vertices 255 down to 128, and the ceiling is the floor turned half a turn.
     */
    private fun addRidge(floor: Boolean, odd: Boolean, near: Float, far: Float, reach: Float) {
        val side = if (floor == odd) 1f else -1f
        val base = if (floor) floorY else ceilingY
        val up = if (floor) 1f else -1f
        val nearColour = colourAt(near, red, green, blue, light)
        val farColour = colourAt(far, red, green, blue, light)
        val first = mesh.vertexCount
        for (column in 0..NUM_BARS) {
            val x = side * columnX[column]
            val lift = if (column < NUM_BARS) visual[column] / HEIGHT_DIVISOR else 0f
            corner(2 * column, x, base + up * reach * lift, near, nearColour)
            corner(2 * column + 1, x, base + up * lift, far, farColour)
        }
        // The page's two triangles per column, (a, b, d) and (b, c, d), outer columns first.
        for (column in 0 until NUM_BARS) {
            val near0 = 2 * column
            val far0 = near0 + 1
            val near1 = near0 + 2
            val far1 = near0 + 3
            if (odd) {
                ridgeTriangle(first, far0, near0, far1, floor)
                ridgeTriangle(first, near0, near1, far1, floor)
            } else {
                ridgeTriangle(first, far1, near1, far0, floor)
                ridgeTriangle(first, near1, near0, far0, floor)
            }
        }
    }

    private fun corner(index: Int, x: Float, y: Float, depth: Float, colour: Int) {
        cornerX[index] = x
        cornerY[index] = y
        cornerDepth[index] = depth
        val sx = projectX(x, y, depth)
        val sy = projectY(x, y, depth)
        screenX[index] = sx
        screenY[index] = sy
        mesh.vertex(sx, sy, colour)
    }

    private fun ridgeTriangle(first: Int, a: Int, b: Int, c: Int, floor: Boolean) {
        val ax = screenX[a]
        val ay = screenY[a]
        val bx = screenX[b]
        val by = screenY[b]
        val cx = screenX[c]
        val cy = screenY[c]
        // The page's material is single sided: a triangle that turns clockwise on the page is its back.
        if ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax) >= 0f) return
        if (offScreen(ax, bx, cx, frameWidth) || offScreen(ay, by, cy, frameHeight)) return
        val outA = beyond(cornerY[a], floor)
        val outB = beyond(cornerY[b], floor)
        val outC = beyond(cornerY[c], floor)
        if (!outA && !outB && !outC) {
            mesh.triangle(first + a, first + b, first + c)
        } else if (!(outA && outB && outC)) {
            clip(first, a, b, c, floor)
        }
    }

    /** Whether a height lies past the opposite surface, where the page's depth test always hid it. */
    private fun beyond(y: Float, floor: Boolean): Boolean = if (floor) y > ceilingY else y < floorY

    /** Adds the part of a triangle on the near side of the opposite surface, as one or two triangles. */
    private fun clip(first: Int, a: Int, b: Int, c: Int, floor: Boolean) {
        val limit = if (floor) ceilingY else floorY
        clipCorners[0] = a
        clipCorners[1] = b
        clipCorners[2] = c
        var count = 0
        for (k in 0 until 3) {
            val p = clipCorners[k]
            val q = clipCorners[(k + 1) % 3]
            val pOut = beyond(cornerY[p], floor)
            if (!pOut) polygon[count++] = first + p
            if (pOut != beyond(cornerY[q], floor)) {
                val t = (limit - cornerY[p]) / (cornerY[q] - cornerY[p])
                val x = cornerX[p] + (cornerX[q] - cornerX[p]) * t
                val depth = cornerDepth[p] + (cornerDepth[q] - cornerDepth[p]) * t
                val added = mesh.vertex(projectX(x, limit, depth), projectY(x, limit, depth), colourAt(depth, red, green, blue, light))
                if (added < 0) return
                polygon[count++] = added
            }
        }
        for (k in 1 until count - 1) mesh.triangle(polygon[0], polygon[k], polygon[k + 1])
    }

    // The camera sits at the origin looking down -z, turned by the roll: the view is the world turned back.
    private fun projectX(x: Float, y: Float, depth: Float): Float = middleX + focal * (cosRoll * x + sinRoll * y) / depth

    private fun projectY(x: Float, y: Float, depth: Float): Float = middleY - focal * (cosRoll * y - sinRoll * x) / depth

    override fun reset() {
        step.reset()
        analyser.reset()
        visual.fill(0f)
        loudness = 0f
        lastLoudness = 0f
        squeeze = 0f
        travel = 0.0
        roll = 0.0
        red = 0f
        green = 0f
        blue = 1f
        owed = 0f
        started = false
    }

    internal companion object {
        const val FFT_SIZE = 4096
        const val PAGE_SAMPLE_RATE = 44_100

        /** `numBars`: bars per read, and the lifted columns of every strip. */
        const val NUM_BARS = 128

        /** `numBands`: strips on the floor, and the same strips again on the ceiling. */
        const val NUM_BANDS = 64
        const val SPECTRUM_START = -200
        const val SPECTRUM_END = 1300

        /** `barLen`: half of a strip's width. */
        const val BAR_LEN = 150.0
        private const val BAR_LEN_F = 150f

        /** `(numBars * 2) - 1` segments: 256 columns across a strip. */
        const val COLUMNS = 256

        /** `barGap`: the distance between two strips, and the depth of each. */
        const val BAR_GAP = 10.0
        private const val BAR_GAP_UNITS = 10
        const val STRIP_DEPTH = 10f

        /** The near and the far edge of slot 0, behind the camera: the first strip starts at z = 10. */
        private const val NEAR_EDGE = 15
        private const val FAR_EDGE = 5

        /** `numBands * barGap`: a strip that passes 20 units behind the camera goes back 640. */
        const val CORRIDOR = 640.0

        /** `plane.position.y` and the rest height of `group.position.y`. */
        const val PLANE_Y = -10f
        const val GROUP_Y = 10f

        /** `min(loudness / 255 * 20, 9.8)`: how far the floor and the ceiling close in. */
        const val SQUEEZE_SCALE = 20f
        const val SQUEEZE_MAX = 9.8f

        /** `visualArray[i] / 10`: a bar of 255 lifts its column 25.5 units. */
        const val HEIGHT_DIVISOR = 10f

        /** The fragment shader's `-pos.z / 500.0`: a strip is black at the camera and full colour 500 units away. */
        const val BRIGHTNESS_DEPTH = 500f

        /** `1 / tan(35 degrees)` for the page's vertical field of view of 70 degrees. */
        val FOCAL: Float = (1.0 / tan(35.0 * PI / 180.0)).toFloat()

        /**
         * The page clips at 0.01 units. Clipping at 0.1 keeps every corner inside what a raster can hold. What lies
         * between the two is off the frame, except a ridge seen edge on at eye level for a frame.
         */
        const val NEAR_CLIP = 0.1f

        /** The page draws at most 60 frames a second, and every constant is per frame. */
        const val PAGE_RATE = 60f
        const val READ_PERIOD = 1f / PAGE_RATE
        private const val MAX_OWED = 6f * READ_PERIOD

        /** The analyser's smoothing of 0.8 per read, as a time constant: about 75 ms. */
        val ANALYSER_SECONDS: Float = (-1.0 / (PAGE_RATE * ln(0.8))).toFloat()

        private const val FULL_TURN = 2.0 * PI
        private const val RIDGE_CORNERS = (NUM_BARS + 1) * 2

        // A strip pair is two ridges, and a cut can add two corners and one triangle to any triangle.
        private const val PAIR_VERTICES = 2 * RIDGE_CORNERS + 4 * 2 * NUM_BARS
        private const val PAIR_INDICES = 2 * 2 * NUM_BARS * 2 * 3
        private const val MESH_VERTICES = 32_000
        private const val MESH_INDICES = 131_072

        /** `(loudness / 8192 + 1)^2 - 1`, the page's rate for the roll and the flight. Zero at a loudness of 1 or less. */
        fun rate(loudness: Float): Double = if (loudness <= 1f) 0.0 else (loudness / 8192.0 + 1.0).pow(2) - 1.0

        /** Radians the camera turns per page frame. */
        fun rollStep(loudness: Float): Double = rate(loudness) / 2.0

        /** Units every strip flies toward the camera per page frame: `rate * loudness * 1.7`. */
        fun flightStep(loudness: Float): Double = rate(loudness) * loudness * 1.7

        fun squeezeFor(loudness: Float): Float = if (loudness <= 1f) 0f else minOf(loudness / 255f * SQUEEZE_SCALE, SQUEEZE_MAX)

        /** `modn(250 - loudness * 2.2, 360)`, in degrees. */
        fun hueFor(loudness: Float): Double = euclideanModulo(250.0 - loudness * 2.2, 360.0)

        /** A corner's colour at [depth]: `-viewZ / 500` times the strip colour, clamped as the frame buffer does, times [light]. */
        fun colourAt(depth: Float, red: Float, green: Float, blue: Float, light: Float): Int {
            val shade = depth / BRIGHTNESS_DEPTH
            return (0xFF shl 24) or (channel(shade * red, light) shl 16) or
                (channel(shade * green, light) shl 8) or channel(shade * blue, light)
        }

        private fun channel(value: Float, light: Float): Int = (value.coerceIn(0f, 1f) * light * 255f + 0.5f).toInt()

        /** three.js `Math.euclideanModulo`, and the page's `modn`. */
        fun euclideanModulo(n: Double, m: Double): Double = ((n % m) + m) % m

        /** three.js `hue2rgb` inside `Color.setHSL`. */
        fun hueToRgb(p: Double, q: Double, hue: Double): Double {
            var t = hue
            if (t < 0.0) t += 1.0
            if (t > 1.0) t -= 1.0
            if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t
            if (t < 1.0 / 2.0) return q
            if (t < 2.0 / 3.0) return p + (q - p) * 6.0 * (2.0 / 3.0 - t)
            return p
        }

        private fun offScreen(a: Float, b: Float, c: Float, extent: Float): Boolean =
            (a < 0f && b < 0f && c < 0f) || (a > extent && b > extent && c > extent)

        private fun offScreen(a: Float, b: Float, c: Float, d: Float, extent: Float): Boolean =
            (a < 0f && b < 0f && c < 0f && d < 0f) || (a > extent && b > extent && c > extent && d > extent)
    }
}
