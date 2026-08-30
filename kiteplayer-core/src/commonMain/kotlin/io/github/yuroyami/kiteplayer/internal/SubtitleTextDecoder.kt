package io.github.yuroyami.kiteplayer.internal

/**
 * The result of reading a subtitle file's bytes as text.
 *
 * [confident] is the honest part. A BOM or a file that validates as UTF-8 is a fact; a single-byte
 * guess is a guess, and the caller warns when it had to make one so an application can offer an
 * override instead of showing mojibake with no explanation.
 */
internal data class DecodedSubtitleText(
    val text: String,
    val charset: String,
    val confident: Boolean,
    /** Set when the bytes look like an encoding this build cannot decode yet, so the warning can name it. */
    val unsupportedGuess: String? = null,
)

/** A winner must read this much of the high bytes as its own script. */
private const val MIN_SCORE = 0.75



/** Real text is largely its own commonest letters; below this share nothing is being read right. */
private const val MIN_COMMON_SHARE = 0.25

/** How far clear of the runner-up on frequency a winner has to be. */
private const val MIN_COMMON_GAP = 0.1

/** Share of high bytes a multi-byte encoding puts into lead/trail pairs. */
private const val MIN_PAIRED_SHARE = 0.8

private class CharsetScore(
    val charset: SubtitleCharset,
    /** Share of high bytes that land in this charset's own script. */
    val inScript: Double,
    /** Share of high bytes that are this charset's commonest letters. See SubtitleCharset.common. */
    val common: Double,
)

private fun SubtitleCharset.scoreAgainst(
    bytes: ByteArray,
    highBytes: Int,
    languageHint: String?,
): CharsetScore {
    var inScript = 0
    var commonHits = 0
    for (raw in bytes) {
        val b = raw.toInt() and 0xFF
        if (b < 0x80) continue
        if (isCommon(b)) commonHits++
        if (!isInScript(b)) continue
        inScript++
    }
    // The hint is worth a hair, enough to order two otherwise identical scores and never enough to
    // promote a charset the bytes argued against.
    val hint = if (languageHint != null && languages.any { languageHint.startsWith(it) }) 0.001 else 0.0
    return CharsetScore(
        charset = this,
        inScript = inScript.toDouble() / highBytes + hint,
        common = commonHits.toDouble() / highBytes,
    )
}

/**
 * Decodes subtitle bytes, deciding the encoding from the bytes themselves.
 *
 * The order is strongest evidence first: a byte-order mark is a declaration, a file that validates
 * as UTF-8 is as good as one (legacy text almost never validates by accident), and only then does
 * anything guess.
 *
 * [languageHint] is the track's declared language when there is one. It only breaks ties: it can
 * choose between two charsets that scored alike and never overrules the bytes.
 */
internal fun decodeSubtitleBytes(bytes: ByteArray, languageHint: String? = null): DecodedSubtitleText {
    bom(bytes)?.let { return it }
    if (isValidUtf8(bytes)) {
        return DecodedSubtitleText(bytes.decodeToString(), "UTF-8", confident = true)
    }
    // Not UTF-8 and no mark. Anything from here is inference, and the fallback below is what an
    // honest failure looks like rather than an exception: a subtitle track that shows imperfect
    // text beats one that does not load.
    val fallback = { guess: String? ->
        DecodedSubtitleText(
            text = SubtitleCharset.Windows1252.decode(bytes),
            charset = SubtitleCharset.Windows1252.label,
            confident = false,
            unsupportedGuess = guess,
        )
    }
    val highBytes = bytes.count { (it.toInt() and 0xFF) >= 0x80 }
    if (highBytes == 0) {
        // Pure ASCII that failed UTF-8 validation is not reachable, but a file of nothing but
        // ASCII is: every charset here agrees about it, so there is nothing to choose.
        return DecodedSubtitleText(bytes.decodeToString(), "US-ASCII", confident = true)
    }

    val scored = SubtitleCharset.entries
        .filter { charset -> bytes.none { charset.isUndefined(it.toInt() and 0xFF) } }
        .map { charset -> charset.scoreAgainst(bytes, highBytes, languageHint) }
        .sortedWith(
            compareByDescending<CharsetScore> { it.common }
                .thenByDescending { it.inScript },
        )

    val best = scored.firstOrNull() ?: return fallback(null)
    val runnerUp = scored.getOrNull(1)
    // Frequency is what actually separates two tables that both map the range into a real script:
    // Arabic bytes read as Cyrillic ARE Cyrillic letters, they are just not Cyrillic WORDS.
    val clearOnFrequency = runnerUp == null || best.common >= runnerUp.common + MIN_COMMON_GAP
    if (best.inScript < MIN_SCORE || best.common < MIN_COMMON_SHARE || !clearOnFrequency) {
        // Nothing single-byte fits. Only now is it worth naming a multi-byte encoding: dense
        // Cyrillic has exactly the byte-pair shape EUC does, so asking that question first told
        // every Russian subtitle it was Korean.
        return fallback(multiByteShape(bytes))
    }
    return DecodedSubtitleText(best.charset.decode(bytes), best.charset.label, confident = true)
}

