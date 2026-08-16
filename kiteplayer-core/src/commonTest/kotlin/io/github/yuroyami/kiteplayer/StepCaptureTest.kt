@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * S4.e landing two: stepping a paused player advances exactly one frame period and presents it,
 * stepping a playing player refuses, and captureFrame copies the presented frame's own pixels at
 * the presentation boundary in both the paused and the playing shape.
 */
class StepCaptureTest {

    @Test
    fun `a paused step advances one frame period and presents one frame`() = runTest {
        val script = MediaScript(durationUs = 4_000_000)
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.run(200.milliseconds)
        val before = harness.core.position().inWholeMicroseconds
        val presentedBefore = harness.renderer!!.presentations.size

        harness.core.stepFrame()
        harness.run(200.milliseconds)

        val moved = harness.core.position().inWholeMicroseconds - before
        assertTrue(
            abs(moved - script.videoFrameDurationUs) <= script.videoFrameDurationUs,
            "one step must move about one frame period (${script.videoFrameDurationUs} us), moved $moved us",
        )
        assertTrue(moved > 0, "the step must move forward, moved $moved us")
        assertTrue(
            harness.renderer!!.presentations.size > presentedBefore,
            "the stepped frame must reach the renderer",
        )
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "a step stays paused")
        harness.close()
    }

    @Test
    fun `stepping a playing player refuses typed`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)
        assertFailsWith<IllegalStateException> { harness.core.stepFrame() }
        harness.close()
    }

    @Test
    fun `a paused capture copies the presented frame's own pixels`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.run(200.milliseconds)

        val captured = harness.core.captureFrame()
        assertEquals(3, captured.planeCount, "the scripted frame carries three planes")
        val luma = ByteArray(captured.planeStride(0) * captured.planeHeight(0))
        captured.copyPlane(0, luma)
        val expected = (captured.pts.micros % 251).toByte()
        assertTrue(
            luma.all { it == expected },
            "the captured luma must be the presented frame's own bytes " +
                "(pts ${captured.pts.micros} expects $expected, got ${luma.toList()})",
        )
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "a capture stays paused")
        harness.close()
    }

    @Test
    fun `a playing capture completes from the next presented frame`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        val capture = async { harness.core.captureFrame() }
        harness.run(1.seconds)
        val captured = capture.await()
        val luma = ByteArray(captured.planeStride(0) * captured.planeHeight(0))
        captured.copyPlane(0, luma)
        assertEquals(
            (captured.pts.micros % 251).toByte(),
            luma[0],
            "the copy must match the frame it names",
        )
        harness.close()
    }

    @Test
    fun `capture refuses typed without video`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(hasVideo = false))
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        assertFailsWith<IllegalStateException> { harness.core.captureFrame() }
        harness.close()
    }
}
