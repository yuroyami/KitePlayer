package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SyncAction
import io.github.yuroyami.kiteplayer.internal.SyncLaw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The synchronisation law is a pure function, so it gets a table instead of a video and a squint.
 *
 * Every row here is a case that shows up on real files. The thresholds are not tuned in this test:
 * if a row fails, either the law changed or the constants did, and both are decisions rather than
 * accidents.
 */
class SyncLawTest {

    private val frame24fps = 41_667L
    private val frame60fps = 16_667L
    private val longFrame = 500_000L

    @Test
    fun `no correction when either clock has no reading`() {
        assertEquals(frame24fps, SyncLaw.targetDelayUs(frame24fps, videoClock = null, masterClock = pts(1_000)))
        assertEquals(frame24fps, SyncLaw.targetDelayUs(frame24fps, videoClock = pts(1_000), masterClock = null))
        assertEquals(frame24fps, SyncLaw.targetDelayUs(frame24fps, videoClock = null, masterClock = null))
    }

    @Test
    fun `no correction inside the tolerance`() {
        // At 24 fps the tolerance is clamped up to 41.7 ms, so a 20 ms error is left alone.
        val delay = SyncLaw.targetDelayUs(frame24fps, videoClock = pts(1_020), masterClock = pts(1_000))
        assertEquals(frame24fps, delay, "a small error must not be corrected, or the picture oscillates")
        assertEquals(SyncAction.OnTime, SyncLaw.classify(frame24fps, delay))
    }

    @Test
    fun `tolerance is clamped to the floor for fast frame rates`() {
        // At 60 fps the frame is 16.7 ms, shorter than the 40 ms floor, so the floor applies.
        // A 30 ms error is inside it and must be left alone.
        assertEquals(frame60fps, SyncLaw.targetDelayUs(frame60fps, pts(1_030), pts(1_000)))
        // A 45 ms error is outside it.
        assertTrue(SyncLaw.targetDelayUs(frame60fps, pts(1_045), pts(1_000)) > frame60fps)
    }

    @Test
    fun `tolerance is clamped to the ceiling for slow frame rates`() {
        // A 500 ms frame would otherwise get a 500 ms tolerance, which the viewer sees as the
        // picture lagging the sound by half a second. The ceiling is 100 ms.
        assertTrue(
            SyncLaw.targetDelayUs(longFrame, pts(1_150), pts(1_000)) != longFrame,
            "a long frame must still be corrected once the error passes 100 ms",
        )
    }

    @Test
    fun `late video shortens the current frame`() {
        // Video is 60 ms behind the master at 24 fps: tolerance 41.7 ms, so correct.
        val delay = SyncLaw.targetDelayUs(frame24fps, videoClock = pts(940), masterClock = pts(1_000))
        assertEquals(0L, delay, "60 ms late on a 41.7 ms frame means show the next frame immediately")
        assertEquals(SyncAction.Immediate, SyncLaw.classify(frame24fps, delay))
    }

    @Test
    fun `slightly late video shortens without reaching zero`() {
        // 50 ms late on a 500 ms frame: shorten by exactly the error.
        val delay = SyncLaw.targetDelayUs(longFrame, videoClock = pts(880), masterClock = pts(1_000))
        assertEquals(longFrame - 120_000L, delay)
        assertEquals(SyncAction.Shortened, SyncLaw.classify(longFrame, delay))
    }

    @Test
    fun `early video on a short frame repeats it exactly once`() {
        val delay = SyncLaw.targetDelayUs(frame24fps, videoClock = pts(1_060), masterClock = pts(1_000))
        assertEquals(frame24fps * 2, delay, "one repeat, never more: the next pass re-measures")
        assertEquals(SyncAction.Repeated, SyncLaw.classify(frame24fps, delay))
    }

    @Test
    fun `early video on a long frame extends it instead of repeating`() {
        // Repeating a 500 ms frame would be a one second freeze. Extend by the error instead.
        val delay = SyncLaw.targetDelayUs(longFrame, videoClock = pts(1_200), masterClock = pts(1_000))
        assertEquals(longFrame + 200_000L, delay)
        assertEquals(SyncAction.Extended, SyncLaw.classify(longFrame, delay))
    }

    @Test
    fun `the frame duplication threshold is where the behaviour switches`() {
        val justUnder = SyncLaw.FRAME_DUP_THRESHOLD_US - 1
        val justOver = SyncLaw.FRAME_DUP_THRESHOLD_US + 1
        val error = 200_000L

        assertEquals(
            justUnder * 2,
            SyncLaw.targetDelayUs(justUnder, pts(1_000 + error / 1000), pts(1_000)),
            "at or below the threshold, repeat",
        )
        assertEquals(
            justOver + error,
            SyncLaw.targetDelayUs(justOver, pts(1_000 + error / 1000), pts(1_000)),
            "above the threshold, extend",
        )
    }

    @Test
    fun `a difference beyond the container ceiling is left uncorrected`() {
        // This is a discontinuity, not drift. Correcting it would try to wait out an hour.
        val delay = SyncLaw.targetDelayUs(
            nominalDelayUs = frame24fps,
            videoClock = pts(1_000),
            masterClock = pts(1_000 + 20_000),
            maxFrameDurationUs = SyncLaw.MAX_FRAME_DURATION_DISCONTINUOUS_US,
        )
        assertEquals(frame24fps, delay)
    }

    @Test
    fun `an ordinary file still corrects a ten second error`() {
        // The guard uses the container ceiling, which is 3600 s for a normal file, not the 10 s
        // no-sync constant. Using the 10 s constant here would silently disable correction on
        // ordinary files with a large but real offset.
        val delay = SyncLaw.targetDelayUs(
            nominalDelayUs = frame24fps,
            videoClock = pts(1_000),
            masterClock = pts(12_000),
            maxFrameDurationUs = SyncLaw.MAX_FRAME_DURATION_NORMAL_US,
        )
        assertEquals(0L, delay, "eleven seconds late means show the next frame immediately")
    }

    @Test
    fun `the correction never returns a negative delay`() {
        val cases = listOf(0L, 1L, 1_000L, frame60fps, frame24fps, longFrame)
        for (nominal in cases) {
            for (errorMs in listOf(-5_000L, -1_000L, -100L, -1L, 0L, 1L, 100L, 1_000L, 5_000L)) {
                val delay = SyncLaw.targetDelayUs(nominal, pts(1_000 + errorMs), pts(1_000))
                assertTrue(delay >= 0, "nominal=$nominal error=${errorMs}ms produced $delay")
            }
        }
    }
}
