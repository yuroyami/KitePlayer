package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Ease
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iris's rings: each one is the waveform at the moment it was born, laid round a circle as dots.
 *
 * Every dot reads its own sample the way the dots of "Audible Visuals" by Sonia Boller do (its Wavy
 * Spiral layout; Apache 2.0 as found, credited in the Iris class): the sample pushes the dot towards
 * the viewer by the sample times its byte times 0.18, so it lands further out and larger, and here
 * it also shifts the dot's hue and light. The dots are drawn farthest first, as the page's canvas
 * renderer sorted them. A ring spreads by the step it is given and fades over eight visual cycles,
 * so its distance from the middle is how long ago it was born. The pool is fixed.
 */
internal class IrisRings(private val capacity: Int = 12, private val dots: Int = 128) {

    init {
        require(capacity * dots <= MOST_DOTS) { "at most $MOST_DOTS dots in all, asked for ${capacity * dots}" }
    }

    private val samples = FloatArray(capacity * dots)
    private val radius = FloatArray(capacity)
    private val age = FloatArray(capacity) { -1f }
    private val hue = FloatArray(capacity)
    private val turn = FloatArray(capacity)
    private val strength = FloatArray(capacity)

    // 1 while a ring lives out its time; below 1 once a newer ring has asked for its place, falling to 0.
    private val keep = FloatArray(capacity)
    private val order = LongArray(capacity)
    private val sortKeys = LongArray(capacity * dots)
    private val tones = IntArray(capacity * TONE_STEPS)

    /** Rings born since the last [clear]. */
    var born: Long = 0L
        private set

    /** Rings on screen, fading ones included. */
    val alive: Int
        get() {
            var count = 0
            for (slot in 0 until capacity) if (age[slot] >= 0f) count++
            return count
        }

    /** Rings living out their time, not fading to make room. */
    val kept: Int
        get() {
            var count = 0
            for (slot in 0 until capacity) if (age[slot] >= 0f && keep[slot] >= 1f) count++
            return count
        }

    fun radiusOf(slot: Int): Float = radius[slot]

    fun sampleOf(slot: Int, dot: Int): Float = samples[slot * dots + dot]

    /** How much light ring [slot] gives, 0 to 1: its strength, less as it ages and as it makes room. */
    fun lightOf(slot: Int): Float {
        if (age[slot] < 0f) return 0f
        val life = (1f - age[slot] / LIFE_CYCLES).coerceIn(0f, 1f)
        return strength[slot] * life * keep[slot].coerceIn(0f, 1f)
    }

    /**
     * A new ring from [scope] times [gain], [startRadius] centred units out, coloured from [hue] (a
     * place on the palette) and turned by [turn]. At most [most] rings live out their time: the
     * oldest beyond that fades out over a quarter of a cycle.
     */
    fun birth(scope: FloatArray, gain: Float, startRadius: Float, hue: Float, turn: Float, strength: Float, most: Int) {
        val limit = most.coerceIn(1, capacity)
        var living = kept
        while (living >= limit) {
            val oldest = oldestKept()
            if (oldest < 0) break
            keep[oldest] = FADING
            living--
        }
        val slot = freeSlot()
        val first = slot * dots
        for (dot in 0 until dots) {
            samples[first + dot] = (scope.sampleAt(dot / (dots - 1f)) * gain).coerceIn(-1f, 1f)
        }
        radius[slot] = startRadius
        age[slot] = 0f
        this.hue[slot] = hue
        this.turn[slot] = turn
        this.strength[slot] = strength.coerceIn(0f, 1f)
        keep[slot] = 1f
        order[slot] = born
        born++
    }

    /**
     * Moves every ring [radiusStep] centred units outward, or inward when it is negative, and ages it
     * [cycles] visual cycles. A ring goes after [LIFE_CYCLES], when its fade ends, or at [innermost].
     */
    fun advance(radiusStep: Float, cycles: Float, innermost: Float) {
        for (slot in 0 until capacity) {
            if (age[slot] < 0f) continue
            radius[slot] += radiusStep
            age[slot] += cycles
            if (keep[slot] < 1f) keep[slot] -= cycles * FADE_PER_CYCLE
            if (age[slot] >= LIFE_CYCLES || keep[slot] <= 0f || radius[slot] <= innermost) age[slot] = -1f
        }
    }

