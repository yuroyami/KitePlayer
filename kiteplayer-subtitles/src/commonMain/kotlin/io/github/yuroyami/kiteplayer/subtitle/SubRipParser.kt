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
 * and a font colour. Those are parsed into [StyledSpan]s. An unknown HTML-like tag is passed
 * through as literal text rather than dropped, because dropping text loses meaning and showing a
 * stray tag only looks untidy.
 *
 * Many files also carry ASS override tags in braces, above all `{\an8}` to lift a line to the top
 * when burned-in text covers the bottom. Those are tags, as FFmpeg reads them: the first `\an1` to
 * `\an9` places the cue, `\b`, `\i`, `\u` and `\s` set the style, and every other `{\...}` run
 * is dropped.
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

            parseCue(body.toString(), timing.first, timing.second)?.let { cues += it }
        }

        // The open-end resolution: a clamped backwards or zero-length cue closes
        // at the NEXT cue's start, or after the documented default when nothing follows.
        return cues.sortedBy { it.startMicros }.closingOpenEnds(OPEN_CUE_DEFAULT_MICROS)
    }

    /**
     * Parses ONE cue's body, the shape a Matroska SubRip track's packets carry: the text alone,
     * timing already on the packet. Same markup rules as whole-file parsing.
     */
    public fun parseCueBody(body: String): List<StyledSpan> = markup(body).spans

    /**
     * One cue from its [body] and its timing, placed where a `{\anN}` tag in the body asks. This is
     * what a Matroska SubRip packet needs, because [parseCueBody] answers the text alone. Null when
     * the body holds no text.
     */
    public fun parseCue(body: String, startMicros: Long, endMicros: Long): SubtitleCue.Text? {
        val parsed = markup(body)
        if (parsed.spans.isEmpty()) return null
        return SubtitleCue.Text(
            startMicros = startMicros,
            endMicros = endMicros,
            spans = parsed.spans,
            layout = parsed.alignment?.let { CueLayout(alignment = it) } ?: CueLayout(),
        )
    }

    private fun markup(body: String): InlineMarkup.Parsed {
        val parsed = InlineMarkup.parse(body.trim(), braceTags = true)
        return InlineMarkup.Parsed(parsed.spans.decodeSpanEntities(), parsed.alignment)
    }
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
 * Closes every cue whose end is not after its start at the next DISTINCT start in this list,
 * which is sorted by start, or at [defaultMicros] past its own start when nothing follows.
 *
 * One backward pass carrying the next distinct start. It used to be `drop(index + 1)` and a
 * search per open cue, which copies the tail of the list every time: a file made of zero-length
 * cues, which both parsers accept on purpose, allocated the square of its own length.
 */
internal fun List<SubtitleCue.Text>.closingOpenEnds(defaultMicros: Long): List<SubtitleCue.Text> {
    val closed = toMutableList()
    var next: Long? = null
    var followingStart: Long? = null
    for (index in lastIndex downTo 0) {
        val cue = this[index]
        if (followingStart != null && followingStart != cue.startMicros) next = followingStart
        if (cue.endMicros <= cue.startMicros) {
            closed[index] = cue.copy(endMicros = next ?: (cue.startMicros + defaultMicros))
        }
        followingStart = cue.startMicros
    }
    return closed
}

/**
 * Turns the HTML-like markup found in SubRip and WebVTT files into styled spans.
 *
 * Unknown `<...>` tags are kept as literal text. That is deliberate: a viewer seeing `<foo>` learns
 * the file is odd, whereas silently deleting content hides a real problem and can remove dialogue.
 */
internal object InlineMarkup {

    /** Spans, and the placement that a `{\anN}` tag asked for when one did. */
    class Parsed(val spans: List<StyledSpan>, val alignment: CueAlignment?)

    /** HTML-like tags only, as WebVTT carries them. */
    fun parse(text: String): List<StyledSpan> = parse(text, braceTags = false).spans

    /**
     * [braceTags] also reads `{\...}` runs as ASS override tags, the way SubRip files carry them.
     * The first `\an1` to `\an9` places the cue, `\b`, `\i`, `\u` and `\s` set the style, and
     * every other tag is dropped. An unterminated run stays text, as it does in FFmpeg.
     */
    fun parse(text: String, braceTags: Boolean): Parsed {
        if (text.isEmpty()) return Parsed(emptyList(), null)
        if ('<' !in text && (!braceTags || '{' !in text)) return Parsed(listOf(StyledSpan(text)), null)

        val spans = mutableListOf<StyledSpan>()
        val buffer = StringBuilder()
        var style = CueStyle()
        var alignment: CueAlignment? = null
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
            if (braceTags && c == '{' && text.getOrNull(i + 1) == '\\') {
                val close = text.indexOf('}', i + 2)
                if (close > 0) {
                    var next = style
                    for (tag in text.substring(i + 2, close).split('\\')) {
                        val placed = ALIGNMENT_TAG.matchEntire(tag.trim())
                        if (placed != null) {
                            if (alignment == null) alignment = NUMPAD[placed.groupValues[1].toInt() - 1]
                        } else {
                            next = override(tag.trim(), next)
                        }
                    }
                    if (next != style) {
                        flush()
                        style = next
                    }
                    i = close + 1
                    continue
                }
            }
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
        return Parsed(spans, alignment)
    }

    /** [current] after one ASS override tag. A tag that is not one of the four styles changes nothing. */
    private fun override(tag: String, current: CueStyle): CueStyle {
        val match = STYLE_TAG.matchEntire(tag) ?: return current
        val value = match.groupValues[2].toIntOrNull() ?: 0
        return when (match.groupValues[1]) {
            // `\b` also takes a font weight, where 700 is bold.
            "b" -> current.copy(bold = value == 1 || value >= 700)
            "i" -> current.copy(italic = value != 0)
            "u" -> current.copy(underline = value != 0)
            else -> current.copy(strikeThrough = value != 0)
        }
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
    private val ALIGNMENT_TAG = Regex("""an([1-9])""")
    private val STYLE_TAG = Regex("""([bius])(\d*)""")

    /** `\an1` to `\an9`, laid out like a numeric keypad. */
    private val NUMPAD = arrayOf(
        CueAlignment.BottomLeft, CueAlignment.BottomCenter, CueAlignment.BottomRight,
        CueAlignment.MiddleLeft, CueAlignment.MiddleCenter, CueAlignment.MiddleRight,
        CueAlignment.TopLeft, CueAlignment.TopCenter, CueAlignment.TopRight,
    )
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
