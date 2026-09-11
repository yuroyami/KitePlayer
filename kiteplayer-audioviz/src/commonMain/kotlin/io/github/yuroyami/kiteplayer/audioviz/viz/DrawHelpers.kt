package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** The middle of the canvas. Half the drawings here are radial. */
internal val DrawScope.centre: Offset get() = Offset(size.width / 2f, size.height / 2f)

/** A scene extends towards the corners, rather than fitting in a small inscribed circle. */
internal val DrawScope.sceneRadius: Float
    get() = sqrt(size.width * size.width + size.height * size.height) * 0.5f

/** A point [radius] away from [from] at [angle] radians, measured clockwise from straight up. */
internal fun polar(from: Offset, angle: Float, radius: Float): Offset = Offset(
    from.x + sin(angle) * radius,
    from.y - cos(angle) * radius,
)

/** Reads the array at a 0..1 position along its length, blending between neighbours. */
internal fun FloatArray.sampleAt(position: Float): Float {
    if (isEmpty()) return 0f
    if (size == 1) return this[0]
    val scaled = position.coerceIn(0f, 1f) * (size - 1)
    val lower = scaled.toInt().coerceIn(0, size - 2)
    val fraction = scaled - lower
    return this[lower] + (this[lower + 1] - this[lower]) * fraction
}

/**
 * Reads the array folded in half, so the first and last points read the same value.
 *
 * Anything drawn round a circle needs this or it has a visible seam where the loud low end meets
 * the quiet high end.
 */
internal fun FloatArray.foldedAt(position: Float): Float {
    val wrapped = position.coerceIn(0f, 1f)
    return sampleAt(if (wrapped <= 0.5f) wrapped * 2f else (1f - wrapped) * 2f)
}

/**
 * A repeatable random number source, one per drawing.
 *
 * Every drawing that sprays particles needs random numbers, and none of them want the same
 * sequence as their neighbour or a different one on every run. This is a plain multiply-and-add
 * generator: not good enough for anything that matters, perfectly good for deciding where a spark
 * goes, and it allocates nothing.
 */
internal class Rng(private val seed: Long = 88_172_645L) {
    private var state = seed

    /** The next number between 0 and 1. */
    fun next(): Float {
        state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        return abs((state shr 24).toInt() % 10_000) / 10_000f
    }

    /** The next number between -1 and 1. */
    fun signed(): Float = next() * 2f - 1f

    fun reset() {
        state = seed
    }
}

/**
 * Scales a raw waveform so it fills the drawing whatever the mastering level, the way the bars use
 * the song's own range. It follows a louder peak at once and lets a quieter one in over a couple of
 * seconds, so a quiet song draws as large as a loud one without the size pumping.
 */
internal class TraceGain(private val releasePerSecond: Float = 0.6f, private val floor: Float = 0.004f) {
    private var peak = floor

    /** The multiplier for this frame: one over the recent peak of either trace. */
    fun update(first: FloatArray, second: FloatArray, deltaSeconds: Float): Float {
        var loudest = 0f
        for (value in first) loudest = maxOf(loudest, abs(value))
        for (value in second) loudest = maxOf(loudest, abs(value))
        peak = if (loudest > peak) loudest else peak * exp(-releasePerSecond * deltaSeconds)
        peak = peak.coerceAtLeast(floor)
        return 1f / peak
    }

    fun reset() {
        peak = floor
    }
}

internal const val TAU: Float = 6.2831855f
