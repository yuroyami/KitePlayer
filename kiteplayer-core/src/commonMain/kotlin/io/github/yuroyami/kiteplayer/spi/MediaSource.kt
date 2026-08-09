package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.Chapter
import io.github.yuroyami.kiteplayer.MediaItem
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
    public suspend fun open(media: MediaItem): PlayerMediaSource
}

public interface PlayerMediaSource : AutoCloseable {
    public val streams: List<PlayerStreamInfo>

    /** Null when unknown, for example a live stream. */
    public val duration: Pts?

    public val seekable: Boolean

    /** Container-level tags. Never trusted, always reported. */
    public val metadata: Map<String, String>

    public val chapters: List<Chapter>

    /**
     * True when this container may contain timestamp discontinuities, MPEG-TS above all.
     *
     * The engine uses this to choose between a 10 second and a 3600 second sanity ceiling on frame
     * durations, and to decide how large a timestamp jump is tolerable before a full reset. Getting
     * it wrong in either direction produces either a frozen picture at a splice or a spurious reset
     * on a normal file.
     */
    public val timestampsMayJump: Boolean

    /** Packets for streams outside this set are read and discarded by the source. */
    public fun selectStreams(indices: Set<Int>)

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
}

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
)

/**
 * One compressed packet.
 *
 * The payload is not exposed. A packet goes from the source to a decoder and nowhere else, and
 * neither of them is Kotlin code that needs to look at the bytes. Keeping the payload opaque is
 * what lets a native implementation pass an `AVPacket` pointer straight through with no copy.
 */
public interface PlayerPacket : AutoCloseable {
    public val streamIndex: Int

    /** Null when the container gave none, which is normal and not an error. */
    public val pts: Pts?
    public val dts: Pts?

    /** Null when the container gave none. */
    public val duration: Pts?

    public val isKeyframe: Boolean

    public val sizeBytes: Int

    /** Byte offset in the container, when known. Used for progress on streams with broken times. */
    public val bytePosition: Long?
}
