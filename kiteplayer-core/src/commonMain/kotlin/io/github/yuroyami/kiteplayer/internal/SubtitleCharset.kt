package io.github.yuroyami.kiteplayer.internal

/**
 * What encoding an external subtitle file is in, decided from its bytes.
 *
 * Every external subtitle used to be decoded as UTF-8 with a BOM strip, on the stated belief that
 * "UTF-8 with an optional BOM is what every subtitle file in the wild is". It is not. Windows-1256
 * Arabic, Windows-1251 Cyrillic, Windows-1253 Greek and Windows-1255 Hebrew subtitles are ordinary,
 * and every one of them rendered as replacement characters with nothing said.
 *
 * ### Why the tables are Kotlin and not the platform's decoder
 *
 * The obvious plan is `java.nio.charset` on the JVM and something equivalent elsewhere. There is no
 * equivalent elsewhere. Kotlin/Native decodes UTF-8 and nothing else, and the native actual here is
 * ONE source set spanning `androidNativeArm32` through `mingwX64`, neither of which has iconv. The
 * platforms that DO have decoders disagree with each other besides: Windows and Java differ on
 * cp932 for the yen sign and the wave dash, and on cp950 for hundreds of codes. A shared table is
 * the only way the same file reads the same on every target, which is the thing worth having.
 *
 * The web needs none of this: its actual cannot read a local file at all and answers null.
 *
 * ### What is NOT here
 *
 * The multi-byte CJK encodings (Shift-JIS, Big5, GBK, EUC-KR). Their tables are about 155 KB
 * together, which is a separate decision rather than a detail of this one. Detection still
 * RECOGNIZES them from their byte-pair structure and names them in the warning, so a Japanese
 * subtitle file gets told what it is instead of being called undetectable.
 */
internal enum class Script { Arabic, Cyrillic, Greek, Hebrew, Latin }

