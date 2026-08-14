@file:OptIn(KiteCodecLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Chapter
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.AudioDecoderFactory
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
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
import io.github.yuroyami.kiteplayer.spi.Vp9BitDepth
import io.github.yuroyami.kiteplayer.spi.Vp9ChromaSubsampling
import io.github.yuroyami.kiteplayer.spi.Vp9CodecConfiguration
import io.github.yuroyami.kiteplayer.spi.Vp9Level
import io.github.yuroyami.kiteplayer.spi.Vp9Profile
import io.github.yuroyami.kitecodec.KiteCodecLowLevelApi
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.HardwareAccel
import io.github.yuroyami.kitecodec.dsl.DecoderOptions
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
            "Custom I/O is not wired yet. KiteCodec has no custom-input path."
        }
        return KiteCodecSource(MediaSource.open(media.uri))
    }
}

public class KiteCodecSource internal constructor(private val source: MediaSource) : PlayerMediaSource {

    private val byIndex: Map<Int, StreamInfo> = source.streams.associateBy { it.index }
    private var reader: PacketReader? = null

    /**
     * Where this source's decoders report a degradation they had to accept and carry on through.
     *
     * The decoders are the only place that knows, because the knowledge arrives with the frames and
     * not with the container: a stream can start standard dynamic range and change. Colour
     * approximation and a policy-permitted hardware fallback both come out here.
     *
     * The callback runs on whichever thread called `receive`, which is the decoder's own thread, so it
     * must be cheap and must not block. Set it before decoding starts. The default discards.
     */
    public var onWarning: (PlaybackWarning) -> Unit = {}

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

    /**
     * Always empty, because the container reader has no chapter list to offer.
     *
     * Mapped from the container's own table (S4.b, KD-5). KiteCodec reports ABSOLUTE microsecond
     * bounds; the engine's timeline starts at zero, so the same mapper every timestamp crosses
     * moves them, and a chapter whose start maps before zero is clamped rather than dropped.
     */
    override val chapters: List<Chapter> = source.chapters.mapIndexed { index, chapter ->
        Chapter(
            index = index,
            start = (mapper.mapTimestamp(chapter.startMicros) ?: Pts(0)).coerceAtLeast(Pts(0)).asDuration,
            end = mapper.mapTimestamp(chapter.endMicros)?.coerceAtLeast(Pts(0))?.asDuration,
            title = chapter.title,
        )
    }

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
        // The container reader does not report where it landed. The engine finds out from the first decoded
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

    /** KD-6: the backend's profile knobs, applied to every VIDEO decoder opened here. */
    internal var videoDecoderOptions: Map<String, String> = emptyMap()
    internal var videoLowDelay: Boolean = false

    internal fun openDecoder(
        index: Int,
        lowDelay: Boolean,
        decoder: CodecId? = null,
        options: DecoderOptions? = null,
        hardwareAccel: HardwareAccel? = null,
    ): StreamDecoder = source.openDecoder(
        kiteStream(index),
        lowDelay = lowDelay,
        decoder = decoder,
        options = options,
        hardware = hardwareAccel,
    )

    /**
     * The decoder wrappers are built here rather than in the factories, because they need the
     * timestamp mapper and the mapper stays private to this file.
     *
     * The warning sink is passed as a lambda that reads [onWarning] when it fires, not as the current
     * value of it, so a caller that sets the property after building its decoders is still heard.
     */
    internal fun newVideoDecoder(
        stream: PlayerStreamInfo,
        decoder: CodecId? = null,
        hardwareAccel: HardwareAccel? = null,
        hardware: HwdecStatus = HwdecStatus.Software,
        continuity: VideoDecoderContinuity = VideoDecoderContinuity(),
    ): VideoDecoder = KiteCodecVideoDecoder(
        decoder = openDecoder(
            stream.index,
            lowDelay = videoLowDelay,
            decoder = decoder,
            options = videoDecoderOptions.takeIf { it.isNotEmpty() }?.let { DecoderOptions(options = it) },
            hardwareAccel = hardwareAccel,
        ),
        stream = stream,
        mapper = mapper,
        hardware = hardware,
        continuity = continuity,
        warn = { onWarning(it) },
    )

