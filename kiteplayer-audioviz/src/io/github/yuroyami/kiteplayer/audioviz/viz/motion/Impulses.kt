package io.github.yuroyami.kiteplayer.audioviz.viz.motion

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram

/**
 * The last hits, each with a place, for rings, shocks and eddies that spread from where a hit
 * landed. The newest is index 0. Ages count heard seconds, so a pause holds every ring where it is.
 *
 * Positions are in the drawings' centred units: 0,0 is the middle and one unit is half the screen
 * height, so x runs to about plus or minus the aspect ratio. A shader reads the same list as a 64
 * by 2 picture through `impulseA` and `impulseB` in the shared header.
 */
@AudioVizAuthoringApi
public class Impulses(private val capacity: Int = 64) {
    private val kind = IntArray(capacity)
    private val strength = FloatArray(capacity)
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val seed = FloatArray(capacity)
    private val age = FloatArray(capacity)
    private val texture = PixelImage(capacity, 2)
    private var dirty = true

    /** How many hits the list holds now. */
    public var count: Int = 0
        private set

    /** Adds a hit of [kind] ([LOW], [BODY] or [HIGH]) at [x], [y] as the newest, dropping the oldest when full. */
    public fun add(kind: Int, strength: Float, x: Float, y: Float, seed: Float) {
        val keep = minOf(count, capacity - 1)
        for (i in keep downTo 1) {
            this.kind[i] = this.kind[i - 1]; this.strength[i] = this.strength[i - 1]
            this.x[i] = this.x[i - 1]; this.y[i] = this.y[i - 1]
            this.seed[i] = this.seed[i - 1]; this.age[i] = this.age[i - 1]
        }
        this.kind[0] = kind.coerceIn(0, 2)
        this.strength[0] = strength.coerceIn(0f, 1f)
        this.x[0] = x; this.y[0] = y
        this.seed[0] = seed.coerceIn(0f, 1f)
        this.age[0] = 0f
        count = keep + 1
        dirty = true
    }

    /** Ages every impulse by [heardSeconds] and drops those older than [MOST_AGE]. */
    public fun advance(heardSeconds: Float) {
        if (heardSeconds <= 0f) return
        var kept = 0
        for (i in 0 until count) {
            age[i] += heardSeconds
            if (age[i] <= MOST_AGE) kept = i + 1
        }
        count = kept
        dirty = true
    }

    /** Heard seconds since hit [i] landed. */
    public fun ageOf(i: Int): Float = age[i]

    /** The kind of hit [i]: [LOW], [BODY] or [HIGH]. */
    public fun kindOf(i: Int): Int = kind[i]

    /** How hard hit [i] was, 0 to 1. */
    public fun strengthOf(i: Int): Float = strength[i]

    /** Where hit [i] landed across, in centred units. */
    public fun xOf(i: Int): Float = x[i]

    /** Where hit [i] landed up, in centred units. */
    public fun yOf(i: Int): Float = y[i]

    /** The random number 0 to 1 that came with hit [i]. */
    public fun seedOf(i: Int): Float = seed[i]

    /** Position and strength of slot [i], packed as the shader reads it. */
    internal fun rowA(i: Int): Int = if (i >= count) 0xFF000000.toInt() else
        argb(((x[i] + 2f) / 4f), ((y[i] + 2f) / 4f), strength[i])

    /** Age, kind and seed of slot [i], packed as the shader reads it. */
    internal fun rowB(i: Int): Int = if (i >= count) 0xFF000000.toInt() else
        argb(age[i] / MOST_AGE, kind[i] / 3f, seed[i])

    /** Writes the picture and hands it to [program] under the header's names. */
    public fun bindTo(program: ShaderProgram) {
        if (dirty) {
            for (i in 0 until capacity) {
                texture.pixels[i] = rowA(i)
                texture.pixels[capacity + i] = rowB(i)
            }
            texture.upload()
            dirty = false
        }
        program.child("uImpulseTex", texture.image)
        program.uniform("uImpulseCount", count.toFloat())
    }

    /** Empties the list. */
    public fun reset() {
        count = 0
        dirty = true
    }

    private fun argb(r: Float, g: Float, b: Float): Int =
        0xFF000000.toInt() or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)

    private fun byte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    public companion object {
        /** A low hit, the one the code calls a kick. */
        public const val LOW: Int = 0

        /** A body hit, the one the code calls a snare. */
        public const val BODY: Int = 1

        /** A high hit, the one the code calls a hat. */
        public const val HIGH: Int = 2

        /** Seconds an impulse stays in the list. */
        public const val MOST_AGE: Float = 8f
    }
}
