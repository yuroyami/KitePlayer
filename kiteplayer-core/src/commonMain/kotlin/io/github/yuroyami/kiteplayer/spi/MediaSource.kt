package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.Chapter
import io.github.yuroyami.kiteplayer.DolbyVisionInfo
import io.github.yuroyami.kiteplayer.KeyframeChoice
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoSize

/**
 * Opens media and produces a packet cursor over it.
 *
 * The engine never asks a source to decode, to buffer ahead, or to decide anything. A source is a
 * cursor and nothing more, which is what lets an FFmpeg source, a pure-Kotlin MP4 reader, a
 * WebCodecs-fed source and a scripted test fake all sit behind the same interface.
 */
public interface MediaSourceFactory {
    /** Opens [media] and returns a cursor at its start. Throws when the media cannot be read. */
    public suspend fun open(media: MediaItem): PlayerMediaSource
}

/** A packet cursor over one opened item. The engine closes it with its session. */
public interface PlayerMediaSource : AutoCloseable {
    /**
     * Every stream the container declares, including streams this build cannot decode.
     *
     * A container can add a stream while it plays, as a live transport stream does when its
     * programme table names a sound that starts after the open (#509). A source that sees one lists
     * it here from the read that found it on, at the end and under a new index, and hands the whole
     * list out on the next packet as [PlayerPacket.newStreams]. A stream is never taken out and never
     * changes its index. The engine reads this list from the actor while the demux lane reads, so a
     * source replaces it whole rather than changing it in place.
     */
    public val streams: List<PlayerStreamInfo>

    /** Null when unknown, for example a live stream. */
    public val duration: Pts?

    /**
     * True when [duration] is an estimate rather than a length the media states, such as FFmpeg's
     * guess from the bit rate of an ADTS AAC file or of an MP3 without its Xing header, which for
     * variable bit rate audio is minutes out (#422). The engine then cuts no seek, start position
     * or clock at it, and once playback passes it, or ends before it, reports the length played.
     */
    public val durationIsEstimate: Boolean get() = false

    /** False when the source can only read forward, such as a live stream. The engine then refuses seeks. */
    public val seekable: Boolean

    /**
     * Container-level tags. Never trusted, always reported. They can change during playback, and
     * each change also arrives on the packet that brings it, as [PlayerPacket.newContainerTags].
     */
    public val metadata: Map<String, String>

    /**
     * What the container declares as its overall bit rate, in bits per second.
     *
     * The container's own claim, not a measurement: the stats report both, and they disagree on
     * variable-bit-rate media by design. Null when the container declares none, which is normal
     * for a live stream. Defaulted so an existing source keeps compiling and keeps saying nothing.
     */
    public val containerBitrateBps: Long? get() = null

    /**
     * Fields where the container's declaration and the decoder disagree.
     *
     * Not an error, and not a reason to refuse anything: the file plays and the decoded numbers
     * are the ones to trust. It is worth telling, because a container that declares one picture
     * size and decodes another is exactly what a viewer sees as a wrong-sized picture, and an
     * audio device opened for a rate nothing feeds sounds like a broken player.
     *
     * Only fields the container ACTUALLY declared belong here. A container that says nothing is
     * not disagreeing, and most say nothing about audio sample format.
     *
     * Populated once the decoders have produced something, so it is empty before the first frame.
     * Defaulted so a source that cannot compare it keeps compiling and keeps saying nothing.
     */
    public val streamDivergences: List<StreamDivergence> get() = emptyList()

    /** The container's chapters, in order. Empty when it has none. */
    public val chapters: List<Chapter>

    /**
     * Files the container carries beside its streams, Matroska attachments above all. Fonts are
     * the ones that matter: a typeset ASS track ships the faces it was authored with, and the
     * subtitle typesetter loads every [MediaAttachment.isFont] entry before it draws. Empty for a
     * container that carries none, and for a source that cannot read them.
     */
    public val attachments: List<MediaAttachment> get() = emptyList()

    /**
     * True when this container may contain timestamp discontinuities, MPEG-TS above all.
     *
     * The engine uses this to choose between a 10 second and a 3600 second sanity ceiling on frame
     * durations, and to decide how large a timestamp jump is tolerable before a full reset. Getting
     * it wrong in either direction produces either a frozen picture at a splice or a spurious reset
     * on a normal file.
     */
    public val timestampsMayJump: Boolean

