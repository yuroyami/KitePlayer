package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A seek that aborts because a worker cannot park changes nothing, and playback goes on (#200). */
class SeekAbortTest {

    @Test
    fun anAbortedSeekLeavesPlaybackRunningFromTheOldPosition() = runTest(timeout = 60.seconds) {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 20_000_000),
            faults = FaultPlan().apply { readWedgesAfter = 200 },
            config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 10.minutes)),
        )
        harness.openWithRenderer()
        harness.core.play()
        repeat(3_000) {
            if (harness.source.wedgedAtNanos != null) return@repeat
            harness.run(10.milliseconds)
        }
        assertTrue(harness.source.wedgedAtNanos != null, "the scripted read never wedged")

        val result = harness.core.seek(Pts(12_000_000), SeekMode.Precise)
        assertIs<SeekResult.Rejected>(result)
        val warning = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.CommandRefused>().single { it.member == "seek" }
        assertTrue("demux" in warning.detail, "the warning must name the worker that did not park: ${warning.detail}")

        harness.source.releaseWedge()
        val before = harness.core.position()
        harness.run(10.seconds)
        val moved = harness.core.position() - before
        assertTrue(moved >= 9.seconds, "playback must go on after an aborted seek, moved $moved in 10 s")
        harness.close()
    }
}
