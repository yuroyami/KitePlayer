@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.AudioPlayback
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Nanoseconds this test pretends pass per rendered device buffer. Only differences ever matter. */
private const val FAKE_DEVICE_PERIOD_NANOS: Long = 10_000_000L

/** Channel layouts, as FFmpeg's native order mask writes them: one bit per speaker. */
private const val MASK_MONO: Long = 0x4L        // front centre
private const val MASK_5POINT1_BACK: Long = 0x3FL   // front left, right, centre, LFE, back pair
private const val MASK_5POINT1_SIDE: Long = 0x60FL  // the same, with the side pair instead of the back

/**
 * A device that takes one fixed format and hands its render callback to the test.
 *
 * Real playback is not what is under test. What is under test is what comes out of the far end of the
 * audio path, so this device never runs on its own: the test pulls frames out of the ring itself,
 * through the same callback the real device calls, on demand and in order.
 */
private class CapturingSink(private val accepts: AudioFormat) : AudioSink {
    var render: AudioRenderCallback? = null
        private set

    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        this.render = render
        return accepts
    }

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun drain() = Unit
    override suspend fun setPaused(paused: Boolean): Boolean = true
    override val deviceBufferFrames: Int = 1024
    override fun latencyNanos(): Long = 0
    override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
    override val events: Flow<AudioSinkEvent> = emptyFlow()
    override fun close() = Unit
}

/** A device buffer that keeps what was written into it, so the test can read the engine's output. */
private class CollectingBuffer(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
    val samples: FloatArray = FloatArray(frames * format.channels)

    override fun writeInterleaved(
        source: FloatArray,
        sourceOffset: Int,
        destinationFrameOffset: Int,
        frames: Int,
    ) {
        source.copyInto(
            destination = samples,
            destinationOffset = destinationFrameOffset * format.channels,
            startIndex = sourceOffset,
            endIndex = sourceOffset + frames * format.channels,
        )
    }

    override fun writePlane(
        channel: Int,
        source: FloatArray,
        sourceOffset: Int,
        destinationFrameOffset: Int,
        frames: Int,
    ) {
        error("the engine's ring writes interleaved, so a planar write here would mean the path changed")
    }

    override fun writeSilence(frameOffset: Int, frames: Int) {
        samples.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
    }
}

/** A float buffer that grows, so three seconds of stereo does not become 288000 boxed values. */
private class FloatSink {
    private var values = FloatArray(1 shl 16)
    var size: Int = 0
        private set

    fun append(source: FloatArray, count: Int) {
        if (size + count > values.size) {
            var capacity = values.size
            while (capacity < size + count) capacity *= 2
            values = values.copyInto(FloatArray(capacity), 0, 0, size)
        }
        source.copyInto(values, size, 0, count)
        size += count
    }

    fun toFloatArray(): FloatArray = values.copyOf(size)
}

/**
 * A clock that never moves.
 *
 * The ring dates its anchors from the deadline the render callback carries and not from this, and
 * nothing here reads the position, so a fixed clock keeps the test deterministic and says plainly that
 * timing is not what it measures.
 */
private object FixedClock : MonotonicClock {
    override fun nanos(): Long = 0
}

/**
 * The whole audio path against FFmpeg's own downmix of the same file.
 *
 * This is the end-to-end check for the multichannel work: a 5.1 file is decoded through the backend,
 * converted by the same call the sample uses, written into the ring, and pulled back out through the
 * device callback, then compared value for value against what `ffmpeg -ac 2` produces from the same
 * clip. Both sides decode the same bytes with the same library, so the only thing that can differ is
 * the mix, which is the point.
 *
 * The unit tests for the matrix live next to the mixer and check exact coefficients per layout. This
 * one checks that the chain is wired the right way round, which no unit test can: a mixer with a
 * perfect matrix still plays garbage if the buffers reach it in the wrong format.
 */
class ReferencePcmTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    private fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("cannot open $path. Run scripts/testmedia.sh first.")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, 0)
            val bytes = ByteArray(size)
            bytes.usePinned { pinned ->
                val read = fread(pinned.addressOf(0), 1uL, size.toULong(), file)
                check(read.toInt() == size) { "short read on $path: $read of $size" }
            }
            return bytes
        } finally {
            fclose(file)
        }
    }

    /** Little-endian 32-bit floats, which is what `-f f32le` writes. */
    private fun readFloats(path: String): FloatArray {
        val bytes = readFile(path)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) {
            val at = i * 4
            val bits = (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
    }

    /** Opens a source with only its audio stream selected, and its decoder. */
    private suspend fun openAudio(clip: String): Triple<KiteCodecSource, Int, AudioDecoder> {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/$clip")) as KiteCodecSource
        // What this file measures is the DOWNMIX MATRIX and the ring's ordering, against reference PCM
        // that ffmpeg's own native decoders produced. The tolerances below are tight on purpose because
        // both sides are then the same arithmetic. A platform decoder is a different decoder, correct
        // but not bit-identical, so letting one in here would move the variable this test holds still
        // (measured: aac_at differs from native aac by 2.7e-3 worst sample on surround51.mp4, which is
        // inaudible and still 2.7x this file's discontinuity bound). AppleAudioToolboxDecodeTest is
        // where that decoder is measured instead.
        source.preferPlatformAudioDecoder = false
        val stream = assertNotNull(source.firstAudio, "no audio stream in $clip")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(source.audioDecoderFactories().first().create(stream))
        return Triple(source, stream.index, decoder)
    }

    /**
     * Decodes [clip]'s audio through the pipeline path the sample uses, into stereo at 48 kHz.
     *
     * @return the interleaved stereo output and the format the decoder reported for it.
     */
    private suspend fun decodeThroughPipeline(clip: String): Pair<FloatArray, AudioFormat> {
        val (source, streamIndex, decoder) = openAudio(clip)
        val stereo = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)
        val sink = CapturingSink(accepts = stereo)
        val audio = AudioPlayback(sink, FixedClock)
        val out = FloatSink()
        val decoderFormat: AudioFormat

        try {
            val negotiated = audio.open(decoder.outputFormat)
            assertEquals(stereo, negotiated, "the fake device must take stereo, or this measures something else")

            val render = assertNotNull(sink.render, "opening the device must install the callback")
            val buffer = CollectingBuffer(negotiated, frames = sink.deviceBufferFrames)
            var deadline = FAKE_DEVICE_PERIOD_NANOS

            // Pulls everything the ring holds, one device buffer at a time, in order.
            fun drain() {
                while (true) {
                    val frames = render.onRender(buffer, sink.deviceBufferFrames, deadline)
                    deadline += FAKE_DEVICE_PERIOD_NANOS
                    if (frames <= 0) return
                    out.append(buffer.samples, frames * negotiated.channels)
                }
            }

            suspend fun hand(handed: AudioBuffer) {
                audio.submitDecoded(
                    pts = handed.pts,
                    interleaved = handed.interleavedFloat(),
                    frames = handed.frameCount,
                    sourceFormat = handed.format,
                )
                handed.close()
                // Drained after every buffer so the ring never fills and nothing has to wait.
                drain()
            }

            while (true) {
                val packet = source.readPacket()
                if (packet == null) {
                    decoder.send(null)
                    while (true) hand(decoder.receive() ?: break)
                    break
                }
                if (packet.streamIndex != streamIndex) {
                    packet.close()
                    continue
                }
                while (!decoder.send(packet)) {
                    hand(decoder.receive() ?: break)
                }
                packet.close()
                while (true) hand(decoder.receive() ?: break)
            }
            decoderFormat = decoder.outputFormat
        } finally {
            decoder.close()
            audio.close()
            source.close()
        }
        return out.toFloatArray() to decoderFormat
    }

    /**
     * Compares the engine's output against the reference, value for value from the first one.
     *
     * No alignment search. Both sides decode the same file through the same libavcodec, which applies
     * the container's own priming trim on both, so frame zero is frame zero. An offset would be a real
     * defect, and hiding one behind a search is how a pipeline ends up half a buffer late.
     */
    private fun assertMatchesReferencePcm(label: String, produced: FloatArray, reference: FloatArray) {
        assertEquals(
            reference.size,
            produced.size,
            "$label produced ${produced.size / 2} sample frames against the reference's ${reference.size / 2}",
        )
        val compared = min(produced.size, reference.size)
        assertTrue(compared > 48_000, "$label produced almost nothing: $compared values")

        var total = 0.0
        var worst = 0f
        var worstAt = -1
        for (i in 0 until compared) {
            val difference = abs(produced[i] - reference[i])
            total += difference
            if (difference > worst) {
                worst = difference
                worstAt = i
            }
        }
        val mean = total / compared

        // The two sides differ only by float rounding inside the same arithmetic, so the bound is tight
        // on purpose. A wrong coefficient shows up in the third decimal place and a channel routed to
        // the wrong speaker in the first, both orders of magnitude above this.
        assertTrue(
            mean < 1e-4,
            "$label mean sample error $mean is too high. Worst $worst at value $worstAt of $compared. " +
                "At that size the mix matrix, the channel order or the buffer format is wrong, and not " +
                "the rounding.",
        )
        assertTrue(
            worst < 1e-3,
            "$label worst sample error $worst at value $worstAt suggests a discontinuity, which means " +
                "buffers reached the ring out of order or one was dropped",
        )
    }

    @Test
    fun `a 5point1 file downmixes to what ffmpeg produces`() = runBlocking {
        val (produced, format) = decodeThroughPipeline("surround51.mp4")
        assertEquals(6, format.channels, "the fixture must decode as six channels")
        assertMatchesReferencePcm("surround51.mp4", produced, readFloats("$mediaDir/surround51-stereo.f32le"))
    }

    @Test
    fun `a 5point1 side file reports the side mask and mixes by it`() = runBlocking {
        // The defect this closes: a six channel stream used to be identified by its count, which cannot
        // tell 5.1 with side surrounds from 5.1 with back surrounds. The two share a stereo matrix, so
        // the comparison proves the routing while the mask assertion proves the layout survived the trip
        // from the container instead of being guessed at the far end.
        val (produced, format) = decodeThroughPipeline("surround51side.wav")
        assertEquals(6, format.channels)
        assertEquals(MASK_5POINT1_SIDE, format.channelLayoutMask, "the side layout must arrive as itself")
        assertMatchesReferencePcm(
            "surround51side.wav",
            produced,
            readFloats("$mediaDir/surround51side-stereo.f32le"),
        )
    }

    @Test
    fun `an AAC 5point1 stream reports the back mask`() = runBlocking {
        // A note about the fixture as much as a test: FFmpeg's AAC decoder resolves this file to back
        // surrounds even though the encoder wrote a program config element for side surrounds, so back
        // is the layout the engine really sees and the one the mask has to report.
        val (source, _, decoder) = openAudio("surround51.mp4")
        try {
            assertEquals(6, decoder.outputFormat.channels)
            assertEquals(
                MASK_5POINT1_BACK,
                decoder.outputFormat.channelLayoutMask,
                "the mask must come from the container rather than from the channel count",
            )
            assertEquals(
                6,
                decoder.outputFormat.channelLayoutMask?.countOneBits(),
                "a mask naming a different number of speakers than the format reports is worse than none",
            )
        } finally {
            decoder.close()
            source.close()
        }
    }

    @Test
    fun `a mono stream reports the mono mask`() = runBlocking {
        // The ordinary case every other clip in the repository exercises, so a regression here would
        // otherwise be silent. Mono's one bit is front centre and not front left, which is the small
        // way a mask keeps being a mask rather than a count: a guess from the count would have to pick
        // one and could pick the other.
        val (source, _, decoder) = openAudio("sync1080p30.mp4")
        try {
            assertEquals(1, decoder.outputFormat.channels)
            assertEquals(MASK_MONO, decoder.outputFormat.channelLayoutMask)
        } finally {
            decoder.close()
            source.close()
        }
    }
}
