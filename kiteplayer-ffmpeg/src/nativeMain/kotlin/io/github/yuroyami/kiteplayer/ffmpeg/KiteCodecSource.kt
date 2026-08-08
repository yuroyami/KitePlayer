@file:OptIn(KiteCodecLowLevelApi::class, ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Chapter
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.AudioDecoderFactory
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.MediaSourceFactory
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kitecodec.KiteCodecLowLevelApi
import io.github.yuroyami.kitecodec.MediaSource
import io.github.yuroyami.kitecodec.MediaType
import io.github.yuroyami.kitecodec.Packet
import io.github.yuroyami.kitecodec.PacketReader
import io.github.yuroyami.kitecodec.SeekDirection
import io.github.yuroyami.kitecodec.StreamDecoder
import io.github.yuroyami.kitecodec.StreamInfo
import io.github.yuroyami.kitecodec.Frame as KiteFrame
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * The engine's source and decoders, over KiteCodec.
 *
 * This is the only module that knows FFmpeg exists. Everything above it works against the interfaces
 * in `kiteplayer-core`, which is what lets a different backend take its place: a browser's WebCodecs,
 * a platform decoder, or a scripted fake in a test.
 */
public class KiteCodecSourceFactory : MediaSourceFactory {
    override suspend fun open(media: MediaItem): PlayerMediaSource {
        require(media.io == null) {
            "Custom I/O is not wired yet. KiteCodec has no AVIOContext path, specified in " +
                "KITEPLAYER.md section 16.7."
        }
        return KiteCodecSource(MediaSource.open(media.uri))
    }
}

public class KiteCodecSource internal constructor(private val source: MediaSource) : PlayerMediaSource {

    private val byIndex: Map<Int, StreamInfo> = source.streams.associateBy { it.index }
    private var reader: PacketReader? = null

    override val streams: List<PlayerStreamInfo> = source.streams.mapNotNull { it.toPlayerStream() }

    override val duration: Pts? = source.durationMicros?.let { Pts(it) }

    override val seekable: Boolean = true

    override val metadata: Map<String, String> = source.metadata

    override val chapters: List<Chapter> = emptyList()

    /**
     * MPEG-TS and friends declare that their timestamps may jump. The engine uses this to pick a
     * 10 second rather than a 3600 second ceiling on a frame's duration, and to decide how large a
     * jump is a discontinuity rather than drift.
     */
    override val timestampsMayJump: Boolean =
        source.formatName.let { it.contains("mpegts") || it.contains("rtsp") || it.contains("rtp") }

    override fun selectStreams(indices: Set<Int>) {
        check(reader == null) { "streams must be selected before the first read" }
        val selected = indices.mapNotNull { byIndex[it] }
        require(selected.isNotEmpty()) { "no selectable stream among $indices" }
        reader = source.openPacketReader(selected)
    }

    override suspend fun readPacket(): PlayerPacket? {
        val reader = reader ?: error("selectStreams must be called before readPacket")
        return reader.read()?.let { KiteCodecPacket(it) }
    }

    override suspend fun seekToKeyframe(target: Pts): Pts? {
        val reader = reader ?: error("selectStreams must be called before seeking")
        reader.seek(target.micros, SeekDirection.Backward)
        // libavformat does not report where it landed. The engine finds out from the first decoded
        // frame, which is also how it detects an overshoot and decides whether to retry.
        return null
    }

    override fun close() {
        reader?.close()
        reader = null
        source.close()
    }

    internal fun kiteStream(index: Int): StreamInfo =
        byIndex[index] ?: error("no stream at index $index")

    internal fun openDecoder(index: Int, lowDelay: Boolean): StreamDecoder =
        source.openDecoder(kiteStream(index), lowDelay = lowDelay)

    /** Video decoders for this source. Ordered best first, which today means software only. */
    public fun videoDecoderFactories(): List<VideoDecoderFactory> =
        listOf(KiteCodecVideoDecoderFactory(this))

    public fun audioDecoderFactories(): List<AudioDecoderFactory> =
        listOf(KiteCodecAudioDecoderFactory(this))

    public val firstVideo: PlayerStreamInfo?
        get() = streams.firstOrNull { it.kind == TrackKind.Video && !it.isCoverArt }

    public val firstAudio: PlayerStreamInfo?
        get() = streams.firstOrNull { it.kind == TrackKind.Audio }
}

private fun StreamInfo.toPlayerStream(): PlayerStreamInfo? {
    val kind = when (type) {
        MediaType.Video -> TrackKind.Video
        MediaType.Audio -> TrackKind.Audio
        MediaType.Subtitle -> TrackKind.Subtitle
        else -> return null
    }
    return PlayerStreamInfo(
        index = index,
        kind = kind,
        codec = codec.name,
        language = language,
        title = title,
        isDefault = disposition.default,
        isForced = disposition.forced,
        isAccessibility = disposition.hearingImpaired || disposition.visualImpaired,
        bitrate = bitrateBps,
        startTime = Pts(startTimeMicros),
        videoSize = video?.let {
            VideoSize(
                width = it.width,
                height = it.height,
                pixelAspectNumerator = it.sampleAspectRatio.num,
                pixelAspectDenominator = it.sampleAspectRatio.den,
            )
        },
        frameRate = video?.frameRate?.let { if (it.den == 0) null else it.num.toDouble() / it.den },
        // A stream with exactly one frame of cover art must never carry the timeline or drive
        // synchronisation. Treating it as normal video makes the player hang at the end of every
        // audio file that has album art.
        isCoverArt = disposition.attachedPicture,
        sampleRate = audio?.sampleRate,
        channels = audio?.channels,
    )
}

private class KiteCodecPacket(val native: Packet) : PlayerPacket {
    override val streamIndex: Int get() = native.streamIndex
    override val pts: Pts? get() = native.ptsMicros?.let { Pts(it) }
    override val dts: Pts? get() = if (native.dts != Long.MIN_VALUE) Pts(native.dts) else null
    override val duration: Pts?
        get() = native.duration.takeIf { it > 0 }?.let {
            Pts(it * 1_000_000L * native.timeBase.num / native.timeBase.den)
        }
    override val isKeyframe: Boolean get() = native.isKeyframe
    override val sizeBytes: Int get() = native.sizeBytes
    override val bytePosition: Long? get() = native.bytePosition.takeIf { it >= 0 }
    override fun close() = native.close()
}

/** Creates video decoders. Hardware decoding is not wired yet: see KITEPLAYER.md section 16.6. */
public class KiteCodecVideoDecoderFactory internal constructor(
    private val source: KiteCodecSource,
) : VideoDecoderFactory {
    override val name: String = "KiteCodec software"

    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
        if (stream.kind != TrackKind.Video) return null
        if (hwdec is HwdecPolicy.Require) return null
        return KiteCodecVideoDecoder(source.openDecoder(stream.index, lowDelay = false), stream)
    }
}

