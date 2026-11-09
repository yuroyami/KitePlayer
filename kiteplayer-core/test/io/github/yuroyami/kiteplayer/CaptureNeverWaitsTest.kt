package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A capture neither waits for a frame a paused schedule never draws nor for a picture turned off (#797). */
class CaptureNeverWaitsTest {

    @Test
    fun aCaptureArmedJustBeforeAPauseStillCompletes() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        val capture = async(start = CoroutineStart.UNDISPATCHED) { harness.core.captureFrame() }
        harness.core.pause()
        harness.run(2.seconds)
        assertTrue(capture.isCompleted, "the capture waited for a play that never came")
        capture.await()
        harness.close()
    }

    @Test
    fun aCaptureWithThePictureTurnedOffIsRefused() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000))
        harness.openWithRenderer()
        harness.core.post(CoreCommand.SetVideoEnabled(false, CompletableDeferred()))
        harness.run(20.milliseconds)
        harness.core.play()
        harness.run(500.milliseconds)
        assertFailsWith<IllegalStateException> { harness.core.captureFrame() }
        harness.close()
    }

    /**
     * A capture on a paused player re-presents its frame by a seek to the clock's position, and
     * a seek the viewer just asked for must not be replaced by it: the capture is the frame the
     * viewer's seek lands on (#794).
     */
    @Test
    fun aPausedCaptureRightAfterASeekTakesTheSeeksFrame() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        harness.core.pause()
        harness.run(500.milliseconds)
        harness.core.seekLater(Pts(40_000_000), SeekMode.Precise)
        val capture = async(start = CoroutineStart.UNDISPATCHED) { harness.core.captureFrame() }
        harness.run(3.seconds)
        val frame = capture.await()
        assertTrue(kotlin.math.abs(frame.pts.micros - 40_000_000) < 100_000, "the capture is at ${frame.pts.micros}")
        assertTrue(kotlin.math.abs(harness.core.position().inWholeMicroseconds - 40_000_000) < 100_000, "the player is at ${harness.core.position()}")
        harness.close()
    }
}
