package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioPipeline
import io.github.yuroyami.kiteplayer.internal.SincResampler
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioResampler
import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Rate conversion at rates far from the usual ones: large downsampling steps, rates near the top
 * of an Int, and expansions whose buffers would not fit. Each either converts correctly or is
 * refused with a typed decoder failure before anything is allocated.
 */
class AudioConversionLimitsTest {

    private fun format(rate: Int, channels: Int = 2) =
        AudioFormat(sampleRate = rate, channels = channels, sampleFormat = SampleFormat.F32)

    /**
     * Feeds [buffers] buffers of [frames] frames of a constant 0.5 through [resampler], and
     * returns every frame that came out, first channel only.
     */
    private fun constantThrough(resampler: SincResampler, frames: Int, buffers: Int, channels: Int = 2): List<Float> {
        val input = FloatArray(frames * channels) { 0.5f }
        val output = FloatArray(resampler.outputCapacity(frames) * channels)
        val heard = mutableListOf<Float>()
        repeat(buffers) {
            val produced = resampler.process(input, frames, output)
            for (frame in 0 until produced) heard += output[frame * channels]
        }
        return heard
    }

    private fun assertRefused(expected: String, block: () -> Unit): PlaybackError.DecoderFailed {
        val refusal = assertFailsWith<PlaybackException> { block() }
        val error = assertIs<PlaybackError.DecoderFailed>(refusal.error)
        assertTrue(expected in error.detail, "expected '$expected' in: ${error.detail}")
        return error
    }

    @Test
    fun aDownsamplingStepLongerThanABufferDropsTheFramesItJumpsOver() {
        // 2.4 MHz to 8 kHz is a step of 300 frames, so every buffer of 100 lies inside one step.
        val resampler = SincResampler(sourceRate = 2_400_000, targetRate = 8_000, channels = 2)
        val heard = constantThrough(resampler, frames = 100, buffers = 3_000)
        // 300,000 frames in, one frame out for every 300 of them.
        assertTrue(abs(heard.size - 1_000) <= 2, "expected about 1000 frames, got ${heard.size}")
        // Past the fade-in at the start, the level of a constant signal survives.
        for (value in heard.drop(1)) assertEquals(0.5f, value, 1e-3f)
    }

    @Test
    fun aLongStepGivesTheSameOutputHoweverTheInputIsSplit() {
        // A slow sawtooth, so an output taken one frame away from its place reads another value.
        val frames = 90_000
        val input = FloatArray(frames * 2) { i -> ((i / 2) % 9_000) / 9_000f }
        fun heard(pieces: IntArray): List<Float> {
            val resampler = SincResampler(sourceRate = 2_400_000, targetRate = 8_000, channels = 2)
            val heard = mutableListOf<Float>()
            var at = 0
            var index = 0
            while (at < frames) {
                val size = minOf(pieces[index++ % pieces.size], frames - at)
                val output = FloatArray(resampler.outputCapacity(size) * 2)
                val produced = resampler.process(input.copyOfRange(at * 2, (at + size) * 2), size, output)
                for (frame in 0 until produced) heard += output[frame * 2]
                at += size
            }
            return heard
        }
        val whole = heard(intArrayOf(frames))
        val split = heard(intArrayOf(100, 7, 313, 1, 2_000, 299, 301))
        assertEquals(whole.size, split.size, "the same input must give the same number of frames")
        for (i in whole.indices) assertEquals(whole[i], split[i], 1e-6f, "frame $i differs")
    }

    @Test
    fun aSourceRateNearTheTopOfAnIntDownsamplesWithoutWrapping() {
        // One step is 44,739 frames and a fraction of 11,647 over 48,000. An Int sum of the two
        // parts passed the range of an Int on the first output.
        val resampler = SincResampler(sourceRate = Int.MAX_VALUE, targetRate = 48_000, channels = 2)
        val heard = constantThrough(resampler, frames = 50_000, buffers = 20)
        val expected = 20L * 50_000 * 48_000 / Int.MAX_VALUE
        assertTrue(abs(heard.size - expected) <= 2, "expected about $expected frames, got ${heard.size}")
        for (value in heard.drop(1)) assertEquals(0.5f, value, 1e-3f)
        assertTrue(resampler.outputCapacity(Int.MAX_VALUE) >= 0, "the capacity wrapped")
    }

    @Test
    fun theCapacityOfALowRateExpansionIsTheIntCeilingRatherThanAWrappedNumber() {
        val resampler = SincResampler(sourceRate = 1, targetRate = 48_000, channels = 8)
        assertEquals((1_024L + SincResampler.TAPS) * 48_000 + 2, resampler.outputCapacity(1_024).toLong())
        assertEquals(Int.MAX_VALUE, resampler.outputCapacity(100_000))
        assertEquals(Int.MAX_VALUE, resampler.outputCapacity(Int.MAX_VALUE))
    }

    @Test
    fun aFlushAfterALongStepDrainsWithoutReadingPastWhatIsHeld() {
        val resampler = SincResampler(sourceRate = 2_400_000, targetRate = 8_000, channels = 2)
        constantThrough(resampler, frames = 1_000, buffers = 3)
        val tail = FloatArray(resampler.outputCapacity(0) * 2)
        val drained = resampler.flush(tail)
        assertTrue(drained in 0..resampler.outputCapacity(0), "the flush wrote $drained frames")
    }

