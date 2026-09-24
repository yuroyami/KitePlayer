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
    /**
     * Set when the bytes look like a multi-byte East Asian encoding that could not be decoded, so
     * the warning can name it. Either nothing supplied a table for it, or no table read the bytes.
     */
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

/**
 * Share of a multi-byte reading that may be characters the table could not read, which is one in
 * fifty. A wrong table leaves far more: every pair it has no entry for becomes U+FFFD or a private
 * use character. The right one leaves almost none, and this forgives a stray byte or a character a
 * vendor added.
 */
private const val MAX_UNREADABLE_SHARE = 0.02

/** The names the multi-byte readings go by, spelled as the WHATWG Encoding Standard spells them. */
private const val SHIFT_JIS = "Shift_JIS"
private const val EUC_JP = "EUC-JP"
private const val GBK = "GBK"
private const val BIG5 = "Big5"
private const val EUC_KR = "EUC-KR"

/** The order the other multi-byte readings are tried in when the likeliest one does not read. */
private val EAST_ASIAN_ORDER = listOf(GBK, BIG5, EUC_KR, EUC_JP, SHIFT_JIS)

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
 *
 * [eastAsian] reads bytes as one of the multi-byte East Asian encodings, given the name the WHATWG
 * Encoding Standard uses (`Shift_JIS`, `EUC-JP`, `GBK`, `Big5` or `EUC-KR`), and answers null when
 * it has no table for that name. The engine passes the backend's `SubtitleFileParser.decode`.
 * Without it, such a file falls back to windows-1252 and the result names the encoding it appears
 * to be in.
 */
