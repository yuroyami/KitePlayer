package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Fracture's glass dust: Silk of Vissonance by Tariq Soliman, which Fracture absorbed.
 *
 * One hexagon per band of 512, scaled by its byte through a log law, climbing thirty units and snapping
 * back to the centre line, mirrored four ways. It reads the same page analyser as the corridor.
 */
internal class FractureDust {

    /** `spectrum.GetVisualBins(dataArray, 512, 6, 1300)`. */
    val bins = VissonanceBins(NUM_BARS, SPECTRUM_START, SPECTRUM_END)

    /** `visualArray` from the last read, 1 to 255 per band. */
    val visual = FloatArray(NUM_BARS)

    /** Each band's hexagon scale, which is also its climb per page frame. */
    val scale = FloatArray(NUM_BARS)

    /** How far each band's hexagon has climbed from the centre line, in world units. */
    val climb = FloatArray(NUM_BARS)

    private val red = FloatArray(NUM_BARS)
    private val green = FloatArray(NUM_BARS)
    private val blue = FloatArray(NUM_BARS)

    /** The smoothed loudness of the last read. At 1 or below, every hexagon sits on the centre line. */
    var loudness = 0f
        private set
    private var lastLoudness = 0f

    /** The bands, the scales and the colours from one row of `getByteFrequencyData`, and [count] loudness steps. */
    fun listen(bytes: IntArray, count: Int = 1) {
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
    }

    /** Every hexagon climbs by its scale per page frame, for [frames] page frames, and snaps back past 30 units. */
    fun climbBy(frames: Float) {
        for (i in 0 until NUM_BARS) {
            var height = climb[i] + scale[i] * frames
            if (height > MAX_CLIMB || loudness <= 1f) height = 0f
            climb[i] = height
        }
    }

    /**
     * Adds every hexagon above the floor to [mesh], mirrored into the four quarters of a [width] by [height]
     * frame, at [alpha] and mixed toward white by [glint].
     */
    fun addTo(mesh: TriangleMesh, width: Float, height: Float, light: Float, alpha: Float, glint: Float) {
        val unit = pixelsPerUnit(height)
        val middleX = width / 2f
        val middleY = height / 2f
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        for (i in 0 until NUM_BARS) {
            if (visual[i] <= 1f) continue
            val radius = RADIUS * scale[i] * unit
            val x = BAR_X[i] * unit
            if (x - radius > middleX) continue
            if (mesh.vertexCount + GROUPS * SIDES > mesh.maxVertices) return
            val y = climb[i] * unit
            val argb = (a shl 24) or (channel(red[i], glint, light) shl 16) or
                (channel(green[i], glint, light) shl 8) or channel(blue[i], glint, light)
            hexagon(mesh, middleX + x, middleY - y, radius, argb)
            hexagon(mesh, middleX - x, middleY + y, radius, argb)
            hexagon(mesh, middleX - x, middleY - y, radius, argb)
            hexagon(mesh, middleX + x, middleY + y, radius, argb)
        }
    }

    /** The centres of the four copies of band [band]'s hexagon on a [width] by [height] frame, as x and y pairs. */
    fun centresOf(band: Int, width: Float, height: Float): List<Pair<Float, Float>> {
        val unit = pixelsPerUnit(height)
        val x = BAR_X[band] * unit
        val y = climb[band] * unit
        val mx = width / 2f
        val my = height / 2f
        return listOf(mx + x to my - y, mx - x to my + y, mx - x to my - y, mx + x to my + y)
    }

    /** Band [band]'s colour at full light, packed as opaque ARGB, for tests. */
    fun packed(band: Int): Int = (0xFF shl 24) or (channel(red[band], 0f, 1f) shl 16) or
        (channel(green[band], 0f, 1f) shl 8) or channel(blue[band], 0f, 1f)

