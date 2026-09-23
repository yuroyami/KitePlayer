package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioResampler
import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The seam that replaces the engine's own rate conversion: `AudioConfig.resampler`, through the
 * player and [AudioPlayback], down to the conversion stage.
 */
class AudioResamplerSeamTest {

    private fun format(rate: Int) = AudioFormat(sampleRate = rate, channels = 2, sampleFormat = SampleFormat.F32)

    private fun tone(frames: Int, rate: Int) = FloatArray(frames * 2) { i ->
        (0.5 * sin(2.0 * PI * 440.0 * (i / 2) / rate)).toFloat()
    }

    /** Writes [MARK] for every frame the ratio allows, and [TAIL] when flushed, so the device can tell. */
    private class MarkingResampler(val inputRate: Int, val outputRate: Int, val channels: Int) : AudioResampler {
        var resets = 0
        var flushes = 0
        var closed = false

        override fun outputCapacity(inputFrames: Int): Int =
            (inputFrames.toLong() * outputRate / inputRate).toInt() + HELD

        override fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
            val produced = (frames.toLong() * outputRate / inputRate).toInt()
            output.fill(MARK, 0, produced * channels)
            return produced
        }

        override fun flush(output: FloatArray): Int {
            flushes++
            output.fill(TAIL, 0, HELD * channels)
            return HELD
        }

        override fun reset() {
            resets++
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingFactory : AudioResamplerFactory {
        val made = mutableListOf<MarkingResampler>()

        override fun create(inputRate: Int, outputRate: Int, channels: Int): AudioResampler =
            MarkingResampler(inputRate, outputRate, channels).also { made += it }
    }

    /** A device that accepts [rate] whatever it is asked for, pumped by hand, keeping what it heard. */
    private class PumpedSink(private val rate: Int) : AudioSink {
        override val deviceBufferFrames: Int = 512
        private var render: AudioRenderCallback? = null
        private var buffer: Heard? = null
        val heard: MutableList<Float> = mutableListOf()

        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            val accepted = request.copy(sampleRate = rate)
            this.render = render
            buffer = Heard(accepted, deviceBufferFrames)
            return accepted
        }

        /** Device periods until the ring has nothing more to give. */
        fun pumpAll() {
            val callback = render ?: return
            val destination = buffer ?: return
            while (true) {
                val written = callback.onRender(destination, deviceBufferFrames, 0L)
                if (written <= 0) return
                for (i in 0 until written * destination.format.channels) heard += destination.values[i]
            }
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
            source.copyInto(
                values,
                destinationFrameOffset * format.channels,
                sourceOffset,
                sourceOffset + frames * format.channels,
            )
        }

        override fun writePlane(
            channel: Int,
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ) {
            for (frame in 0 until frames) {
                values[(destinationFrameOffset + frame) * format.channels + channel] = source[sourceOffset + frame]
            }
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            values.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
        }
    }

    @Test
    fun `a configured resampler converts the rate instead of the engine's own sinc`() = runTest {
        val factory = RecordingFactory()
        val sink = PumpedSink(rate = 48_000)
        val audio = AudioPlayback(sink, TestClock(), resampler = factory)
        audio.open(format(44_100))
        audio.play()
        audio.submitDecoded(null, tone(2_205, 44_100), 2_205, format(44_100))
        sink.pumpAll()

        assertEquals(
            listOf(Triple(44_100, 48_000, 2)),
            factory.made.map { Triple(it.inputRate, it.outputRate, it.channels) },
        )
        assertTrue(sink.heard.isNotEmpty(), "the device heard nothing")
        assertTrue(
            sink.heard.all { it == MARK },
            "the device heard values the resampler never wrote: ${sink.heard.distinct().take(5)}",
        )
        audio.close()
    }

    @Test
    fun `matching rates never ask the factory`() = runTest {
        val factory = RecordingFactory()
        val sink = PumpedSink(rate = 44_100)
        val audio = AudioPlayback(sink, TestClock(), resampler = factory)
        audio.open(format(44_100))
        audio.play()
        audio.submitDecoded(null, tone(2_205, 44_100), 2_205, format(44_100))
        sink.pumpAll()

        assertEquals(0, factory.made.size, "equal rates need no conversion, so nothing should be made")
        assertTrue(sink.heard.any { it != 0f }, "the tone itself should have played")
        audio.close()
    }

    @Test
    fun `a factory that throws leaves the engine's sinc in place and is asked once`() = runTest {
        var asked = 0
        val refusing = AudioResamplerFactory { _, _, _ ->
            asked++
            error("no filter graph here")
        }
        val warnings = mutableListOf<PlaybackWarning>()
        val sink = PumpedSink(rate = 48_000)
        val audio = AudioPlayback(sink, TestClock(), onWarning = { warnings += it }, resampler = refusing)
        audio.open(format(44_100))
        audio.play()
        audio.submitDecoded(null, tone(2_205, 44_100), 2_205, format(44_100))
        // A new source rate builds a new conversion stage, which is where the factory would be asked again.
        audio.submitDecoded(null, tone(1_102, 22_050), 1_102, format(22_050))
        sink.pumpAll()

        assertEquals(1, asked, "a factory that refused was asked again")
        val refusals = warnings.filterIsInstance<PlaybackWarning.ResamplerUnavailable>()
        assertEquals(1, refusals.size, "one refusal, one warning: $warnings")
        assertTrue("no filter graph here" in refusals.single().detail, refusals.single().detail)
        assertTrue(sink.heard.any { it != 0f }, "the engine's own sinc should still have played the tone")
        audio.close()
    }

