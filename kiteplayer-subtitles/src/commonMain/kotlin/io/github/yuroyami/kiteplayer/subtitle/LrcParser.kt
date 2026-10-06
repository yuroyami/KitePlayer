package io.github.yuroyami.kiteplayer.subtitle

/**
 * Reads LRC synced lyrics (`.lrc`), the form music players and lyrics services share (#443).
 *
 * Each line carries one or more time stamps, `[mm:ss.xx]`, before its text, and shows from its time
 * until the next line's. What real files hold, and what this reads:
 *
 * - Hundredths, thousandths, tenths or no fraction at all, and a colon for the full stop.
 * - Several time stamps on one line, which repeat the line at each of them, as a chorus does.
 * - The `[offset:]` tag, in milliseconds, where a positive value shows every line sooner, as the
 *   format's own description and FFmpeg read it.
 * - The other tags, `[ar:]`, `[ti:]`, `[al:]`, `[by:]` and the rest, which are not lyrics and draw
 *   nothing.
 * - The enhanced form's word stamps, `<mm:ss.xx>`, which are dropped, so each line shows whole.
 * - A line with a stamp and no text, which ends the line before it and draws nothing.
 * - Lines out of order, and Windows, Unix or old Mac line ends, mixed.
 *
 * The last line runs to `[length:]` when the file gives one past its start, and otherwise for
 * [LAST_LINE_MICROS].
 */
public object LrcParser {

    /** How long the last line shows when the file gives no length to run to. */
    public const val LAST_LINE_MICROS: Long = 5_000_000

    /**
     * True when [text] reads as LRC: its first line that is not blank opens with a bracketed tag,
     * and some line opens with a time stamp. SubRip, WebVTT and ASS never do both.
     */
    public fun isLrc(text: String): Boolean {
        val lines = text.removePrefix("\uFEFF").split(LINE_BREAK)
        val first = lines.firstOrNull { it.isNotBlank() }?.trim() ?: return false
        if (!first.startsWith('[')) return false
        return lines.any { line -> leadingStamp(line.trim()) != null }
    }

    /**
     * Parses [text] into cues, sorted by start time. Never throws on malformed input. A file with
     * more than 100,000 lines keeps the first 100,000 in file order.
     */
    public fun parse(text: String): List<SubtitleCue.Text> {
        var offsetMillis = 0L
        var lengthMicros: Long? = null
        // Each stamp of each line, with the line's text, or null for a stamp that only ends a line.
        val stamped = mutableListOf<Pair<Long, String?>>()
        for (raw in text.removePrefix("\uFEFF").split(LINE_BREAK)) {
            if (stamped.size >= MAX_FILE_CUES) break
            val line = raw.trim()
            val stamps = mutableListOf<Long>()
            var at = 0
            var tagOnly = false
            while (at < line.length && line[at] == '[') {
                val close = line.indexOf(']', at)
                if (close < 0) break
                val inner = line.substring(at + 1, close)
                val stamp = stampMicros(inner)
                if (stamp != null) {
                    stamps += stamp
                    at = close + 1
                    continue
                }
                if (stamps.isEmpty()) {
                    // An information tag. Two of them change the timing; the rest name the song.
                    val key = inner.substringBefore(':').trim().lowercase()
                    val value = inner.substringAfter(':', "").trim()
                    when (key) {
                        "offset" -> value.removePrefix("+").toLongOrNull()?.let { offsetMillis = it }
                        "length" -> lengthMicros = stampMicros(value)
                    }
                    tagOnly = true
                }
                break
            }
            if (tagOnly || stamps.isEmpty()) continue
            val words = withoutWordStamps(line.substring(at)).trim().takeIf { it.isNotEmpty() }
            for (stamp in stamps) {
                if (stamped.size >= MAX_FILE_CUES) break
                stamped += (stamp - offsetMillis * 1_000L).coerceAtLeast(0L) to words
            }
        }
        // Stable, so lines that share a time keep the file's order, the first at the bottom.
        val ordered = stamped.sortedBy { it.first }
        val cues = ArrayList<SubtitleCue.Text>(ordered.size)
        var next = 0
        for ((index, entry) in ordered.withIndex()) {
            val (start, words) = entry
            if (words == null) continue
            if (next <= index) next = index + 1
            while (next < ordered.size && ordered[next].first <= start) next++
            val end = when {
                next < ordered.size -> ordered[next].first
                lengthMicros != null && lengthMicros > start -> lengthMicros
                else -> start + LAST_LINE_MICROS
            }
            cues += SubtitleCue.Text(start, end, listOf(StyledSpan(words)))
        }
        return cues
    }

    /** The time of the stamp [line] opens with, or null when it opens with none. */
    private fun leadingStamp(line: String): Long? {
        if (!line.startsWith('[')) return null
        val close = line.indexOf(']')
        if (close < 0) return null
        return stampMicros(line.substring(1, close))
    }

    /**
     * `mm:ss`, `mm:ss.f` to `mm:ss.ffffff`, or `mm:ss:ff`, in microseconds, or null for anything
     * else, which is how a tag such as `ar:` reads as no stamp.
     */
    private fun stampMicros(inner: String): Long? {
        val parts = inner.trim().split(':')
        if (parts.size !in 2..3) return null
        val minutes = digits(parts[0], maxLength = 4) ?: return null
        val secondsPart: String
        val fraction: String
        if (parts.size == 3) {
            secondsPart = parts[1]
            fraction = parts[2]
        } else {
            secondsPart = parts[1].substringBefore('.')
            fraction = parts[1].substringAfter('.', "")
        }
        val seconds = digits(secondsPart, maxLength = 2) ?: return null
        val fractionMicros = when {
            fraction.isEmpty() -> 0L
            else -> {
                val kept = fraction.take(6)
                var value = digits(kept, maxLength = 6) ?: return null
                repeat(6 - kept.length) { value *= 10 }
                if (fraction.length > 6 && digits(fraction, maxLength = fraction.length) == null) return null
                value
            }
        }
        return (minutes * 60L + seconds) * 1_000_000L + fractionMicros
    }

    /** [text] as a number when it is one to [maxLength] ASCII digits, else null. */
    private fun digits(text: String, maxLength: Int): Long? {
        if (text.isEmpty() || text.length > maxLength) return null
        var value = 0L
        for (char in text) {
            if (char !in '0'..'9') return null
            value = value * 10 + (char - '0')
        }
        return value
    }

    /** [text] without the enhanced form's `<mm:ss.xx>` word stamps, and the spaces they leave doubled. */
    private fun withoutWordStamps(text: String): String {
        if ('<' !in text) return text
        val out = StringBuilder(text.length)
        var at = 0
        while (at < text.length) {
            val char = text[at]
            if (char == '<') {
                val close = text.indexOf('>', at)
                if (close > at && stampMicros(text.substring(at + 1, close)) != null) {
                    at = close + 1
                    continue
                }
            }
            if (char == ' ' && out.endsWith(' ')) {
                at++
                continue
            }
            out.append(char)
            at++
        }
        return out.toString()
    }

    private val LINE_BREAK = Regex("\r\n|\n|\r")
}
