@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * "Is it the decoder or the display" is the first question of every device session. These two
 * figures answer it: how long a frame took to decode, and how late it reached the renderer against
 * the schedule's own target.
 */
class FrameTimingStatsTest {

    @Test
    fun `decode time percentiles report what the decoder took`() = runTest {
        val faults = FaultPlan().apply { videoDecodeReceiveDelay = 4.milliseconds }
        val harness = CoreHarness(
            this,
            faults = faults,
            config = PlayerConfig(statsInterval = 200.milliseconds),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)

        val stats = harness.core.stats.value
        assertEquals(4.milliseconds, stats.decodeTimeP50, "every decode took exactly 4 ms of virtual time")
        assertEquals(4.milliseconds, stats.decodeTimeP95)
        harness.close()
    }

    @Test
    fun `presentation lateness is measured from the schedule's target`() = runTest {
        // A renderer that takes 3 ms to draw. The schedule aims each frame at a target instant and
        // the clock is read after present returns, so the lateness is the draw time plus however
        // late the wake-up was. The scheduler waits in whole virtual milliseconds, so the wake-up
        // can trail the target by under a millisecond; nothing else may move the figure.
        val harness = CoreHarness(
            this,
            renderer = RecordingRenderer(presentDuration = 3.milliseconds),
            config = PlayerConfig(statsInterval = 200.milliseconds),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)

        val lateness = harness.core.stats.value.presentLatenessP95
        assertTrue(
            lateness >= 3.milliseconds && lateness < 4.milliseconds,
            "expected the 3 ms draw plus at most the wake-up rounding, got $lateness",
        )
        harness.close()
    }

    @Test
    fun `with no renderer attached nothing is presented and nothing is measured`() = runTest {
        val harness = CoreHarness(
            this,
            renderer = null,
            config = PlayerConfig(statsInterval = 200.milliseconds),
        )
        harness.open()
        harness.core.play()
        harness.run(1.seconds)

        assertEquals(Duration.ZERO, harness.core.stats.value.presentLatenessP95)
        harness.close()
    }
}
