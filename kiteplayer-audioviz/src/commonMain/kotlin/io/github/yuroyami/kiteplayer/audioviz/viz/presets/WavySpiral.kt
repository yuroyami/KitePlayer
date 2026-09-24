package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Wavy Spiral, a port of "Audible Visuals" by Sonia Boller: its Wavy Spiral layout, the default, and
 * its Flower layout.
 *
 * - Title: Audible Visuals (the Wavy Spiral and Flower visualizers of its spiral page)
 * - Author: Sonia Boller (GitHub: soniaboller)
 * - URL: https://soniaboller.github.io/audible-visuals/
 * - Repository: https://github.com/soniaboller/soniaboller.github.io, folder `audible-visuals/`
 *   (`scripts/spiral.js`), which the older repository's README names as the main one. The older copy
 *   is https://github.com/soniaboller/audible-visuals (`public/scripts/spiral.js`).
 * - Licence as found: the Apache License 2.0 text in `soniaboller/audible-visuals` (`LICENSE`), with
 *   the copyright line left as the unfilled template, "Copyright [yyyy] [name of copyright owner]". No
 *   licence file was found in `soniaboller/soniaboller.github.io`.
 * - Year: 2016
 *
 * Deviations from the original:
 * - Two of the page's four layouts are ported, Wavy Spiral and Flower, as the Layout setting. The
 *   Spiral and Circle layouts, the keys and the settings panel are left out, and the page's defaults
 *   are kept: red emphasis, intensity 0.18, the 35 degree view and the drift on.
 * - The waveform is the player's 512-sample stereo trace (about 11 ms at 48 kHz), started at a rising
 *   zero crossing, mixed to mono as the browser mixes it and stretched over the page's 2048 samples by
 *   [WebAudioAnalyser]. The page read 2048 samples (about 46 ms), so colour and depth change about four
 *   times more slowly along the spiral.
 * - The waveform is read once per sixtieth of a second, the page's frame rate, on any screen.
 * - The angle drifts by one page frame's step per sixtieth of a second of heard music, not per drawn
 *   frame, so it keeps the page's speed on any screen and stands still while the music is paused or
 *   silent. A reduced-motion setting slows it.
 * - On a portrait frame the 35 degree view spans the width instead of the height, so the spiral is
 *   scaled down to fit rather than cropped at the sides.
 * - The flash guard's light share multiplies every colour. It is 1 unless the picture would flash.
 *
 * About a thousand small dots lie on a spiral that faces the viewer. Each dot is pushed one unit off
 * the spiral in a direction that jumps from dot to dot, so the rings look frayed, and the slowly
 * drifting angle makes neighbouring turns alias into arms and petals that re-form over tens of
 * seconds. The music speckles the dots sample by sample, from their resting purple towards red or
 * azure, and pushes them toward or away from the viewer. The Flower layout puts the dots on a circle
 * with a second circle turning around it, which aliases into petals.
 */
internal class WavySpiral : Visualization {