internal fun decodeSubtitleBytes(
    bytes: ByteArray,
    languageHint: String? = null,
    eastAsian: ((ByteArray, String) -> String?)? = null,
): DecodedSubtitleText {
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
        // Nothing single-byte fits. Only now is it worth asking about a multi-byte encoding: dense
        // Cyrillic has exactly the byte-pair shape EUC does, so asking that question first told
        // every Russian subtitle it was Korean.
        val candidates = eastAsianCandidates(bytes) ?: return fallback(null)
        if (eastAsian != null) readEastAsian(bytes, candidates, eastAsian)?.let { return it }
        return fallback(candidates.first())
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
 * Names the multi-byte East Asian encodings the bytes could be in, likeliest first, from their
 * byte-pair shape alone. Null when they do not have that shape.
 *
 * All five put a character in a lead byte from 0x81 up and a trail byte after it. What tells them
 * apart is where each language's commonest characters sit:
 *
 * - Shift_JIS puts kana and punctuation behind leads 0x81 to 0x9F. The others use those leads only
 *   for rare characters, or never.
 * - Big5 puts about two characters in five on a trail byte below 0x7F. EUC never does, and GBK does
 *   only for characters that simplified Chinese rarely uses.
 * - EUC-JP puts kana on rows 0xA4 and 0xA5, and Japanese text is mostly kana.
 * - EUC-KR puts every common Hangul syllable on rows 0xB0 to 0xC8. Chinese text in GBK uses the
 *   rows above as well, where Korean has only Hanja. Korean also puts a space between words, and
 *   Chinese almost never puts one between two characters, which settles Korean with some Hanja.
 *
 * The other four follow the likeliest, so a table that cannot read the bytes can hand over to the
 * next. This decodes nothing: the tables live in `kiteplayer-subtitles`, above the core.
 */
private fun eastAsianCandidates(bytes: ByteArray): List<String>? {
    var shiftJisLeads = 0
    var lowTrails = 0
    var middleTrails = 0
    var eucPairs = 0
    var kanaRows = 0
    var ideographRows = 0
    var upperRows = 0
    var highTrails = 0
    var i = 0
    while (i < bytes.size - 1) {
        val lead = bytes[i].toInt() and 0xFF
        val trail = bytes[i + 1].toInt() and 0xFF
        if (lead < 0x81 || lead == 0xFF || trail < 0x40 || trail == 0x7F || trail == 0xFF) {
            i++
            continue
        }
        when {
            lead <= 0xA0 -> shiftJisLeads++
            trail <= 0x7E -> lowTrails++
            // A trail from 0x80 to 0xA0 after a high lead: GBK or Shift_JIS, never Big5 or EUC.
            trail <= 0xA0 -> middleTrails++
            else -> {
                eucPairs++
                if (lead == 0xA4 || lead == 0xA5) kanaRows++
                if (lead >= 0xB0) ideographRows++
                if (lead >= 0xC9) upperRows++
            }
        }
        if (trail >= 0x80) highTrails++
        i += 2
    }
    val pairs = shiftJisLeads + lowTrails + middleTrails + eucPairs
    val highBytes = bytes.count { (it.toInt() and 0xFF) >= 0x80 }
    // A handful of pairs happens by chance in any text; a real CJK file is almost entirely pairs.
    // A PROPORTION rather than an exact count: requiring every high byte to pair made this turn on
    // whether the total happened to be even, which is not a property of the encoding.
    if (pairs < 8 || pairs * 2 < highBytes * MIN_PAIRED_SHARE) return null
    // Latin text pairs up too, an accented letter with the letter after it, but its trails are
    // plain ASCII. The five put well over a third of their trails at 0x80 or above, so a text with
    // fewer than a quarter there is none of them.
    if (highTrails * 4 < pairs) return null
    // In order: Shift_JIS when half the pairs sit behind its leads. Big5 when a third sit on low
    // trails, none on middle ones and few behind Shift_JIS leads. GBK when a fifth sit on low
    // trails all the same. EUC-JP when a fifth of the EUC pairs sit on the kana rows. EUC-KR when
    // no more than one pair in twenty on the ideograph rows sits above the Hangul rows, or one in
    // four when there is a space between two characters for every six pairs. GBK otherwise.
    val likeliest = when {
        shiftJisLeads * 2 >= pairs -> SHIFT_JIS
        lowTrails * 3 >= pairs && middleTrails == 0 && shiftJisLeads * 10 <= pairs -> BIG5
        lowTrails * 5 >= pairs -> GBK
        eucPairs > 0 && kanaRows * 5 >= eucPairs -> EUC_JP
        ideographRows > 0 && upperRows * 20 <= ideographRows -> EUC_KR
        ideographRows > 0 && upperRows * 4 <= ideographRows &&
            spacesBetweenCharacters(bytes) * 6 >= eucPairs -> EUC_KR
        else -> GBK
    }
    return listOf(likeliest) + (EAST_ASIAN_ORDER - likeliest)
}

/** ASCII spaces with a high byte on both sides: the gaps between words that Korean writes. */
private fun spacesBetweenCharacters(bytes: ByteArray): Int {
    var spaces = 0
    for (i in 1 until bytes.size - 1) {
        val before = bytes[i - 1].toInt() and 0xFF
        val after = bytes[i + 1].toInt() and 0xFF
        if (bytes[i].toInt() == 0x20 && before >= 0xA1 && after >= 0xA1) spaces++
    }
    return spaces
}

/**
 * The first reading, in [candidates] order, that leaves at most [MAX_UNREADABLE_SHARE] of its
 * characters unread, or null when none does.
 *
 * Only the likeliest encoding read cleanly counts as confident. A reading that forgave anything, or
 * needed a later candidate, says so through the warning.
 */
private fun readEastAsian(
    bytes: ByteArray,
    candidates: List<String>,
    eastAsian: (ByteArray, String) -> String?,
): DecodedSubtitleText? {
    candidates.forEachIndexed { rank, name ->
        // A parser that throws is treated as one without that table: a subtitle never fails an open.
        val text = runCatching { eastAsian(bytes, name) }.getOrNull() ?: return@forEachIndexed
        var nonAscii = 0
        var unreadable = 0
        for (c in text) {
            if (c.code < 0x80) continue
            nonAscii++
            if (c == '\uFFFD' || c in '\uE000'..'\uF8FF') unreadable++
        }
        if (unreadable <= nonAscii * MAX_UNREADABLE_SHARE) {
            return DecodedSubtitleText(text, name, confident = rank == 0 && unreadable == 0)
        }
    }
    return null
}
