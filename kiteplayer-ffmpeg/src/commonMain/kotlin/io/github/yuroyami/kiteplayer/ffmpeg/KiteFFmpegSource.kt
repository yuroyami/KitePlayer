// This module IS the one that speaks FFmpeg, so the raw-syntax opt-in belongs here: the annotation
// exists to name that coupling, not to forbid it. MediaItem.videoFilter is an FFmpeg filter chain
// and only this backend can act on one.
@file:OptIn(KiteFFmpegLowLevelApi::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

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
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame
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
import io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.HardwareAccel
import io.github.yuroyami.kiteffmpeg.dsl.DecoderOptions
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteffmpeg.Packet
import io.github.yuroyami.kiteffmpeg.PacketReader
import io.github.yuroyami.kiteffmpeg.SeekDirection
import io.github.yuroyami.kiteffmpeg.StreamDecoder
import io.github.yuroyami.kiteffmpeg.StreamInfo
import io.github.yuroyami.kiteffmpeg.durationMicros
import io.github.yuroyami.kiteffmpeg.ptsMicros
import io.github.yuroyami.kiteffmpeg.Frame as KiteFrame
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

/**
 * The engine's source and decoders, over KiteFFmpeg.
 *
 * This is the only module that knows FFmpeg exists. Everything above it works against the interfaces
 * in `kiteplayer-core`, which is what lets a different backend take its place: a browser's WebCodecs,
 * a platform decoder, or a scripted fake in a test.
 */
public class KiteFFmpegSourceFactory : MediaSourceFactory {
    override suspend fun open(media: MediaItem): PlayerMediaSource {
        // The same funnel KiteFFmpegMediaBackend.open runs: this factory used to
        // drop headers, openOptions, formatHint and videoFilter on the floor and skip the
        // FFmpeg identity mapping, so the documented SPI door behaved differently from the
        // backend door for the same MediaItem.
        val options = preOpenOptions(media)
        rewindFdOption(options)
        // Once per open, like the backend door: what the factory answers with is this source's
        // reader and is closed with it.
        val io = media.io?.invoke()
        val source = mappingFFmpegRuntimeRejection {
            KiteFFmpegSource(
                when {
                    // M1, the custom AVIO bridge: an item's own byte reader carries the media.
                    io != null -> MediaSource.open(BlockingMediaIo(io), options)
                    options.isEmpty() -> MediaSource.open(media.uri)
                    else -> MediaSource.open(media.uri, options)
                },
            )
        }
        source.videoFilterDescription = media.videoFilter
        return source
    }
}

public class KiteFFmpegSource internal constructor(private val source: MediaSource) : PlayerMediaSource {

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

    /** One canonical table for both the public track list and every reader selection. */
    private val selectableStreams: List<Pair<StreamInfo, PlayerStreamInfo>> = source.streams.mapNotNull { stream ->
        stream.toPlayerStream(mapper)?.let { exposed -> stream to exposed }
    }

    override val streams: List<PlayerStreamInfo> = selectableStreams.map { it.second }

    /** Raw KiteFFmpeg descriptors only for indices actually exposed through [streams]. */
    private val byIndex: Map<Int, StreamInfo> = selectableStreams.associate { (raw, _) -> raw.index to raw }

    /** The length of the content, which is an interval and so carries no origin. */
    override val duration: Pts? = mapper.mapDuration(source.durationMicros)

    /**
     * Read from the input, never assumed. False for a pipe or a capture device, and a player that
     * offers a seek bar for one of those offers a control that fails on every use.
     */
    override val seekable: Boolean = source.isSeekable

    override val metadata: Map<String, String> = source.metadata

    /**
     * The container's chapters, empty only when the file declares none.
     *
     * Mapped from the container's own table (S4.b, KD-5). KiteFFmpeg reports ABSOLUTE microsecond
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
        // Named one by one, not filtered. A mapNotNull here meant {0, 999} selected 0 and never
        // mentioned 999: the caller asked for two streams, got one, and nothing said which request
        // went nowhere. A missing index is a caller mistake and this library answers those with
        // IllegalArgumentException, so it says so.
        val unknown = indices.filter { it !in byIndex }
        require(unknown.isEmpty()) {
            "no selectable stream at ${unknown.sorted()}; this source offers " +
                "${byIndex.keys.sorted()}"
        }
        val selected = indices.map { byIndex.getValue(it) }
        require(selected.isNotEmpty()) { "selectStreams needs at least one stream" }
        reader = source.openPacketReader(selected)
    }

    override fun interrupt(): Boolean {
        // A single volatile write on the format context; KiteFFmpeg documents this as
        // the one member callable while another thread is blocked in a read or seek.
        source.interrupt()
        return true
    }

    override suspend fun readPacket(): PlayerPacket? {
        val reader = reader ?: error("selectStreams must be called before readPacket")
        return reader.read()?.let { KiteFFmpegPacket(it, mapper) }
    }

    override suspend fun seekToKeyframe(target: Pts): Pts? {
        val reader = reader ?: error("selectStreams must be called before seeking")
        // [target] needs no conversion. KiteFFmpeg's seek already speaks the content-relative
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

    /**
     * The library stream behind a caller-supplied index.
     *
     * Refuses with [IllegalArgumentException] rather than `error(...)`, which threw
     * IllegalStateException from the bottom of the decoder-factory stack. A stream this source
     * does not have is a caller mistake, and this repository answers those one way.
     */
    internal fun kiteStream(index: Int): StreamInfo =
        byIndex[index] ?: throw IllegalArgumentException(
            "no stream at index $index in this source; it offers ${byIndex.keys.sorted()}. " +
                "Pass a stream from this source's own stream list.",
        )

    /** KD-6: the backend's profile knobs, applied to every VIDEO decoder opened here. */
    internal var videoDecoderOptions: Map<String, String> = emptyMap()
    internal var videoLowDelay: Boolean = false

    /** S4.e: the media item's compiled KD-1 video filter chain, or null for none. */
    internal var videoFilterDescription: String? = null

    /**
     * Whether audio may open the platform's own decoder (see `platformAudioDecoder`).
     *
     * A knob and not a constant, because a platform decoder is a DIFFERENT decoder and not a faster
     * copy of the same one. Two conformant AAC decoders agree on the music and disagree in the last
     * bits, so any test that pins exact samples has to pick one and say which; `ReferencePcmTest`
     * turns this off for exactly that reason, since what it measures is the downmix matrix.
     */
    internal var preferPlatformAudioDecoder: Boolean = true

    /** S4.e: the pre-open keys the demuxer never consumed, straight from KiteFFmpeg's funnel. */
    internal val unusedOpenOptions: List<String> get() = source.unusedOpenOptions

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
    ): VideoDecoder = KiteFFmpegVideoDecoder(
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
        // The graph runs on software frames only; the factory stands hardware down first (S4.e).
        filterDescription = if (hardware == HwdecStatus.Software) videoFilterDescription else null,
    )

    /**
     * Low delay for audio: a player is waiting on these frames, and the decoder holding them back
     * for reordering costs latency for no benefit.
     */
    internal fun newAudioDecoder(
        stream: PlayerStreamInfo,
        decoder: CodecId? = null,
    ): AudioDecoder =
        KiteFFmpegAudioDecoder(
            decoder = openDecoder(stream.index, lowDelay = true, decoder = decoder),
            stream = stream,
            mapper = mapper,
            // The container's answer, used until the decoder gives its own. A stream that declares no
            // layout, or one no mask can describe, reports null and the mixer falls back to the count.
            declaredChannelLayoutMask = kiteStream(stream.index).audio?.channelLayoutMask,
        )

    /** Video decoders for this source. The factory applies the caller's platform policy at open. */
    public fun videoDecoderFactories(): List<VideoDecoderFactory> =
        listOf(KiteFFmpegVideoDecoderFactory(this))

    public fun audioDecoderFactories(): List<AudioDecoderFactory> =
        listOf(KiteFFmpegAudioDecoderFactory(this))

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
 * Neither function rescales. The values handed in are already microseconds, converted by KiteFFmpeg
 * through `av_rescale_q` and its 128 bit intermediate, because the obvious
 * `ticks * 1_000_000 * num / den` overflows a signed 64 bit multiply on a fine time base.
 */
internal class TimestampMapper(private val containerStartMicros: Long) {

    /** A point on the timeline. Null in, null out: an absent timestamp is not a timestamp of zero. */
    fun mapTimestamp(micros: Long?): Pts? = micros?.let { Pts(it - containerStartMicros) }

    /** An interval. Rescaled by KiteFFmpeg and shifted by nothing. */
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
        // Verbatim. `language` and `title` above are parsed readings of two of these keys; an
        // application that wants the rest, or wants the raw form, had no way to reach them.
        metadata = metadata,
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
        // The container's display matrix, already reduced to clockwise degrees by KiteFFmpeg. Only a
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

internal class KiteFFmpegPacket(val native: Packet, private val mapper: TimestampMapper) : PlayerPacket {
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
    internal fun copyForReplay(): KiteFFmpegPacket = KiteFFmpegPacket(native.copy(), mapper)
    override fun close() = native.close()
}

/**
 * Creates the platform-selected decoder and, where policy allows, its replay-safe software fallback.
 */
public class KiteFFmpegVideoDecoderFactory internal constructor(
    private val source: KiteFFmpegSource,
) : VideoDecoderFactory {
    override val name: String = "KiteFFmpeg FFmpeg"

    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
        if (stream.kind != TrackKind.Video) return null
        val selection = platformDecoderSelection(stream.codec, hwdec)
        if (selection.requiresHardware && selection.hardware == null) return null

        // A video filter runs on software frames (S4.e): under Auto and Prefer the hardware
        // route stands down with a warning; under Require the two demands cannot both hold and
        // the refusal is this factory's null, which the engine reports typed.
        if (source.videoFilterDescription != null && selection.hardware != null) {
            source.onWarning(
                PlaybackWarning.HardwareDecodeUnavailable(
                    stream.codec,
                    "a video filter is attached and filters run on software frames",
                ),
            )
            return if (hwdec == HwdecPolicy.Require) null else source.newVideoDecoder(stream)
        }

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
            copyPacket = { packet -> (packet as KiteFFmpegPacket).copyForReplay() },
            isKeyframe = { frame -> (frame as? KiteFFmpegVideoFrame)?.isKeyframe == true },
            prepareReplay = continuity::beginReplay,
            warn = { source.onWarning(it) },
        )
    }
}

