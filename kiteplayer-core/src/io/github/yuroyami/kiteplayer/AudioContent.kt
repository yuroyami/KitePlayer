package io.github.yuroyami.kiteplayer

/**
 * What an item's sound is, as the platform's own sound processing wants to know it (#446).
 *
 * Android builds the audio track and asks for audio focus with a content type, and iOS sets its
 * audio session's mode, and some devices choose their equaliser, their virtual surround or their
 * dialogue processing from the answer. On Android it is `CONTENT_TYPE_MUSIC`, `CONTENT_TYPE_SPEECH`
 * or `CONTENT_TYPE_MOVIE`. On iOS music is the default mode, speech is `spokenAudio`, under which
 * another app's spoken prompt pauses the player rather than ducking it, and a film is
 * `moviePlayback`. Other platforms have no such setting and ignore it.
 *
 * The answer is fixed when the audio device opens, at the open of each item and at a change of
 * audio track, so it changes between items and never in the middle of one. Items that follow each
 * other with no gap share the device the first one opened.
 */
public enum class AudioContent {
    /** [Movie] when the item shows a picture, cover art aside, and [Music] when it shows none. */
    Automatic,

    /** A song or other music. */
    Music,

    /** Speech, such as a podcast or an audiobook. */
    Speech,

    /** The soundtrack of a film, a show or any other video. */
    Movie,
    ;

    /** What this choice declares for an item that shows a picture when [hasPicture] is true. */
    internal fun resolve(hasPicture: Boolean): AudioContent = when (this) {
        Automatic -> if (hasPicture) Movie else Music
        else -> this
    }
}