private class KiteCodecVideoDecoder(
    private val decoder: StreamDecoder,
    private val stream: PlayerStreamInfo,
) : VideoDecoder {

    override val hardware: HwdecStatus = HwdecStatus.Software

    private var generation: Generation = Generation.Initial

    override suspend fun send(packet: PlayerPacket?, generation: Generation): Boolean {
        this.generation = generation
        val native = (packet as KiteCodecPacket?)?.native
        return decoder.send(native)
    }

    override suspend fun receive(): VideoFrame? =
        decoder.receive()?.let { KiteCodecVideoFrame(it, generation, stream) }

    override suspend fun flush() = decoder.flush()

    override fun close() = decoder.close()
}

/** Creates audio decoders. */
public class KiteCodecAudioDecoderFactory internal constructor(
    private val source: KiteCodecSource,
) : AudioDecoderFactory {
    override val name: String = "KiteCodec software"

    override suspend fun create(stream: PlayerStreamInfo): AudioDecoder? {
        if (stream.kind != TrackKind.Audio) return null
        // Low delay for audio: a player is waiting on these frames, and the decoder holding them
        // back for reordering costs latency for no benefit.
        return KiteCodecAudioDecoder(source.openDecoder(stream.index, lowDelay = true), stream)
    }
}

private class KiteCodecAudioDecoder(
    private val decoder: StreamDecoder,
    stream: PlayerStreamInfo,
) : AudioDecoder {

    private var generation: Generation = Generation.Initial

    override var outputFormat: AudioFormat = AudioFormat(
        sampleRate = stream.sampleRate ?: 48_000,
        channels = (stream.channels ?: 2).coerceIn(1, 8),
        sampleFormat = SampleFormat.F32,
        channelLayout = ChannelLayout.forChannelCount((stream.channels ?: 2).coerceIn(1, 8)),
    )
        private set

    override suspend fun send(packet: PlayerPacket?, generation: Generation): Boolean {
        this.generation = generation
        val native = (packet as KiteCodecPacket?)?.native
        return decoder.send(native)
    }

    override suspend fun receive(): AudioBuffer? {
        val frame = decoder.receive() ?: return null
        val info = frame.info
        // A stream can change its rate or channel count mid-file. Reporting it here lets the engine
        // rebuild its resampler rather than quietly playing at the wrong speed.
        if (info.sampleRate > 0 && info.channelCount > 0) {
            val channels = info.channelCount.coerceIn(1, 8)
            if (info.sampleRate != outputFormat.sampleRate || channels != outputFormat.channels) {
                outputFormat = AudioFormat(
                    sampleRate = info.sampleRate,
                    channels = channels,
                    sampleFormat = SampleFormat.F32,
                    channelLayout = ChannelLayout.forChannelCount(channels),
                )
            }
        }
        return KiteCodecAudioBuffer(frame, generation, outputFormat)
    }

    override suspend fun flush() = decoder.flush()

    override fun close() = decoder.close()
}