    @Test
    fun anExpansionWhoseBuffersWouldNotFitIsRefusedBeforeAnythingIsAllocated() {
        // One buffer of 32,768 frames at 1 Hz asks for room for 1.6 billion frames at 48 kHz.
        val pipeline = AudioPipeline(format(1), format(48_000))
        val error = assertRefused("the rate conversion from 1 Hz to 48000 Hz") {
            pipeline.process(FloatArray(32_768 * 2), 32_768)
        }
        assertTrue("the limit is ${AudioPipeline.MAX_STAGE_VALUES} values" in error.detail, error.detail)
        pipeline.close()
        // In 7.1, even a single frame needs more room than one stage may take.
        val surround = AudioPipeline(format(1, 8), format(48_000, 8))
        assertRefused("the rate conversion from 1 Hz to 48000 Hz") { surround.process(FloatArray(8), 1) }
        surround.close()
    }

    @Test
    fun aLargeLosslessBlockIn71At192KilohertzStillFits() {
        // A 65,535-frame block of 7.1 audio from 44.1 kHz to 192 kHz asks for about 2.3 Mi values.
        // Only the room is under test here, so the resampler reports the engine's own answer and
        // writes nothing.
        val sinc = SincResampler(sourceRate = 44_100, targetRate = 192_000, channels = 8)
        val capacity = sinc.outputCapacity(65_535)
        assertTrue(capacity * 8L in 2_000_000..AudioPipeline.MAX_STAGE_VALUES.toLong(), "capacity $capacity")
        val pipeline = pipelineWith(
            ScriptedResampler(capacity = sinc::outputCapacity, written = { 0 }),
            source = format(44_100, 8),
            target = format(192_000, 8),
        )
        assertEquals(0, pipeline.process(FloatArray(65_535 * 8), 65_535))
        pipeline.close()
    }

    @Test
    fun aDecoderFormatWithoutChannelsOrRateIsRefusedTyped() {
        assertRefused("the decoder reported 2 channels at 0 Hz") { AudioPipeline(format(0), format(48_000)) }
        assertRefused("the decoder reported 2 channels at -8000 Hz") { AudioPipeline(format(-8_000), format(48_000)) }
        assertRefused("the decoder reported 0 channels at 44100 Hz") {
            AudioPipeline(format(44_100, channels = 0), format(48_000))
        }
    }

    /** A resampler that answers [capacity] and writes [written] frames whatever it is given. */
    private class ScriptedResampler(val capacity: (Int) -> Int, val written: (Int) -> Int) : AudioResampler {
        override fun outputCapacity(inputFrames: Int): Int = capacity(inputFrames)
        override fun process(input: FloatArray, frames: Int, output: FloatArray): Int = written(frames)
        override fun flush(output: FloatArray): Int = written(0)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private fun pipelineWith(
        resampler: AudioResampler,
        source: AudioFormat = format(44_100),
        target: AudioFormat = format(48_000),
    ) = AudioPipeline(source, target, resamplerFactory = AudioResamplerFactory { _, _, _ -> resampler })

    @Test
    fun aCustomResamplerThatAsksForTooMuchOrNegativeRoomIsRefusedTyped() {
        for (answer in listOf(Int.MAX_VALUE, AudioPipeline.MAX_STAGE_VALUES, -1)) {
            val pipeline = pipelineWith(ScriptedResampler(capacity = { answer }, written = { 0 }))
            assertRefused("asks for room for $answer frames") { pipeline.process(FloatArray(1_024 * 2), 1_024) }
            pipeline.close()
        }
    }

    @Test
    fun aCustomResamplerThatWritesOutsideItsRoomIsRefusedTyped() {
        val tooMany = pipelineWith(ScriptedResampler(capacity = { it + 16 }, written = { it + 17 }))
        assertRefused("wrote 1041 frames into room for 1040") { tooMany.process(FloatArray(1_024 * 2), 1_024) }
        tooMany.close()
        val negative = pipelineWith(ScriptedResampler(capacity = { it + 16 }, written = { -1 }))
        assertRefused("wrote -1 frames into room for 1040") { negative.process(FloatArray(1_024 * 2), 1_024) }
        negative.close()
        // The end of the stream is checked the same way.
        val tail = pipelineWith(ScriptedResampler(capacity = { it + 16 }, written = { if (it == 0) 17 else it }))
        tail.process(FloatArray(1_024 * 2), 1_024)
        assertRefused("wrote 17 frames into room for 16") { tail.finish() }
        tail.close()
    }

    @Test
    fun thePlayerReportsARefusedConversionAsADecoderFailureNamingTheCodec() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(hasVideo = false, sampleRate = 1, audioBufferFrames = 32_768),
            sinkAccepts = format(48_000),
        )
        val opened = runCatching { harness.openWithRenderer() }
        runCatching { harness.core.play() }
        harness.run(1.seconds)

        val error = assertIs<PlaybackError.DecoderFailed>(
            harness.core.snapshots.value.error,
            "the player reported ${harness.core.snapshots.value.error}; open said ${opened.exceptionOrNull()}",
        )
        assertEquals("scripted-audio", error.codec)
        assertTrue("the rate conversion from 1 Hz to 48000 Hz" in error.detail, error.detail)
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        harness.close()
    }
}
