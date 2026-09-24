package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Bytes from a hex string, spaces allowed, so a fixture reads as the codes it is made of. */
private fun hex(text: String): ByteArray {
    val digits = text.filter { !it.isWhitespace() }
    return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** The code points of a string, with a surrogate pair counted as the one code point it is. */
private fun String.codePointList(): List<Int> {
    val points = mutableListOf<Int>()
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length) {
            points += 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
            i += 2
        } else {
            points += c.code
            i++
        }
    }
    return points
}

private fun decode(encoding: String, bytes: String): List<Int> =
    EastAsianText.decode(hex(bytes), encoding)!!.codePointList()

/** A SubRip file of one cue per line, each line given as the hex of its encoded text. */
private fun subRip(vararg lines: String): ByteArray {
    val out = mutableListOf<Byte>()
    lines.forEachIndexed { index, line ->
        val n = index + 1
        out += "$n\n00:00:0$n,000 --> 00:00:0$n,900\n".encodeToByteArray().toList()
        out += hex(line).toList()
        out += "\n\n".encodeToByteArray().toList()
    }
    return out.toByteArray()
}

private fun cueTexts(bytes: ByteArray, encoding: String): List<String> =
    SubRipParser.parse(EastAsianText.decode(bytes, encoding)!!).map { cue -> cue.spans.joinToString("") { it.text } }

/**
 * The five East Asian decoders, against code points the WHATWG Encoding Standard fixes.
 *
 * The fixtures are hex rather than files because this suite runs on every target, and most have no
 * filesystem. Each encoding gets the codes where vendors disagree, because a table taken from the
 * wrong source differs exactly there, and the bytes that must become U+FFFD.
 */
class EastAsianTextTest {

    @Test
    fun everyTableExpandsToWhatTheGeneratorWrote() {
        // The generator counted the entries and summed the table it packed. The same numbers here
        // mean this target's expansion reproduced every pointer of it.
        fun check(name: String, table: IntArray, size: Int, entries: Int, checksum: Int) {
            assertEquals(size, table.size, "$name size")
            assertEquals(entries, table.count { it != 0 }, "$name entries")
            var sum = 0
            for (entry in table) sum = (sum * 31 + entry) and 0x7FFFFFFF
            assertEquals(checksum, sum, "$name checksum")
        }
        check("jis0208", jis0208, JIS0208_SIZE, JIS0208_ENTRIES, JIS0208_CHECKSUM)
        check("jis0212", jis0212, JIS0212_SIZE, JIS0212_ENTRIES, JIS0212_CHECKSUM)
        check("gb18030", gb18030, GB18030_SIZE, GB18030_ENTRIES, GB18030_CHECKSUM)
        check("big5", big5, BIG5_SIZE, BIG5_ENTRIES, BIG5_CHECKSUM)
        check("euc-kr", eucKr, EUC_KR_SIZE, EUC_KR_ENTRIES, EUC_KR_CHECKSUM)
    }

    @Test
    fun shiftJisDecodesKnownCodePoints() {
        // 0x5C and 0x7E stay ASCII, a half-width katakana is one byte, and 0x8160 is the wave dash
        // as Windows reads it (U+FF5E), which is where a JIS-derived table would say U+301C.
        assertEquals(
            listOf(0x5C, 0x7E, 0x80, 0xFF61, 0xFF9F, 0x3042, 0x30A3, 0xFF5E, 0x4E9C, 0x7199),
            decode("Shift_JIS", "5C 7E 80 A1 DF 82A0 8342 8160 889F EAA4"),
        )
        // NEC row 13, an IBM extension, and the user-defined area, which lands in private use.
        assertEquals(listOf(0x2460, 0x2170, 0xE000), decode("Shift_JIS", "8740 FA40 F040"))
    }

    @Test
    fun eucJpDecodesKnownCodePoints() {
        // Two bytes for JIS X 0208, 0x8E for a half-width katakana, and 0x8F for JIS X 0212.
        assertEquals(
            listOf(0x3042, 0x4E9C, 0xFF5E, 0xFF61, 0x4E02, 0x2460),
            decode("EUC-JP", "A4A2 B0A1 A1C1 8EA1 8FB0A1 ADA1"),
        )
    }

