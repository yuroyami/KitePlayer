package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How much of a subtitle file the SubRip and WebVTT scanners read, and what they find.
 *
 * The step counts read the text through [CountingText], which counts every character read, so
 * they hold on a busy machine as well as an idle one.
 */
class TextScanCostTest {

    /** Counts every character read through [get], and every character copied out by [subSequence]. */
    private class CountingText(private val text: String) : CharSequence {
        var reads = 0L
            private set

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            reads++
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            reads += endIndex - startIndex
            return text.subSequence(startIndex, endIndex)
        }

        override fun toString(): String {
            reads += text.length
            return text
        }
    }

    private fun assertFewReads(text: CountingText, what: String) {
        assertTrue(text.reads <= 8L * text.length, "$what: read ${text.reads} characters of ${text.length}")
    }

    @Test
    fun aLongLineThatIsNotATimingLineIsReadAFewTimesPerCharacter() {
        val lines = listOf(
            "0".repeat(40_000),
            "00:00:00,000".repeat(3_400),
            "0 -->x".repeat(7_000),
            "-->".repeat(14_000),
            "1,2.3:4 --> ".repeat(3_000) + "x",
        )
        for (line in lines) {
            for (allowComma in listOf(true, false)) {
                val text = CountingText(line)
                findTiming(text, allowComma)
                assertFewReads(text, "${line.take(12)}... allowComma=$allowComma")
            }
        }
    }

    @Test
    fun theTimingScannerFindsTheFirstArrowWithATimestampOnEachSide() {
        val plain = assertNotNull(findTiming("00:00:01,000 --> 00:00:02,500", allowComma = true))
        assertEquals("00:00:01,000", plain.start)
        assertEquals("00:00:02,500", plain.end)
        assertEquals(29, plain.endIndex)

        val padded = assertNotNull(findTiming("  7 00:00:01,000-->\t00:00:02,000 X1:10 X2:20", allowComma = true))
        assertEquals("00:00:01,000", padded.start)
        assertEquals("00:00:02,000", padded.end)
        assertEquals(" X1:10 X2:20", "  7 00:00:01,000-->\t00:00:02,000 X1:10 X2:20".substring(padded.endIndex))

        val second = assertNotNull(findTiming("x --> 00:00:01.000 --> 00:00:02.000 line:0%", allowComma = false))
        assertEquals("00:00:01.000", second.start)
        assertEquals("00:00:02.000", second.end)

        assertNull(findTiming("00:00:01,000 --> x", allowComma = true))
        assertNull(findTiming("--> 00:00:01,000", allowComma = true))
        assertNull(findTiming("no arrow at all", allowComma = true))
        // Without the comma a SubRip time splits in two, so WebVTT sees `000` before the arrow.
        assertEquals("000", findTiming("00:00:01,000 --> 00:00:02,000", allowComma = false)?.start)
    }

    @Test
    fun aSubRipFileWithOneLongLineOfDigitsKeepsItsCue() {
        val digits = "0".repeat(40_000)
        val cues = SubRipParser.parse("1\n00:00:01,000 --> 00:00:02,000\nHello\n\n$digits\n")
        assertEquals(1, cues.size)
        assertEquals(1_000_000L, cues.single().startMicros)
        assertEquals("Hello", cues.single().spans.joinToString("") { it.text })
    }

    @Test
    fun aWebVttFileWithOneLongLineOfDigitsKeepsItsCue() {
        val digits = "0".repeat(40_000)
        val cues = WebVttParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n\n$digits\n")
        assertEquals(1, cues.size)
        assertEquals(2_000_000L, cues.single().endMicros)
    }

    @Test
    fun voiceAndClassTagsWithoutACloseAreReadAFewTimesPerCharacter() {
        for (body in listOf("<v ".repeat(10_000), "<c.".repeat(10_000), "</v x".repeat(6_000), "<".repeat(30_000))) {
            for (isTag in listOf<(CharSequence, Int, Int) -> Boolean>({ _, _, _ -> false }, { _, _, _ -> true })) {
                val text = CountingText(body)
                assertEquals(body, stripTags(text, isTag), "no tag ends, so nothing is removed")
                assertFewReads(text, body.take(6))
            }
        }
    }

    @Test
    fun stripTagsRemovesWhatItsTestAcceptsUpToTheFirstClose() {
        val voiceOnly: (CharSequence, Int, Int) -> Boolean = { text, from, to -> text.substring(from, to).startsWith("v") }
        assertEquals("Hi there", stripTags("<v Bob>Hi<v> there", voiceOnly))
        assertEquals("", stripTags("<v Bob<i>", voiceOnly), "a tag runs to the first close after it")
        assertEquals("<b>x<", stripTags("<b>x<", voiceOnly))
        val cues = WebVttParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v Bob><c.loud>Hi</c></v> <00:00:01.500>there\n")
        assertEquals("Hi there", cues.single().spans.joinToString("") { it.text })
    }

    @Test
    fun braceRunsWithoutACloseAreReadAFewTimesPerCharacter() {
        for (body in listOf("{\\".repeat(20_000), "{\\an8 ".repeat(8_000), "x{\\".repeat(12_000))) {
            val text = CountingText(body)
            val parsed = InlineMarkup.parse(text, braceTags = true)
            assertEquals(body, parsed.spans.joinToString("") { it.text }, "an unterminated run stays text")
            assertFewReads(text, body.take(6))
        }
    }

    @Test
    fun aFileKeepsItsFirstCuesUpToTheLimit() {
        val srt = (1..5).joinToString("") { n -> "$n\n00:00:0$n,000 --> 00:00:0$n,500\ncue $n\n\n" }
        assertEquals(listOf("cue 1", "cue 2", "cue 3"), SubRipParser.parse(srt, maxCues = 3).map { it.spans.single().text })
        val vtt = "WEBVTT\n\n" + (1..5).joinToString("") { n -> "00:00:0$n.000 --> 00:00:0$n.500\ncue $n\n\n" }
        assertEquals(listOf("cue 1", "cue 2", "cue 3"), WebVttParser.parse(vtt, maxCues = 3).map { it.spans.single().text })
        assertEquals(100_000, MAX_FILE_CUES)
    }
}
