package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SubtitleCharset
import io.github.yuroyami.kiteplayer.internal.SubtitleEncodings
import io.github.yuroyami.kiteplayer.internal.decodeSubtitleBytes
import io.github.yuroyami.kiteplayer.internal.decodeSubtitleBytesAs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Subtitle files are not all UTF-8, and the ones that are not used to render as replacement
 * characters with nothing said.
 *
 * The fixtures are real bytes: real sentences encoded with the real codec, so a table with a wrong
 * row fails here rather than in someone's Arabic subtitles. They are byte literals rather than
 * files because this suite runs on twenty-one targets and most have no filesystem to put one on.
 *
 * They are also several lines long on purpose. A nine-byte greeting cannot be told apart from
 * another script's nine bytes by any honest method, and an earlier draft of this file used one and
 * was measuring the fixture rather than the detector.
 */
class SubtitleCharsetTest {

    @Test
    fun `arabic subtitles encoded as windows-1256 decode to their real words`() {
        val bytes = byteArrayOf(-29, -47, -51, -56, -57, 32, -56, -57, -31, -38, -57, -31, -29, 32, -33, -19, -35, 32, -51, -57, -31, -33, 32, -57, -31, -19, -26, -29, 10, -28, -51, -28, 32, -28, -54, -38, -31, -29, 32, -57, -31, -31, -37, -55, 32, -57, -31, -38, -47, -56, -19, -55, 32, -29, -38, -57, 10, -27, -48, -57, 32, -57, -31, -35, -19, -31, -29, 32, -52, -29, -19, -31, 32, -52, -49, -57, 32, -26, -47, -57, -58, -38)
        val decoded = decodeSubtitleBytes(bytes)
        assertEquals("مرحبا بالعالم كيف حالك اليوم\nنحن نتعلم اللغة العربية معا\nهذا الفيلم جميل جدا ورائع", decoded.text)
        assertEquals("windows-1256", decoded.charset)
        assertTrue(decoded.confident, "a clear winner is not a guess")
    }

    @Test
    fun `cyrillic subtitles encoded as windows-1251 decode to their real words`() {
        val bytes = byteArrayOf(-49, -16, -24, -30, -27, -14, 32, -20, -24, -16, 32, -22, -32, -22, 32, -14, -30, -18, -24, 32, -28, -27, -21, -32, 32, -15, -27, -29, -18, -28, -19, -1, 10, -52, -5, 32, -30, -20, -27, -15, -14, -27, 32, -15, -20, -18, -14, -16, -24, -20, 32, -3, -14, -18, -14, 32, -12, -24, -21, -4, -20, 10, -50, -19, 32, -18, -9, -27, -19, -4, 32, -24, -19, -14, -27, -16, -27, -15, -19, -5, -23, 32, -24, 32, -22, -16, -32, -15, -24, -30, -5, -23)
        val decoded = decodeSubtitleBytes(bytes)
        assertEquals("Привет мир как твои дела сегодня\nМы вместе смотрим этот фильм\nОн очень интересный и красивый", decoded.text)
        assertEquals("windows-1251", decoded.charset)
        assertTrue(decoded.confident, "a clear winner is not a guess")
    }

    @Test
    fun `greek subtitles encoded as windows-1253 decode to their real words`() {
        val bytes = byteArrayOf(-61, -27, -23, -31, 32, -13, -17, -11, 32, -22, -4, -13, -20, -27, 32, -12, -23, 32, -22, -36, -19, -27, -23, -14, 32, -13, -34, -20, -27, -15, -31, 10, -62, -21, -35, -16, -17, -11, -20, -27, 32, -20, -31, -26, -33, 32, -31, -11, -12, -34, 32, -12, -25, -19, 32, -12, -31, -23, -19, -33, -31, 10, -59, -33, -19, -31, -23, 32, -16, -17, -21, -3, 32, -7, -15, -31, -33, -31, 32, -22, -31, -23, 32, -27, -19, -28, -23, -31, -10, -35, -15, -17, -11, -13, -31)
        val decoded = decodeSubtitleBytes(bytes)
        assertEquals("Γεια σου κόσμε τι κάνεις σήμερα\nΒλέπουμε μαζί αυτή την ταινία\nΕίναι πολύ ωραία και ενδιαφέρουσα", decoded.text)
        assertEquals("windows-1253", decoded.charset)
        assertTrue(decoded.confident, "a clear winner is not a guess")
    }

    @Test
    fun `hebrew subtitles encoded as windows-1255 decode to their real words`() {
        val bytes = byteArrayOf(-7, -20, -27, -19, 32, -14, -27, -20, -19, 32, -18, -28, 32, -7, -20, -27, -18, -22, 32, -28, -23, -27, -19, 10, -32, -16, -25, -16, -27, 32, -10, -27, -12, -23, -19, 32, -23, -25, -29, 32, -31, -15, -8, -24, 32, -28, -26, -28, 10, -28, -27, -32, 32, -18, -32, -27, -29, 32, -23, -12, -28, 32, -27, -18, -14, -16, -23, -23, -17)
        val decoded = decodeSubtitleBytes(bytes)
        assertEquals("שלום עולם מה שלומך היום\nאנחנו צופים יחד בסרט הזה\nהוא מאוד יפה ומעניין", decoded.text)
        assertEquals("windows-1255", decoded.charset)
        assertTrue(decoded.confident, "a clear winner is not a guess")
    }

