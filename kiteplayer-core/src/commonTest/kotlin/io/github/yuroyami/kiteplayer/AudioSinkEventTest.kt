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

    /**
     * The other half of the contract, closed on 2026-08-27: the sink was already
     * telling the engine these things and the engine threw them away with `else -> Unit`. A
     * device-reported underrun now warns once per session, typed; a format-change request is
     * surfaced as a device warning; and neither fails the player or tears the sink down, because
     * the engine still cannot renegotiate a device, which stays open in MASTER_PLAN.md.
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
}
