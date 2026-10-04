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

/**
 * How likely a reading has to be to read as real text: the average, over its high bytes, of the
 * natural logarithm of each character's share in the language it reads best as. On held-out
 * subtitle lines, text read through its own table averages about -2 at any length, and 99 samples
 * in 100 of five lines stay above -3.7, while a multi-byte East Asian file read one byte at a time
 * falls under -4 in 9 files in 10 of two lines and in all but one in 500 of five; a line or two of
 * it can still read as Arabic or Thai (#520). Below this, no single-byte reading is certain, and the
 * file is asked whether it is East Asian.
 */
private const val MIN_LIKELIHOOD = -4.0

/**
 * How much likelier than every reading that shows different text the best one has to be to count
 * as certain, as a natural logarithm: a factor of about a thousand. It trades confident mistakes
 * against unsure answers. On the held-out lines, 6 left a third more confident mistakes, and 8 left
 * three more samples in a hundred unsure.
 */
private const val MIN_MARGIN = 7.0

/**
 * What a track's declared language is worth, as a natural logarithm: the charsets that do not list
 * it pay this, a factor of about twenty thousand. On the held-out lines, half of it still left a
 * confident mistake among files with their language given, and twice it changed almost nothing.
 */
private const val HINT_WEIGHT = 10.0

/**
 * What an ASCII byte from 0x40 to 0x7E is worth in single-byte text, as a natural logarithm: about
 * one in a thousand. Shift_JIS, GBK and Big5 put many of their second bytes there. A single-byte
 * reading takes those bytes as ASCII, which its likelihood leaves out, so to be weighed against an
 * East Asian reading, which counts them, it is charged this for each. In subtitles those capitals
 * and symbols cost -7 in French, German, Polish and Vietnamese and down to -11.7 in Hebrew; the
 * cheapest is charged, which favours the single-byte reading.
 */
private const val ASCII_SECOND_BYTE_LIKELIHOOD = -7.0

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

/**
 * The high bytes of a file, counted once for every charset to read them: how often each occurs
 * where no ASCII letter touches it, at an even index of [counts], and where one does on either
 * side, at the odd index after it, both from the byte less 0x80 times two.
 */
private class HighBytes(private val bytes: ByteArray) {
    val counts = IntArray(0x100)
    var total = 0
        private set

    init {
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) continue
            val touching = bytes.isAsciiLetterAt(i - 1) || bytes.isAsciiLetterAt(i + 1)
            counts[(b - 0x80) * 2 + if (touching) 1 else 0]++
            total++
        }
    }

    /** The high byte values the file holds, ascending. */
    val present: List<Int> = (0x80..0xFF).filter { counts[(it - 0x80) * 2] + counts[(it - 0x80) * 2 + 1] > 0 }

    /**
     * How often [charset] reads a capital letter from a high byte straight after a small letter,
     * which is how a word read through the wrong one of two tables of a script comes out.
     */
    fun capitalsAfterSmallLetters(charset: SubtitleCharset): Int {
        var events = 0
        for (i in 1 until bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80 || !charset.decodedChar(b).isUpperCase()) continue
            val before = bytes[i - 1].toInt() and 0xFF
            val previous = if (before < 0x80) before.toChar() else charset.decodedChar(before)
            if (previous.isLowerCase()) events++
        }
        return events
    }
}

/**
 * One charset's reading of a file: [score] is how likely its text is in the language it reads best
 * as, the logarithm summed over the high bytes, and [text] is what those bytes read as, one
 * character for each byte value present, so two tables that agree on every byte the file holds read
 * alike.
 */
private class Reading(val charset: SubtitleCharset, val score: Double, val text: String)

private fun SubtitleCharset.read(high: HighBytes, language: String?, hinted: Boolean): Reading? {
    if (models.isEmpty()) return null
    val capitals = high.capitalsAfterSmallLetters(this)
    var best = Double.NEGATIVE_INFINITY
    for (model in models) {
        var score = 0.0
        for (b in high.present) {
            val index = (b - 0x80) * 2
            score += high.counts[index] * model.apart[b - 0x80] + high.counts[index + 1] * model.touching[b - 0x80]
        }
        score += capitals * model.capital + (high.total - capitals) * model.notCapital
        if (score > best) best = score
    }
    if (hinted && language !in languages) best -= HINT_WEIGHT
    val text = buildString(high.present.size) { for (b in high.present) append(decodedChar(b)) }
    return Reading(this, best, text)
}

private fun ByteArray.isAsciiLetterAt(index: Int): Boolean {
    if (index !in indices) return false
    val b = this[index].toInt()
    return b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code
}