    /** `setUniformColor`, through `THREE.Color('hsl(h, 90%, l%)')` and `setHSL` of three.js r84. */
    private fun setColour(band: Int, value: Float) {
        val h = Fracture.euclideanModulo(250.0 - value * 0.9, 360.0) / 360.0
        // `parseInt` of the value, and the CSS parser's whole percentages.
        val l = (100 - min(40, floor(value.toDouble()).toInt())) / 100.0
        val s = SATURATION
        val p = if (l <= 0.5) l * (1.0 + s) else l + s - l * s
        val q = 2.0 * l - p
        red[band] = Fracture.hueToRgb(q, p, h + 1.0 / 3.0).toFloat()
        green[band] = Fracture.hueToRgb(q, p, h).toFloat()
        blue[band] = Fracture.hueToRgb(q, p, h - 1.0 / 3.0).toFloat()
    }

    /** A hexagon of `CircleBufferGeometry(10, 6)`: a corner on each side along x, flat top and bottom. */
    private fun hexagon(mesh: TriangleMesh, x: Float, y: Float, radius: Float, argb: Int) {
        val first = mesh.vertex(x + radius, y, argb)
        if (first < 0) return
        for (corner in 1 until SIDES) mesh.vertex(x + CORNER_X[corner] * radius, y + CORNER_Y[corner] * radius, argb)
        for (corner in 1 until SIDES - 1) mesh.triangle(first, first + corner, first + corner + 1)
    }

    fun reset() {
        visual.fill(0f)
        scale.fill(0f)
        climb.fill(0f)
        red.fill(0f)
        green.fill(0f)
        blue.fill(0f)
        loudness = 0f
        lastLoudness = 0f
    }

    companion object {
        /** `numBars`, and the range of FFT bins handed to `GetVisualBins`. */
        const val NUM_BARS = 512
        const val SPECTRUM_START = 6
        const val SPECTRUM_END = 1300

        /** `CircleBufferGeometry( 10, 6 )`: the radius at scale 1, in world units. */
        const val RADIUS = 10f
        const val SIDES = 6

        /** The page's four groups: as made, turned half a turn twice, and as made again. */
        const val GROUPS = 4

        /** A hexagon snaps back to the centre line once it has climbed past this many units. */
        const val MAX_CLIMB = 30f

        private const val SATURATION = 0.9

        /** The hexagons sit 50 units in front of the camera, under a vertical field of view of 70 degrees. */
        const val DEPTH = 50.0
        const val FOV_DEGREES = 70.0
        val VIEW_HEIGHT: Float = (2.0 * DEPTH * tan(FOV_DEGREES / 2.0 * PI / 180.0)).toFloat()

        /** Each band's x, `posX` from 3 in steps of `barGap = 0.12`, added up as the page did. */
        val BAR_X: FloatArray = run {
            val x = FloatArray(NUM_BARS)
            var posX = 3.0
            for (i in 0 until NUM_BARS) {
                x[i] = posX.toFloat()
                posX += 0.12
            }
            x
        }

        private val CORNER_X = FloatArray(SIDES) { cos(it * PI / 3.0).toFloat() }
        private val CORNER_Y = FloatArray(SIDES) { sin(it * PI / 3.0).toFloat() }

        /** Pixels per world unit at the hexagons' depth, on a canvas [height] pixels high. */
        fun pixelsPerUnit(height: Float): Float = height / VIEW_HEIGHT

        /** The hexagon scale and the climb per page frame for a band at [value]: `4 log10(1 + v / 255 / 7)`. */
        fun scaleOf(value: Float): Float =
            if (value <= 1f) FLOOR_SCALE else (4.0 * log10(1.0 + value / 255.0 / 7.0)).toFloat()

        private const val FLOOR_SCALE = 0.0001f

        private fun channel(value: Float, glint: Float, light: Float): Int {
            val lit = (value + (1f - value) * glint.coerceIn(0f, 1f)) * light
            return (lit.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
    }
}
