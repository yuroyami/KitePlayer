package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The dates of an HLS stream's segments, and the time of day of its positions (#444). */
class HlsTimeOfDayTest {

    /** 2026-10-07T21:34:00Z in microseconds. */
    private val origin = 1_791_408_840_000_000L

    private val base = "https://cdn.test/live/video.m3u8"

    /** A media playlist from [first], one two second segment each, with a date on the segments [dated] names. */
    private fun playlist(first: Long, count: Int, dated: Map<Long, String>, ended: Boolean = false, discontinuityAt: Long? = null) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        for (sequence in first until first + count) {
            if (sequence == discontinuityAt) append("#EXT-X-DISCONTINUITY\n")
            dated[sequence]?.let { append("#EXT-X-PROGRAM-DATE-TIME:$it\n") }
            append("#EXTINF:2.000,\nsegment-$sequence.ts\n")
        }
        if (ended) append("#EXT-X-ENDLIST\n")
    }

    private fun address(sequence: Long) = "https://cdn.test/live/segment-$sequence.ts"

    @Test
    fun theDatesOfAPlaylistCarryFromATaggedSegmentToTheNext() {
        val read = assertNotNull(readDatedPlaylist(playlist(10, 4, mapOf(10L to "2026-10-07T21:34:00.000Z")), base))
        assertEquals(listOf(10L, 11L, 12L, 13L), read.segments.map { it.sequence })
        assertEquals(listOf(0L, 2L, 4L, 6L).map { origin + it * 1_000_000 }, read.segments.map { it.dateMicros })
        assertEquals(address(12), read.segments[2].address)
        assertEquals(2_000_000L, read.segments[2].durationMicros)
    }

    @Test
    fun aDiscontinuityWithoutItsOwnDateHasNone() {
        val text = playlist(0, 4, mapOf(0L to "2026-10-07T21:34:00Z", 3L to "2026-10-07T22:00:00Z"), discontinuityAt = 2)
        val dates = assertNotNull(readDatedPlaylist(text, base)).segments.map { it.dateMicros }
        assertEquals(listOf(origin, origin + 2_000_000, null, origin + 26 * 60_000_000L), dates)
    }

    @Test
    fun aMasterPlaylistAndTextThatIsNoPlaylistGiveNoSegments() {
        assertNull(readDatedPlaylist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvideo.m3u8\n", base))
        assertNull(readDatedPlaylist("segment-0.ts\n", base))
    }

    @Test
    fun theDateFormsPackagersWriteAreRead() {
        assertEquals(origin, parseProgramDateTimeMicros("2026-10-07T21:34:00Z"))
        assertEquals(origin + 123_000, parseProgramDateTimeMicros("2026-10-07T21:34:00.123Z"))
        assertEquals(origin + 123_456, parseProgramDateTimeMicros("2026-10-07T21:34:00.123456789Z"))
        assertEquals(origin, parseProgramDateTimeMicros("2026-10-07T23:34:00+02:00"))
        assertEquals(origin, parseProgramDateTimeMicros("2026-10-07T23:34:00+0200"))
        assertEquals(origin, parseProgramDateTimeMicros("2026-10-07T16:34:00-05:00"))
        assertEquals(origin, parseProgramDateTimeMicros("2026-10-07T21:34:00"))
        assertNull(parseProgramDateTimeMicros("yesterday"))
        assertNull(parseProgramDateTimeMicros("2026-13-07T21:34:00Z"))
    }

    @Test
    fun theFirstSegmentFFmpegOpensStartsThePositions() {
        val times = HlsTimeOfDay()
        // A live join: FFmpeg starts three segments from the end, at 12.
        times.read(base, playlist(10, 5, mapOf(10L to "2026-10-07T21:34:00Z")))
        assertNull(times.timeOfDayAt(0), "nothing is known before a segment opens")
        times.opened(base)
        times.opened(address(12))
        assertEquals(origin + 4_000_000, times.timeOfDayAt(0))
        assertEquals(origin + 7_500_000, times.timeOfDayAt(3_500_000))
        assertEquals(3_500_000L, times.positionAt(origin + 7_500_000))
        assertNull(times.positionAt(origin + 1_000_000), "a moment before the first segment that opened has no position")
        assertEquals(origin..origin + 10_000_000, times.span())
    }

    @Test
    fun aReloadExtendsThePositionsAndALateOneBridgesItsGapByTheDates() {
        val times = HlsTimeOfDay()
        times.read(base, playlist(10, 3, mapOf(10L to "2026-10-07T21:34:00Z")))
        times.opened(address(10))
        // The next reload lists 11 to 13.
        times.read(base, playlist(11, 3, mapOf(11L to "2026-10-07T21:34:02Z")))
        assertEquals(origin + 7_000_000, times.timeOfDayAt(7_000_000))
        // A late reload: 14 to 16 left the playlist before anyone saw them.
        times.read(base, playlist(17, 3, mapOf(17L to "2026-10-07T21:34:14Z")))
        assertEquals(origin + 15_000_000, times.timeOfDayAt(15_000_000))
        assertEquals(15_000_000L, times.positionAt(origin + 15_000_000))
        assertNull(times.timeOfDayAt(10_000_000), "the gap the reload left has no date")
        assertEquals(origin + 14_000_000..origin + 20_000_000, times.span(), "the span is the latest playlist's")
    }

    @Test
    fun anEndedPlaylistWithNoOpensToReportStartsAtItsFirstSegment() {
        val ended = HlsTimeOfDay()
        ended.read(base, playlist(0, 3, mapOf(0L to "2026-10-07T21:34:00Z"), ended = true))
        ended.anchorAtStart(base)
        assertEquals(origin + 5_000_000, ended.timeOfDayAt(5_000_000))
        assertEquals(6_000_000L, ended.positionAt(origin + 6_000_000), "the end of the last segment is a position")

        // A live playlist starts where only an open can say.
        val live = HlsTimeOfDay()
        live.read(base, playlist(0, 3, mapOf(0L to "2026-10-07T21:34:00Z")))
        live.anchorAtStart(base)
        assertNull(live.timeOfDayAt(0))
    }

    @Test
    fun aPlaylistWithoutDatesStatesNothing() {
        val times = HlsTimeOfDay()
        times.read(base, playlist(0, 3, emptyMap()))
        times.opened(address(0))
        assertNull(times.timeOfDayAt(0))
        assertNull(times.positionAt(origin))
        assertNull(times.span())
    }
}
