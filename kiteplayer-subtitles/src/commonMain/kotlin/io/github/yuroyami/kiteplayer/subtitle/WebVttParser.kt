package io.github.yuroyami.kiteplayer.subtitle

/**
 * Reads WebVTT (`.vtt`) subtitles, the caption format the web standardised out of SubRip.
 *
 * The same philosophy as [SubRipParser]: accept what real files contain. The differences that
 * matter here, and only these, are handled:
 *
 * - The `WEBVTT` signature line, optionally after a byte order mark, optionally with a trailing
 *   description. A file without it is still read, because files without it exist.
 * - `NOTE`, `STYLE` and `REGION` blocks are skipped whole. Styling by stylesheet is left
 *   out, never silently half-applied.
 * - The millisecond separator is a full stop and hours are optional, which the shared timestamp
 *   grammar already accepts.
 * - Cue identifiers (the line before a timing line) are ignored, like SubRip's indices.
 * - Cue settings after the timing (`position:`, `line:`, `align:`) are read for the one thing
 *   the text path draws today, the horizontal alignment; the rest is recorded nowhere rather
 *   than misdrawn.
 * - Inline `<b>`, `<i>`, `<u>`, `<c>` classes and `<v Speaker>` voice tags: bold, italic and
 *   underline map to styles, the class and voice wrappers contribute their text and drop their
 *   decoration, and timestamps tags (`<00:00:01.000>`, karaoke) are stripped, because painting
 *   karaoke honestly is libass's job.
 */
public object WebVttParser {

    /**
     * Parses [text] into cues, sorted by start time. Never throws on malformed input. A file with
     * more than 100,000 cues keeps the first 100,000 in file order.
     */
    public fun parse(text: String): List<SubtitleCue.Text> = parse(text, MAX_FILE_CUES)

    internal fun parse(text: String, maxCues: Int): List<SubtitleCue.Text> {
        val lines = text.removePrefix("﻿").split(LINE_BREAK)
        val cues = mutableListOf<SubtitleCue.Text>()

        var i = 0
        while (i < lines.size && cues.size < maxCues) {
            val line = lines[i]
            // Block skips first: NOTE/STYLE/REGION run to the next blank line. The keyword must
            // stand alone or be followed by whitespace: an identifier that merely
            // BEGINS with one of these words is a cue's own name, not a block.
            if (isBlockKeyword(line)) {
                i++
                while (i < lines.size && lines[i].isNotBlank()) i++
                continue
            }
            val timing = parseTiming(line)
            if (timing == null) {
                i++
                continue
            }
            i++

            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && parseTiming(lines[i]) == null) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }

