package io.github.yuroyami.kiteplayer.internal

/**
 * The encodings an application can name for an external subtitle file (#515), and the labels it
 * may name each by.
 *
 * Every name the encoding guess can report is here, so whatever [io.github.yuroyami.kiteplayer.PlaybackWarning.SubtitleCharsetGuessed]
 * says a file was read as, an application can ask for again, and the ones it offers in place of
 * that. The labels are the WHATWG Encoding Standard's, the names browsers accept, matched in any
 * letter case and with white space around them ignored. Two departures, both so that a name the
 * guess reports reads the same table when it is asked for: `ISO-8859-9` and its labels read the
 * ISO table the guess uses, where the standard reads windows-1254 for them, and `US-ASCII`, which
 * the standard folds into windows-1252, is that table too, as the standard says.
 */
internal object SubtitleEncodings {

    const val UTF_8: String = "UTF-8"
    const val UTF_16LE: String = "UTF-16LE"
    const val UTF_16BE: String = "UTF-16BE"

    /** The multi-byte East Asian encodings, which the backend's subtitle parser reads. */
    val eastAsian: List<String> = listOf("Shift_JIS", "EUC-JP", "GBK", "Big5", "EUC-KR")

    /** Every name, Unicode first, then the single-byte tables by script, then the East Asian ones. */
    val names: List<String> = listOf(UTF_8, UTF_16LE, UTF_16BE) +
        listOf(
            SubtitleCharset.Windows1252, SubtitleCharset.Windows1250, SubtitleCharset.Iso88592,
            SubtitleCharset.Windows1257, SubtitleCharset.Windows1254, SubtitleCharset.Iso88599,
            SubtitleCharset.Windows1258, SubtitleCharset.Windows1251, SubtitleCharset.Koi8R,
            SubtitleCharset.Windows1253, SubtitleCharset.Windows1255, SubtitleCharset.Windows1256,
            SubtitleCharset.Windows874,
        ).map { it.label } +
        eastAsian

    private val labels: Map<String, String> = buildMap {
        fun name(canonical: String, vararg aliases: String) {
            put(canonical.lowercase(), canonical)
            aliases.forEach { put(it, canonical) }
        }
        name(UTF_8, "unicode-1-1-utf-8", "unicode11utf8", "unicode20utf8", "utf8", "x-unicode20utf8")
        name(UTF_16LE, "csunicode", "iso-10646-ucs-2", "ucs-2", "unicode", "unicodefeff", "utf-16")
        name(UTF_16BE, "unicodefffe")
        name(
            "windows-1252", "ansi_x3.4-1968", "ascii", "cp1252", "cp819", "csisolatin1", "ibm819", "iso-8859-1",
            "iso-ir-100", "iso8859-1", "iso88591", "iso_8859-1", "iso_8859-1:1987", "l1", "latin1", "us-ascii",
            "x-cp1252",
        )
        name("windows-1250", "cp1250", "x-cp1250")
        name(
            "ISO-8859-2", "csisolatin2", "iso-ir-101", "iso8859-2", "iso88592", "iso_8859-2", "iso_8859-2:1987",
            "l2", "latin2",
        )
        name("windows-1257", "cp1257", "x-cp1257")
        name("windows-1254", "cp1254", "x-cp1254")
        name(
            "ISO-8859-9", "csisolatin5", "iso-ir-148", "iso8859-9", "iso88599", "iso_8859-9", "iso_8859-9:1989",
            "l5", "latin5",
        )
        name("windows-1258", "cp1258", "x-cp1258")
        name("windows-1251", "cp1251", "x-cp1251")
        name("KOI8-R", "cskoi8r", "koi", "koi8", "koi8_r")
        name("windows-1253", "cp1253", "x-cp1253")
        name("windows-1255", "cp1255", "x-cp1255")
        name("windows-1256", "cp1256", "x-cp1256")
        name("windows-874", "dos-874", "iso-8859-11", "iso8859-11", "iso885911", "tis-620")
        name("Shift_JIS", "csshiftjis", "ms932", "ms_kanji", "shift-jis", "sjis", "windows-31j", "x-sjis")
        name("EUC-JP", "cseucpkdfmtjapanese", "x-euc-jp")
        name(
            "GBK", "chinese", "csgb2312", "csiso58gb231280", "gb2312", "gb_2312", "gb_2312-80", "iso-ir-58",
            "x-gbk",
        )
        name("Big5", "big5-hkscs", "cn-big5", "csbig5", "x-x-big5")
        name(
            "EUC-KR", "cseuckr", "csksc56011987", "iso-ir-149", "korean", "ks_c_5601-1987", "ks_c_5601-1989",
            "ksc5601", "ksc_5601", "windows-949",
        )
    }

    /** The name [label] stands for, or null when it names nothing here. */
    fun canonical(label: String): String? = labels[label.trim().lowercase()]
}