    override val name: String = "Wavy Spiral"
    override val bucket: VizEnergy = VizEnergy.Mid
    override val post: PostSpec = PostSpec.Off
    override val paintsWholeScreen: Boolean = true

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // Each dot's colour is (0.7 + s, 0 - s, 0.7 - s) for its own sample s.
            VizDrive(VizDriver.Waveform, VizProperty.Colour),
            // Each dot moves toward or away from the camera by s * byte * 0.18.
            VizDrive(VizDriver.Waveform, VizProperty.Shape),
        ),
        silence = VizSilence.Still,
    )

    /** The page's layout: Wavy Spiral, its default, or Flower. */
    internal val layout = VizParam("Layout", 0f, 1f, 0f).apply {
        step = 1f
        choices = listOf("Wavy Spiral", "Flower")
    }
    override val params: List<VizParam> = listOf(layout)

    /** The page's AnalyserNode, left at the browser's default fftSize of 2048. */
    private val analyser = WebAudioAnalyser()

    /** `getFloatTimeDomainData`: 2048 samples between -1 and 1. */
    internal val timeFloatData = FloatArray(analyser.fftSize)

    /** `getByteTimeDomainData` of the same samples: 0 to 255, with 128 in silence. */
    internal val timeFrequencyData = IntArray(analyser.fftSize) { SILENT_BYTE }

    private val left = FloatArray(analyser.fftSize)
    private val right = FloatArray(analyser.fftSize)

    /** `spiral.wavyAngle`, with `app.wavySpiralCounter` as the direction. */
    internal var wavyAngle: Double = WAVY_ANGLE
    private var wavyRising = true

    /** `spiral.flowerAngle`, with `app.flowerCounter`, which starts out falling. */
    internal var flowerAngle: Double = FLOWER_ANGLE
    private var flowerRising = false

    // The page places the dots and then drifts the angle, so a frame's dots use the angle from before its drift.
    private var placedWavyAngle = WAVY_ANGLE
    private var placedFlowerAngle = FLOWER_ANGLE

    /** How many times the waveform was read since the last reset. */
    internal var reads: Long = 0L
        private set

    private val step = DisplayStep()
    private var readClock = READ_PERIOD

    // Each dot this frame: where it lands in pixels, its radius, and its colour as packed RGB.
    internal val dotX = FloatArray(DOTS)
    internal val dotY = FloatArray(DOTS)
    internal val dotRadius = FloatArray(DOTS)
    internal val dotRgb = IntArray(DOTS)

    /** The dots drawn this frame, farthest first, in the order the canvas renderer sorts them. */
    internal val drawOrder = IntArray(DOTS)

    /** How many entries of [drawOrder] are in use. */
    internal var drawn: Int = 0
        private set

    private val sortKeys = LongArray(DOTS)

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state)
        // renderer.setClearColor(0x000000, 1): the canvas is cleared to black every frame.
        drawRect(Color.Black)
    }

    override fun DrawScope.drawFront(state: VizRenderState) {
        advance(state)
        arrange(size.width, size.height)
        val light = state.lightScale.coerceIn(0f, 1f)
        for (index in 0 until drawn) {
            val dot = drawOrder[index]
            val rgb = dotRgb[dot]
            drawCircle(
                Color(
                    (rgb shr 16 and 0xFF) / 255f * light,
                    (rgb shr 8 and 0xFF) / 255f * light,
                    (rgb and 0xFF) / 255f * light,
                ),
                dotRadius[dot],
                Offset(dotX[dot], dotY[dot]),
            )
        }
    }

    private fun advance(state: VizRenderState) {
        val dt = step.of(state) ?: return
        // The page read the analyser once per 60 Hz frame. The rows carry no smoothing, so the rate
        // only sets how often the dots change; a paused player's frame reads the same every time.
        readClock = minOf(readClock + dt, 2f * READ_PERIOD)
        if (readClock >= READ_PERIOD * 0.999f) {
            readClock -= READ_PERIOD
            read(state.frame)
        }
        placedWavyAngle = wavyAngle
        placedFlowerAngle = flowerAngle
        // checkVisualizer: one page frame's drift per sixtieth of a second of heard music.
        drift(dt.toDouble() * state.frame.audible * 60.0 * state.motionScale)
    }

    /** Takes the page's two time-domain rows from [frame]. */
    internal fun read(frame: SpectrumFrame) {
        reads++
        analyser.timeDomainFloats(frame, left, channel = 1)
        analyser.timeDomainFloats(frame, right, channel = 2)
        for (index in timeFloatData.indices) {
            // The browser mixes a stereo input to mono, (L + R) / 2, before the analyser keeps it.
            val sample = (left[index] + right[index]) * 0.5f
            timeFloatData[index] = sample
            timeFrequencyData[index] = timeDomainByte(sample)
        }
    }

    /**
     * The page's draw loop: every dot's place, size and colour from the current rows, projected by
     * three.js's camera and sorted farthest first.
     */
    internal fun arrange(width: Float, height: Float) {
        drawn = 0
        if (width <= 0f || height <= 0f) return
        val flower = layout.value >= 0.5f
        val angle = if (flower) placedFlowerAngle else placedWavyAngle
        val middleX = width * 0.5
        val middleY = height * 0.5
        // The camera sits on the z axis looking at the origin, so projecting is a divide by the view
        // depth w = 175 - z. One world unit at depth w spans FOCAL * halfHeight / w pixels.
        val pixels = FOCAL * minOf(width, height) * 0.5
        for (dot in 0 until DOTS) {
            val j = sampleIndex(dot)
            // Index 2049 is past the 2048-sample row. The page's sample there is undefined, so z is
            // NaN and the projector drops the dot.
            if (j >= timeFloatData.size) continue
            val x: Double
            val y: Double
            if (flower) {
                // "Archimedean Wavy Spiral with opposite sin and cos to generate crossover in flower pattern"
                x = (A_FLOWER + B_FLOWER * ((angle / 100) * j)) * cos((angle / 100) * j) +
                    sin(j / (angle / 100)) * FLOWER_OFFSET
                y = (A_FLOWER + B_FLOWER * ((angle / 100) * j)) * sin((angle / 100) * j) +
                    cos(j / (angle / 100)) * FLOWER_OFFSET
            } else {
                // "Archimedean Spiral with sin and cos added respectively to position to create a wavy spiral"
                x = (A_WAVY + B_WAVY * ((angle / 100) * j)) * sin((angle / 100) * j) + sin(j / (angle / 100))
                y = (A_WAVY + B_WAVY * ((angle / 100) * j)) * cos((angle / 100) * j) + cos(j / (angle / 100))
            }
            val sample = timeFloatData[j].toDouble()
            val z = sample * timeFrequencyData[j] * INTENSITY
            val w = CAMERA_DISTANCE - z
            // The projector keeps a sprite only between the near and far planes.
            if (!(w >= NEAR && w <= FAR)) continue
            val perUnit = pixels / w
            dotX[dot] = (middleX + x * perUnit).toFloat()
            dotY[dot] = (middleY - y * perUnit).toFloat()
            dotRadius[dot] = (DOT_RADIUS * perUnit).toFloat()
            dotRgb[dot] = colourOf(sample)
            // painterSort: the larger depth first, and creation order between equal depths.
            sortKeys[drawn++] = ((Int.MAX_VALUE - w.toFloat().toRawBits()).toLong() shl 11) or dot.toLong()
        }
        sortKeys.sort(0, drawn)
        for (index in 0 until drawn) drawOrder[index] = (sortKeys[index] and 0x7FF).toInt()
    }

    /** checkVisualizer for the active layout, [frames] page frames long. */
    internal fun drift(frames: Double) {
        if (frames <= 0.0) return
        if (layout.value >= 0.5f) {
            // changeFlowerAngle
            if (flowerRising) {
                flowerAngle += FLOWER_STEP * frames
                if (flowerAngle >= FLOWER_HIGH) flowerRising = false
            } else {
                flowerAngle -= FLOWER_STEP * frames
                if (flowerAngle <= FLOWER_LOW) flowerRising = true
            }
        } else {
            // changeWavyAngle: up by 0.000004 a frame to 2.48, then down by 0.000006 a frame to 2.43.
            if (wavyRising) {
                wavyAngle += WAVY_RISE * frames
                if (wavyAngle >= WAVY_HIGH) wavyRising = false
            } else {
                wavyAngle -= WAVY_FALL * frames
                if (wavyAngle <= WAVY_LOW) wavyRising = true
            }
        }
    }

    override fun reset() {
        step.reset()
        readClock = READ_PERIOD
        reads = 0L
        timeFloatData.fill(0f)
        timeFrequencyData.fill(SILENT_BYTE)
        wavyAngle = WAVY_ANGLE
        wavyRising = true
        flowerAngle = FLOWER_ANGLE
        flowerRising = false
        placedWavyAngle = WAVY_ANGLE
        placedFlowerAngle = FLOWER_ANGLE
        drawn = 0
    }

    internal companion object {
        /**
         * The page makes a dot at every other index, `particles[i++]` for i = 0 to 2048, so there
         * are 1025 of them. Do not tidy this to every index: the count and the odd index set are what
         * alias into the arms.
         */
        const val DOTS = 1025

        /**
         * The index dot [dot] is placed by and reads its sample from. The draw loop's own `j++`
         * makes it odd: 1, 3, ... 2049.
         */
        fun sampleIndex(dot: Int): Int = 2 * dot + 1

        /** `spiral.intensity`: how far a sample pushes a dot in depth. */
        const val INTENSITY = 0.18

        // The resting colour, `spiral.R`, `G` and `B`: purple, rgb(178, 0, 178).
        const val R = 0.7
        const val G = 0.0
        const val B = 0.7

        const val A_WAVY = 1.20
        const val B_WAVY = 0.76
        const val WAVY_ANGLE = 2.44
        const val WAVY_RISE = 0.000004
        const val WAVY_FALL = 0.000006
        const val WAVY_HIGH = 2.48
        const val WAVY_LOW = 2.43

        const val A_FLOWER = 25.0
        const val B_FLOWER = 0.0
        const val FLOWER_ANGLE = 2.86
        const val FLOWER_OFFSET = 17.0
        const val FLOWER_STEP = 0.0000004
        const val FLOWER_HIGH = 2.87
        const val FLOWER_LOW = 2.85

        /** `camera.position.set(0, 0, 175)`, looking at the origin. */
        const val CAMERA_DISTANCE = 175.0

        /** `spiral.fov`, the vertical field of view from the second frame on, in degrees. */
        const val FOV_DEGREES = 35.0
        const val NEAR = 1.0
        const val FAR = 10_000.0

        /** `context.arc(0, 0, 0.33, 0, PI2)`: each dot's radius in world units. */
        const val DOT_RADIUS = 0.33

        /** `1 / tan(fov / 2)`: one world unit at depth 1, in half heights of the frame. */
        val FOCAL: Double = 1.0 / tan(FOV_DEGREES / 2 * PI / 180)

        const val READ_PERIOD = 1f / 60f
        const val SILENT_BYTE = 128

        /** `getByteTimeDomainData`: 128 * (1 + s), clamped to 0..255 and truncated, as Chrome does. */
        fun timeDomainByte(sample: Float): Int = (128.0 * (1.0 + sample)).coerceIn(0.0, 255.0).toInt()

        /**
         * `setRGB(0.7 + s, 0 - s, 0.7 - s)` as the canvas paints it: three.js's `getStyle` truncates
         * each channel times 255 (`| 0`), and the canvas clamps it to 0..255. Packed as RGB.
         */
        fun colourOf(sample: Double): Int =
            (styleChannel(R + sample) shl 16) or (styleChannel(G - sample) shl 8) or styleChannel(B - sample)

        private fun styleChannel(value: Double): Int = (value * 255.0).toInt().coerceIn(0, 255)
    }
}
