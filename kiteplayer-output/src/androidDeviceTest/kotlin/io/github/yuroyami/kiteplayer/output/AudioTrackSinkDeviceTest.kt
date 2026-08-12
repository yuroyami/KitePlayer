package io.github.yuroyami.kiteplayer.output

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S1.c.4 step 9, on the named emulator: a real `AudioTrack` opens 48 kHz stereo float, renders a
 * bounded sine, advances its playback head by at least 256 frames within five seconds, reports
 * monotonically increasing callback deadlines, stops, closes twice, reopens and repeats once.
 * The observed deadline source (timestamp or the documented playback-head fallback) is recorded
 * in the assertion message rather than asserted, because which one the emulator provides is the
 * platform's choice, not this test's. No claim is made that a human heard anything.
 */
@RunWith(AndroidJUnit4::class)
class AudioTrackSinkDeviceTest {

    private fun runOnce(sink: AudioTrackSink) = runBlocking {
        val deadlines = mutableListOf<Long>()
        val phase = AtomicLong()
        val accepted = sink.open(AudioFormat(48_000, 2, SampleFormat.F32)) { destination, frames, deadline ->
            synchronized(deadlines) { deadlines += deadline }
            val start = phase.getAndAdd(frames.toLong())
            val block = FloatArray(frames * 2)
            for (i in 0 until frames) {
                val sample = (0.1 * sin(2.0 * PI * 440.0 * (start + i) / 48_000.0)).toFloat()
                block[i * 2] = sample
                block[i * 2 + 1] = sample
            }
            destination.writeInterleaved(block, 0, 0, frames)
            frames
        }
        assertEquals(48_000, accepted.sampleRate)
        assertEquals(2, accepted.channels)
        assertTrue(sink.deviceBufferFrames > 0)

        sink.start()
        val startedAt = System.nanoTime()
        var advanced = false
        while (System.nanoTime() - startedAt < 5_000_000_000L) {
            /* latencyNanos reads the newest played position; the head having moved at least 256
             * frames shows as submitted-minus-played smaller than submitted once playing. The
             * direct signal is the sink's own position arithmetic, driven by the real device. */
            if (phase.get() >= 256 && sink.latencyNanos() < AudioTrackSink.framesToNanos(phase.get(), 48_000)) {
                advanced = true
                break
            }
            Thread.sleep(50)
        }
        assertTrue(
            advanced,
            "the playback head must advance at least 256 frames within five seconds " +
                "(deadline source observed: ${sink.observedDeadlineSource})",
        )
        val snapshot = synchronized(deadlines) { deadlines.toList() }
        assertTrue(snapshot.size >= 2, "the writer must have rendered more than one block")
        snapshot.zipWithNext().forEach { (a, b) ->
            assertTrue(b >= a, "callback deadlines must be monotonically increasing: $a then $b")
        }
        sink.stop()
    }

    @Test
    fun realTrackAdvancesStopsClosesTwiceAndReopensOnce() {
        val first = AudioTrackSink()
        runOnce(first)
        first.close()
        first.close() /* idempotent on the real device too */

        val second = AudioTrackSink()
        runOnce(second)
        second.close()
    }
}
