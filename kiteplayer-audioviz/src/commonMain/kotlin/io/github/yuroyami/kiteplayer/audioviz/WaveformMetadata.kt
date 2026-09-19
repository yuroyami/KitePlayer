package io.github.yuroyami.kiteplayer.audioviz

/** Projection of source channels into a decorative waveform. Speaker roles come from layout metadata. */
@AudioVizAuthoringApi
public enum class WaveformChannels {
    /** Arithmetic mean of all source channels; opposite polarities can cancel in this trace. */
    MeanAll,
    /** Source channels zero and one, or channel zero twice for a mono source. */
    FirstPair,
}

/** Exact contiguous samples represented by a triggered raw trace. No analysis resampling is applied. */
@AudioVizAuthoringApi
public class WaveformMetadata internal constructor(
    public val window: AnalysisWindow,
    /** First sample frame since the current analyser reset. */
    public val firstSampleIndex: Long,
    /** Displacement from the latest possible contiguous slice, zero or negative. */
    public val triggerOffsetSamples: Int,
    public val channels: WaveformChannels,
    public val sourceChannelCount: Int,
) {
    /** Raw source amplitude, nominal full scale one and sanitised headroom through +/-16. */
    public val sourceAmplitudeGain: Float get() = 1f
}
