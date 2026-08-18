package io.github.yuroyami.kiteplayer

import kotlin.jvm.JvmInline

/**
 * Identifies one track for the life of one opened media item.
 *
 * This is the stream index inside the container for tracks that came from the container, and a
 * negative value for tracks the application added, for example an external subtitle file. Both are
 * opaque to callers: pass back what [Tracks] gave you.
 */
@JvmInline
public value class TrackId(public val value: Int) {
    public val isExternal: Boolean get() = value < 0
    override fun toString(): String = if (isExternal) "external${-value}" else "stream$value"
}

public enum class TrackKind { Video, Audio, Subtitle }

/**
 * One selectable track.
 *
 * Everything here comes from the container, so every field can be missing or wrong. The engine
 * does not repair the metadata, it reports it, because an application showing a track list wants
 * to show what the file claims.
 */
public data class TrackInfo(
    val id: TrackId,
    val kind: TrackKind,
    val codec: String,
    /** BCP 47 where the container gives one, otherwise the raw three-letter code, otherwise null. */
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    /** The container marks this track as impaired-audience or hearing-impaired. */
    val isAccessibility: Boolean = false,
    val bitrate: Long? = null,
    // Video only.
    val videoSize: VideoSize? = null,
    val frameRate: Double? = null,
    // Audio only.
    val sampleRate: Int? = null,
    val channels: Int? = null,
    /**
     * True when this "video" track is a single still image, for example album art in an audio
     * file. Such a track is never the sync master and never carries the timeline.
     */
    val isCoverArt: Boolean = false,
) {
    /** A label suitable for a track menu, built from whatever the container actually provided. */
    public val label: String
        get() = buildString {
            title?.let { append(it) }
            if (isEmpty()) {
                language?.let { append(it) }
            } else {
                language?.let { append(" (").append(it).append(")") }
            }
            if (isEmpty()) append(codec)
            if (isForced) append(" [forced]")
        }
}

/** The tracks of the current media item, and which of them are selected. */
public data class Tracks(
    val all: List<TrackInfo> = emptyList(),
    val selectedVideo: TrackId? = null,
    val selectedAudio: TrackId? = null,
    /**
     * The selected subtitle track.
     *
     * Always null. A container's subtitle streams are listed in [all], because reporting what a file
     * holds is honest, and selecting one is refused with a typed error: no decoder in this build produces
     * a cue. Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
     */
    val selectedSubtitle: TrackId? = null,
) {
    public val video: List<TrackInfo> get() = all.filter { it.kind == TrackKind.Video }
    public val audio: List<TrackInfo> get() = all.filter { it.kind == TrackKind.Audio }
    public val subtitles: List<TrackInfo> get() = all.filter { it.kind == TrackKind.Subtitle }

    public fun selected(kind: TrackKind): TrackId? = when (kind) {
        TrackKind.Video -> selectedVideo
        TrackKind.Audio -> selectedAudio
        TrackKind.Subtitle -> selectedSubtitle
    }

    public fun find(id: TrackId): TrackInfo? = all.firstOrNull { it.id == id }

    internal fun withSelection(kind: TrackKind, id: TrackId?): Tracks = when (kind) {
        TrackKind.Video -> copy(selectedVideo = id)
        TrackKind.Audio -> copy(selectedAudio = id)
        TrackKind.Subtitle -> copy(selectedSubtitle = id)
    }

    public companion object {
        public val Empty: Tracks = Tracks()
    }
}
