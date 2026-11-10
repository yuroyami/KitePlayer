@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.darwin.mach_absolute_time
import platform.darwin.mach_timebase_info
import platform.darwin.mach_timebase_info_data_t

/** The host clock's ticks to nanoseconds, as numerator and denominator. */
private val hostTimebase: Pair<ULong, ULong> = memScoped {
    val info = alloc<mach_timebase_info_data_t>()
    mach_timebase_info(info.ptr)
    info.numer.toULong() to info.denom.toULong()
}

/**
 * The host time CoreAudio dates its buffers on, which `CoreAudioSink` reads through
 * `AppleHostClock`, in nanoseconds (#829). The ticks are converted whole part first, so the
 * product cannot overflow, and the result is the same floor of ticks times the timebase that
 * `AppleHostClock` computes from the host clock's frequency.
 */
internal actual fun systemMonotonicNanos(): Long {
    val ticks = mach_absolute_time()
    val (numer, denom) = hostTimebase
    return (ticks / denom * numer + ticks % denom * numer / denom).toLong()
}
