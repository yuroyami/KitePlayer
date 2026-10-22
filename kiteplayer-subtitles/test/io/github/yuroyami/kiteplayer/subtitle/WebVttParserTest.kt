package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebVttParserTest {

    @Test
    fun aPlainFileParsesWithTimesInMicroseconds() {
        val cues = WebVttParser.parse(
            """
            WEBVTT

            00:00:00.500 --> 00:00:03.000
            Hello from KitePlayer

            00:00:03.500 --> 00:00:06.000
            Second cue
            """.trimIndent(),
        )
        assertEquals(2, cues.size)
        assertEquals(500_000L, cues[0].startMicros)
        assertEquals(3_000_000L, cues[0].endMicros)
        assertEquals("Hello from KitePlayer", cues[0].plainText)
        assertEquals("Second cue", cues[1].plainText)
    }

    @Test
    fun bomSignatureIdentifiersAndHourlessTimesAllPass() {
        val cues = WebVttParser.parse(
            "﻿WEBVTT - a description\n\nchapter-1\n00:07.000 --> 00:09.500\nShort form\n",
        )
        assertEquals(1, cues.size)
        assertEquals(7_000_000L, cues[0].startMicros)
        assertEquals(9_500_000L, cues[0].endMicros)
        assertEquals("Short form", cues[0].plainText)
    }

    @Test
    fun noteStyleAndRegionBlocksAreSkippedWhole() {
        val cues = WebVttParser.parse(
            """
            WEBVTT

            NOTE this looks like a cue
            00:00:01.000 --> 00:00:02.000 inside a note

            STYLE
            ::cue { color: red }

            00:00:04.000 --> 00:00:05.000
            The real cue
            """.trimIndent(),
        )
        assertEquals(1, cues.size)
        assertEquals("The real cue", cues[0].plainText)
    }

    @Test
    fun voiceClassAndKaraokeTagsContributeTextWithoutDecoration() {
        val cues = WebVttParser.parse(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v Fred>Hi <00:00:01.500>there <c.yellow>friend</c></v>\n",
        )
        assertEquals(1, cues.size)
        assertEquals("Hi there friend", cues[0].plainText)
    }

    @Test
    fun aLanguageSpanKeepsItsTextWithoutItsTags() {
        val cue = WebVttParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<lang ja>こんにちは</lang>\n").single()
        assertEquals(listOf("こんにちは"), cue.spans.map { it.text })
        assertEquals("Hi there", WebVttParser.parseCueBody("<lang.loud en-GB>Hi</lang> there").joinToString("") { it.text })
    }

    @Test
    fun rubyIsWrittenAsTheBaseTextFollowedByItsReading() {
        fun read(body: String) = WebVttParser.parseCueBody(body).joinToString("") { it.text }
        assertEquals("漢字(かんじ)", read("<ruby>漢字<rt>かんじ</rt></ruby>"))
        assertEquals("漢(かん)字(じ)を読む", read("<ruby>漢<rt>かん</rt>字<rt>じ</rt></ruby>を読む"))
        // The reading's end tag may be left out before the ruby's, and either tag may carry a class.
        assertEquals("日本(にほん)", read("<ruby.big>日本<rt.small>にほん</ruby>"))
        // A reading outside a ruby keeps its text, as the specification's parser does.
        assertEquals("alone", read("<rt>alone</rt>"))
        // Styles inside a ruby still apply to the text they hold.
        val styled = WebVttParser.parseCueBody("<ruby><i>漢字</i><rt>かんじ</rt></ruby>")
        assertEquals("漢字(かんじ)", styled.joinToString("") { it.text })
        assertTrue(styled.first { it.text.contains("漢字") }.style.italic)
    }

    @Test
    fun aTagThatOnlyStartsLikeAKnownOneStaysText() {
        fun read(body: String) = WebVttParser.parseCueBody(body).joinToString("") { it.text }
        assertEquals("<foo>bar</foo>", read("<foo>bar</foo>"))
        assertEquals("<language>x</language>", read("<language>x</language>"))
        assertEquals("<rubyx>y</rubyx>", read("<rubyx>y</rubyx>"))
    }

    @Test
    fun boldAndItalicSurviveAsStyles() {
        val cues = WebVttParser.parse(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nplain <b>bold</b> <i>italic</i>\n",
        )
        val spans = cues.single().spans
        assertTrue(spans.any { it.style.bold && it.text == "bold" })
        assertTrue(spans.any { it.style.italic && it.text == "italic" })
    }

    @Test
    fun alignmentSettingsReachTheLayout() {
        val cues = WebVttParser.parse(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:end position:90%\nRight side\n",
        )
        assertEquals(CueAlignment.BottomRight, cues.single().layout.alignment)
    }

    @Test
    fun malformedInputNeverThrowsAndBackwardsEndsClose() {
        assertEquals(emptyList(), WebVttParser.parse(""))
        assertEquals(emptyList(), WebVttParser.parse("not a subtitle file at all"))
        val backwards = WebVttParser.parse(
            "WEBVTT\n\n00:00:05.000 --> 00:00:01.000\nBackwards\n",
        )
        assertEquals(5_000_000L, backwards.single().startMicros)
        assertEquals(
            5_000_000L + SubRipParser.OPEN_CUE_DEFAULT_MICROS,
            backwards.single().endMicros,
            "the open end resolves to the shared default when nothing follows",
        )
    }

    @Test
    fun `an identifier beginning with a keyword is a cue and not a block`() {
        // NOTEWORTHY is a cue identifier; the old startsWith read it as a NOTE block and ate the
        // cue that followed.
        val cues = WebVttParser.parse(
            """
            WEBVTT

            NOTEWORTHY
            00:00:01.000 --> 00:00:02.000
            Visible

            NOTE this really is a comment
            it runs to the blank line

            00:00:03.000 --> 00:00:04.000
            Second
            """.trimIndent(),
        )
        assertEquals(listOf("Visible", "Second"), cues.map { it.plainText })
    }

    @Test
    fun `entities decode after vtt tag stripping`() {
        val cue = WebVttParser.parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            <v Tom>Tom &amp; Jerry &lt;3</v>
            """.trimIndent(),
        ).single()
        assertEquals("Tom & Jerry <3", cue.plainText)
    }

    // WebVTT has no brace tags, so braces are text there, unlike in SubRip.
    @Test
    fun `braces stay text in WebVTT`() {
        val cue = WebVttParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n{\\an8}Literal\n").single()
        assertEquals("{\\an8}Literal", cue.plainText)
    }
}
