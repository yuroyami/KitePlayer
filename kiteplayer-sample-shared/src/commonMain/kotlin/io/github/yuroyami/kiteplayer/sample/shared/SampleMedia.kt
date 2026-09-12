package io.github.yuroyami.kiteplayer.sample.shared

/**
 * A file for the sample to open. [songMissing] is true when it is the conformance clip, playing
 * because the song file is not there.
 */
data class SampleMedia(val path: String, val songMissing: Boolean)

/**
 * The file the sample opens: the one asked for by name, else the song when that file is there, else
 * the conformance clip.
 */
fun sampleMedia(requested: String?, song: String?, clip: String, exists: (String) -> Boolean): SampleMedia = when {
    requested != null -> SampleMedia(requested, songMissing = false)
    !song.isNullOrBlank() && exists(song) -> SampleMedia(song, songMissing = false)
    else -> SampleMedia(clip, songMissing = true)
}
