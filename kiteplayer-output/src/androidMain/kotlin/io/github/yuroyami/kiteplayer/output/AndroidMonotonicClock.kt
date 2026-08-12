package io.github.yuroyami.kiteplayer.output

import android.os.SystemClock
import io.github.yuroyami.kiteplayer.MonotonicClock

/**
 * Android's monotonic clock, on the exact time base `AudioTimestamp.nanoTime` reports:
 * `SystemClock.elapsedRealtimeNanos()`.
 *
 * This pairing is the whole point of the object (S1.c.4 step 2). The sink anchors the engine's
 * master clock to the deadline its render callback carries, and that deadline is computed from
 * an `AudioTimestamp`. Measure the rest of the engine's time from any other source and audio and
 * video sit at a constant offset no correction can find, because both sides believe they are
 * right. `AndroidOutputBackend` pairs this clock with the AudioTrack sink so the mismatch cannot
 * be assembled, mirroring what the Apple output asserts at construction.
 */
public object AndroidMonotonicClock : MonotonicClock {
    override fun nanos(): Long = SystemClock.elapsedRealtimeNanos()
}