/**
 * Decodes subtitle bytes, deciding the encoding from the bytes themselves.
 *
 * The order is strongest evidence first: a byte-order mark is a declaration, a file that validates
 * as UTF-8 is as good as one (legacy text almost never validates by accident), and only then does
 * anything guess.
 *
 * The guess reads the file through every single-byte table and asks how likely each text is in the
 * languages that table is written in, from how often each character appears in their subtitles
 * ([SUBTITLE_LETTERS]), whether an ASCII letter touches it, and how often a capital follows a small
 * letter. That is the question uchardet, which mpv uses, asks. Tables that read every byte the file
 * holds alike are one answer. The best is certain when it reads as real text and is far likelier
 * than any reading that shows different text. When it is not, the result says so, and shows the best
 * reading still, or windows-1252, the usual default of VLC and of the Windows that wrote these files,
 * when that is nearly as likely. Only when no single-byte reading reads as text at all is the file
 * asked whether it is one of the multi-byte East Asian encodings, and an East Asian reading is kept
 * only when its characters are likelier in its language ([EAST_ASIAN_CHARACTERS]) than the single-byte
 * reading's are in theirs.
 *
 * [languageHint] is the track's declared language when there is one. It counts against the charsets
 * that do not list it, which settles readings the bytes leave close, and it never overrules a clear
 * answer.
 *
 * [fallback] is an encoding to read a file in when it has no byte-order mark and is not UTF-8, in
 * place of the guess. One that cannot be read here, an East Asian name with no table, is ignored.
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
    fallback: String? = null,
    eastAsian: ((ByteArray, String) -> String?)? = null,
): DecodedSubtitleText {
    bom(bytes)?.let { return it }
    if (isValidUtf8(bytes)) {
        return DecodedSubtitleText(bytes.decodeToString(), "UTF-8", confident = true)
    }
    // The application's own choice for a file that says nothing about itself, as VLC's default
    // encoding and mpv's sub-codepage are. Nothing is guessed, so nothing is warned (#515).
    if (fallback != null) decodeSubtitleBytesAs(bytes, fallback, eastAsian)?.let { return it }
    // Not UTF-8 and no mark. Anything from here is inference, and an unsure reading is what an
    // honest failure looks like rather than an exception: a subtitle track that shows imperfect
    // text beats one that does not load.
    fun unsure(charset: SubtitleCharset, guess: String? = null) = DecodedSubtitleText(
        text = charset.decode(bytes),
        charset = charset.label,
        confident = false,
        unsupportedGuess = guess,
    )
    val high = HighBytes(bytes)
    if (high.total == 0) {
        // Pure ASCII that failed UTF-8 validation is not reachable, but a file of nothing but
        // ASCII is: every charset here agrees about it, so there is nothing to choose.
        return DecodedSubtitleText(bytes.decodeToString(), "US-ASCII", confident = true)
    }

    // The hint as one spelling, so `lit`, `lt` and `lt-LT` all name Lithuanian.
    val language = LanguageTag.parse(languageHint)?.language
    val hinted = language != null && SubtitleCharset.entries.any { language in it.languages }
    // A table that reads one of the file's bytes as nothing, or as a control character, is not the
    // file's. Tables that read every byte present alike show the same text, so they are one answer
    // and never each other's runner-up: windows-1250 and ISO-8859-2 read most Polish letters alike,
    // and counted apart, the tie left Polish unsure for ever (#518).
    val answers = SubtitleCharset.entries
        .filter { charset -> high.present.none { charset.cannotBeText(it) } }
        .mapNotNull { charset -> charset.read(high, language, hinted) }
        .sortedByDescending { it.score }
        .groupBy { it.text }
        .values
        .toList()
    val best = answers.firstOrNull()
    val plausible = best != null && best.first().score / high.total >= MIN_LIKELIHOOD
    if (!plausible) {
        // Nothing single-byte reads as text. Only now is it worth asking about a multi-byte
        // encoding: dense Cyrillic has exactly the byte-pair shape EUC does, so asking that
        // question first told every Russian subtitle it was Korean.
        eastAsianCandidates(bytes)?.let { candidates ->
            val reading = eastAsian?.let { readEastAsian(bytes, candidates, it) }
                ?: return unsure(SubtitleCharset.Windows1252, candidates.first())
            // GBK and Shift_JIS read almost any run of high bytes without a gap, so a clean reading
            // is not yet a likely one: a Thai line heavy with rare letters, read below the floor in
            // Thai, came out as Japanese. The East Asian reading is taken when its characters are
            // likelier in its own language than the best single-byte reading's are in theirs.
            val singleByte = best?.first()?.score?.plus(asciiSecondBytes(bytes) * ASCII_SECOND_BYTE_LIKELIHOOD)
            if (singleByte == null || reading.eastAsianLikelihood() > singleByte) return reading
        }
    }
    // Not East Asian either, so the likeliest single-byte reading is still the best there is, only
    // never a certain one: a one-word Thai line such as "ใช่" reads below the floor in Thai and
    // far below it in every other table.
    if (best == null) return unsure(SubtitleCharset.Windows1252)
    val score = best.first().score
    // The tables in the answer show the same text, so which one it is named by only matters to
    // the warning and the override it offers: the one the track's language names, as windows-1254
    // for Turkish that ISO-8859-9 reads alike, then windows-1252, the usual default, and otherwise
    // the likeliest.
    val charset = (
        best.firstOrNull { language in it.charset.languages }
            ?: best.firstOrNull { it.charset == SubtitleCharset.Windows1252 }
            ?: best.first()
        ).charset
    val runnerUp = answers.getOrNull(1)?.first()?.score ?: Double.NEGATIVE_INFINITY
    if (score - runnerUp >= MIN_MARGIN) {
        return DecodedSubtitleText(charset.decode(bytes), charset.label, confident = plausible)
    }
    // Unsure, the file is shown as the likeliest reading, unless windows-1252 is nearly as likely:
    // that is what such a file is shown as everywhere else, so a wrong guess there is the familiar
    // one, and it keeps short Western lines with an accent or two from showing as Polish. A track
    // whose language names the likeliest reading is shown in it all the same: a Lithuanian line
    // with two ė reads nearly as well as Albanian ë, and its language says which it is.
    val named = language != null && best.any { language in it.charset.languages }
    val western = answers.firstOrNull { answer -> answer.any { it.charset == SubtitleCharset.Windows1252 } }
    if (!named && western != null && western !== best && score - western.first().score < MIN_MARGIN) {
        return unsure(SubtitleCharset.Windows1252)
    }
    return unsure(charset)
}

/**
 * Reads [bytes] as [encoding] with no guess, for a file whose encoding the application named (#515).
 *
 * [encoding] is one of [SubtitleEncodings.names] or a label for one. The bytes are read as told: a
 * byte the encoding does not define becomes U+FFFD, so a file that is not in the encoding it was
 * given shows that, rather than failing. A Unicode encoding skips its own byte-order mark, which is
 * its signature rather than text; any other mark is read as the bytes it is. Null when [encoding]
 * names nothing here, or names an East Asian encoding that [eastAsian] has no table for.
 */
