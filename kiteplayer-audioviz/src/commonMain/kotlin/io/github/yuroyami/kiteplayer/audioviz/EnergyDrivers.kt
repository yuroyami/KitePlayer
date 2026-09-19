package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.log10
import kotlin.math.exp
import kotlin.math.ln

/** Ungated 400 ms K-weighted programme power, with its own availability and measurement time. */
@AudioVizAuthoringApi
public class ProgrammeLoudness internal constructor(
    public val availability: AnalysisAvailability,
    public val window: AnalysisWindow?,
    public val meanSquare: Double?,
    public val digitalSilence: Boolean,
) {
    /** LUFS, or null for unavailable data and zero power. No non-finite dB values are published. */
    public val lufs: Double? get() = meanSquare?.takeIf { it > 0.0 }?.let { -0.691 + 10 * log10(it) }
}

/** Shared-gain display heights: fast 10/120 ms, slow 100 ms/2 s, peak hold 500 ms then 12 dB/s. */
@AudioVizAuthoringApi
public class EnergyDriver internal constructor(
    public val fast: Float,
    public val slow: Float,
    public val peak: Float,
) {
    internal fun blend(other: EnergyDriver, mix: Float): EnergyDriver = EnergyDriver(
        fast + (other.fast - fast) * mix,
        slow + (other.slow - slow) * mix,
        peak + (other.peak - peak) * mix,
    )
}

/**
 * Immutable energy display drivers from one analysis. All receive [powerGain] before the same
 * fixed power-to-height curve. The raw measurements remain in [SpectrumFrame.power].
 */
@AudioVizAuthoringApi
public class EnergyDrivers internal constructor(
    public val overall: EnergyDriver,
    public val bass: EnergyDriver,
    public val mid: EnergyDriver,
    public val treble: EnergyDriver,
    private val bands: Array<EnergyDriver>,
    public val referencePower: Double,
    public val powerGain: Double,
    public val gainLimited: Boolean,
    public val handingOver: Boolean,
    /** Number of overall/range/band inputs above unity before height clipping this analysis. */
    public val saturatedDrivers: Int,
) {
    public val bandCount: Int get() = bands.size
    public fun band(index: Int): EnergyDriver = bands[index]

    internal fun blend(other: EnergyDrivers, mix: Float): EnergyDrivers = EnergyDrivers(
        overall.blend(other.overall, mix), bass.blend(other.bass, mix),
        mid.blend(other.mid, mix), treble.blend(other.treble, mix),
        if (bands.size == other.bands.size) Array(bands.size) { bands[it].blend(other.bands[it], mix) } else bands,
        exp(ln(referencePower) + (ln(other.referencePower) - ln(referencePower)) * mix),
        exp(ln(powerGain) + (ln(other.powerGain) - ln(powerGain)) * mix),
        gainLimited, handingOver, saturatedDrivers,
    )
}