    /**
     * Low delay for audio: a player is waiting on these frames, and the decoder holding them back
     * for reordering costs latency for no benefit.
     */
    internal fun newAudioDecoder(stream: PlayerStreamInfo): AudioDecoder =
        KiteCodecAudioDecoder(
            decoder = openDecoder(stream.index, lowDelay = true),
            stream = stream,
            mapper = mapper,
            // The container's answer, used until the decoder gives its own. A stream that declares no
            // layout, or one no mask can describe, reports null and the mixer falls back to the count.
            declaredChannelLayoutMask = kiteStream(stream.index).audio?.channelLayoutMask,
        )

    /** Video decoders for this source. The factory applies the caller's platform policy at open. */
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
internal class TimestampMapper(private val containerStartMicros: Long) {

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

/**
 * State that must survive replacing a hardware decoder wrapper with its software replay wrapper.
 *
 * Replay begins at the last decoded keyframe. Remembering the timestamp immediately before that
 * keyframe makes a timestampless replay reproduce the same synthetic sequence, so ordinal
 * suppression neither jumps backward to zero nor advances the timeline twice. The colour-warning
 * latch is also per stream, not per wrapper, and deliberately survives seeks.
 */
internal class VideoDecoderContinuity {
    private var lastPts: Pts? = null
    private var replaySeed: Pts? = null
    private var replaySeedPending: Boolean = false
    private var colorWarningClaimed: Boolean = false

    internal fun timestamp(
        real: Pts?,
        duration: Pts?,
        frameRate: Double?,
        isKeyframe: Boolean,
    ): Pts {
        val before = lastPts
        val stepMicros = duration?.micros?.takeIf { it > 0 }
            ?: frameRate
                ?.takeIf { it.isFinite() && it > 0.0 && it < 1000.0 }
                ?.let { (1_000_000.0 / it).roundToLong() }
            ?: SYNTHESIZED_FRAME_STEP_US
        val value = real ?: before?.let { Pts(it.micros + stepMicros) } ?: Pts.Zero
        if (isKeyframe) {
            replaySeed = before
            replaySeedPending = true
        }
        lastPts = value
        return value
    }

    /** Restores the state immediately before the confirmed replay keyframe. */
    internal fun beginReplay() {
        lastPts = replaySeed
        replaySeedPending = false
    }

    /** A seek starts a new timestamp epoch but does not make a repeated colour warning useful. */
    internal fun resetEpoch() {
        lastPts = null
        if (!replaySeedPending) replaySeed = null
    }

