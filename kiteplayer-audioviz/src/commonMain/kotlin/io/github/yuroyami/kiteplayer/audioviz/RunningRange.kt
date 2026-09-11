package io.github.yuroyami.kiteplayer.audioviz

/**
 * Places a value inside the range the signal has actually used lately.
 *
 * This is what lets a quiet song and a loud song both fill the screen. An absolute scale wastes
 * most of its room: a track masters at whatever level it masters at, and uses maybe a quarter of
 * the range from silence to full. This watches the highest and lowest readings of the last few
 * seconds and reports where the newest one sits between them.
 *
 * Both ends walk back towards the middle at [adaptPerSecond] when nothing pushes them out, so a
 * loud passage that ends is forgotten instead of holding the ceiling up forever. [minimumSpan]
 * stops a steady passage from collapsing the range to nothing and turning noise into swings.
 */
internal class RunningRange(
    private val adaptPerSecond: Float,
    private val minimumSpan: Float,
    private val initialLow: Float,
    private val initialHigh: Float,
) {
    var low: Float = initialLow
        private set

    var high: Float = initialHigh
        private set

    /** Feeds one reading and answers where it sits, 0 at the floor and 1 at the ceiling. */
    fun place(value: Float, deltaSeconds: Float): Float {
        if (value < low) low = value else low += adaptPerSecond * deltaSeconds
        if (value > high) high = value else high -= adaptPerSecond * deltaSeconds
        widen()
        return ((value - low) / (high - low)).coerceIn(0f, 1f)
    }

    /** Where [value] sits in the range as it stands, without moving it. */
    fun placeWithoutMoving(value: Float): Float = ((value - low) / (high - low)).coerceIn(0f, 1f)

    private fun widen() {
        val span = high - low
        if (span >= minimumSpan) return
        val middle = (high + low) * 0.5f
        low = middle - minimumSpan * 0.5f
        high = middle + minimumSpan * 0.5f
    }

    fun reset() {
        low = initialLow
        high = initialHigh
    }
}
