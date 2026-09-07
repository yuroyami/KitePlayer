package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.inspectMedia
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import kotlin.time.Duration

/**
 * What a file says about itself, read without playing it.
 *
 * A library screen wants the length, the tracks and the chapters of a thousand files and wants to
 * play none of them. This is the answer to that question, and it holds exactly the fields
 * [KitePlayer.open] would have published, so a caller can show the same thing either way.
 */
public data class MediaInspection(
    /** Null when the container declares none, for example a live stream. */
    val duration: Duration?,
    val tracks: Tracks,
    /** The container's own tags: `title`, `artist`, and whatever else it wrote. */
    val metadata: Map<String, String>,
    val chapters: List<Chapter>,
    val seekable: Boolean,
    /** What the container claims its overall bit rate is, in bits per second. */
    val containerBitrateBps: Long? = null,
)

/**
 * Reads what [KitePlayer.open] would publish about [media], through [backend] alone.
 *
 * No player, no output device, no clock. A library screen listing a thousand files wants the
 * length and the tracks of every one of them and wants to play none, and building a player for
 * that would ask a device for an audio route it is never going to use.
 *
 * The session it makes to read the container is closed before this returns.
 *
 * @throws PlaybackException when the media cannot be reached or is not media.
 */
public suspend fun inspect(media: MediaItem, backend: MediaBackend): MediaInspection =
    inspectMedia(backend, media)
