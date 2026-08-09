package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreAudio.AudioConvertHostTimeToNanos
import platform.CoreAudio.AudioGetCurrentHostTime

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
 * merely checked. Using CoreAudio's own conversion for both readings is what guarantees one time base.
 */
@OptIn(ExperimentalForeignApi::class)
public object AppleHostClock : MonotonicClock {

    override fun nanos(): Long = AudioConvertHostTimeToNanos(AudioGetCurrentHostTime()).toLong()

    /** Converts a CoreAudio timestamp's host time into the same nanoseconds [nanos] returns. */
    public fun hostTimeToNanos(hostTime: ULong): Long = AudioConvertHostTimeToNanos(hostTime).toLong()
}
