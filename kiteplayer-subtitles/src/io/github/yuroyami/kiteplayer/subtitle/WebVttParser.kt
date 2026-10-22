package io.github.yuroyami.kiteplayer.subtitle

/**
 * Reads WebVTT (`.vtt`) subtitles, the caption format the web standardised out of SubRip.
 *
 * The same philosophy as [SubRipParser]: accept what real files contain. The differences that
 * matter here, and only these, are handled:
 *
 * - The `WEBVTT` signature line, optionally after a byte order mark, optionally with a trailing
 *   description. A file without it is still read, because files without it exist.
 * - `NOTE` and `REGION` blocks are skipped whole. `STYLE` blocks are read for the `::cue` rules a
 *   cue here can carry, and a rule with anything more is ignored whole, never half applied
 *   (#498).
 * - The millisecond separator is a full stop and hours are optional, which the shared timestamp
 *   grammar already accepts.
 * - A cue's identifier (the line before its timing line) is kept only for `::cue(#id)` rules.
 * - Cue settings after the timing (`position:`, `line:`, `align:`) are read for the one thing
 *   the text path draws today, the horizontal alignment; the rest is recorded nowhere rather
 *   than misdrawn.
 * - Inline `<b>`, `<i>`, `<u>`, `<c>` classes, `<v Speaker>` voices, `<lang>` and `<ruby>`: bold,
 *   italic and underline map to styles, the standard's colour classes such as `<c.yellow>` and
 *   `<c.bg_blue>` colour the text and its background, and the file's rules style classes and
 *   voices. Ruby keeps its reading after the base text in parentheses. Timestamp tags
 *   (`<00:00:01.000>`, karaoke) are stripped, because painting karaoke honestly is libass's job.
 */
public object WebVttParser {

    /**
     * Parses [text] into cues, sorted by start time. Never throws on malformed input. A file with
     * more than 100,000 cues keeps the first 100,000 in file order.
     */
    public fun parse(text: String): List<SubtitleCue.Text> = parse(text, MAX_FILE_CUES)

    internal fun parse(text: String, maxCues: Int): List<SubtitleCue.Text> {
        val lines = text.removePrefix("\uFEFF").split(LINE_BREAK)
        val cues = mutableListOf<SubtitleCue.Text>()
        var sheet = VttStyleSheet.EMPTY
        // The line before a timing line names its cue.
        var identifier: String? = null

        var i = 0
        while (i < lines.size && cues.size < maxCues) {
            val line = lines[i]
            // Blocks first: NOTE/STYLE/REGION run to the next blank line. The keyword must
            // stand alone or be followed by whitespace: an identifier that merely
            // BEGINS with one of these words is a cue's own name, not a block.
            if (isBlockKeyword(line)) {
                val style = line.startsWith("STYLE")
                i++
                val css = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (style) css.append(lines[i]).append('\n')
                    i++
                }
                if (style) sheet = sheet.plus(css)
                identifier = null
                continue
            }
            val timing = parseTiming(line)
            if (timing == null) {
                identifier = line.trim().ifEmpty { null }
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

            val spans = vttSpans(body.toString().trim(), sheet, identifier)
            identifier = null
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

    /** One cue's body from a container track, timing already on the packet, with no stylesheet. */
    public fun parseCueBody(body: String): List<StyledSpan> = vttSpans(body.trim(), VttStyleSheet.EMPTY, null)

    /**
     * A parser for the cues of one container track whose [header] is the start of a WebVTT file,
     * up to its first cue, as Matroska keeps it in the track's private data. Its `STYLE` blocks
     * style every cue of the track. A header with none, or none that reads, styles nothing.
     */
    public fun trackParser(header: String): WebVttTrackParser {
        var sheet = VttStyleSheet.EMPTY
        val lines = header.split(LINE_BREAK)
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("STYLE") && isBlockKeyword(lines[i])) {
                i++
                val css = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) css.append(lines[i++]).append('\n')
                sheet = sheet.plus(css)
            } else {
                i++
            }
        }
        return WebVttTrackParser(sheet)
    }

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
}

/** The cues of one WebVTT container track, styled by the `STYLE` blocks of its header (#498). */
public class WebVttTrackParser internal constructor(private val sheet: VttStyleSheet) {

    /** One cue's body, timing already on the packet. */
    public fun parseCueBody(body: String): List<StyledSpan> = vttSpans(body.trim(), sheet, null)
}