    internal fun claimColorWarning(): Boolean {
        if (colorWarningClaimed) return false
        colorWarningClaimed = true
        return true
    }
}

internal fun StreamInfo.toPlayerStream(mapper: TimestampMapper): PlayerStreamInfo? {
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
        // The container's display matrix, already reduced to clockwise degrees by KiteCodec. Only a
        // video stream is ever muxed with one, and a value on any other kind reaches no renderer, so
        // no stream kind has to be excluded here.
        rotationDegrees = rotationDegrees,
        frameRate = video?.frameRate?.let { if (it.den == 0) null else it.num.toDouble() / it.den },
        colorSpace = video?.color?.toPlayerColorSpace(),
        // A stream with exactly one frame of cover art must never carry the timeline or drive
        // synchronisation. Treating it as normal video makes the player hang at the end of every
        // audio file that has album art.
        isCoverArt = disposition.attachedPicture,
        sampleRate = audio?.sampleRate,
        channels = audio?.channels,
        vp9 = video?.vp9?.let { metadata ->
            Vp9CodecConfiguration(
                profile = metadata.profile?.let { source ->
                    Vp9Profile.entries.firstOrNull { it.number == source.number }
                },
                level = metadata.level?.let { source ->
                    Vp9Level.entries.firstOrNull { it.code == source.code }
                },
                bitDepth = metadata.bitDepth?.let { source ->
                    Vp9BitDepth.entries.firstOrNull { it.bits == source.bits }
                },
                chromaSubsampling = metadata.chromaSubsampling?.let { source ->
                    Vp9ChromaSubsampling.entries.firstOrNull { it.code == source.code }
                },
            )
        },
        codecExtradata = codecExtradata?.copyOf(),
    )
}

internal class KiteCodecPacket(val native: Packet, private val mapper: TimestampMapper) : PlayerPacket {
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
    override fun copyBytes(): ByteArray = native.copyBytes()
    override val bytePosition: Long? get() = native.bytePosition.takeIf { it >= 0 }
    internal fun copyForReplay(): KiteCodecPacket = KiteCodecPacket(native.copy(), mapper)
    override fun close() = native.close()
}

/**
 * Creates the platform-selected decoder and, where policy allows, its replay-safe software fallback.
 */
public class KiteCodecVideoDecoderFactory internal constructor(
    private val source: KiteCodecSource,
) : VideoDecoderFactory {
    override val name: String = "KiteCodec FFmpeg"

    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
        if (stream.kind != TrackKind.Video) return null
        val selection = platformDecoderSelection(stream.codec, hwdec)
        if (selection.requiresHardware && selection.hardware == null) return null

        if (selection.hardware == null) return source.newVideoDecoder(stream)

        val continuity = VideoDecoderContinuity()

        return openDecoderWithFallback(
            stream = stream,
            selection = selection,
            open = { route ->
                source.newVideoDecoder(
                    stream = stream,
                    decoder = (route as? HardwareRoute.NamedDecoder)?.decoder,
                    hardwareAccel = (route as? HardwareRoute.Accel)?.accel,
                    // HardwareWithDownload is the honest S2.b status for BOTH shapes: mediacodec
                    // downloads inside FFmpeg's wrapper, and every current renderer reads a
                    // VideoToolbox frame through the download twin. S2.c revisits this when the
                    // Metal renderer makes zero-copy real.
                    hardware = if (route == null) {
                        HwdecStatus.Software
                    } else {
                        HwdecStatus.HardwareWithDownload(route.kind)
                    },
                    continuity = continuity,
                )
            },
            copyPacket = { packet -> (packet as KiteCodecPacket).copyForReplay() },
            isKeyframe = { frame -> (frame as? KiteCodecVideoFrame)?.isKeyframe == true },
            prepareReplay = continuity::beginReplay,
            warn = { source.onWarning(it) },
        )
    }
}

