// This module IS the one that speaks FFmpeg, so the raw-syntax opt-in belongs here: the annotation
// exists to name that coupling, not to forbid it. MediaItem.videoFilter is an FFmpeg filter chain
// and only this backend can act on one.
@file:OptIn(KiteFFmpegLowLevelApi::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Chapter
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.DeinterlacePolicy
import io.github.yuroyami.kiteplayer.DolbyVisionInfo
import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.KeyframeChoice
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MediaProgram
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.AudioDecoderFactory
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.DisplayPrimaries
import io.github.yuroyami.kiteplayer.spi.HdrStaticMetadata
import io.github.yuroyami.kiteplayer.spi.MediaSourceFactory
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.MediaAttachment
import io.github.yuroyami.kiteplayer.spi.FieldOrder
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.RecordingCapable
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
import io.github.yuroyami.kiteffmpeg.DecoderId
import io.github.yuroyami.kiteffmpeg.DolbyVisionMetadata
import io.github.yuroyami.kiteffmpeg.HardwareAccel
import io.github.yuroyami.kiteffmpeg.dsl.DecoderOptions
import io.github.yuroyami.kiteffmpeg.dsl.DecoderSkip
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteffmpeg.Packet
import io.github.yuroyami.kiteffmpeg.PacketReader
import io.github.yuroyami.kiteffmpeg.Program
import io.github.yuroyami.kiteffmpeg.SeekDirection
import io.github.yuroyami.kiteffmpeg.StreamDecoder
import io.github.yuroyami.kiteffmpeg.StreamInfo
import io.github.yuroyami.kiteffmpeg.durationMicros
import io.github.yuroyami.kiteffmpeg.ptsMicros
import io.github.yuroyami.kiteffmpeg.Frame as KiteFrame
import kotlin.concurrent.Volatile
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
        // The same open KiteFFmpegMediaBackend.open runs. This factory once dropped headers,
        // openOptions, formatHint and videoFilter and skipped the FFmpeg identity mapping, so the
        // documented SPI door behaved differently from the backend door for the same MediaItem.
        val source = typingOpenFailures(media) {
            openItem(media).toSource()
        }
        source.attachItemFilters(media)
        return source
    }
}

/**
 * The source of this opened item, with everything the open learned. The backend's open and the
 * source factory both build it here, so neither can leave a part out: the backend once built its
 * own and left out the variants, so the player listed no quality to choose or step to (#543).
 */
internal fun OpenedItem.toSource(): KiteFFmpegSource =
    KiteFFmpegSource(source, bridge, hls, variants, selectedVariant, realTimeScheme, listedTitle, growing, thumbnails, times, switch)

/**
 * Applies [media]'s filter chains to this source. An audio chain on a build without filter graphs,
 * which is the web build, closes the source and refuses the open, typed, rather than playing the
 * item without the effect it asked for.
 */
internal fun KiteFFmpegSource.attachItemFilters(media: MediaItem) {
    videoFilterDescription = media.videoFilter
    val audioChain = media.audioFilter ?: return
    if (!io.github.yuroyami.kiteffmpeg.FFmpeg.hasFilter("abuffer")) {
        close()
        throw io.github.yuroyami.kiteplayer.PlaybackException(
            io.github.yuroyami.kiteplayer.PlaybackError.ConfigurationInvalid(
                "MediaItem.audioFilter needs FFmpeg filter graphs, and this build has none",
            ),
        )
    }
    audioFilterDescription = audioChain
}

