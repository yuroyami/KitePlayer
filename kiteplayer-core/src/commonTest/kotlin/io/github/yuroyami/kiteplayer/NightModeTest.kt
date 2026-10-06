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

/**
 * The dialogue level through the whole engine (#442): a 5.1 file whose centre alone carries sound,
 * folded into a stereo device, is heard 6 dB louder once the level is raised by 6 dB.
 */
class DialogueLevelEngineTest {

    private suspend fun leftPeak(db: Float, scope: kotlinx.coroutines.test.TestScope): Float {
        val harness = CoreHarness(
            scope,
            script = MediaScript(durationUs = 3_000_000, channels = 6, audioChannelMarkers = listOf(0f, 0f, 0.1f, 0f, 0f, 0f)),
            sinkAccepts = io.github.yuroyami.kiteplayer.spi.AudioFormat(48_000, 2, io.github.yuroyami.kiteplayer.spi.SampleFormat.F32),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(50.milliseconds)
        harness.core.post(CoreCommand.SetDialogueLevel(db, CompletableDeferred()))
        harness.run(600.milliseconds)
        harness.sink.clearChannelPeaks()
        harness.run(200.milliseconds)
        assertEquals(db, harness.core.snapshots.value.dialogueLevelDb)
        val result = harness.sink.channelPeak(0)
        harness.close()
        return result
    }

    @Test
    fun aRaisedDialogueLevelIsHeardInTheFoldedCentre() = runTest {
        val plain = leftPeak(0f, this)
        assertTrue(plain > 0f, "the folded centre was not heard at all")
        val raised = leftPeak(6f, this)
        assertEquals(1.995f, raised / plain, absoluteTolerance = 0.01f, message = "the centre went from $plain to $raised")
    }
}

/**
 * The pitch through the whole engine (#465): it changes no timing, so the clock after two seconds
 * reads what it reads with no pitch, at speed 1 and at 1.5, and the snapshot names it.
 */
class PitchEngineTest {

    private suspend fun positionAfter(semitones: Double, speed: Double, scope: kotlinx.coroutines.test.TestScope): Long {
        val harness = CoreHarness(scope, script = MediaScript(durationUs = 8_000_000))
        harness.openWithRenderer()
        harness.core.post(CoreCommand.SetPitch(semitones, CompletableDeferred()))
        harness.core.setSpeed(speed)
        harness.core.play()
        harness.run(2_000.milliseconds)
        assertEquals(semitones, harness.core.snapshots.value.pitchSemitones)
        val position = harness.core.position().inWholeMilliseconds
        harness.close()
        return position
    }

    @Test
    fun thePitchMovesNoTiming() = runTest {
        for (speed in listOf(1.0, 1.5)) {
            val plain = positionAfter(0.0, speed, this)
            val shifted = positionAfter(12.0, speed, this)
            assertTrue(kotlin.math.abs(shifted - plain) <= 60, "at speed $speed the clock read $shifted ms with the pitch and $plain without")
            assertTrue(plain > 1_000, "at speed $speed the clock never moved: $plain ms")
        }
    }
}
