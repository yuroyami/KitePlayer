package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import io.github.yuroyami.kitecodec.FrameInfo
import io.github.yuroyami.kitecodec.MediaSource
import io.github.yuroyami.kitecodec.SampleFormat as KiteSampleFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Decodes a file's audio track into interleaved float, using KiteCodec.
 *
 * This is the first piece of the FFmpeg backend, and it is deliberately narrow: audio only, one pass,
 * no seeking. That is not the design, it is what KiteCodec can currently do. Its `MediaSource` allows
 * one decode pass at a time and rejects a seek while that pass is running, so video, audio and
 * subtitles cannot be decoded as independent consumers and playback cannot seek. KITEPLAYER.md
 * section 16.3 specifies the change that lifts both limits, and this class becomes a thin adapter over
 * it once that lands.
 *
 * Everything here that is not conversion is temporary. The conversion is not: audio has to reach the
 * engine as interleaved float whatever the decoder produced, because that is what the filter chain and
 * every audio device want.
 */
public class FFmpegAudioReader private constructor(
    private val source: MediaSource,
    private val stream: io.github.yuroyami.kitecodec.StreamInfo,
    /** What the engine and the device will use. Up to two channels, float, at the stream's rate. */
    public val format: AudioFormat,
) : AutoCloseable {

    public val duration: Duration? get() = source.durationMicros?.microseconds

    public val codec: String get() = stream.codec.name

    /** The stream's own channel count, which may be more than [format] carries. */
    public val sourceChannels: Int get() = stream.audio?.channels ?: 0

    /**
     * Decoded audio, in order.
     *
     * One emission per decoded frame, which for AAC is 1024 sample frames and for other codecs
     * something else. The engine does not care: it holds a ring precisely so the decoder's frame size
     * and the device's period size never have to agree.
     */
    public fun chunks(): Flow<AudioChunk> = flow {
        source.decodedFrames(stream).collect { frame ->
            try {
                val info = frame.info
                if (info.sampleCount > 0) {
                    val bytes = frame.copyPlanesToByteArray()
                    val interleaved = toInterleavedFloat(bytes, info, format.channels)
                    emit(
                        AudioChunk(
                            pts = if (info.hasPts) Pts(info.pts * info.timeBase.num * 1_000_000L / info.timeBase.den) else null,
                            interleaved = interleaved,
                            frames = info.sampleCount,
                        ),
                    )
                }
            } finally {
                // Frames from a KiteCodec flow are owned by the collector. Not closing one leaks its
                // native buffers, and at 43 frames a second that is quick to notice.
                frame.close()
            }
        }
    }

    override fun close() {
        source.close()
    }

    public companion object {
        /**
         * Opens [path] and selects its primary audio track.
         *
         * @throws IllegalStateException when the file has no audio track this build can decode.
         */
        public fun open(path: String): FFmpegAudioReader {
            val source = MediaSource.open(path)
            val stream = source.primaryAudio
            if (stream == null) {
                source.close()
                error("no audio stream in $path")
            }
            val audio = stream.audio
            if (audio == null || audio.sampleRate <= 0) {
                source.close()
                error("the audio stream in $path declares no usable sample rate")
            }

            // Two channels at most for now. Proper downmixing of 5.1 and 7.1 belongs in the engine's
            // filter chain, where the channel layout is known and the matrix can be correct, rather
            // than here where dropping channels is all that is possible.
            val channels = min(audio.channels, 2)
            val format = AudioFormat(
                sampleRate = audio.sampleRate,
                channels = channels,
                sampleFormat = SampleFormat.F32,
                channelLayout = ChannelLayout.forChannelCount(channels),
            )
            return FFmpegAudioReader(source, stream, format)
        }
    }
}

/** One decoded run of audio, ready to submit to the engine. */
public class AudioChunk(
    /** The media timestamp of the first frame, when the decoder gave one. */
    public val pts: Pts?,
    /** Channel-interleaved float samples. */
    public val interleaved: FloatArray,
    /** Sample frames, meaning one value per channel each. */
    public val frames: Int,
)

/**
 * Converts whatever the decoder produced into interleaved float.
 *
 * The layout depends on the sample format's name. A planar format holds every sample of channel 0,
 * then every sample of channel 1, and so on. A packed format interleaves them already. Both arrive
 * tightly packed with no row padding, which is what KiteCodec's copy guarantees.
 *
 * Integer formats are scaled by their full-scale value rather than by the next power of two, so a
 * full-scale input reaches exactly 1.0 and never clips on the way back out.
 */
private fun toInterleavedFloat(bytes: ByteArray, info: FrameInfo, outChannels: Int): FloatArray {
    val frames = info.sampleCount
    val sourceChannels = info.channelCount.coerceAtLeast(1)
    val out = FloatArray(frames * outChannels)
    val format = info.sampleFormat
    val planar = format.name.endsWith("p")
    val bytesPerSample = when (format) {
        KiteSampleFormat.U8, KiteSampleFormat.U8p -> 1
        KiteSampleFormat.S16, KiteSampleFormat.S16p -> 2
        KiteSampleFormat.S32, KiteSampleFormat.S32p, KiteSampleFormat.Flt, KiteSampleFormat.FltP -> 4
        KiteSampleFormat.S64, KiteSampleFormat.S64p, KiteSampleFormat.Dbl, KiteSampleFormat.DblP -> 8
        else -> error("unsupported sample format ${format.name}")
    }

    for (channel in 0 until outChannels) {
        // A mono source feeding a stereo device plays the same samples in both channels rather than
        // leaving one silent.
        val sourceChannel = if (channel < sourceChannels) channel else 0
        for (frame in 0 until frames) {
            val byteOffset = if (planar) {
                (sourceChannel * frames + frame) * bytesPerSample
            } else {
                (frame * sourceChannels + sourceChannel) * bytesPerSample
            }
            if (byteOffset + bytesPerSample > bytes.size) break
            out[frame * outChannels + channel] = readSample(bytes, byteOffset, format)
        }
    }
    return out
}

private fun readSample(bytes: ByteArray, offset: Int, format: KiteSampleFormat): Float = when (format) {
    KiteSampleFormat.U8, KiteSampleFormat.U8p ->
        ((bytes[offset].toInt() and 0xFF) - 128) / 128f

    KiteSampleFormat.S16, KiteSampleFormat.S16p ->
        bytes.leShort(offset) / 32_768f

    KiteSampleFormat.S32, KiteSampleFormat.S32p ->
        bytes.leInt(offset) / 2_147_483_648f

    KiteSampleFormat.Flt, KiteSampleFormat.FltP ->
        Float.fromBits(bytes.leInt(offset))

    KiteSampleFormat.Dbl, KiteSampleFormat.DblP ->
        Double.fromBits(bytes.leLong(offset)).toFloat()

    KiteSampleFormat.S64, KiteSampleFormat.S64p ->
        (bytes.leLong(offset).toDouble() / 9.223372036854776E18).toFloat()

    else -> error("unsupported sample format ${format.name}")
}

private fun ByteArray.leShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() shl 8)).toShort().toInt()

private fun ByteArray.leInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.leLong(offset: Int): Long =
    (leInt(offset).toLong() and 0xFFFFFFFFL) or (leInt(offset + 4).toLong() shl 32)
