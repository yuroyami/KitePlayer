package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Pts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * A live stream that a resume dated again from zero goes on from where the pause left it, plus the
 * time the pause took (#996). A stream that a resume dated forward keeps its own timestamps.
 */
class ResumeShiftTest {

    private val clock = TestTimeSource()
    private val mapper = TimestampMapper(containerStartMicros = 1_000_000)
    private val shift = ResumeShift(mapper, clock)

    /** Reads [count] packets of [stream], 20 ms apart, from [fromMicros] on. */
    private fun play(stream: Int, fromMicros: Long, count: Int = 50) {
        for (packet in 0 until count) {
            shift.read(stream, fromMicros + packet * 20_000L)
            clock += 20.milliseconds
        }
    }

    @Test
    fun aStreamDatedAgainFromZeroGoesOnFromTheLastTimestampPlusThePause() {
        play(stream = 0, fromMicros = 1_000_000)
        val lastMicros = 1_000_000L + 49 * 20_000L
        clock += 5.seconds
        shift.resumed()
        // The first packet after the resume is dated from zero, 20 ms and 5 s after the last read.
        shift.read(0, 0)
        assertEquals(Pts(lastMicros - 1_000_000 + 5_020_000), mapper.mapTimestamp(0, 0))
        // Every later packet and frame of the stream moves by the same amount.
        shift.read(0, 20_000)
        assertEquals(Pts(lastMicros - 1_000_000 + 5_040_000), mapper.mapTimestamp(20_000, 0))
    }

    @Test
    fun aStreamThatGoesOnForwardKeepsItsOwnTimestamps() {
        play(stream = 0, fromMicros = 1_000_000)
        clock += 5.seconds
        shift.resumed()
        // A PLAY reply that dated the stream at the live edge.
        shift.read(0, 7_000_000)
        assertEquals(emptyMap(), mapper.resumeShifts)
        assertEquals(Pts(6_000_000), mapper.mapTimestamp(7_000_000, 0))
    }

    @Test
    fun onlyTheFirstPacketAfterAResumeIsChecked() {
        play(stream = 0, fromMicros = 1_000_000)
        shift.resumed()
        shift.read(0, 3_000_000)
        // A later step back is the stream's own and stays as it is.
        shift.read(0, 2_000_000)
        assertEquals(emptyMap(), mapper.resumeShifts)
    }

    @Test
    fun eachStreamMovesByItsOwnGap() {
        play(stream = 0, fromMicros = 1_000_000)
        play(stream = 1, fromMicros = 1_000_000, count = 10)
        clock += 2.seconds
        shift.resumed()
        shift.read(0, 0)
        shift.read(1, 5_000_000)
        assertEquals(setOf(0), mapper.resumeShifts.keys)
        // A stream never read before the pause has nothing to go on from.
        shift.read(2, 0)
        assertEquals(setOf(0), mapper.resumeShifts.keys)
    }

    @Test
    fun aSecondResumeGoesOnFromTheShiftedTimeline() {
        play(stream = 0, fromMicros = 1_000_000, count = 1)
        clock += 1.seconds
        shift.resumed()
        shift.read(0, 0)
        val firstShift = mapper.resumeShifts.getValue(0)
        val shownAfterFirst = mapper.mapTimestamp(0, 0)!!.micros
        clock += 3.seconds
        shift.resumed()
        shift.read(0, 0)
        assertEquals(shownAfterFirst + 3_000_000, mapper.mapTimestamp(0, 0)!!.micros)
        assertEquals(firstShift + 3_000_000, mapper.resumeShifts.getValue(0))
    }

    @Test
    fun aPacketWithoutATimestampLeavesTheCheckForTheNextOne() {
        play(stream = 0, fromMicros = 1_000_000, count = 1)
        clock += 1.seconds
        shift.resumed()
        shift.read(0, null)
        shift.read(0, 0)
        assertEquals(setOf(0), mapper.resumeShifts.keys)
    }
}
