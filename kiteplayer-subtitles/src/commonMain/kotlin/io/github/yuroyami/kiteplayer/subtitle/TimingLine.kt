package io.github.yuroyami.kiteplayer.subtitle

/**
 * The two timestamps of a SubRip or WebVTT timing line, as written, and [endIndex], where the
 * second one ends. WebVTT cue settings start there.
 */
internal class TimingMatch(val start: String, val end: String, val endIndex: Int)

/**
 * The first `-->` in [line] that has a run of timestamp characters right before it and right after
 * it, with whitespace allowed between each run and the arrow, or null when there is none. The
 * timestamp characters are digits, `:` and `.`, and `,` as well when [allowComma] is set, because
 * SubRip writes its milliseconds after either one.
 *
 * Each search for an arrow starts past the previous arrow, and each look to either side of an
 * arrow stops at the first character that is not whitespace or a timestamp character. The next
 * arrow never holds such a character, so every character of the line is read a bounded number of
 * times, however long the line is.
 */
internal fun findTiming(line: CharSequence, allowComma: Boolean): TimingMatch? {
    var from = 0
    while (true) {
        val arrow = line.indexOf("-->", from)
        if (arrow < 0) return null
        var startEnd = arrow
        while (startEnd > 0 && line[startEnd - 1].isWhitespace()) startEnd--
        var startBegin = startEnd
        while (startBegin > 0 && line[startBegin - 1].isTimestampChar(allowComma)) startBegin--
        var endBegin = arrow + 3
        while (endBegin < line.length && line[endBegin].isWhitespace()) endBegin++
        var endEnd = endBegin
        while (endEnd < line.length && line[endEnd].isTimestampChar(allowComma)) endEnd++
        if (startBegin < startEnd && endBegin < endEnd) {
            return TimingMatch(line.substring(startBegin, startEnd), line.substring(endBegin, endEnd), endEnd)
        }
        from = arrow + 1
    }
}

private fun Char.isTimestampChar(allowComma: Boolean): Boolean =
    this in '0'..'9' || this == ':' || this == '.' || (allowComma && this == ',')

/** The longest timestamp both grammars allow, `999:59:59.999`. A longer run is not a timestamp. */
internal const val MAX_TIMESTAMP_LENGTH: Int = 13
