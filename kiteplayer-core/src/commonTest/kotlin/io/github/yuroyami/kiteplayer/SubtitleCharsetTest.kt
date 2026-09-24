package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.decodeSubtitleBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private companion object {
        fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun latin1(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

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
