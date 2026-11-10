package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A device that runs dry mid-stream, with the packet queues still full, holds the clock where the
 * last sound left it, rather than running through the silence and stepping back when sound
 * returns (#819).
 */
class AudioStarvedClockTest {

    @Test
    fun aStarvedDeviceHoldsThePositionAndNeverStepsBack() = runTest {
        val faults = FaultPlan()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000, hasVideo = false), faults = faults)
        harness.open()
        harness.core.play()
        harness.run(2.seconds)
        val before = harness.core.position()
        faults.audioDecodeHiccup = 600.milliseconds
        val readings = mutableListOf<Duration>()
        repeat(200) {
            harness.run(5.milliseconds)
            readings += harness.core.position()
        }
        assertTrue(harness.core.stats.value.audioUnderruns > 0, "the stall never starved the device, so this proves nothing")
        val back = readings.zipWithNext().maxOf { (a, b) -> (a - b).inWholeMilliseconds }
        assertTrue(back <= 1, "the position stepped back by $back ms when the sound returned")
        harness.close()
    }
}
