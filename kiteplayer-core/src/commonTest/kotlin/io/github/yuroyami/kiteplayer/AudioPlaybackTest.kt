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
    fun `a rate within the tempo range is accepted while audio is open`() = runTest {
        val audio = AudioPlayback(FakeAudioSink(), TestClock())
        audio.open(format(2))

        // A real tempo stage sits in the pipeline now, so the setter's old open-path refusal
        // would itself be the lie. The value rules from the next flush, which is how the engine
        // routes every live change.
        audio.speed = 2.0
        assertEquals(2.0, audio.speed)
        audio.speed = 0.5
        assertEquals(0.5, audio.speed)

        // The honesty moved to the range boundary: outside it, splices dominate the signal.
        assertFailsWith<IllegalArgumentException> { audio.speed = 0.1 }
        assertFailsWith<IllegalArgumentException> { audio.speed = 8.0 }
        assertEquals(0.5, audio.speed, "a refused rate leaves the wanted rate where it was")
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

        // Two buffers: the rate conversion is a windowed filter and holds half a kernel, once, so
        // the first buffer is short by that and every one after it carries the exact ratio.
        audio.submitDecoded(pts(0), FloatArray(441 * 2), 441, format(2, rate = 44_100))
        audio.submitDecoded(null, FloatArray(441 * 2), 441, format(2, rate = 44_100))

        assertEquals(
            19,
            audio.buffered.inWholeMilliseconds,
            "882 frames at 44.1 kHz are 960 at 48 kHz, less the filter's one-off lookahead, which " +
                "is 20 ms of audio minus a third of a millisecond",
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

    @Test
    // No comma in the name: a backtick test name containing one is rejected by Kotlin/Native with
    // `Name contains illegal characters: ","`, which the JVM compiler accepts. This case is in
    // commonTest, so it is compiled for both and the stricter one decides.
    fun `close drops the ring before the sink releases it and every reader sees that`() = runTest {
        // The teardown order the independent verification of B1.8 found broken, from the engine's own
        // API. Before B1.8 the ring was a managed object and this ordering was cosmetic; now the ring
        // can be memory the sink frees inside `close`, and four public members that are documented safe
        // from any thread read it. What this case pins is the half a test can observe: at the instant the
        // sink releases the device, the reference is already gone and the readers answer their empty
        // values instead of reaching a ring that is being freed.
        //
        // What it cannot observe, stated so the evidence is not overread: a use-after-free. There is no
        // instrument for that in Kotlin/Native on this platform, and the ring here is a Kotlin object
        // anyway. That the hazard is real was proved at the C level with AddressSanitizer over
        // `kprt_ring_anchor` racing `kprt_sink_destroy`, and that the two are now ordered rests on the
        // reference being cleared inside the same lock every cross-thread reader takes, which is this
        // case plus the lock's own semantics.
        var readersDuringSinkClose: String? = null
        var audio: AudioPlayback? = null
        val sink = object : AudioSink {
            override suspend fun open(request: AudioFormat, render: AudioRenderCallback) = request
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override suspend fun drain() = Unit
            override suspend fun setPaused(paused: Boolean): Boolean = true
            override val deviceBufferFrames: Int = 512
            override fun latencyNanos(): Long = 0
            override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
            override val events: Flow<AudioSinkEvent> = emptyFlow()
            override fun close() {
                // Stands in for `kprt_sink_destroy`, which frees the C ring. Whatever the readers can
                // still see at this moment is what a real teardown would hand them.
                val player = audio ?: return
                readersDuringSinkClose =
                    "position=${player.position()} buffered=${player.buffered} underruns=${player.underruns}"
            }
        }

        val player = AudioPlayback(sink, TestClock())
        audio = player
        player.open(format(2))
        player.submit(pts(0), FloatArray(480 * 2), 480)
        assertEquals(10, player.buffered.inWholeMilliseconds, "the ring took the audio before the close")

        player.close()

        assertEquals(
            "position=null buffered=0s underruns=0",
            readersDuringSinkClose,
            "while the sink was releasing the ring, a reader still reached it",
        )
    }

    @Test
    fun `a submit that races a close fails loudly instead of touching a cleared ring`() = runTest {
        // Interlude item I-02. [submit] reads the ring FIELD under the same lock [close] clears it
        // under, so the two can interleave only in whole steps: a submit that loads the reference
        // before the clear writes into a ring the engine has not freed yet (the engine's join is
        // what guarantees that), and one that loads after the clear finds null and fails loudly.
        // What must never happen is the third outcome the old plain read allowed, a load torn
        // against the clear. This drives the pair through both orders; the C-level race below
        // Kotlin's visibility is TSan's and AddressSanitizer's to judge, and was.
        val audio = AudioPlayback(FakeAudioSink(), TestClock())
        audio.open(format(2))
        val samples = FloatArray(64 * 2)

        // Order one: submit completes, then close.
        audio.submit(pts = null, interleaved = samples, frames = 64)
        audio.close()

        // Order two: close first, then submit must fail loudly, not silently drop or corrupt.
        val late = AudioPlayback(FakeAudioSink(), TestClock())
        late.open(format(2))
        late.close()
        assertFailsWith<IllegalStateException> {
            late.submit(pts = null, interleaved = samples, frames = 64)
        }
    }

    @Test
    fun `a decoder that changes format mid-stream says so once`() = runTest {
        // The conversion half has always worked: the pipeline is keyed on the source format, so a
        // change rebuilds it on the buffer that changed. What did not exist was anyone being told.
        // A 48 kHz stream turning into a 44.1 kHz one was completely silent, and the only symptom
        // was a resampler appearing in a profile.
        val warnings = mutableListOf<PlaybackWarning>()
        val audio = AudioPlayback(FakeAudioSink(), TestClock(), onWarning = { warnings += it })
        audio.open(format(2))

        val frames = 128
        audio.submitDecoded(null, FloatArray(frames * 2), frames, format(2, 48_000))
        assertTrue(
            warnings.none { it is PlaybackWarning.AudioSourceFormatChanged },
            "the first buffer establishes the format; it did not change",
        )

        audio.submitDecoded(null, FloatArray(frames * 2), frames, format(2, 44_100))
        val changed = warnings.filterIsInstance<PlaybackWarning.AudioSourceFormatChanged>()
        assertEquals(1, changed.size, "one change, one warning, got $warnings")
        assertEquals(48_000, changed[0].fromSampleRate)
        assertEquals(44_100, changed[0].toSampleRate)
        assertEquals(2, changed[0].fromChannels)
        assertEquals(2, changed[0].toChannels)

        // A steady stream after the change must not keep warning: the pipeline now matches, and a
        // warning per buffer would bury every other warning a caller is listening for.
        repeat(3) { audio.submitDecoded(null, FloatArray(frames * 2), frames, format(2, 44_100)) }
        assertEquals(
            1,
            warnings.filterIsInstance<PlaybackWarning.AudioSourceFormatChanged>().size,
            "the warning repeated on buffers that did not change anything",
        )
        audio.close()
    }

    @Test
    fun `a channel count change is reported as well as a rate change`() = runTest {
        val warnings = mutableListOf<PlaybackWarning>()
        val audio = AudioPlayback(FakeAudioSink(), TestClock(), onWarning = { warnings += it })
        audio.open(format(2))

        val frames = 64
        audio.submitDecoded(null, FloatArray(frames * 2), frames, format(2, 48_000))
        audio.submitDecoded(null, FloatArray(frames * 6), frames, format(6, 48_000))

        val changed = warnings.filterIsInstance<PlaybackWarning.AudioSourceFormatChanged>()
        assertEquals(1, changed.size, "a layout change is a format change, got $warnings")
        assertEquals(2, changed[0].fromChannels)
        assertEquals(6, changed[0].toChannels)
        audio.close()
    }

}