    /**
     * True when a sender pushes this stream at the pace it plays, as RTSP, RTMP, UDP and RTP do.
     *
     * Media from such a source arrives no faster than real time, so whatever has arrived and not
     * yet been heard is delay behind the sender, and a stall that is later made good leaves the
     * picture that much later for good unless the player catches up. The engine keeps that delay
     * bounded for a source that says true here, by playing a little faster for a while.
     *
     * A live HLS or DASH stream is not one: its segments arrive faster than they play, up to the
     * live edge, so its buffer says nothing about how far behind it is. Defaulted to false, so an
     * existing source keeps compiling and keeps playing at the speed it is given.
     */
    public val realTime: Boolean get() = false

    /**
     * True when each alternate sound arrives by a download of its own, as an HLS or a DASH audio
     * rendition does (#455), rather than in the same bytes as the picture, as every track of a file
     * does. Reading such a sound costs bandwidth whether anyone hears it or not, so the engine then
     * reads only the sound being heard. A switch to another one reads it from the moment playing,
     * by a seek of the reads that keeps what the other streams already hold when the source can
     * seek, and the sound being heard goes on until the new one covers that moment.
     *
     * False, the default, keeps every sound read into a cache, which is what makes a switch on a
     * file instant.
     */
    public val separateAudioRenditions: Boolean get() = false

    /**
     * The time of day at which [position] was broadcast, in milliseconds since 1970 UTC, for a
     * stream that states it, such as HLS with `EXT-X-PROGRAM-DATE-TIME` (#444). Null when the
     * stream states nothing, or nothing for that position.
     *
     * The engine calls this and the two members below from its own thread and from the callers'
     * threads while the demux lane reads, so a source keeps what they answer behind a lock. They
     * must return at once. Defaulted to null, so an existing source keeps compiling and states no
     * time.
     */
    public fun timeOfDayAt(position: Pts): Long? = null

    /** The position broadcast at [epochMillis], milliseconds since 1970 UTC, or null where the stream states none. */
    public fun positionAtTimeOfDay(epochMillis: Long): Pts? = null

    /**
     * The earliest and the latest moment the stream lists, in milliseconds since 1970 UTC, or null
     * when it states no time. For a live stream, the latest is the live edge.
     */
    public val timeOfDaySpan: LongRange? get() = null

    /**
     * Tells the sender of a [realTime] stream that the player has stopped reading because it is
     * paused (#441), and says whether the source has any notion of that: true when it has and is
     * now paused, false when it has none, in which case nothing was sent and the source reads on.
     *
     * An RTSP camera ends a session it has not heard from within its timeout, usually 60 seconds,
     * and a paused player reads nothing. So while paused the engine calls this again about every
     * second, and the source sends whatever keeps the session alive when it is due: KiteFFmpeg
     * sends RTSP's PAUSE once and then the keepalive, GET_PARAMETER or OPTIONS, at half the
     * session timeout. [resumeReading] asks the sender to play on.
     *
     * The engine calls it on the demux lane, between two reads, and reads nothing until
     * [resumeReading] succeeds. Defaulted to false, so an existing source keeps compiling and keeps
     * reading through a pause as it always did.
     *
     * @throws Exception when the sender or the connection refuses, for example because the
     *   server already ended the session. The engine then opens the stream again on play.
     */
    public fun pauseReading(): Boolean = false

    /**
     * Lifts a [pauseReading]: the sender plays on, which for a live stream is the live edge, so the
     * engine drops what it had buffered before the pause and plays what arrives from here (#441).
     * Called on the demux lane, only after a [pauseReading] that answered true.
     *
     * @return true when a pause was lifted, false when there was none
     * @throws Exception when the sender or the connection refuses, for example because the server
     *   ended the session while the player was paused. The engine then opens the stream again, at
     *   the live edge. Defaulted to false, as [pauseReading] is.
     */
    public fun resumeReading(): Boolean = false