    @Test
    fun `the same Russian words in KOI8-R are not mistaken for windows-1251`() {
        // The hard pair. Both are Cyrillic and both read these bytes as real Cyrillic letters, so
        // script says nothing; they put the alphabet at different byte values, which is what the
        // commonest-letters test actually measures.
        val bytes = byteArrayOf(-16, -46, -55, -41, -59, -44, 32, -51, -55, -46, 32, -53, -63, -53, 32, -44, -41, -49, -55, 32, -60, -59, -52, -63, 32, -45, -59, -57, -49, -60, -50, -47, 10, -19, -39, 32, -41, -51, -59, -45, -44, -59, 32, -45, -51, -49, -44, -46, -55, -51, 32, -36, -44, -49, -44, 32, -58, -55, -52, -40, -51, 10, -17, -50, 32, -49, -34, -59, -50, -40, 32, -55, -50, -44, -59, -46, -59, -45, -50, -39, -54, 32, -55, 32, -53, -46, -63, -45, -55, -41, -39, -54)
        val decoded = decodeSubtitleBytes(bytes)
        assertEquals("KOI8-R", decoded.charset)
        assertTrue(decoded.confident)
    }

    @Test
    fun `a dense unspaced line is not mistaken for an East Asian encoding`() {
        // Dense Cyrillic has exactly the byte-pair shape EUC has, and on a short line the pair
        // count clears every structural threshold. So the multi-byte question must be asked AFTER
        // the single-byte tables have had their say, not before, or a one-line Russian subtitle is
        // reported as Korean and read with the fallback table.
        val bytes = byteArrayOf(-57, -28, -16, -32, -30, -15, -14, -30, -13, -23, -14, -27, -28, -18, -16, -18, -29, -24, -27, -25, -16, -24, -14, -27, -21, -24, -15, -27, -29, -18, -28, -19, -1, -20, -5, -17, -18, -15, -20, -18, -14, -16, -24, -20, -12, -24, -21, -4, -20)
        val decoded = decodeSubtitleBytes(bytes)
        assertEquals("Здравствуйтедорогиезрителисегоднямыпосмотримфильм", decoded.text)
        assertEquals("windows-1251", decoded.charset)
        assertNull(decoded.unsupportedGuess, "nothing here is East Asian")
    }

    @Test
    fun `a byte-order mark is a declaration and settles it`() {
        val utf8 = byteArrayOf(-17, -69, -65) + "héllo".encodeToByteArray()
        assertEquals("héllo", decodeSubtitleBytes(utf8).text)
        assertEquals("UTF-8", decodeSubtitleBytes(utf8).charset)

        // UTF-16 could not be read at all before: the old BOM strip ran on an already-decoded
        // string, so a file saved as "Unicode" from Notepad was garbage in the same silent way.
        val utf16le = byteArrayOf(-1, -2, 0x68, 0, 0x69, 0)
        val decoded = decodeSubtitleBytes(utf16le)
        assertEquals("hi", decoded.text)
        assertEquals("UTF-16LE", decoded.charset)
        assertTrue(decoded.confident)
    }

    @Test
    fun `valid UTF-8 without a mark is taken at its word`() {
        val bytes = "Привет мир".encodeToByteArray()
        val decoded = decodeSubtitleBytes(bytes)
        assertEquals("Привет мир", decoded.text)
        assertEquals("UTF-8", decoded.charset)
        assertTrue(decoded.confident, "text that validates as UTF-8 is not a guess")
    }

    @Test
    fun `an encoding this build cannot decode is NAMED rather than called undetectable`() {
        val bytes = byteArrayOf(-126, -79, -126, -15, -126, -55, -126, -65, -126, -51, -112, -94, -118, 69, -126, -59, -126, -73)
        val decoded = decodeSubtitleBytes(bytes)
        assertFalse(decoded.confident)
        assertEquals("Shift_JIS", decoded.unsupportedGuess)
        // The track still loads with a fallback reading, because imperfect subtitles beat none.
        assertTrue(decoded.text.isNotEmpty())
    }

    @Test
    fun `an undecidable file falls back and says so instead of throwing`() {
        // High bytes that no charset's common letters claim, so nothing clears the bar.
        val bytes = byteArrayOf(-128, -127, -126, 0x20, -125, -124)
        val decoded = decodeSubtitleBytes(bytes)
        assertFalse(decoded.confident, "a fallback must not claim confidence")
        assertEquals("windows-1252", decoded.charset)
        assertNull(decoded.unsupportedGuess)
    }

