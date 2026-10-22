package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A video track change at position zero rebuilds a session that plays, not one that drops everything (#277). */
class TrackChangeAtZeroTest {

    @Test
    fun disablingVideoAtZeroLeavesTheSoundPlaying() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Video, null))
        harness.core.play()
        harness.run(2.seconds)
        val position = harness.core.position()
        assertTrue(position >= 1.seconds, "the sound must play after the change, position $position")
        harness.close()
    }

    @Test
    fun reselectingVideoAtZeroPresentsFrames() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Video, null))
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Video, TrackId(0)))
        val presentedBefore = harness.renderer!!.presentations.size
        harness.core.play()
        harness.run(2.seconds)
        val presented = harness.renderer!!.presentations.size - presentedBefore
        assertTrue(presented >= 20, "frames must reach the renderer after the change, got $presented")
        assertTrue(harness.core.position() >= 1.seconds, "and the position must move")
        harness.close()
    }
}
