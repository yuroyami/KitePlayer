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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A live speed change on the audio path: the device never stops, the waveform never steps, and
 * the clock reports the media time the listener hears, before, during and after the change.
 */
class AudioPlaybackSpeedTest {

    private val rate = 48_000
    private val stereo = AudioFormat(sampleRate = rate, channels = 2, sampleFormat = SampleFormat.F32)

    /** A device pulled one 10 ms period at a time, whose block is audible one period later. */
    private class PeriodSink(private val clock: TestClock) : AudioSink {
        override val deviceBufferFrames: Int = 480
        private var render: AudioRenderCallback? = null
        private var buffer: Heard? = null
        var stops = 0
        var pauses = 0

        /** The left channel of every frame handed to the device, silence included. */
        val heard: MutableList<Float> = mutableListOf()

        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            this.render = render
            buffer = Heard(request, deviceBufferFrames)
            return request
        }

        fun period() {
            clock.advance(10.milliseconds)
            val destination = buffer ?: return
            render?.onRender(destination, deviceBufferFrames, clock.nanos() + 10_000_000L)
            for (frame in 0 until deviceBufferFrames) heard += destination.values[frame * 2]
        }

        override suspend fun start() = Unit
        override suspend fun stop() {
            stops++
        }
        override suspend fun drain() = Unit
        override suspend fun setPaused(paused: Boolean): Boolean {
            if (paused) pauses++
            return true
        }
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

