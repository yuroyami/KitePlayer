package io.github.yuroyami.kiteplayer.audioviz

/**
 * The player's position, moved on with the wall clock between the player's own updates.
 *
 * The player republishes its position about every fifty milliseconds, so read at sixty frames a
 * second it stands still for three frames and then jumps, and the picture would move in steps. This
 * takes each new position as it is, and in between adds the time that has passed at the playback speed.
 */
internal class SmoothClock(
    private val published: () -> Long,
    /** The playback speed while playing, and 0 while paused, buffering or stopped. */
    private val rate: () -> Double,
    private val nanos: () -> Long,
) {
    private var lastPublished = Long.MIN_VALUE
    private var anchorNanos = 0L
    private var lastReturned = Long.MIN_VALUE

    fun micros(): Long {
        val now = nanos()
        val position = published()
        val speed = rate()
        if (position != lastPublished || speed <= 0.0) {
            lastPublished = position
            anchorNanos = now
        }
        if (speed <= 0.0) {
            lastReturned = position
            return position
        }
        val ahead = ((now - anchorNanos) / 1_000 * speed).toLong().coerceIn(0L, MOST_AHEAD_MICROS)
        val next = position + ahead
        // An update that lands a little behind holds the clock still: the picture reads a step back as a seek.
        if (next < lastReturned && lastReturned - next < MOST_AHEAD_MICROS) return lastReturned
        lastReturned = next
        return next
    }
}

/** How far it may run past the player's last update, in case the player stops publishing. */
private const val MOST_AHEAD_MICROS = 100_000L
