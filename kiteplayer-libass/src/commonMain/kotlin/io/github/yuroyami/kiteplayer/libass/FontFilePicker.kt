package io.github.yuroyami.kiteplayer.libass

/**
 * One font file a directory scan found, before it is read.
 */
internal class FontFileCandidate(val name: String, val sizeBytes: Long, val path: String)

/**
 * Orders a directory scan's font files so the useful ones fit a byte budget.
 *
 * Sans-serif text faces first (Roboto, Noto Sans, DejaVu Sans, Liberation Sans, Arial, Open Sans),
 * regular weights before the rest, then every other TrueType or OpenType file by name. Files that
 * would pass the budget are skipped, not truncated, and the scan never reads a file it will not
 * hand to libass. Symbol and emoji faces are pushed last: `AndroidClock.ttf` sorts first
 * alphabetically and carries not one Latin letter, which is how a "fonts loaded, nothing rendered"
 * report came to be written.
 */
internal fun pickFontFiles(candidates: List<FontFileCandidate>, budgetBytes: Long): List<FontFileCandidate> {
    val ranked = candidates
        .filter { it.name.substringAfterLast('.', "").lowercase() in FONT_FILE_EXTENSIONS }
        .sortedWith(compareBy<FontFileCandidate>({ rank(it.name) }, { it.name.lowercase() }))
    val chosen = ArrayList<FontFileCandidate>()
    var spent = 0L
    ranked.forEach { candidate ->
        if (candidate.sizeBytes <= 0 || spent + candidate.sizeBytes > budgetBytes) return@forEach
        spent += candidate.sizeBytes
        chosen += candidate
    }
    return chosen
}

private val FONT_FILE_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

private val SANS_FAMILIES = listOf("roboto", "notosans", "dejavusans", "liberationsans", "arial", "opensans", "droidsans")

private val PUSHED_LAST = listOf("emoji", "symbol", "clock", "color", "math", "music", "braille", "dingbat")

private fun rank(fileName: String): Int {
    val lower = fileName.lowercase().replace(" ", "").replace("_", "").replace("-", "")
    if (PUSHED_LAST.any { it in lower }) return 9
    val sans = SANS_FAMILIES.indexOfFirst { lower.startsWith(it) }
    if (sans < 0) return 5
    // Regular weights of the first families come first; their italics and bolds follow.
    val plain = lower.contains("regular") || !lower.contains("bold") && !lower.contains("italic") &&
        !lower.contains("light") && !lower.contains("thin") && !lower.contains("black") && !lower.contains("medium")
    return if (plain) sans / 2 else 3 + sans / 4
}
