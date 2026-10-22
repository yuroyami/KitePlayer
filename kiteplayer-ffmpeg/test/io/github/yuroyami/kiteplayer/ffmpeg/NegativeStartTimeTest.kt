package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteffmpeg.StreamInfo
import io.github.yuroyami.kiteplayer.Pts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * A container whose timeline does not start at zero is normalized ONCE, and a container whose
 * timeline starts BEFORE zero is the case nothing covered.
 *
 * Negative start times are real: an MPEG-TS capture joined mid-stream, or an MP4 with an edit list
 * that trims the head. The existing coverage is a +1400 second offset on native only, and a
 * positive offset cannot catch a sign error, which is exactly what this shape is prone to.
 *
 * The subtraction is a shift, and applying a shift twice is the classic way to get it wrong, so
 * these also pin the two halves that keep it single: durations are never shifted at all, and a
 * timestamp mapped twice is visibly wrong by exactly the offset.
 */
class NegativeStartTimeTest {

    private companion object {
        /** A capture joined 200 ms before the zero mark: the whole timeline is negative at first. */
        const val NEGATIVE_START = -200_000L
        const val POSITIVE_START = 1_400_000_000L
    }

    @Test
    fun aContainerStartingBeforeZeroPutsItsOwnFirstTimestampAtTheOrigin() {
        val mapper = TimestampMapper(NEGATIVE_START)
        // The whole point of the shift: whatever the container calls its beginning is what the
        // player calls zero, sign regardless.
        assertEquals(Pts.Zero, mapper.mapTimestamp(NEGATIVE_START))
        // And a timestamp 40 ms into that container is 40 ms into the media, not 240 ms.
        assertEquals(Pts(40_000), mapper.mapTimestamp(NEGATIVE_START + 40_000))
        // Crossing the container's own zero is not special: it is simply 200 ms in.
        assertEquals(Pts(200_000), mapper.mapTimestamp(0))
    }

    @Test
    fun theSameRuleHoldsForZeroAndPositiveStarts() {
        assertEquals(Pts.Zero, TimestampMapper(0).mapTimestamp(0))
        assertEquals(Pts(40_000), TimestampMapper(0).mapTimestamp(40_000))
        assertEquals(Pts.Zero, TimestampMapper(POSITIVE_START).mapTimestamp(POSITIVE_START))
        assertEquals(Pts(40_000), TimestampMapper(POSITIVE_START).mapTimestamp(POSITIVE_START + 40_000))
    }

    @Test
    fun durationsAreNeverShiftedByTheStartTime() {
        // A duration is an interval, not a point. Shifting one would make every frame's length wrong
        // by the container's start, which for a negative start would make them longer.
        for (start in listOf(NEGATIVE_START, 0L, POSITIVE_START)) {
            val mapper = TimestampMapper(start)
            assertEquals(Pts(40_000), mapper.mapDuration(40_000), "duration shifted at start=$start")
            assertEquals(Pts.Zero, mapper.mapDuration(0), "duration shifted at start=$start")
        }
    }

    @Test
    fun anAbsentTimestampStaysAbsentRatherThanBecomingTheOrigin() {
        // Null in, null out. Turning an unset timestamp into zero would place an untimed frame at
        // the start of the media, which for a negative-start container is a real position.
        val mapper = TimestampMapper(NEGATIVE_START)
        assertNull(mapper.mapTimestamp(null))
        assertNull(mapper.mapDuration(null))
    }

    @Test
    fun mappingTwiceIsWrongByExactlyTheStartTime() {
        // The reason the decoders are handed the RAW packet rather than a mapped one: the frame that
        // comes back out is mapped, once. If a shifted timestamp were ever fed back through, the
        // error would be silent and would look like a stream that starts late. Stated as a test so
        // the size of that mistake is on the record.
        val mapper = TimestampMapper(NEGATIVE_START)
        val once = mapper.mapTimestamp(NEGATIVE_START + 40_000)!!
        val twice = mapper.mapTimestamp(once.micros)!!
        assertNotEquals(once, twice)
        assertEquals(-NEGATIVE_START, twice.micros - once.micros)
    }

    @Test
    fun aStreamsOwnStartTimeCrossesTheBoundaryNormalized() {
        // The mapper is not only used on packets: the stream's declared start goes through it too,
        // so a consumer reading StreamInfo.startTime sees the same timeline the frames arrive on.
        val stream = StreamInfo(
            index = 0,
            type = MediaType.Audio,
            codec = CodecId("aac"),
            timeBase = Rational(1, 1_000),
            durationMicros = 5_000_000,
            bitrateBps = 128_000,
            startTimeMicros = NEGATIVE_START,
        )
        val mapped = requireNotNull(stream.toPlayerStream(TimestampMapper(NEGATIVE_START)))
        assertEquals(Pts.Zero, mapped.startTime, "the stream's own start must land at the origin")

        // The same stream read through a mapper that does NOT know about the offset keeps the raw
        // negative value, which is what makes the assertion above a statement about the mapping
        // rather than about the fixture.
        val unmapped = requireNotNull(stream.toPlayerStream(TimestampMapper(0)))
        assertEquals(Pts(NEGATIVE_START), unmapped.startTime)
    }
}