private class KiteFFmpegVideoDecoder(
    private val decoder: StreamDecoder,
    private val stream: PlayerStreamInfo,
    private val mapper: TimestampMapper,
    override val hardware: HwdecStatus,
    private val continuity: VideoDecoderContinuity,
    private val warn: (PlaybackWarning) -> Unit,
    /** S4.e: the compiled KD-1 chain every decoded frame runs through, or null for none. */
    private val filterDescription: String? = null,
) : VideoDecoder {

    private var generation: Generation = Generation.Initial

    /** The graph, built lazily from the FIRST decoded frame's own geometry and format (S4.e). */
    private var filterGraph: io.github.yuroyami.kiteffmpeg.FilterGraph? = null
    private val filteredPending = ArrayDeque<KiteFrame>()
    private var filterFlushed = false

    override suspend fun send(packet: PlayerPacket?): Boolean =
        decoder.send((packet as KiteFFmpegPacket?)?.native)

    /** KiteFFmpeg's own flag, set when its `receive` saw the end of the stream and cleared by flush. */
    override val isDrained: Boolean
        // A graph that was never built has nothing to flush: the lazy build waits
        // for the first decoded frame, and a stream that never produced one used to hold the
        // whole end of stream off for ever through the filterFlushed flag it could never set.
        get() = decoder.isDrained &&
            (filterDescription == null || filterGraph == null || (filterFlushed && filteredPending.isEmpty()))

    override suspend fun receive(): VideoFrame? {
        val frame = nextDecodedFrame() ?: return null
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
        val wrapped = KiteFFmpegVideoFrame(frame, pts, duration, generation, stream.rotationDegrees)
        try {
            warnIfColorIsApproximated(wrapped.colorSpace)
        } catch (failure: Throwable) {
            wrapped.close()
            throw failure
        }
        return wrapped
    }

    /**
     * The decoder's next frame, run through the attached graph when one is attached (S4.e).
     *
     * The graph is built from the first frame's own width, height, format, time base and rate,
     * which is the only honest moment to build it: the container's declared parameters can lie
     * and the decoder's output cannot. Timestamps pass through in the stream's own time base, so
     * the supported chains are the timebase-preserving ones (scale, crop, eq, format and
     * friends); fps-changing chains are the KD roadmap's own next step and refuse nothing today
     * because their output time base would silently disagree with the stream's.
     */
    private fun nextDecodedFrame(): KiteFrame? {
        val description = filterDescription ?: return decoder.receive()
        while (filteredPending.isEmpty()) {
            val raw = decoder.receive()
            if (raw == null) {
                if (decoder.isDrained && filterGraph != null && !filterFlushed) {
                    filterFlushed = true
                    filterGraph?.flushInput(0) { out -> filteredPending.addLast(out.copy()) }
                    continue
                }
                return null
            }
            val info = raw.info
            val graph = filterGraph ?: io.github.yuroyami.kiteffmpeg.FilterGraph.buildVideo(
                description = description,
                width = info.width,
                height = info.height,
                pixelFormat = info.pixelFormat,
                timeBase = info.timeBase,
                frameRate = frameRateRational(),
                sampleAspectRatio = info.sampleAspectRatio,
            ).also { filterGraph = it }
            // feedInput owns and closes the raw frame; every output is copied out of the callback.
            graph.feedInput(0, raw) { out -> filteredPending.addLast(out.copy()) }
        }
        return filteredPending.removeFirst()
    }

    private fun frameRateRational(): io.github.yuroyami.kiteffmpeg.Rational {
        val rate = stream.frameRate?.takeIf { it > 0.0 } ?: return io.github.yuroyami.kiteffmpeg.Rational(25, 1)
        return io.github.yuroyami.kiteffmpeg.Rational((rate * 1000).toInt(), 1000)
    }

    private fun dropFilterState() {
        while (true) filteredPending.removeFirstOrNull()?.close() ?: break
        filterGraph?.close()
        filterGraph = null
        filterFlushed = false
    }

    /**
     * Says once, out loud, that this stream's colour will be APPROXIMATED and shown anyway.
     *
     * One cause now, not two. BT.2020 constant luminance encodes
     * luma after the transfer function rather than before it, so the non-constant luminance matrix
     * every conversion path here runs is the wrong inverse for it and chroma-heavy areas shift.
     * That is not fixable with a matrix; it needs the transfer function in the loop, which is the
     * colour-managed pipeline this engine does not have.
     *
     * **The HDR half was REMOVED from here on 2026-08-25 and it was the false one.** It warned
     * `TonemappingUnavailable` on every HDR stream from the stream's METADATA, while the engine
     * has tone mapped HDR since 2026-08-16 on every built-in display path. Metadata cannot tell a
     * path that tone maps from one that hands HDR to a display able to show it, so this site could
     * only ever have been right by accident. Tone mapping now announces itself where it ENGAGES,
     * as `RendererEvent.ToneMapEngaged` from the renderer that did it.
     */
    private fun warnIfColorIsApproximated(color: ColorSpaceInfo) {
        if (color.matrix != ColorMatrix.Bt2020Cl) return
        val detail = "BT.2020 constant luminance converted with the non-constant luminance matrix " +
            "on stream ${stream.index}"
        if (!continuity.claimColorWarning()) return
        // Latched before the callback runs, so a callback that throws cannot turn a one-time warning
        // into one per frame.
        warn(PlaybackWarning.ColorApproximated(detail))
    }

    override suspend fun flush(newGeneration: Generation) {
        // Flush first: nothing buffered survives it, so the new epoch cannot reach an old frame. The
        // wrapper only claims the epoch once the decoder is actually in it.
        decoder.flush()
        // The graph's internal state is the old timeline's too; it rebuilds from the next frame.
        dropFilterState()
        generation = newGeneration
        // A timestamp measured before a seek is no base for one after it.
        continuity.resetEpoch()
    }

    override fun close() {
        dropFilterState()
        decoder.close()
    }
}

