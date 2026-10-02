package io.github.yuroyami.kiteplayer.internal

import kotlin.math.abs

/**
 * Maps the ring's own time to media time, for audio that may change speed while it plays.
 *
 * The ring dates its frames on one continuous axis, the playout axis: one frame further along is
 * one frame period later, whatever speed produced it. This map turns a playout time into the media
 * time it plays, as a list of straight lines. Each line starts at a playout time, carries the media
 * time there, and says how many media microseconds pass per playout microsecond: the speed.
 *
 * A new line starts where the speed changes, where the media jumps (a gap in the stream, a join to
 * the next queue item), and where the stretch settles back onto its real input position. So a speed
 * change becomes audible exactly when the device reaches the first frame made at the new speed, and
 * the audio already in the ring keeps the speed it was made at.
 *
 * Not thread safe. `AudioPlayback` guards it with its lock: the feeder appends, readers on any
 * thread map, and the reader that anchors the clock drops lines the device has played past.
 */
internal class PlayoutTimeline {
    private var starts = LongArray(INITIAL_LINES)
    private var medias = DoubleArray(INITIAL_LINES)
    private var slopes = DoubleArray(INITIAL_LINES)
    private var count = 0

    val isEmpty: Boolean get() = count == 0

    /** Lines held, for tests. */
    val size: Int get() = count

    /** The slope of the newest line, or null when empty. */
    val lastSlope: Double? get() = if (count == 0) null else slopes[count - 1]

    fun clear() {
        count = 0
    }

    /**
     * Starts a line at playout time [playoutUs] carrying [mediaUs] with [slope], unless the newest
     * line already says the same within [JOIN_TOLERANCE_US]. Lines arrive in playout order.
     */
    fun append(playoutUs: Long, mediaUs: Double, slope: Double) {
        if (count > 0) {
            val last = count - 1
            if (slopes[last] == slope && abs(lineAt(last, playoutUs) - mediaUs) < JOIN_TOLERANCE_US) return
            if (starts[last] >= playoutUs) {
                // A later decision about the same frame replaces the earlier one.
                medias[last] = mediaUs
                slopes[last] = slope
                return
            }
        }
        if (count == starts.size) {
            starts = starts.copyOf(count * 2)
            medias = medias.copyOf(count * 2)
            slopes = slopes.copyOf(count * 2)
        }
        starts[count] = playoutUs
        medias[count] = mediaUs
        slopes[count] = slope
        count++
    }

    /** The newest line extended to [playoutUs], or null when empty. */
    fun predictLast(playoutUs: Long): Double? = if (count == 0) null else lineAt(count - 1, playoutUs)

    /** The media time at [playoutUs]. Before the first line, that line extended backwards. */
    fun mediaAt(playoutUs: Long): Long? {
        if (count == 0) return null
        return lineAt(indexAt(playoutUs), playoutUs).toLong()
    }

    /** Media microseconds per playout microsecond at [playoutUs]. 1.0 when empty. */
    fun slopeAt(playoutUs: Long): Double = if (count == 0) 1.0 else slopes[indexAt(playoutUs)]

    /** Forgets the lines that end before [playoutUs], keeping the one that covers it. */
    fun prune(playoutUs: Long) {
        var drop = 0
        while (drop + 1 < count && starts[drop + 1] <= playoutUs) drop++
        if (drop == 0) return
        starts.copyInto(starts, 0, drop, count)
        medias.copyInto(medias, 0, drop, count)
        slopes.copyInto(slopes, 0, drop, count)
        count -= drop
    }

    private fun indexAt(playoutUs: Long): Int {
        var index = count - 1
        while (index > 0 && starts[index] > playoutUs) index--
        return index
    }

    private fun lineAt(index: Int, playoutUs: Long): Double =
        medias[index] + (playoutUs - starts[index]).toDouble() * slopes[index]

    private companion object {
        const val INITIAL_LINES = 8

        /** Two lines closer than this at the new line's start are one line. */
        const val JOIN_TOLERANCE_US = 50.0
    }
}

/**
 * Maps a pipeline's source frames to media time: the timestamps the decoder gave, as the points
 * where they break from simple continuity.
 *
 * A source frame is a frame of decoded audio as the decoder made it, counted from when the
 * pipeline was built or last reset. A point is kept only where a buffer's timestamp disagrees with
 * what the frames before it predict by more than [DISCONTINUITY_US], the same rule the ring uses.
 * A point can also mark where the next queue item begins.
 *
 * Owned by the feeder alone.
 */
internal class SourceTimeline {
    private var frames = LongArray(INITIAL_POINTS)
    private var medias = LongArray(INITIAL_POINTS)
    private var joins = BooleanArray(INITIAL_POINTS)
    private var count = 0
    private var rate = 1

    val isEmpty: Boolean get() = count == 0

    /** The newest point the output has reached, so no point takes effect twice. */
    var takenThrough: Long = Long.MIN_VALUE

    /** Starts over for a pipeline whose source runs at [sampleRate]. */
    fun clear(sampleRate: Int) {
        count = 0
        rate = sampleRate.coerceAtLeast(1)
        takenThrough = Long.MIN_VALUE
    }

    /**
     * Records that source frame [frame] carries [mediaUs]. Kept only when it breaks continuity or
     * marks a [join]; true when a point was added.
     */
    fun record(frame: Long, mediaUs: Long, join: Boolean = false): Boolean {
        if (!join && count > 0) {
            val predicted = mediaAt(frame.toDouble())
            if (abs(predicted - mediaUs) < DISCONTINUITY_US) return false
        }
        if (count == frames.size) {
            frames = frames.copyOf(count * 2)
            medias = medias.copyOf(count * 2)
            joins = joins.copyOf(count * 2)
        }
        frames[count] = frame
        medias[count] = mediaUs
        joins[count] = join
        count++
        return true
    }

    /** The media time of source position [position]. Before the first point, that point's line backwards. */
    fun mediaAt(position: Double): Double {
        var index = count - 1
        while (index > 0 && frames[index] > position) index--
        return medias[index] + (position - frames[index]) * 1_000_000.0 / rate
    }

    /** The first point after [after] and no later than [until], as an index, or -1. */
    fun nextPointIn(after: Double, until: Double): Int {
        for (index in 0 until count) {
            val at = frames[index].toDouble()
            if (at > after && at <= until) return index
        }
        return -1
    }

    fun pointFrame(index: Int): Long = frames[index]

    fun pointMedia(index: Int): Long = medias[index]

    fun pointIsJoin(index: Int): Boolean = joins[index]

    /** Forgets the points before the one that covers [position]. */
    fun prune(position: Double) {
        var drop = 0
        while (drop + 1 < count && frames[drop + 1] <= position) drop++
        if (drop == 0) return
        frames.copyInto(frames, 0, drop, count)
        medias.copyInto(medias, 0, drop, count)
        joins.copyInto(joins, 0, drop, count)
        count -= drop
    }

    private companion object {
        const val INITIAL_POINTS = 8
        const val DISCONTINUITY_US = KotlinAudioRing.DISCONTINUITY_TOLERANCE_US.toDouble()
    }
}