    /**
     * Packets for streams outside this set are read and discarded by the source.
     *
     * Every index must be one this source offers. A set naming one it does not is a caller
     * mistake and an implementation must refuse the whole call, never quietly select the subset it
     * recognised: a caller that asked for two streams and silently got one has no way to find out.
     *
     * The engine calls this once before the first read, and again on the demux lane, between two
     * reads, to add a stream that [PlayerPacket.newStreams] announced (#509). Called again before the
     * read after the announcing packet, it should deliver the new stream from its first packet, as
     * KiteFFmpeg does by holding that stream's packets until then. The cursor does not move.
     *
     * @throws IllegalArgumentException when [indices] is empty or names a stream this source does
     *   not have.
     */
    public fun selectStreams(indices: Set<Int>)

    /**
     * Requests that a blocking call another lane is stuck inside return promptly with a typed
     * failure, and that every later blocking call on this source fail fast.
     *
     * One-way: an interrupted source is being abandoned, and the session that owns it is on its
     * way down. The engine calls this from the actor while the demux lane is wedged inside a
     * read or seek, which is the ONE concurrency this contract permits; it never runs
     * concurrently with, or after, the backend session's close.
     *
     * @return false when this source cannot interrupt, in which case the caller must keep
     *         waiting, exactly as every engine before this member existed did.
     */
    public fun interrupt(): Boolean = false

    /**
     * Reads the next packet from any selected stream.
     *
     * @return the packet, or null at the end of the media. The engine turns that null into the
     *         in-band drain signal each decoder needs.
     */
    public suspend fun readPacket(): PlayerPacket?

    /**
     * Moves the read cursor to a keyframe at or before [target].
     *
     * This call moves the cursor and nothing else. It does not flush queues and it does not flush
     * decoders, because only the engine knows the generation those flushes belong to. A source that
     * flushes on the caller's behalf makes the engine's seek
     * ordering rules impossible to honour.
     *
     * @return where the cursor actually landed, when the source can tell. Null means unknown, and
     *         the engine then discovers it from the first decoded frame.
     */
    public suspend fun seekToKeyframe(target: Pts): Pts?

    /**
     * Moves the read cursor to the keyframe [choice] names around [target], under the same rules as
     * the call without a choice.
     *
     * The engine resolves [KeyframeChoice.InSeekDirection] itself, so
     * [choice] is one of `Before`, `After` and `Closest`. `After` and `Closest` fall back to the
     * keyframe before [target] when none follows it.
     *
     * The default serves every choice as `Before`, which is what a source that cannot look for a
     * keyframe after the target can honestly do.
     */
    public suspend fun seekToKeyframe(target: Pts, choice: KeyframeChoice): Pts? =
        seekToKeyframe(target)

    /**
     * The versions of this media at other qualities, such as the variants of an HLS master
     * playlist, or empty for media with one version. The engine lists them in [io.github.yuroyami.kiteplayer.Tracks.variants].
     */
    public val variants: List<io.github.yuroyami.kiteplayer.StreamVariant> get() = emptyList()

    /** The [io.github.yuroyami.kiteplayer.StreamVariant.index] of the variant this source reads, or null. */
    public val selectedVariant: Int? get() = null

    /**
     * The seek bar pictures the media carries, such as a DASH thumbnail set or an HLS image
     * playlist, or null when it carries none (#433). The engine lists them in
     * [io.github.yuroyami.kiteplayer.Tracks.thumbnails].
     */
    public val thumbnails: PlayerThumbnails? get() = null

    /**
     * The channels of a multiplex, each a set of [streams] that play together, or empty when the
     * container declares none (#505). Every [io.github.yuroyami.kiteplayer.MediaProgram.tracks]
     * entry names one of [streams] by its index, and every number is one only that programme has.
     * The engine lists them in [io.github.yuroyami.kiteplayer.Tracks.programs] and, when there are
     * two or more, picks every track from one of them.
     *
     * A live transport stream can change them while it plays, as when a channel moves its sound to a
     * new stream at a programme boundary. A source that sees a change lists it here from the read that
     * found it on and hands it out on the next packet as [PlayerPacket.newPrograms] (#509).
     */
    public val programs: List<io.github.yuroyami.kiteplayer.MediaProgram> get() = emptyList()
}

