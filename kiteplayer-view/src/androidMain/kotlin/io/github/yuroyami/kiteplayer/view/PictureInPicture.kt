package io.github.yuroyami.kiteplayer.view

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