private fun bom(bytes: ByteArray): DecodedSubtitleText? {
    fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    return when {
        at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF ->
            DecodedSubtitleText(bytes.decodeToString(3, bytes.size), "UTF-8", confident = true)
        at(0) == 0xFF && at(1) == 0xFE ->
            DecodedSubtitleText(decodeUtf16(bytes, littleEndian = true), "UTF-16LE", confident = true)
        at(0) == 0xFE && at(1) == 0xFF ->
            DecodedSubtitleText(decodeUtf16(bytes, littleEndian = false), "UTF-16BE", confident = true)
        else -> null
    }
}

/**
 * UTF-16 with the mark already matched, which nothing here could read before.
 *
 * Worth the twenty lines: a file saved as "Unicode" from Notepad is UTF-16, and the old BOM strip
 * ran on an already-decoded string, so those files were garbage in exactly the same silent way.
 */
private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean): String = buildString {
    var i = 2
    while (i + 1 < bytes.size) {
        val lo = bytes[i].toInt() and 0xFF
        val hi = bytes[i + 1].toInt() and 0xFF
        append((if (littleEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar())
        i += 2
    }
}

/** Strict: overlongs, surrogates, out-of-range and truncated tails all fail. */
private fun isValidUtf8(bytes: ByteArray): Boolean {
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        val extra: Int
        var code: Int
        when {
            b < 0x80 -> { i++; continue }
            b in 0xC2..0xDF -> { extra = 1; code = b and 0x1F }
            b in 0xE0..0xEF -> { extra = 2; code = b and 0x0F }
            b in 0xF0..0xF4 -> { extra = 3; code = b and 0x07 }
            // 0xC0/0xC1 are overlong two-byte leads and 0x80..0xBF cannot lead at all.
            else -> return false
        }
        if (i + extra >= bytes.size) return false
        for (k in 1..extra) {
            val c = bytes[i + k].toInt() and 0xFF
            if (c !in 0x80..0xBF) return false
            code = (code shl 6) or (c and 0x3F)
        }
        val overlong = (extra == 2 && code < 0x800) || (extra == 3 && code < 0x10000)
        if (overlong || code in 0xD800..0xDFFF || code > 0x10FFFF) return false
        i += extra + 1
    }
    return true
}

/**
 * Names a multi-byte East Asian encoding from its byte-pair shape, without carrying its table.
 *
 * This decodes nothing. It exists so a Japanese subtitle file is told what it appears to be rather
 * than called undetectable, which is the difference between a user who can act and one who cannot.
 */
private fun multiByteShape(bytes: ByteArray): String? {
    var eucPairs = 0
    var sjisPairs = 0
    var i = 0
    while (i < bytes.size - 1) {
        val b = bytes[i].toInt() and 0xFF
        val next = bytes[i + 1].toInt() and 0xFF
        when {
            b in 0xA1..0xFE && next in 0xA1..0xFE -> { eucPairs++; i += 2 }
            (b in 0x81..0x9F || b in 0xE0..0xEF) && (next in 0x40..0x7E || next in 0x80..0xFC) -> {
                sjisPairs++; i += 2
            }
            else -> i++
        }
    }
    val pairs = eucPairs + sjisPairs
    val highBytes = bytes.count { (it.toInt() and 0xFF) >= 0x80 }
    // A handful of pairs happens by chance in any text; a real CJK file is almost entirely pairs.
    // A PROPORTION rather than an exact count: requiring every high byte to pair made this turn on
    // whether the total happened to be even, which is not a property of the encoding.
    if (pairs < 8 || pairs * 2 < highBytes * MIN_PAIRED_SHARE) return null
    // EUC covers EUC-KR, GBK and Big5 alike from this distance, so the name stays honest about
    // being a family rather than claiming to have told them apart.
    return if (sjisPairs > eucPairs) "Shift-JIS" else "a EUC or Big5 family encoding"
}
