package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.launch
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

    /** A device-reported underrun warns once per session, typed, and neither fails the player nor stops the sink. */
    @Test
    fun `a device underrun warns once and the sink keeps playing`() = runTest {
        val harness = CoreHarness(this, publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        harness.sink.publish(AudioSinkEvent.Underrun("ran dry"))
        harness.run(50.milliseconds)
        harness.sink.publish(AudioSinkEvent.Underrun("ran dry again"))
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
        assertTrue(harness.core.snapshots.value.status != PlaybackStatus.Failed, "it does not fail the player")
        assertEquals(0, harness.sink.stopCount, "and the sink is not torn down for it")
        harness.close()
    }

    private val stereo = AudioFormat(48_000, 2, SampleFormat.F32)

    /** A 5.1 item whose centre alone carries sound, which is where a film keeps its dialogue. */
    private fun centreOnly() =
        MediaScript(durationUs = 10_000_000, channels = 6, audioChannelMarkers = listOf(0f, 0f, 0.1f, 0f, 0f, 0f))

    private fun CoreHarness.formats(): List<Int> =
        events.filterIsInstance<PlayerEvent.AudioFormatChanged>().map { it.channels }

    /**
     * The sound moves from a receiver to two speakers (#563). The six channel output is replaced by
     * a two channel one, and the centre, which had a speaker of its own, is folded into both.
     */
    @Test
    fun `an output that asks for another format is replaced and the surround folds into the new one`() = runTest {
        val harness = CoreHarness(this, script = centreOnly(), publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        assertTrue(harness.sink.channelPeak(2) > 0f, "the centre plays from its own speaker on the receiver")
        assertEquals(0f, harness.sink.channelPeak(0))

        val speakers = ScriptedSink(accepts = stereo, publishesEvents = true)
        speakers.recordsSamples = true
        harness.output.laterSinks += speakers
        backgroundScope.launch { speakers.runDevice(harness.clock) }
        val before = harness.core.position().inWholeMilliseconds
        val picturesBefore = harness.renderer!!.presentations.size
        harness.sink.publish(AudioSinkEvent.FormatChangeRequested("the output now takes 2 channels"))
        harness.run(1_000.milliseconds)

        assertTrue(harness.sink.closed, "the old output is closed, not changed in place")
        assertEquals(1, speakers.openCount)
        assertEquals(6, speakers.openRequests.single().channels, "the new output is asked for what the sound has")
        assertTrue(speakers.isRunning, "the new output plays")
        assertEquals(listOf(6, 2), harness.formats(), "the application hears of the new format")
        assertEquals(0.0707f, speakers.channelPeak(0), absoluteTolerance = 0.001f, message = "the centre folds into the left speaker at 3 dB down")
        assertEquals(0.0707f, speakers.channelPeak(1), absoluteTolerance = 0.001f, message = "and into the right one")
        val moved = harness.core.position().inWholeMilliseconds - before
        assertTrue(moved in 900..1_100, "the clock runs on through the change, and moved $moved ms in one second")
        assertTrue(harness.renderer!!.presentations.size > picturesBefore + 10, "the picture plays on")
        // The old ring held sound nobody heard. The new one starts with a silence of that length,
        // written by the engine and not an underrun, and then the sound goes on.
        val heard = speakers.recorded.toFloatArray()
        val quiet = heard.indexOfFirst { it != 0f }
        assertTrue(quiet in 2_400..24_000, "the new output starts with a short silence, and it was $quiet frames")
        assertTrue(heard.drop(quiet + 480).all { it != 0f }, "and no hole follows it")
        assertEquals(0L, speakers.silenceFrames, "the new output never ran dry")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        assertTrue(harness.deviceWarnings().isEmpty(), "a change that worked is an event and no warning")
        harness.close()
    }

    /** The other way: headphones first, then a receiver, and the centre gets its own speaker back. */
    @Test
    fun `an output with more speakers gets the channels a stereo one had folded`() = runTest {
        val harness = CoreHarness(this, script = centreOnly(), publishesSinkEvents = true, sinkAccepts = stereo)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        assertTrue(harness.sink.channelPeak(0) > 0f, "the centre is folded into the headphones")

        val receiver = ScriptedSink(publishesEvents = true)
        harness.output.laterSinks += receiver
        backgroundScope.launch { receiver.runDevice(harness.clock) }
        harness.sink.publish(AudioSinkEvent.FormatChangeRequested("the output now takes 6 channels"))
        harness.run(1_000.milliseconds)

        assertTrue(harness.sink.closed)
        assertEquals(listOf(2, 6), harness.formats())
        assertEquals(0.1f, receiver.channelPeak(2), absoluteTolerance = 0.001f, message = "the centre plays whole from its own speaker")
        assertEquals(0f, receiver.channelPeak(0), "and no longer from the front pair")
        harness.close()
    }

    /** A second request reaches the output that replaced the first, so the sound can move twice. */
    @Test
    fun `the new output is listened to and can be replaced in turn`() = runTest {
        val harness = CoreHarness(this, script = centreOnly(), publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)

        val speakers = ScriptedSink(accepts = stereo, publishesEvents = true)
        val receiver = ScriptedSink(publishesEvents = true)
        harness.output.laterSinks += speakers
        backgroundScope.launch { speakers.runDevice(harness.clock) }
        backgroundScope.launch { receiver.runDevice(harness.clock) }
        harness.sink.publish(AudioSinkEvent.FormatChangeRequested("the output now takes 2 channels"))
        harness.run(600.milliseconds)
        harness.output.laterSinks += receiver
        speakers.publish(AudioSinkEvent.FormatChangeRequested("the output now takes 6 channels"))
        harness.run(600.milliseconds)

        assertEquals(listOf(6, 2, 6), harness.formats())
        assertTrue(speakers.closed)
        assertTrue(receiver.isRunning)
        harness.close()
    }

    /** A request while paused opens the new output and leaves it silent until play. */
    @Test
    fun `a paused player changes its output and stays paused`() = runTest {
        val harness = CoreHarness(this, script = centreOnly(), publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(400.milliseconds)
        harness.core.pause()
        harness.run(200.milliseconds)
        val at = harness.core.position().inWholeMilliseconds

        val speakers = ScriptedSink(accepts = stereo, publishesEvents = true)
        harness.output.laterSinks += speakers
        backgroundScope.launch { speakers.runDevice(harness.clock) }
        harness.sink.publish(AudioSinkEvent.FormatChangeRequested("the output now takes 2 channels"))
        harness.run(500.milliseconds)

        assertEquals(listOf(6, 2), harness.formats())
        assertTrue(!speakers.isRunning, "nothing plays while the player is paused")
        assertEquals(at, harness.core.position().inWholeMilliseconds, "and the position stays where it was")

        harness.core.play()
        harness.run(600.milliseconds)
        assertTrue(speakers.isRunning)
        assertTrue(speakers.channelPeak(0) > 0f, "play is heard from the new output")
        assertTrue(harness.core.position().inWholeMilliseconds > at + 400)
        harness.close()
    }

    /** A route that still takes the format the output opened with costs a look and nothing else. */
    @Test
    fun `a new output that takes the same format is closed again and nothing changes`() = runTest {
        val harness = CoreHarness(this, script = centreOnly(), publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)

        val same = ScriptedSink(publishesEvents = true)
        harness.output.laterSinks += same
        harness.sink.publish(AudioSinkEvent.FormatChangeRequested("the output now takes 6 channels"))
        harness.run(300.milliseconds)

        assertEquals(1, same.openCount, "the new output was opened to see what it takes")
        assertTrue(same.closed, "and closed, because it takes what the old one took")
        assertTrue(!harness.sink.closed && harness.sink.isRunning, "the old output plays on")
        assertEquals(0, harness.sink.stopCount)
        assertEquals(listOf(6), harness.formats())
        assertTrue(harness.deviceWarnings().isEmpty())
        harness.close()
    }

    /** No new output opens: the old one keeps playing, and the application is told. */
    @Test
    fun `a new output that does not open leaves the old one playing and warns`() = runTest {
        val harness = CoreHarness(this, script = centreOnly(), publishesSinkEvents = true)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)

        val broken = FaultPlan().apply { sinkOpenFails = true }
        harness.output.laterSinks += ScriptedSink(accepts = stereo, faults = broken, publishesEvents = true)
        val before = harness.core.position().inWholeMilliseconds
        harness.sink.publish(AudioSinkEvent.FormatChangeRequested("the output now takes 2 channels"))
        harness.run(500.milliseconds)

        assertEquals(
            listOf(
                "the device requested a format change: the output now takes 2 channels, " +
                    "and no new output opened: the scripted device refuses to open",
            ),
            harness.deviceWarnings(),
        )
        assertTrue(harness.sink.isRunning, "the old output plays on")
        assertEquals(listOf(6), harness.formats())
        assertTrue(harness.core.position().inWholeMilliseconds - before in 400..600)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
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
