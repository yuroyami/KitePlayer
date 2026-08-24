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
     * The other half of the contract, and the half a reader is most likely to get wrong: these two
     * are collected and DROPPED. Nothing counts an underrun and nothing rebuilds a sink on a format
     * request. When SOL-A6 changes that, this test is what tells you it changed.
     */
    @Test
    fun `underrun and format-change requests are read and dropped`() = runTest {
        val harness = CoreHarness(this, publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        harness.sink.publish(AudioSinkEvent.Underrun("ran dry"))
        harness.sink.publish(AudioSinkEvent.FormatChangeRequested("wants 48000 stereo"))
        harness.run(100.milliseconds)

        assertEquals(emptyList(), harness.deviceWarnings(), "neither event produces a device warning")
        assertTrue(harness.core.snapshots.value.status != PlaybackStatus.Failed, "and neither fails the player")
        assertEquals(0, harness.sink.stopCount, "and the sink is not torn down for either")
        harness.close()
    }
}
