package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The stereo mode through the whole engine, heard at the device (#462). The scripted left side
 * plays at full scale and the right at a quarter of it, so where each side went reads off the two
 * channel peaks. As with the balance, a change is heard once the ring's depth has played.
 */
class StereoModeTest {

    private suspend fun peaks(mode: StereoMode, scope: kotlinx.coroutines.test.TestScope): Pair<Float, Float> {
        val harness = CoreHarness(scope, script = MediaScript(durationUs = 3_000_000, audioChannelMarkers = listOf(1f, 0.25f)))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(50.milliseconds)
        harness.core.post(CoreCommand.SetStereoMode(mode, CompletableDeferred()))
        harness.run(400.milliseconds)
        harness.sink.clearChannelPeaks()
        harness.run(200.milliseconds)
        assertEquals(mode, harness.core.snapshots.value.stereoMode)
        val result = harness.sink.channelPeak(0) to harness.sink.channelPeak(1)
        harness.close()
        return result
    }

    private fun assertSides(left: Float, right: Float, heard: Pair<Float, Float>, mode: StereoMode) {
        assertEquals(left, heard.first, absoluteTolerance = 0.002f, message = "$mode left: $heard")
        assertEquals(right, heard.second, absoluteTolerance = 0.002f, message = "$mode right: $heard")
    }

    @Test
    fun eachModeIsHeardOnTheSidesItNames() = runTest {
        val stereo = peaks(StereoMode.Stereo, this)
        val full = stereo.first
        assertSides(full, full * 0.25f, stereo, StereoMode.Stereo)
        assertSides(full * 0.625f, full * 0.625f, peaks(StereoMode.Mono, this), StereoMode.Mono)
        assertSides(full, full, peaks(StereoMode.LeftOnly, this), StereoMode.LeftOnly)
        assertSides(full * 0.25f, full * 0.25f, peaks(StereoMode.RightOnly, this), StereoMode.RightOnly)
        assertSides(full * 0.25f, full, peaks(StereoMode.Swapped, this), StereoMode.Swapped)
    }
}
