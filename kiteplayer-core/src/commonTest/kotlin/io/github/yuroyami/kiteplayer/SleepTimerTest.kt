@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Stopping playback later, and getting quieter on the way.
 *
 * Every media application writes this and most of them write it badly: a bare `delay` then
 * `pause()` cuts the sound off mid-word, and one that fades by driving the public volume down
 * leaves the user at volume zero the next morning.
 *
 * So the fade is the engine's own. It rides the ring's gain, which is where the volume already
 * lives, and it never touches the volume the user set: the published value is unchanged
 * throughout, and after the pause the level is back where it was, ready for the next play.
 */
class SleepTimerTest {

    private suspend fun CoreHarness.setTimer(timer: SleepTimer?, fade: kotlin.time.Duration = 3.seconds) {
        core.post(CoreCommand.SetSleepTimer(timer, fade, CompletableDeferred()))
        run(20.milliseconds)
    }

    private suspend fun playing(scope: kotlinx.coroutines.test.TestScope, durationUs: Long = 30_000_000): CoreHarness {
        val harness = CoreHarness(scope, script = MediaScript(durationUs = durationUs))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)
        return harness
    }

    @Test
    fun `a timer pauses when it fires and leaves the volume where it was`() = runTest {
        val harness = playing(this)
        harness.setTimer(SleepTimer.After(4.seconds), fade = 1.seconds)

        harness.run(2.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "it paused far too early")

        harness.run(4.seconds)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "the timer never fired")
        assertEquals(1f, harness.core.snapshots.value.volume, "the fade was left in the published volume")
        assertNull(harness.core.snapshots.value.sleepTimer, "a fired timer stayed armed")
        harness.close()
    }

    @Test
    fun `pushing the timer back during its fade brings the sound back up`() = runTest {
        // Extending a timer is the ordinary case: the film turned out better than expected. The
        // old fade's multiplier was left on the ring, so the extension played on at a quarter
        // of the volume with nothing to show why.
        val harness = playing(this)
        harness.setTimer(SleepTimer.After(3.seconds), fade = 2.seconds)
        harness.run(2500.milliseconds)
        harness.setTimer(SleepTimer.After(20.seconds), fade = 2.seconds)
        harness.sink.clearChannelPeaks()
        harness.run(300.milliseconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "the extension paused")
        val peak = harness.sink.channelPeak(0)
        assertTrue(peak > 0.9f, "the old fade stayed in force: peak $peak")
        harness.close()
    }

    @Test
    fun `the sound gets quieter before it stops`() = runTest {
        // The point of the feature. Without this arm the timer is a delay and a pause, which is
        // what every application already writes for itself.
        val harness = playing(this)
        harness.setTimer(SleepTimer.After(3.seconds), fade = 2.seconds)
        harness.run(2.seconds)

        // Mid-fade: what the device is being handed must be below full scale and above silence.
        harness.sink.clearChannelPeaks()
        harness.run(500.milliseconds)
        val midFade = harness.sink.channelPeak(0)
        assertTrue(
            midFade in 0.02f..0.9f,
            "expected a partly faded level while the timer was counting down, heard $midFade",
        )

        harness.run(3.seconds)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun `cancelling before it fires changes nothing`() = runTest {
        val harness = playing(this)
        harness.setTimer(SleepTimer.After(3.seconds), fade = 1.seconds)
        harness.run(500.milliseconds)
        harness.setTimer(null)
        harness.run(5.seconds)

        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "a cancelled timer still fired")
        assertNull(harness.core.snapshots.value.sleepTimer)
        harness.sink.clearChannelPeaks()
        harness.run(300.milliseconds)
        assertTrue(
            harness.sink.channelPeak(0) > 0.9f,
            "a cancelled timer left the sound faded at ${harness.sink.channelPeak(0)}",
        )
        harness.close()
    }

    @Test
    fun `a timer at a media position fires there`() = runTest {
        val harness = playing(this)
        harness.setTimer(SleepTimer.At(5.seconds), fade = 500.milliseconds)
        harness.run(2.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.run(5.seconds)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "the position timer never fired")
        harness.close()
    }

    @Test
    fun `the snapshot carries the armed timer`() = runTest {
        val harness = playing(this)
        assertNull(harness.core.snapshots.value.sleepTimer)
        harness.setTimer(SleepTimer.After(10.seconds))
        assertEquals(SleepTimer.After(10.seconds), harness.core.snapshots.value.sleepTimer)
        harness.setTimer(null)
        assertNull(harness.core.snapshots.value.sleepTimer)
        harness.close()
    }

    @Test
    fun `an impossible fade is refused`() = runTest {
        val harness = playing(this)
        val reply = CompletableDeferred<Unit>()
        harness.core.post(CoreCommand.SetSleepTimer(SleepTimer.After(5.seconds), (-1).seconds, reply))
        harness.run(20.milliseconds)
        var threw = false
        try {
            reply.await()
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "a negative fade was accepted")
        assertNull(harness.core.snapshots.value.sleepTimer, "a refused timer was armed anyway")
        harness.close()
    }
}