    @Test
    fun `strict UTF-8 validation rejects what a lenient one would accept`() {
        // An overlong encoding of '/' is the classic path-traversal trick; accepting it here would
        // also mean calling a legacy file UTF-8 whenever it happened to contain one.
        val overlong = byteArrayOf(-64, -81)
        assertTrue(decodeSubtitleBytes(overlong).charset != "UTF-8")
        // A lone continuation byte and a truncated three-byte sequence are equally not UTF-8.
        assertTrue(decodeSubtitleBytes(byteArrayOf(-128)).charset != "UTF-8")
        assertTrue(decodeSubtitleBytes(byteArrayOf(-30, -126)).charset != "UTF-8")
    }

    @Test
    fun `pure ASCII is settled without guessing at anything`() {
        val decoded = decodeSubtitleBytes("1\n00:00:01,000 --> 00:00:02,000\nHello\n".encodeToByteArray())
        assertEquals("UTF-8", decoded.charset)
        assertTrue(decoded.confident)
    }

    @Test
    fun `a language hint breaks a tie and cannot overrule the bytes`() {
        // Russian text in windows-1251. The hint agrees, and the answer is the same without it:
        // a hint that could change a decided answer would be a hint deciding, not helping.
        val bytes = byteArrayOf(-49, -16, -24, -30, -27, -14, 32, -20, -24, -16, 32, -22, -32, -22, 32, -14, -30, -18, -24, 32, -28, -27, -21, -32, 32, -15, -27, -29, -18, -28, -19, -1, 10, -52, -5, 32, -30, -20, -27, -15, -14, -27, 32, -15, -20, -18, -14, -16, -24, -20, 32, -3, -14, -18, -14, 32, -12, -24, -21, -4, -20, 10, -50, -19, 32, -18, -9, -27, -19, -4, 32, -24, -19, -14, -27, -16, -27, -15, -19, -5, -23, 32, -24, 32, -22, -16, -32, -15, -24, -30, -5, -23)
        assertEquals("windows-1251", decodeSubtitleBytes(bytes, languageHint = "ru").charset)
        assertEquals("windows-1251", decodeSubtitleBytes(bytes, languageHint = "ar").charset)
    }


    @Test
    fun eachEastAsianSubRipFileIsNamedForItsOwnEncoding() {
        // Without tables the file still falls back, and the name is what the warning carries.
        for ((encoding, bytes) in EAST_ASIAN_FILES) {
            val decoded = decodeSubtitleBytes(bytes)
            assertEquals(encoding, decoded.unsupportedGuess, "the $encoding file was named wrongly")
            assertEquals("windows-1252", decoded.charset)
            assertFalse(decoded.confident)
        }
    }

    @Test
    fun koreanThatAlsoWritesHanjaIsStillNamedEucKr() {
        // One pair in eighteen is a Hanja, on the rows above the Hangul, which on its own is what
        // Chinese in GBK looks like. The spaces between words are what Korean has and Chinese has not.
        val bytes = subRip(
            "b4ebc7d1b9ceb1b920c1a4baceb4c220bfc0b4c320bbf5b7cebfee20c1a4c3a5c0bb20b9dfc7a5c7dfbdc0b4cfb4d92e",
            "bcadbfeff7e5dcace3bcc0c720c0ceb1b8b4c220bee020c3b5b8b820b8edc0d4b4cfb4d92e",
            "b3bbc0cfc0ba20baf1b0a120bfc320b0cd20b0b0c0b8b4cf20bfecbbeac0bb20c3acb1e2bcbcbfe42e",
        )
        assertEquals("EUC-KR", decodeSubtitleBytes(bytes).unsupportedGuess)
    }

    @Test
    fun theDetectorsNameIsTheFirstTableAskedAndItsReadingIsKept() {
        // What a backend's parser does with the tables: read the bytes as the name it is given.
        for ((encoding, bytes) in EAST_ASIAN_FILES) {
            val asked = mutableListOf<String>()
            val decoded = decodeSubtitleBytes(bytes) { _, name ->
                asked += name
                if (name == encoding) "read as $name" else null
            }
            assertEquals(listOf(encoding), asked, "the $encoding file asked the wrong table first")
            assertEquals("read as $encoding", decoded.text)
            assertEquals(encoding, decoded.charset)
            assertTrue(decoded.confident, "the likeliest table reading cleanly is not a guess")
            assertNull(decoded.unsupportedGuess)
        }
    }

    @Test
    fun aTableThatCannotReadTheBytesHandsOverToTheNextAndTheResultIsAGuess() {
        // Korean is likeliest here. A table that leaves one character in three unread is wrong,
        // so the next name gets its turn, and a reading that needed a second try must say so.
        val asked = mutableListOf<String>()
        val decoded = decodeSubtitleBytes(EAST_ASIAN_FILES.getValue("EUC-KR")) { _, name ->
            asked += name
            when (name) {
                "EUC-KR" -> "\uD55C\uFFFD\uAD6D"
                "GBK" -> "\u97E9\u56FD\u8BED"
                else -> null
            }
        }
        assertEquals(listOf("EUC-KR", "GBK"), asked)
        assertEquals("GBK", decoded.charset)
        assertEquals("\u97E9\u56FD\u8BED", decoded.text)
        assertFalse(decoded.confident)
    }

