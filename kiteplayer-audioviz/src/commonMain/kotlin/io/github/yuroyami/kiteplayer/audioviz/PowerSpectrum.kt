package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.ChannelLayout

/** Availability of a feature measurement. Ready silence has zero power; it is not unavailable. */
@AudioVizAuthoringApi
public enum class AnalysisAvailability { Unavailable, WarmingUp, Ready }

/** A complete sample window on the source media timeline. Null times mean unknown, not zero. */
@AudioVizAuthoringApi
public class AnalysisWindow internal constructor(
    public val startMicros: Long?,
    public val endMicros: Long?,
    public val referenceMicros: Long?,
    public val sampleRate: Int,
    public val sampleCount: Int,
) {
    public val durationSeconds: Double get() = sampleCount.toDouble() / sampleRate
    public val hasTimestamp: Boolean get() = referenceMicros != null
}

/**
 * Immutable unweighted spectral power from a complete symmetric Hann window.
 *
 * Values are linear mean-square power per FFT bin or integrated band, relative to nominal
 * full-scale amplitude one. They are not dB, power per Hz, display height or a loudness estimate.
 * Channel powers are averaged equally, including LFE. Opposite channel polarities cannot cancel.
 * [totalMeanSquare] is the window-weighted mean-square power across the entire Nyquist range.
 * Bands cover 30 Hz to min(16 kHz, 0.95 Nyquist), so their sum normally excludes some of that power.
 * The separately timed [window] remains attached even when a drawing interpolates display drivers.
 */
@AudioVizAuthoringApi
public class PowerSpectrum internal constructor(
    public val window: AnalysisWindow,
    public val generation: Generation,
    public val analysisRevision: Long,
    public val channelCount: Int,
    public val channelLayout: ChannelLayout,
    public val channelLayoutMask: Long?,
    public val totalMeanSquare: Float,
    private val bins: FloatArray,
    private val bands: FloatArray,
    private val edges: DoubleArray,
) {
    public val fftSize: Int get() = window.sampleCount
    public val binCount: Int get() = bins.size
    public val bandCount: Int get() = bands.size

    public fun binMeanSquare(index: Int): Float = bins[index]
    public fun bandMeanSquare(index: Int): Float = bands[index]
    public fun bandLowHz(index: Int): Double = edges[index.also { require(it in bands.indices) }]
    public fun bandHighHz(index: Int): Double = edges[index.also { require(it in bands.indices) } + 1]
    public fun copyBinPowers(): FloatArray = bins.copyOf()
    public fun copyBandPowers(): FloatArray = bands.copyOf()
    public fun copyBandEdgesHz(): DoubleArray = edges.copyOf()
}
