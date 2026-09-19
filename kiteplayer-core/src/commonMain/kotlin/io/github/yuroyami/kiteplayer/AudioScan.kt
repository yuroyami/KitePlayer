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
 * Decodes one audio track of [media] from its start to its end without an output device.
 *
 * Opens its own session through [backend], so it never touches the reader of a playing session.
 * The decoder comes from the same factory list, in the same order, as playback, and every buffer
 * is interleaved the way the playback feed does. The sink therefore receives the samples and
 * timestamps an [AudioTap] would, before any seek trim. [track] names the audio track; null picks
 * the container's default ordinary track, else the first ordinary one.
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
    sink: AudioScanSink,
): AudioScanResult = scanMediaAudio(backend, media, track, emptyList(), sink)
