package io.github.yuroyami.kiteplayer.subtitle

/**
 * Reads WebVTT (`.vtt`) subtitles, the caption format the web standardised out of SubRip.
 *
 * The same philosophy as [SubRipParser]: accept what real files contain. The differences that
 * matter here, and only these, are handled:
 *
 * - The `WEBVTT` signature line, optionally after a byte order mark, optionally with a trailing
 *   description. A file without it is still read, because files without it exist.
 * - `NOTE`, `STYLE` and `REGION` blocks are skipped whole. Styling by stylesheet is an S4.f
 *   concern, never silently half-applied.
 * - The millisecond separator is a full stop and hours are optional, which the shared timestamp
 *   grammar already accepts.
 * - Cue identifiers (the line before a timing line) are ignored, like SubRip's indices.
 * - Cue settings after the timing (`position:`, `line:`, `align:`) are read for the one thing
 *   the text path draws today, the horizontal alignment; the rest is recorded nowhere rather
 *   than misdrawn.
 * - Inline `<b>`, `<i>`, `<u>`, `<c>` classes and `<v Speaker>` voice tags: bold, italic and
 *   underline map to styles, the class and voice wrappers contribute their text and drop their
 *   decoration, and timestamps tags (`<00:00:01.000>`, karaoke) are stripped, because painting
 *   karaoke honestly is libass's job (S4.f).
 */
public object WebVttParser {

    /** Parses [text] into cues, sorted by start time. Never throws on malformed input. */
    public fun parse(text: String): List<SubtitleCue.Text> {
        val lines = text.removePrefix("﻿").split(LINE_BREAK)
        val cues = mutableListOf<SubtitleCue.Text>()

        var i = 0
        while (i < lines.size) {
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

    /** One cue's body from a container track, timing already on the packet (S4.c). */
    public fun parseCueBody(body: String): List<StyledSpan> =
        InlineMarkup.parse(stripVttOnlyTags(body.trim())).decodeSpanEntities()

    private class Timing(val start: Long, val end: Long, val layout: CueLayout)

    private fun parseTiming(line: String): Timing? {
        val match = TIMING.find(line) ?: return null
        val start = timestampToMicros(match.groupValues[1]) ?: return null
        val end = timestampToMicros(match.groupValues[2]) ?: return null
        val settings = line.substringAfter(match.groupValues[0], "")
        val align = ALIGN.find(settings)?.groupValues?.get(1)
        val alignment = when (align) {
            "start", "left" -> CueAlignment.BottomLeft
            "end", "right" -> CueAlignment.BottomRight
            "center", "middle" -> CueAlignment.BottomCenter
            else -> null
        }
        // Where the cue sits across the picture and how far down it is. The specification writes
        // both as percentages of the video, which is the same 0 to 1 fraction CueLayout carries.
        val positionX = percentSetting(settings, POSITION)
        val positionY = percentSetting(settings, LINE)
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

    /**
     * A cue setting written as a percentage, as a 0 to 1 fraction.
     *
     * Only the percentage forms. `line` also has a line-number form, which is a count of text rows
     * from the top or the bottom and means nothing until the text has been measured, so it is not
     * a fraction of the picture and is deliberately not turned into one here.
     */
    private fun percentSetting(settings: String, pattern: Regex): Float? =
        pattern.find(settings)?.groupValues?.get(1)?.toFloatOrNull()?.let { it / 100f }?.takeIf {
            it in 0f..1f
        }

    private fun timestampToMicros(raw: String): Long? {
        val match = TIMESTAMP.matchEntire(raw.trim()) ?: return null
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

    /** Voice, class and karaoke-timestamp tags are VTT-only shapes InlineMarkup does not know. */
    private fun stripVttOnlyTags(body: String): String = body
        .replace(VOICE_TAG, "")
        .replace(CLASS_TAG, "")
        .replace(KARAOKE_TAG, "")

    private val LINE_BREAK = Regex("\r\n|\n|\r")
    private val TIMING = Regex("""([\d:.]+)\s*-->\s*([\d:.]+)""")
    private val TIMESTAMP = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})\.(\d{1,3})""")
    private val ALIGN = Regex("""align:(\S+)""")
    private val POSITION = Regex("""position:(-?[\d.]+)%""")
    private val LINE = Regex("""line:(-?[\d.]+)%""")
    private val SIZE = Regex("""size:(-?[\d.]+)%""")

    /** Where a cue sits across the picture when it says nothing: the middle, as the specification says. */
    private const val DEFAULT_POSITION = 0.5f
    private val VOICE_TAG = Regex("""</?v(?:\s[^>]*)?>""")
    private val CLASS_TAG = Regex("""</?c(?:\.[^>]*)?>""")
    private val KARAOKE_TAG = Regex("""<\d{1,3}:?\d{1,2}:\d{1,2}\.\d{1,3}>""")
}
