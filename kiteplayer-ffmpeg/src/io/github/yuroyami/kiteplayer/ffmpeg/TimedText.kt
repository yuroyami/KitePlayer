package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubRipParser
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * MPEG-4 timed text, the `mov_text` (tx3g) subtitles of an MP4 or MOV file, as 3GPP TS 26.245
 * lays it out (#512). A sample is a 2-byte big-endian text length, that many bytes of text, then
 * boxes. The `styl` box gives runs of characters their face and colour; the track's sample
 * description, which FFmpeg hands over as the codec extradata, gives the style of everything else.
 * The `hlit`, `hclr`, `krok` and `tbox` boxes are not read.
 */

/** The face flags and colour of a run of timed text, from a StyleRecord. [argb] is opaque white by default. */
internal data class TimedTextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val argb: Int = 0xFFFFFFFF.toInt(),
) {
    fun toCueStyle(): CueStyle = CueStyle(bold = bold, italic = italic, underline = underline, primaryColor = argb)
}

/** Characters [start] up to, not including, [end] of a sample's text, in [style]. */
internal class TimedTextRun(val start: Int, val end: Int, val style: TimedTextStyle)

/** One sample: its text and the runs its `styl` box names. */
internal class TimedTextSample(val text: String, val runs: List<TimedTextRun>)

/**
 * The default style of a track from its sample description: display flags (4 bytes), the two
 * justifications (2), the background colour (4) and the text box (8), then the 12-byte
 * StyleRecord. Null when [extradata] is too short to hold it.
 */
internal fun timedTextDefaultStyle(extradata: ByteArray?): TimedTextStyle? =
    if (extradata == null || extradata.size < DEFAULT_STYLE_AT + STYLE_RECORD_BYTES) null else styleAt(extradata, DEFAULT_STYLE_AT)

/**
 * The text and the style runs of one sample. Text that starts with the UTF-16 byte order mark is
 * UTF-16, as the specification allows; any other is UTF-8. A box that runs past the sample ends
 * the reading, and so does a `styl` record that does.
 */
internal fun parseTimedTextSample(payload: ByteArray): TimedTextSample {
    if (payload.size < 2) return TimedTextSample("", emptyList())
    val length = u16(payload, 0)
    val textEnd = (2 + length).coerceAtMost(payload.size)
    val text = when {
        textEnd - 2 >= 2 && payload[2] == 0xFE.toByte() && payload[3] == 0xFF.toByte() -> utf16(payload, 4, textEnd)
        else -> payload.decodeToString(2, textEnd)
    }
    val runs = mutableListOf<TimedTextRun>()
    var at = 2 + length
    while (at + 8 <= payload.size) {
        val size = u32(payload, at)
        if (size < 8 || size > payload.size - at) break
        val type = payload.decodeToString(at + 4, at + 8)
        if (type == "styl" && size >= 10) {
            val count = u16(payload, at + 8)
            var record = at + 10
            for (n in 0 until count) {
                if (record + STYLE_RECORD_BYTES > at + size) break
                runs += TimedTextRun(u16(payload, record), u16(payload, record + 2), styleAt(payload, record))
                record += STYLE_RECORD_BYTES
            }
        }
        at += size.toInt()
    }
    return TimedTextSample(text, runs)
}

/**
 * One cue from a sample. A sample with no style runs, in a track whose default style is plain,
 * reads as SubRip text, as before, so a file whose text carries SubRip tags still shows them as
 * styles. Every other sample's text is plain, and its runs and the [default] style make the spans.
 * Characters are counted as the specification counts them, one for each character however many
 * bytes it takes, so text before a run in any script lands the run on the right characters.
 */
internal fun timedTextCue(payload: ByteArray, default: TimedTextStyle?, startMicros: Long, endMicros: Long): SubtitleCue.Text? {
    val sample = parseTimedTextSample(payload)
    if (sample.text.isEmpty()) return null
    val base = default ?: TimedTextStyle()
    if (sample.runs.isEmpty() && base == TimedTextStyle()) return SubRipParser.parseCue(sample.text, startMicros, endMicros)
    val spans = timedTextSpans(sample, base)
    if (spans.isEmpty()) return null
    return SubtitleCue.Text(startMicros, endMicros, spans)
}

/** The spans of [sample]: each run in its own style, and the text between them in [base]. */
internal fun timedTextSpans(sample: TimedTextSample, base: TimedTextStyle): List<StyledSpan> {
    val text = sample.text
    // Where each character starts in the string, and the string's end: a character outside the
    // basic plane takes two places in a Kotlin string and counts once.
    val starts = ArrayList<Int>(text.length + 1)
    var i = 0
    while (i < text.length) {
        starts += i
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
    }
    starts += text.length
    val characters = starts.size - 1
    val pieces = mutableListOf<Pair<String, TimedTextStyle>>()
    fun add(from: Int, to: Int, style: TimedTextStyle) {
        if (to > from) pieces += text.substring(starts[from], starts[to]).replace("\r", "") to style
    }
    var at = 0
    for (run in sample.runs.sortedBy { it.start }) {
        val start = run.start.coerceIn(at, characters)
        val end = run.end.coerceIn(start, characters)
        add(at, start, base)
        add(start, end, run.style)
        at = end
    }
    add(at, characters, base)
    // SubRip text is trimmed, so timed text is too, without moving a run off its characters.
    if (pieces.isNotEmpty()) pieces[0] = pieces[0].copy(first = pieces[0].first.trimStart())
    if (pieces.isNotEmpty()) pieces[pieces.lastIndex] = pieces.last().copy(first = pieces.last().first.trimEnd())
    val spans = mutableListOf<StyledSpan>()
    for ((piece, style) in pieces) {
        if (piece.isEmpty()) continue
        val cueStyle = style.toCueStyle()
        val last = spans.lastOrNull()
        if (last != null && last.style == cueStyle) {
            spans[spans.lastIndex] = last.copy(text = last.text + piece)
        } else {
            spans += StyledSpan(piece, cueStyle)
        }
    }
    return spans
}

/** A StyleRecord's face and colour at [at]: start, end, font id, face flags, font size, then RGBA. */
private fun styleAt(bytes: ByteArray, at: Int): TimedTextStyle {
    val face = bytes[at + 6].toInt()
    val rgba = u32(bytes, at + 8)
    val argb = ((rgba and 0xFF) shl 24) or (rgba ushr 8)
    return TimedTextStyle(
        bold = face and FACE_BOLD != 0,
        italic = face and FACE_ITALIC != 0,
        underline = face and FACE_UNDERLINE != 0,
        argb = argb.toInt(),
    )
}

private fun u16(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

private fun u32(bytes: ByteArray, at: Int): Long =
    ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
        ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

/** Big-endian UTF-16 from [from] to [to]. A lone trailing byte is dropped. */
private fun utf16(bytes: ByteArray, from: Int, to: Int): String {
    val out = StringBuilder((to - from) / 2)
    var at = from
    while (at + 1 < to) {
        out.append(u16(bytes, at).toChar())
        at += 2
    }
    return out.toString()
}

private const val DEFAULT_STYLE_AT = 18
private const val STYLE_RECORD_BYTES = 12
private const val FACE_BOLD = 1
private const val FACE_ITALIC = 2
private const val FACE_UNDERLINE = 4
