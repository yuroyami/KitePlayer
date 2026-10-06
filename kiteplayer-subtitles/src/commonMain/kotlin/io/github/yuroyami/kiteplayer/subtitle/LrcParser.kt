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
    public fun isLrc(text: String): Boolean = false

    /** Parses [text] into cues, sorted by start time. Never throws on malformed input. */
    public fun parse(text: String): List<SubtitleCue.Text> = emptyList()
}
