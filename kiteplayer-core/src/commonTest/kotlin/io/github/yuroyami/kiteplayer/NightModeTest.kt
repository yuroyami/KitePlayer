package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The night mode through the whole engine, heard at the device (#442). The scripted sound is a
 * steady level at -30 dBFS, quiet speech, which the mode lifts. As with the balance, a change is
 * heard once the ring's depth has played.
 */
class NightModeTest {

    private suspend fun peak(night: Boolean, scope: kotlinx.coroutines.test.TestScope): Float {
        val harness = CoreHarness(scope, script = MediaScript(durationUs = 4_000_000, audioChannelMarkers = listOf(QUIET, QUIET)))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(50.milliseconds)
        harness.core.post(CoreCommand.SetNightMode(night, CompletableDeferred()))
        harness.run(1_500.milliseconds)
        harness.sink.clearChannelPeaks()
        harness.run(200.milliseconds)
        assertEquals(night, harness.core.snapshots.value.nightMode)
        val result = harness.sink.channelPeak(0)
        harness.close()
        return result
    }

    @Test
    fun quietSoundIsLiftedOnlyWhileTheModeIsOn() = runTest {
        val off = peak(night = false, this)
        assertEquals(QUIET, off, absoluteTolerance = 0.0005f, message = "the mode acted while off")
        val on = peak(night = true, this)
        assertTrue(on > off * 2f, "the night mode lifted $off only to $on")
    }

    private companion object {
        const val QUIET = 0.0316f
    }
}