/**
 * A decoded video frame, still in native memory.
 *
 * The pixels are not copied here and they never reach Kotlin memory unless a renderer asks for them.
 * That is the whole point: a 1080p frame is 3.11 MB and a 4K 10-bit frame is 24.9 MB, so copying at
 * 60 frames a second would cost between 187 MB/s and 1.5 GB/s for nothing.
 */
public class KiteCodecVideoFrame internal constructor(
    public val frame: KiteFrame,
    override val generation: Generation,
    stream: PlayerStreamInfo,
) : VideoFrame {

    private val info = frame.info

    override val pts: Pts = Pts(
        if (info.hasPts) info.pts * 1_000_000L * info.timeBase.num / info.timeBase.den else 0L,
    )

    override val duration: Pts? = info.duration.takeIf { it > 0 }?.let {
        Pts(it * 1_000_000L * info.timeBase.num / info.timeBase.den)
    }

    override val size: VideoSize = VideoSize(
        width = info.width,
        height = info.height,
        pixelAspectNumerator = info.sampleAspectRatio.num,
        pixelAspectDenominator = info.sampleAspectRatio.den,
    )

    override val pixelFormat: PlayerPixelFormat = info.pixelFormat.toPlayerFormat()

    override val colorSpace: ColorSpaceInfo = info.color.toPlayerColorSpace()

    override val hardwareSurface: HwSurfaceKind? =
        if (info.isHardware) hardwareKindFor(info.pixelFormat.name) else null

    /** False when this frame carries no timestamp, which the engine has rules for. */
    public val hasPts: Boolean = info.hasPts

    override fun close(): Unit = frame.close()
}

internal class KiteCodecAudioBuffer(
    private val frame: KiteFrame,
    override val generation: Generation,
    override val format: AudioFormat,
) : AudioBuffer {

    private val info = frame.info
    private val samples: FloatArray by lazy { decodeToFloat(frame.copyPlanesToByteArray(), info) }

    override val pts: Pts = Pts(
        if (info.hasPts) info.pts * 1_000_000L * info.timeBase.num / info.timeBase.den else 0L,
    )

    override val frameCount: Int get() = info.sampleCount

    override fun copyChannel(channel: Int, into: FloatArray, offset: Int) {
        val channels = format.channels
        val source = if (channel < channels) channel else 0
        for (i in 0 until frameCount) {
            into[offset + i] = samples[i * channels + source]
        }
    }

    internal fun interleaved(): FloatArray = samples

    override fun close() = frame.close()
}

/**
 * The buffer's samples as interleaved float, which is what the engine's ring and every audio device
 * want.
 *
 * A copy for audio and none for video is not an inconsistency. One second of 48 kHz stereo float is
 * 384 KB against 187 MB for a second of 1080p60 video, and the engine has to touch every audio sample
 * anyway to resample and mix.
 */
public fun AudioBuffer.interleavedFloat(): FloatArray = when (this) {
    is KiteCodecAudioBuffer -> interleaved()
    else -> FloatArray(frameCount * format.channels).also { out ->
        val scratch = FloatArray(frameCount)
        for (channel in 0 until format.channels) {
            copyChannel(channel, scratch)
            for (i in 0 until frameCount) out[i * format.channels + channel] = scratch[i]
        }
    }
}
