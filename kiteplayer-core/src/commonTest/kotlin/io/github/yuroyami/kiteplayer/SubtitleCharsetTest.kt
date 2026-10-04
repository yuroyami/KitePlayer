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
        // script says nothing; they put the alphabet at different byte values, and the wrong one
        // reads as rare letters and as capitals in the middle of words.
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
    fun anUndecidableFileIsShownUnsureInsteadOfThrowing() {
        // High bytes that read as no language's text in any table, and not as an East Asian file
        // either. The likeliest reading is still shown, and says it is a guess. It is not
        // windows-1252, which has no character for 0x81.
        val bytes = byteArrayOf(-128, -127, -126, 0x20, -125, -124)
        val decoded = decodeSubtitleBytes(bytes)
        assertFalse(decoded.confident, "a guess must not claim confidence")
        assertNotEquals("windows-1252", decoded.charset)
        assertFalse('\uFFFD' in decoded.text, decoded.text)
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
            assertEquals(encoding, asked.first(), "the $encoding file asked the wrong table first")
            assertEquals(EAST_ASIAN_FILES.keys, asked.toSet(), "every table reads the $encoding file")
            assertEquals("read as $encoding", decoded.text)
            assertEquals(encoding, decoded.charset)
            assertTrue(decoded.confident, "the likeliest table reading cleanly is not a guess")
            assertNull(decoded.unsupportedGuess)
        }
    }

    @Test
    fun aTableThatCannotReadTheBytesIsPassedOverAndTheResultIsAGuess() {
        // Korean is likeliest here. A table that leaves one character in three unread is wrong, so
        // the reading another table makes is kept, and since that table is not the one the bytes
        // looked most like, it says it guessed.
        val asked = mutableListOf<String>()
        val decoded = decodeSubtitleBytes(EAST_ASIAN_FILES.getValue("EUC-KR")) { _, name ->
            asked += name
            when (name) {
                "EUC-KR" -> "\uD55C\uFFFD\uAD6D"
                "GBK" -> "\u97E9\u56FD\u8BED"
                else -> null
            }
        }
        assertEquals(listOf("EUC-KR", "GBK", "Big5", "EUC-JP", "Shift_JIS"), asked)
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
    fun aBalticFileIsReadAsWindows1257WithOrWithoutItsLanguage() {
        // windows-1257 puts the Baltic letters on the bytes of Western accents, so #515 let only a
        // Baltic language choose it, for fear of reading Portuguese and Albanian as Lithuanian. The
        // letters' own frequencies tell them apart (#518): Lithuanian is mostly ė, š and ž and never
        // Danish ø, and Portuguese is mostly ã, ç and é.
        for (hint in listOf(null, "lt", "lit", "lt-LT")) {
            val decoded = decodeSubtitleBytes(LITHUANIAN.bytes, languageHint = hint)
            assertEquals("windows-1257", decoded.charset, hint)
            assertEquals(LITHUANIAN.text, decoded.text, hint)
            assertTrue(decoded.confident, hint)
        }
        for (hint in listOf(null, "lv")) {
            val latvian = decodeSubtitleBytes(LATVIAN.bytes, languageHint = hint)
            assertEquals("windows-1257", latvian.charset, hint)
            assertEquals(LATVIAN.text, latvian.text, hint)
            assertTrue(latvian.confident, hint)
        }
        val portuguese = decodeSubtitleBytes(PORTUGUESE.bytes)
        assertEquals("windows-1252", portuguese.charset)
        assertEquals(PORTUGUESE.text, portuguese.text)
        assertTrue(portuguese.confident)
    }

    @Test
    fun centralEuropeanAndTurkishFilesReadInTheirOwnTablesWithOrWithoutTheirLanguage() {
        // Each read as windows-1252 with a warning, even given its language (#518): Turkish and
        // Hungarian because two tables read them alike and the guess called that a tie, Polish and
        // Romanian because windows-1250 was scored on Czech letters alone.
        val files = listOf(
            Triple("tr", "windows-1254", TURKISH),
            Triple("hu", "windows-1250", HUNGARIAN),
            Triple("pl", "windows-1250", POLISH),
            Triple("ro", "windows-1250", ROMANIAN),
            Triple("pl", "ISO-8859-2", POLISH_ISO),
        )
        for ((language, encoding, fixture) in files) {
            for (hint in listOf(null, language)) {
                val decoded = decodeSubtitleBytes(fixture.bytes, languageHint = hint)
                assertEquals(encoding, decoded.charset, "the $language file, hint $hint")
                assertEquals(fixture.text, decoded.text, "the $language file, hint $hint")
                assertTrue(decoded.confident, "the $language file, hint $hint")
            }
        }
    }

    @Test
    fun tablesThatReadAFileAlikeAreOneAnswer() {
        // windows-1250 and ISO-8859-2 put every Hungarian letter on the same byte, so they read this
        // file alike. That is one answer, named for the first of the two, not a tie that leaves the
        // file unsure (#518).
        assertEquals(
            SubtitleCharset.Windows1250.decode(HUNGARIAN.bytes),
            SubtitleCharset.Iso88592.decode(HUNGARIAN.bytes),
            "the premise: both tables read the file alike",
        )
        val decoded = decodeSubtitleBytes(HUNGARIAN.bytes)
        assertEquals("windows-1250", decoded.charset)
        assertTrue(decoded.confident)
        // Where they differ, as on Polish ą and ś, the file is read by the one it is in.
        assertNotEquals(
            SubtitleCharset.Windows1250.decode(POLISH_ISO.bytes),
            SubtitleCharset.Iso88592.decode(POLISH_ISO.bytes),
        )
        assertEquals("ISO-8859-2", decodeSubtitleBytes(POLISH_ISO.bytes).charset)
    }

    @Test
    fun turkishWithCurlyQuotesIsReadAsWindows1254() {
        // ISO-8859-9 reads every Turkish letter as windows-1254 does, and puts a control character
        // where windows-1254 has the curly quotes, which text never holds (#518).
        assertTrue(SubtitleCharset.Iso88599.cannotBeText(0x93))
        assertTrue(SubtitleCharset.Iso88599.cannotBeText(0x94))
        assertFalse(SubtitleCharset.Windows1254.cannotBeText(0x93))
        assertFalse(SubtitleCharset.Windows1254.cannotBeText(0x94))
        assertTrue(SubtitleCharset.Windows1254.cannotBeText(0x81), "a byte windows-1254 does not define")
        assertFalse(SubtitleCharset.Iso88599.cannotBeText(0xF0), "a letter")
        assertTrue(0x93.toByte() in TURKISH.bytes && 0x94.toByte() in TURKISH.bytes, "the premise: the file has curly quotes")
        for (hint in listOf(null, "tr")) {
            val decoded = decodeSubtitleBytes(TURKISH.bytes, languageHint = hint)
            assertEquals("windows-1254", decoded.charset, hint)
            assertEquals(TURKISH.text, decoded.text, hint)
            assertTrue(decoded.confident, hint)
        }
    }

    @Test
    fun aShortThaiLineIsReadAsWindows874WithoutItsLanguage() {
        // One line of Thai had too few letters for the old guess to be sure of it (#518).
        for (hint in listOf(null, "th")) {
            val decoded = decodeSubtitleBytes(SHORT_THAI.bytes, languageHint = hint)
            assertEquals("windows-874", decoded.charset, hint)
            assertEquals(SHORT_THAI.text, decoded.text, hint)
            assertTrue(decoded.confident, hint)
        }
    }

    @Test
    fun bytesThatCannotTellTwoLanguagesApartAreShownWesternUnlessTheLanguageSaysOtherwise() {
        // Lithuanian ė is Albanian ë in windows-1252, and a line whose only accent is ė reads as
        // either. Unsure, the guess shows what such a file is shown as everywhere else, and says it
        // guessed; a track that says it is Lithuanian is shown as Lithuanian, still unsure.
        val line = "1\n00:00:01,000 --> 00:00:01,900\n".encodeToByteArray() + hex("54eb74eb206e75eb6a6f206e616d6f2e")
        val unhinted = decodeSubtitleBytes(line)
        assertEquals("windows-1252", unhinted.charset)
        assertTrue(unhinted.text.endsWith("T\u00EBt\u00EB nu\u00EBjo namo."), unhinted.text)
        assertFalse(unhinted.confident)
        val hinted = decodeSubtitleBytes(line, languageHint = "lt")
        assertEquals("windows-1257", hinted.charset)
        assertTrue(hinted.text.endsWith("T\u0117t\u0117 nu\u0117jo namo."), hinted.text)
        assertFalse(hinted.confident)
    }

    @Test
    fun aShortWesternLineThatAnotherTableReadsNearlyAsWellIsShownWestern() {
        // windows-1257 has Č where windows-1252 has Ç, and this line reads a little likelier as
        // Lithuanian than as French. Too close to be sure either way, the guess shows the reading
        // such a file gets everywhere else, and says it guessed.
        val text = "1\n00:00:01,000 --> 00:00:01,900\n\u00C7a va mieux."
        val decoded = decodeSubtitleBytes(latin1(text))
        assertEquals("windows-1252", decoded.charset)
        assertEquals(text, decoded.text)
        assertFalse(decoded.confident)
    }

    @Test
    fun aShortLineThatNoEastAsianTableReadsStaysInItsOwnScript() {
        // Every word of a short Hebrew, Greek or Thai line is a run of high bytes, which is the
        // byte-pair shape of the East Asian encodings, so each table is asked, and each leaves a
        // character it cannot make out. Too few letters to be sure of, each line is still shown in
        // its own script, and with no reading to weigh, two or three pairs are not enough to name an
        // East Asian encoding in a warning.
        val cases = listOf(
            Triple(HEBREW, "windows-1255", "\u05D4\u05DE\u05E9\u05E4\u05D7\u05D4 \u05E9\u05DC\u05E0\u05D5 \u05D2\u05D3\u05D5\u05DC\u05D4 \u05DE\u05D0\u05D5\u05D3"),
            Triple(GREEK, "windows-1253", "\u0384E\u03BB\u03B1, \u03A6\u03C1\u03B1\u03BD\u03BA."),
            Triple(THAI_NAME, "windows-874", "\u0E44\u0E1A\u0E42\u0E2D-\u0E1E\u0E2D\u0E23\u0E4C\u0E17"),
        )
        for ((line, charset, text) in cases) {
            val asked = mutableListOf<String>()
            val decoded = decodeSubtitleBytes(line.bytes) { bytes, name ->
                asked += name
                line.tables(bytes, name)
            }
            assertEquals(EAST_ASIAN_FILES.keys, asked.toSet(), text)
            assertEquals(charset, decoded.charset, text)
            assertEquals(CUE.decodeToString() + text, decoded.text)
            assertFalse(decoded.confident, text)
            assertNull(decoded.unsupportedGuess, text)
            val untabled = decodeSubtitleBytes(line.bytes)
            assertEquals(charset, untabled.charset, text)
            assertNull(untabled.unsupportedGuess, text)
        }
    }

    @Test
    fun aShortEastAsianLineIsReadInItsOwnTableAndIsCertain() {
        // Lines #520 found shown as Arabic or Thai, a word or two each. Several tables read each of
        // them cleanly, and its own table reads it as characters far likelier in its language.
        for ((line, encoding) in listOf(WHAT to "Big5", WHERE to "EUC-KR", RIGHT to "Shift_JIS")) {
            val decoded = decodeSubtitleBytes(line.bytes, eastAsian = line.tables)
            assertEquals(encoding, decoded.charset, line.text(encoding))
            assertEquals(line.text(encoding), decoded.text)
            assertTrue(decoded.confident, line.text(encoding))
            assertNull(decoded.unsupportedGuess)
        }
    }

    @Test
    fun twoCharactersThatSeveralTablesReadAreSettledByTheTracksLanguage() {
        // Shift_JIS, GBK and EUC-KR each read these four bytes as two common characters, so on its
        // own the line is a guess. The track's language says which language they are in.
        val alone = decodeSubtitleBytes(BETSUNI.bytes, eastAsian = BETSUNI.tables)
        assertFalse(alone.confident, alone.text)
        for (hint in listOf("ja", "jpn", "ja-JP")) {
            val decoded = decodeSubtitleBytes(BETSUNI.bytes, languageHint = hint, eastAsian = BETSUNI.tables)
            assertEquals("Shift_JIS", decoded.charset, hint)
            assertEquals(BETSUNI.text("Shift_JIS"), decoded.text)
            assertTrue(decoded.confident, hint)
        }
    }

    @Test
    fun aChineseTagThatImpliesItsScriptNamesBig5OrGbk() {
        // Traditional Chinese in Big5 that GBK also reads cleanly, as other characters. Chinese alone
        // names both tables, so the line stays a guess; Taiwan or the traditional script names Big5.
        val bare = decodeSubtitleBytes(CRICKET.bytes, languageHint = "zh", eastAsian = CRICKET.tables)
        assertFalse(bare.confident, bare.text)
        for (hint in listOf("zh-TW", "zh-Hant", "zh-HK")) {
            val decoded = decodeSubtitleBytes(CRICKET.bytes, languageHint = hint, eastAsian = CRICKET.tables)
            assertEquals("Big5", decoded.charset, hint)
            assertEquals(CRICKET.text("Big5"), decoded.text)
            assertTrue(decoded.confident, hint)
        }
        val simplified = decodeSubtitleBytes(UNDERSTOOD.bytes, languageHint = "zh-CN", eastAsian = UNDERSTOOD.tables)
        assertEquals("GBK", simplified.charset)
        assertEquals(UNDERSTOOD.text("GBK"), simplified.text)
        assertTrue(simplified.confident)
    }

    @Test
    fun oneCharacterIsEnoughWhenTheTrackNamesItsLanguage() {
        // A one-word line, which Korean and Japanese subtitles have often, is one byte pair. Without
        // a language one pair is as likely two accents side by side, so no table is asked; with
        // Korean or Japanese named, it is one character of that language.
        for ((line, hint, encoding) in listOf(Triple(YES, "ko", "EUC-KR"), Triple(EH, "ja", "Shift_JIS"))) {
            val decoded = decodeSubtitleBytes(line.bytes, languageHint = hint, eastAsian = line.tables)
            assertEquals(encoding, decoded.charset, hint)
            assertEquals(line.text(encoding), decoded.text)
            assertTrue(decoded.confident, hint)
            val asked = mutableListOf<String>()
            decodeSubtitleBytes(line.bytes) { _, name ->
                asked += name
                null
            }
            assertEquals(emptyList(), asked, line.text(encoding))
        }
    }

    @Test
    fun aCloseEastAsianReadingLeavesTheSingleByteOneUnsure() {
        // Read one byte at a time, these two Japanese characters are four Cyrillic letters that
        // windows-1251 reads far likelier than any other single-byte table. The Japanese reading is
        // not much less likely, so the Cyrillic is a guess, and with Japanese named the line is read
        // as Japanese.
        val alone = decodeSubtitleBytes(ROGER.bytes, eastAsian = ROGER.tables)
        assertEquals("windows-1251", alone.charset)
        assertFalse(alone.confident)
        val japanese = decodeSubtitleBytes(ROGER.bytes, languageHint = "ja", eastAsian = ROGER.tables)
        assertEquals("EUC-JP", japanese.charset)
        assertEquals(ROGER.text("EUC-JP"), japanese.text)
    }

    @Test
    fun aShortChineseLineThatKoreanReadsCleanlyIsNotCertain() {
        // EUC-KR reads these three GBK characters as three Hangul syllables, every one of them real.
        // The Chinese reading is the likelier, and too short to be sure of; before #520 the line was
        // shown as Korean with certainty.
        val decoded = decodeSubtitleBytes(UNDERSTOOD.bytes, eastAsian = UNDERSTOOD.tables)
        assertEquals("GBK", decoded.charset)
        assertEquals(UNDERSTOOD.text("GBK"), decoded.text)
        assertFalse(decoded.confident)
    }

    @Test
    fun aWesternWordWithTwoAccentsInARowIsNotCertainWithoutItsLanguage() {
        // "maïsbrood" written with a diaeresis before the i, two high bytes that Big5 and EUC-KR each
        // read as one character. Without a language the line is a guess; Dutch settles it.
        val text = "Ik heb ma\u00A8\u00EFsbrood met chili gemaakt.\nZei je ma\u00A8\u00EFsbrood en chili?"
        val bytes = CUE + latin1(text)
        val tables: (ByteArray, String) -> String? = { _, name ->
            when (name) {
                "Big5" -> "\u5241"
                "EUC-KR" -> "\u2468"
                "GBK" -> "\uE7D2"
                else -> null
            }?.let { CUE.decodeToString() + text.replace("\u00A8\u00EF", it) }
        }
        assertFalse(decodeSubtitleBytes(bytes, eastAsian = tables).confident)
        val dutch = decodeSubtitleBytes(bytes, languageHint = "nl", eastAsian = tables)
        assertEquals("windows-1252", dutch.charset)
        assertEquals(CUE.decodeToString() + text, dutch.text)
    }

    @Test
    fun aThaiLineThatAChineseTableReadsCleanlyIsStillThai() {
        // One long Thai word of rare letters reads too unlikely to be sure of as Thai, and GBK reads
        // every byte pair of it as a Chinese character. Those characters are rarer in Chinese than
        // the Thai letters are in Thai, so the line stays Thai, unsure.
        val thai = "ศาสตราจารย์ศึกษา" +
            "เศรษฐศาสตร์พิเศษ"
        val chinese = "纫实靡ㄒ寐烊帧梢嗳蒙叭沂得炀脏壬"
        val bytes = CUE + hex("c8d2cab5c3d2a8d2c3c2ecc8d6a1c9d2e0c8c3c9b0c8d2cab5c3ecbed4e0c8c9")
        val asked = mutableListOf<String>()
        val decoded = decodeSubtitleBytes(bytes) { _, name ->
            asked += name
            if (name == "GBK") CUE.decodeToString() + chinese else null
        }
        assertEquals("GBK", asked.first(), "the bytes have the shape of GBK first")
        assertEquals("windows-874", decoded.charset)
        assertTrue(decoded.text.endsWith(thai), decoded.text)
        assertFalse(decoded.confident)
        assertNull(decoded.unsupportedGuess)
    }

    @Test
    fun aJapaneseLineWhoseCharactersEndInAsciiBytesIsStillJapanese() {
        // Two of these Shift_JIS characters end in a byte that is an ASCII letter on its own. Read
        // as Arabic, those letters cost nothing, so they are charged as ASCII letters cost in
        // subtitles, and without that charge the line read as Arabic.
        val japanese = "\"前代未聞の嵐雲が\""
        val bytes = CUE + hex("22914f91e396a295b782cc9792895f82aa22")
        val decoded = decodeSubtitleBytes(bytes) { _, name ->
            if (name == "Shift_JIS") CUE.decodeToString() + japanese else null
        }
        assertEquals("Shift_JIS", decoded.charset)
        assertTrue(decoded.text.endsWith(japanese), decoded.text)
        assertTrue(decoded.confident)
    }

    @Test
    fun aWordReadThroughTheWrongTableOfItsScriptHasCapitalsAfterSmallLetters() {
        // windows-1251 and KOI8-R put capital and small Cyrillic letters on opposite halves, and
        // KOI8-R reads windows-1253's Greek bytes as Cyrillic the same way round. Read through the
        // wrong table, a word comes out as a small letter followed by capitals, which real text
        // almost never has. Counted letter by letter alone, each line read likelier as KOI8-R.
        val cases = listOf(
            Triple("d5eef0eef8ee2e", "windows-1251", "\u0425\u043E\u0440\u043E\u0448\u043E."),
            Triple(
                "c5f5f7e1f1e9f3f4fe2e",
                "windows-1253",
                "\u0395\u03C5\u03C7\u03B1\u03C1\u03B9\u03C3\u03C4\u03CE.",
            ),
        )
        for ((bytes, charset, text) in cases) {
            val decoded = decodeSubtitleBytes(CUE + hex(bytes))
            assertEquals(charset, decoded.charset, text)
            assertTrue(decoded.text.endsWith(text), decoded.text)
            assertTrue(decoded.confident, text)
        }
    }

    @Test
    fun aLineInCapitalsIsReadAsTheLettersItSpells() {
        // A capital counts as its small letter, so a sign in capitals reads as the words it spells.
        // Counted as they are, its capitals are rare, and KOI8-R, which reads them as small
        // letters, won. Only a capital straight after a small letter is a sign of the wrong table:
        // a capital after another, or after a space, is how a capital is written.
        val text = "\u0412\u041D\u0418\u041C\u0410\u041D\u0418\u0415! \u041F\u041E\u0416\u0410\u0420!"
        val decoded = decodeSubtitleBytes(CUE + hex("c2cdc8ccc0cdc8c52120cfcec6c0d021"))
        assertEquals("windows-1251", decoded.charset)
        assertTrue(decoded.text.endsWith(text), decoded.text)
        assertTrue(decoded.confident)
    }

    @Test
    fun anAnswerSeveralTablesShareIsNamedForTheLanguageThenForWindows1252() {
        // Every Latin table reads á and é alike, and so does windows-1254 with ç and ü, so these
        // lines show the same text in several tables. The name is what the warning and the override
        // offer: the table the track's language names, then windows-1252, before the one the
        // letters happen to favour.
        val spanish = CUE + latin1("Mam\u00E1, \u00E9l no es mi amigo.")
        assertEquals("windows-1252", decodeSubtitleBytes(spanish).charset)
        val turkish = CUE + hex("c76f6b2067fc7a656c206269722067fc6e2e")
        assertEquals("windows-1252", decodeSubtitleBytes(turkish).charset)
        val named = decodeSubtitleBytes(turkish, languageHint = "tr")
        assertEquals("windows-1254", named.charset)
        assertTrue(named.text.endsWith("\u00C7ok g\u00FCzel bir g\u00FCn."), named.text)
    }

    @Test
    fun aWordTooShortToReadAsTextIsStillShownInItsLikeliestTable() {
        // "ใช่", yes in Thai, is three letters with a tone mark, too few to read as convincing
        // text in any table. It is not East Asian, so the likeliest table is still the best
        // answer, shown unsure, rather than windows-1252, which shows it as three Latin letters.
        for (hint in listOf(null, "th")) {
            val decoded = decodeSubtitleBytes(CUE + hex("e3aae8"), languageHint = hint)
            assertEquals("windows-874", decoded.charset, hint)
            assertTrue(decoded.text.endsWith("\u0E43\u0E0A\u0E48"), decoded.text)
            assertFalse(decoded.confident, hint)
        }
    }

    @Test
    fun aNamedEncodingIsReadAsToldWithNoGuess() {
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
        assertEquals(decodeSubtitleBytes(POLISH.bytes), decodeSubtitleBytes(POLISH.bytes, fallback = "GBK"))
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

    /**
     * A short line after a cue, and what each East Asian table reads it as, by the detector's name for
     * the table. The readings are Python's codecs cp932, euc_jp, gb18030, big5hkscs and cp949, the
     * nearest there to the WHATWG tables `kiteplayer-subtitles` reads with. A reading with U+FFFD or a
     * private-use character in it is one that table could not make out.
     */
    private class EastAsianLine(line: String, vararg readings: Pair<String, String>) {
        private val byName = readings.toMap()
        val bytes = CUE + hex(line)

        /** A backend's tables, which read the cue as ASCII and the line as the readings say. */
        val tables: (ByteArray, String) -> String? = { _, name -> byName[name]?.let { CUE.decodeToString() + it } }

        fun text(encoding: String): String = CUE.decodeToString() + byName.getValue(encoding)
    }

    private companion object {
        /** The tables whose letters are not Latin ones. */
        val OTHER_SCRIPTS = setOf("windows-1251", "windows-1253", "windows-1255", "windows-1256", "KOI8-R")

        /** A cue's number and times, which hold no byte above ASCII. */
        val CUE = "1\n00:00:01,000 --> 00:00:01,900\n".encodeToByteArray()

        fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun latin1(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

        /**
         * Real dialogue in each table #515 added, and short Polish in windows-1250, which the guess
         * read as windows-1252 until #518. Each text is what Python's codec of that name decodes the
         * bytes to, and windows-1258 keeps Vietnamese tones as combining marks after their vowels.
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


        /**
         * Real dialogue for #518, each in the table its language was written in: Turkish in
         * windows-1254 with a pair of curly quotes, Hungarian and Romanian in windows-1250, one line
         * of Thai in windows-874, Polish in ISO-8859-2, and Portuguese in windows-1252.
         */
        val TURKISH = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a427567fc6e2069fe65206769746d6564696d" +
                "2c20e7fc6e6bfc2068617374617964fd6d2e0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930" +
                "300a4b61726465fe696d2093796172fd6e2067656c69796f72756d94206465646920616d612067656c6d6564692e0a0a330a" +
                "30303a30303a30332c303030202d2d3e2030303a30303a30332c3930300ade696d6469206e6520796170616361f0fd6dfd7a" +
                "fd2062696c6d69796f72756d2e0a0a340a30303a30303a30342c303030202d2d3e2030303a30303a30342c3930300a49f064" +
                "fd72276120676964656e206f746f62fc732073616174206b61e77461206b616c6bfd796f723f0a0a350a30303a30303a3035" +
                "2c303030202d2d3e2030303a30303a30352c3930300add7374616e62756c276461206861766120627567fc6e20e76f6b2067" +
                "fc7a656c64692e0a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\nBug\u00FCn i\u015Fe gitmedim, \u00E7\u00FCnk\u00FC hastayd\u0131m.\n\n2\n00:00:02,000 --> 00:00:02,900\nKarde\u015Fim \u201Cyar\u0131n geliyorum\u201D dedi ama gelmedi.\n\n3\n00:00:03,000 --> 00:00:03,900\n\u015Eimdi ne yapaca\u011F\u0131m\u0131z\u0131 bilmiyorum.\n\n4\n00:00:04,000 --> 00:00:04,900\nI\u011Fd\u0131r'a giden otob\u00FCs saat ka\u00E7ta kalk\u0131yor?\n\n5\n00:00:05,000 --> 00:00:05,900\n\u0130stanbul'da hava bug\u00FCn \u00E7ok g\u00FCzeldi.\n\n",
        )
        val HUNGARIAN = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a4e656d207475646f6d2c20686f76e1206d65" +
                "6e7420612062e17479e16d2e0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930300a486f6c6e" +
                "617020612067796572656b656b6b656c20656779fc7474206d656779fc6e6b20612074f3686f7a2e0a0a330a30303a30303a" +
                "30332c303030202d2d3e2030303a30303a30332c3930300a417a74206d6f6e6474612c20686f67792061206dfb736f72206d" +
                "e1722076e967657420e972742e0a0a340a30303a30303a30342c303030202d2d3e2030303a30303a30342c3930300a4d6f73" +
                "74206de1722074fa6c206be973f520657a656e2076e16c746f7a7461746e692e0a0a350a30303a30303a30352c303030202d" +
                "2d3e2030303a30303a30352c3930300a48616c6c61737a20656e67656d3f2056e16c61737a6f6c6a2c206d69656cf5747420" +
                "74fa6c206be973f5206c65737a2e0a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\nNem tudom, hov\u00E1 ment a b\u00E1ty\u00E1m.\n\n2\n00:00:02,000 --> 00:00:02,900\nHolnap a gyerekekkel egy\u00FCtt megy\u00FCnk a t\u00F3hoz.\n\n3\n00:00:03,000 --> 00:00:03,900\nAzt mondta, hogy a m\u0171sor m\u00E1r v\u00E9get \u00E9rt.\n\n4\n00:00:04,000 --> 00:00:04,900\nMost m\u00E1r t\u00FAl k\u00E9s\u0151 ezen v\u00E1ltoztatni.\n\n5\n00:00:05,000 --> 00:00:05,900\nHallasz engem? V\u00E1laszolj, miel\u0151tt t\u00FAl k\u00E9s\u0151 lesz.\n\n",
        )
        val ROMANIAN = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a4e7520ba74697520756e6465206120706c65" +
                "6361742066726174656c65206d65752e0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930300a" +
                "4de2696e65206d657267656d206c61206d61726520637520636f706969692e0a0a330a30303a30303a30332c303030202d2d" +
                "3e2030303a30303a30332c3930300a4120737075732063e320636166656e65617561206572612064656a6120ee6e63686973" +
                "e32e0a0a340a30303a30303a30342c303030202d2d3e2030303a30303a30342c3930300a4163756d20657374652070726561" +
                "2074e2727a69752073e320736368696d62e36d20636576612e0a0a350a30303a30303a30352c303030202d2d3e2030303a30" +
                "303a30352c3930300a4de32061757a693f2052e37370756e64652d6d692c20746520726f672c20ba6920fe696e65206d696e" +
                "746520617374612e0a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\nNu \u015Ftiu unde a plecat fratele meu.\n\n2\n00:00:02,000 --> 00:00:02,900\nM\u00E2ine mergem la mare cu copiii.\n\n3\n00:00:03,000 --> 00:00:03,900\nA spus c\u0103 cafeneaua era deja \u00EEnchis\u0103.\n\n4\n00:00:04,000 --> 00:00:04,900\nAcum este prea t\u00E2rziu s\u0103 schimb\u0103m ceva.\n\n5\n00:00:05,000 --> 00:00:05,900\nM\u0103 auzi? R\u0103spunde-mi, te rog, \u015Fi \u0163ine minte asta.\n\n",
        )
        val SHORT_THAI = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300aa9d1b9e4c1e8c3d9e9c7e8d2e0a2d2e4bbe4" +
                "cbb90a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\n\u0E09\u0E31\u0E19\u0E44\u0E21\u0E48\u0E23\u0E39\u0E49\u0E27\u0E48\u0E32\u0E40\u0E02\u0E32\u0E44\u0E1B\u0E44\u0E2B\u0E19\n\n",
        )
        val POLISH_ISO = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a447a6965f120646f6272792c20637a79206d" +
                "f367b36279b6206d6920706f6df3633f0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930300a" +
                "4a61b620777a69b1b3207a6520736f62b1206b7369b1bf6bea206920bc6c65207369ea20637a75b32e0a0a330a30303a3030" +
                "3a30332c303030202d2d3e2030303a30303a30332c3930300a50726f737aea206f206369737aea2c207a6172617a207a6163" +
                "7a796e616d792e0a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\nDzie\u0144 dobry, czy m\u00F3g\u0142by\u015B mi pom\u00F3c?\n\n2\n00:00:02,000 --> 00:00:02,900\nJa\u015B wzi\u0105\u0142 ze sob\u0105 ksi\u0105\u017Ck\u0119 i \u017Ale si\u0119 czu\u0142.\n\n3\n00:00:03,000 --> 00:00:03,900\nProsz\u0119 o cisz\u0119, zaraz zaczynamy.\n\n",
        )
        val PORTUGUESE = Fixture(
            hex(
                "310a30303a30303a30312c303030202d2d3e2030303a30303a30312c3930300a4ee36f207365692070617261206f6e646520" +
                "6f206d65752069726de36f20666f692e0a0a320a30303a30303a30322c303030202d2d3e2030303a30303a30322c3930300a" +
                "416d616e68e32076616d6f7320e020707261696120636f6d20617320637269616ee761732e0a0a330a30303a30303a30332c" +
                "303030202d2d3e2030303a30303a30332c3930300a456c6520646973736520717565206f20636166e9206ae1206573746176" +
                "61206665636861646f2e0a0a340a30303a30303a30342c303030202d2d3e2030303a30303a30342c3930300a41676f726120" +
                "e92074617264652064656d6169732070617261206d75646172206973736f2e0a0a350a30303a30303a30352c303030202d2d" +
                "3e2030303a30303a30352c3930300a566f63ea206d65206f7576653f20526573706f6e646120616e74657320717565207365" +
                "6a612074617264652e0a0a",
            ),
            "1\n00:00:01,000 --> 00:00:01,900\nN\u00E3o sei para onde o meu irm\u00E3o foi.\n\n2\n00:00:02,000 --> 00:00:02,900\nAmanh\u00E3 vamos \u00E0 praia com as crian\u00E7as.\n\n3\n00:00:03,000 --> 00:00:03,900\nEle disse que o caf\u00E9 j\u00E1 estava fechado.\n\n4\n00:00:04,000 --> 00:00:04,900\nAgora \u00E9 tarde demais para mudar isso.\n\n5\n00:00:05,000 --> 00:00:05,900\nVoc\u00EA me ouve? Responda antes que seja tarde.\n\n",
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

        /** 什麼？, "what?", in Big5. */
        val WHAT = EastAsianLine(
            "a4b0bbf2a148",
            "Shift_JIS" to "\uFF64\uFF70\uFF7B\uE1D8H",
            "EUC-JP" to "\u3050\u8CDC\uFFFDH",
            "GBK" to "\u3050\u6216\uE4CE",
            "Big5" to "\u4EC0\u9EBC\uFF1F",
            "EUC-KR" to "\u3140\uC0C0\uC8AD",
        )

        /** 어디 가?, "where are you going?", in EUC-KR. */
        val WHERE = EastAsianLine(
            "beeeb5f020b0a13f",
            "Shift_JIS" to "\uFF7E\u92D9\uFFFD \uFF70\uFF61?",
            "EUC-JP" to "\u5B22\u5DE8 \u4E9C?",
            "GBK" to "\u7EE2\u53FC \u554A?",
            "Big5" to "\u6A6B\u86E4 \u965B?",
            "EUC-KR" to "\uC5B4\uB514 \uAC00?",
        )

        /** - そうだ, "- that's right", in Shift_JIS. */
        val RIGHT = EastAsianLine(
            "2d2082bb82a482be",
            "Shift_JIS" to "- \u305D\u3046\u3060",
            "EUC-JP" to "- \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD",
            "GBK" to "- \u5066\u5046\u5069",
            "Big5" to "- \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD",
            "EUC-KR" to "- \uADA9\uAD8E\uADAC",
        )

        /** 別に, "not really", in Shift_JIS. */
        val BETSUNI = EastAsianLine(
            "95ca82c9",
            "Shift_JIS" to "\u5225\u306B",
            "EUC-JP" to "\uFFFD\uFFFD\uFFFD\uFFFD",
            "GBK" to "\u66BF\u5075",
            "Big5" to "\uD853\uDC09\uFFFD\uFFFD",
            "EUC-KR" to "\uBE76\uADB8",
        )

        /** 讓蟋蟀, "let the cricket", in Big5. */
        val CRICKET = EastAsianLine(
            "c5fdc1b5c1ac",
            "Shift_JIS" to "\uFF85\uF8F1\uFF81\uFF75\uFF81\uFF6C",
            "EUC-JP" to "\u7D71\u7985\u92AD",
            "GBK" to "\u7435\u604B\u8FDE",
            "Big5" to "\u8B93\u87CB\u87C0",
            "EUC-KR" to "\uD248\uC854\uC82F",
        )

        /** 明白了, "understood", in GBK. */
        val UNDERSTOOD = EastAsianLine(
            "c3f7b0d7c1cb",
            "Shift_JIS" to "\uFF83\uE593\uFF97\uFF81\uFF8B",
            "EUC-JP" to "\u82E7\u6613\u963B",
            "GBK" to "\u660E\u767D\u4E86",
            "Big5" to "\u96B4\u555E\u8CF8",
            "EUC-KR" to "\uCE20\uAC9C\uC8C4",
        )

        /** "΄Eλα, Φρανκ.", "come on, Frank", in windows-1253, as a file of the corpus wrote it. */
        val GREEK = EastAsianLine(
            "b445ebe12c20d6f1e1edea2e",
            "Shift_JIS" to "\uFF74E\uFFFD\uFFFD, \uFF96\uE15C\u6E3C.",
            "EUC-JP" to "\uFFFDE\u8AF1, \u5E62\u7622\uFFFD.",
            "GBK" to "\u788B\u8136, \u7AF9\u72B4\uFFFD.",
            "Big5" to "\u5AA7\u9306, \u7F63\u647F\uFFFD.",
            "EUC-KR" to "\uD032\u541F, \u8CC2\uF970\uFFFD.",
        )

        /** ไบโอ-พอร์ท, a name, in windows-874. */
        val THAI_NAME = EastAsianLine(
            "e4bae2cd2dbecdc3ecb7",
            "Shift_JIS" to "\u83A0\u7C23-\uFF7E\uFF8D\uFF83\uFFFD\uFF77",
            "EUC-JP" to "\u7BCB\u775B-\u7965\u67F1\uFFFD",
            "GBK" to "\u6D5C\u9994-\u5C31\u6E3A\uFFFD",
            "Big5" to "\u922D\u734C-\u61A9\u93C8\uFFFD",
            "EUC-KR" to "\u96C5\u9700-\uC54E\uCDEC\uFFFD",
        )

        /** "our family is very big" in windows-1255. */
        val HEBREW = EastAsianLine(
            "e4eef9f4e7e420f9ecf0e520e2e3e5ece420eee0e5e3",
            "Shift_JIS" to "\u84A1\uE74F\u9215 \uE747\uE0A4 \u7CA4\u890C\uFFFD \u9AD9\u88D9",
            "EUC-JP" to "\u7CAE\uFFFD\uFFFD\u822E \uFFFD\u8DDF\uFFFD \u77E3\u7E7B\uFFFD \u91F5\u7E5D",
            "GBK" to "\u6F15\uE2E5\u739F \uE2DD\u75B1 \u5FCF\u5C50\uFFFD \u94B9\u9088",
            "Big5" to "\u510B\u2562\u8AD9 \u2558\u8B25 \u777C\u6A26\uFFFD \u9357\u6A0D",
            "EUC-KR" to "\u54C0\u5AE6\uF9B7 \u76D2\u91E3 \u6812\u8AFA\uFFFD \u7E3E\u61B6",
        )

        /** 네?, "yes?", in EUC-KR. */
        val YES = EastAsianLine(
            "b3d73f",
            "Shift_JIS" to "\uFF73\uFF97?",
            "EUC-JP" to "\u9769?",
            "GBK" to "\u5319?",
            "Big5" to "\u557B?",
            "EUC-KR" to "\uB124?",
        )

        /** え?, "huh?", in Shift_JIS. */
        val EH = EastAsianLine(
            "82a63f",
            "Shift_JIS" to "\u3048?",
            "EUC-JP" to "\uFFFD\uFFFD?",
            "GBK" to "\u504A?",
            "Big5" to "\uFFFD\uFFFD?",
            "EUC-KR" to "\uAD91?",
        )

        /** 了解, "roger", in EUC-JP. */
        val ROGER = EastAsianLine(
            "cebbb2f2",
            "Shift_JIS" to "\uFF8E\uFF7B\uFF72\uFFFD",
            "EUC-JP" to "\u4E86\u89E3",
            "GBK" to "\u4F4D\u8C7A",
            "Big5" to "\u5F07\u8378",
            "EUC-KR" to "\u8CAB\uB01D",
        )
    }
}
