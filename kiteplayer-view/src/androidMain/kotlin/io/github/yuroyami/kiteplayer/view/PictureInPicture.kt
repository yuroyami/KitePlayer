package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.PlaybackStatus

/** A width to height ratio as the integer pair `android.util.Rational` wants. */
internal data class PipAspect(val numerator: Int, val denominator: Int)

/**
 * The aspect a picture-in-picture window should ask for: the video's display size, turned with
 * its rotation, reduced, and clamped to the widest and tallest the OS accepts (2.39:1 either
 * way). A size that is not a size answers 16:9 rather than throwing at the moment the viewer is
 * leaving the app.
 */
internal fun pictureInPictureAspect(width: Int, height: Int, rotationDegrees: Int): PipAspect {
    if (width <= 0 || height <= 0) return PipAspect(16, 9)
    val turn = ((rotationDegrees % 360) + 360) % 360
    val (w, h) = if (turn == 90 || turn == 270) height to width else width to height
    // The limits as the OS states them: no wider than 2.39:1, no taller than 1:2.39.
    if (w.toLong() * 100 > h.toLong() * 239) return PipAspect(239, 100)
    if (h.toLong() * 100 > w.toLong() * 239) return PipAspect(100, 239)
    val divisor = gcd(w, h)
    return PipAspect(w / divisor, h / divisor)
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/** The two things picture-in-picture parameters carry that this view can work out. */
internal data class PipUpdate(val aspect: PipAspect, val autoEnter: Boolean)

/**
 * Decides when fresh parameters are worth pushing.
 *
 * Auto-enter is why this exists. The OS reads it from the parameters it was last given rather than
 * watching the player, so parameters built once while paused leave auto-enter off for the whole
 * session. Anything that moves the aspect or the play state has to be pushed again.
 */
internal class PipUpdatePump(private val autoEnterWhilePlaying: Boolean) {
    private var last: PipUpdate? = null

    /** The update to push, or null when nothing a window would notice has changed. */
    fun next(width: Int, height: Int, rotationDegrees: Int, status: PlaybackStatus): PipUpdate? {
        val update = PipUpdate(
            aspect = pictureInPictureAspect(width, height, rotationDegrees),
            autoEnter = autoEnterWhilePlaying && status == PlaybackStatus.Playing,
        )
        if (update == last) return null
        last = update
        return update
    }
}