private class KiteCodecVideoDecoder(
    private val decoder: StreamDecoder,
    private val stream: PlayerStreamInfo,
    private val mapper: TimestampMapper,
    override val hardware: HwdecStatus,
    private val continuity: VideoDecoderContinuity,
    private val warn: (PlaybackWarning) -> Unit,
) : VideoDecoder {

    private var generation: Generation = Generation.Initial

    override suspend fun send(packet: PlayerPacket?): Boolean =
        decoder.send((packet as KiteCodecPacket?)?.native)

    /** KiteCodec's own flag, set when its `receive` saw the end of the stream and cleared by flush. */
    override val isDrained: Boolean get() = decoder.isDrained

    override suspend fun receive(): VideoFrame? {
        val frame = decoder.receive() ?: return null
        val duration = mapper.mapDuration(frame.durationMicros)
        val info = frame.info
        val pts = continuity.timestamp(
            real = mapper.mapTimestamp(frame.ptsMicros),
            duration = duration,
            frameRate = stream.frameRate,
            isKeyframe = info.isKeyframe,
        )
        // The rotation is the stream's, taken from the container's display matrix once at open. Every
        // frame of the stream carries it, because the renderer sees frames and nothing else.
        val wrapped = KiteCodecVideoFrame(frame, pts, duration, generation, stream.rotationDegrees)
        try {
            warnIfColorIsApproximated(wrapped.colorSpace)
        } catch (failure: Throwable) {
            wrapped.close()
            throw failure
        }
        return wrapped
    }

    /**
     * Says once, out loud, that the picture is not colour correct and will still be shown.
     *
     * Two colours reach the converter that it can only approximate, and both approximate in the same
     * way, by running the matrix and nothing else:
     *
     * - PQ and HLG are high dynamic range transfer functions. There is no tone mapping anywhere in the
     *   engine, so a 1000 nit picture is displayed as if its code values were standard dynamic range.
     *   Highlights flatten and the whole image reads dull.
     * - BT.2020 constant luminance encodes luma after the transfer function rather than before it, so
     *   the non-constant luminance matrix is the wrong inverse for it. Chroma-heavy areas shift.
     *
     * Neither is fixable with a matrix, because both need the transfer function in the loop, which is
     * the colour managed pipeline this engine does not have. The honest behaviour is to convert
     * approximately and say so, which is also the documented default until that pipeline exists.
     */
    private fun warnIfColorIsApproximated(color: ColorSpaceInfo) {
        val detail = when {
            color.isHdr -> "${color.transfer} transfer converted as standard dynamic range on stream ${stream.index}"
            color.matrix == ColorMatrix.Bt2020Cl ->
                "BT.2020 constant luminance converted with the non-constant luminance matrix on " +
                    "stream ${stream.index}"
            else -> return
        }
        if (!continuity.claimColorWarning()) return
        // Latched before the callback runs, so a callback that throws cannot turn a one-time warning
        // into one per frame.
        warn(PlaybackWarning.TonemappingUnavailable(detail))
    }

    override suspend fun flush(newGeneration: Generation) {
        // Flush first: nothing buffered survives it, so the new epoch cannot reach an old frame. The
        // wrapper only claims the epoch once the decoder is actually in it.
        decoder.flush()
        generation = newGeneration
        // A timestamp measured before a seek is no base for one after it.
        continuity.resetEpoch()
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

/** The most channels the engine models. Anything wider is truncated, and then the mask cannot stand. */
private const val MAX_MODELLED_CHANNELS: Int = 8

/**
 * One decoded audio format, with the channel mask kept only while it still describes the channels.
 *
 * The mask is FFmpeg's native order mask, one bit per speaker, and it is the only thing that
 * distinguishes 5.1 with side surrounds from 5.1 with back surrounds. The count cannot: both are six.
 * A mixer keyed on the count sends the surround content to the wrong pair of speakers, which sounds
 * like a broken file.
 *
 * The mask is dropped when its bit count and the reported channel count disagree, which happens when
 * a stream is wider than [MAX_MODELLED_CHANNELS] and the count is truncated to fit. Reporting a mask
 * for channels that are not there would be worse than reporting none: none means "fall back to the
 * count and say that you did", which is a defined behaviour, while a mismatched mask names speakers
 * for samples that were never handed over.
 */
private fun audioFormat(sampleRate: Int, sourceChannels: Int, mask: Long?): AudioFormat {
    val channels = sourceChannels.coerceIn(1, MAX_MODELLED_CHANNELS)
    return AudioFormat(
        sampleRate = sampleRate,
        channels = channels,
        sampleFormat = SampleFormat.F32,
        channelLayout = ChannelLayout.forChannelCount(channels),
        channelLayoutMask = mask?.takeIf { it.countOneBits() == channels },
    )
}

private class KiteCodecAudioDecoder(
    private val decoder: StreamDecoder,
    stream: PlayerStreamInfo,
    private val mapper: TimestampMapper,
    declaredChannelLayoutMask: Long?,
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

    override var outputFormat: AudioFormat = audioFormat(
        sampleRate = stream.sampleRate ?: 48_000,
        sourceChannels = stream.channels ?: 2,
        mask = declaredChannelLayoutMask,
    )
        private set

    override suspend fun send(packet: PlayerPacket?): Boolean =
        decoder.send((packet as KiteCodecPacket?)?.native)

    /** KiteCodec's own flag, set when its `receive` saw the end of the stream and cleared by flush. */
    override val isDrained: Boolean get() = decoder.isDrained

    override suspend fun receive(): AudioBuffer? {
        val frame = decoder.receive() ?: return null
        val info = frame.info
        // A stream can change its rate, channel count or layout mid-file. Reporting it here lets the
        // engine rebuild its mixer and resampler rather than quietly playing at the wrong speed or
        // sending surround content to the wrong speakers.
        if (info.sampleRate > 0 && info.channelCount > 0) {
            val candidate = audioFormat(info.sampleRate, info.channelCount, info.channelLayoutMask)
            if (candidate != outputFormat) {
                // Re-anchor before adopting the new format: the sample counter is denominated in
                // the OLD rate, and applying the new rate to samples accumulated at the old one
                // would misdate every synthetic timestamp after the transition (audit P1-17).
                val oldRate = outputFormat.sampleRate
                if (oldRate > 0 && samplesSinceAnchor > 0) {
                    anchorMicros += samplesSinceAnchor * 1_000_000L / oldRate
                    samplesSinceAnchor = 0
                }
                outputFormat = candidate
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
    /**
     * The stream's own clockwise rotation, from the container's display matrix.
     *
     * No default on purpose, like every other parameter here. A default of zero would let a new call
     * site drop the rotation silently, which is the exact bug this phase exists to remove.
     */
    override val rotationDegrees: Int,
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

    /** Decoder-keyframe truth used only to confirm the fallback replay handover boundary. */
    internal val isKeyframe: Boolean = info.isKeyframe

    /**
     * The software twin of a VideoToolbox frame, downloaded ONCE on first need and owned by this
     * wrapper (S2.b). Lazy on purpose: a newest-wins renderer supersedes most frames without ever
     * reading pixels, and an eager download would pay 3 to 25 MB of copying for every one of
     * them. A renderer that can draw the CVPixelBuffer itself never triggers this.
     */
    private var downloadedTwin: KiteFrame? = null

    /**
     * The frame whose planes may be read: the frame itself when it is software, its downloaded
     * twin when it is a VideoToolbox frame. Other hardware kinds refuse here, because their
     * pixels genuinely cannot be read back and pretending otherwise would hide a wiring bug.
     */
    internal fun readableFrame(): KiteFrame {
        if (!info.isHardware) return frame
        check(hardwareSurface == HwSurfaceKind.CoreVideoPixelBuffer) {
            "a $hardwareSurface frame needs its matching renderer"
        }
        return downloadedTwin ?: frame.downloadFromHardware().also { downloadedTwin = it }
    }

    override fun close() {
        downloadedTwin?.close()
        downloadedTwin = null
        frame.close()
    }
}

internal class KiteCodecAudioBuffer(
    private val frame: KiteFrame,
    /** Already on the engine's relative timeline, and counted from samples when none was given. */
    override val pts: Pts,
    override val generation: Generation,
    override val format: AudioFormat,
) : AudioBuffer {

    private val info = frame.info

    /**
     * Decoded straight into the MODELLED layout. The stride has to be [format].channels, because
     * every consumer indexes with it: a 16-channel source decoded at its own stride but read at
     * the truncated stride interleaved wrong-channel samples into every frame (audit P1-16).
     * decodeToFloat itself maps source channels onto the requested count.
     */
    private val samples: FloatArray by lazy {
        decodeToFloat(frame.copyPlanesToByteArray(), info, format.channels)
    }

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
