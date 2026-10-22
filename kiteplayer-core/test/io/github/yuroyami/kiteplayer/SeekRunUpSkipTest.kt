package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import io.github.yuroyami.kiteplayer.internal.skipNonReferenceBeforeTarget
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A precise seek skips the pictures nothing is built on below its target (#468): a 40 ms grid with
 * a keyframe every 2 s, where every other picture between keyframes is one no other picture is
 * predicted from, as the B-frames of a stream coded I, B, P, B, P are.
 */
class SeekRunUpSkipTest {

    private val script = MediaScript(
        durationUs = 4_000_000,
        keyframeIntervalUs = 2_000_000,
        alternateFramesAreNonReference = true,
    )
    private val probe get() = script.videoProbe

    private suspend fun TestScope.opened(): CoreHarness {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        return harness
    }

    private fun CoreHarness.onScreenUs(): Long = renderer!!.timestamps.last().micros

    @Test
    fun aPreciseSeekSkipsThePicturesNothingIsBuiltOnBelowItsTarget() = runTest {
        val harness = opened()
        assertTrue(probe.skippedUs.isEmpty(), "an open from the start skips nothing: ${probe.skippedUs}")
        probe.clear()

        assertIs<SeekResult.Applied>(harness.core.seek(Pts(1_880_000), SeekMode.Precise))
        harness.run(100.milliseconds)

        // The 47th picture, itself one nothing is built on.
        assertEquals(1_880_000, harness.onScreenUs(), "the landing is the picture at the target")
        // From the keyframe at 0 the run up holds 47 pictures below 1.88 s, and the 23 odd ones are
        // built on by nothing.
        val expectedSkips = (0 until 47).filter { it % 2 == 1 }.map { it * 40_000L }
        assertEquals(expectedSkips, probe.skippedUs, "every picture nothing is built on below the target, and nothing else")
        assertTrue(1_880_000L in probe.decodedUs && 1_920_000L in probe.decodedUs, "the pictures from the target on decode")
        harness.close()
    }

    @Test
    fun playingOnAfterThePreciseSeekDecodesEveryPicture() = runTest {
        val harness = opened()
        assertIs<SeekResult.Applied>(harness.core.seek(Pts(1_880_000), SeekMode.Precise))
        harness.run(100.milliseconds)
        probe.clear()

        harness.core.play()
        harness.run(500.milliseconds)

        assertTrue(probe.decodedUs.size > 10, "playback decoded on: ${probe.decodedUs}")
        assertTrue(probe.skippedUs.isEmpty(), "nothing after the landing is skipped: ${probe.skippedUs}")
        harness.close()
    }

    @Test
    fun aKeyframeSeekSkipsNothing() = runTest {
        val harness = opened()
        probe.clear()

        assertIs<SeekResult.Applied>(harness.core.seek(Pts(2_500_000), SeekMode.Keyframe))
        harness.run(100.milliseconds)

        assertTrue(probe.skippedUs.isEmpty(), "a keyframe seek shows the pictures it decodes: ${probe.skippedUs}")
        harness.close()
    }

    @Test
    fun aBackwardStepStillLandsOnThePictureBeforeEvenOneNothingIsBuiltOn() = runTest {
        val harness = opened()
        assertIs<SeekResult.Applied>(harness.core.seek(Pts(1_000_000), SeekMode.Precise))
        harness.run(100.milliseconds)
        assertEquals(1_000_000, harness.onScreenUs())
        probe.clear()

        harness.core.stepFrame(StepDirection.Backward)
        harness.run(100.milliseconds)
        assertEquals(960_000, harness.onScreenUs(), "one picture back")
        harness.core.stepFrame(StepDirection.Backward)
        harness.run(100.milliseconds)
        // 920 ms is the 23rd picture, one nothing is built on, and it is the landing.
        assertEquals(920_000, harness.onScreenUs(), "two pictures back, onto a picture nothing is built on")
        assertEquals(920_000, harness.core.position().inWholeMicroseconds)

        assertTrue(probe.skippedUs.isNotEmpty(), "the backward steps skip on their run up too")
        assertTrue(
            probe.skippedUs.all { it < 880_000 },
            "only pictures a whole picture short of each target are skipped: ${probe.skippedUs}",
        )
        harness.close()
    }

    @Test
    fun theRuleSkipsOnlyBelowTheTargetAndKeepsTheBackwardLanding() {
        fun skip(pts: Long?, duration: Long? = 40_000, landsBefore: Boolean = false, target: Long = 1_000_000) =
            skipNonReferenceBeforeTarget(pts, duration, target, landsBefore)

        assertTrue(skip(960_000))
        assertFalse(skip(1_000_000), "the picture at the target is kept")
        assertFalse(skip(null), "a packet with no time is never skipped")
        assertFalse(skipNonReferenceBeforeTarget(0, 40_000, Long.MIN_VALUE, false), "no seek, no skipping")

        assertTrue(skip(919_999, landsBefore = true))
        assertFalse(skip(920_000, landsBefore = true), "its next picture at 960 ms leaves no picture to spare")
        assertFalse(skip(960_000, landsBefore = true), "the backward landing itself")
        assertFalse(skip(0, duration = null, landsBefore = true), "with no duration a backward step skips nothing")
        assertFalse(skip(0, duration = 0, landsBefore = true))
    }
}
