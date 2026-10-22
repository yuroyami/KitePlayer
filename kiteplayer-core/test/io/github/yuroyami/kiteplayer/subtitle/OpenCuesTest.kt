package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenCuesTest {

    private fun image(startMicros: Long, endMicros: Long = SubtitleCue.OPEN_END): SubtitleCue =
        SubtitleCue.Bitmap(startMicros, endMicros, regions = emptyList())

    @Test
    fun anOpenCueEndsWhereTheNextLaterCueStarts() {
        val cues = mutableListOf(image(1_000_000), image(2_000_000), image(5_000_000))
        closeOpenCues(cues)
        assertEquals(listOf(2_000_000L, 5_000_000L, SubtitleCue.OPEN_END), cues.map { it.endMicros })
    }

    @Test
    fun cuesThatStartTogetherAreClosedByTheNextDistinctStart() {
        val cues = mutableListOf(image(1_000_000), image(1_000_000), image(3_000_000, endMicros = 4_000_000))
        closeOpenCues(cues)
        assertEquals(listOf(3_000_000L, 3_000_000L, 4_000_000L), cues.map { it.endMicros })
    }

    @Test
    fun aCueWithItsOwnEndIsLeftAlone() {
        val cues = mutableListOf(image(1_000_000, endMicros = 1_500_000), image(2_000_000))
        closeOpenCues(cues)
        assertEquals(listOf(1_500_000L, SubtitleCue.OPEN_END), cues.map { it.endMicros })
    }

    @Test
    fun anAppendedBatchClosesTheOpenCueBeforeIt() {
        val table = mutableListOf(image(1_000_000, endMicros = 2_000_000), image(2_000_000))
        val from = lastStartGroup(table)
        assertEquals(1, from)
        table += listOf(image(3_500_000), image(4_000_000, endMicros = 6_000_000))
        closeOpenCues(table, from)
        assertEquals(listOf(2_000_000L, 3_500_000L, 4_000_000L, 6_000_000L), table.map { it.endMicros })
    }

    @Test
    fun theLastGroupOfAnEmptyOrSingleStartTableIsItsStart() {
        assertEquals(0, lastStartGroup(emptyList()))
        assertEquals(0, lastStartGroup(listOf(image(1), image(1))))
    }
}
