@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer

import platform.CoreVideo.CVGetCurrentHostTime
import platform.CoreVideo.CVGetHostClockFrequency

private const val NANOS_PER_SECOND: ULong = 1_000_000_000uL

/** Host clock ticks per second. Kotlin/Native binds no mach time call, so this reads CoreVideo. */
private val hostTicksPerSecond: ULong = CVGetHostClockFrequency().toULong()

/**
 * The host time CoreAudio dates its buffers on, which `CoreAudioSink` reads through
 * `AppleHostClock`, in nanoseconds (#829). It reads the same CoreVideo calls and converts with the
 * same arithmetic as `AppleHostClock`, so the two clocks give the same value for the same tick.
 */
internal actual fun systemMonotonicNanos(): Long {
    val ticks = CVGetCurrentHostTime()
    val whole = ticks / hostTicksPerSecond * NANOS_PER_SECOND
    return (whole + ticks % hostTicksPerSecond * NANOS_PER_SECOND / hostTicksPerSecond).toLong()
}
