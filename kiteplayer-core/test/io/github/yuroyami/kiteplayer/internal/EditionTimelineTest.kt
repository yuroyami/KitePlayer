package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * How an ordered edition's parts are laid out and played (#501): which parts share one reading of
 * a file, which run holds a position, which chapters a viewer is shown, and which stream of a
 * linked file plays for the main file's.
 */
class EditionTimelineTest {

    private fun part(segment: String?, start: Int, end: Int, at: Int, chapter: Int, title: String? = null, hidden: Boolean = false) =
        TimelinePart(title, hidden, segment, start * 1_000_000L, end * 1_000_000L, at * 1_000_000L, chapter)

    @Test
    fun partsThatFollowEachOtherInOneFileShareARun() {
        val timeline = EditionTimeline(
            edition = 0,
            parts = listOf(
                part("op", 1, 4, 0, 0),
                part(null, 0, 3, 3, 1),
                part(null, 3, 5, 6, 2),
                part(null, 7, 9, 8, 3),
                part("ed", 0, 2, 10, 4),
            ),
        )
        assertEquals(
            listOf(Triple("op", 1, 4), Triple(null, 0, 5), Triple(null, 7, 9), Triple("ed", 0, 2)),
            timeline.runs.map { Triple(it.segmentUid, (it.startUs / 1_000_000L).toInt(), (it.endUs / 1_000_000L).toInt()) },
        )
        assertEquals(12_000_000L, timeline.lengthUs)
        // A run's place on the timeline, and the time of its file there.
        assertEquals(listOf(0L, 3_000_000L, 8_000_000L, 10_000_000L), timeline.runs.map { it.atUs })
        assertEquals(7_500_000L, timeline.runs[2].fileUsAt(8_500_000L))
    }

    @Test
    fun aPositionIsInTheRunThatStartsAtOrBeforeIt() {
        val timeline = EditionTimeline(0, listOf(part(null, 0, 3, 0, 0), part(null, 5, 8, 3, 1)))
        assertEquals(listOf(0, 0, 1, 1, 1), listOf(-1L, 0L, 3_000_000L, 4_999_999L, 6_000_000L).map(timeline::runAt))
    }

    @Test
    fun aViewerIsShownEachChapterOnceAndNoHiddenOne() {
        val timeline = EditionTimeline(
            edition = 0,
            parts = listOf(
                part(null, 0, 2, 0, 0, "Previously"),
                // Two ranges of another file's edition, which one chapter plays whole.
                part("film", 0, 3, 2, 1, "The film"),
                part("film", 5, 8, 5, 1, "The film"),
                part(null, 2, 3, 8, 2, "Credits", hidden = true),
            ),
        )
        assertEquals(
            listOf(Triple("Previously", 0.seconds, 2.seconds), Triple("The film", 2.seconds, 8.seconds)),
            timeline.chapters.map { Triple(it.title, it.start, it.end) },
        )
        assertEquals(listOf(0, 1), timeline.chapters.map { it.index })
    }

    private fun audio(index: Int, language: String?, title: String?) =
        PlayerStreamInfo(index = index, kind = TrackKind.Audio, codec = "aac", language = language, title = title)

    @Test
    fun aLinkedFilePlaysTheStreamThatMatchesByLanguageThenTitleThenPlace() {
        val linked = listOf(
            PlayerStreamInfo(index = 0, kind = TrackKind.Video, codec = "h264"),
            audio(1, "jpn", null),
            audio(2, "eng", "Commentary"),
            audio(3, "eng", "Main"),
        )
        fun match(stream: PlayerStreamInfo, position: Int) = matchingStream(StreamChoice.Like(stream, position), linked)?.index
        assertEquals(3, match(audio(5, "eng", "Main"), 0))
        assertEquals(2, match(audio(5, "eng", "Commentary"), 1))
        // Of the two English ones, the one at the same place among the sounds.
        assertEquals(3, match(audio(5, "eng", null), 2))
        assertEquals(1, match(audio(5, "jpn", "Other"), 2))
        // No language in common: the same place, or else the first.
        assertEquals(2, match(audio(5, "fre", null), 1))
        assertEquals(1, match(audio(5, "fre", null), 7))
        // No sound at all is silence.
        assertEquals(null, matchingStream(StreamChoice.Like(audio(5, "eng", null), 0), linked.take(1)))
    }
}