public class KiteFFmpegSource internal constructor(
    private val source: MediaSource,
    /** The bridge that reads the item's own reader, when it has one. [interrupt] must reach it too. */
    private val bridge: BlockingMediaIo? = null,
    /** What happened to the addresses of an HLS stream read through the item's reader. */
    private val hls: HlsLedger? = null,
    override val variants: List<io.github.yuroyami.kiteplayer.StreamVariant> = emptyList(),
    private val openedVariant: Int? = null,
    /** True when the URL fallback opened a scheme whose sender pushes media at the pace it plays. */
    realTimeScheme: Boolean = false,
    /** The title the list of streams the item named gave this stream (#450). */
    private val listedTitle: String? = null,
    /** The reader of a file still being written, when the item is one (#430). */
    private val growing: GrowingMediaIo? = null,
    /** The seek bar pictures an HLS stream, or a DASH one through its stand-in, names (#433). */
    override val thumbnails: io.github.yuroyami.kiteplayer.spi.PlayerThumbnails? = null,
    /** The time of day of an HLS stream's positions, or a DASH one's through its stand-in (#444). */
    private val times: HlsTimeOfDay? = null,
    /** Moves an HLS stream to another variant without a new open, when it has one (#464). */
    private val switch: HlsVariantSwitch? = null,
) : PlayerMediaSource, RecordingCapable {

    override val selectedVariant: Int? get() = switch?.selected ?: openedVariant

    override suspend fun switchVariant(index: Int?): Boolean = switch?.request(index) ?: false

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
     *
     * An HLS stream also reports each address it could not read here, those from the open included.
     */
    public var onWarning: (PlaybackWarning) -> Unit = {}
        set(value) {
            field = value
            hls?.attach(value)
        }

    /** Reads [onWarning] when it warns, because the engine replaces that listener after construction. */
    private val recorder = SourceRecorder(source) { onWarning(it) }

    /** The streams the reader delivers, which are the streams a recording copies. */
    private var readStreams: List<StreamInfo> = emptyList()

    /**
     * The single place the container's timeline becomes the engine's. Declared before [streams]
     * because that list is normalised through it.
     */
    private val mapper = TimestampMapper(source.startTimeMicros)

    /**
     * One canonical table for the public track list, every reader selection and every decoder, with
     * the programmes over it. The demux lane replaces it whole when a read finds the container's
     * streams or programmes changed (#509), and every other caller reads whichever table is current.
     */
    @Volatile
    private var layout: Layout = Layout.of(source.streams, source.programs, mapper, renditionNames = source.formatName == "hls")

    /** What the container lists, as it stands after the latest read: a live stream can add to it (#509). */
    override val streams: List<PlayerStreamInfo> get() = layout.streams

    /**
     * Matroska attachments, read once from the container's attachment streams. FFmpeg keeps an
     * attachment's bytes in that stream's codec extradata and its name and type in the stream's
     * tags, so no packet is ever read for one. Fonts are what the subtitle typesetter loads.
     */
    override val attachments: List<MediaAttachment> = source.streams
        .filter { it.type == MediaType.Attachment }
        .mapNotNull { stream ->
            val bytes = stream.codecExtradata?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            MediaAttachment(
                fileName = stream.metadata["filename"] ?: "attachment-${stream.index}",
                mimeType = stream.metadata["mimetype"],
                data = bytes,
            )
        }

    /** Raw KiteFFmpeg descriptors only for indices actually exposed through [streams]. */
    private val byIndex: Map<Int, StreamInfo> get() = layout.byIndex

    /**
     * The channels of a multiplex (#505), as they stand after the latest read, because a live
     * transport stream can change them (#509). Only a programme the container numbers is one,
     * which leaves out those FFmpeg makes for the variants of an HLS master playlist and for a DASH
     * presentation, and a programme keeps only the streams [streams] lists.
     */
    override val programs: List<MediaProgram> get() = layout.programs

    /** The length at the open, which is an interval and so carries no origin. */
    private val openDuration: Pts? = mapper.mapDuration(source.durationMicros)

    /**
     * The length of the content. A file still being written grows (#430), so its length is the
     * open's, scaled by how much the file has grown since, which is the open's own bit rate.
     */
    override val duration: Pts? get() {
        val atOpen = openDuration ?: return null
        val growing = growing ?: return atOpen
        val from = growing.sizeAtOpen?.takeIf { it > 0 } ?: return atOpen
        val now = growing.size?.takeIf { it > from } ?: return atOpen
        return Pts((atOpen.micros.toDouble() * now / from).toLong())
    }

    // FFmpeg's guess from the bit rate, for an input that states no length, can be minutes out
    // (#422). The length of a file still being written is one until it ends (#430).
    override val durationIsEstimate: Boolean =
        growing != null || source.durationOrigin == io.github.yuroyami.kiteffmpeg.DurationOrigin.Bitrate

    /**
     * Read from the input, never assumed. False for a pipe or a capture device, and a player that
     * offers a seek bar for one of those offers a control that fails on every use.
     *
     * An HLS stream can seek when its playlist has a duration, which FFmpeg sets for a finished
     * playlist and an event playlist, the two it can seek in. The playlist's own bytes say nothing,
     * because a server may answer a playlist without byte ranges.
     */
    override val seekable: Boolean = if (source.formatName == "hls") duration != null else source.isSeekable

    /**
     * The container's tags as they stand, which a packet that brings new ones replaces (#423): a
     * radio station's next song, a chained Ogg's next comments. Written by the demux lane, read by
     * the engine's snapshot from any thread.
     */
    override val metadata: Map<String, String> get() = tags.value

    /**
     * The stream whose comments are the file's own tags, or null: an Ogg file keeps its title and
     * artist in its first sound's comments, not in the container, and a chained Ogg brings the next
     * song's there (#423).
     */
    private val commentStream: Int? =
        if (source.formatName == "ogg") source.streams.firstOrNull { it.type == io.github.yuroyami.kiteffmpeg.MediaType.Audio }?.index else null

    /** The container's own tags and the comment stream's, as they last stood. */
    private var containerTags: Map<String, String> = source.metadata
    private var commentTags: Map<String, String> =
        commentStream?.let { index -> source.streams.firstOrNull { it.index == index }?.metadata }.orEmpty()

    private val tags = kotlinx.atomicfu.atomic(withListedTitle(containerTags + commentTags))

    // The stream's own title wins; a list's title names a stream that names itself nothing.
    private fun withListedTitle(found: Map<String, String>): Map<String, String> =
        if (listedTitle == null || found.keys.any { it.equals("title", ignoreCase = true) }) found else found + ("title" to listedTitle)

    /** The container's own claim, read once at open. Null when it declares none. */
    override val containerBitrateBps: Long? = source.bitrateBps

    /**
     * Read on every call rather than cached: the library fills this in as decoders produce their
     * first output, so a value read at open would always be empty.
     */
    override val streamDivergences: List<io.github.yuroyami.kiteplayer.spi.StreamDivergence>
        get() = source.streamDivergences.map { divergence ->
            io.github.yuroyami.kiteplayer.spi.StreamDivergence(
                streamIndex = divergence.streamIndex,
                field = divergence.field.name,
                declared = divergence.declared,
                decoded = divergence.decoded,
            )
        }

    /**
     * The container's chapters, empty only when the file declares none.
     *
     * Mapped from the container's own table. KiteFFmpeg reports ABSOLUTE microsecond
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
     * jump is a discontinuity rather than drift. HLS joins segments that may each start a new
     * timeline, and FFmpeg passes those jumps on.
     */
    override val timestampsMayJump: Boolean =
        source.formatName.let { it.contains("mpegts") || it.contains("rtsp") || it.contains("rtp") || it == "hls" }

    /**
     * True for a stream with no duration that came over udp, rtp, rtsp or rtmp, or from an SDP
     * file: a sender pushes it at the pace it plays. An RTMP server's recording has a duration and
     * is not one, and neither is anything read through the item's own reader.
     */
    override val realTime: Boolean = realTimeScheme && duration == null

    /**
     * FFmpeg's HLS reader downloads each rendition of a master playlist on its own, and a DASH
     * presentation reaches it as one, so a sound nobody hears there costs its download (#455).
     */
    // The map counts from the first segment FFmpeg opened, which is where the mapper's zero is.
    override fun timeOfDayAt(position: Pts): Long? = times?.timeOfDayAt(position.micros)?.let { it.floorDiv(1_000L) }

    override fun positionAtTimeOfDay(epochMillis: Long): Pts? = times?.positionAt(epochMillis * 1_000)?.let(::Pts)

    override val timeOfDaySpan: LongRange?
        get() = times?.span()?.let { it.first.floorDiv(1_000L)..it.last.floorDiv(1_000L) }

    override val separateAudioRenditions: Boolean =
        source.formatName == "hls" && streams.count { it.kind == io.github.yuroyami.kiteplayer.TrackKind.Audio } > 1

    /**
     * The first call opens the reader. A later one, between reads, changes which streams it delivers
     * without moving it, which is how the demux lane adds a stream that appeared after the open; the
     * packets of it that FFmpeg read before then come first on the next read (#509).
     */
    override fun selectStreams(indices: Set<Int>) {
        // Named one by one, not filtered. A mapNotNull here meant {0, 999} selected 0 and never
        // mentioned 999: the caller asked for two streams, got one, and nothing said which request
        // went nowhere. A missing index is a caller mistake and this library answers those with
        // IllegalArgumentException, so it says so.
        val unknown = indices.filter { it !in byIndex }
        require(unknown.isEmpty()) {
            "no selectable stream at ${unknown.sorted()}; this source offers " +
                "${byIndex.keys.sorted()}"
        }
        // Several page tracks of one teletext stream are one stream to the reader (#510).
        val selected = indices.map { byIndex.getValue(it) }.distinctBy { it.index }
        require(selected.isNotEmpty()) { "selectStreams needs at least one stream" }
        val open = reader
        if (open == null) reader = source.openPacketReader(selected) else open.reselect(selected)
        readStreams = selected
        fanOut = indices.groupBy { byIndex.getValue(it).index }
            .filter { (stream, tracks) -> tracks != listOf(stream) }
            .mapValues { (_, tracks) -> tracks.sorted().toIntArray() }
        dropCopies { it in indices }
    }

    /**
     * The tracks each read stream's packets go to, for a stream whose packets do not simply go to
     * its own index, which is a teletext stream with a page chosen that is not its first, or with
     * more than one page chosen (#510).
     */
    private var fanOut: Map<Int, IntArray> = emptyMap()

    /** Copies of the packet last read, still owed to the further pages [fanOut] chose from its stream. */
    private val copies = ArrayDeque<KiteFFmpegPacket>()

    /** Closes every owed copy except those for a track [keep] answers true for. */
    private fun dropCopies(keep: (Int) -> Boolean = { false }) {
        val iterator = copies.iterator()
        while (iterator.hasNext()) {
            val copy = iterator.next()
            if (keep(copy.streamIndex)) continue
            iterator.remove()
            copy.close()
        }
    }

    override val recordingPath: String? get() = recorder.path

    /**
     * Records every stream this source reads, except cover art, which is one picture the file
     * would never show again, and a subtitle format Matroska cannot hold.
     */
    override fun startRecording(path: String) {
        check(readStreams.isNotEmpty()) { "streams must be selected before a recording starts" }
        recorder.start(path, readStreams.filterNot { it.disposition.attachedPicture })
    }

    override fun stopRecording() {
        recorder.stop()
    }

    override fun interrupt(): Boolean {
        // A single volatile write on the format context; KiteFFmpeg documents this as
        // the one member callable while another thread is blocked in a read or seek.
        source.interrupt()
        // KiteFFmpeg reads that flag before it calls the bridge, never while the bridge waits
        // inside the item's reader, so the bridge ends that wait itself.
        bridge?.interrupt()
        return true
    }

    /**
     * KiteFFmpeg's pause (#441): RTSP's PAUSE once and then its keepalive whenever one is due, or
     * RTMP's pause command. Every other input answers false.
     */
    override fun pauseReading(): Boolean = source.pause()

    /** KiteFFmpeg's resume, which asks the sender to play on only while a pause is in effect. */
    override fun resumeReading(): Boolean = source.resume()

    override suspend fun readPacket(): PlayerPacket? {
        val reader = reader ?: error("selectStreams must be called before readPacket")
        copies.removeFirstOrNull()?.let { return it }
        // Only a packet read waits at the end of a file still being written (#430).
        growing?.waitAtEnd = true
        val read = try {
            reader.read()
        } finally {
            growing?.waitAtEnd = false
        }
        val packet = read ?: run {
            // FFmpeg skips what it cannot read, so a stream whose server stopped answering ends here too.
            hls?.failureAtEnd()?.let { throw it }
            return null
        }
        recorder.copy(packet)
        absorbLayout(packet)
        // The whole new set, from this packet on (#423), with an Ogg's comments over its container's.
        packet.newContainerTags?.let { containerTags = it }
        val comments = packet.newStreamTags?.takeIf { packet.streamIndex == commentStream }
        if (comments != null) commentTags = comments
        val newTags = if (packet.newContainerTags != null || comments != null) withListedTitle(containerTags + commentTags) else null
        if (newTags != null) tags.value = newTags
        val tracks = fanOut[packet.streamIndex]
        if (tracks != null) for (track in 1 until tracks.size) copies.addLast(KiteFFmpegPacket(packet.copy(), mapper, index = tracks[track]))
        val track = tracks?.first()
        val before = announced
        val after = layout
        if (after === before) return KiteFFmpegPacket(packet, mapper, index = track, newContainerTags = newTags)
        announced = after
        // Only what the engine sees: a change to a stream it is never shown is no change to it.
        return KiteFFmpegPacket(
            packet,
            mapper,
            index = track,
            newStreams = after.streams.takeIf { it != before.streams },
            newPrograms = after.programs.takeIf { it != before.programs },
            newContainerTags = newTags,
        )
    }

    /** The table the engine last saw, which a keyframe search can leave behind [layout]. */
    private var announced: Layout = layout

    /** Takes in the streams and programmes [packet] says changed, before anything reads its stream. */
    private fun absorbLayout(packet: Packet) {
        if (packet.newStreams == null && packet.newPrograms == null) return
        layout = Layout.of(
            packet.newStreams ?: source.streams,
            packet.newPrograms ?: source.programs,
            mapper,
            renditionNames = source.formatName == "hls",
        )
    }

    override suspend fun seekToKeyframe(target: Pts): Pts? {
        val reader = reader ?: error("selectStreams must be called before seeking")
        dropCopies()
        recorder.endForSeek()
        // [target] needs no conversion. KiteFFmpeg's seek already speaks the content-relative
        // timeline, and every timestamp this class produces is now on that same timeline.
        seekBackward(target.micros) { micros, floor ->
            reader.seek(micros, SeekDirection.Backward, notEarlierThan = floor)
        }
        // The container reader does not report where it landed. The engine finds out from the first decoded
        // frame, which is also how it detects an overshoot and decides whether to retry.
        return null
    }

    /**
     * Finds the keyframes either side of [target] by reading the first picture after a forward seek
     * and, for [KeyframeChoice.Closest], after a backward one, then lands with a backward seek aimed
     * at the chosen keyframe's own time, which KiteFFmpeg lands on exactly (#496). A source with no
     * picture has nothing to choose between, because every sound packet is a keyframe.
     */
    override suspend fun seekToKeyframe(target: Pts, choice: KeyframeChoice): Pts? {
        if (choice == KeyframeChoice.Before) return seekToKeyframe(target)
        val reader = reader ?: error("selectStreams must be called before seeking")
        val picture = readStreams.firstOrNull { it.type == MediaType.Video && !it.disposition.attachedPicture }
            ?: return seekToKeyframe(target)
        dropCopies()
        recorder.endForSeek()
        val after = keyframeAfter(reader, picture.index, target.micros)
        val aim = when {
            after == null -> target.micros
            choice == KeyframeChoice.After -> after
            else -> {
                seekBackward(target.micros) { micros, floor ->
                    reader.seek(micros, SeekDirection.Backward, notEarlierThan = floor)
                }
                val before = firstKeyframe(reader, picture.index, Long.MIN_VALUE)
                if (before != null && target.micros - before <= after - target.micros) target.micros else after
            }
        }
        seekBackward(aim) { micros, floor ->
            reader.seek(micros, SeekDirection.Backward, notEarlierThan = floor)
        }
        return null
    }

    /** The time of the first keyframe of [stream] that shows at or after [micros], or null when none follows. */
    private fun keyframeAfter(reader: PacketReader, stream: Int, micros: Long): Long? {
        try {
            reader.seek(micros, SeekDirection.Forward)
        } catch (failure: FFmpegException) {
            // FFmpeg refuses a forward seek with nothing after the target, and how depends on the
            // demuxer: the MP4 reader answers a bare -1, which reads as EPERM. Any refusal means
            // "take the one before", and the backward seek that follows reports a real failure
            // itself. An interrupt is the engine abandoning the seek, so it is never swallowed.
            when (failure.error) {
                is FFmpegError.Interrupted, is FFmpegError.OutOfMemory, is FFmpegError.Internal -> throw failure
                else -> return null
            }
        }
        return firstKeyframe(reader, stream, micros)
    }

    /**
     * Reads on to the first keyframe of [stream] that shows at or after [notBefore] and returns its
     * time, closing every packet it reads. Null at the end of the media, or past
     * [KEYFRAME_SEARCH_BYTES] of input, which bounds a stream whose keyframes are lost.
     */
    private fun firstKeyframe(reader: PacketReader, stream: Int, notBefore: Long): Long? {
        var bytes = 0L
        while (bytes < KEYFRAME_SEARCH_BYTES) {
            val packet = reader.read() ?: return null
            try {
                // The change still reaches the engine, on the next packet it reads.
                absorbLayout(packet)
                bytes += packet.sizeBytes
                if (packet.streamIndex != stream || !packet.isKeyframe) continue
                val shows = mapper.mapTimestamp(packet.ptsMicros)?.micros ?: continue
                if (shows >= notBefore) return shows
            } finally {
                packet.close()
            }
        }
        return null
    }

    override fun close() {
        closeInOrder(
            recorder::close,
            { dropCopies() },
            { reader?.close() },
            { reader = null },
            source::close,
        )
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

    /** The backend's profile knobs, applied to every VIDEO decoder opened here. */
    internal var videoDecoderOptions: Map<String, String> = emptyMap()
    internal var videoLowDelay: Boolean = false

    /** The media item's compiled video filter chain, or null for none. */
    internal var videoFilterDescription: String? = null

    /** The media item's audio filter chain, or null for none. */
    internal var audioFilterDescription: String? = null

    /**
     * Whether audio may open the platform's own decoder (see `platformAudioDecoder`).
     *
     * A knob and not a constant, because a platform decoder is a DIFFERENT decoder and not a faster
     * copy of the same one. Two conformant AAC decoders agree on the music and disagree in the last
     * bits, so any test that pins exact samples has to pick one and say which; `ReferencePcmTest`
     * turns this off for exactly that reason, since what it measures is the downmix matrix.
     */
    internal var preferPlatformAudioDecoder: Boolean = true

    /** The pre-open keys the demuxer never consumed, straight from KiteFFmpeg's funnel. */
    internal val unusedOpenOptions: List<String> get() = source.unusedOpenOptions

    internal fun openDecoder(
        index: Int,
        lowDelay: Boolean,
        decoder: DecoderId? = null,
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
        decoder: DecoderId? = null,
        hardwareAccel: HardwareAccel? = null,
        hardware: HwdecStatus = HwdecStatus.Software,
        continuity: VideoDecoderContinuity = VideoDecoderContinuity(),
        filter: String? = videoFilterDescription,
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
        // The graph runs on software frames only; the factory stands hardware down first.
        filterDescription = if (hardware == HwdecStatus.Software) filter else null,
        openingSkip = openingSkip(videoDecoderOptions["skip_frame"]),
    )

    /**
     * Low delay for audio: a player is waiting on these frames, and the decoder holding them back
     * for reordering costs latency for no benefit.
     */
    internal fun newAudioDecoder(
        stream: PlayerStreamInfo,
        decoder: DecoderId? = null,
    ): AudioDecoder =
        KiteFFmpegAudioDecoder(
            decoder = openDecoder(stream.index, lowDelay = true, decoder = decoder),
            stream = stream,
            mapper = mapper,
            // The container's answer, used until the decoder gives its own. A stream that declares no
            // layout, or one no mask can describe, reports null and the mixer falls back to the count.
            declaredChannelLayoutMask = kiteStream(stream.index).audio?.channelLayoutMask,
            filterDescription = audioFilterDescription,
        )

    /**
     * A decoder for the image subtitle stream [stream], such as Blu-ray, DVB or DVD subtitles. Built
     * here for the timestamp mapper, like the video and audio decoders. An image with no canvas size
     * is placed on the first video stream's picture.
     */
    internal fun newImageSubtitleDecoder(stream: PlayerStreamInfo): io.github.yuroyami.kiteplayer.spi.SubtitleDecoder =
        KiteFFmpegImageSubtitleDecoder(
            decoder = source.openSubtitleDecoder(kiteStream(stream.index)),
            mapper = mapper,
            fallbackCanvas = firstVideo?.videoSize,
        )

    /** A decoder for the caption stream [stream], such as a MOV `c608` track. */
    internal fun newCaptionDecoder(stream: PlayerStreamInfo): io.github.yuroyami.kiteplayer.spi.SubtitleDecoder =
        // CEA-608 captions show as they are sent (#542); the other formats carry their own times.
        (stream.codec == "eia_608").let { realTime ->
            KiteFFmpegCaptionDecoder(decoder = source.openSubtitleDecoder(kiteStream(stream.index), realTime), mapper = mapper, realTime = realTime)
        }

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
 * How much input a keyframe choice reads looking for the next keyframe before it gives up and takes
 * the one before: the same 32 MB KiteFFmpeg's backward seek reads to check its own landing.
 */
private const val KEYFRAME_SEARCH_BYTES: Long = 32L * 1024 * 1024

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
 * suppression neither jumps backward to zero nor advances the timeline twice. The two warning
 * latches are also per stream, not per wrapper, and deliberately survive seeks.
 */
internal class VideoDecoderContinuity {
    private var lastPts: Pts? = null
    private var replaySeed: Pts? = null
    private var replaySeedPending: Boolean = false
    private var colorWarningClaimed: Boolean = false
    private var dolbyVisionWarningClaimed: Boolean = false
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

    /** The latch of the warning that a Dolby Vision frame came without the RPU to compose it. */
    internal fun claimDolbyVisionWarning(): Boolean {
        if (dolbyVisionWarningClaimed) return false
        dolbyVisionWarningClaimed = true
        return true
    }

    /** The latch of the warning that the container's crop does not fit a decoded frame. */
}

/** The media library's HDR metadata in the player's type, or null when it holds nothing usable. */
internal fun io.github.yuroyami.kiteffmpeg.HdrMetadata.toPlayerHdr(): HdrStaticMetadata? {
    fun io.github.yuroyami.kiteffmpeg.Rational.finite(): Float? = if (den == 0) null else asFloat.takeIf { it.isFinite() }
    val luminance = masteringDisplay?.luminance
    val metadata = HdrStaticMetadata(
        masteringPrimaries = masteringDisplay?.primaries?.let { p ->
            val values = listOf(p.redX, p.redY, p.greenX, p.greenY, p.blueX, p.blueY, p.whiteX, p.whiteY).map { it.finite() }
            if (values.any { it == null }) {
                null
            } else {
                DisplayPrimaries(values[0]!!, values[1]!!, values[2]!!, values[3]!!, values[4]!!, values[5]!!, values[6]!!, values[7]!!)
            }
        },
        masteringMinNits = luminance?.min?.finite(),
        masteringMaxNits = luminance?.max?.finite()?.takeIf { it > 0f },
        maxContentLightNits = contentLight?.maxCll?.takeIf { it > 0 },
        maxFrameAverageNits = contentLight?.maxFall?.takeIf { it > 0 },
    )
    return metadata.takeUnless { it == HdrStaticMetadata() }
}

/**
 * This stream as the engine sees it. With [renditionNames], for an HLS input, a stream with no
 * title of its own takes its rendition's `NAME`, which FFmpeg's HLS reader files under `comment`
 * (#404), unless that name only repeats the stream's language.
 */
internal fun StreamInfo.toPlayerStream(mapper: TimestampMapper, renditionNames: Boolean = false): PlayerStreamInfo? {
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
        title = title ?: metadata["comment"]?.trim()?.takeIf { name ->
            renditionNames && name.isNotEmpty() && !name.equals(language, ignoreCase = true)
        },
        isDefault = disposition.default,
        isForced = disposition.forced,
        isAccessibility = disposition.hearingImpaired || disposition.visualImpaired,
        isCommentary = disposition.comment,
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
        // The rest of the same matrix: a mirror that the renderer applies before the turn.
        mirrored = mirrored,
        frameRate = video?.frameRate?.let { if (it.den == 0) null else it.num.toDouble() / it.den },
        colorSpace = video?.let { it.color.toPlayerColorSpace(it.pixelFormat) },
        // A stream with exactly one frame of cover art must never carry the timeline or drive
        // synchronisation. Treating it as normal video makes the player hang at the end of every
        // audio file that has album art.
        isCoverArt = disposition.attachedPicture,
        // FFmpeg says 0 for what it does not know yet, as for a transport stream's sound listed from
        // its programme table before any of its packets was parsed, and 0 is no rate (#509).
        sampleRate = audio?.sampleRate?.takeIf { it > 0 },
        channels = audio?.channels?.takeIf { it > 0 },
        hdr = video?.hdr?.toPlayerHdr(),
        dolbyVision = video?.dolbyVision?.let { config ->
            DolbyVisionInfo(
                profile = config.profile,
                level = config.level,
                baseLayerCompatibility = config.baseLayerCompatibility,
                hasEnhancementLayer = config.hasEnhancementLayer,
            )
        },
        fieldOrder = when (video?.fieldOrder) {
            io.github.yuroyami.kiteffmpeg.FieldOrder.Progressive -> io.github.yuroyami.kiteplayer.spi.FieldOrder.Progressive
            io.github.yuroyami.kiteffmpeg.FieldOrder.TopFirst -> io.github.yuroyami.kiteplayer.spi.FieldOrder.TopFirst
            io.github.yuroyami.kiteffmpeg.FieldOrder.BottomFirst -> io.github.yuroyami.kiteplayer.spi.FieldOrder.BottomFirst
            else -> io.github.yuroyami.kiteplayer.spi.FieldOrder.Unknown
        },
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
        // As the container states it. Whether it fits is a question for each decoded frame, whose
        // size can differ from the one declared here (#497).
        crop = video?.crop?.let { PictureCrop(top = it.top, bottom = it.bottom, left = it.left, right = it.right) }
            ?.takeUnless { it.isEmpty },
    )
}

/**
 * The source's selectable streams as the engine sees them, keyed back to KiteFFmpeg's own entries,
 * and the programmes over them. A teletext stream is one entry for each subtitle page it carries,
 * all keyed back to the one stream (#510).
 */
private class Layout(
    val streams: List<PlayerStreamInfo>,
    val byIndex: Map<Int, StreamInfo>,
    val programs: List<MediaProgram>,
) {
    companion object {
        fun of(raw: List<StreamInfo>, rawPrograms: List<Program>, mapper: TimestampMapper, renditionNames: Boolean): Layout {
            val selectable = raw.flatMap { stream ->
                val exposed = stream.toPlayerStream(mapper, renditionNames) ?: return@flatMap emptyList()
                if (exposed.codec == TELETEXT) teletextPages(exposed).map { stream to it } else listOf(stream to exposed)
            }
            val byIndex = selectable.associate { (stream, exposed) -> exposed.index to stream }
            val tracksOf = selectable.groupBy({ (stream, _) -> stream.index }, { (_, exposed) -> exposed.index })
            val programs = rawPrograms
                .mapNotNull { program ->
                    val number = program.number ?: return@mapNotNull null
                    MediaProgram(
                        number = number,
                        tracks = program.streamIndexes.flatMap { tracksOf[it].orEmpty() }.map(::TrackId),
                        name = program.serviceName,
                        provider = program.serviceProvider,
                        metadata = program.metadata,
                    )
                }
                .distinctBy { it.number }
            return Layout(selectable.map { it.second }, byIndex, programs)
        }
    }
}

internal class KiteFFmpegPacket(
    val native: Packet,
    private val mapper: TimestampMapper,
    override val newStreams: List<PlayerStreamInfo>? = null,
    override val newPrograms: List<MediaProgram>? = null,
    /** The track this packet goes to when that is not its stream, as for a teletext page (#510). */
    private val index: Int? = null,
    override val newContainerTags: Map<String, String>? = null,
) : PlayerPacket {
    override val streamIndex: Int get() = index ?: native.streamIndex
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
    internal fun copyForReplay(): KiteFFmpegPacket = KiteFFmpegPacket(native.copy(), mapper, index = index)
    override fun close() = native.close()
}

/**
 * Creates the platform-selected decoder and, where policy allows, its replay-safe software fallback.
 */
public class KiteFFmpegVideoDecoderFactory internal constructor(
    private val source: KiteFFmpegSource,
) : VideoDecoderFactory {
    override val name: String = "KiteFFmpeg FFmpeg"

    /** Without a deinterlacing policy, as before the policy existed: no deinterlacer. */
    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? =
        create(stream, hwdec, DeinterlacePolicy.Off)

    override suspend fun create(
        stream: PlayerStreamInfo,
        hwdec: HwdecPolicy,
        deinterlace: DeinterlacePolicy,
    ): VideoDecoder? {
        if (stream.kind != TrackKind.Video) return null
        val selection = platformDecoderSelection(stream.codec, hwdec)
        if (selection.requiresHardware && selection.hardware == null) return null
        val filter = videoFilterChain(stream, deinterlace)

        // A video filter runs on software frames: under Auto and Prefer the hardware
        // route stands down with a warning; under Require the two demands cannot both hold and
        // the refusal is this factory's null, which the engine reports typed.
        if (filter != null && selection.hardware != null) {
            source.onWarning(
                PlaybackWarning.HardwareDecodeUnavailable(
                    stream.codec,
                    if (source.videoFilterDescription != null) {
                        "a video filter is attached and filters run on software frames"
                    } else {
                        "the stream is deinterlaced, and the deinterlacer runs on software frames"
                    },
                ),
            )
            return if (hwdec == HwdecPolicy.Require) null else source.newVideoDecoder(stream, filter = filter)
        }

        // MediaCodec hands back pictures without the RPU, so a stream whose base layer needs it
        // plays in software. VideoToolbox and Direct3D frames keep the RPU, and are downloaded
        // for the composer.
        val dolbyVision = stream.dolbyVision
        if (dolbyVision != null && !dolbyVision.baseLayerPlaysAlone && selection.hardware?.kind == HwdecKind.MediaCodec) {
            source.onWarning(
                PlaybackWarning.HardwareDecodeUnavailable(
                    stream.codec,
                    "the stream is Dolby Vision profile ${dolbyVision.profileName}, and MediaCodec hands back " +
                        "pictures without the RPU that composes them",
                ),
            )
            return if (hwdec == HwdecPolicy.Require) null else source.newVideoDecoder(stream, filter = filter)
        }

        if (selection.hardware == null) return source.newVideoDecoder(stream, filter = filter)
        return openSelected(stream, selection, filter)
    }

    /**
     * Opens [stream] on [selection]'s hardware route, with the software fallback its policy allows.
     * Apart from [create] so that a test can hand it a route this platform would not choose.
     */
    internal suspend fun openSelected(stream: PlayerStreamInfo, selection: DecoderSelection, filter: String?): VideoDecoder? {
        val continuity = VideoDecoderContinuity()

        return openDecoderWithFallback(
            stream = stream,
            selection = selection,
            open = { route ->
                source.newVideoDecoder(
                    stream = stream,
                    decoder = when (route) {
                        is HardwareRoute.NamedDecoder -> route.decoder
                        is HardwareRoute.Accel -> route.decoder
                        null -> null
                    },
                    hardwareAccel = (route as? HardwareRoute.Accel)?.accel,
                    // HardwareWithDownload is the honest status for BOTH shapes: mediacodec
                    // downloads inside FFmpeg's wrapper, and every current renderer reads a
                    // VideoToolbox frame through the download twin. This changes when the
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

    /**
     * The media item's filter chain, with a deinterlacer in front when [policy] and the stream's
     * field order ask for one. A build without the deinterlacer, which is the web build, warns and
     * plays the stream as it is rather than failing at the first frame.
     */
    private fun videoFilterChain(stream: PlayerStreamInfo, policy: DeinterlacePolicy): String? {
        val itemFilter = source.videoFilterDescription
        val deinterlacer = deinterlaceFilter(policy, stream.fieldOrder) ?: return itemFilter
        if (!io.github.yuroyami.kiteffmpeg.FFmpeg.hasFilter(DEINTERLACER)) {
            source.onWarning(PlaybackWarning.DeinterlaceUnavailable(stream.codec, "this build has no $DEINTERLACER filter"))
            return itemFilter
        }
        return if (itemFilter == null) deinterlacer else "$deinterlacer,$itemFilter"
    }
}

/**
 * The skip level the `skip_frame` decoder option names, by FFmpeg's own names for it, or
 * [DecoderSkip.None] when there is none. FFmpeg's `default` skips only empty packets, which no
 * decoder treats differently from `none`.
 */
internal fun openingSkip(option: String?): DecoderSkip = when (option?.trim()) {
    "noref" -> DecoderSkip.NonReference
    "bidir" -> DecoderSkip.Bidirectional
    "nointra" -> DecoderSkip.NonIntra
    "nokey" -> DecoderSkip.NonKey
    "all" -> DecoderSkip.All
    else -> DecoderSkip.None
}

/** The deinterlacer: FFmpeg's bwdif, which keeps the frame rate in send_frame mode. */
private const val DEINTERLACER = "bwdif"

/**
 * The deinterlacing filter [policy] asks for on a stream of [fieldOrder], or null for none. Under
 * [DeinterlacePolicy.Auto] only a stream the container calls interlaced gets one, and it touches
 * only the frames marked interlaced; [DeinterlacePolicy.Always] deinterlaces every frame.
 */
internal fun deinterlaceFilter(policy: DeinterlacePolicy, fieldOrder: FieldOrder): String? = when (policy) {
    DeinterlacePolicy.Off -> null
    DeinterlacePolicy.Auto -> if (fieldOrder.isInterlaced) "$DEINTERLACER=mode=send_frame:parity=auto:deint=interlaced" else null
    DeinterlacePolicy.Always -> "$DEINTERLACER=mode=send_frame:parity=auto:deint=all"
}

/** Two frame times this close are one time written in two time bases: a millisecond. */
private const val SAME_TIME_US: Long = 1_000

/** A decoded picture, composed when its stream needs it, and the peak of its scene when its RPU says. */
private class DecodedPicture(val picture: KiteFrame, val sceneMaxNits: Float?)

private class KiteFFmpegVideoDecoder(
    private val decoder: StreamDecoder,
    private val stream: PlayerStreamInfo,
    private val mapper: TimestampMapper,
    override val hardware: HwdecStatus,
    private val continuity: VideoDecoderContinuity,
    private val warn: (PlaybackWarning) -> Unit,
    /** The compiled filter chain every decoded frame runs through, or null for none. */
    private val filterDescription: String? = null,
    /** The frames the decoder was opened to skip, which [skipNonReferenceFrames] goes back to. */
    private val openingSkip: DecoderSkip = DecoderSkip.None,
) : VideoDecoder {

    private var generation: Generation = Generation.Initial
    private var skippingNonReference = false

    /** The graph, built from the first decoded frame's own geometry and format and rebuilt when that changes. */
    private var filterGraph: io.github.yuroyami.kiteffmpeg.FilterGraph? = null
    private var graphInput: List<Any?>? = null
    private val filteredPending = ArrayDeque<DecodedPicture>()
    private var filterFlushed = false

    override suspend fun send(packet: PlayerPacket?): Boolean =
        decoder.send((packet as KiteFFmpegPacket?)?.native)

    /**
     * FFmpeg's `skip_frame` at its non-reference level, raised and lowered between packets (#468).
     *
     * A decoder opened to skip more, such as the scrubbing profile's keyframes only, already skips
     * every frame this would and keeps its own level. A decoder with a filter graph decodes every
     * frame, because a filter such as the deinterlacer reads the frames beside the one it gives,
     * so the first picture kept would differ from the one an unskipped run gives.
     */
    override fun skipNonReferenceFrames(skip: Boolean) {
        if (skip == skippingNonReference) return
        skippingNonReference = skip
        if (filterDescription != null || openingSkip >= DecoderSkip.NonReference) return
        decoder.setSkipFrame(if (skip) DecoderSkip.NonReference else openingSkip)
    }

    /** KiteFFmpeg's own flag, set when its `receive` saw the end of the stream and cleared by flush. */
    override val isDrained: Boolean
        // A graph that was never built has nothing to flush: the lazy build waits
        // for the first decoded frame, and a stream that never produced one used to hold the
        // whole end of stream off for ever through the filterFlushed flag it could never set.
        get() = decoder.isDrained &&
            (filterDescription == null || filterGraph == null || (filterFlushed && filteredPending.isEmpty()))

    override suspend fun receive(): VideoFrame? {
        val decoded = nextDecodedFrame() ?: return null
        val frame = decoded.picture
        val duration = mapper.mapDuration(frame.durationMicros)
        val info = frame.info
        val pts = continuity.timestamp(
            real = mapper.mapTimestamp(frame.ptsMicros),
            duration = duration,
            frameRate = stream.frameRate,
            isKeyframe = info.isKeyframe,
        )
        // The rotation is the stream's, taken from the container's display matrix once at open. Every
        // frame of the stream carries it, because the renderer sees frames and nothing else. So does
        // the container's crop, which FFmpeg's decoder never applies (#497).
        val wrapped = KiteFFmpegVideoFrame(
            frame, pts, duration, generation, stream.rotationDegrees, stream.mirrored, stream.hdr, decoded.sceneMaxNits,
            crop = cropFitting(info.width, info.height),
        )
        try {
            warnIfColorIsApproximated(wrapped.colorSpace)
        } catch (failure: Throwable) {
            wrapped.close()
            throw failure
        }
        return wrapped
    }

    /**
     * The decoder's next frame, run through the attached graph when one is attached.
     *
     * The graph is built from the first frame's own width, height, format, time base and rate,
     * which is the only honest moment to build it: the container's declared parameters can lie
     * and the decoder's output cannot. A graph takes one input shape, so a picture whose size,
     * format or pixel shape moves mid-stream gets a new graph, after the old one gave back what it
     * held (#484), as the `ffmpeg` command line does. Timestamps pass through in the stream's own time base, so
     * the supported chains are the timebase-preserving ones (scale, crop, eq, format and
     * friends); fps-changing chains are the KD roadmap's own next step and refuse nothing today
     * because their output time base would silently disagree with the stream's.
     */
    private fun nextDecodedFrame(): DecodedPicture? {
        val description = filterDescription ?: return decoder.receive()?.let(::readDolbyVision)
        while (filteredPending.isEmpty()) {
            val decoded = decoder.receive()
            if (decoded == null) {
                if (decoder.isDrained && filterGraph != null && !filterFlushed) {
                    filterFlushed = true
                    filterGraph?.flushInput(0) { out -> filteredPending.addLast(DecodedPicture(out.copy(), scenePeakOf(out))) }
                    continue
                }
                return null
            }
            // Composed first, so a filter sees the picture rather than Dolby Vision's IPT signal.
            val picture = readDolbyVision(decoded)
            val raw = picture.picture
            val info = raw.info
            val input = listOf(info.width, info.height, info.pixelFormat, info.sampleAspectRatio)
            if (filterGraph != null && input != graphInput) {
                retireGraph { out -> filteredPending.addLast(DecodedPicture(out.copy(), scenePeakOf(out))) }
            }
            val graph = filterGraph ?: try {
                io.github.yuroyami.kiteffmpeg.FilterGraph.buildVideo(
                    description = description,
                    width = info.width,
                    height = info.height,
                    pixelFormat = info.pixelFormat,
                    timeBase = info.timeBase,
                    frameRate = frameRateRational(),
                    sampleAspectRatio = info.sampleAspectRatio,
                )
            } catch (failure: Throwable) {
                // Only feedInput takes the frame, so a graph that cannot be built leaves it here (#263).
                raw.close()
                throw failure
            }.also {
                filterGraph = it
                graphInput = input
            }
            scenePeaksInGraph.addLast(raw.ptsMicros to picture.sceneMaxNits)
            // feedInput owns and closes the raw frame; every output is copied out of the callback.
            graph.feedInput(0, raw) { out -> filteredPending.addLast(DecodedPicture(out.copy(), scenePeakOf(out))) }
        }
        return filteredPending.removeFirst()
    }

    /**
     * The scene peaks of the frames inside the filter graph, in the order they went in, by their
     * times in microseconds. A filter such as the deinterlacer gives a frame back an input later
     * than it took it, and in a time base of its own, so an output takes the peak of the input at
     * its own time, not of the last one in.
     */
    private val scenePeaksInGraph = ArrayDeque<Pair<Long?, Float?>>()

    /** The scene peak of the input [out] came from, forgetting the inputs before it, which the graph dropped. */
    private fun scenePeakOf(out: KiteFrame): Float? {
        val time = out.ptsMicros ?: return scenePeaksInGraph.removeFirstOrNull()?.second
        while (scenePeaksInGraph.isNotEmpty() && (scenePeaksInGraph.first().first ?: Long.MIN_VALUE) < time - SAME_TIME_US) {
            scenePeaksInGraph.removeFirst()
        }
        val (inputTime, peak) = scenePeaksInGraph.firstOrNull() ?: return null
        return peak.takeIf { inputTime != null && inputTime <= time + SAME_TIME_US }
    }

    /** Whether this stream's frames mean nothing until they are composed with their RPU. */
    private val composesDolbyVision: Boolean = stream.dolbyVision?.baseLayerPlaysAlone == false

    /**
     * [frame] as a renderer should see it, with its scene's peak from the RPU it carries.
     *
     * Every frame of a Dolby Vision stream is read for its level 1, which profile 8 benefits from
     * as much as profile 5. A stream whose base layer does not play alone is composed into HDR10
     * here, in bands on the converter's row-slice threads. A hardware frame is downloaded first,
     * because the composer reads memory; a frame without an RPU passes as it came.
     */
    private fun readDolbyVision(frame: KiteFrame): DecodedPicture {
        if (stream.dolbyVision == null) return DecodedPicture(frame, null)
        val metadata = try {
            frame.dolbyVision()
        } catch (failure: Throwable) {
            frame.close()
            throw failure
        }
        if (metadata == null) {
            if (composesDolbyVision) warnUncomposed()
            return DecodedPicture(frame, null)
        }
        val sceneMaxNits = metadata.sceneBrightness?.let { DolbyVisionMetadata.nitsOfPq(it.maxPq).toFloat() }
        return DecodedPicture(if (composesDolbyVision) composeDolbyVision(frame) else frame, sceneMaxNits)
    }

    /** The HDR10 composition of [frame], which this takes and closes. */
    private fun composeDolbyVision(frame: KiteFrame): KiteFrame {
        val readable = if (frame.info.isHardware) {
            try {
                frame.downloadFromHardware()
            } finally {
                frame.close()
            }
        } else {
            frame
        }
        val composition = try {
            readable.beginDolbyVisionComposition()
        } catch (failure: Throwable) {
            readable.close()
            throw failure
        } ?: return readable
        val width = readable.info.width
        // The composition holds its own reference to the picture it reads.
        readable.close()
        return composition.use {
            parallelRowSlices(width, it.height) { start, end -> it.composeRows(start, end) }
            it.finish()
        }
    }

    /** Says once that a frame of a stream that needs composition came without the RPU to compose it. */
    private fun warnUncomposed() {
        if (!continuity.claimDolbyVisionWarning()) return
        warn(
            PlaybackWarning.ColorApproximated(
                "a frame of Dolby Vision profile ${stream.dolbyVision?.profileName} on stream ${stream.index} " +
                    "carries no RPU, so its base layer is shown as it is",
            ),
        )
    }

    private fun frameRateRational(): io.github.yuroyami.kiteffmpeg.Rational {
        val rate = stream.frameRate?.takeIf { it > 0.0 } ?: return io.github.yuroyami.kiteffmpeg.Rational(25, 1)
        return io.github.yuroyami.kiteffmpeg.Rational((rate * 1000).toInt(), 1000)
    }

    /**
     * Ends the graph's input, hands [output] every picture it still held, and closes it. The new
     * graph's pictures follow these, so the order stays the order the decoder gave (#484).
     */
    private fun retireGraph(output: (KiteFrame) -> Unit) {
        val graph = filterGraph ?: return
        filterGraph = null
        graphInput = null
        try {
            graph.flushInput(0, output)
        } finally {
            graph.close()
        }
    }

    private fun dropFilterState() {
        while (true) filteredPending.removeFirstOrNull()?.picture?.close() ?: break
        scenePeaksInGraph.clear()
        filterGraph?.close()
        filterGraph = null
        graphInput = null
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
    /**
     * The stream's crop when it leaves something of a [width] by [height] frame, else null. A crop
     * that leaves nothing is the file's mistake, and showing the whole picture beats showing none;
     * the engine, which sees the stream's crop beside each frame, says so once.
     */
    private fun cropFitting(width: Int, height: Int): PictureCrop? =
        stream.crop?.takeIf { it.fits(width, height) }

    private fun warnIfColorIsApproximated(color: ColorSpaceInfo) {
        val detail = when (color.matrix) {
            ColorMatrix.Bt2020Cl ->
                "BT.2020 constant luminance converted with the non-constant luminance matrix"
            // Its inverse runs the PQ curve between two matrices, which no converter here does.
            ColorMatrix.ICtCp -> "ICtCp converted with the BT.709 matrix"
            else -> return
        } + " on stream ${stream.index}"
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
    /** The media item's audio filter chain every decoded frame runs through, or null for none. */
    private val filterDescription: String? = null,
) : AudioDecoder {

    /** The graph, built from the first decoded frame's own format and rebuilt when that changes. */
    private var filterGraph: io.github.yuroyami.kiteffmpeg.FilterGraph? = null
    private var graphInput: List<Any?>? = null
    private val filteredPending = ArrayDeque<KiteFrame>()
    private var filterFlushed = false

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

    /**
     * KiteFFmpeg's own flag, set when its `receive` saw the end of the stream and cleared by flush,
     * and with a filter, only once the graph gave back its tail.
     */
    override val isDrained: Boolean
        get() = decoder.isDrained &&
            (filterDescription == null || filterGraph == null || (filterFlushed && filteredPending.isEmpty()))

    override suspend fun receive(): AudioBuffer? {
        val frame = nextFrame() ?: return null
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
        dropFilterState()
        generation = newGeneration
        // The sample counter measured a run that the seek ended. Nothing about it survives, so the
        // count restarts and waits for the first real timestamp of the new position, which every
        // container this backend can open provides.
        anchorMicros = 0
        samplesSinceAnchor = 0
    }

    override fun close() {
        dropFilterState()
        decoder.close()
    }

    /** The next frame for the engine: the decoder's own, or the filter graph's when there is a chain. */
    private fun nextFrame(): KiteFrame? {
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
            val input = listOf(info.sampleRate, info.sampleFormat, info.channelCount, info.channelLayoutMask)
            if (filterGraph != null && input != graphInput) {
                // The decoder changed its format mid-stream, and a graph takes one input format. The
                // old graph's tail, such as the window a tempo filter holds, comes out first (#484).
                retireGraph { out -> filteredPending.addLast(out.copy()) }
            }
            val graph = filterGraph ?: try {
                io.github.yuroyami.kiteffmpeg.FilterGraph.buildAudio(
                    description = description,
                    sampleRate = info.sampleRate,
                    sampleFormat = info.sampleFormat,
                    channels = info.channelCount,
                    timeBase = info.timeBase,
                    channelLayoutMask = info.channelLayoutMask,
                )
            } catch (failure: Throwable) {
                // Only feedInput takes the frame, so a graph that cannot be built leaves it here.
                raw.close()
                throw failure
            }.also {
                filterGraph = it
                graphInput = input
            }
            // feedInput owns and closes the raw frame; every output is copied out of the callback.
            graph.feedInput(0, raw) { out -> filteredPending.addLast(out.copy()) }
        }
        return filteredPending.removeFirst()
    }

    /** Ends the graph's input, hands [output] every frame it still held, and closes it (#484). */
    private fun retireGraph(output: (KiteFrame) -> Unit) {
        val graph = filterGraph ?: return
        filterGraph = null
        graphInput = null
        try {
            graph.flushInput(0, output)
        } finally {
            graph.close()
        }
    }

    private fun dropFilterState() {
        while (true) filteredPending.removeFirstOrNull()?.close() ?: break
        filterGraph?.close()
        filterGraph = null
        graphInput = null
        filterFlushed = false
    }
}

/**
 * A decoded video frame, still in native memory.
 *
 * The pixels are not copied here and they never reach Kotlin memory unless a renderer asks for them.
 * That is the whole point: a 1080p frame is 3.11 MB and a 4K 10-bit frame is 24.9 MB, so copying at
 * 60 frames a second would cost between 187 MB/s and 1.5 GB/s for nothing.
 */
public class KiteFFmpegVideoFrame internal constructor(
    /** The media library's frame. Internal, so no media library type is part of this module's surface. */
    internal val frame: KiteFrame,
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
    /** Whether the stream's display matrix also mirrors the picture. No default, for the same reason. */
    override val mirrored: Boolean,
    /** The stream's static HDR metadata, which a frame that carries none of its own reports. */
    private val streamHdr: HdrStaticMetadata? = null,
    /** The peak of this frame's scene, from the Dolby Vision RPU the decoder read, in nits. */
    override val sceneMaxNits: Float? = null,
    /** The container's crop, already checked to fit this frame. */
    override val crop: PictureCrop? = null,
) : VideoFrame, SoftwareReadableFrame {

    private val info = frame.info

    /** The decoder's own reading first, because a raw HEVC or MPEG-TS stream says it only in the bitstream. */
    override val hdr: HdrStaticMetadata? = info.hdr?.toPlayerHdr() ?: streamHdr

    override val size: VideoSize = VideoSize(
        width = info.width,
        height = info.height,
        pixelAspectNumerator = info.sampleAspectRatio.num,
        pixelAspectDenominator = info.sampleAspectRatio.den,
    )

    override val pixelFormat: PlayerPixelFormat = info.pixelFormat.toPlayerFormat()

    override val colorSpace: ColorSpaceInfo = info.color.toPlayerColorSpace(info.pixelFormat)

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
     * The A/53 caption bytes FFmpeg's decoder attached to this picture (#236). Unreadable side
     * data costs this picture's captions and nothing more.
     */
    override val closedCaptions: ByteArray? by lazy {
        try {
            frame.closedCaptions()
        } catch (unreadable: io.github.yuroyami.kiteffmpeg.FFmpegException) {
            null
        }
    }

    /**
     * The software twin of a VideoToolbox or Direct3D 11 frame, downloaded ONCE on first need and
     * owned by this wrapper. Lazy on purpose: a newest-wins renderer supersedes most frames without ever
     * reading pixels, and an eager download would pay 3 to 25 MB of copying for every one of
     * them. A renderer that can draw the CVPixelBuffer itself never triggers this.
     */
    private var downloadedTwin: KiteFrame? = null

    /**
     * The frame whose planes may be read: the frame itself when it is software, its downloaded
     * twin when it is a VideoToolbox or Direct3D 11 frame. Other hardware kinds refuse here,
     * because nothing in this backend downloads them and pretending otherwise would hide a wiring
     * bug.
     */
    internal fun readableFrame(): KiteFrame {
        if (!info.isHardware) return frame
        check(hardwareSurface?.downloadsToMemory() == true) {
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

    /** The downloaded copy's format for a VideoToolbox frame, which downloads it on first read. */
    override val planeFormat: PlayerPixelFormat by lazy {
        if (info.isHardware) readableFrame().info.pixelFormat.toPlayerFormat() else pixelFormat
    }

    private val planeLayout: List<PlaneSpec> by lazy {
        planeLayoutFor(planeFormat, info.width, info.height)
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
    private val samplesDecoded: Lazy<FloatArray> = lazy {
        decodeToFloat(frame.copyPlanesToByteArray(), info, format.channels)
    }
    private val samples: FloatArray by samplesDecoded

    override val frameCount: Int get() = info.sampleCount

    override fun copyChannel(channel: Int, into: FloatArray, offset: Int) {
        val channels = format.channels
        val source = if (channel < channels) channel else 0
        for (i in 0 until frameCount) {
            into[offset + i] = samples[i * channels + source]
        }
    }

    /** Decodes straight into [into], with no interleaved copy of its own, unless one was made already. */
    override fun copyInterleaved(into: FloatArray, offset: Int) {
        if (samplesDecoded.isInitialized()) {
            samples.copyInto(into, offset, 0, frameCount * format.channels)
        } else {
            decodeToFloatInto(frame.copyPlanesToByteArray(), info, format.channels, into, offset)
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
    else -> FloatArray(frameCount * format.channels).also { copyInterleaved(it) }
}
