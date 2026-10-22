package io.github.yuroyami.kiteplayer.libass

/**
 * One font file a directory scan found, before it is read.
 */
internal class FontFileCandidate(val name: String, val sizeBytes: Long, val path: String)

/**
 * Orders a directory scan's font files so the useful ones fit a byte budget, and so the first one
 * is the platform's Latin sans face, which libass takes as its default family (#507).
 *
 * In this order: the regular faces of the Latin sans families, in the order Roboto, Noto Sans,
 * DejaVu Sans, Liberation Sans, Arial, Open Sans, Droid Sans, FreeSans, then one East Asian face,
 * then the regular faces of the other scripts' Noto families, the scripts subtitles most often use
 * first, then the Latin sans families' other weights, then every other TrueType or OpenType file.
 * Symbol and emoji faces go last: `AndroidClock.ttf` sorts first alphabetically and carries not one
 * Latin letter, which is how a "fonts loaded, nothing rendered" report came to be written. Within
 * a rank, files go by name.
 *
 * A file that would pass the budget is skipped, not truncated, and the scan never reads a file it
 * will not hand to libass. Only one East Asian face is taken, because one covers Chinese, Japanese
 * and Korean and each is several megabytes. The order matters beyond the budget too: an Android
 * system lists about a hundred `NotoSans<Script>` files that sort before `Roboto`, and the old order
 * made one of them the default family and could leave the East Asian face out of the budget.
 */
internal fun pickFontFiles(candidates: List<FontFileCandidate>, budgetBytes: Long): List<FontFileCandidate> {
    val ranked = candidates
        .filter { it.name.substringAfterLast('.', "").lowercase() in FONT_FILE_EXTENSIONS }
        .map { it to rank(it.name) }
        .sortedWith(compareBy<Pair<FontFileCandidate, Int>>({ it.second }, { it.first.name.lowercase() }))
    val chosen = ArrayList<FontFileCandidate>()
    var spent = 0L
    var eastAsian = false
    ranked.forEach { (candidate, rank) ->
        if (candidate.sizeBytes <= 0 || spent + candidate.sizeBytes > budgetBytes) return@forEach
        if (rank / TIER == EAST_ASIAN) {
            if (eastAsian) return@forEach
            eastAsian = true
        }
        spent += candidate.sizeBytes
        chosen += candidate
    }
    return chosen
}

private val FONT_FILE_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

/** The Latin sans families a system's default text face comes from, in order of preference. */
private val SANS_FAMILIES = listOf(
    "roboto", "notosans", "dejavusans", "liberationsans", "arial", "opensans", "droidsans", "freesans",
)

/** East Asian sans faces first, then the serif ones a system may have instead. */
private val EAST_ASIAN_FACES = listOf(
    "notosanscjk", "sourcehansans", "notosanssc", "notosanstc", "notosanshk", "notosansjp", "notosanskr",
    "droidsansfallback", "wqyzenhei", "wqymicrohei", "nanumgothic", "ipagothic", "ipaexgothic",
    "takaopgothic", "notosansmonocjk", "notoserifcjk", "sourcehanserif", "uming", "ukai",
)

/** Scripts in the order subtitles most often need them; the rest of the Noto scripts follow by name. */
private val COMMON_SCRIPTS = listOf(
    "arabic", "hebrew", "thai", "devanagari", "bengali", "tamil", "telugu", "kannada", "malayalam",
    "gujarati", "gurmukhi", "sinhala", "khmer", "lao", "myanmar", "georgian", "armenian", "ethiopic",
)

/** Words in a file name that name its regular face. */
private val REGULAR_WORDS = listOf("regular", "book", "normal", "roman", "static", "variable", "vf")

/** Words in a file name that name any other face. */
private val STYLE_WORDS = listOf(
    "semibold", "extrabold", "demibold", "bold", "italic", "oblique", "extralight", "light", "thin",
    "black", "heavy", "medium", "semicondensed", "condensed", "narrow",
)

private val PUSHED_LAST = listOf("emoji", "symbol", "clock", "color", "math", "music", "braille", "dingbat")

private const val TIER = 100
private const val LATIN = 0
private const val EAST_ASIAN = 1
private const val SCRIPTS = 2
private const val LATIN_OTHER_FACES = 3
private const val THE_REST = 5
private const val LAST = 9

/** The name without its extension, lower case, with no spaces, dashes, underscores or bracketed axes. */
private fun stem(fileName: String): String = fileName.substringBeforeLast('.').lowercase()
    .replace(Regex("""\[[^\]]*\]"""), "")
    .filter { it != ' ' && it != '-' && it != '_' && it != '.' }

private fun withoutWords(text: String, words: List<String>): String =
    words.fold(text) { rest, word -> rest.replace(word, "") }

private fun rank(fileName: String): Int {
    val stem = stem(fileName)
    if (PUSHED_LAST.any { it in stem }) return LAST * TIER
    val regular = STYLE_WORDS.none { it in stem }
    SANS_FAMILIES.forEachIndexed { index, family ->
        if (stem.startsWith(family) && withoutWords(withoutWords(stem.removePrefix(family), STYLE_WORDS), REGULAR_WORDS).isEmpty()) {
            return (if (regular) LATIN else LATIN_OTHER_FACES) * TIER + index
        }
    }
    if (!regular) return THE_REST * TIER
    val eastAsian = EAST_ASIAN_FACES.indexOfFirst { stem.startsWith(it) }
    if (eastAsian >= 0) return EAST_ASIAN * TIER + eastAsian
    if (stem.startsWith("noto") && "serif" !in stem && "mono" !in stem) {
        val script = COMMON_SCRIPTS.indexOfFirst { it in stem }
        return SCRIPTS * TIER + if (script >= 0) script else COMMON_SCRIPTS.size
    }
    return THE_REST * TIER
}
