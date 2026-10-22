@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the audio device says it is holding reaches the caller.
 *
 * `AudioSink.latencyNanos` was implemented by every sink and read by nobody, so `audioLatency` was
 * documented as "always zero" while the number sat one field access away. It is diagnostic rather
 * than a correction: the clock anchors on when a frame became audible, so nothing needs this to
 * keep time. It is the figure that explains a device holding an enormous buffer, which is exactly
 * the question someone asks when audio and video look out of step.
 */
class AudioLatencyStatTest {

    @Test
    fun `the device's reported latency reaches the stats`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(hasVideo = false, durationUs = 4_000_000),
            sinkLatencyNanos = 35_000_000L,
            renderer = null,
        )
        harness.open()
        harness.core.play()
        harness.run(1500.milliseconds)

        assertEquals(
            35.milliseconds,
            harness.core.stats.value.audioLatency,
            "the sink reports 35 ms of pending audio and the stats must say so",
        )
        // Quality travels beside it, because a figure without its confidence is worse than none:
        // a sink that cannot measure must not read like one that measured zero.
        assertEquals(LatencyQuality.Estimated, harness.core.stats.value.audioLatencyQuality)
        harness.close()
    }
}
