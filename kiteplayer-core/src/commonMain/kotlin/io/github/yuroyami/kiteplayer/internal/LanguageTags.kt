package io.github.yuroyami.kiteplayer.internal

/**
 * A language tag reduced to what track selection compares (#435).
 *
 * Containers and streaming formats spell one language several ways: Matroska and MP4 carry the
 * three letter ISO 639-2 code, in its terminology form (`deu`) or its bibliographic one (`ger`), and
 * HLS and DASH carry a BCP 47 tag such as `ja`, `en-US` or `zh-Hant`. [language] folds all of them to
 * one spelling, the two letter code where ISO 639-1 has one and the three letter code otherwise, so
 * `ja`, `jpn` and `ja-JP` all read `ja`, while `enm`, Middle English, stays `enm` and never meets `en`.
 *
 * [script] is a four letter script subtag in title case, such as `Hant`, and [region] a two letter
 * region in upper case or a three digit area such as `419`. Extensions and private-use subtags are
 * not kept.
 */
internal class LanguageTag(val language: String, val script: String?, val region: String?) {

    /**
     * The script this tag's text is written in: the one it names, or for Chinese the one its region
     * implies, since `zh-TW` is written in Traditional characters and `zh-CN` in Simplified ones.
     */
    val impliedScript: String?
        get() = script ?: if (language == "zh") CHINESE_SCRIPT_BY_REGION[region] else null

    override fun equals(other: Any?): Boolean =
        other is LanguageTag && language == other.language && script == other.script && region == other.region

    override fun hashCode(): Int = (language.hashCode() * 31 + script.hashCode()) * 31 + region.hashCode()

    override fun toString(): String = listOfNotNull(language, script, region).joinToString("-")

    companion object {
        /**
         * The tag behind [tag], or null when it names no language: absent, blank, not a language
         * code at all, or one of the codes that say "no particular language" (`und`, `mul`, `mis`,
         * `zxx` and the local-use range `qaa` to `qtz`).
         */
        fun parse(tag: String?): LanguageTag? {
            if (tag.isNullOrBlank()) return null
            val parts = tag.trim().split('-', '_').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var language = parts[0].lowercase()
            if (language.length !in 2..3 || !language.all { it in 'a'..'z' }) return null
            var next = 1
            // An extended language subtag, as in `zh-yue`, is the language itself in its preferred
            // form, so `zh-yue` is Cantonese and `zh-cmn` Mandarin.
            if (next < parts.size && parts[next].length == 3 && parts[next].all { it.isLetter() }) {
                language = parts[next].lowercase()
                next++
            }
            language = canonicalLanguage(language) ?: return null
            var script: String? = null
            var region: String? = null
            if (next < parts.size && parts[next].length == 4 && parts[next].all { it.isLetter() }) {
                script = parts[next].lowercase().replaceFirstChar { it.uppercaseChar() }
                next++
            }
            if (next < parts.size) {
                val subtag = parts[next]
                if (subtag.length == 2 && subtag.all { it.isLetter() }) region = subtag.uppercase()
                if (subtag.length == 3 && subtag.all { it.isDigit() }) region = subtag
            }
            return LanguageTag(language, script, region)
        }

        /** The one spelling of [code], a lower-case two or three letter code, or null for no language. */
        private fun canonicalLanguage(code: String): String? {
            if (code in NO_LANGUAGE) return null
            if (code.length == 3 && code[0] == 'q' && code[1] in 'a'..'t') return null
            LEGACY[code]?.let { return it }
            if (code.length == 2) return code
            return TWO_LETTER_BY_THREE[code] ?: code
        }

        private val NO_LANGUAGE = setOf("und", "mul", "mis", "zxx")

        /**
         * Withdrawn codes and the individual languages that tracks commonly carry in place of their
         * macrolanguage, each with the code it now goes by.
         */
        private val LEGACY = mapOf(
            "iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "mo" to "ro", "mol" to "ro",
            "scc" to "sr", "scr" to "hr",
            "cmn" to "zh", "arb" to "ar", "pes" to "fa", "zsm" to "ms", "swh" to "sw", "ekk" to "et",
            "lvs" to "lv", "khk" to "mn", "uzn" to "uz", "azj" to "az", "ydd" to "yi",
        )

        /**
         * ISO 639-1 with the ISO 639-2 codes for each language: the terminology code, then the
         * bibliographic one where the two differ.
         */
        private const val ISO_639 =
            "aa:aar ab:abk ae:ave af:afr ak:aka am:amh an:arg ar:ara as:asm av:ava ay:aym az:aze " +
                "ba:bak be:bel bg:bul bh:bih bi:bis bm:bam bn:ben bo:bod/tib br:bre bs:bos " +
                "ca:cat ce:che ch:cha co:cos cr:cre cs:ces/cze cu:chu cv:chv cy:cym/wel " +
                "da:dan de:deu/ger dv:div dz:dzo ee:ewe el:ell/gre en:eng eo:epo es:spa et:est eu:eus/baq " +
                "fa:fas/per ff:ful fi:fin fj:fij fo:fao fr:fra/fre fy:fry " +
                "ga:gle gd:gla gl:glg gn:grn gu:guj gv:glv " +
                "ha:hau he:heb hi:hin ho:hmo hr:hrv ht:hat hu:hun hy:hye/arm hz:her " +
                "ia:ina id:ind ie:ile ig:ibo ii:iii ik:ipk io:ido is:isl/ice it:ita iu:iku " +
                "ja:jpn jv:jav " +
                "ka:kat/geo kg:kon ki:kik kj:kua kk:kaz kl:kal km:khm kn:kan ko:kor kr:kau ks:kas ku:kur " +
                "kv:kom kw:cor ky:kir " +
                "la:lat lb:ltz lg:lug li:lim ln:lin lo:lao lt:lit lu:lub lv:lav " +
                "mg:mlg mh:mah mi:mri/mao mk:mkd/mac ml:mal mn:mon mr:mar ms:msa/may mt:mlt my:mya/bur " +
                "na:nau nb:nob nd:nde ne:nep ng:ndo nl:nld/dut nn:nno no:nor nr:nbl nv:nav ny:nya " +
                "oc:oci oj:oji om:orm or:ori os:oss " +
                "pa:pan pi:pli pl:pol ps:pus pt:por " +
                "qu:que " +
                "rm:roh rn:run ro:ron/rum ru:rus rw:kin " +
                "sa:san sc:srd sd:snd se:sme sg:sag si:sin sk:slk/slo sl:slv sm:smo sn:sna so:som " +
                "sq:sqi/alb sr:srp ss:ssw st:sot su:sun sv:swe sw:swa " +
                "ta:tam te:tel tg:tgk th:tha ti:tir tk:tuk tl:tgl tn:tsn to:ton tr:tur ts:tso tt:tat " +
                "tw:twi ty:tah " +
                "ug:uig uk:ukr ur:urd uz:uzb " +
                "ve:ven vi:vie vo:vol " +
                "wa:wln wo:wol " +
                "xh:xho " +
                "yi:yid yo:yor " +
                "za:zha zh:zho/chi zu:zul"

        private val TWO_LETTER_BY_THREE: Map<String, String> = buildMap {
            for (entry in ISO_639.split(' ')) {
                val (two, threes) = entry.split(':')
                for (three in threes.split('/')) put(three, two)
            }
        }

        private val CHINESE_SCRIPT_BY_REGION = mapOf(
            "TW" to "Hant", "HK" to "Hant", "MO" to "Hant",
            "CN" to "Hans", "SG" to "Hans", "MY" to "Hans",
        )
    }
}