    @Test
    fun `a new source format closes the old resampler and close closes the last one`() = runTest {
        val factory = RecordingFactory()
        val sink = PumpedSink(rate = 48_000)
        val audio = AudioPlayback(sink, TestClock(), resampler = factory)
        audio.open(format(44_100))
        audio.play()
        audio.submitDecoded(null, tone(2_205, 44_100), 2_205, format(44_100))
        audio.submitDecoded(null, tone(1_102, 22_050), 1_102, format(22_050))

        assertEquals(listOf(44_100, 22_050), factory.made.map { it.inputRate })
        assertTrue(factory.made[0].closed, "the resampler for the old format was never closed")
        assertFalse(factory.made[1].closed, "the resampler in use was closed")
        audio.close()
        assertTrue(factory.made[1].closed, "closing the playback left its resampler open")
    }

    @Test
    fun `a flush resets the resampler`() = runTest {
        val factory = RecordingFactory()
        val sink = PumpedSink(rate = 48_000)
        val audio = AudioPlayback(sink, TestClock(), resampler = factory)
        audio.open(format(44_100))
        audio.play()
        audio.submitDecoded(null, tone(2_205, 44_100), 2_205, format(44_100))
        audio.flush(Generation.Initial.next())

        assertEquals(listOf(1), factory.made.map { it.resets })
        audio.close()
    }

    @Test
    fun `the end of the stream plays what the resampler held`() = runTest {
        val factory = RecordingFactory()
        val sink = PumpedSink(rate = 48_000)
        val audio = AudioPlayback(sink, TestClock(), resampler = factory)
        audio.open(format(44_100))
        audio.play()
        audio.submitDecoded(null, tone(2_205, 44_100), 2_205, format(44_100))
        audio.finishDecoded()
        sink.pumpAll()

        assertEquals(listOf(1), factory.made.map { it.flushes })
        assertEquals(HELD * 2, sink.heard.count { it == TAIL }, "the held tail never reached the device")
        audio.close()
    }

    @Test
    fun `without pitch correction the speed is folded into the input rate`() = runTest {
        val factory = RecordingFactory()
        val sink = PumpedSink(rate = 48_000)
        val audio = AudioPlayback(sink, TestClock(), resampler = factory)
        audio.speed = 1.5
        audio.preservePitch = false
        audio.open(format(44_100))
        audio.play()
        audio.submitDecoded(null, tone(2_205, 44_100), 2_205, format(44_100))

        assertEquals(listOf(66_150), factory.made.map { it.inputRate })
        audio.close()
    }

    @Test
    fun `a speed change without pitch correction replaces the resampler and closes the old one`() = runTest {
        val factory = RecordingFactory()
        val sink = PumpedSink(rate = 48_000)
        val audio = AudioPlayback(sink, TestClock(), resampler = factory)
        audio.preservePitch = false
        audio.open(format(44_100))
        audio.play()
        audio.submitDecoded(null, tone(2_205, 44_100), 2_205, format(44_100))
        audio.speed = 2.0
        audio.flush(Generation.Initial.next())
        audio.submitDecoded(null, tone(2_205, 44_100), 2_205, format(44_100))

        assertEquals(listOf(44_100, 88_200), factory.made.map { it.inputRate })
        assertTrue(factory.made[0].closed, "the resampler for the old speed was never closed")
        audio.close()
    }

    @Test
    fun `the player hands the configured factory to its audio path`() = runTest {
        val factory = RecordingFactory()
        val harness = CoreHarness(
            this,
            config = PlayerConfig(audio = AudioConfig(resampler = factory)),
            sinkAccepts = format(44_100),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)

        assertEquals(
            listOf(Triple(48_000, 44_100, 2)),
            factory.made.map { Triple(it.inputRate, it.outputRate, it.channels) },
        )
        harness.close()
        assertTrue(factory.made.single().closed, "closing the player left its resampler open")
    }

    @Test
    fun `the player stops asking a factory that refused and warns once`() = runTest {
        var asked = 0
        val refusing = AudioResamplerFactory { _, _, _ ->
            asked++
            error("no filter graph here")
        }
        val harness = CoreHarness(
            this,
            config = PlayerConfig(audio = AudioConfig(resampler = refusing)),
            sinkAccepts = format(44_100),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        // A second open builds a new audio path, which would ask the factory again.
        harness.core.stop()
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)

        assertEquals(1, asked, "a factory that refused was asked again")
        assertEquals(
            1,
            harness.core.warningHistory().count { it.warning is PlaybackWarning.ResamplerUnavailable },
            "one refusal, one warning",
        )
        harness.close()
    }

    private companion object {
        const val MARK = 0.25f
        const val TAIL = -0.125f
        const val HELD = 16
    }
}