    /** A quiet tone with a loud 1 ms burst every [markerMs] of media time, starting at [firstMarkerMs]. */
    private fun markedTone(seconds: Int, markerMs: Int = 250, firstMarkerMs: Int = 125): FloatArray {
        val frames = seconds * rate
        val out = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (0.15 * sin(2 * PI * 330.0 * frame / rate)).toFloat()
            out[frame * 2] = value
            out[frame * 2 + 1] = value
        }
        var marker = rate * firstMarkerMs / 1000
        while (marker + 48 < frames) {
            for (k in 0 until 48) {
                val value = (0.9 * exp(-k / 8.0) * if (k % 2 == 0) 1 else -1).toFloat()
                out[(marker + k) * 2] = value
                out[(marker + k) * 2 + 1] = value
            }
            marker += rate * markerMs / 1000
        }
        return out
    }

    /**
     * Plays [media] through [audio] in decoder-sized buffers, keeping the ring topped up, and calls
     * [beforeBuffer] with each buffer's media time so the test can change the speed there. After
     * every device period, [afterPeriod] sees the clock.
     */
    private suspend fun play(
        audio: AudioPlayback,
        sink: PeriodSink,
        media: FloatArray,
        beforeBuffer: (mediaUs: Long) -> Unit = {},
        afterPeriod: () -> Unit = {},
    ) {
        val bufferFrames = 1_024
        val total = media.size / 2
        var offset = 0
        while (offset < total) {
            // Keep about 100 ms queued. The device is pumped by this same loop, so a write into a
            // full ring would wait for ever, and a change from 2x to 0.5x releases about 120 ms at
            // once, the lookahead gathered for 2x: hence the deep ring.
            while (audio.buffered < 100.milliseconds && offset < total) {
                val frames = minOf(bufferFrames, total - offset)
                val mediaUs = offset * 1_000_000L / rate
                beforeBuffer(mediaUs)
                audio.submitDecoded(Pts(mediaUs), media.copyOfRange(offset * 2, (offset + frames) * 2), frames, stereo)
                offset += frames
            }
            sink.period()
            afterPeriod()
        }
        audio.finishDecoded()
        audio.endOfStream()
        repeat(40) {
            sink.period()
            afterPeriod()
        }
    }

    @Test
    fun theClockReadsTheMarkerTheListenerHearsAcrossSpeedChanges() = runTest {
        val clock = TestClock()
        val sink = PeriodSink(clock)
        val audio = AudioPlayback(sink, clock, bufferDuration = RING)
        audio.open(stereo)
        audio.play()
        val media = markedTone(seconds = 12)
        // What the clock said at the start of each heard period, and the rate it ran at.
        val readings = ArrayList<Pair<Long, Double>>()
        play(
            audio, sink, media,
            beforeBuffer = { mediaUs ->
                audio.speed = when (mediaUs / 1_000_000L) {
                    in 0L until 2L -> 1.0
                    in 2L until 4L -> 2.0
                    in 4L until 6L -> 0.5
                    in 6L until 8L -> 1.005
                    in 8L until 10L -> 0.75
                    else -> 1.0
                }
            },
            afterPeriod = {
                val snapshot = audio.clockSnapshot()
                readings += (snapshot.pts?.micros ?: -1L) to snapshot.speed
            },
        )

        // Find each burst the device played, and compare the media time the clock reported for
        // that moment with the burst's own media time.
        val heard = sink.heard
        var compared = 0
        var worst = 0L
        var lastBurst = -rate
        var markerIndex = 0
        for (frame in heard.indices) {
            if (abs(heard[frame]) < 0.6f) continue
            if (frame - lastBurst <= rate / 50) {
                lastBurst = frame
                continue
            }
            lastBurst = frame
            val period = frame / 480
            // The reading taken after period p is the media time at the start of period p's block.
            val (startUs, speed) = readings.getOrNull(period) ?: break
            if (startUs < 0) continue
            val readUs = startUs + ((frame % 480) * 1_000_000L / rate * speed).toLong()
            val markerUs = 125_000L + markerIndex * 250_000L
            markerIndex++
            val error = readUs - markerUs
            worst = maxOf(worst, abs(error))
            compared++
        }
        assertEquals(48, compared, "each of the 48 markers must be heard exactly once")
        assertTrue(worst <= 30_000, "the clock read up to ${worst / 1000} ms away from the marker being heard")
        assertEquals(0, sink.stops, "a live speed change stopped the device")
        assertEquals(0L, audio.underruns, "a live speed change ran the ring dry")
        audio.close()
    }

    @Test
    fun aLiveSpeedChangeLeavesNoGapAndNoStepInTheSound() = runTest {
        val clock = TestClock()
        val sink = PeriodSink(clock)
        val audio = AudioPlayback(sink, clock, bufferDuration = RING)
        audio.open(stereo)
        audio.play()
        val frames = 8 * rate
        val media = FloatArray(frames * 2) { index -> (0.4 * sin(2 * PI * 220.0 * (index / 2) / rate)).toFloat() }
        play(audio, sink, media, beforeBuffer = { mediaUs ->
            audio.speed = when ((mediaUs / 500_000L) % 4) {
                0L -> 1.0
                1L -> 0.995
                2L -> 1.5
                else -> 0.8
            }
        })
        val heard = sink.heard
        // From the first sound to the last: no silence inside, and no step larger than the tone's.
        val first = heard.indexOfFirst { it != 0f }
        val last = heard.indexOfLast { it != 0f }
        var largest = 0f
        var zeroRun = 0
        var longestZeroRun = 0
        for (index in first + 1..last) {
            largest = maxOf(largest, abs(heard[index] - heard[index - 1]))
            zeroRun = if (heard[index] == 0f) zeroRun + 1 else 0
            longestZeroRun = maxOf(longestZeroRun, zeroRun)
        }
        val natural = (0.4 * 2 * PI * 220.0 / rate).toFloat()
        assertTrue(largest <= natural * 1.05f, "a change stepped the waveform by $largest, the tone allows $natural")
        assertTrue(longestZeroRun < 3, "the sound stopped for $longestZeroRun frames during a change")
        assertEquals(0, sink.stops)
        audio.close()
    }

    @Test
    fun aSpeedChangeIsHeardOnlyAfterTheAudioAlreadyBuffered() = runTest {
        val clock = TestClock()
        val sink = PeriodSink(clock)
        val audio = AudioPlayback(sink, clock, bufferDuration = RING)
        audio.open(stereo)
        audio.play()
        val media = markedTone(seconds = 4)
        val rates = ArrayList<Double>()
        var changedAtPeriod = -1
        var period = 0
        play(
            audio, sink, media,
            beforeBuffer = { mediaUs ->
                if (mediaUs >= 1_000_000L && audio.speed == 1.0) {
                    audio.speed = 2.0
                    changedAtPeriod = period
                }
            },
            afterPeriod = {
                rates += audio.clockSnapshot().speed
                period++
            },
        )
        val firstFast = rates.indexOfFirst { it == 2.0 }
        assertTrue(changedAtPeriod >= 0 && firstFast > changedAtPeriod, "the rate changed before the change was asked for")
        // The ring held about 100 ms when the change came, so the device reaches it about that
        // much later, plus the stretch's own lookahead.
        val lagMs = (firstFast - changedAtPeriod) * 10
        assertTrue(lagMs in 60..220, "the new rate was heard $lagMs ms after the change, not after the buffered audio")
        audio.close()
    }

    @Test
    fun aPitchLawChangeMidStreamKeepsTheDeviceRunning() = runTest {
        val clock = TestClock()
        val sink = PeriodSink(clock)
        val audio = AudioPlayback(sink, clock, bufferDuration = RING)
        audio.speed = 1.5
        audio.open(stereo)
        audio.play()
        val media = markedTone(seconds = 4)
        val readings = ArrayList<Long>()
        play(
            audio, sink, media,
            beforeBuffer = { mediaUs -> audio.preservePitch = (mediaUs / 1_000_000L) % 2 == 0L },
            afterPeriod = { readings += audio.position()?.micros ?: -1L },
        )
        // The period that played the last sound reads the end of the media.
        val lastSound = sink.heard.indexOfLast { it != 0f } / 480
        val reading = readings[lastSound]
        assertTrue(reading in 3_950_000..4_050_000, "the clock read $reading us when the end of the media was heard")
        val first = sink.heard.indexOfFirst { it != 0f }
        var zeroRun = 0
        var longestZeroRun = 0
        for (index in first..sink.heard.indexOfLast { it != 0f }) {
            zeroRun = if (sink.heard[index] == 0f) zeroRun + 1 else 0
            longestZeroRun = maxOf(longestZeroRun, zeroRun)
        }
        assertTrue(longestZeroRun < 3, "the sound stopped for $longestZeroRun frames at a law change")
        assertEquals(0, sink.stops)
        assertEquals(0L, audio.underruns)
        audio.close()
    }

    @Test
    fun aTimestampGapAtSpeedMovesTheClockWhereTheGapIsHeard() = runTest {
        val clock = TestClock()
        val sink = PeriodSink(clock)
        val audio = AudioPlayback(sink, clock, bufferDuration = RING)
        audio.speed = 1.5
        audio.open(stereo)
        audio.play()
        // Two seconds of tone, then the same tone stamped half a second later: a gap in the file.
        val media = markedTone(seconds = 2)
        val bufferFrames = 1_024
        var offset = 0
        val total = media.size / 2
        val readings = ArrayList<Long>()
        while (offset < 2 * total) {
            while (audio.buffered < 100.milliseconds && offset < 2 * total) {
                val inside = offset % total
                val frames = minOf(bufferFrames, total - inside)
                val stampUs = offset * 1_000_000L / rate + if (offset >= total) 500_000L else 0L
                audio.submitDecoded(Pts(stampUs), media.copyOfRange(inside * 2, (inside + frames) * 2), frames, stereo)
                offset += frames
            }
            sink.period()
            readings += audio.position()?.micros ?: -1L
        }
        // At 1.5x the clock advances 15 ms per 10 ms period, except once, where it jumps the gap.
        val jumps = readings.zipWithNext { a, b -> b - a }.filter { it > 100_000 }
        assertEquals(1, jumps.size, "the gap moved the clock ${jumps.size} times")
        assertTrue(jumps.single() in 500_000..530_000, "the clock jumped ${jumps.single()} us over a 500 ms gap")
        audio.close()
    }

    private companion object {
        /** Deep enough for the largest burst one buffer can release, see [play]. */
        val RING = 500.milliseconds
    }
}
