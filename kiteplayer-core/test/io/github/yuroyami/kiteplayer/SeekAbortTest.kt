package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** A rejected seek leaves the frame-step hold, so the position still answers the stepped frame (#788). */
    @Test
    fun anAbortedSeekKeepsTheSteppedFramesPosition() = runTest(timeout = 60.seconds) {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 20_000_000),
            faults = FaultPlan().apply { readWedgesAfter = 200 },
            config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 10.minutes)),
        )
        harness.openWithRenderer()
        harness.core.play()
        // The demux worker wedges in a read, so it cannot park for the seek below.
        repeat(3_000) {
            if (harness.source.wedgedAtNanos != null) return@repeat
            harness.run(10.milliseconds)
        }
        assertTrue(harness.source.wedgedAtNanos != null, "the scripted read never wedged")
        harness.core.pause()
        harness.run(200.milliseconds)
        val pausedAt = harness.core.position()
        repeat(3) {
            harness.core.stepFrame(StepDirection.Forward)
            harness.run(200.milliseconds)
        }
        val stepped = harness.core.position()
        assertTrue(stepped > pausedAt, "the steps did not move the picture, so this proves nothing")

        assertIs<SeekResult.Rejected>(harness.core.seek(Pts(12_000_000), SeekMode.Precise))
        harness.run(500.milliseconds)
        assertEquals(stepped, harness.core.position(), "the rejected seek let go of the stepped frame")
        harness.source.releaseWedge()
        val shownBefore = harness.renderer!!.timestamps.size
        harness.core.play()
        harness.run(1.seconds)
        val after = harness.renderer!!.timestamps.drop(shownBefore).map { it.micros }
        assertTrue(after.isNotEmpty() && after.first() >= stepped.inWholeMicroseconds, "play went back behind the stepped frame: ${after.take(5)}")
        harness.close()
    }
}
