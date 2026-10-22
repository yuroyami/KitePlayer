package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreVideo.CVGetCurrentHostTime
import platform.CoreVideo.CVGetHostClockFrequency

/**
 * The one clock the engine and the audio device share on Apple platforms.
 *
 * This matters more than it looks. CoreAudio reports when a buffer will be heard as a host time, and
 * the engine anchors its audio clock to that instant. If the engine measured time from a different
 * source, the two would agree only by luck, and audio and video would sit at a constant offset that
 * no correction could ever find, because both sides would believe they were right.
 *
 * So the sink and the engine are handed the same clock explicitly, through [AppleOutputBackend] in
 * `PlayerConfig.backends.output`, rather than each picking its own. That object pairs this clock with
 * the sink factory that reports on it, which is what makes the mismatch unassemblable rather than
 * merely checked. CoreVideo exposes that shared host clock without allocating on each reading.
 */
@OptIn(ExperimentalForeignApi::class)
public object AppleHostClock : MonotonicClock {

    private const val NANOS_PER_SECOND: ULong = 1_000_000_000uL
    private val ticksPerSecond: ULong = CVGetHostClockFrequency().let { frequency ->
        require(frequency.isFinite() && frequency > 0.0) {
            "CoreVideo host clock frequency must be finite and positive: $frequency"
        }
        val integralFrequency = frequency.toULong()
        require(integralFrequency.toDouble() == frequency) {
            "CoreVideo host clock frequency must be exactly integral: $frequency"
        }
        require(integralFrequency <= ULong.MAX_VALUE / NANOS_PER_SECOND) {
            "CoreVideo host clock frequency is too large to convert safely: $integralFrequency"
        }
        integralFrequency
    }

    override fun nanos(): Long = hostTimeToNanos(CVGetCurrentHostTime())

    /** Converts a CoreAudio timestamp's host time into the same nanoseconds [nanos] returns. */
    public fun hostTimeToNanos(hostTime: ULong): Long {
        val wholeSeconds = hostTime / ticksPerSecond
        val remainderTicks = hostTime % ticksPerSecond
        require(wholeSeconds <= Long.MAX_VALUE.toULong() / NANOS_PER_SECOND) {
            "CoreVideo host time is too large to convert safely: $hostTime"
        }
        val wholeNanos = wholeSeconds * NANOS_PER_SECOND
        val remainderNanos = remainderTicks * NANOS_PER_SECOND / ticksPerSecond
        require(wholeNanos <= Long.MAX_VALUE.toULong() - remainderNanos) {
            "CoreVideo host time is too large to convert safely: $hostTime"
        }
        return (wholeNanos + remainderNanos).toLong()
    }
}
