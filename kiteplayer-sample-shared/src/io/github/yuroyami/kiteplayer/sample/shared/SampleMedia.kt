package io.github.yuroyami.kiteplayer.sample.shared

/** The audio file types every sample looks for, in the order it prefers them. */
val SONG_TYPES: List<String> = listOf("mp3", "m4a", "flac", "ogg", "wav", "aac")

/** One file the sample can open, with the name to show before its tags are read. */
data class SampleTrack(val path: String, val label: String)

/**
 * What the sample can play, in the order it offers them. [songMissing] is true when the only track
 * is the conformance clip, playing because no song file was found.
 */
data class SampleMedia(val tracks: List<SampleTrack>, val songMissing: Boolean) {
    init {
        require(tracks.isNotEmpty()) { "the sample needs at least one file to play" }
    }

    /** The file the sample opens on. */
    val path: String get() = tracks.first().path
}

/**
 * What the sample opens: the file asked for by name, else every song that is really there, else the
 * conformance clip.
 *
 * A song named in [songs] that is not on disk is dropped rather than offered, so a half-installed
 * set of songs still gives a working sample instead of a track that fails to open.
 */
fun sampleMedia(
    requested: String?,
    songs: List<String>,
    clip: String,
    exists: (String) -> Boolean,
): SampleMedia {
    if (requested != null) return SampleMedia(listOf(trackOf(requested)), songMissing = false)
    val found = songs.filter { it.isNotBlank() && exists(it) }
    return if (found.isEmpty()) {
        SampleMedia(listOf(trackOf(clip)), songMissing = true)
    } else {
        SampleMedia(found.map(::trackOf), songMissing = false)
    }
}

private fun trackOf(path: String) =
    SampleTrack(path, path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'))
