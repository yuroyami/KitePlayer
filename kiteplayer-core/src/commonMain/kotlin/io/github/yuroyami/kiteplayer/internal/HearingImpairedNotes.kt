package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.HearingImpairedNotes
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * Removes the notes that subtitles for deaf and hard-of-hearing viewers carry (#493), as
 * [HearingImpairedNotes] asks: sound descriptions in square brackets, a parenthesis that opens a
 * line or fills it, and every parenthesis with [HearingImpairedNotes.HideStrict], a speaker's name
 * in capitals before a colon at the start of a line, and a line of music marked with `♪`. Full-width
 * brackets and parentheses count too. A line left empty, or holding only a dash, goes, and a cue left
 * with no line is dropped. The spans keep their styles; only the removed characters leave them.
 *
 * A speaker's name must be written in capital letters that have a lower case, so a script without
 * case, such as Arabic or Hebrew, never matches a rule meant for Latin capitals.
 */
internal fun hideHearingImpairedNotes(cues: List<SubtitleCue>, mode: HearingImpairedNotes): List<SubtitleCue> {
    if (mode == HearingImpairedNotes.Keep) return cues
    var changed = false
    val out = ArrayList<SubtitleCue>(cues.size)
    for (cue in cues) {
        if (cue !is SubtitleCue.Text) {
            out += cue
            continue
        }
        val spans = filterSpans(cue.spans, mode)
        when {
            spans === cue.spans -> out += cue
            spans.isEmpty() -> changed = true
            else -> {
                changed = true
                out += cue.copy(spans = spans)
            }
        }
    }
    return if (changed) out else cues
}

/** [spans] with the notes removed line by line, the same list when nothing changed, or empty when nothing is left. */
private fun filterSpans(spans: List<StyledSpan>, mode: HearingImpairedNotes): List<StyledSpan> {
    // The cue as lines of pieces, each piece a run of one span's text within one line.
    val lines = ArrayList<MutableList<StyledSpan>>()
    var current = ArrayList<StyledSpan>()
    for (span in spans) {
        val parts = span.text.split('\n')
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                lines += current
                current = ArrayList()
            }
            if (part.isNotEmpty()) current += span.copy(text = part)
        }
    }
    lines += current
    var changed = false
    val kept = ArrayList<List<StyledSpan>>()
    for (line in lines) {
        val text = line.joinToString("") { it.text }
        val removed = removals(text, mode)
        if (removed.isEmpty()) {
            if (text.isNotBlank()) kept += line
            continue
        }
        changed = true
        val filtered = cut(line, removed)
        val rest = filtered.joinToString("") { it.text }
        if (rest.isBlank() || rest.trim().all { it in DASHES || it.isWhitespace() }) continue
        kept += tidy(filtered)
    }
    if (!changed) return spans
    val out = ArrayList<StyledSpan>()
    kept.forEachIndexed { index, line ->
        line.forEachIndexed { at, piece ->
            val text = if (index > 0 && at == 0) "\n" + piece.text else piece.text
            val last = out.lastOrNull()
            if (last != null && last.style == piece.style) out[out.lastIndex] = last.copy(text = last.text + text) else out += piece.copy(text = text)
        }
    }
    return out
}

/** The ranges of [line] to remove, in order and not overlapping, or empty when the whole line stays. */
private fun removals(line: String, mode: HearingImpairedNotes): List<IntRange> {
    val trimmed = line.trim()
    // A line of music goes whole.
    if (trimmed.isNotEmpty() && (trimmed.first() in MUSIC || trimmed.last() in MUSIC)) return listOf(line.indices)
    val ranges = ArrayList<IntRange>()
    for (match in SQUARE.findAll(line)) ranges += match.range
    val parentheses = PARENTHESES.findAll(line).toList()
    for (match in parentheses) {
        val before = line.substring(0, match.range.first)
        val opensLine = before.isBlank() || before.trim().all { it in DASHES }
        if (mode == HearingImpairedNotes.HideStrict || opensLine) ranges += match.range
    }
    speakerName(line)?.let { ranges += it }
    if (ranges.isEmpty()) return emptyList()
    ranges.sortBy { it.first }
    val merged = ArrayList<IntRange>()
    for (range in ranges) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, range.last) else merged += range
    }
    return merged
}

/**
 * The range of a speaker's name at the start of [line], with its colon and the space after it: a
 * dash or not, then letters in capitals, digits, spaces and a few marks, then a colon. Null when the
 * line starts otherwise, or when the name has no capital letter with a lower case.
 */
private fun speakerName(line: String): IntRange? {
    var at = 0
    while (at < line.length && line[at] == ' ') at++
    if (at < line.length && line[at] in DASHES) at++
    while (at < line.length && line[at] == ' ') at++
    // Keep a dash that opens a line of dialogue: only the name and its colon go.
    val start = at
    while (at < line.length && at - start <= MAX_SPEAKER_CHARS && (line[at].isLetterOrDigit() || line[at] in SPEAKER_MARKS)) at++
    if (at >= line.length || line[at] != ':' || at == start) return null
    val letters = line.substring(start, at).filter { it.isLetter() }
    if (letters.length < 2) return null
    if (!letters.all { it.isUpperCase() && it.lowercaseChar() != it }) return null
    at++
    while (at < line.length && line[at] == ' ') at++
    return start until at
}

/** [line]'s pieces without the characters in [removed], which are offsets into the line's text. */
private fun cut(line: List<StyledSpan>, removed: List<IntRange>): List<StyledSpan> {
    val out = ArrayList<StyledSpan>()
    var offset = 0
    for (piece in line) {
        val kept = StringBuilder()
        for ((index, char) in piece.text.withIndex()) {
            val at = offset + index
            if (removed.none { at in it }) kept.append(char)
        }
        offset += piece.text.length
        if (kept.isNotEmpty()) out += piece.copy(text = kept.toString())
    }
    return out
}

/** [line] with the spaces a removal left doubled, and at its ends, taken out. */
private fun tidy(line: List<StyledSpan>): List<StyledSpan> {
    val out = ArrayList<StyledSpan>()
    var lastWasSpace = true
    for (piece in line) {
        val text = StringBuilder()
        for (char in piece.text) {
            val space = char == ' '
            if (space && lastWasSpace) continue
            text.append(char)
            lastWasSpace = space
        }
        if (text.isNotEmpty()) out += piece.copy(text = text.toString())
    }
    while (out.isNotEmpty()) {
        val last = out.last()
        val trimmed = last.text.trimEnd()
        if (trimmed.isEmpty()) out.removeAt(out.lastIndex) else {
            out[out.lastIndex] = last.copy(text = trimmed)
            break
        }
    }
    return out
}

private const val MUSIC = "♪♫♬"
// A closing bracket outside a class is escaped: Kotlin/JS compiles every pattern in JavaScript's
// unicode mode, which refuses a bare one, and this object failed to load there (#537).
private val SQUARE = Regex("""\[[^\[\]\n]*\]|［[^［］\n]*］|【[^【】\n]*】""")
private val PARENTHESES = Regex("""\([^()\n]*\)|（[^（）\n]*）""")
private const val DASHES = "-–\u2014"
private const val SPEAKER_MARKS = " .'#&-"
private const val MAX_SPEAKER_CHARS = 40

/** The codecs whose text tracks the filter leaves alone: ASS scripts carry signs and karaoke as text. */
internal val ASS_CODECS: Set<String?> = setOf("ass", "ssa")
