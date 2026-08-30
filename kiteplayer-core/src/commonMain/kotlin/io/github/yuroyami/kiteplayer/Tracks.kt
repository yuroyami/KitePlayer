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
    /**
     * The track's own container tags, verbatim.
     *
     * `language` and `title` above are the parsed, normalized readings of two of these; this is
     * everything the container wrote, including the keys this type has no field for. Empty when
     * the container carried none, which is common.
     */
    val metadata: Map<String, String> = emptyMap(),
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
     * The selected subtitle track: a container stream, or a negative id for an external file.
     *
     * Null means no subtitles are showing. Real since S4.c gave the engine a text cue path and S4.e
     * added external files; a container subtitle stream whose format no decoder in this build reads
     * is still refused with a typed error rather than selected and left silent.
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

/**
 * What happened to one [KitePlayer.selectTrack] call.
 *
 * There is a result type here because the answer is genuinely not a boolean and genuinely not
 * always success. Only one selection of a kind can be pending at a time, so two callers asking for
 * two different audio tracks at once means one of them does not get what it asked for, and the
 * engine used to complete BOTH of them normally: the loser was told its track was selected while a
 * different one was playing. A stop, a close and a call made against no open
 * media did the same. Each of those now has its own answer.
 *
 * A selection that fails because the media or the device failed still throws [PlaybackException],
 * and one made with a track id of the wrong kind, on an unseekable source, or for a format no
 * decoder reads still throws, because those are mistakes in the calling code. This type is for the
 * outcomes that are nobody's mistake.
 */
public sealed interface TrackChange {

    /** This request is the live selection: [track] of [kind] is what the player is using now. */
    public data class Applied(val kind: TrackKind, val track: TrackId?) : TrackChange

    /**
     * A later [KitePlayer.selectTrack] replaced this request before it ran, and [by] is what the
     * player applied instead. Nothing of this request is live.
     */
    public data class Superseded(val kind: TrackKind, val by: TrackId?) : TrackChange

    /**
     * The request ended without applying and without being replaced: a stop or a close arrived
     * first, or there was no longer an open media item to apply it to. [reason] says which.
     */
    public data class Discarded(val reason: String) : TrackChange
}