    @Test
    fun whenNoTableReadsTheBytesTheFallbackStillNamesTheEncoding() {
        val bytes = EAST_ASIAN_FILES.getValue("Shift_JIS")
        val asked = mutableListOf<String>()
        val decoded = decodeSubtitleBytes(bytes) { _, name ->
            asked += name
            "\uFFFD\uFFFD"
        }
        assertEquals(listOf("Shift_JIS", "GBK", "Big5", "EUC-KR", "EUC-JP"), asked, "every table gets its turn")
        assertEquals("windows-1252", decoded.charset)
        assertEquals("Shift_JIS", decoded.unsupportedGuess)
        assertFalse(decoded.confident)
    }

    @Test
    fun aParserThatThrowsCountsAsOneWithoutTheTable() {
        // A subtitle never fails an open, so a broken backend parser costs the reading and no more.
        val decoded = decodeSubtitleBytes(EAST_ASIAN_FILES.getValue("Big5")) { _, name ->
            if (name == "Big5") error("no Big5 table after all") else null
        }
        assertEquals("windows-1252", decoded.charset)
        assertEquals("Big5", decoded.unsupportedGuess)
        assertFalse(decoded.confident)
    }

    @Test
    fun frenchTextWhoseAccentsPairWithLettersIsNotTakenForEastAsian() {
        // Each accented letter sits in front of an ASCII letter, which is a byte pair in form. The
        // old shape test called this file Shift_JIS, which was harmless while nothing decoded it
        // and would now read French as Japanese.
        val bytes = latin1(
            "1\n00:00:01,000 --> 00:00:02,000\n\u00C9lise a pr\u00E9f\u00E9r\u00E9 rester \u00E0 la maison ce soir.\n\n" +
                "2\n00:00:03,000 --> 00:00:04,000\nLe caf\u00E9 \u00E9tait d\u00E9j\u00E0 froid quand il est arriv\u00E9.\n\n" +
                "3\n00:00:05,000 --> 00:00:06,000\nNous avons visit\u00E9 le mus\u00E9e pr\u00E8s de la cath\u00E9drale.\n\n" +
                "4\n00:00:07,000 --> 00:00:08,000\nTu as oubli\u00E9 tes cl\u00E9s sur la table de la cuisine.\n\n" +
                "5\n00:00:09,000 --> 00:00:10,000\nCette id\u00E9e \u00E9tait vraiment g\u00E9niale, f\u00E9licitations !\n\n" +
                "6\n00:00:11,000 --> 00:00:12,000\nIl a r\u00E9p\u00E9t\u00E9 la m\u00EAme phrase trois fois de suite.\n",
        )
        val asked = mutableListOf<String>()
        val decoded = decodeSubtitleBytes(bytes) { _, name ->
            asked += name
            "not French"
        }
        assertTrue(asked.isEmpty(), "French text was offered to the East Asian tables as $asked")
        assertNull(decoded.unsupportedGuess)
        assertEquals("windows-1252", decoded.charset)
        assertContentEquals(bytes, latin1(decoded.text), "the windows-1252 reading of this file is the right one")
    }

    @Test
    fun westernTextIsNotReadAsAnotherScript() {
        // Each accent here is also a letter in the Hebrew, Greek or Cyrillic table, and several are
        // those tables' commonest letters, so each file used to read as that script, with certainty
        // and therefore with no warning (#516). An accent touches the Latin letters around it, and a
        // letter of another script never does.
        val files = mapOf(
            "Italian" to listOf(
                "Perché non mi hai detto niente?", "Lunedì andiamo al mare con la città.",
                "È già tardi, non posso più aspettare.", "Così non funziona, però possiamo provare.",
                "Il caffè è pronto, vuoi una tazza?",
            ),
            "Dutch" to listOf(
                "Ik heb geen idee waar hij naartoe is gegaan.", "Hij zei dat het café al gesloten was.",
                "Ze hebben een ruïne gevonden bij de rivier.", "Dat is een heel goed idee, bedankt.",
            ),
            "Danish" to listOf(
                "Jeg ved ikke, hvor han er gået hen.", "Vi skal købe brød og smør i morgen.",
                "Hun går på skole i en lille by på øen.", "Det er for sent at ændre på det nu.",
            ),
            "Norwegian" to listOf(
                "Vi må kjøpe brød og smør før butikken stenger.", "Hun bor på en øy langt mot nord.",
                "Det er for sent å forandre på det nå.", "Kan du høre meg? Svar meg før det er for sent.",
            ),
            "Albanian" to listOf(
                "Unë nuk e di se ku ka shkuar ai.", "Ne duhet të blejmë bukë para se të mbyllet dyqani.",
                "Ajo jeton në një fshat të vogël pranë liqenit.", "A më dëgjon? Përgjigjju para se të jetë vonë.",
            ),
        )
        for ((language, lines) in files) {
            val text = lines.mapIndexed { i, line -> "${i + 1}\n00:00:0${i + 1},000 --> 00:00:0${i + 1},900\n$line\n\n" }.joinToString("")
            val decoded = decodeSubtitleBytes(latin1(text))
            assertEquals("windows-1252", decoded.charset, "the $language file")
            assertEquals(text, decoded.text, "the $language file")
        }
        // Two short lines of Vietnamese in windows-1258, whose tone marks follow their vowels.
        val vietnamese = hex(
            "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a58696e206368e06f2c206261f26e206b686f" +
                "d265206b68f46e673f0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930300a4361d26d20f56e" +
                "206e6869eacc752e0a0a",
        )
        val decoded = decodeSubtitleBytes(vietnamese)
        assertFalse(decoded.charset in OTHER_SCRIPTS, "Vietnamese read as ${decoded.charset}")
    }