            val spans = InlineMarkup.parse(stripVttOnlyTags(body.toString().trim())).decodeSpanEntities()
            if (spans.isNotEmpty()) {
                cues += SubtitleCue.Text(
                    startMicros = timing.start,
                    endMicros = timing.end,
                    spans = spans,
                    layout = timing.layout,
                )
            }
        }

        // The same open-end resolution SubRip applies: a clamped backwards or
        // zero-length cue closes at the next cue's start, or after the shared default.
        return cues.sortedBy { it.startMicros }.closingOpenEnds(SubRipParser.OPEN_CUE_DEFAULT_MICROS)
    }

    /** One cue's body from a container track, timing already on the packet. */
    public fun parseCueBody(body: String): List<StyledSpan> =
        InlineMarkup.parse(stripVttOnlyTags(body.trim())).decodeSpanEntities()

    private class Timing(val start: Long, val end: Long, val layout: CueLayout)

    private fun parseTiming(line: String): Timing? {
        val match = findTiming(line, allowComma = false) ?: return null
        val start = timestampToMicros(match.start) ?: return null
        val end = timestampToMicros(match.end) ?: return null
        val settings = line.substring(match.endIndex)
        val align = ALIGN.find(settings)?.groupValues?.get(1)
        // The column: left, centre or right.
        val column = when (align) {
            "start", "left" -> 0
            "end", "right" -> 2
            "center", "middle" -> 1
            else -> null
        }
        // Where the cue sits across the picture and how far down it is. The specification writes
        // both as percentages of the video, which is the same 0 to 1 fraction CueLayout carries.
        val positionX = percentSetting(settings, POSITION)
        val line = LINE.find(settings)
        val positionY = line?.groupValues?.get(1)?.toFloatOrNull()?.let { it / 100f }?.takeIf { it in 0f..1f }
        // The row. A percentage line anchors the edge that its line alignment names, and that is the
        // top unless the cue says otherwise, so `line:0%` is the top of the picture. Without a line
        // the cue stays in the bottom row. The line-number form counts text rows, which means
        // nothing until the text is measured, so it is not read.
        val lineAlign = line?.groupValues?.get(2)
        val row = when {
            positionY == null -> 2
            lineAlign == "center" -> 1
            lineAlign == "end" -> 2
            else -> 0
        }
        val alignment = if (column == null && positionY == null) null else ALIGNMENTS[row][column ?: 1]
        val width = percentSetting(settings, SIZE)
        var layout = CueLayout()
        if (alignment != null) layout = layout.copy(alignment = alignment)
        if (positionX != null) layout = layout.copy(positionX = positionX)
        if (positionY != null) layout = layout.copy(positionY = positionY)
        if (width != null) {
            // The box's own width, which CueLayout already expresses as the space left clear on
            // each side. Where that box starts depends on which of its edges `position` anchors.
            val anchor = positionX ?: DEFAULT_POSITION
            val left = when (align) {
                "start", "left" -> anchor
                "end", "right" -> anchor - width
                else -> anchor - width / 2f
            }.coerceIn(0f, 1f - width)
            layout = layout.copy(marginLeft = left, marginRight = 1f - left - width)
        }
        return Timing(
            start = start,
            end = if (end > start) end else start,
            layout = layout,
        )
    }

    /** A cue setting written as a percentage, as a 0 to 1 fraction. */
    private fun percentSetting(settings: String, pattern: Regex): Float? =
        pattern.find(settings)?.groupValues?.get(1)?.toFloatOrNull()?.let { it / 100f }?.takeIf {
            it in 0f..1f
        }

    private fun timestampToMicros(raw: String): Long? {
        if (raw.length > MAX_TIMESTAMP_LENGTH) return null
        val match = TIMESTAMP.matchEntire(raw) ?: return null
        val hours = match.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val fraction = match.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + fraction) * 1000
    }

    /** NOTE, STYLE or REGION starts a block only when the word stands alone or before space. */
    private fun isBlockKeyword(line: String): Boolean =
        listOf("NOTE", "STYLE", "REGION").any { keyword ->
            line == keyword || line.startsWith("$keyword ") || line.startsWith("$keyword\t")
        }

    /**
     * Voice, class, language and karaoke-timestamp tags are VTT-only shapes InlineMarkup does not
     * know, and so is ruby, which no text renderer here can draw above its line (#511). Each kind
     * goes in its own pass, in that order, and ruby keeps its reading after the base text, in
     * parentheses.
     */
    private fun stripVttOnlyTags(body: String): String =
        flattenRuby(stripTags(stripTags(stripTags(stripTags(body, ::isVoiceTag), ::isClassTag), ::isLangTag), ::isKaraokeTag))

    /** `<v>`, `</v>` or `<v Speaker>`, given the inside of the tag. */
    private fun isVoiceTag(text: CharSequence, from: Int, to: Int): Boolean = isNamedTag(text, from, to, "v") {
        it.isWhitespace()
    }

    /** `<c>`, `</c>` or `<c.class>`, given the inside of the tag. */
    private fun isClassTag(text: CharSequence, from: Int, to: Int): Boolean = isNamedTag(text, from, to, "c") {
        it == '.'
    }

    /** `<lang>`, `</lang>`, `<lang en>` or `<lang.class en>`, given the inside of the tag. */
    private fun isLangTag(text: CharSequence, from: Int, to: Int): Boolean = isNamedTag(text, from, to, "lang") {
        it == '.' || it.isWhitespace()
    }

    /** An optional `/`, then [name], then nothing or a character that [opensRest] accepts. */
    private inline fun isNamedTag(text: CharSequence, from: Int, to: Int, name: String, opensRest: (Char) -> Boolean): Boolean {
        var at = from
        if (at < to && text[at] == '/') at++
        if (to - at < name.length || !text.regionMatches(at, name, 0, name.length)) return false
        at += name.length
        return at == to || opensRest(text[at])
    }

    /** A karaoke timestamp such as `00:00:01.000`, given the inside of the tag. */
    private fun isKaraokeTag(text: CharSequence, from: Int, to: Int): Boolean =
        to - from <= MAX_TIMESTAMP_LENGTH && KARAOKE_TIME.matches(text.substring(from, to))

    private val LINE_BREAK = Regex("\r\n|\n|\r")
    private val TIMESTAMP = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})\.(\d{1,3})""")
    private val ALIGN = Regex("""align:(\S+)""")
    private val POSITION = Regex("""position:(-?[\d.]+)%""")
    private val LINE = Regex("""line:(-?[\d.]+)%(?:,(start|center|end))?""")
    private val SIZE = Regex("""size:(-?[\d.]+)%""")

    /** Where a cue sits across the picture when it says nothing: the middle, as the specification says. */
    private const val DEFAULT_POSITION = 0.5f

    /** The alignment for a row (top, middle, bottom) and a column (left, centre, right). */
    private val ALIGNMENTS = arrayOf(
        arrayOf(CueAlignment.TopLeft, CueAlignment.TopCenter, CueAlignment.TopRight),
        arrayOf(CueAlignment.MiddleLeft, CueAlignment.MiddleCenter, CueAlignment.MiddleRight),
        arrayOf(CueAlignment.BottomLeft, CueAlignment.BottomCenter, CueAlignment.BottomRight),
    )
    private val KARAOKE_TIME = Regex("""\d{1,3}:?\d{1,2}:\d{1,2}\.\d{1,3}""")
}

/**
 * [text] with its WebVTT ruby written out in line: `<ruby>漢字<rt>かんじ</rt></ruby>` becomes
 * `漢字(かんじ)`, the reading after the base text in parentheses. The `</rt>` end tag may be left
 * out before `</ruby>`, and an `<rt>` outside a ruby keeps its text without its tag, as the
 * specification's parser does. A class on either tag goes with it. The pass reads each character
 * a bounded number of times, as [stripTags] does.
 */
internal fun flattenRuby(text: CharSequence): String {
    if ('<' !in text) return text.toString()
    val out = StringBuilder(text.length)
    var close = text.indexOf('>')
    var inRuby = false
    var inReading = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '<' && close >= 0) {
            if (close <= i) close = text.indexOf('>', i + 1)
            if (close > i) {
                val tag = rubyTag(text, i + 1, close)
                if (tag != null) {
                    when (tag) {
                        RubyTag.RubyStart -> inRuby = true
                        RubyTag.ReadingStart -> if (inRuby && !inReading) {
                            out.append('(')
                            inReading = true
                        }
                        RubyTag.ReadingEnd -> if (inReading) {
                            out.append(')')
                            inReading = false
                        }
                        RubyTag.RubyEnd -> {
                            if (inReading) out.append(')')
                            inReading = false
                            inRuby = false
                        }
                    }
                    i = close + 1
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    if (inReading) out.append(')')
    return out.toString()
}

private enum class RubyTag { RubyStart, RubyEnd, ReadingStart, ReadingEnd }

/** Which ruby tag the inside of a tag from [from] to [to] is, or null for any other tag. */
private fun rubyTag(text: CharSequence, from: Int, to: Int): RubyTag? {
    var at = from
    val end = at < to && text[at] == '/'
    if (end) at++
    fun named(name: String): Boolean =
        to - at >= name.length && text.regionMatches(at, name, 0, name.length) &&
            (at + name.length == to || text[at + name.length] == '.')
    return when {
        named("ruby") -> if (end) RubyTag.RubyEnd else RubyTag.RubyStart
        named("rt") -> if (end) RubyTag.ReadingEnd else RubyTag.ReadingStart
        else -> null
    }
}

/**
 * [text] without every `<...>` tag whose inside [isTag] accepts, given the text and the bounds of
 * that inside. A tag ends at the first `>` after its `<`. The search for that `>` only ever moves
 * forward, and it stops for good once it finds none, so the pass reads each character a bounded
 * number of times.
 */
internal fun stripTags(text: CharSequence, isTag: (CharSequence, Int, Int) -> Boolean): String {
    if ('<' !in text) return text.toString()
    val out = StringBuilder(text.length)
    var close = text.indexOf('>')
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '<' && close >= 0) {
            if (close <= i) close = text.indexOf('>', i + 1)
            if (close > i && isTag(text, i + 1, close)) {
                i = close + 1
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}