/** What the container declares about one stream. */
public data class PlayerStreamInfo(
    val index: Int,
    val kind: TrackKind,
    val codec: String,
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isAccessibility: Boolean = false,
    val bitrate: Long? = null,
    val startTime: Pts? = null,
    // Video.
    val videoSize: VideoSize? = null,
    /**
     * Clockwise rotation the container asks a renderer to apply, in degrees.
     *
     * Zero for everything a camera did not turn on its side. See [VideoFrame.rotationDegrees] for what
     * it means to a renderer and why [videoSize] does not change with it.
     */
    val rotationDegrees: Int = 0,
    /** The container's declared frame rate. Used to snap measured durations. */
    val frameRate: Double? = null,
    val colorSpace: ColorSpaceInfo? = null,
    /** A single still image, for example album art. Never the sync master. */
    val isCoverArt: Boolean = false,
    /** Frames arrive rarely and irregularly, for example a slideshow. Not A/V synced. */
    val isSparse: Boolean = false,
    // Audio.
    val sampleRate: Int? = null,
    val channels: Int? = null,
    /** Typed VP9 sequence metadata. Present only for VP9; unknown declarations remain null inside. */
    val vp9: Vp9CodecConfiguration? = null,
    /**
     * An owned copy of the container's codec configuration record, such as avcC or hvcC. Null
     * when the stream has no separate configuration record.
     */
    val codecExtradata: ByteArray? = null,
    /**
     * The stream's own tags, as the container wrote them: `language`, `title`, `handler_name` and
     * whatever else it carried. Reported, never repaired.
     */
    val metadata: Map<String, String> = emptyMap(),
    /**
     * Whether a video stream is interlaced, and which field comes first. [FieldOrder.Unknown] on
     * most files, because most containers do not say, and never a synonym for progressive.
     */
    val fieldOrder: FieldOrder = FieldOrder.Unknown,
    /** The stream's static HDR metadata, or null when the container and the codec say none. */
    val hdr: HdrStaticMetadata? = null,
    /**
     * True when the container's display matrix also mirrors the picture. See
     * [VideoFrame.mirrored] for the order in which a renderer mirrors and turns it.
     */
    val mirrored: Boolean = false,
    /**
     * The Dolby Vision configuration the container declares, or null when the stream is not Dolby
     * Vision. When [DolbyVisionInfo.baseLayerPlaysAlone] is false, a frame means nothing until it is
     * composed with its RPU, so a decoder whose frames do not carry the RPU cannot play the stream.
     */
    val dolbyVision: DolbyVisionInfo? = null,
    /**
     * The container marks this stream as commentary, such as a director talking over the film.
     * Never chosen in place of the main mix for its channel count (#466).
     */
    val isCommentary: Boolean = false,
    /**
     * The edges of each stored picture that the container says are not part of the image, or null
     * when it says none (#497). [videoSize] stays the stored size; [visibleVideoSize] is what is
     * shown. See [PictureCrop].
     */
    val crop: PictureCrop? = null,
) {
    /**
     * The size of the picture as it is shown: [videoSize] with [crop]'s edges taken away, or
     * [videoSize] itself when the crop is absent or does not fit it.
     */
    val visibleVideoSize: VideoSize? get() = videoSize?.cropped(crop)

    /**
     * By CONTENT, including [codecExtradata].
     *
     * A data class holding a `ByteArray` gets an `equals` that compares that field by REFERENCE,
     * so two descriptions of the same stream came out unequal and a `Set` of them held duplicates.
     * The sibling's own `StreamInfo` has compared extradata by content from the start; this is the
     * same rule on this side of the boundary.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerStreamInfo) return false
        return index == other.index &&
            kind == other.kind &&
            codec == other.codec &&
            language == other.language &&
            title == other.title &&
            isDefault == other.isDefault &&
            isForced == other.isForced &&
            isAccessibility == other.isAccessibility &&
            bitrate == other.bitrate &&
            startTime == other.startTime &&
            videoSize == other.videoSize &&
            rotationDegrees == other.rotationDegrees &&
            frameRate == other.frameRate &&
            colorSpace == other.colorSpace &&
            isCoverArt == other.isCoverArt &&
            isSparse == other.isSparse &&
            sampleRate == other.sampleRate &&
            channels == other.channels &&
            vp9 == other.vp9 &&
            metadata == other.metadata &&
            fieldOrder == other.fieldOrder &&
            hdr == other.hdr &&
            mirrored == other.mirrored &&
            dolbyVision == other.dolbyVision &&
            isCommentary == other.isCommentary &&
            crop == other.crop &&
            (codecExtradata?.contentEquals(other.codecExtradata) ?: (other.codecExtradata == null))
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + kind.hashCode()
        result = 31 * result + codec.hashCode()
        result = 31 * result + (language?.hashCode() ?: 0)
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + isDefault.hashCode()
        result = 31 * result + isForced.hashCode()
        result = 31 * result + isAccessibility.hashCode()
        result = 31 * result + (bitrate?.hashCode() ?: 0)
        result = 31 * result + (startTime?.hashCode() ?: 0)
        result = 31 * result + (videoSize?.hashCode() ?: 0)
        result = 31 * result + rotationDegrees
        result = 31 * result + (frameRate?.hashCode() ?: 0)
        result = 31 * result + (colorSpace?.hashCode() ?: 0)
        result = 31 * result + isCoverArt.hashCode()
        result = 31 * result + isSparse.hashCode()
        result = 31 * result + (sampleRate ?: 0)
        result = 31 * result + (channels ?: 0)
        result = 31 * result + (vp9?.hashCode() ?: 0)
        result = 31 * result + metadata.hashCode()
        result = 31 * result + fieldOrder.hashCode()
        result = 31 * result + (hdr?.hashCode() ?: 0)
        result = 31 * result + mirrored.hashCode()
        result = 31 * result + (dolbyVision?.hashCode() ?: 0)
        result = 31 * result + isCommentary.hashCode()
        result = 31 * result + (crop?.hashCode() ?: 0)
        result = 31 * result + (codecExtradata?.contentHashCode() ?: 0)
        return result
    }
}

/** How a video stream's fields are ordered, in display order. */
public enum class FieldOrder {
    /** The container did not say. Not a synonym for [Progressive]. */
    Unknown,
    Progressive,

    /** Interlaced, top field shown first. */
    TopFirst,

    /** Interlaced, bottom field shown first. */
    BottomFirst,
    ;

    /** True for the two interlaced answers. */
    public val isInterlaced: Boolean get() = this == TopFirst || this == BottomFirst
}

/** The VP9 profile, level, bit depth and chroma subsampling the container declares. A null field was not declared. */
public data class Vp9CodecConfiguration(
    val profile: Vp9Profile?,
    val level: Vp9Level?,
    val bitDepth: Vp9BitDepth?,
    val chromaSubsampling: Vp9ChromaSubsampling?,
)

/** A VP9 profile. [number] is its number in the VP9 bitstream specification. */
public enum class Vp9Profile(public val number: Int) {
    Profile0(0),
    Profile1(1),
    Profile2(2),
    Profile3(3),
}

/** A VP9 level. [code] is the level times ten, as a codec string writes it. */
public enum class Vp9Level(public val code: Int) {
    Level1(10),
    Level1_1(11),
    Level2(20),
    Level2_1(21),
    Level3(30),
    Level3_1(31),
    Level4(40),
    Level4_1(41),
    Level5(50),
    Level5_1(51),
    Level5_2(52),
    Level6(60),
    Level6_1(61),
    Level6_2(62),
}

/** Bits per sample. */
public enum class Vp9BitDepth(public val bits: Int) {
    Eight(8),
    Ten(10),
    Twelve(12),
}

/** The chroma subsampling. [code] names it with the usual digits, such as 420. */
public enum class Vp9ChromaSubsampling(public val code: Int) {
    Monochrome(400),
    Yuv420(420),
    Yuv422(422),
    Yuv444(444),
}

/**
 * One compressed packet.
 *
 * The source-to-decoder handoff keeps the payload opaque so native backends can pass a referenced
 * packet through without copying it. Platform decoders that require Kotlin-visible bytes may opt
 * into the explicit copy provided by [copyBytes].
 */
public interface PlayerPacket : AutoCloseable {
    /** The stream this packet belongs to, as [PlayerStreamInfo.index]. */
    public val streamIndex: Int

    /** Null when the container gave none, which is normal and not an error. */
    public val pts: Pts?
    /** The decode time. Null when the container gave none. */
    public val dts: Pts?

    /** Null when the container gave none. */
    public val duration: Pts?

    /** True when a decoder can start at this packet. */
    public val isKeyframe: Boolean

    /** The size of the compressed payload. */
    public val sizeBytes: Int

    /**
     * Returns a new, caller-owned copy of the compressed payload.
     *
     * The returned array remains valid after this packet is closed and may be modified freely.
     * Avoid this on native decoder paths, where handing the opaque packet through is copy-free.
     */
    public fun copyBytes(): ByteArray

    /** Byte offset in the container, when known. Used for progress on streams with broken times. */
    public val bytePosition: Long?

    /**
     * The source's [PlayerMediaSource.streams] as they stand from this packet on, present only on the
     * first packet a source hands out after its list changed, and null on every other packet (#509).
     * It is the whole list: a stream is new when its index was not in the list before, and an entry
     * that differs from the one before was corrected, as FFmpeg corrects a sound's sample rate at its
     * first packet. Null for a source whose streams never change.
     */
    public val newStreams: List<PlayerStreamInfo>? get() = null

    /**
     * The source's [PlayerMediaSource.metadata] as it stands from this packet on, present only on
     * the first packet a source hands out after its tags changed, and null on every other packet
     * (#423): a radio station's next song, the comments of a chained Ogg's next song, an ID3 tag
     * between ADTS frames. The engine shows them when this packet is heard, not when it is read,
     * since a stream is read seconds ahead. Null for a source whose tags never change.
     */
    public val newContainerTags: Map<String, String>? get() = null

    /**
     * The source's [PlayerMediaSource.programs] as they stand from this packet on, present only on the
     * first packet a source hands out after they changed, and null on every other packet (#509). A
     * stream the container stopped carrying has left its programme here.
     */
    public val newPrograms: List<io.github.yuroyami.kiteplayer.MediaProgram>? get() = null
}

/**
 * One file attached to a container, as the container wrote it. See [PlayerMediaSource.attachments].
 *
 * [mimeType] is the container's own claim and may be missing or wrong, so [isFont] also accepts
 * the file extensions font files actually carry.
 */
public class MediaAttachment(
    /** The name the container stored the file under. */
    public val fileName: String,
    /** The media type the container declares, when it declares one. */
    public val mimeType: String?,
    /** SHARED, not copied: a font can be megabytes and is read once. Treat it as read-only. */
    public val data: ByteArray,
) {
    /** True for the TrueType and OpenType files a typesetter can shape with. */
    public val isFont: Boolean
        get() {
            val mime = mimeType?.lowercase()
            if (mime != null && (mime.startsWith("font/") || mime in FONT_MIME_TYPES)) return true
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return extension in FONT_EXTENSIONS
        }

    override fun toString(): String = "MediaAttachment($fileName, ${mimeType ?: "no type"}, ${data.size} bytes)"

    private companion object {
        val FONT_MIME_TYPES: Set<String> = setOf(
            "application/x-truetype-font", "application/x-font-ttf", "application/x-font-otf",
            "application/x-font-truetype", "application/x-font-opentype", "application/vnd.ms-opentype",
            "application/font-sfnt", "application/x-font", "application/octet-stream+font",
        )
        val FONT_EXTENSIONS: Set<String> = setOf("ttf", "otf", "ttc", "otc", "sfnt")
    }
}

/**
 * One field where a container's declaration and its decoder disagree.
 *
 * A core type rather than the media library's own, so a backend's dependency does not become part
 * of this project's public surface.
 */
public data class StreamDivergence(
    val streamIndex: Int,
    /** The field that disagrees, for example `Width` or `SampleRate`. */
    val field: String,
    /** What the container said, formatted for reading. */
    val declared: String,
    /** What the decoder actually produced. */
    val decoded: String,
)

/**
 * The seek bar pictures of a source (#433). [at] reads an image only when it is asked for, through
 * the source's own transport, and keeps a few recent ones, so a finger that scrubs back and forth
 * over one image downloads it once. Safe to call from any coroutine while the source is read.
 */
public interface PlayerThumbnails {
    /** What the pictures are: the size of a tile and the time one stands for. */
    public val set: io.github.yuroyami.kiteplayer.ThumbnailSet

    /**
     * The picture for [position], on the source's own timeline, with its start and end on that
     * timeline too, or null where no picture stands for it.
     */
    public suspend fun at(position: io.github.yuroyami.kiteplayer.Pts): io.github.yuroyami.kiteplayer.StreamThumbnail?
}
