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
            // stand alone or be followed by whitespace (17.11 SOL-S5): an identifier that merely
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

        val sorted = cues.sortedBy { it.startMicros }
        // The same open-end resolution SubRip applies (17.11 SOL-S4): a clamped backwards or
        // zero-length cue closes at the next cue's start, or after the shared default.
        return sorted.mapIndexed { index, cue ->
            if (cue.endMicros > cue.startMicros) {
                cue
            } else {
                val nextStart = sorted.drop(index + 1)
                    .firstOrNull { it.startMicros > cue.startMicros }
                    ?.startMicros
                cue.copy(endMicros = nextStart ?: (cue.startMicros + SubRipParser.OPEN_CUE_DEFAULT_MICROS))
            }
        }
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
        val alignment = ALIGN.find(settings)?.groupValues?.get(1)?.let { raw ->
            when (raw) {
                "start", "left" -> CueAlignment.BottomLeft
                "end", "right" -> CueAlignment.BottomRight
                "center", "middle" -> CueAlignment.BottomCenter
                else -> null
            }
        }
        return Timing(
            start = start,
            end = if (end > start) end else start,
            layout = alignment?.let { CueLayout(alignment = it) } ?: CueLayout(),
        )
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
    private val VOICE_TAG = Regex("""</?v(?:\s[^>]*)?>""")
    private val CLASS_TAG = Regex("""</?c(?:\.[^>]*)?>""")
    private val KARAOKE_TAG = Regex("""<\d{1,3}:?\d{1,2}:\d{1,2}\.\d{1,3}>""")
}
