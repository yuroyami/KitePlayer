package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SincResampler
import io.github.yuroyami.kiteplayer.internal.TempoStage
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two audio hot paths against bounds of ten times their measured medians.
 *
 * Each block feeds ten seconds of stereo music in chunks of 1024 frames, the way the feeder does.
 */
class AudioHotPathTest {

    private val music = FloatArray(SECONDS * RATE * CHANNELS) { index ->
        val time = (index / CHANNELS).toDouble() / RATE
        (0.4 * sin(2 * PI * 220.0 * time) + 0.2 * sin(2 * PI * 440.0 * time) + 0.1 * sin(2 * PI * 660.0 * time)).toFloat()
    }

    /** Calls [consume] with each chunk of [music] and its frame count. */
    private inline fun inChunks(consume: (FloatArray, Int) -> Unit) {
        val chunk = FloatArray(CHUNK_FRAMES * CHANNELS)
        val totalFrames = music.size / CHANNELS
        var offset = 0
        while (offset < totalFrames) {
            val frames = minOf(CHUNK_FRAMES, totalFrames - offset)
            music.copyInto(chunk, 0, offset * CHANNELS, (offset + frames) * CHANNELS)
            consume(chunk, frames)
            offset += frames
        }
    }

    @Test
    fun `resampling ten seconds from 48000 Hz to 44100 Hz stays under its bound`() {
        var produced = 0
        hotPathGate("resample 10 s of stereo from 48000 Hz to 44100 Hz", RESAMPLE_BOUND_MS) {
            val resampler = SincResampler(sourceRate = RATE, targetRate = 44_100, channels = CHANNELS)
            val output = FloatArray(resampler.outputCapacity(CHUNK_FRAMES) * CHANNELS)
            produced = 0
            inChunks { chunk, frames -> produced += resampler.process(chunk, frames, output) }
        }
        // Not vacuous: a resampler that stopped producing would time an empty loop.
        assertTrue(produced in 440_000..441_000, "10 s at 44100 Hz is 441000 frames, less the lookahead; got $produced")
    }

    // MAX_SPEED, which is 4, is the fastest speed the stage accepts.
    @Test
    fun `tempo at its fastest speed over ten seconds stays under its bound`() {
        var emitted = 0L
        hotPathGate("tempo at 4x over 10 s of stereo at 48000 Hz", TEMPO_BOUND_MS) {
            val stage = TempoStage(channels = CHANNELS, sampleRate = RATE)
            stage.speed = TempoStage.MAX_SPEED
            inChunks { chunk, frames -> stage.process(chunk, frames) }
            stage.finish()
            emitted = stage.emittedFrames
        }
        // Not vacuous: 480000 frames at 4x is 120000, within about two pitch periods.
        assertTrue(emitted in 118_000L..122_000L, "4x of 480000 frames is 120000; got $emitted")
    }

    private companion object {
        const val SECONDS = 10
        const val RATE = 48_000
        const val CHANNELS = 2
        const val CHUNK_FRAMES = 1024

        // Ten times the median measured on an Apple M2 with JDK 21, which is in each comment.
        const val RESAMPLE_BOUND_MS = 450.0 // measured 43 to 49 ms
        const val TEMPO_BOUND_MS = 140.0 // measured 14 ms, once 24 ms
    }
}