    /**
     * Every living ring as dots round ([centreX], [centreY]), [unit] pixels to a centred unit. A dot's
     * colour is its ring's place on the palette, from [baseHue] across [span] degrees, walked by
     * [walk], leaned by [lean] degrees, and moved a little by its own sample.
     */
    fun DrawScope.drawRings(
        mesh: TriangleMesh,
        centreX: Float,
        centreY: Float,
        unit: Float,
        baseHue: Float,
        span: Float,
        lean: Float,
        walk: Float,
        light: Float,
    ) {
        if (light <= 0f) return
        var count = 0
        for (slot in 0 until capacity) {
            if (age[slot] < 0f) continue
            paint(slot, baseHue, span, lean, walk)
            val first = slot * dots
            for (dot in 0 until dots) {
                val depth = depthOf(samples[first + dot])
                // The page's painter sort: the larger depth first, and creation order between equals.
                sortKeys[count++] = ((Int.MAX_VALUE - depth.toRawBits()).toLong() shl 11) or (first + dot).toLong()
            }
        }
        if (count == 0) return
        sortKeys.sort(0, count)
        mesh.clear()
        val dotPixels = (unit * DOT_SHARE).coerceAtLeast(0.8f)
        for (k in 0 until count) {
            val index = (sortKeys[k] and 0x7FF).toInt()
            val slot = index / dots
            val dot = index - slot * dots
            val alpha = lightOf(slot) * light
            if (alpha <= MIN_ALPHA) continue
            val sample = samples[index]
            val scale = CAMERA_DISTANCE / depthOf(sample)
            val reach = radius[slot] * unit * scale
            val theta = turn[slot] + TAU * dot / dots
            val rgb = tones[slot * TONE_STEPS + toneStep(sample)]
            mesh.polygon(centreX + reach * sin(theta), centreY - reach * cos(theta), dotPixels * scale, 6, 0f, argbOf(alpha, rgb))
        }
        drawMesh(mesh)
    }

    fun clear() {
        age.fill(-1f)
        keep.fill(0f)
        born = 0L
    }

    // Nine colours for one ring, from a sample of -1 to 1: its hue moved by the sample, lighter as it swings.
    private fun paint(slot: Int, baseHue: Float, span: Float, lean: Float, walk: Float) {
        for (step in 0 until TONE_STEPS) {
            val sample = step * 2f / (TONE_STEPS - 1) - 1f
            val degrees = baseHue + Ease.pingPong(hue[slot] + walk + sample * SPECKLE) * span + lean
            val lightness = 0.55f + 0.25f * abs(sample)
            val chroma = 0.85f * mostChroma(lightness, degrees)
            tones[slot * TONE_STEPS + step] = colourOf(lightness, chroma, degrees).toArgb() and 0xFFFFFF
        }
    }

    private fun oldestKept(): Int {
        var oldest = -1
        for (slot in 0 until capacity) {
            if (age[slot] < 0f || keep[slot] < 1f) continue
            if (oldest < 0 || order[slot] < order[oldest]) oldest = slot
        }
        return oldest
    }

    private fun freeSlot(): Int {
        var dimmest = 0
        var least = Float.MAX_VALUE
        for (slot in 0 until capacity) {
            if (age[slot] < 0f) return slot
            val light = lightOf(slot)
            if (light < least) {
                least = light
                dimmest = slot
            }
        }
        return dimmest
    }

    private fun toneStep(sample: Float): Int = ((sample.coerceIn(-1f, 1f) + 1f) * 0.5f * (TONE_STEPS - 1) + 0.5f).toInt()

    private fun argbOf(alpha: Float, rgb: Int): Int = ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or rgb

    internal companion object {
        /** Visual cycles a ring lives. */
        const val LIFE_CYCLES = 8f

        /** How fast a ring that made room fades, per visual cycle: gone in a quarter of one. */
        const val FADE_PER_CYCLE = 4f
        const val FADING = 0.999f

        /** A dot's index is packed into eleven bits of a sort key, as the page's sort packed it. */
        const val MOST_DOTS = 2048

        const val TONE_STEPS = 9

        /** A dot's radius at rest, as a share of a centred unit, and how far a sample moves its hue. */
        const val DOT_SHARE = 0.016f
        const val SPECKLE = 0.08f
        const val MIN_ALPHA = 0.004f

        /** `camera.position.set(0, 0, 175)` and `spiral.intensity` of the page. */
        const val CAMERA_DISTANCE = 175f
        const val INTENSITY = 0.18f

        /** `getByteTimeDomainData`: 128 * (1 + s), clamped to 0..255 and truncated, as Chrome does. */
        fun timeDomainByte(sample: Float): Int = (128.0 * (1.0 + sample)).coerceIn(0.0, 255.0).toInt()

        /** A dot's depth from the camera: the sample times its byte times the intensity, pulled towards the viewer. */
        fun depthOf(sample: Float): Float = CAMERA_DISTANCE - sample * timeDomainByte(sample) * INTENSITY
    }
}
