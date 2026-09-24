package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
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
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lines, by Silvio Paganini (FLUUUID), ported with credit.
 *
 * - Title: Lines
 * - Author: Silvio Paganini (@silviopaganini), for the FLUUUID collective
 * - URL: https://labs.fluuu.id/lines/
 * - Repository: https://github.com/fluuuid/labs, folder `lines/`
 * - Licence as found: ISC declared in package.json, no license file
 * - Year: 2015
 * - The page names the cover art of Joy Division's Unknown Pleasures as its inspiration.
 *
 * Deviations from the original:
 * - The orbit controls are dropped. The camera keeps the author's default: (0, 45, 240), looking at the origin.
 * - [WebAudioAnalyser] rebuilds the page's analyser from the player's spectrum, with the desktop settings:
 *   an FFT of 2048, smoothing 0.8, -100 to -30 dB. The phone setting of the page (an FFT of 256) is not ported.
 * - The analyser read, the noise re-roll and the easing run 60 times per second of heard music, not once
 *   per browser frame. The lines hold still while the music is paused or silent.
 * - The easing of 0.04 per page frame is scaled by frame time, so a 120 Hz screen moves as fast as a 60 Hz one.
 * - The fog is set once per line, at the floor height of 5, not per pixel. A peak is up to 3 percent of
 *   full white darker than on the page.
 * - Each line is 1 device pixel wide. The page asks for 3, but WebGL draws 1 in the common browsers.
 * - The world width is half the longer side of the canvas in device pixels, and the page measured its window
 *   in CSS pixels. The two are the same on a desktop screen with one device pixel per CSS pixel, so a 1080p
 *   phone frames the lines as a 1080p desktop did, and a 160 dp preview tile still shows the whole stack.
 *   The page measured its window once. This drawing measures the canvas every frame.
 * - A seeded generator gives the noise instead of `Math.random()`, so a render repeats.
 * - The flash guard's light scale multiplies the brightness. It is 1 unless the picture would flash.
 *
 * Twenty-two thin white lines stand front to back in black fog, seen from a slightly raised camera.
 * Each line wobbles flat at its ends and rises into mirrored peaks at its centre. The middle lines of
 * the stack read the bass, and the front and back lines read the treble. A frequency bin lifts its line
 * only above byte 100 of 255, so a line is either on its floor or in a peak. Nothing is filled, so a
 * tall ridge in front shows the lines behind it.
 */
internal class Lines : Visualization {