    @Test
    fun thaiAndVietnameseAreReadInTheirOwnTables() {
        // Neither had a table before #515, so both fell back to windows-1252 with a warning.
        for ((encoding, fixture) in listOf("windows-874" to THAI, "windows-1258" to VIETNAMESE)) {
            val decoded = decodeSubtitleBytes(fixture.bytes)
            assertEquals(encoding, decoded.charset)
            assertEquals(fixture.text, decoded.text, "the $encoding file")
            assertTrue(decoded.confident, "the $encoding file")
        }
    }

    @Test
    fun aBalticFileIsReadAsWindows1257OnlyWhenItsLanguageSaysSo() {
        // windows-1257 puts the Baltic letters on the bytes of Western accents, so guessing it from
        // the bytes alone would read Portuguese and Albanian as Lithuanian (#515). Without a Baltic
        // language the file keeps the answer it had, a windows-1252 reading with a warning.
        val unhinted = decodeSubtitleBytes(LITHUANIAN.bytes)
        assertEquals("windows-1252", unhinted.charset)
        assertFalse(unhinted.confident)
        for (hint in listOf("lt", "lit", "lt-LT")) {
            val decoded = decodeSubtitleBytes(LITHUANIAN.bytes, languageHint = hint)
            assertEquals("windows-1257", decoded.charset, hint)
            assertEquals(LITHUANIAN.text, decoded.text, hint)
            assertTrue(decoded.confident, hint)
        }
        val latvian = decodeSubtitleBytes(LATVIAN.bytes, languageHint = "lv")
        assertEquals("windows-1257", latvian.charset)
        assertEquals(LATVIAN.text, latvian.text)
    }

    @Test
    fun aNamedEncodingIsReadAsToldWithNoGuess() {
        // The guess reads this Polish file as windows-1252, because its letters are Western ones too.
        assertEquals("windows-1252", decodeSubtitleBytes(POLISH.bytes).charset)
        for (label in listOf("windows-1250", "cp1250", " WINDOWS-1250 ", "x-cp1250")) {
            val decoded = decodeSubtitleBytesAs(POLISH.bytes, label)
            assertEquals("windows-1250", decoded?.charset, label)
            assertEquals(POLISH.text, decoded?.text, label)
            assertTrue(decoded?.confident == true, label)
        }
        // Given an encoding it is not in, the file still reads, each byte as that table has it.
        val wrong = decodeSubtitleBytesAs(POLISH.bytes, "windows-1251")
        assertEquals("windows-1251", wrong?.charset)
        assertEquals(POLISH.bytes.size, wrong?.text?.length)
        assertNotEquals(POLISH.text, wrong?.text)
        assertTrue('\uFFFD' in decodeSubtitleBytesAs(POLISH.bytes, "utf8")?.text.orEmpty(), "UTF-8 shows what it cannot read")
        assertNull(decodeSubtitleBytesAs(POLISH.bytes, "klingon"))
    }

