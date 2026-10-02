package io.github.yuroyami.kiteplayer

import kotlin.time.Duration.Companion.milliseconds

/**
 * Builds a [MediaItem] for a Java app (#394), which cannot call its constructor: a parameter of
 * the type [kotlin.time.Duration] hides it from Java. Each setter is the field of that name and
 * returns this builder.
 *
 * ```java
 * MediaItem item = new MediaItemBuilder("https://example.com/movie.mp4")
 *         .title("Movie")
 *         .startPositionMillis(90_000)
 *         .build();
 * ```
 *
 * The fields for raw FFmpeg options and filters, and a reader of the app's own, are left out:
 * the first two need the low-level opt-in, and Java cannot write the suspending reader.
 */
public class MediaItemBuilder(uri: String) {

    private var item = MediaItem(uri)

    /** See [MediaItem.headers]. */
    public fun headers(headers: Map<String, String>): MediaItemBuilder = apply { item = item.copy(headers = headers) }

    /** See [MediaItem.externalSubtitles]. */
    public fun externalSubtitles(subtitles: List<SubtitleSource>): MediaItemBuilder =
        apply { item = item.copy(externalSubtitles = subtitles) }

    /** [MediaItem.startPosition], in milliseconds, or null to start at the beginning. */
    public fun startPositionMillis(millis: Long?): MediaItemBuilder =
        apply { item = item.copy(startPosition = millis?.milliseconds) }

    /** See [MediaItem.formatHint]. */
    public fun formatHint(hint: String?): MediaItemBuilder = apply { item = item.copy(formatHint = hint) }

    /** See [MediaItem.demux]. */
    public fun demux(policy: DemuxPolicy): MediaItemBuilder = apply { item = item.copy(demux = policy) }

    /** See [MediaItem.title]. */
    public fun title(title: String?): MediaItemBuilder = apply { item = item.copy(title = title) }

    /** See [MediaItem.artist]. */
    public fun artist(artist: String?): MediaItemBuilder = apply { item = item.copy(artist = artist) }

    /** See [MediaItem.album]. */
    public fun album(album: String?): MediaItemBuilder = apply { item = item.copy(album = album) }

    /** The item, checked as its constructor checks it. */
    public fun build(): MediaItem = item
}
