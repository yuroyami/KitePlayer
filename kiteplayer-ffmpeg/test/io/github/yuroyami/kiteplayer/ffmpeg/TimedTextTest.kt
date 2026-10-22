package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MPEG-4 timed text keeps its faces and colours (#512). The samples are the bytes FFmpeg's encoder
 * writes for SubRip lines with `<i>`, `<b>` and `<u>`, and a hand-made one for a colour.
 */
class TimedTextTest {

    private fun bytes(hex: String): ByteArray {
        val digits = hex.filter { !it.isWhitespace() }
        return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** A sample of [text] with one `styl` box holding [records], each start, end, face flags and RGBA. */
    private fun sample(text: String, vararg records: Pair<Pair<Int, Int>, Pair<Int, Long>>): ByteArray {
        val utf8 = text.encodeToByteArray()
        val out = ArrayList<Byte>()
        fun u16(value: Int) { out += (value shr 8).toByte(); out += value.toByte() }
        fun u32(value: Long) { for (shift in intArrayOf(24, 16, 8, 0)) out += (value shr shift).toByte() }
        u16(utf8.size)
        utf8.forEach { out += it }
        if (records.isNotEmpty()) {
            u32(10L + 12L * records.size)
            "styl".encodeToByteArray().forEach { out += it }
            u16(records.size)
            for ((range, look) in records) {
                u16(range.first); u16(range.second); u16(1)
                out += look.first.toByte(); out += 0x12
                u32(look.second)
            }
        }
        return out.toByteArray()
    }

    private fun spans(payload: ByteArray, default: TimedTextStyle? = null): List<StyledSpan> =
        assertNotNull(timedTextCue(payload, default, 0, 1_000_000)).spans

    // The sample description FFmpeg writes from a SubRip file: plain white, then its font table.
    private val description = bytes(
        "0000 0000 01ff 0000 00ff 0000 0000 0000 0000 0000 0000 0001 0010 ffff ffff 0000" +
            "0012 6674 6162 0001 0001 0541 7269 616c 0000 0014 6274 7274 0000 0000 0000 00cd 0000 00cd",
    )

    @Test
    fun aWholeItalicLineIsItalic() {
        val payload = bytes("0013 4f66 6620 7363 7265 656e 2c20 6120 766f 6963 6500 0000 1673 7479 6c00 0100 0000 1300 0102 10ff ffff ff")
        val spans = spans(payload, timedTextDefaultStyle(description))
        assertEquals(listOf("Off screen, a voice"), spans.map { it.text })
        assertTrue(spans.single().style.italic)
        assertEquals(0xFFFFFFFF.toInt(), spans.single().style.primaryColor)
    }

    @Test
    fun oneBoldWordIsBoldAndTheRestIsNot() {
        val payload = bytes(
            "001f 4120 706c 6169 6e20 6c69 6e65 2077 6974 6820 6f6e 6520 626f 6c64 2077 6f72 6400 0000 1673 7479 6c00 0100 1600 1a00 0101 10ff ffff ff",
        )
        val spans = spans(payload, timedTextDefaultStyle(description))
        assertEquals(listOf("A plain line with one ", "bold", " word"), spans.map { it.text })
        assertEquals(listOf(false, true, false), spans.map { it.style.bold })
    }

    @Test
    fun aRunAfterNonLatinTextCountsCharactersNotBytes() {
        // 日本語 takes nine bytes and three characters, and the run names characters 14 to 18.
        val payload = bytes("0018 e697 a5e6 9cac e8aa 9e20 616e 6420 6120 7265 6420 776f 7264 0000 0016 7374 796c 0001 000e 0012 0001 0410 ffff ffff")
        val spans = spans(payload, timedTextDefaultStyle(description))
        assertEquals(listOf("日本語 and a red ", "word"), spans.map { it.text })
        assertEquals(listOf(false, true), spans.map { it.style.underline })
    }

    @Test
    fun aColourRunTakesItsColour() {
        val payload = sample("a red word", (2 to 5) to (0 to 0xFF0000FFL), (6 to 10) to (3 to 0x00FF0080L))
        val spans = spans(payload)
        assertEquals(listOf("a ", "red", " ", "word"), spans.map { it.text })
        assertEquals(0xFFFF0000.toInt(), spans[1].style.primaryColor)
        // RGBA 00FF0080 is green at half alpha.
        assertEquals(0x8000FF00.toInt(), spans[3].style.primaryColor)
        assertTrue(spans[3].style.bold && spans[3].style.italic)
    }

    @Test
    fun aCharacterOutsideTheBasicPlaneCountsOnce() {
        val payload = sample("🎵 la la", (2 to 4) to (2 to 0xFFFFFFFFL))
        val spans = spans(payload)
        assertEquals(listOf("🎵 ", "la", " la"), spans.map { it.text })
        assertEquals(listOf(false, true, false), spans.map { it.style.italic })
    }

    @Test
    fun theTrackDefaultStyleCoversTextNoRunNames() {
        // The description's StyleRecord made italic and yellow.
        val yellow = description.copyOf().also {
            it[24] = 2
            it[26] = 0xFF.toByte(); it[27] = 0xFF.toByte(); it[28] = 0; it[29] = 0xFF.toByte()
        }
        val default = assertNotNull(timedTextDefaultStyle(yellow))
        val spans = spans(sample("all of it"), default)
        assertEquals("all of it", spans.single().text)
        assertTrue(spans.single().style.italic)
        assertEquals(0xFFFFFF00.toInt(), spans.single().style.primaryColor)
        assertNull(timedTextDefaultStyle(ByteArray(29)))
    }

    @Test
    fun anUnstyledSampleStillReadsAsSubRipText() {
        // A file whose text carries SubRip tags, as some tools write it, still shows them as styles.
        val spans = spans(sample("<i>tagged</i>"), timedTextDefaultStyle(description))
        assertEquals(listOf("tagged"), spans.map { it.text })
        assertTrue(spans.single().style.italic)
    }

    @Test
    fun anEmptySampleIsNoCue() {
        assertNull(timedTextCue(bytes("0000"), null, 0, 1))
        assertNull(timedTextCue(ByteArray(1), null, 0, 1))
    }

    @Test
    fun brokenBoxesAndRunsNeverThrow() {
        // A box that claims more than the sample holds ends the reading, and the text stays.
        val long = bytes("0002 6869 0000 0040 7374 796c 0001 0000 0002 0001 0110 ffff ffff")
        assertEquals("hi", spans(long).joinToString("") { it.text })
        // A record count larger than the box, runs out of order, past the end and back to front.
        val wild = sample("abcdef", (4 to 99) to (1 to 0xFFFFFFFFL), (3 to 1) to (2 to 0xFFFFFFFFL), (0 to 2) to (4 to 0xFFFFFFFFL))
        val spans = spans(wild)
        assertEquals("abcdef", spans.joinToString("") { it.text })
        assertTrue(spans.first().style.underline)
        assertTrue(spans.last().style.bold)
        val counted = sample("abc", (0 to 3) to (1 to 0xFFFFFFFFL)).also { it[it.size - 13] = 9 }
        assertEquals("abc", spans(counted).joinToString("") { it.text })
    }

    @Test
    fun utf16TextIsRead() {
        val payload = bytes("0008 feff 0068 0069 00e9 0000 0016 7374 796c 0001 0002 0003 0001 0210 ffff ffff")
        val spans = spans(payload)
        assertEquals(listOf("hi", "é"), spans.map { it.text })
        assertTrue(spans[1].style.italic)
    }
}
