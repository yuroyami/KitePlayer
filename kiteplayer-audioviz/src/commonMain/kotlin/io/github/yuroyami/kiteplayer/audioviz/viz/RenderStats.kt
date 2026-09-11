package io.github.yuroyami.kiteplayer.audioviz.viz

/**
 * How long the processor spent preparing each part of a frame, in milliseconds, smoothed.
 *
 * Hand one to a surface and read it whenever you like, for example once a frame in a readout. It
 * counts time spent on this side only: on a graphics card the drawing itself happens later,
 * elsewhere, and is not seen here.
 */
public class RenderStats {
    /** Drawing the picture itself. */
    public var scene: Float = 0f
        internal set

    /** Bending and fading the previous frame, for drawings with a trail. */
    public var warp: Float = 0f
        internal set

    /** The finishing pass: glow, fringes, corners and grain. */
    public var post: Float = 0f
        internal set

    /** The full-screen ground under the drawing. */
    public var ground: Float = 0f
        internal set

    /** The sharp layer drawn over the echoes. */
    public var front: Float = 0f
        internal set

    /** How much of the canvas the feedback buffers cover at the moment, 0 to 1. */
    public var scale: Float = 1f
        internal set

    internal fun addScene(millis: Float) {
        scene += (millis - scene) * SMOOTHING
    }

    internal fun addWarp(millis: Float) {
        warp += (millis - warp) * SMOOTHING
    }

    internal fun addPost(millis: Float) {
        post += (millis - post) * SMOOTHING
    }

    internal fun addGround(millis: Float) {
        ground += (millis - ground) * SMOOTHING
    }

    internal fun addFront(millis: Float) {
        front += (millis - front) * SMOOTHING
    }

    private companion object {
        const val SMOOTHING = 0.1f
    }
}

/**
 * How much of the canvas the feedback buffers cover, and whether that may drop while frames run slow.
 *
 * Only drawings with a trail render into a buffer; the rest draw straight onto the canvas and are not
 * affected. The finishing pass always runs at full size, so edges stay sharp. With [dynamic] on, five
 * slow frames in a row take the buffers down by a tenth, and they climb back two percent a second
 * while frames are quick again.
 */
public class RenderQuality(scale: Float = 1f, public var dynamic: Boolean = true) {

    /** The most of the canvas the buffers may cover, from a quarter to all of it. */
    public var scale: Float = scale.coerceIn(SMALLEST, 1f)
        set(value) {
            field = value.coerceIn(SMALLEST, 1f)
        }

    /** What is in use at this moment: [scale], or less while frames are running slow. */
    public var current: Float = this.scale
        private set

    private var slowFrames = 0

    /** Takes note of how long the last frame took and adjusts [current]. */
    internal fun afterFrame(millis: Float, deltaSeconds: Float) {
        if (!dynamic) {
            current = scale
            return
        }
        if (millis > SLOW_MILLIS) {
            slowFrames++
            if (slowFrames >= SLOW_RUN) {
                current = (current * 0.9f).coerceAtLeast(SMALLEST)
                slowFrames = 0
            }
        } else {
            slowFrames = 0
            if (millis < QUICK_MILLIS) current += 0.02f * deltaSeconds
        }
        current = current.coerceIn(SMALLEST, scale)
    }

    /** [current] in steps of five percent, so the buffers are not rebuilt on every tiny change. */
    internal val stepped: Float get() = (kotlin.math.floor(current * 20f) / 20f).coerceAtLeast(SMALLEST)

    private companion object {
        const val SMALLEST = 0.25f
        const val SLOW_MILLIS = 14f
        const val QUICK_MILLIS = 10f
        const val SLOW_RUN = 5
    }
}
