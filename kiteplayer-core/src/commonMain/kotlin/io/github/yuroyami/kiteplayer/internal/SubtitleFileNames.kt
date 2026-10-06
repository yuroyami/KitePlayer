package io.github.yuroyami.kiteplayer.internal

/**
 * What a subtitle file's name says about it (#514): `Film.en.srt` is English, `Film.eng.forced.srt`
 * English and forced, and `Film.pt-BR.sdh.srt` Brazilian Portuguese for the hearing impaired.
 */
internal class SubtitleNameHints(val language: String?, val forced: Boolean, val hearingImpaired: Boolean) {
    companion object {
        val NONE: SubtitleNameHints = SubtitleNameHints(null, forced = false, hearingImpaired = false)
    }
}

/**
 * The hints in the name of the file [uri] names: the last segment of its path, without a query or a
 * fragment, and without its extension. The dotted parts after the title are read from the end while
 * each is a language or a flag: an ISO 639-1 or 639-2 code or a BCP 47 tag built on one, `forced`,
 * or `sdh`, `cc` or `hi` for the hearing impaired. The first part is always the title, and a part
 * in title case, such as the `Pi` of `Life.of.Pi`, is a word rather than a code. `hi` is Hindi when
 * no other part names a language, and a hearing-impaired flag when one does.
 */
internal fun subtitleNameHints(uri: String): SubtitleNameHints {
    val name = uri.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('\\')
    val stem = name.substringBeforeLast('.', missingDelimiterValue = "")
    val parts = stem.split('.')
    if (parts.size < 2) return SubtitleNameHints.NONE
    val suffix = ArrayList<String>()
    for (index in parts.lastIndex downTo 1) {
        val part = parts[index]
        if (part.lowercase() !in FLAGS && !isLanguagePart(part)) break
        suffix.add(0, part)
        if (suffix.size == MAX_HINT_PARTS) break
    }
    val languages = suffix.filter(::isLanguagePart)
    val hindiIsAFlag = languages.any { !it.equals("hi", ignoreCase = true) }
    val language = languages.lastOrNull { !(hindiIsAFlag && it.equals("hi", ignoreCase = true)) }
    val flags = suffix.map { it.lowercase() }.filter { it in FLAGS && (it != "hi" || hindiIsAFlag) }
    return SubtitleNameHints(
        language = language?.replace('_', '-'),
        forced = "forced" in flags,
        hearingImpaired = flags.any { it in HEARING_IMPAIRED },
    )
}

/** True when [part] is a language code: lower or upper case, never title case, on a known language. */
private fun isLanguagePart(part: String): Boolean {
    val subtags = part.split('-', '_')
    val primary = subtags[0]
    if (primary.length !in 2..3 || !primary.all { it.isLetter() }) return false
    if (primary != primary.lowercase() && primary != primary.uppercase()) return false
    if (!LanguageTag.isKnownCode(primary.lowercase())) return false
    return subtags.drop(1).all { subtag ->
        subtag.length == 2 && subtag.all { it.isLetter() } ||
            subtag.length == 4 && subtag.all { it.isLetter() } ||
            subtag.length == 3 && subtag.all { it.isDigit() }
    }
}

private val HEARING_IMPAIRED = setOf("sdh", "cc", "hi")
private val FLAGS = HEARING_IMPAIRED + "forced"

/** How many dotted parts before the extension are read at most. */
private const val MAX_HINT_PARTS = 4
