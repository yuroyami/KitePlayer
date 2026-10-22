package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The gapless join on the audio path: the next queue item's samples continue the ring after the
 * last sample of the item before it, and the clock holds at that item's end until the owner
 * commits the join. See `docs/gapless-queue.md`.
 */
class AudioPlaybackJoinTest {

    private val stereo = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    /** A device pulled one 10 ms period at a time, with the block audible one period later. */
    private class PeriodSink(private val clock: TestClock) : AudioSink {
        override val deviceBufferFrames: Int = 480
        private var render: AudioRenderCallback? = null
        private var buffer: Heard? = null

        /** The left channel of every frame handed to the device, silence included. */
        val heard: MutableList<Float> = mutableListOf()

        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            this.render = render
            buffer = Heard(request, deviceBufferFrames)
            return request
        }

        /** Moves the clock one period and plays one block. */
        fun period() {
            clock.advance(10.milliseconds)
            val destination = buffer ?: return
            render?.onRender(destination, deviceBufferFrames, clock.nanos() + 10_000_000L)
            for (frame in 0 until deviceBufferFrames) heard += destination.values[frame * 2]
        }

        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun drain() = Unit
        override suspend fun setPaused(paused: Boolean): Boolean = true
        override fun latencyNanos(): Long = 0
        override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
        override val events: Flow<AudioSinkEvent> = emptyFlow()
        override fun close() = Unit
    }

    private class Heard(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
        val values = FloatArray(frames * format.channels)

        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
            source.copyInto(values, destinationFrameOffset * format.channels, sourceOffset, sourceOffset + frames * format.channels)
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            values.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
        }
    }

    private fun block(frames: Int, value: Float) = FloatArray(frames * 2) { value }

    @Test
    fun theNextItemPlaysRightAfterTheLastSampleAndTheClockHoldsUntilTheCommit() = runTest {
        val clock = TestClock()
        val sink = PeriodSink(clock)
        val audio = AudioPlayback(sink, clock)
        audio.open(stereo)
        audio.play()
        // 50 ms of the first item, then 50 ms of the next one, whose own timestamps start at zero.
        audio.submitDecoded(pts(0), block(2400, 0.25f), 2400, stereo)
        audio.beginJoin()
        audio.submitDecoded(pts(0), block(2400, 0.5f), 2400, stereo)

        var crossedAfter = -1
        for (period in 1..5) {
            sink.period()
            assertFalse(audio.joinCrossed, "the device is still in the first item after $period periods")
            val reading = assertNotNull(audio.position())
            assertTrue(reading.micros <= 50_000, "the first item never reads past its end: $reading")
        }
        for (period in 6..8) {
            sink.period()
            if (audio.joinCrossed) {
                crossedAfter = period
                break
            }
        }
        assertEquals(6, crossedAfter, "the clock reaches the first item's end one period after its last block")
        assertEquals(50_000, audio.position()?.micros, "the clock holds at the first item's end")
        sink.period()
        assertEquals(50_000, audio.position()?.micros, "and keeps holding until the owner commits")

        audio.commitJoin()
        val afterCommit = assertNotNull(audio.position()).micros
        assertTrue(afterCommit in 0..10_000, "the next item's own position starts within a period of zero: $afterCommit")
        sink.period()
        sink.period()
        val later = assertNotNull(audio.position()).micros
        assertTrue(later in 20_000..30_000, "the new item's clock then runs on its own timestamps: $later")

        // The tenth period takes the last of the 4800 frames written.
        sink.period()
        val played = sink.heard.takeWhile { it != 0f }
        assertEquals(List(2400) { 0.25f } + List(2400) { 0.5f }, played.take(4800), "no gap and nothing lost at the join")
        assertEquals(0, audio.underruns, "the device never ran dry across the join")
        audio.close()
    }

    @Test
    fun aFlushEndsTheJoinAndTimestampsReachTheClockUnshifted() = runTest {
        val clock = TestClock()
        val sink = PeriodSink(clock)
        val audio = AudioPlayback(sink, clock)
        audio.open(stereo)
        audio.play()
        audio.submitDecoded(pts(0), block(960, 0.25f), 960, stereo)
        audio.beginJoin()
        audio.submitDecoded(pts(0), block(960, 0.5f), 960, stereo)
        repeat(4) { sink.period() }
        assertTrue(audio.joinCrossed)
        audio.commitJoin()

        // A seek in the new item: a flush, then samples from one second in.
        audio.flush(Generation(1))
        audio.play()
        audio.submitDecoded(pts(1_000), block(2400, 0.75f), 2400, stereo)
        repeat(3) { sink.period() }
        val reading = assertNotNull(audio.position()).micros
        assertTrue(reading in 1_010_000..1_030_000, "after the flush the clock reads the item's own time: $reading")
        assertFalse(audio.joinCrossed)
        audio.close()
    }

    @Test
    fun aJoinAtTwiceTheSpeedHoldsAtTheEndAndStartsTheNextItemNearZero() = runTest {
        val clock = TestClock()
        val sink = PeriodSink(clock)
        val audio = AudioPlayback(sink, clock)
        audio.speed = 2.0
        audio.open(stereo)
        audio.play()
        // 100 ms of each item in media time, which plays in about 50 ms at twice the speed.
        audio.submitDecoded(pts(0), block(4800, 0.25f), 4800, stereo)
        audio.beginJoin()
        audio.submitDecoded(pts(0), block(4800, 0.5f), 4800, stereo)

        var crossed = false
        var heldAt = 0L
        for (period in 1..12) {
            sink.period()
            if (audio.joinCrossed) {
                crossed = true
                heldAt = assertNotNull(audio.position()).micros
                break
            }
        }
        assertTrue(crossed, "the device crossed into the next item")
        // The tempo stage keeps up to two pitch periods back, so the first item's end sits a few
        // tens of milliseconds of media time before its last input sample.
        assertTrue(heldAt in 40_000..100_000, "the clock holds at the first item's end: $heldAt")
        audio.commitJoin()
        val afterCommit = assertNotNull(audio.position()).micros
        assertTrue(afterCommit in 0..60_000, "the next item's position starts near zero: $afterCommit")
        audio.close()
    }
}
