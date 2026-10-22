package io.github.yuroyami.kiteplayer.internal

/** The codec of a subtitle track made from the lyrics of the media's own tags (#443). */
internal const val TAG_LYRICS_CODEC: String = "tag/lrc"

/** Lyrics a media file's tags carry, and the language the tag names, if it names one. */
internal class TagLyrics(val text: String, val language: String?)

/**
 * The lyrics in [metadata], as FFmpeg names the tags that hold them (#443): `lyrics` for an MP4
 * `©lyr` atom, an APE tag and a Vorbis or Matroska `LYRICS`, `lyrics-<lang>` or
 * `lyrics-<description>-<lang>` for an ID3 `USLT` frame, and `UNSYNCEDLYRICS` as some taggers write
 * it. Case is ignored, as FFmpeg ignores it. The first one with text wins; null when none has any.
 */
internal fun tagLyrics(metadata: Map<String, String>): TagLyrics? {
    for ((key, value) in metadata) {
        if (value.isBlank()) continue
        val name = key.lowercase()
        val language = when {
            name == "lyrics" || name == "unsyncedlyrics" || name == "unsynced lyrics" || name == "\u00A9lyr" -> null
            name.startsWith("lyrics-") ->
                name.substringAfterLast('-').takeIf { tag -> tag.length == 3 && tag.all { it in 'a'..'z' } && tag != "und" && tag != "xxx" }
            else -> continue
        }
        return TagLyrics(value.trim(), language)
    }
    return null
}

/**
 * Whether [text], an external file's or a tag's, is LRC lyrics (#443), so a file's track is labelled
 * as such and keeps its brackets, and a tag's becomes a track at all: the first line that is not blank opens with a tag, and some line opens with a
 * time stamp. The same test `LrcParser.isLrc` in kiteplayer-subtitles routes the file on, which this
 * module sits below and cannot call.
 */
internal fun looksLikeLrc(text: String): Boolean {
    val lines = text.lineSequence()
    val first = lines.firstOrNull { it.isNotBlank() }?.trim() ?: return false
    if (!first.startsWith('[')) return false
    return lines.any { LRC_STAMP.containsMatchIn(it.trim()) }
}

private val LRC_STAMP = Regex("^\\[\\d{1,4}:\\d{1,2}([.:]\\d{1,6})?\\]")
