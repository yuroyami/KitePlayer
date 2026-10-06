package io.github.yuroyami.kiteplayer

/**
 * What the two front speakers play (#462), as a player's audio menu offers it. It acts on the
 * output after the downmix, so a surround film folded to two speakers obeys it too, and only the
 * first two channels move, as with the balance. A change while playing crossfades, so it never
 * clicks, and it is heard once the audio already buffered has played, as a balance change is.
 */
public enum class StereoMode {
    /** Each side plays its own channel. The default, and it costs nothing. */
    Stereo,

    /**
     * Both sides play the average of the two, for a listener with hearing in one ear or with one
     * earbud in. A side carries half of each channel, so nothing can pass full scale.
     */
    Mono,

    /** Both sides play the left channel, as for a commentary or a second language on the right. */
    LeftOnly,

    /** Both sides play the right channel. */
    RightOnly,

    /** The sides swap, for a file or a cable wired the wrong way round. */
    Swapped,
}
