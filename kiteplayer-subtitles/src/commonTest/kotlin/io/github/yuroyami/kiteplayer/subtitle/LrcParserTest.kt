package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** LRC synced lyrics as music players and lyrics services write them (#443). */
class LrcParserTest {

    private fun timed(text: String): List<Triple<Long, Long, String>> =
        LrcParser.parse(text).map { Triple(it.startMicros, it.endMicros, it.plainText) }

    @Test
    fun eachLineShowsUntilTheNextAndTheTagsDrawNothing() {
        val lyrics = "[ar:Somebody]\r\n[ti:A Song]\r\n[00:01.00]First line\r\n[00:03.50]Second line\r\n[00:06.25]Third line\r\n"
        assertEquals(
            listOf(
                Triple(1_000_000L, 3_500_000L, "First line"),
                Triple(3_500_000L, 6_250_000L, "Second line"),
                Triple(6_250_000L, 6_250_000L + LrcParser.LAST_LINE_MICROS, "Third line"),
            ),
            timed(lyrics),
        )
    }

    @Test
    fun aLineWithTwoStampsShowsAtEach() {
        val lyrics = "[00:01.00][00:05.00]Chorus\n[00:03.00]Verse\n[00:07.00]End"
        assertEquals(
            listOf(
                Triple(1_000_000L, 3_000_000L, "Chorus"),
                Triple(3_000_000L, 5_000_000L, "Verse"),
                Triple(5_000_000L, 7_000_000L, "Chorus"),
                Triple(7_000_000L, 7_000_000L + LrcParser.LAST_LINE_MICROS, "End"),
            ),
            timed(lyrics),
        )
    }

    /** A positive offset shows every line sooner, as the format's description and FFmpeg have it. */
    @Test
    fun theOffsetMovesEveryLine() {
        assertEquals(
            listOf(Triple(500_000L, 1_500_000L, "One"), Triple(1_500_000L, 1_500_000L + LrcParser.LAST_LINE_MICROS, "Two")),
            timed("[offset:+500]\n[00:01.00]One\n[00:02.00]Two"),
        )
        assertEquals(1_250_000L, LrcParser.parse("[offset:-250]\n[00:01.00]One").single().startMicros)
        assertEquals(0L, LrcParser.parse("[offset:2000]\n[00:01.00]One").single().startMicros, "a line moved before the start")
    }

    @Test
    fun everyStampFormReadsAsItsTime() {
        val lyrics = "[00:01]a\n[00:02.5]b\n[00:03.25]c\n[00:04.125]d\n[00:05:50]e\n[61:00.00]f"
        assertEquals(
            listOf(1_000_000L, 2_500_000L, 3_250_000L, 4_125_000L, 5_500_000L, 3_660_000_000L),
            LrcParser.parse(lyrics).map { it.startMicros },
        )
    }

    @Test
    fun theEnhancedFormsWordStampsAreDropped() {
        val lyrics = "[00:01.00]<00:01.00>Every <00:01.50>word <00:02.00>timed\n[00:03.00]Next"
        assertEquals("Every word timed", LrcParser.parse(lyrics).first().plainText)
    }

    @Test
    fun aStampWithNoTextEndsTheLineBeforeIt() {
        val lyrics = "[00:01.00]Sung\n[00:02.00]\n[00:09.00]Again"
        assertEquals(
            listOf(Triple(1_000_000L, 2_000_000L, "Sung"), Triple(9_000_000L, 9_000_000L + LrcParser.LAST_LINE_MICROS, "Again")),
            timed(lyrics),
        )
    }

    @Test
    fun theLastLineRunsToTheLengthTag() {
        assertEquals(
            listOf(Triple(1_000_000L, 200_000_000L, "Only")),
            timed("[length:03:20]\n[00:01.00]Only"),
        )
    }

    @Test
    fun linesOutOfOrderAreSortedAndLinesThatShareATimeShowTogether() {
        val lyrics = "[00:05.00]Later\n[00:01.00]Sung\n[00:01.00]Translated"
        assertEquals(
            listOf(
                Triple(1_000_000L, 5_000_000L, "Sung"),
                Triple(1_000_000L, 5_000_000L, "Translated"),
                Triple(5_000_000L, 5_000_000L + LrcParser.LAST_LINE_MICROS, "Later"),
            ),
            timed(lyrics),
        )
    }

    @Test
    fun bracketsInsideALineAreSung() {
        assertEquals("Oh (oh) [yeah]", LrcParser.parse("[00:01.00]Oh (oh) [yeah]").single().plainText)
    }

    @Test
    fun onlyLrcReadsAsLrc() {
        assertTrue(LrcParser.isLrc("﻿[ar:Somebody]\n[00:01.00]Line"))
        assertTrue(LrcParser.isLrc("\n\n[00:01.00]Line"))
        assertFalse(LrcParser.isLrc("1\n00:00:01,000 --> 00:00:02,000\nLine"))
        assertFalse(LrcParser.isLrc("WEBVTT\n\n00:01.000 --> 00:02.000\nLine"))
        assertFalse(LrcParser.isLrc("[Script Info]\nScriptType: v4.00+\n\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Line"))
        assertFalse(LrcParser.isLrc("[ar:Only tags]\n[ti:No lines]"))
        assertFalse(LrcParser.isLrc(""))
    }

    @Test
    fun malformedTextGivesNoCuesAndNeverThrows() {
        assertEquals(emptyList(), LrcParser.parse("[00:xx.00]not a time\n[00:01.00\n[]\n[:]x\n<00:01.00>"))
    }
}
