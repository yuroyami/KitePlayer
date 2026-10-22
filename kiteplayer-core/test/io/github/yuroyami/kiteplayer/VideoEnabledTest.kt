@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Parking video decoding in place, without reopening anything.
 *
 * The only way to stop the video lane spending CPU was to deselect the track, and that reopens the
 * container and seeks back to where playback was. An application going to the background wants the
 * decoder to stop NOW and the picture to come back on return, and it wants neither of those to cost
 * a reopen: on a network source a reopen is a fresh request, and on any source it is a seek the
 * user did not ask for.
 *
 * So the packets are discarded before the decoder instead. Nothing else changes: audio keeps
 * playing, subtitles keep timing, the container stays open on the same read position.
 *
 * The counter that proves it is `decodedVideoFrames`, because it counts frames that came OUT of the
 * decoder. A parked lane must leave it perfectly still while the clock keeps moving, which is the
 * pair of facts no single assertion can fake.
 */
class VideoEnabledTest {

    private suspend fun CoreHarness.setVideoEnabled(enabled: Boolean) {
        core.post(CoreCommand.SetVideoEnabled(enabled, CompletableDeferred()))
        run(20.milliseconds)
    }

    @Test
    fun `parking stops the decoder while the clock keeps moving`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 5_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        val opensBefore = harness.backend.openCalls
        harness.setVideoEnabled(false)
        // One settling stretch: packets already inside the decoder still come out, and parking the
        // lane cannot un-decode them.
        harness.run(200.milliseconds)

        val framesAtPark = harness.core.stats.value.decodedVideoFrames
        val positionAtPark = harness.core.position()
        harness.run(500.milliseconds)

        assertEquals(
            framesAtPark,
            harness.core.stats.value.decodedVideoFrames,
            "a parked lane decoded more frames",
        )
        assertTrue(
            harness.core.position() > positionAtPark,
            "the clock stopped when only the video lane was asked to",
        )
        assertEquals(
            opensBefore,
            harness.backend.openCalls,
            "parking reopened the container, which is the whole thing it exists to avoid",
        )
        harness.close()
    }

    @Test
    fun `resuming brings the decoder back`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 5_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)
        harness.setVideoEnabled(false)
        harness.run(300.milliseconds)

        val parked = harness.core.stats.value.decodedVideoFrames
        val opensBefore = harness.backend.openCalls
        harness.setVideoEnabled(true)
        harness.run(500.milliseconds)

        assertTrue(
            harness.core.stats.value.decodedVideoFrames > parked,
            "the decoder did not come back: still at $parked frames",
        )
        assertEquals(opensBefore, harness.backend.openCalls, "resuming reopened the container")
        harness.close()
    }

    @Test
    fun `the snapshot says which way it is`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 5_000_000))
        harness.openWithRenderer()
        harness.run(50.milliseconds)
        assertTrue(harness.core.snapshots.value.videoEnabled, "video starts enabled")
        harness.setVideoEnabled(false)
        assertEquals(false, harness.core.snapshots.value.videoEnabled)
        harness.setVideoEnabled(true)
        assertEquals(true, harness.core.snapshots.value.videoEnabled)
        harness.close()
    }

    @Test
    fun `a player configured without video opens parked`() = runTest {
        // For an application that knows from the start it is only playing sound: no first frame is
        // decoded at all, rather than one decoded and then thrown away.
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 5_000_000),
            config = PlayerConfig(videoEnabled = false),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)

        assertEquals(
            0L,
            harness.core.stats.value.decodedVideoFrames,
            "a player opened with video disabled decoded a frame anyway",
        )
        assertEquals(false, harness.core.snapshots.value.videoEnabled)
        assertTrue(harness.core.position().inWholeMilliseconds > 0, "audio did not play either")
        harness.close()
    }

    @Test
    fun `parking is not a frame drop`() = runTest {
        // The drop counters mean the engine could not keep up. Parking is a decision, so counting
        // it as a drop would turn every backgrounded app into a performance bug report.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 5_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)
        harness.setVideoEnabled(false)
        val dropsBefore = harness.core.stats.value.droppedFramesDecode
        harness.run(500.milliseconds)
        assertEquals(
            dropsBefore,
            harness.core.stats.value.droppedFramesDecode,
            "parking was counted as a decode drop",
        )
        harness.close()
    }

}