/** Whether [a] and [b] name one language, whatever their spellings, regions or scripts. */
internal fun sameLanguage(a: String?, b: String?): Boolean {
    val left = LanguageTag.parse(a)
    val right = LanguageTag.parse(b)
    if (left != null && right != null) return left.language == right.language
    // A tag that is not a language code at all can still be spelled the same on both sides.
    return left == null && right == null && a != null && b != null && a.isNotBlank() && a.trim().equals(b.trim(), ignoreCase = true)
}

/**
 * A caller's language preferences, best first, matched against tracks.
 *
 * A track matches a preference when both name the same language. Among the tracks of one language,
 * the one whose script and then region also agree with the preference is closer, so `pt-BR` prefers a
 * `pt-BR` track over a `pt-PT` one and takes either over nothing, and `zh-Hant` prefers a Traditional
 * Chinese track, which a `zh-TW` tag or, on a plain `chi` track, a title such as "Traditional" or
 * "繁體" says it is.
 */
internal class LanguagePreferences(tags: List<String>) {

    private val preferences: List<Pair<String, LanguageTag?>> = tags.map { it to LanguageTag.parse(it) }

    val isEmpty: Boolean get() = preferences.isEmpty()

    /** How a track answered: the first preference it matched, and how closely. */
    data class Match(val preference: Int, val closeness: Int)

    /** The best match of a track tagged [language] and titled [title], or null when it matches none. */
    fun match(language: String?, title: String?): Match? {
        val track = LanguageTag.parse(language)
        preferences.forEachIndexed { index, (raw, wanted) ->
            val closeness = when {
                wanted == null || track == null ->
                    if (sameLanguage(raw, language)) 0 else null
                wanted.language != track.language -> null
                else -> closeness(wanted, track, title)
            }
            if (closeness != null) return Match(index, closeness)
        }
        return null
    }

    /** Whether a track tagged [language] matches any preference. */
    fun matches(language: String?): Boolean = match(language, null) != null

    private fun closeness(wanted: LanguageTag, track: LanguageTag, title: String?): Int {
        var closeness = 0
        val script = wanted.impliedScript
        if (script != null && script == (track.impliedScript ?: titleScript(track, title))) closeness += 2
        if (wanted.region != null && wanted.region == track.region) closeness += 1
        return closeness
    }

    /**
     * The Chinese script a track's title names, for a track whose tag names none. Fansub and disc
     * titles say it in English, in Chinese, or as an abbreviation.
     */
    private fun titleScript(track: LanguageTag, title: String?): String? {
        if (track.language != "zh" || title.isNullOrBlank()) return null
        if ('繁' in title) return "Hant"
        if ('简' in title || '簡' in title) return "Hans"
        val words = title.lowercase().split(Regex("[^a-z]+")).toSet()
        if (words.any { it in TRADITIONAL_WORDS }) return "Hant"
        if (words.any { it in SIMPLIFIED_WORDS }) return "Hans"
        return null
    }

    private companion object {
        val TRADITIONAL_WORDS = setOf("traditional", "hant", "cht", "tc")
        val SIMPLIFIED_WORDS = setOf("simplified", "hans", "chs", "sc")
    }
}
