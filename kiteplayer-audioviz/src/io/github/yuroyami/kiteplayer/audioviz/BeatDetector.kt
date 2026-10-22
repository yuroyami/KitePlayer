package io.github.yuroyami.kiteplayer.audioviz

/**
 * Causal transient cues from maximum-filtered log-frequency flux.
 * Low/body/high refer to frequency regions, not instrument identification. The returned values
 * indicate detection; SpectrumAnalyzer computes event strength from shared-reference energy.
 * See docs/audioviz-onset-method.md for compression, lag and peak-picking conventions.
 */
internal class BeatDetector(
    private val binCount: Int,
    sampleRate: Int = 48_000,
    fftSize: Int = 2048,
    hop: Int = 480,
) {
    private val bank = LogFrequencyFilterbank(sampleRate, fftSize)
    private val difference = MaximumFilteredFlux(bank.size.coerceAtLeast(1), superFluxLag(fftSize, hop))
    private val memberships = Array(4) { kind ->
        (0 until bank.size).filter { index ->
            val hz = bank.centreHz(index)
            when (kind) {
                0 -> true
                1 -> hz in 40.0..150.0
                2 -> hz in 150.0..300.0 || hz in 1_000.0..5_000.0
                else -> hz in 6_000.0..16_000.0
            }
        }.toIntArray()
    }
    // Fixed offsets apply to mean log-filter growth, before the song's display gain.
    private val pickers = Array(4) { CausalFluxPeakPicker(hop.toDouble() / sampleRate, OFFSETS[it]) }
    private val detected = BooleanArray(4)

    var kick = 0f
        private set
    var snare = 0f
        private set
    var hat = 0f
        private set
    /** Detection evidence for the compatibility tracker, never a display-energy measurement. */
    var strength = 0f
        private set
    /** Ungated whole-spectrum positive change relative to the picker's past mean, 0..4. */
    var novelty = 0f
        private set

    fun confidence(kind: AudioEventKind): Float = picker(kind)?.confidence ?: 0f
    fun surprise(kind: AudioEventKind): Float = picker(kind)?.surprise ?: 0f

    private fun picker(kind: AudioEventKind): CausalFluxPeakPicker? = when (kind) {
        AudioEventKind.Onset -> pickers[0]
        AudioEventKind.LowTransient -> pickers[1]
        AudioEventKind.BodyTransient -> pickers[2]
        AudioEventKind.HighTransient -> pickers[3]
        AudioEventKind.EnergyRise, AudioEventKind.SectionBoundary, AudioEventKind.Drop, AudioEventKind.Breakdown -> null
    }

    fun feed(magnitudes: FloatArray, usableBins: Int): Float {
        require(usableBins == binCount && usableBins <= magnitudes.size)
        if (bank.size == 0) return 0f
        bank.measure(magnitudes)
        difference.feed(bank.values)
        for (kind in pickers.indices) {
            val selected = memberships[kind]
            var sum = 0.0
            for (index in selected) sum += difference.growth[index]
            val flux = if (selected.isEmpty()) 0f else (sum / selected.size).toFloat()
            detected[kind] = pickers[kind].feed(flux)
        }
        kick = if (detected[1]) 1f else 0f
        snare = if (detected[2]) 1f else 0f
        hat = if (detected[3]) 1f else 0f
        strength = if (detected[0]) pickers[0].confidence else 0f
        novelty = pickers[0].surprise * 4f
        return if (detected[0]) 1f else 0f
    }

    fun reset() {
        difference.reset()
        pickers.forEach { it.reset() }
        detected.fill(false)
        kick = 0f
        snare = 0f
        hat = 0f
        strength = 0f
        novelty = 0f
    }

    private companion object {
        val OFFSETS = floatArrayOf(0.012f, 0.080f, 0.014f, 0.010f)
    }
}
