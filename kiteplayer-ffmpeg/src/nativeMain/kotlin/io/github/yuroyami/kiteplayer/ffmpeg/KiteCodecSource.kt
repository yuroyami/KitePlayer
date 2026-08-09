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
import io.github.yuroyami.kitecodec.durationMicros
import io.github.yuroyami.kitecodec.ptsMicros
import io.github.yuroyami.kitecodec.Frame as KiteFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.math.roundToLong

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
            "Custom I/O is not wired yet. KiteCodec has no AVIOContext path."
        }
        return KiteCodecSource(MediaSource.open(media.uri))
    }
}

public class KiteCodecSource internal constructor(private val source: MediaSource) : PlayerMediaSource {

    private val byIndex: Map<Int, StreamInfo> = source.streams.associateBy { it.index }
    private var reader: PacketReader? = null

    /**
     * The single place the container's timeline becomes the engine's. Declared before [streams]
     * because that list is normalised through it.
     */
    private val mapper = TimestampMapper(source.startTimeMicros)

    override val streams: List<PlayerStreamInfo> = source.streams.mapNotNull { it.toPlayerStream(mapper) }

    /** The length of the content, which is an interval and so carries no origin. */
    override val duration: Pts? = mapper.mapDuration(source.durationMicros)

    /**
     * Read from the input, never assumed. False for a pipe or a capture device, and a player that
     * offers a seek bar for one of those offers a control that fails on every use.
     */
    override val seekable: Boolean = source.isSeekable

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
        return reader.read()?.let { KiteCodecPacket(it, mapper) }
    }

    override suspend fun seekToKeyframe(target: Pts): Pts? {
        val reader = reader ?: error("selectStreams must be called before seeking")
        // [target] needs no conversion. KiteCodec's seek already speaks the content-relative
        // timeline, and every timestamp this class produces is now on that same timeline.
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

    /**
     * The decoder wrappers are built here rather than in the factories, because they need the
     * timestamp mapper and the mapper stays private to this file.
     */
    internal fun newVideoDecoder(stream: PlayerStreamInfo): VideoDecoder =
        KiteCodecVideoDecoder(openDecoder(stream.index, lowDelay = false), stream, mapper)

    /**
     * Low delay for audio: a player is waiting on these frames, and the decoder holding them back
     * for reordering costs latency for no benefit.
     */
    internal fun newAudioDecoder(stream: PlayerStreamInfo): AudioDecoder =
        KiteCodecAudioDecoder(openDecoder(stream.index, lowDelay = true), stream, mapper)

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

/**
 * Moves the container's timestamps onto the engine's timeline, which starts at zero.
 *
 * This is the only place that normalisation happens, and it happens exactly once. Above this class
 * every timestamp is content-relative, which is the timeline seeking already spoke, so a position
 * asked for and a position reported finally mean the same thing.
 *
 * The two functions are deliberately separate and are not interchangeable:
 *
 * - [mapTimestamp] takes a point on the timeline and moves it onto the new origin.
 * - [mapDuration] takes an interval and leaves it exactly as it is. An interval has no origin, so
 *   subtracting one from it produces a length that is wrong by the whole container start offset.
 *   On an MPEG-TS capture starting at 1401 seconds, a 33 millisecond frame would come out as minus
 *   1401 seconds.
 *
 * Neither function rescales. The values handed in are already microseconds, converted by KiteCodec
 * through `av_rescale_q` and its 128 bit intermediate, because the obvious
 * `ticks * 1_000_000 * num / den` overflows a signed 64 bit multiply on a fine time base.
 */
private class TimestampMapper(private val containerStartMicros: Long) {

    /** A point on the timeline. Null in, null out: an absent timestamp is not a timestamp of zero. */
    fun mapTimestamp(micros: Long?): Pts? = micros?.let { Pts(it - containerStartMicros) }

    /** An interval. Rescaled by KiteCodec and shifted by nothing. */
    fun mapDuration(micros: Long?): Pts? = micros?.let { Pts(it) }
}

/**
 * The last resort step between two synthesised video timestamps: 40 milliseconds, or 25 frames a
 * second. It is the same guess the engine's own frame duration estimator falls back to, and it only
 * ever applies to a stream that declares no frame rate and whose decoder reports no duration.
 */
private const val SYNTHESIZED_FRAME_STEP_US: Long = 40_000

private fun StreamInfo.toPlayerStream(mapper: TimestampMapper): PlayerStreamInfo? {
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
        // A stream's own start is a point on the timeline, so it is normalised like every other one.
        // It is already in microseconds, so the mapper only has to move the origin.
        startTime = mapper.mapTimestamp(startTimeMicros),
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

private class KiteCodecPacket(val native: Packet, private val mapper: TimestampMapper) : PlayerPacket {
    override val streamIndex: Int get() = native.streamIndex
    override val pts: Pts? get() = mapper.mapTimestamp(native.ptsMicros)

    /**
     * The decode timestamp, on the same relative timeline as [pts] and in the same unit.
     *
     * It used to be the packet's raw tick count wrapped in a microsecond type, which is only ever
     * right on a stream whose time base happens to be 1/1000000.
     */
    override val dts: Pts? get() = mapper.mapTimestamp(native.dtsMicros)

    override val duration: Pts? get() = mapper.mapDuration(native.durationMicros)
    override val isKeyframe: Boolean get() = native.isKeyframe
    override val sizeBytes: Int get() = native.sizeBytes
    override val bytePosition: Long? get() = native.bytePosition.takeIf { it >= 0 }
    override fun close() = native.close()
}

/** Creates video decoders. Hardware decoding is not wired yet. */
public class KiteCodecVideoDecoderFactory internal constructor(
    private val source: KiteCodecSource,
) : VideoDecoderFactory {
    override val name: String = "KiteCodec software"

    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
        if (stream.kind != TrackKind.Video) return null
        if (hwdec is HwdecPolicy.Require) return null
        return source.newVideoDecoder(stream)
    }
}

private class KiteCodecVideoDecoder(
    private val decoder: StreamDecoder,
    private val stream: PlayerStreamInfo,
    private val mapper: TimestampMapper,
) : VideoDecoder {

    override val hardware: HwdecStatus = HwdecStatus.Software

    private var generation: Generation = Generation.Initial

    /**
     * The last timestamp handed out, real or synthesised, and the base for the next synthesised one.
     * Null before the first frame of an epoch.
     */
    private var lastPts: Pts? = null

    override suspend fun send(packet: PlayerPacket?): Boolean =
        decoder.send((packet as KiteCodecPacket?)?.native)

    override suspend fun receive(): VideoFrame? {
        val frame = decoder.receive() ?: return null
        val duration = mapper.mapDuration(frame.durationMicros)
        val pts = mapper.mapTimestamp(frame.ptsMicros) ?: synthesisedPts(duration)
        lastPts = pts
        return KiteCodecVideoFrame(frame, pts, duration, generation)
    }

    /**
     * A timestamp for a frame that has none: the previous one plus this frame's best known length.
     *
     * The step is the frame's own duration, else the container's declared rate, else 40 ms. That
     * order is deliberate: the decoder reports a duration per frame, so it stays right on a stream
     * whose rate varies, while the declared rate is one number for the whole stream and can only be
     * an average of it.
     *
     * The first frame of a stream that carries no timestamps sits at zero, because a counter has to
     * start somewhere and the timeline starts there. That is not the fabricated stamp this replaced:
     * the second frame is one step later rather than zero again, so the frames keep their spacing
     * and the scheduler can pace them.
     */
    private fun synthesisedPts(duration: Pts?): Pts {
        val stepMicros = duration?.micros?.takeIf { it > 0 }
            ?: stream.frameRate
                ?.takeIf { it.isFinite() && it > 0.0 && it < 1000.0 }
                ?.let { (1_000_000.0 / it).roundToLong() }
            ?: SYNTHESIZED_FRAME_STEP_US
        return lastPts?.let { Pts(it.micros + stepMicros) } ?: Pts.Zero
    }

    override suspend fun flush(newGeneration: Generation) {
        // Flush first: nothing buffered survives it, so the new epoch cannot reach an old frame. The
        // wrapper only claims the epoch once the decoder is actually in it.
        decoder.flush()
        generation = newGeneration
        // A timestamp measured before a seek is no base for one after it.
        lastPts = null
    }

    override fun close() = decoder.close()
}

/** Creates audio decoders. */
public class KiteCodecAudioDecoderFactory internal constructor(
    private val source: KiteCodecSource,
) : AudioDecoderFactory {
    override val name: String = "KiteCodec software"

    override suspend fun create(stream: PlayerStreamInfo): AudioDecoder? {
        if (stream.kind != TrackKind.Audio) return null
        return source.newAudioDecoder(stream)
    }
}

private class KiteCodecAudioDecoder(
    private val decoder: StreamDecoder,
    stream: PlayerStreamInfo,
    private val mapper: TimestampMapper,
) : AudioDecoder {

    private var generation: Generation = Generation.Initial

    /**
     * Where the last real timestamp sat, and how many sample frames have gone out since it.
     *
     * Audio needs no duration guessing: a buffer's length is its sample count over its rate, exactly.
     * Counting samples rather than adding rounded durations is what keeps a long run of timestampless
     * buffers from drifting, because one division happens at the end instead of one per buffer.
     */
    private var anchorMicros: Long = 0
    private var samplesSinceAnchor: Long = 0

    override var outputFormat: AudioFormat = AudioFormat(
        sampleRate = stream.sampleRate ?: 48_000,
        channels = (stream.channels ?: 2).coerceIn(1, 8),
        sampleFormat = SampleFormat.F32,
        channelLayout = ChannelLayout.forChannelCount((stream.channels ?: 2).coerceIn(1, 8)),
    )
        private set

    override suspend fun send(packet: PlayerPacket?): Boolean =
        decoder.send((packet as KiteCodecPacket?)?.native)

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

        val mapped = mapper.mapTimestamp(frame.ptsMicros)
        val pts = if (mapped != null) {
            anchorMicros = mapped.micros
            samplesSinceAnchor = 0
            mapped
        } else {
            // Without a rate a sample count cannot become a duration. A stream that declares none and
            // whose decoder reports none is broken rather than unusual, and holding the anchor is
            // bounded where dividing by a coerced 1 would date the next buffer days into the file.
            val rate = if (info.sampleRate > 0) info.sampleRate else outputFormat.sampleRate
            if (rate > 0) Pts(anchorMicros + samplesSinceAnchor * 1_000_000L / rate) else Pts(anchorMicros)
        }
        samplesSinceAnchor += info.sampleCount
        return KiteCodecAudioBuffer(frame, pts, generation, outputFormat)
    }

    override suspend fun flush(newGeneration: Generation) {
        decoder.flush()
        generation = newGeneration
        // The sample counter measured a run that the seek ended. Nothing about it survives, so the
        // count restarts and waits for the first real timestamp of the new position, which every
        // container this backend can open provides.
        anchorMicros = 0
        samplesSinceAnchor = 0
    }

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
    /** Already on the engine's relative timeline, and synthesised when the decoder gave none. */
    override val pts: Pts,
    override val duration: Pts?,
    override val generation: Generation,
) : VideoFrame {

    private val info = frame.info

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

    /**
     * False when the decoder gave this frame no timestamp, so [pts] was counted forward from the
     * previous frame rather than read from the media.
     */
    public val hasPts: Boolean = info.hasPts

    override fun close(): Unit = frame.close()
}

internal class KiteCodecAudioBuffer(
    private val frame: KiteFrame,
    /** Already on the engine's relative timeline, and counted from samples when none was given. */
    override val pts: Pts,
    override val generation: Generation,
    override val format: AudioFormat,
) : AudioBuffer {

    private val info = frame.info
    private val samples: FloatArray by lazy { decodeToFloat(frame.copyPlanesToByteArray(), info) }

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
