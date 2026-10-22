package io.github.yuroyami.kiteplayer.internal

import kotlin.math.ln

/**
 * The share a character [SUBTITLE_LETTERS] never saw in a language gets there: one in a million.
 * About as rare as the rarest characters it did see, so one stray byte costs a right reading little
 * and a wrong reading, which turns most of its bytes into such characters, a great deal.
 */
private const val UNSEEN_SHARE = 1e-6

/**
 * How many characters of the language's own mix stand behind each character's split between
 * apart from ASCII letters and touching one. A character seen a handful of times keeps close to
 * the language's split; one seen thousands of times keeps its own.
 */
private const val CONTEXT_PSEUDO_COUNT = 2.0

/**
 * The share each of [POOLED_PUNCTUATION] gets in every language. The corpus writes its quotes in
 * ASCII, so this is chosen rather than measured: a file whose only high bytes are curly quotes then
 * reads as plausible text, at about the likelihood of a common letter, and since every windows table
 * puts those quotes on the same bytes, the value never decides between two of them.
 */
private const val PUNCTUATION_SHARE = 0.05

/**
 * One language's subtitles as [SUBTITLE_LETTERS] and [SUBTITLE_CAPITALS] counted them: each
 * character apart from any ASCII letter and touching one, and the capitals straight after a small
 * letter among all of them.
 */
private class LetterCounts(entries: String, val capitals: Int) {
    val apart = HashMap<Char, Int>()
    val touching = HashMap<Char, Int>()
    var total = 0
        private set
    var touchingTotal = 0
        private set

    init {
        var i = 0
        while (i < entries.length) {
            val c = entries[i++]
            var a = 0
            while (entries[i] != '/') a = a * 10 + (entries[i++] - '0')
            i++
            var t = 0
            while (i < entries.length && entries[i] != ' ') t = t * 10 + (entries[i++] - '0')
            i++
            apart[c] = a
            touching[c] = t
            total += a + t
            touchingTotal += t
        }
    }
}

private val letterCounts: Map<String, LetterCounts> by lazy {
    SUBTITLE_LETTERS.mapValues { (language, entries) -> LetterCounts(entries, SUBTITLE_CAPITALS[language] ?: 0) }
}

/**
 * How likely each high byte is, as one charset reads it, in the subtitles of one language: the
 * natural logarithm of its share there, for a byte that no ASCII letter touches in [apart] and for
 * one that an ASCII letter touches on either side in [touching], each indexed by the byte less
 * 0x80. [capital] and [notCapital] are the logarithms of the chance that a character is, or is not,
 * a capital straight after a small letter.
 *
 * The two contexts carry what tells the scripts apart. A letter of Cyrillic, Greek, Arabic, Hebrew or
 * Thai almost never touches an ASCII letter, and an accented Latin letter nearly always does, as in
 * "così"; scored without it, Italian read as Hebrew and Dutch as Greek, each with certainty, because
 * their accents land on those tables' commonest letters (#516). The capitals carry what tells two
 * tables of one script apart: windows-1251 and KOI8-R put the capital and small Cyrillic letters on
 * opposite halves, so a word read through the wrong one comes out as a capital after small letters,
 * and so does Arabic read as Greek or Cyrillic.
 */
internal class ReadingModel(
    val language: String,
    val apart: DoubleArray,
    val touching: DoubleArray,
    val capital: Double,
    val notCapital: Double,
)

/**
 * The model of every language in [SubtitleCharset.languages] that [SUBTITLE_LETTERS] measured, for
 * the reading of [high], that charset's 128 high characters.
 */
internal fun readingModels(languages: Set<String>, high: String): List<ReadingModel> =
    languages.mapNotNull { language ->
        val counts = letterCounts[language] ?: return@mapNotNull null
        val total = counts.total.toDouble()
        val touchingShare = counts.touchingTotal / total
        val apartShare = 1 - touchingShare
        val apart = DoubleArray(0x80)
        val touching = DoubleArray(0x80)
        for (b in 0 until 0x80) {
            val ch = high[b]
            if (ch in POOLED_PUNCTUATION) {
                apart[b] = ln(PUNCTUATION_SHARE)
                touching[b] = ln(PUNCTUATION_SHARE)
                continue
            }
            val key = folded(ch)
            val a = counts.apart[key]
            val t = counts.touching[key]
            if (a == null || t == null) {
                apart[b] = ln(UNSEEN_SHARE * apartShare)
                touching[b] = ln(UNSEEN_SHARE * touchingShare)
                continue
            }
            val seen = (a + t).toDouble()
            val share = seen / total
            apart[b] = ln(share * (a + CONTEXT_PSEUDO_COUNT * apartShare) / (seen + CONTEXT_PSEUDO_COUNT))
            touching[b] = ln(share * (t + CONTEXT_PSEUDO_COUNT * touchingShare) / (seen + CONTEXT_PSEUDO_COUNT))
        }
        val capitalShare = (counts.capitals + 1) / (total + 2)
        ReadingModel(language, apart, touching, ln(capitalShare), ln(1 - capitalShare))
    }

/**
 * The character [SUBTITLE_LETTERS] counts [ch] as: its small letter, wherever that is one character
 * above ASCII. The capital dotted I lowers to a plain ASCII i, so it is counted as it is.
 */
private fun folded(ch: Char): Char {
    val lower = ch.lowercaseChar()
    return if (lower.code >= 0x80) lower else ch
}

/**
 * How many characters an East Asian language's rarer characters, those past [EAST_ASIAN_CHARACTERS],
 * share among them: about as many as GBK holds, the largest of the five encodings at 21,886.
 */
private const val EAST_ASIAN_REPERTOIRE = 20_000.0

/**
 * One East Asian language's subtitles as [EAST_ASIAN_CHARACTERS] and [EAST_ASIAN_BANDS] counted
 * them: the natural logarithm of each character's share, which inside a band is that band's share
 * spread evenly over its characters, and past the bands the rest spread over the repertoire.
 */
internal class EastAsianModel(characters: String, bands: IntArray) {
    private val likelihoods = HashMap<Char, Double>(characters.length * 2)
    private val rare: Double

    init {
        val total = bands.sum().toDouble()
        var start = 0
        for (band in 0 until bands.size - 1) {
            val end = minOf(start + (1 shl band), characters.length)
            if (end > start) {
                val each = ln(bands[band] / total / (end - start))
                for (i in start until end) likelihoods[characters[i]] = each
            }
            start = end
        }
        rare = ln(maxOf(bands.last(), 1) / total / EAST_ASIAN_REPERTOIRE)
    }

    /**
     * How likely [text] is in this language: the sum, over its characters above ASCII, of the
     * natural logarithm of each one's share. A character the table could not read counts as a rare
     * one.
     */
    fun likelihood(text: String): Double {
        var sum = 0.0
        for (c in text) if (c.code >= 0x80) sum += likelihoods[c] ?: rare
        return sum
    }
}

/** The model of each language [EAST_ASIAN_CHARACTERS] measured, by its language tag. */
internal val eastAsianModels: Map<String, EastAsianModel> by lazy {
    EAST_ASIAN_CHARACTERS.mapValues { (language, characters) -> EastAsianModel(characters, EAST_ASIAN_BANDS.getValue(language)) }
}
