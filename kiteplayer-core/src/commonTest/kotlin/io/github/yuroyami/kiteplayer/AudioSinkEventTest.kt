package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the engine actually does with the sink's event feed, per event.
 *
 * This path shipped unexercised: every fixture published `emptyFlow()`, so the collector in
 * `PlaybackCore` had never received anything, and `spi/AudioSink.kt` described four behaviours that
 * disagreed with the code and with each other. The KDoc now states the mapping, and this suite is
 * what makes that statement falsifiable.
 */
class AudioSinkEventTest {

    private fun CoreHarness.deviceWarnings(): List<String> =
        events.filterIsInstance<PlayerEvent.Warning>()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.AudioDeviceChanged>()
            .map { it.detail }

    @Test
    fun `device loss and device change both become warnings carrying the sink detail`() = runTest {
        val harness = CoreHarness(this, publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        harness.sink.publish(AudioSinkEvent.DeviceLost("headphones unplugged"))
        harness.run(50.milliseconds)
        harness.sink.publish(AudioSinkEvent.DeviceChanged("default output is now the display"))
        harness.run(50.milliseconds)

        assertEquals(
            listOf("device lost: headphones unplugged", "default output is now the display"),
            harness.deviceWarnings(),
            "both device events are owed a warning, in order, with the detail the sink gave",
        )
        harness.close()
    }

    /** The engine passes the notice on with the transport mark and leaves the pause to the media session (#503). */
    @Test
    fun `sound that became loud is passed on with the transport mark and playback goes on`() = runTest {
        val harness = CoreHarness(this, publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.noteTransport()
        harness.core.play()
        harness.run(200.milliseconds)
        val mark = harness.core.transportMark

        harness.sink.publish(AudioSinkEvent.BecameNoisy(atNanos = harness.clock.nanos()))
        harness.run(50.milliseconds)

        assertEquals(listOf(mark), harness.events.filterIsInstance<PlayerEvent.AudioOutputBecameNoisy>().map { it.transportMark })
        assertTrue(harness.core.snapshots.value.playRequested, "pausing is the policy of the session and the engine keeps playing")
        assertTrue(harness.deviceWarnings().isEmpty(), "it is an event, not a warning")
        harness.close()
    }

    /**
     * The sink's notice can arrive late. One from before the listener's last play or pause would
     * pause a play the listener made after the headphones left, so it is dropped, and a tie goes
     * to the listener.
     */
    @Test
    fun `a notice that is not newer than the last transport command is dropped`() = runTest {
        val harness = CoreHarness(this, publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        val unplugged = harness.clock.nanos()
        harness.run(100.milliseconds)
        harness.core.noteTransport()
        harness.core.play()
        val pressed = harness.clock.nanos()
        harness.run(100.milliseconds)

        harness.sink.publish(AudioSinkEvent.BecameNoisy(atNanos = unplugged))
        harness.sink.publish(AudioSinkEvent.BecameNoisy(atNanos = pressed))
        harness.run(50.milliseconds)
        assertTrue(harness.events.none { it is PlayerEvent.AudioOutputBecameNoisy }, "an older notice and a tie are both stale")

        harness.sink.publish(AudioSinkEvent.BecameNoisy(atNanos = pressed + 1))
        harness.run(50.milliseconds)
        assertEquals(1, harness.events.count { it is PlayerEvent.AudioOutputBecameNoisy }, "a newer one is passed on")
        harness.close()
    }

    /**
     * The other half of the contract, closed on 2026-08-27: the sink was already
     * telling the engine these things and the engine threw them away with `else -> Unit`. A
     * device-reported underrun now warns once per session, typed; a format-change request is
     * surfaced as a device warning; and neither fails the player or tears the sink down, because
     * the engine still cannot renegotiate a device, which stays open.
     */
    @Test
    fun `underrun and format-change requests are surfaced rather than dropped`() = runTest {
        val harness = CoreHarness(this, publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        harness.sink.publish(AudioSinkEvent.Underrun("ran dry"))
        harness.run(50.milliseconds)
        harness.sink.publish(AudioSinkEvent.Underrun("ran dry again"))
        harness.run(50.milliseconds)
        harness.sink.publish(AudioSinkEvent.FormatChangeRequested("wants 48000 stereo"))
        harness.run(100.milliseconds)

        val deviceUnderruns = harness.events
            .filterIsInstance<PlayerEvent.Warning>()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.AudioDeviceUnderrun>()
            .map { it.detail }
        assertEquals(
            listOf("ran dry"),
            deviceUnderruns,
            "the first device-reported underrun warns once per session, repeats stay silent",
        )
        assertEquals(
            listOf("the device requested a format change: wants 48000 stereo"),
            harness.deviceWarnings(),
            "a format-change request is a device condition the caller must hear about",
        )
        assertTrue(harness.core.snapshots.value.status != PlaybackStatus.Failed, "neither event fails the player")
        assertEquals(0, harness.sink.stopCount, "and the sink is not torn down for either")
        harness.close()
    }

    /** A bound device that is gone: the sink cannot play again, so the player fails with its error. */
    @Test
    fun `a sink failure stops the session and fails the player with the sink's error`() = runTest {
        val harness = CoreHarness(this, publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        val error = PlaybackError.AudioDeviceUnavailable("usb-dac", "the output device USB DAC is gone")
        harness.sink.publish(AudioSinkEvent.Failed(error))
        harness.run(100.milliseconds)

        val snapshot = harness.core.snapshots.value
        assertEquals(PlaybackStatus.Failed, snapshot.status)
        assertEquals(error, snapshot.error, "the player fails with the sink's own error")
        assertEquals(listOf(error), harness.events.filterIsInstance<PlayerEvent.Failed>().map { it.error })
        assertTrue(harness.sink.closed, "the session is stopped, and its sink with it")
        assertEquals(emptyList(), harness.deviceWarnings(), "a failure is not also a warning")
        harness.close()
    }
}
