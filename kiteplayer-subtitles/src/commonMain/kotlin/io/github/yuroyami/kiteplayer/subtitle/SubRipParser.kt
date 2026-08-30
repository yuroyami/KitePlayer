package io.github.yuroyami.kiteplayer.subtitle

/**
 * Reads SubRip (`.srt`) subtitles.
 *
 * SubRip has no specification. What exists is twenty years of files written by dozens of tools,
 * so this parser is written to accept what real files contain rather than what a grammar would
 * allow. Every tolerance below corresponds to files that exist in the wild.
 *
 * Accepted deviations from the common shape:
 *
 * - A missing or non-numeric index line. The index is ignored entirely, because it is wrong in
 *   many files and nothing depends on it.
 * - Comma or full stop as the millisecond separator. Both appear.
 * - One or two digit hours, and a missing hour field.
 * - Windows, Unix or old Mac line endings, mixed within one file.
 * - A byte order mark at the start.
 * - Blank lines inside a cue's text, when the following line is not a timing line.
 * - Cues out of chronological order. The result is sorted by start time.
 * - A final cue with no trailing blank line.
 *
 * The inline markup SubRip files carry in practice is HTML-like: bold, italic, underline, strike
 * and a font colour. Those are parsed into [StyledSpan]s. Anything else is passed through as
 * literal text rather than dropped, because dropping text loses meaning and showing a stray tag
 * only looks untidy.
 */
public object SubRipParser {

    /**
     * A cue whose end does not follow its start would otherwise never display: the selector
     * requires the time to sit strictly before the end. The parser resolves it
     * against the next cue's start, or holds it for this documented default when no cue follows.
     */
    public const val OPEN_CUE_DEFAULT_MICROS: Long = 3_000_000

    /** Parses [text] into cues, sorted by start time. Never throws on malformed input. */
    public fun parse(text: String): List<SubtitleCue.Text> {
        val lines = text.removePrefix("﻿").split(LINE_BREAK)
        val cues = mutableListOf<SubtitleCue.Text>()

        var i = 0
        while (i < lines.size) {
            // Find the next timing line. Everything before it that is not a timing line is
            // either an index, a blank, or junk, and none of it matters.
            val timing = parseTiming(lines[i])
            if (timing == null) {
                i++
                continue
            }
            i++

            val body = StringBuilder()
            while (i < lines.size) {
                val line = lines[i]
                // A blank line ends the cue only when it is not followed by more text that
                // belongs to it. Looking ahead for a timing line is what distinguishes the two.
                if (line.isBlank()) {
                    val next = lines.getOrNull(i + 1)
                    if (next == null || next.isBlank() || parseTiming(next) != null || looksLikeIndex(next)) {
                        i++
                        break
                    }
                }
                if (parseTiming(line) != null) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(line)
                i++
            }

            val spans = InlineMarkup.parse(body.toString().trim()).decodeSpanEntities()
            if (spans.isNotEmpty()) {
                cues += SubtitleCue.Text(
                    startMicros = timing.first,
                    endMicros = timing.second,
                    spans = spans,
                )
            }
        }

        val sorted = cues.sortedBy { it.startMicros }
        // The open-end resolution: a clamped backwards or zero-length cue closes
        // at the NEXT cue's start, or after the documented default when nothing follows.
        return sorted.mapIndexed { index, cue ->
            if (cue.endMicros > cue.startMicros) {
                cue
            } else {
                val nextStart = sorted.drop(index + 1)
                    .firstOrNull { it.startMicros > cue.startMicros }
                    ?.startMicros
                cue.copy(endMicros = nextStart ?: (cue.startMicros + OPEN_CUE_DEFAULT_MICROS))
            }
        }
    }

    /**
     * Parses ONE cue's body, the shape a Matroska SubRip track's packets carry: the text alone,
     * timing already on the packet (S4.c). Same markup rules as whole-file parsing.
     */
    public fun parseCueBody(body: String): List<StyledSpan> =
        InlineMarkup.parse(body.trim()).decodeSpanEntities()
}

/**
 * The four entities every subtitle file in the wild actually uses, decoded on each span's TEXT
 * after markup parsing: decoding first turns escaped markup into real tags. An author's
 * literal `&amp;lt;` would double-decode.
 */