    @Test
    fun aNamedUnicodeEncodingSkipsOnlyItsOwnMark() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n\u017B\u00F3\u0142w\n"
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.encodeToByteArray()
        assertEquals(text, decodeSubtitleBytesAs(utf8, "UTF-8")?.text)
        assertEquals(text, decodeSubtitleBytesAs(text.encodeToByteArray(), "UTF-8")?.text)
        val utf16 = ByteArray(text.length * 2) { (text[it / 2].code shr (if (it % 2 == 0) 0 else 8)).toByte() }
        val marked = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16
        assertEquals(text, decodeSubtitleBytesAs(marked, "utf-16")?.text, "the standard's utf-16 is little-endian")
        assertEquals(text, decodeSubtitleBytesAs(utf16, "UTF-16LE")?.text)
        // Another encoding's mark is bytes like any other.
        assertEquals("\u00EF\u00BB\u00BF1", decodeSubtitleBytesAs(utf8, "windows-1252")?.text?.take(4))
    }

    @Test
    fun aNamedEastAsianEncodingReachesTheParserByItsStandardName() {
        val bytes = EAST_ASIAN_FILES.getValue("Shift_JIS")
        val asked = mutableListOf<String>()
        val decoded = decodeSubtitleBytesAs(bytes, "sjis") { _, name -> asked += name; "read" }
        assertEquals(listOf("Shift_JIS"), asked)
        assertEquals("Shift_JIS", decoded?.charset)
        assertEquals("read", decoded?.text)
        // With no table there is nothing to read it with, and the caller says so.
        assertNull(decodeSubtitleBytesAs(bytes, "Shift_JIS"))
        assertNull(decodeSubtitleBytesAs(bytes, "Shift_JIS") { _, _ -> null })
        assertNull(decodeSubtitleBytesAs(bytes, "Shift_JIS") { _, _ -> error("no table") })
    }

    @Test
    fun theFallbackIsUsedOnlyForAFileThatIsNotUnicode() {
        val polish = decodeSubtitleBytes(POLISH.bytes, fallback = "windows-1250")
        assertEquals("windows-1250", polish.charset)
        assertEquals(POLISH.text, polish.text)
        assertTrue(polish.confident, "a reading the application asked for is not a guess")
        // A UTF-8 file stays UTF-8, as it does in VLC and mpv with their fallback set.
        assertEquals("UTF-8", decodeSubtitleBytes(POLISH.text.encodeToByteArray(), fallback = "windows-1250").charset)
        // A fallback with no table to read it leaves the file to the guess.
        val guessed = decodeSubtitleBytes(POLISH.bytes, fallback = "GBK")
        assertEquals("windows-1252", guessed.charset)
        assertFalse(guessed.confident)
    }

    @Test
    fun everyEncodingTheGuessCanNameCanBeAskedForByThatName() {
        // A warning names what a file was read as and what it seems to be, and an application offers
        // those names back, so each must be one the list holds and a source accepts.
        val guessable = SubtitleCharset.entries.map { it.label } + listOf("UTF-8", "UTF-16LE", "UTF-16BE") + EAST_ASIAN_FILES.keys
        for (name in guessable) assertTrue(name in SubtitleSource.ENCODINGS, name)
        for (name in SubtitleSource.ENCODINGS) {
            assertEquals(name, SubtitleEncodings.canonical(name), name)
            assertEquals(name, SubtitleSource("file.srt", encoding = name).encoding)
        }
        assertEquals(SubtitleSource.ENCODINGS.size, SubtitleSource.ENCODINGS.distinct().size)
        assertFailsWith<IllegalArgumentException> { SubtitleSource("file.srt", encoding = "klingon") }
        assertFailsWith<IllegalArgumentException> { SubtitleConfig(fallbackEncoding = "klingon") }
    }

    private class Fixture(val bytes: ByteArray, val text: String)

    private companion object {
        /** The tables whose letters are not Latin ones. */
        val OTHER_SCRIPTS = setOf("windows-1251", "windows-1253", "windows-1255", "windows-1256", "KOI8-R")

        fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun latin1(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

        /**
         * Real dialogue in each table #515 added, and short Polish in windows-1250, which the guess
         * reads as windows-1252. Each text is what Python's codec of that name decodes the bytes
         * to, and windows-1258 keeps Vietnamese tones as combining marks after their vowels.
         */
        val POLISH = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a447a6965f120646f6272792c2070726f737a" +
                "ea2070616e612e0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930300a47647a6965206a6573" +
                "7420b3617a69656e6b613f0a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\nDzie\u0144 dobry, prosz\u0119 pana.\n\n2\n00:00:02,000 --> 00:00:02,900\nGdzie jest \u0142azienka?\n\n",
        )
        val THAI = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300aa9d1b9e4c1e8c3d9e9c7e8d2bed5e8aad2c2" +
                "a2cda7a9d1b9e4bbe4cbb90a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930300abec3d8e8a7" +
                "b9d5e9e0c3d2a8d0e4bbb7d0e0c5a1d1bae0b4e7a1e60a0a330a30303a30303a30332c303030202d2d3e2030303a30303a30" +
                "332c3930300ae0a2d2bacda1c7e8d2c3e9d2b9a1d2e1bfbbd4b4e1c5e9c70a0a340a30303a30303a30342c303030202d2d3e" +
                "2030303a30303a30342c3930300ab5cdb9b9d5e9cad2c2e0a1d4b9e4bbb7d5e8a8d0e0bbc5d5e8c2b9e1c5e9c70a0a350a30" +
                "303a30303a30352c303030202d2d3e2030303a30303a30352c3930300aa4d8b3e4b4e9c2d4b9a9d1b9e4cbc120b5cdbaa1e8" +
                "cdb9b7d5e8a8d0cad2c2e0a1d4b9e4bb0a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\n\u0E09\u0E31\u0E19\u0E44\u0E21\u0E48\u0E23\u0E39\u0E49\u0E27\u0E48\u0E32\u0E1E\u0E35\u0E48\u0E0A\u0E32\u0E22\u0E02\u0E2D\u0E07\u0E09\u0E31\u0E19\u0E44\u0E1B\u0E44\u0E2B\u0E19\n\n2\n00:00:02,000 --> 00:00:02,900\n\u0E1E\u0E23\u0E38\u0E48\u0E07\u0E19\u0E35\u0E49\u0E40\u0E23\u0E32\u0E08\u0E30\u0E44\u0E1B\u0E17\u0E30\u0E40\u0E25\u0E01\u0E31\u0E1A\u0E40\u0E14\u0E47\u0E01\u0E46\n\n3\n00:00:03,000 --> 00:00:03,900\n\u0E40\u0E02\u0E32\u0E1A\u0E2D\u0E01\u0E27\u0E48\u0E32\u0E23\u0E49\u0E32\u0E19\u0E01\u0E32\u0E41\u0E1F\u0E1B\u0E34\u0E14\u0E41\u0E25\u0E49\u0E27\n\n4\n00:00:04,000 --> 00:00:04,900\n\u0E15\u0E2D\u0E19\u0E19\u0E35\u0E49\u0E2A\u0E32\u0E22\u0E40\u0E01\u0E34\u0E19\u0E44\u0E1B\u0E17\u0E35\u0E48\u0E08\u0E30\u0E40\u0E1B\u0E25\u0E35\u0E48\u0E22\u0E19\u0E41\u0E25\u0E49\u0E27\n\n5\n00:00:05,000 --> 00:00:05,900\n\u0E04\u0E38\u0E13\u0E44\u0E14\u0E49\u0E22\u0E34\u0E19\u0E09\u0E31\u0E19\u0E44\u0E2B\u0E21 \u0E15\u0E2D\u0E1A\u0E01\u0E48\u0E2D\u0E19\u0E17\u0E35\u0E48\u0E08\u0E30\u0E2A\u0E32\u0E22\u0E40\u0E01\u0E34\u0E19\u0E44\u0E1B\n\n",
        )
        val VIETNAMESE = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a54f469206b68f46e67206269eaec7420616e" +
                "6820747261692074f46920f061de20f06920f0e2752e0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30" +
                "322c3930300a4e67e079206d6169206368fa6e67207461207365de20f069206269ead26e2076f5ec6920626ff26e20747265" +
                "d22e0a0a330a30303a30303a30332c303030202d2d3e2030303a30303a30332c3930300a416e6820e2ec79206ef3692072e3" +
                "cc6e67207175e16e2063e0207068ea20f061de20f0f36e672063fdd2612e0a0a340a30303a30303a30342c303030202d2d3e" +
                "2030303a30303a30342c3930300a42e279206769f5cc20f061de207175e1206d75f4f26e20f0ead2207468617920f0f4d269" +
                "20f069eacc7520f0f32e0a0a350a30303a30303a30352c303030202d2d3e2030303a30303a30352c3930300a4261f26e2063" +
                "f3206e6768652074f469206b68f46e673f20547261d2206cf5cc69207472fdf5ec63206b6869207175e1206d75f4f26e2e0a" +
                "0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\nT\u00F4i kh\u00F4ng bi\u00EA\u0301t anh trai t\u00F4i \u0111a\u0303 \u0111i \u0111\u00E2u.\n\n2\n00:00:02,000 --> 00:00:02,900\nNg\u00E0y mai ch\u00FAng ta se\u0303 \u0111i bi\u00EA\u0309n v\u01A1\u0301i bo\u0323n tre\u0309.\n\n3\n00:00:03,000 --> 00:00:03,900\nAnh \u00E2\u0301y n\u00F3i r\u0103\u0300ng qu\u00E1n c\u00E0 ph\u00EA \u0111a\u0303 \u0111\u00F3ng c\u01B0\u0309a.\n\n4\n00:00:04,000 --> 00:00:04,900\nB\u00E2y gi\u01A1\u0300 \u0111a\u0303 qu\u00E1 mu\u00F4\u0323n \u0111\u00EA\u0309 thay \u0111\u00F4\u0309i \u0111i\u00EA\u0300u \u0111\u00F3.\n\n5\n00:00:05,000 --> 00:00:05,900\nBa\u0323n c\u00F3 nghe t\u00F4i kh\u00F4ng? Tra\u0309 l\u01A1\u0300i tr\u01B0\u01A1\u0301c khi qu\u00E1 mu\u00F4\u0323n.\n\n",
        )
        val LITHUANIAN = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a4e65fe696e61752c206b75722069f0eb6a6f" +
                "206d616e6f2062726f6c69732e0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930300a527974" +
                "6f6a207375207661696b616973207661fe69756f73696d652070726965206afb726f732e0a0a330a30303a30303a30332c30" +
                "3030202d2d3e2030303a30303a30332c3930300a4a69732073616beb2c206b6164206b6176696eeb206a6175206275766f20" +
                "75fe6461727974612e0a0a340a30303a30303a30342c303030202d2d3e2030303a30303a30342c3930300a4461626172206a" +
                "6175207065722076eb6c7520746169206b65697374692e0a0a350a30303a30303a30352c303030202d2d3e2030303a30303a" +
                "30352c3930300a4172206769726469206d616e653f20417473616b796b2c206b6f6c20646172206e6576eb6c752e0a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\nNe\u017Einau, kur i\u0161\u0117jo mano brolis.\n\n2\n00:00:02,000 --> 00:00:02,900\nRytoj su vaikais va\u017Eiuosime prie j\u016Bros.\n\n3\n00:00:03,000 --> 00:00:03,900\nJis sak\u0117, kad kavin\u0117 jau buvo u\u017Edaryta.\n\n4\n00:00:04,000 --> 00:00:04,900\nDabar jau per v\u0117lu tai keisti.\n\n5\n00:00:05,000 --> 00:00:05,900\nAr girdi mane? Atsakyk, kol dar nev\u0117lu.\n\n",
        )
        val LATVIAN = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a4573206e657a696e752c206b757270206169" +
                "7a67e26a61206d616e73206272e26c69732e0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930" +
                "300a52ee74206de7732061722062e7726e69656d20627261756b73696d20757a206afb72752e0a0a330a30303a30303a3033" +
                "2c303030202d2d3e2030303a30303a30332c3930300a5669f2f02074656963612c206b61206b6166656a6eee6361206a6175" +
                "2062696a6120736ce76774612e0a0a340a30303a30303a30342c303030202d2d3e2030303a30303a30342c3930300a546167" +
                "6164206972207061722076e76c7520746f206d61696eee742e0a0a350a30303a30303a30352c303030202d2d3e2030303a30" +
                "303a30352c3930300a566169207475206d616e6920647a697264693f20417462696c64692c207069726d73206e6176207061" +
                "722076e76c752e0a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\nEs nezinu, kurp aizg\u0101ja mans br\u0101lis.\n\n2\n00:00:02,000 --> 00:00:02,900\nR\u012Bt m\u0113s ar b\u0113rniem brauksim uz j\u016Bru.\n\n3\n00:00:03,000 --> 00:00:03,900\nVi\u0146\u0161 teica, ka kafejn\u012Bca jau bija sl\u0113gta.\n\n4\n00:00:04,000 --> 00:00:04,900\nTagad ir par v\u0113lu to main\u012Bt.\n\n5\n00:00:05,000 --> 00:00:05,900\nVai tu mani dzirdi? Atbildi, pirms nav par v\u0113lu.\n\n",
        )

        /** A SubRip file of one cue per line, each line the hex of its text in the file's encoding. */
        fun subRip(vararg lines: String): ByteArray {
            var file = ByteArray(0)
            lines.forEachIndexed { index, line ->
                val n = index + 1
                file += "$n\n00:00:0$n,000 --> 00:00:0$n,900\n".encodeToByteArray() + hex(line) +
                    "\n\n".encodeToByteArray()
            }
            return file
        }

        /**
         * Three short lines of dialogue in each encoding, by the name the detector gives it. The
         * Japanese lines are the same in both Japanese encodings; the Chinese ones are simplified
         * in GBK and traditional in Big5.
         */
        val EAST_ASIAN_FILES: Map<String, ByteArray> = mapOf(
            "Shift_JIS" to subRip(
                "82a882cd82e682a482b282b482a282dc82b781428da193fa82cd82a282a293568b4382c582b782cb8142",
                "897782cc914f82c591d282c182c482a282e982a982e78141918182ad978882c482ad82be82b382a28142",
                "82a082e882aa82c682a4814282dc82bd8da1937882e482c182ad82e8986282bb82a482cb8142",
            ),
            "EUC-JP" to subRip(
                "a4aaa4cfa4e8a4a6a4b4a4b6a4a4a4dea4b9a1a3baa3c6fca4cfa4a4a4a4c5b7b5a4a4c7a4b9a4cda1a3",
                "b1d8a4cec1b0a4c7c2d4a4c3a4c6a4a4a4eba4aba4e9a1a2c1e1a4afcde8a4c6a4afa4c0a4b5a4a4a1a3",
                "a4a2a4eaa4aca4c8a4a6a1a3a4dea4bfbaa3c5d9a4e6a4c3a4afa4eacfc3a4bda4a6a4cda1a3",
            ),
            "GBK" to subRip(
                "d4e7c9cfbac3a3acbdf1ccecccecc6f8d5e6b2bbb4eda1a3",
                "ced2d4dab3b5d5bec7b0c3e6b5c8c4e3a3acc7ebbfecb5e3c0b4a1a3",
                "d0bbd0bbc4e3a3accfc2b4ceced2c3c7d4d9c2fdc2fdc1c4b0c9a1a3",
            ),
            "Big5" to subRip(
                "a6ada677a141a4b5a4d1a4d1aef0af75a4a3bff9a143",
                "a7daa662a8aeafb8ab65adb1b5a5a741a141bdd0a7d6c249a8d3a143",
                "c1c2c1c2a741a141a455a6b8a7daadcca641ba43ba43b2e1a761a143",
            ),
            "EUC-KR" to subRip(
                "c1c1c0ba20bec6c4a7c0ccbfa1bfe42e20bfc0b4c320b3afbebeb0a120c1a4b8bb20c1c1b3d7bfe42e",
                "bfaa20bed5bfa1bcad20b1e2b4d9b8aeb0ed20c0d6c0b8b4cfb1ee20bba1b8ae20bfcd20c1d6bcbcbfe42e",
                "b0edb8b6bff62e20b4d9c0bdbfa120c3b5c3b5c8f720c0ccbedfb1e2c7cfc0da2e",
            ),
        )
    }
}