/** The single-byte encodings worth carrying, each as its own 0x80..0xFF table. */
internal enum class SubtitleCharset(
    val label: String,
    val script: Script,
    /** Language codes that make this charset the likely reading, used only to break a tie. */
    val languages: Set<String>,
    /** The 128 characters bytes 0x80..0xFF decode to. U+FFFD marks a byte the charset does not define. */
    private val high: String,
    /**
     * The BYTES that carry this charset's most common letters, as characters.
     *
     * The small frequency heuristic. Script membership alone cannot tell Arabic bytes from Cyrillic
     * ones, because each table maps the same range fully into its own script and both score
     * perfectly. What separates them is that real text is made of common letters, and those live at
     * different byte values in each table. A wrong table hits its own common set only by chance.
     */
    private val common: String,
) {
    Windows1256(
        label = "windows-1256",
        script = Script.Arabic,
        languages = setOf("ar", "fa", "ur"),
        high = "\u20AC\u067E\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0679\u2039\u0152\u0686\u0698\u0688\u06AF\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u06A9\u2122\u0691\u203A\u0153\u200C\u200D\u06BA\u00A0\u060C\u00A2\u00A3\u00A4\u00A5\u00A6\u00A7\u00A8\u00A9\u06BE\u00AB\u00AC\u00AD\u00AE\u00AF\u00B0\u00B1\u00B2\u00B3\u00B4\u00B5\u00B6\u00B7\u00B8\u00B9\u061B\u00BB\u00BC\u00BD\u00BE\u061F\u06C1\u0621\u0622\u0623\u0624\u0625\u0626\u0627\u0628\u0629\u062A\u062B\u062C\u062D\u062E\u062F\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u00D7\u0637\u0638\u0639\u063A\u0640\u0641\u0642\u0643\u00E0\u0644\u00E2\u0645\u0646\u0647\u0648\u00E7\u00E8\u00E9\u00EA\u00EB\u0649\u064A\u00EE\u00EF\u064B\u064C\u064D\u064E\u00F4\u064F\u0650\u00F7\u0651\u00F9\u0652\u00FB\u00FC\u200E\u200F\u06D2",
        common = "\u00C7\u00C8\u00CA\u00D1\u00E1\u00E3\u00E4\u00E5\u00E6\u00ED",
    ),
    Windows1251(
        label = "windows-1251",
        script = Script.Cyrillic,
        languages = setOf("ru", "uk", "bg", "sr", "mk", "be"),
        high = "\u0402\u0403\u201A\u0453\u201E\u2026\u2020\u2021\u20AC\u2030\u0409\u2039\u040A\u040C\u040B\u040F\u0452\u2018\u2019\u201C\u201D\u2022\u2013\u2014\uFFFD\u2122\u0459\u203A\u045A\u045C\u045B\u045F\u00A0\u040E\u045E\u0408\u00A4\u0490\u00A6\u00A7\u0401\u00A9\u0404\u00AB\u00AC\u00AD\u00AE\u0407\u00B0\u00B1\u0406\u0456\u0491\u00B5\u00B6\u00B7\u0451\u2116\u0454\u00BB\u0458\u0405\u0455\u0457\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417\u0418\u0419\u041A\u041B\u041C\u041D\u041E\u041F\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042A\u042B\u042C\u042D\u042E\u042F\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u0439\u043A\u043B\u043C\u043D\u043E\u043F\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044A\u044B\u044C\u044D\u044E\u044F",
        common = "\u00E0\u00E2\u00E5\u00E8\u00EB\u00ED\u00EE\u00F0\u00F1\u00F2",
    ),
    Windows1252(
        label = "windows-1252",
        script = Script.Latin,
        languages = setOf("en", "fr", "de", "es", "it", "pt", "nl", "sv", "da", "no", "fi"),
        high = "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\uFFFD\u017D\uFFFD\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\uFFFD\u017E\u0178\u00A0\u00A1\u00A2\u00A3\u00A4\u00A5\u00A6\u00A7\u00A8\u00A9\u00AA\u00AB\u00AC\u00AD\u00AE\u00AF\u00B0\u00B1\u00B2\u00B3\u00B4\u00B5\u00B6\u00B7\u00B8\u00B9\u00BA\u00BB\u00BC\u00BD\u00BE\u00BF\u00C0\u00C1\u00C2\u00C3\u00C4\u00C5\u00C6\u00C7\u00C8\u00C9\u00CA\u00CB\u00CC\u00CD\u00CE\u00CF\u00D0\u00D1\u00D2\u00D3\u00D4\u00D5\u00D6\u00D7\u00D8\u00D9\u00DA\u00DB\u00DC\u00DD\u00DE\u00DF\u00E0\u00E1\u00E2\u00E3\u00E4\u00E5\u00E6\u00E7\u00E8\u00E9\u00EA\u00EB\u00EC\u00ED\u00EE\u00EF\u00F0\u00F1\u00F2\u00F3\u00F4\u00F5\u00F6\u00F7\u00F8\u00F9\u00FA\u00FB\u00FC\u00FD\u00FE\u00FF",
        common = "\u00E0\u00E4\u00E7\u00E8\u00E9\u00ED\u00F1\u00F3\u00F6\u00FC",
    ),
    Windows1253(
        label = "windows-1253",
        script = Script.Greek,
        languages = setOf("el"),
        high = "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\uFFFD\u2030\uFFFD\u2039\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\uFFFD\u2122\uFFFD\u203A\uFFFD\uFFFD\uFFFD\uFFFD\u00A0\u0385\u0386\u00A3\u00A4\u00A5\u00A6\u00A7\u00A8\u00A9\uFFFD\u00AB\u00AC\u00AD\u00AE\u2015\u00B0\u00B1\u00B2\u00B3\u0384\u00B5\u00B6\u00B7\u0388\u0389\u038A\u00BB\u038C\u00BD\u038E\u038F\u0390\u0391\u0392\u0393\u0394\u0395\u0396\u0397\u0398\u0399\u039A\u039B\u039C\u039D\u039E\u039F\u03A0\u03A1\uFFFD\u03A3\u03A4\u03A5\u03A6\u03A7\u03A8\u03A9\u03AA\u03AB\u03AC\u03AD\u03AE\u03AF\u03B0\u03B1\u03B2\u03B3\u03B4\u03B5\u03B6\u03B7\u03B8\u03B9\u03BA\u03BB\u03BC\u03BD\u03BE\u03BF\u03C0\u03C1\u03C2\u03C3\u03C4\u03C5\u03C6\u03C7\u03C8\u03C9\u03CA\u03CB\u03CC\u03CD\u03CE\uFFFD",
        common = "\u00E1\u00E5\u00E9\u00EA\u00ED\u00EF\u00F1\u00F3\u00F4\u00F5",
    ),
    Windows1254(
        label = "windows-1254",
        script = Script.Latin,
        languages = setOf("tr"),
        high = "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\uFFFD\uFFFD\uFFFD\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\uFFFD\uFFFD\u0178\u00A0\u00A1\u00A2\u00A3\u00A4\u00A5\u00A6\u00A7\u00A8\u00A9\u00AA\u00AB\u00AC\u00AD\u00AE\u00AF\u00B0\u00B1\u00B2\u00B3\u00B4\u00B5\u00B6\u00B7\u00B8\u00B9\u00BA\u00BB\u00BC\u00BD\u00BE\u00BF\u00C0\u00C1\u00C2\u00C3\u00C4\u00C5\u00C6\u00C7\u00C8\u00C9\u00CA\u00CB\u00CC\u00CD\u00CE\u00CF\u011E\u00D1\u00D2\u00D3\u00D4\u00D5\u00D6\u00D7\u00D8\u00D9\u00DA\u00DB\u00DC\u0130\u015E\u00DF\u00E0\u00E1\u00E2\u00E3\u00E4\u00E5\u00E6\u00E7\u00E8\u00E9\u00EA\u00EB\u00EC\u00ED\u00EE\u00EF\u011F\u00F1\u00F2\u00F3\u00F4\u00F5\u00F6\u00F7\u00F8\u00F9\u00FA\u00FB\u00FC\u0131\u015F\u00FF",
        common = "\u00E2\u00E7\u00E9\u00EE\u00F0\u00F6\u00FC\u00FD\u00FE",
    ),
    Windows1255(
        label = "windows-1255",
        script = Script.Hebrew,
        languages = setOf("he", "iw"),
        high = "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\uFFFD\u2039\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\uFFFD\u203A\uFFFD\uFFFD\uFFFD\uFFFD\u00A0\u00A1\u00A2\u00A3\u20AA\u00A5\u00A6\u00A7\u00A8\u00A9\u00D7\u00AB\u00AC\u00AD\u00AE\u00AF\u00B0\u00B1\u00B2\u00B3\u00B4\u00B5\u00B6\u00B7\u00B8\u00B9\u00F7\u00BB\u00BC\u00BD\u00BE\u00BF\u05B0\u05B1\u05B2\u05B3\u05B4\u05B5\u05B6\u05B7\u05B8\u05B9\uFFFD\u05BB\u05BC\u05BD\u05BE\u05BF\u05C0\u05C1\u05C2\u05C3\u05F0\u05F1\u05F2\u05F3\u05F4\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\u05D0\u05D1\u05D2\u05D3\u05D4\u05D5\u05D6\u05D7\u05D8\u05D9\u05DA\u05DB\u05DC\u05DD\u05DE\u05DF\u05E0\u05E1\u05E2\u05E3\u05E4\u05E5\u05E6\u05E7\u05E8\u05E9\u05EA\uFFFD\uFFFD\u200E\u200F\uFFFD",
        common = "\u00E0\u00E1\u00E4\u00E5\u00E9\u00EC\u00EE\u00F8\u00F9\u00FA",
    ),
    Windows1250(
        label = "windows-1250",
        script = Script.Latin,
        languages = setOf("pl", "cs", "sk", "hu", "ro", "hr", "sl"),
        high = "\u20AC\uFFFD\u201A\uFFFD\u201E\u2026\u2020\u2021\uFFFD\u2030\u0160\u2039\u015A\u0164\u017D\u0179\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\uFFFD\u2122\u0161\u203A\u015B\u0165\u017E\u017A\u00A0\u02C7\u02D8\u0141\u00A4\u0104\u00A6\u00A7\u00A8\u00A9\u015E\u00AB\u00AC\u00AD\u00AE\u017B\u00B0\u00B1\u02DB\u0142\u00B4\u00B5\u00B6\u00B7\u00B8\u0105\u015F\u00BB\u013D\u02DD\u013E\u017C\u0154\u00C1\u00C2\u0102\u00C4\u0139\u0106\u00C7\u010C\u00C9\u0118\u00CB\u011A\u00CD\u00CE\u010E\u0110\u0143\u0147\u00D3\u00D4\u0150\u00D6\u00D7\u0158\u016E\u00DA\u0170\u00DC\u00DD\u0162\u00DF\u0155\u00E1\u00E2\u0103\u00E4\u013A\u0107\u00E7\u010D\u00E9\u0119\u00EB\u011B\u00ED\u00EE\u010F\u0111\u0144\u0148\u00F3\u00F4\u0151\u00F6\u00F7\u0159\u016F\u00FA\u0171\u00FC\u00FD\u0163\u02D9",
        common = "\u009A\u009E\u00E1\u00E8\u00E9\u00ED\u00F3\u00F8\u00FA\u00FD",
    ),
    Iso88592(
        label = "ISO-8859-2",
        script = Script.Latin,
        languages = setOf("pl", "cs", "sk", "hu", "ro", "hr", "sl"),
        high = "\u0080\u0081\u0082\u0083\u0084\u0085\u0086\u0087\u0088\u0089\u008A\u008B\u008C\u008D\u008E\u008F\u0090\u0091\u0092\u0093\u0094\u0095\u0096\u0097\u0098\u0099\u009A\u009B\u009C\u009D\u009E\u009F\u00A0\u0104\u02D8\u0141\u00A4\u013D\u015A\u00A7\u00A8\u0160\u015E\u0164\u0179\u00AD\u017D\u017B\u00B0\u0105\u02DB\u0142\u00B4\u013E\u015B\u02C7\u00B8\u0161\u015F\u0165\u017A\u02DD\u017E\u017C\u0154\u00C1\u00C2\u0102\u00C4\u0139\u0106\u00C7\u010C\u00C9\u0118\u00CB\u011A\u00CD\u00CE\u010E\u0110\u0143\u0147\u00D3\u00D4\u0150\u00D6\u00D7\u0158\u016E\u00DA\u0170\u00DC\u00DD\u0162\u00DF\u0155\u00E1\u00E2\u0103\u00E4\u013A\u0107\u00E7\u010D\u00E9\u0119\u00EB\u011B\u00ED\u00EE\u010F\u0111\u0144\u0148\u00F3\u00F4\u0151\u00F6\u00F7\u0159\u016F\u00FA\u0171\u00FC\u00FD\u0163\u02D9",
        common = "\u00B9\u00BE\u00E1\u00E8\u00E9\u00ED\u00F3\u00F8\u00FA\u00FD",
    ),
    Koi8R(
        label = "KOI8-R",
        script = Script.Cyrillic,
        languages = setOf("ru"),
        high = "\u2500\u2502\u250C\u2510\u2514\u2518\u251C\u2524\u252C\u2534\u253C\u2580\u2584\u2588\u258C\u2590\u2591\u2592\u2593\u2320\u25A0\u2219\u221A\u2248\u2264\u2265\u00A0\u2321\u00B0\u00B2\u00B7\u00F7\u2550\u2551\u2552\u0451\u2553\u2554\u2555\u2556\u2557\u2558\u2559\u255A\u255B\u255C\u255D\u255E\u255F\u2560\u2561\u0401\u2562\u2563\u2564\u2565\u2566\u2567\u2568\u2569\u256A\u256B\u256C\u00A9\u044E\u0430\u0431\u0446\u0434\u0435\u0444\u0433\u0445\u0438\u0439\u043A\u043B\u043C\u043D\u043E\u043F\u044F\u0440\u0441\u0442\u0443\u0436\u0432\u044C\u044B\u0437\u0448\u044D\u0449\u0447\u044A\u042E\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0419\u041A\u041B\u041C\u041D\u041E\u041F\u042F\u0420\u0421\u0422\u0423\u0416\u0412\u042C\u042B\u0417\u0428\u042D\u0429\u0427\u042A",
        common = "\u00C1\u00C5\u00C9\u00CC\u00CE\u00CF\u00D2\u00D3\u00D4\u00D7",
    ),
    Iso88599(
        label = "ISO-8859-9",
        script = Script.Latin,
        languages = setOf("tr"),
        high = "\u0080\u0081\u0082\u0083\u0084\u0085\u0086\u0087\u0088\u0089\u008A\u008B\u008C\u008D\u008E\u008F\u0090\u0091\u0092\u0093\u0094\u0095\u0096\u0097\u0098\u0099\u009A\u009B\u009C\u009D\u009E\u009F\u00A0\u00A1\u00A2\u00A3\u00A4\u00A5\u00A6\u00A7\u00A8\u00A9\u00AA\u00AB\u00AC\u00AD\u00AE\u00AF\u00B0\u00B1\u00B2\u00B3\u00B4\u00B5\u00B6\u00B7\u00B8\u00B9\u00BA\u00BB\u00BC\u00BD\u00BE\u00BF\u00C0\u00C1\u00C2\u00C3\u00C4\u00C5\u00C6\u00C7\u00C8\u00C9\u00CA\u00CB\u00CC\u00CD\u00CE\u00CF\u011E\u00D1\u00D2\u00D3\u00D4\u00D5\u00D6\u00D7\u00D8\u00D9\u00DA\u00DB\u00DC\u0130\u015E\u00DF\u00E0\u00E1\u00E2\u00E3\u00E4\u00E5\u00E6\u00E7\u00E8\u00E9\u00EA\u00EB\u00EC\u00ED\u00EE\u00EF\u011F\u00F1\u00F2\u00F3\u00F4\u00F5\u00F6\u00F7\u00F8\u00F9\u00FA\u00FB\u00FC\u0131\u015F\u00FF",
        common = "\u00E2\u00E7\u00E9\u00EE\u00F0\u00F6\u00FC\u00FD\u00FE",
    ),
    ;

    /**
     * True when [byte] is a hole: a value this charset does not define, which disqualifies it.
     *
     * Safe for any byte. ASCII is defined by every charset here, and answering that directly beats
     * making each caller remember to range-check before asking.
     */
    fun isUndefined(byte: Int): Boolean = byte >= 0x80 && high[byte - 0x80] == HOLE

    /** What one high byte decodes to, for scoring without building the whole string. */
    fun decodedChar(byte: Int): Char = high[byte - 0x80]

    /** True when [byte] is one of this charset's most common letters. See [common]. */
    fun isCommon(byte: Int): Boolean = byte >= 0x80 && common.indexOf(byte.toChar()) >= 0

    fun decode(bytes: ByteArray): String = buildString(bytes.size) {
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            append(if (b < 0x80) b.toChar() else high[b - 0x80])
        }
    }

    /** True when [byte] decodes into this charset's own script, which is what scoring counts. */
    fun isInScript(byte: Int): Boolean {
        if (byte < 0x80) return false
        val ch = high[byte - 0x80]
        if (ch == HOLE) return false
        return when (script) {
            Script.Arabic -> ch in '\u0600'..'\u06FF'
            Script.Cyrillic -> ch in '\u0400'..'\u04FF'
            Script.Greek -> ch in '\u0370'..'\u03FF'
            Script.Hebrew -> ch in '\u0590'..'\u05FF'
            // Letters rather than the punctuation and symbols every Latin table also carries:
            // counting a curly quote as evidence of Polish would make every charset score alike.
            Script.Latin -> ch in '\u00C0'..'\u024F'
        }
    }

    internal companion object {
        const val HOLE: Char = '\uFFFD'
    }
}