    @Test
    fun gbkDecodesKnownCodePoints() {
        // 0x80 alone is the euro sign, and 0xA1AA is the em dash. 0xFE50 is a character that GBK
        // left in private use. 0xA6D9 is one that GB18030-2022 moved out of it, where a table made
        // before 2022 says U+E78D.
        assertEquals(
            listOf(0x20AC, 0x554A, 0x4E02, 0xFF0C, 0x2014, 0x2E81, 0xFE10),
            decode("GBK", "80 B0A1 8140 A3AC A1AA FE50 A6D9"),
        )
        // Four-byte GB18030: the first range, the one special pointer, the first code point past
        // the Basic Multilingual Plane, and the last code point in it.
        assertEquals(
            listOf(0x80, 0xE7C7, 0x10000, 0xFFFF),
            decode("GBK", "81308130 8135F437 90308130 8431A439"),
        )
    }

    @Test
    fun big5DecodesKnownCodePoints() {
        assertEquals(
            listOf(0x3000, 0x4E00, 0x4E59, 0xFFED, 0x20AC, 0x27267, 0x200CC),
            decode("Big5", "A140 A440 A441 F9FE A3E1 8745 C87A"),
        )
        // The four codes that decode to a letter and a combining mark.
        assertEquals(
            listOf(0xCA, 0x304, 0xCA, 0x30C, 0xEA, 0x304, 0xEA, 0x30C),
            decode("Big5", "8862 8864 88A3 88A5"),
        )
    }

    @Test
    fun eucKrDecodesKnownCodePoints() {
        // The first and last Hangul of KS X 1001, a syllable only the Windows extension carries,
        // an ideographic space, a Hanja, and a compatibility jamo.
        assertEquals(
            listOf(0xAC00, 0xD79D, 0xAC02, 0x3000, 0x4F3D, 0x314B),
            decode("EUC-KR", "B0A1 C8FE 8141 A1A1 CAA1 A4BB"),
        )
    }

    @Test
    fun aBrokenCharacterBecomesOneReplacementAndNeverSwallowsAnAsciiByte() {
        // A lead byte followed by a newline is one error, and the newline is still read. A
        // subtitle file whose cue text breaks off must not lose the line break that ends the cue.
        assertEquals(listOf(0xFFFD, 0x0A), decode("Shift_JIS", "820A"))
        assertEquals(listOf(0xFFFD, 0x41), decode("EUC-JP", "A441"))
        assertEquals(listOf(0xFFFD, 0x30), decode("Big5", "A130"))
        assertEquals(listOf(0xFFFD, 0x0A), decode("EUC-KR", "B00A"))
        // A four-byte sequence that breaks off after its digit gives the digit back, too.
        assertEquals(listOf(0xFFFD, 0x30, 0x20, 0x41), decode("GBK", "81302041"))
        // Codes no table fills, and a lead byte at the very end.
        assertEquals(listOf(0xFFFD), decode("EUC-KR", "C9A1"))
        assertEquals(listOf(0xFFFD), decode("EUC-JP", "8FA1A1"))
        assertEquals(listOf(0x41, 0xFFFD), decode("GBK", "41 A1"))
        assertEquals(listOf(0xFFFD, 0xFFFD), decode("Shift_JIS", "A0 82"))
    }

    @Test
    fun theDetectorsNamesResolveToTheirDecodersInAnyLetterCase() {
        // The engine's detector names the encoding with exactly these five spellings.
        val bytes = hex("82A0")
        assertEquals("あ", EastAsianText.decode(bytes, "Shift_JIS"))
        assertEquals("あ", EastAsianText.decode(bytes, "shift_jis"))
        assertEquals("あ", EastAsianText.decode(hex("A4A2"), "EUC-JP"))
        assertEquals("啊", EastAsianText.decode(hex("B0A1"), "GBK"))
        assertEquals("一", EastAsianText.decode(hex("A440"), "BIG5"))
        assertEquals("가", EastAsianText.decode(hex("B0A1"), "euc-kr"))
        // Anything else is not this decoder's to read, and says so rather than guessing.
        assertNull(EastAsianText.decode(bytes, "windows-1252"))
        assertNull(EastAsianText.decode(bytes, "Shift-JIS"))
        assertNull(EastAsianText.decode(bytes, ""))
    }

