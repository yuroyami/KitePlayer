package io.github.yuroyami.kiteplayer

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * A presentation timestamp on the media timeline, in microseconds.
 *
 * Microseconds because that is FFmpeg's own `AV_TIME_BASE`, so no conversion happens at the
 * boundary, and because a 64-bit microsecond counter covers 292 000 years.
 *
 * This is a value class rather than a `Long` on purpose. The largest single source of subtle bugs
 * in every player's timing code is naked numbers in mixed units: media time and wall time, seconds
 * and nanoseconds, values already divided by playback speed and values not yet divided. Making the
 * unit part of the type turns those mistakes into compile errors.
 *
 * There is no sentinel for "absent". A timestamp that may be missing is a `Pts?`, so the compiler
 * asks for the check that FFmpeg's `AV_NOPTS_VALUE` leaves to the reader.
 */
@JvmInline
public value class Pts(public val micros: Long) : Comparable<Pts> {

    override fun compareTo(other: Pts): Int = micros.compareTo(other.micros)

    public operator fun plus(duration: Duration): Pts = Pts(micros + duration.inWholeMicroseconds)
    public operator fun minus(duration: Duration): Pts = Pts(micros - duration.inWholeMicroseconds)

    /** The interval from [other] to this. Positive when this is later. */
    public operator fun minus(other: Pts): Duration = (micros - other.micros).microseconds

    public fun coerceAtLeast(minimum: Pts): Pts = if (this < minimum) minimum else this

    public val asDuration: Duration get() = micros.microseconds

    override fun toString(): String {
        if (micros == 0L) return "0s"
        val negative = micros < 0
        val total = if (negative) -micros else micros
        val hours = total / 3_600_000_000L
        val minutes = total / 60_000_000L % 60
        val seconds = total / 1_000_000L % 60
        val millis = total / 1_000L % 1_000
        val sign = if (negative) "-" else ""
        return if (hours > 0) {
            "$sign$hours:${pad2(minutes)}:${pad2(seconds)}.${pad3(millis)}"
        } else {
            "$sign${pad2(minutes)}:${pad2(seconds)}.${pad3(millis)}"
        }
    }

    public companion object {
        public val Zero: Pts = Pts(0)
        public fun ofDuration(duration: Duration): Pts = Pts(duration.inWholeMicroseconds)
        private fun pad2(v: Long) = if (v < 10) "0$v" else "$v"
        private fun pad3(v: Long) = when {
            v < 10 -> "00$v"
            v < 100 -> "0$v"
            else -> "$v"
        }
    }
}

/**
 * The epoch a piece of pipeline state belongs to.
 *
 * Every packet, frame, queue, decoder and clock carries one. The core increments it on every seek
 * and every stream reconfiguration, and anything whose generation is not current is discarded
 * without being decoded or shown, wherever it is found.
 *
 * This single integer replaces a flush handshake across four threads. It is the reason seeking in
 * this engine is a state machine that can be reasoned about instead of a race that is tested by
 * hope.
 *
 * Generation 0 is the initial one and is valid. "Not started" is expressed as a null
 * `Generation?`, never as a reserved value.
 */
@JvmInline
public value class Generation(public val value: Long) : Comparable<Generation> {
    override fun compareTo(other: Generation): Int = value.compareTo(other.value)
    public fun next(): Generation = Generation(value + 1)
    override fun toString(): String = "gen$value"

    public companion object {
        public val Initial: Generation = Generation(0)
    }
}
