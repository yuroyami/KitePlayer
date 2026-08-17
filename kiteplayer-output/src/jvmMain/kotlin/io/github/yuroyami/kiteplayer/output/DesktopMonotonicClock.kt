package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock

/**
 * The desktop JVM monotonic clock: `System.nanoTime()`, the base every deadline
 * [DesktopAudioSink] publishes is computed on.
 *
 * NOT `System.currentTimeMillis()`: that is wall time, it jumps when NTP or the user moves the
 * clock, and a backwards jump makes the engine believe a frame it already showed is due again.
 * `System.nanoTime()` is the JVM's monotonic source on all three desktop hosts (mach_absolute_time
 * on macOS, CLOCK_MONOTONIC on Linux, QueryPerformanceCounter on Windows).
 *
 * This pairing is the whole point of the object. `javax.sound.sampled` publishes no presentation
 * timestamp of its own, so the sink builds every deadline as "now plus the audio still queued",
 * and its "now" is this call. Measure the rest of the engine from any other source and audio and
 * video sit at a constant offset no correction can find, because both sides believe they are
 * right. [DesktopOutputBackend] pairs this clock with the sink so the mismatch cannot be
 * assembled, exactly as the Android and Apple backends do.
 */
public object DesktopMonotonicClock : MonotonicClock {
    override fun nanos(): Long = System.nanoTime()
}
