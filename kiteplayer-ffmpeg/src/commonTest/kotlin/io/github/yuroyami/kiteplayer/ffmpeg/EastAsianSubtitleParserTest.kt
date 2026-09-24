package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The FFmpeg backend's subtitle parser is where the engine meets the East Asian tables of
 * kiteplayer-subtitles. The engine asks it by name and gets the text back; without this wiring the
 * default answers null and every such file falls back to windows-1252. No native call is involved.
 */
class EastAsianSubtitleParserTest {

    @Test
    fun theBackendsParserReadsEastAsianBytesWithTheSubtitleTables() {
        val parser = KiteFFmpegMediaBackend().subtitleFileParser()
        // The hiragana a, i and u in Shift_JIS, and the Hangul ga in EUC-KR.
        val shiftJis = byteArrayOf(0x82.toByte(), 0xA0.toByte(), 0x82.toByte(), 0xA2.toByte(), 0x82.toByte(), 0xA4.toByte())
        assertEquals("あいう", parser.decode(shiftJis, "Shift_JIS"))
        assertEquals("가", parser.decode(byteArrayOf(0xB0.toByte(), 0xA1.toByte()), "EUC-KR"))
        // A name the tables do not carry is not the parser's to read.
        assertNull(parser.decode(shiftJis, "windows-1252"))
    }
}