/** Creates audio decoders. */
public class KiteFFmpegAudioDecoderFactory internal constructor(
    private val source: KiteFFmpegSource,
) : AudioDecoderFactory {
    override val name: String = "KiteFFmpeg FFmpeg"

    /**
     * Prefers the platform's own decoder when one exists, and open is the ONLY place it may refuse.
     *
     * The video path needs a whole replay machine to demote mid-stream, because an hwaccel can accept
     * its attach and then fail on a later picture. Audio needs none of that: a named audio decoder
     * either opens or does not, and at open nothing has been decoded, nothing delivered, and no
     * timeline exists to rebuild. So the fallback is one retry on the native decoder, and the warning
     * says which codec lost its platform path so the loss is visible rather than silent.
     *
     * A failure here is not a playback failure. If BOTH opens fail, the second exception propagates
     * exactly as it did before any of this existed, and the engine reports it typed.
     */
    override suspend fun create(stream: PlayerStreamInfo): AudioDecoder? {
        if (stream.kind != TrackKind.Audio) return null
        val platform = stream.codec
            .takeIf { source.preferPlatformAudioDecoder }
            ?.let { platformAudioDecoder(it) }
            ?: return source.newAudioDecoder(stream)
        return try {
            source.newAudioDecoder(stream, decoder = platform)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            source.onWarning(
                PlaybackWarning.HardwareDecodeUnavailable(
                    codec = stream.codec,
                    reason = "platform audio decoder ${platform.name} refused to open: " +
                        (failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "unknown"),
                ),
            )
            source.newAudioDecoder(stream)
        }
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

private class KiteFFmpegAudioDecoder(
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
        decoder.send((packet as KiteFFmpegPacket?)?.native)

    /** KiteFFmpeg's own flag, set when its `receive` saw the end of the stream and cleared by flush. */
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
                // would misdate every synthetic timestamp after the transition.
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
        return KiteFFmpegAudioBuffer(frame, pts, generation, outputFormat)
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
public class KiteFFmpegVideoFrame internal constructor(
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
) : VideoFrame, SoftwareReadableFrame {

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

    /**
     * Tightly packed planes, copied out of native memory ONCE on first plane read.
     * [KiteFrame.copyPlanesToByteArray] documents the layout: plane after plane, no padding, so
     * every stride below is exactly the plane's width in bytes.
     */
    private val packedPlanes: ByteArray by lazy { readableFrame().copyPlanesToByteArray() }

    private val planeLayout: List<PlaneSpec> by lazy {
        planeLayoutFor(pixelFormat, info.width, info.height)
    }

    override val planeCount: Int get() = planeLayout.size

    override fun planeStride(index: Int): Int = planeLayout[index].strideBytes

    override fun planeHeight(index: Int): Int = planeLayout[index].height

    override fun copyPlane(index: Int, into: ByteArray, offset: Int) {
        val plane = planeLayout[index]
        packedPlanes.copyInto(
            destination = into,
            destinationOffset = offset,
            startIndex = plane.offset,
            endIndex = plane.offset + plane.strideBytes * plane.height,
        )
    }

    override fun close() {
        downloadedTwin?.close()
        downloadedTwin = null
        frame.close()
    }
}

internal class PlaneSpec(val strideBytes: Int, val height: Int, val offset: Int)

/**
 * The tightly packed geometry of each modelled software format, matching FFmpeg's own
 * `av_image_copy_to_buffer(align = 1)` layout that [KiteFrame.copyPlanesToByteArray] produces.
 * Chroma dimensions use ceiling division, exactly as libavutil computes them for odd sizes.
 */
internal fun planeLayoutFor(format: PlayerPixelFormat, width: Int, height: Int): List<PlaneSpec> {
    val chromaW = (width + 1) / 2
    val chromaH = (height + 1) / 2
    fun specs(vararg dims: Pair<Int, Int>): List<PlaneSpec> {
        var offset = 0
        return dims.map { (stride, planeHeight) ->
            PlaneSpec(stride, planeHeight, offset).also { offset += stride * planeHeight }
        }
    }
    return when (format) {
        PlayerPixelFormat.Yuv420p -> specs(width to height, chromaW to chromaH, chromaW to chromaH)
        PlayerPixelFormat.Yuv422p -> specs(width to height, chromaW to height, chromaW to height)
        PlayerPixelFormat.Yuv444p -> specs(width to height, width to height, width to height)
        PlayerPixelFormat.Yuv420p10le ->
            specs(width * 2 to height, chromaW * 2 to chromaH, chromaW * 2 to chromaH)
        PlayerPixelFormat.Yuv422p10le ->
            specs(width * 2 to height, chromaW * 2 to height, chromaW * 2 to height)
        PlayerPixelFormat.Nv12 -> specs(width to height, chromaW * 2 to chromaH)
        PlayerPixelFormat.P010le -> specs(width * 2 to height, chromaW * 4 to chromaH)
        PlayerPixelFormat.Rgba, PlayerPixelFormat.Bgra -> specs(width * 4 to height)
        PlayerPixelFormat.Rgb24 -> specs(width * 3 to height)
        PlayerPixelFormat.Opaque -> throw UnsupportedOperationException(
            "an Opaque frame has no modelled plane layout; capture needs a format the engine models",
        )
    }
}

internal class KiteFFmpegAudioBuffer(
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
     * the truncated stride interleaved wrong-channel samples into every frame.
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
    is KiteFFmpegAudioBuffer -> interleaved()
    else -> FloatArray(frameCount * format.channels).also { out ->
        val scratch = FloatArray(frameCount)
        for (channel in 0 until format.channels) {
            copyChannel(channel, scratch)
            for (i in 0 until frameCount) out[i * format.channels + channel] = scratch[i]
        }
    }
}
