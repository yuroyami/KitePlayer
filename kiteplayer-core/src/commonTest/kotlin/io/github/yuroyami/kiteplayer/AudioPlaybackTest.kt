package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.MixLayout
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A device that accepts a format of the test's choosing and plays nothing.
 *
 * It never calls the render callback, so the ring only ever fills. That is what these cases need: what
 * is under test is what reaches the ring and what the rate setter allows, not the device.
 */
private class FakeAudioSink(
    /** What this device takes, whatever it is offered. Null means it takes what it is given. */
    private val accepts: AudioFormat? = null,
) : AudioSink {
    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat =
        accepts ?: request

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun drain() = Unit
    override suspend fun setPaused(paused: Boolean): Boolean = true
    override val deviceBufferFrames: Int = 512
    override fun latencyNanos(): Long = 0
    override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
    override val events: Flow<AudioSinkEvent> = emptyFlow()
    override fun close() = Unit
}

/**
 * The audio half of the engine, on the two things phase A4 changed about it: the rate is no longer a
 * lie, and decoded audio can reach the ring in a format the device never agreed to.
 */
class AudioPlaybackTest {

    private fun format(channels: Int, rate: Int = 48_000, mask: Long? = null) = AudioFormat(
        sampleRate = rate,
        channels = channels,
        sampleFormat = SampleFormat.F32,
        channelLayoutMask = mask,
    )

    @Test
    fun `a rate other than one is refused while audio is open`() = runTest {
        val audio = AudioPlayback(FakeAudioSink(), TestClock())
        audio.open(format(2))

        val failure = assertFailsWith<UnsupportedOperationException> { audio.speed = 2.0 }
        assertTrue(
            failure.message?.contains("tempo") == true,
            "the message must say why rather than just refusing: ${failure.message}",
        )
        assertEquals(1.0, audio.speed, "a refused rate leaves the clock where it was")

        // The one rate that is honest stays legal, so a caller resetting to normal is not an error.
        audio.speed = 1.0
        assertEquals(1.0, audio.speed)
        audio.close()
    }

    @Test
    fun `a rate other than one is legal with no audio path open`() = runTest {
        // Video-only playback: with no ring there is no device whose rate the clock could contradict,
        // so storing the value is not a pretence. It is not a working speed control either, which is
        // why the assertion is about the setter and not about playback.
        val audio = AudioPlayback(FakeAudioSink(), TestClock())
        audio.speed = 2.0
        assertEquals(2.0, audio.speed)

        audio.speed = 0.5
        assertEquals(0.5, audio.speed)
        audio.close()
    }

    @Test
    fun `a closed audio path takes a rate again`() = runTest {
        val audio = AudioPlayback(FakeAudioSink(), TestClock())
        audio.open(format(2))
        audio.close()

        audio.speed = 1.5
        assertEquals(1.5, audio.speed, "the ring is gone, so there is nothing left to lie about")
    }

    @Test
    fun `submitDecoded downmixes into the format the device took`() = runTest {
        // A 5.1 file on a stereo device, which is the case that used to reach the ring as garbage:
        // six channels of samples read as three frames of two.
        val audio = AudioPlayback(FakeAudioSink(accepts = format(2)), TestClock())
        val negotiated = audio.open(format(6, mask = MixLayout.Surround51Side.mask))
        assertEquals(2, negotiated.channels)

        audio.submitDecoded(pts(0), FloatArray(480 * 6), 480, format(6, mask = MixLayout.Surround51Side.mask))

        assertEquals(
            10,
            audio.buffered.inWholeMilliseconds,
            "480 frames at 48 kHz is 10 ms, whatever the source channel count was",
        )
        audio.close()
    }

    @Test
    fun `submitDecoded converts the rate the device refused`() = runTest {
        val audio = AudioPlayback(FakeAudioSink(accepts = format(2, rate = 48_000)), TestClock())
        audio.open(format(2, rate = 44_100))

        audio.submitDecoded(pts(0), FloatArray(441 * 2), 441, format(2, rate = 44_100))

        assertEquals(
            10,
            audio.buffered.inWholeMilliseconds,
            "441 frames at 44.1 kHz are 480 at 48 kHz, which is the same 10 ms of audio",
        )
        audio.close()
    }

    @Test
    fun `submitDecoded follows the decoder through a format change`() = runTest {
        val audio = AudioPlayback(FakeAudioSink(accepts = format(2)), TestClock())
        audio.open(format(6, mask = MixLayout.Surround51Side.mask))

        val surround = format(6, mask = MixLayout.Surround51Side.mask)
        audio.submitDecoded(pts(0), FloatArray(240 * 6), 240, surround)

        // The stream switches to stereo mid-file. The stage has to be rebuilt on this buffer, not on
        // the one after it, or 240 frames of stereo are read as 120 frames of 5.1.
        val stereo = format(2, mask = MixLayout.Stereo.mask)
        audio.submitDecoded(null, FloatArray(240 * 2), 240, stereo)

        assertEquals(
            10,
            audio.buffered.inWholeMilliseconds,
            "480 frames of stereo reached the ring, which is 10 ms at 48 kHz",
        )
        audio.close()
    }
}