internal fun decodeSubtitleBytesAs(
    bytes: ByteArray,
    encoding: String,
    eastAsian: ((ByteArray, String) -> String?)? = null,
): DecodedSubtitleText? {
    val name = SubtitleEncodings.canonical(encoding) ?: return null
    val text = when (name) {
        SubtitleEncodings.UTF_8, SubtitleEncodings.UTF_16LE, SubtitleEncodings.UTF_16BE ->
            bom(bytes)?.takeIf { it.charset == name }?.text ?: when (name) {
                SubtitleEncodings.UTF_8 -> bytes.decodeToString()
                else -> decodeUtf16(bytes, littleEndian = name == SubtitleEncodings.UTF_16LE, from = 0)
            }
        in SubtitleEncodings.eastAsian ->
            // A parser that throws is one without the table, as it is for the guess.
            eastAsian?.let { read -> runCatching { read(bytes, name) }.getOrNull() } ?: return null
        else -> SubtitleCharset.entries.first { it.label == name }.decode(bytes)
    }
    return DecodedSubtitleText(text, name, confident = true)
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
private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean, from: Int = 2): String = buildString {
    var i = from
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

/**
 * The pairs [eastAsianCandidates] finds whose second byte is ASCII: bytes an East Asian reading
 * counts as part of a character and a single-byte reading takes as ASCII.
 */
private fun asciiSecondBytes(bytes: ByteArray): Int {
    var count = 0
    var i = 0
    while (i < bytes.size - 1) {
        val lead = bytes[i].toInt() and 0xFF
        val trail = bytes[i + 1].toInt() and 0xFF
        if (lead < 0x81 || lead == 0xFF || trail < 0x40 || trail == 0x7F || trail == 0xFF) {
            i++
            continue
        }
        if (trail < 0x80) count++
        i += 2
    }
    return count
}

/**
 * How likely an East Asian reading is in the language its encoding is written in, as
 * [EastAsianModel.likelihood] says: Japanese for Shift_JIS and EUC-JP, simplified Chinese for GBK,
 * traditional Chinese for Big5 and Korean for EUC-KR.
 */
private fun DecodedSubtitleText.eastAsianLikelihood(): Double {
    val language = when (charset) {
        SHIFT_JIS, EUC_JP -> "ja"
        GBK -> "zh-Hans"
        BIG5 -> "zh-Hant"
        else -> "ko"
    }
    return eastAsianModels.getValue(language).likelihood(text)
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
