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
        assertEquals(5_000_000L, backwards.single().endMicros)
    }
}