    override val name: String = "Lines"
    override val bucket: VizEnergy = VizEnergy.Calm
    override val post: PostSpec = PostSpec.Off

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // The level is a share of the analyser's byte range: above byte 100 a bin raises a peak.
            VizDrive(VizDriver.Bands, VizProperty.Shape, VizCurve.Threshold(KNEE / 255f), VizResponse.envelope(EASE_SECONDS)),
        ),
        silence = VizSilence.Still,
    )

    /** The page's analyser: `fftSize = 2048` on the desktop, and the Web Audio defaults for the rest. */
    internal val analyser = WebAudioAnalyser(fftSize = 2048, smoothing = 0.8f, minDecibels = -100f, maxDecibels = -30f)

    private val step = DisplayStep()
    private val pageFrames = PageFrames()
    private val noise = PerlinNoise(NOISE_POINTS)
    private var random = Random(SEED)

    /** The 386 heights of `generatedPoints`: noise, the band from its highest bin down, then all of it mirrored. */
    private val heights = FloatArray(CONTROL_POINTS)

    /** Where each line's 512 vertices are heading and where they are, as heights above the line. */
    private val targets = Array(LINES) { FloatArray(POINTS) }
    private val vertices = Array(LINES) { FloatArray(POINTS) }
    private var ready = false

    // THREE.SplineCurve3.getPoint at t = d / 511: the four control points vertex d reads, and its weight.
    private val point0 = IntArray(POINTS)
    private val point1 = IntArray(POINTS)
    private val point2 = IntArray(POINTS)
    private val point3 = IntArray(POINTS)
    private val weight = FloatArray(POINTS)

    private val pointX = FloatArray(POINTS)
    private var sizeX = Float.NaN

    // Line.js: `mesh.position.y = -20 + y` with `y = -SIZE.y / 2 + i`, and `mesh.position.z = (16 - index) * -20`.
    private val lineY = FloatArray(LINES) { -20f + (-SIZE_Y / 2f + it) }
    private val lineZ = FloatArray(LINES) { (16 - it) * -20f }
    private val visibility = FloatArray(LINES) { fogVisibility(lineY[it] + FLOOR, lineZ[it]) }

    private val scene = Scene3D()
    private val paths = Array(LINES) { Path() }
    private val hairline = Stroke(width = Stroke.HairlineWidth)

    init {
        val last = CONTROL_POINTS - 1
        for (d in 0 until POINTS) {
            val point = last * (d.toDouble() / (POINTS - 1))
            val intPoint = floor(point).toInt()
            weight[d] = (point - intPoint).toFloat()
            point0[d] = if (intPoint == 0) intPoint else intPoint - 1
            point1[d] = intPoint
            point2[d] = if (intPoint > CONTROL_POINTS - 2) CONTROL_POINTS - 1 else intPoint + 1
            point3[d] = if (intPoint > CONTROL_POINTS - 3) CONTROL_POINTS - 1 else intPoint + 2
        }
    }

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state)
        // The page clears to black, and its fog fades to black.
        drawRect(Color.Black)
    }

    override fun DrawScope.drawFront(state: VizRenderState) {
        advance(state)
        if (!ready) return
        // SIZE.x: half the longer side of the window, with a device pixel for each CSS pixel.
        placePoints(max(size.width, size.height) / 2f)
        scene.lens(size, FOV, NEAR, FAR)
        scene.camera(0f, EYE_Y, EYE_Z, 0f, 0f, 0f)
        val light = state.lightScale.coerceIn(0f, 1f)
        // Back to front, so the nearer line wins where two cross, as the depth test made it.
        for (index in 0 until LINES) {
            val path = paths[index]
            path.reset()
            val vertex = vertices[index]
            var open = false
            for (d in 0 until POINTS) {
                if (!scene.project(pointX[d], lineY[index] + vertex[d], lineZ[index])) {
                    open = false
                    continue
                }
                if (open) path.lineTo(scene.screenX, scene.screenY) else path.moveTo(scene.screenX, scene.screenY)
                open = true
            }
            val grey = visibility[index] * light
            drawPath(path, Color(grey, grey, grey), style = hairline)
        }
    }

    private fun advance(state: VizRenderState) {
        val dt = step.of(state) ?: return
        if (!ready) {
            // The Line constructor: the vertices start on the first target.
            analyser.update(state.frame)
            for (index in 0 until LINES) {
                generatePoints(index, analyser.frequencyBytes, targets[index])
                targets[index].copyInto(vertices[index])
            }
            ready = true
            return
        }
        // The page's frame loop runs on heard seconds only, so a pause or a silence holds every line.
        val heard = dt * state.frame.audible
        val frames = pageFrames.advance(heard)
        var easing = heard
        for (pageFrame in 1..frames) {
            // A screen frame carries one analysis, so the analyser smooths once however many page frames it covers.
            if (pageFrame == 1) analyser.update(state.frame)
            for (index in 0 until LINES) generatePoints(index, analyser.frequencyBytes, targets[index])
            if (pageFrame < frames) {
                ease(PAGE_FRAME)
                easing -= PAGE_FRAME
            }
        }
        ease(easing)
    }

    /** Line.js `generatedPoints` for line [index]: 386 heights resampled to 512 by a Catmull-Rom spline. */
    private fun generatePoints(index: Int, bins: IntArray, out: FloatArray) {
        noise.generate(random, heights)
        for (i in 0 until NOISE_POINTS) heights[i] *= 10f
        val start = bandStart(index)
        var at = NOISE_POINTS
        for (i in RANGE + start downTo start) heights[at++] = heightOf(bins[i])
        for (i in 0 until HALF) heights[HALF + i] = heights[HALF - 1 - i]
        for (d in 0 until POINTS) {
            out[d] = catmullRom(heights[point0[d]], heights[point1[d]], heights[point2[d]], heights[point3[d]], weight[d])
        }
    }

    /** Line.js `update`: every vertex moves 4 percent of the way to its target per page frame. */
    private fun ease(seconds: Float) {
        if (seconds <= 0f) return
        val share = 1f - (1f - EASE).pow(seconds * 60f)
        for (index in 0 until LINES) {
            val target = targets[index]
            val vertex = vertices[index]
            for (d in 0 until POINTS) vertex[d] += (target[d] - vertex[d]) * share
        }
    }

    /** The x of every vertex for a line [width] world units wide, which only changes with the canvas. */
    private fun placePoints(width: Float) {
        if (width == sizeX) return
        sizeX = width
        val ratio = width / CONTROL_POINTS
        for (d in 0 until POINTS) {
            pointX[d] = catmullRom(
                -width / 2f + ratio * point0[d], -width / 2f + ratio * point1[d],
                -width / 2f + ratio * point2[d], -width / 2f + ratio * point3[d], weight[d],
            )
        }
    }

    override fun reset() {
        step.reset()
        pageFrames.reset()
        analyser.reset()
        random = Random(SEED)
        ready = false
    }

    /**
     * Counts the page's frames, 60 to a second of the time it is given. It rounds to the nearest frame,
     * so a 60 Hz screen whose frame times jitter still gets exactly one page frame per screen frame.
     */
    internal class PageFrames {
        private var owed = 0f

        fun advance(seconds: Float): Int {
            owed += seconds
            var frames = 0
            while (owed >= PAGE_FRAME / 2f) {
                owed -= PAGE_FRAME
                frames++
            }
            return frames
        }

        fun reset() {
            owed = 0f
        }
    }

    /**
     * The npm package perlin-noise 0.0.1, `generatePerlinNoise(1, height)` with its defaults: four octaves,
     * amplitude 0.1, persistence 0.2. It draws fresh white noise on every call, as the page did.
     */
    private class PerlinNoise(private val height: Int) {
        private val whiteNoise = FloatArray(height)
        private val smoothNoiseList = Array(OCTAVE_COUNT) { FloatArray(height) }

        /** Writes the noise, 0 to 1, into the first [height] entries of [out]. */
        fun generate(random: Random, out: FloatArray) {
            for (i in 0 until height) whiteNoise[i] = random.nextFloat()
            for (octave in 0 until OCTAVE_COUNT) generateSmoothNoise(octave, smoothNoiseList[octave])
            var amplitude = AMPLITUDE
            var totalAmplitude = 0f
            out.fill(0f, 0, height)
            for (i in OCTAVE_COUNT - 1 downTo 0) {
                amplitude *= PERSISTENCE
                totalAmplitude += amplitude
                val smooth = smoothNoiseList[i]
                for (j in 0 until height) out[j] += smooth[j] * amplitude
            }
            for (i in 0 until height) out[i] /= totalAmplitude
        }

        private fun generateSmoothNoise(octave: Int, noise: FloatArray) {
            val samplePeriod = 1 shl octave
            val sampleFrequency = 1f / samplePeriod
            for (y in 0 until height) {
                val sampleY0 = y / samplePeriod * samplePeriod
                val sampleY1 = (sampleY0 + samplePeriod) % height
                val vertBlend = (y - sampleY0) * sampleFrequency
                // One column wide, so both x samples are column 0 and the horizontal blend changes nothing.
                noise[y] = interpolate(whiteNoise[sampleY0], whiteNoise[sampleY1], vertBlend)
            }
        }

        private fun interpolate(x0: Float, x1: Float, alpha: Float): Float = x0 * (1f - alpha) + alpha * x1
    }

    internal companion object {
        const val LINES = 22

        /** `curve.getPoints(511)`. */
        const val POINTS = 512

        /** `perlin.generatePerlinNoise(1, 128)`. */
        const val NOISE_POINTS = 128

        /** `512 / 8`: the step between two lines' bands. Each line reads 65 bins, this step plus one. */
        const val RANGE = 64

        /** The noise and the band, before the mirror doubles them. */
        const val HALF = NOISE_POINTS + RANGE + 1
        const val CONTROL_POINTS = HALF * 2

        /** The byte above which a bin raises its line: `bin / 5 > 20`. */
        const val KNEE = 100

        const val SIZE_Y = 30f
        const val EYE_Y = 45f
        const val EYE_Z = 240f
        const val FOV = 45f
        const val NEAR = 0.01f
        const val FAR = 4000f
        const val FOG_DENSITY = 0.00255f

        /** three.js's own constant in the fog chunk. */
        const val LOG2 = 1.442695f

        /** The height the fog is worked out at: the floor that `max(5, a)` holds every quiet bin on. */
        const val FLOOR = 5f

        /** The share of the way to its target a vertex moves per page frame. */
        const val EASE = 0.04f
        const val PAGE_FRAME = 1f / 60f

        const val OCTAVE_COUNT = 4
        const val AMPLITUDE = 0.1f
        const val PERSISTENCE = 0.2f
        const val SEED = 20_150_617L

        /** The easing's time constant: a vertex covers 63 percent of a step in about 0.41 seconds. */
        val EASE_SECONDS: Float = (-1.0 / (60.0 * ln(1.0 - EASE))).toFloat()

        /** Line.js: `a = bin / 5; a = a > 20 ? a * 1.5 : a / 50; a = max(5, a)`. A height is 5, or 30.3 to 76.5. */
        fun heightOf(byte: Int): Float {
            var a = byte / 5f
            a = if (a > 20f) a * 1.5f else a / 50f
            return max(5f, a)
        }

        /** The lowest of the 65 bins line [index] reads. The middle of the stack reads the bass. */
        fun bandStart(index: Int): Int {
            val order = if (index < 11) 10 - index else index
            return RANGE * (order % 11)
        }

        /** THREE.Curve.Utils.interpolate in three.js r71: one uniform Catmull-Rom step. */
        fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val v0 = 0.5f * (p2 - p0)
            val v1 = 0.5f * (p3 - p1)
            val t2 = t * t
            return (2f * p1 - 2f * p2 + v0 + v1) * (t * t2) + (-3f * p1 + 3f * p2 - 2f * v0 - v1) * t2 + v0 * t + p1
        }

        /**
         * The share of a white line that three.js's FogExp2 leaves at height [y] and depth [z], over black.
         * The fog reads `gl_FragCoord.z / gl_FragCoord.w`: the distance along the view axis less the near
         * plane, times far over (far - near).
         */
        fun fogVisibility(y: Float, z: Float): Float {
            // The camera looks from (0, EYE_Y, EYE_Z) at the origin.
            val length = sqrt(EYE_Y * EYE_Y + EYE_Z * EYE_Z)
            val along = ((y - EYE_Y) * -EYE_Y + (z - EYE_Z) * -EYE_Z) / length
            val depth = FAR / (FAR - NEAR) * (along - NEAR)
            val fogFactor = 1f - 2f.pow(-FOG_DENSITY * FOG_DENSITY * depth * depth * LOG2).coerceIn(0f, 1f)
            return 1f - fogFactor
        }
    }
}
