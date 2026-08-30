package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [FrameDropPolicy.LateAndDecode] wired into a real session, with a decoder that cannot keep up.
 *
 * [VideoDropLawTest] proves the rule. This proves the rule is CONNECTED, which is a separate
 * question and the one the counter used to answer with a permanent zero.
 *
 * The decoder is made slow rather than faulty: every send takes four frame periods of the test's
 * virtual clock, so the audio clock walks away from the video lane exactly the way it does on a
 * phone decoding 4K it cannot manage. Nothing here is timing dependent; the clock is the test
 * scheduler's.
 */
class LateAndDecodeTest {

    private val script = MediaScript(
        durationUs = 20_000_000,
        // 25 fps with a keyframe every ten frames, so a skip gives up part of a group of pictures
        // and lands on the next keyframe rather than running to the end of the file.
        videoFrameDurationUs = 40_000,
        keyframeIntervalUs = 400_000,
    )

    private fun slowDecoder() = FaultPlan().apply { videoDecodeSendDelay = 160.milliseconds }

    @Test
    fun `a decoder that falls behind drops packets before decoding them`() = runTest {
        val harness = CoreHarness(
            this,
            script = script,
            faults = slowDecoder(),
            config = PlayerConfig(frameDrop = FrameDropPolicy.LateAndDecode),
        )
        harness.open()
        harness.core.play()
        harness.run(8.seconds)

        val stats = harness.core.stats.value
        assertTrue(
            stats.droppedFramesDecode > 0,
            "the decoder was four times too slow and nothing was dropped before it: $stats",
        )
        // The point of dropping is to keep showing SOMETHING. A skip that swallowed the keyframes
        // too would leave this at zero and the screen frozen for the whole file.
        assertTrue(
            stats.decodedVideoFrames > 0,
            "keyframes must still get through, or the picture never recovers: $stats",
        )
        harness.close()
    }

    @Test
    fun `the default policy never drops before decoding however far behind it is`() = runTest {
        val harness = CoreHarness(
            this,
            script = script,
            faults = slowDecoder(),
            config = PlayerConfig(frameDrop = FrameDropPolicy.LateOnly),
        )
        harness.open()
        harness.core.play()
        harness.run(8.seconds)

        val stats = harness.core.stats.value
        assertEquals(
            0L,
            stats.droppedFramesDecode,
            "LateOnly drops after the decoder, never before it: $stats",
        )
        assertTrue(stats.decodedVideoFrames > 0, "and it still decodes: $stats")
        harness.close()
    }

    @Test
    fun `a decoder that keeps up drops nothing before decoding`() = runTest {
        val harness = CoreHarness(
            this,
            script = script,
            config = PlayerConfig(frameDrop = FrameDropPolicy.LateAndDecode),
        )
        harness.open()
        harness.core.play()
        harness.run(8.seconds)

        val stats = harness.core.stats.value
        assertEquals(
            0L,
            stats.droppedFramesDecode,
            "nothing was late, so the policy must cost nothing: $stats",
        )
        harness.close()
    }
}