internal fun List<StyledSpan>.decodeSpanEntities(): List<StyledSpan> = map { span ->
    val decoded = span.text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
    if (decoded == span.text) span else span.copy(text = decoded)
}

private fun looksLikeIndex(line: String): Boolean =
        line.trim().let { it.isNotEmpty() && it.all { c -> c.isDigit() } }

    /** Returns start and end in microseconds, or null when [line] is not a timing line. */
    private fun parseTiming(line: String): Pair<Long, Long>? {
        val match = TIMING.find(line) ?: return null
        val start = timestampToMicros(match.groupValues[1]) ?: return null
        val end = timestampToMicros(match.groupValues[2]) ?: return null
        // An end before the start is a broken file. Treat it as an open end rather than
        // discarding the text, and let the track state close it at the next cue.
        return start to if (end > start) end else start
    }

    private fun timestampToMicros(raw: String): Long? {
        val match = TIMESTAMP.matchEntire(raw.trim()) ?: return null
        val hours = match.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        // Millisecond fields of one, two or three digits all appear. Pad rather than assume.
        val fraction = match.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + fraction) * 1000
    }

    private val LINE_BREAK = Regex("\r\n|\n|\r")
    private val TIMING = Regex("""([\d:.,]+)\s*-->\s*([\d:.,]+)""")
    private val TIMESTAMP = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})""")

/**
 * Turns the HTML-like markup found in SubRip and WebVTT files into styled spans.
 *
 * Unknown tags are kept as literal text. That is deliberate: a viewer seeing `<foo>` learns the
 * file is odd, whereas silently deleting content hides a real problem and can remove dialogue.
 */
internal object InlineMarkup {

    fun parse(text: String): List<StyledSpan> {
        if (text.isEmpty()) return emptyList()
        if ('<' !in text && '{' !in text) return listOf(StyledSpan(text))

        val spans = mutableListOf<StyledSpan>()
        val buffer = StringBuilder()
        var style = CueStyle()
        val stack = ArrayDeque<CueStyle>()
        var i = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                spans += StyledSpan(buffer.toString(), style)
                buffer.clear()
            }
        }

        while (i < text.length) {
            val c = text[i]
            if (c != '<') {
                buffer.append(c)
                i++
                continue
            }
            val close = text.indexOf('>', i + 1)
            if (close < 0) {
                // An unterminated tag is literal text.
                buffer.append(text.substring(i))
                break
            }
            val tag = text.substring(i + 1, close).trim()
            val applied = applyTag(tag, style, stack)
            if (applied == null) {
                // Not a tag we understand. Keep it visible.
                buffer.append(text, i, close + 1)
            } else {
                flush()
                style = applied
            }
            i = close + 1
        }
        flush()
        return spans
    }

    /** Returns the new style, or null when [tag] is not recognised. */
    private fun applyTag(tag: String, current: CueStyle, stack: ArrayDeque<CueStyle>): CueStyle? {
        if (tag.startsWith("/")) {
            val name = tag.drop(1).trim().lowercase()
            if (name !in KNOWN) return null
            return stack.removeLastOrNull() ?: CueStyle()
        }
        val name = tag.substringBefore(' ').lowercase()
        if (name !in KNOWN) return null
        stack.addLast(current)
        return when (name) {
            "b" -> current.copy(bold = true)
            "i" -> current.copy(italic = true)
            "u" -> current.copy(underline = true)
            "s" -> current.copy(strikeThrough = true)
            "font" -> parseColor(tag)?.let { current.copy(primaryColor = it) } ?: current
            else -> current
        }
    }

    private fun parseColor(tag: String): Int? {
        val value = COLOR.find(tag)?.groupValues?.get(1)?.trim() ?: return null
        val hex = value.removePrefix("#")
        if (hex.length != 6) return NAMED[value.lowercase()]
        val rgb = hex.toLongOrNull(16) ?: return null
        return (0xFF000000L or rgb).toInt()
    }

    private val KNOWN = setOf("b", "i", "u", "s", "font")
    private val COLOR = Regex("""color\s*=\s*["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE)
    private val NAMED = mapOf(
        "white" to 0xFFFFFFFF.toInt(),
        "black" to 0xFF000000.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
    )
}