    @Test
    fun aShiftJisSubRipFileParsesToItsJapaneseCues() {
        val file = subRip(
            "82a882cd82e682a482b282b482a282dc82b781428da193fa82cd82a282a293568b4382c582b782cb8142",
            "897782cc914f82c591d282c182c482a282e982a982e78141918182ad978882c482ad82be82b382a28142",
            "82a082e882aa82c682a4814282dc82bd8da1937882e482c182ad82e8986282bb82a482cb8142",
        )
        assertEquals(JAPANESE, cueTexts(file, "Shift_JIS"))
    }

    @Test
    fun anEucJpSubRipFileParsesToItsJapaneseCues() {
        val file = subRip(
            "a4aaa4cfa4e8a4a6a4b4a4b6a4a4a4dea4b9a1a3baa3c6fca4cfa4a4a4a4c5b7b5a4a4c7a4b9a4cda1a3",
            "b1d8a4cec1b0a4c7c2d4a4c3a4c6a4a4a4eba4aba4e9a1a2c1e1a4afcde8a4c6a4afa4c0a4b5a4a4a1a3",
            "a4a2a4eaa4aca4c8a4a6a1a3a4dea4bfbaa3c5d9a4e6a4c3a4afa4eacfc3a4bda4a6a4cda1a3",
        )
        assertEquals(JAPANESE, cueTexts(file, "EUC-JP"))
    }

    @Test
    fun aGbkSubRipFileParsesToItsChineseCues() {
        val file = subRip(
            "d4e7c9cfbac3a3acbdf1ccecccecc6f8d5e6b2bbb4eda1a3",
            "ced2d4dab3b5d5bec7b0c3e6b5c8c4e3a3acc7ebbfecb5e3c0b4a1a3",
            "d0bbd0bbc4e3a3accfc2b4ceced2c3c7d4d9c2fdc2fdc1c4b0c9a1a3",
        )
        assertEquals(
            listOf("早上好，今天天气真不错。", "我在车站前面等你，请快点来。", "谢谢你，下次我们再慢慢聊吧。"),
            cueTexts(file, "GBK"),
        )
    }

    @Test
    fun aBig5SubRipFileParsesToItsChineseCues() {
        val file = subRip(
            "a6ada677a141a4b5a4d1a4d1aef0af75a4a3bff9a143",
            "a7daa662a8aeafb8ab65adb1b5a5a741a141bdd0a7d6c249a8d3a143",
            "c1c2c1c2a741a141a455a6b8a7daadcca641ba43ba43b2e1a761a143",
        )
        assertEquals(
            listOf("早安，今天天氣真不錯。", "我在車站前面等你，請快點來。", "謝謝你，下次我們再慢慢聊吧。"),
            cueTexts(file, "Big5"),
        )
    }

    @Test
    fun anEucKrSubRipFileParsesToItsKoreanCues() {
        val file = subRip(
            "c1c1c0ba20bec6c4a7c0ccbfa1bfe42e20bfc0b4c320b3afbebeb0a120c1a4b8bb20c1c1b3d7bfe42e",
            "bfaa20bed5bfa1bcad20b1e2b4d9b8aeb0ed20c0d6c0b8b4cfb1ee20bba1b8ae20bfcd20c1d6bcbcbfe42e",
            "b0edb8b6bff62e20b4d9c0bdbfa120c3b5c3b5c8f720c0ccbedfb1e2c7cfc0da2e",
        )
        assertEquals(
            listOf("좋은 아침이에요. 오늘 날씨가 정말 좋네요.", "역 앞에서 기다리고 있으니까 빨리 와 주세요.", "고마워. 다음에 천천히 이야기하자."),
            cueTexts(file, "EUC-KR"),
        )
    }

    private companion object {
        val JAPANESE = listOf(
            "おはようございます。今日はいい天気ですね。",
            "駅の前で待っているから、早く来てください。",
            "ありがとう。また今度ゆっくり話そうね。",
        )
    }
}
