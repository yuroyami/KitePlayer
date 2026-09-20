package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.scanMediaAudio
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.MediaBackend

/** Receives the decoded audio of [scanAudio], one block at a time, in media order. */
public fun interface AudioScanSink {
    /**
     * [interleaved] holds [frames] sample frames and is borrowed: copy whatever outlives the call.
     * The scan reads nothing more while this runs, so suspending here paces the whole scan.
     */
    public suspend fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat)
}

/**
 * A stretch of one audio track to scan, in the stream's own timeline.
 *
 * [from] seeks before decoding, and the scan starts at the first frame the container can begin at,
 * which is at or before [from]. [until] stops the scan at the first block that ends at or after it.
 * Both are inclusive of the blocks that straddle them, so two ranges that meet cover every sample
 * between them, and each covers a little more.
 */
public class AudioScanRange(
    /** Where to seek before decoding. Null starts at the beginning of the stream. */
    public val from: Pts? = null,
    /** Where to stop. Null decodes to the end of the stream. */
    public val until: Pts? = null,
) {
    init {
        require(from == null || until == null || from <= until) { "a scan range ends before it starts" }
    }
}

/** What one [scanAudio] call covered. */
public class AudioScanResult internal constructor(
    /** The audio track that was decoded. */
    public val track: TrackId,
    /** Sample frames handed to the sink. */
    public val framesDecoded: Long,
    /** The timestamp of the first block, or null when the track decoded to nothing. */
    public val firstPts: Pts?,
    /** Where the last block ended, or null when the track decoded to nothing. */
    public val endPts: Pts?,
    /** Whether the decoder reported the end of the stream. */
    public val reachedEnd: Boolean,
)

/**
 * Decodes one audio track of [media] without an output device, all of it or one [range] of it.
 *
 * Opens its own session through [backend], so it never touches the reader of a playing session.
 * The decoder comes from the same factory list, in the same order, as playback, and every buffer
 * is interleaved the way the playback feed does. The sink therefore receives the samples and
 * timestamps an [AudioTap] would, before any seek trim. [track] names the audio track; null picks
 * the container's default ordinary track, else the first ordinary one.
 *
 * A [range] seeks first, so it needs a seekable item. A container that seeks by estimate lands at
 * or before the asked-for time, and the blocks carry the timestamps they really have, so a caller
 * that needs an exact boundary reads it from them rather than assuming the seek was exact.
 *
 * Reads block in the caller's context, so call this from one that may block. Cancel the calling
 * coroutine to stop: the decoder and the session are closed before this returns or throws.
 *
 * @throws IllegalArgumentException when [track] is not an audio track of [media], or it has none.
 * @throws UnsupportedOperationException when no decoder accepts the track.
 */
public suspend fun scanAudio(
    media: MediaItem,
    backend: MediaBackend,
    track: TrackId? = null,
    range: AudioScanRange? = null,
    sink: AudioScanSink,
): AudioScanResult = scanMediaAudio(backend, media, track, emptyList(), range, sink)
