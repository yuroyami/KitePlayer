package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.AudioTap
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * The player's audio tap: every block it hears goes through a [SpectrumAnalyzer] into [timeline],
 * stamped with the time the block plays at.
 *
 * Blocks arrive on the player's feed thread, and only that thread touches the analyser or writes the
 * timeline. A discontinuity can arrive on another thread, so it is only counted there, and the next
 * block starts the analysis again before it is analysed.
 */
internal class AudioVizFeed : AudioTap {

    val timeline: SpectrumTimeline = SpectrumTimeline(TIMELINE_CAPACITY)

    @Volatile
    private var discontinuities = 0
    private var handled = 0

    private var analyzer: SpectrumAnalyzer? = null
    private var sampleRate = 0
    private var expectedMicros = -1L

    override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
        if (frames <= 0) return
        analyzerFor(pts.micros, format.sampleRate).feed(interleaved, frames, format.channels, pts.micros)
        expectedMicros = if (pts.micros < 0) -1L else pts.micros + frames * 1_000_000L / format.sampleRate
    }

    override fun onDiscontinuity() {
        discontinuities++
    }

    /** The analyser for this block, started again after a discontinuity, a jump in time or a new rate. */
    private fun analyzerFor(ptsMicros: Long, rate: Int): SpectrumAnalyzer {
        val seen = discontinuities
        val jumped = ptsMicros >= 0 && expectedMicros >= 0 && abs(ptsMicros - expectedMicros) > JUMP_MICROS
        val current = analyzer
        if (current != null && rate == sampleRate && seen == handled && !jumped) return current

        handled = seen
        timeline.clear()
        val fresh = if (current != null && rate == sampleRate) current.apply { reset() } else newAnalyzer(rate)
        analyzer = fresh
        sampleRate = rate
        return fresh
    }

    private fun newAnalyzer(rate: Int) = SpectrumAnalyzer(
        fftSize = FFT_SIZE,
        bandCount = BAND_COUNT,
        hop = HOP,
        scopePoints = SCOPE_POINTS,
        sampleRate = rate,
    ).apply { onAnalysis = timeline::push }
}

/** Bars in each analysis. */
internal const val BAND_COUNT = 56

/** Points in each analysis's oscilloscope trace. */
internal const val SCOPE_POINTS = 256

private const val FFT_SIZE = 2048
private const val HOP = 512

/** About three seconds of analyses at 44.1 kHz, more than any device buffers ahead of the sound. */
private const val TIMELINE_CAPACITY = 256

/** A step in time between two blocks bigger than this is a jump, such as a loop, rather than jitter. */
private const val JUMP_MICROS = 50_000L
