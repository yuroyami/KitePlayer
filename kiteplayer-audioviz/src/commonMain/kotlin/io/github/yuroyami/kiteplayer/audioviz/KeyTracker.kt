package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pitch-class profile and key from a separately timed long window.
 *
 * Every 200 ms of input it measures the power of the first two channels over the newest
 * [windowSize] samples, picks spectral peaks, estimates tuning and folds the peaks into twelve
 * pitch classes with harmonic weighting. The key comes from correlating a slow average of pitched
 * profiles with the Krumhansl-Kessler key profiles. See docs/audioviz-structure-api.md.
 */
internal class KeyTracker(private val sampleRate: Int) {
    val windowSize: Int = longWindowSize(sampleRate)

    /** Short-term profile. Each pitched frame peaks at one; unpitched audio lets it decay to zero. */
    val chroma: FloatArray = FloatArray(12)
    /** Whether the newest long window was pitched. */
    var pitched: Boolean = false
        private set
    var key: KeyEstimate? = null
        private set
    /** Estimated tuning offset from A4 = 440 Hz, in cents. */
    var tuningCents: Float = 0f
        private set

    private val rings = arrayOf(FloatArray(windowSize), FloatArray(windowSize))
    private var writeIndex = 0
    private var samplesSeen = 0L
    private var sinceAnalysis = 0L
    private val analysisSamples = (sampleRate * FRAME_SECONDS).roundToInt().coerceAtLeast(1)
    private val power = SpectralPower(windowSize, sampleRate, 1)
    private val binHz = sampleRate.toDouble() / windowSize
    private val lowBin = ceil(LOW_HZ / binHz).toInt().coerceAtLeast(2)
    private val highBin = floor(minOf(HIGH_HZ, 0.45 * sampleRate) / binHz).toInt().coerceAtMost(windowSize / 2 - 2)
    private val levels = DoubleArray(windowSize / 2 + 1)
    private val neighbourhood = DoubleArray(2 * NEIGHBOUR_BINS + 1)
    private val peakHz = DoubleArray(MAX_PEAKS)
    private val peakAmplitude = DoubleArray(MAX_PEAKS)
    private val frame = DoubleArray(12)
    private val fast = DoubleArray(12)
    private val slow = DoubleArray(12)
    private var evidenceSeconds = 0.0
    private var unpitchedSeconds = 0.0
    private var tuningX = 0.0
    private var tuningY = 0.0

    /** One input sample frame from the first two channels; mono passes the same value twice. */
    fun push(left: Float, right: Float) {
        rings[0][writeIndex] = left
        rings[1][writeIndex] = right
        if (++writeIndex == windowSize) writeIndex = 0
        samplesSeen++
        sinceAnalysis++
    }

    /**
     * Called once per analysis with the media time through which input was consumed, or null when
     * it is unknown. Answers true when a new long window was analysed.
     */
    fun analyse(availableMicros: Long?): Boolean {
        if (samplesSeen < windowSize || sinceAnalysis < analysisSamples) return false
        sinceAnalysis = 0
        power.measure(rings, writeIndex)
        pitched = measureFrame()
        val fastShare = 1.0 - exp(-FRAME_SECONDS / FAST_SECONDS)
        for (note in 0 until 12) {
            fast[note] += ((if (pitched) frame[note] else 0.0) - fast[note]) * fastShare
            chroma[note] = fast[note].toFloat()
        }
        if (pitched) {
            val slowShare = 1.0 - exp(-FRAME_SECONDS / KEY_SECONDS)
            for (note in 0 until 12) slow[note] += (frame[note] - slow[note]) * slowShare
            evidenceSeconds = (evidenceSeconds + FRAME_SECONDS).coerceAtMost(60.0)
            unpitchedSeconds = 0.0
        } else {
            // Counted in frames, so evidence expires even when timestamps are unknown.
            unpitchedSeconds += FRAME_SECONDS
            if (unpitchedSeconds > EXPIRY_SECONDS) forgetKey()
        }
        decide(availableMicros)
        return true
    }

    /** Fills [frame] with this window's normalised pitch-class profile. Answers whether it is pitched. */
    private fun measureFrame(): Boolean {
        frame.fill(0.0)
        val bins = power.bins
        var total = 0.0
        for (bin in lowBin..highBin) total += bins[bin]
        if (total <= SILENT_POWER) return false
        for (bin in lowBin - 1..highBin + 1) levels[bin] = 10.0 * log10(bins[bin] + 1e-20)
        var loudest = Double.NEGATIVE_INFINITY
        for (bin in lowBin..highBin) {
            if (levels[bin] > levels[bin - 1] && levels[bin] >= levels[bin + 1]) loudest = maxOf(loudest, levels[bin])
        }
        var peaks = 0
        var peakPower = 0.0
        for (bin in lowBin..highBin) {
            val level = levels[bin]
            if (!(level > levels[bin - 1] && level >= levels[bin + 1]) || level < loudest - RANGE_DB) continue
            if (level < medianAround(bin) + PEAK_DB) continue
            val before = levels[bin - 1]
            val after = levels[bin + 1]
            val curvature = before - 2 * level + after
            val shift = if (curvature < -1e-9) (0.5 * (before - after) / curvature).coerceIn(-0.5, 0.5) else 0.0
            val peakLevel = level - 0.25 * (before - after) * shift
            peakPower += bins[bin - 1] + bins[bin] + bins[bin + 1]
            if (peaks < MAX_PEAKS) {
                peakHz[peaks] = (bin + shift) * binHz
                peakAmplitude[peaks] = sqrt(10.0.pow(peakLevel / 10.0))
                peaks++
            }
        }
        if (peaks == 0 || peakPower < PITCHED_SHARE * total) return false

        // Tuning: the amplitude-weighted circular mean of each peak's distance to the semitone grid.
        var x = 0.0
        var y = 0.0
        for (index in 0 until peaks) {
            val hz = peakHz[index]
            if (hz !in TUNING_LOW_HZ..TUNING_HIGH_HZ) continue
            val semitones = 12.0 * ln(hz / 440.0) / LN2
            val angle = 2 * PI * (semitones - kotlin.math.round(semitones))
            x += peakAmplitude[index] * cos(angle)
            y += peakAmplitude[index] * sin(angle)
        }
        val keep = exp(-FRAME_SECONDS / TUNING_SECONDS)
        tuningX = tuningX * keep + x
        tuningY = tuningY * keep + y
        val tuning = if (tuningX == 0.0 && tuningY == 0.0) 0.0 else atan2(tuningY, tuningX) / (2 * PI)
        tuningCents = (tuning * 100.0).toFloat()

        // Each peak may be harmonic h of a fundamental at f / h, weighted 0.6^(h-1), and spreads
        // over its nearest pitch classes with a squared-cosine window four-thirds of a semitone wide.
        for (index in 0 until peaks) {
            var weight = peakAmplitude[index]
            for (harmonic in 1..4) {
                val fundamental = peakHz[index] / harmonic
                if (fundamental < LOWEST_FUNDAMENTAL_HZ) break
                val midi = 12.0 * ln(fundamental / 440.0) / LN2 + 69.0 - tuning
                val nearest = kotlin.math.round(midi).toInt()
                for (note in nearest - 1..nearest + 1) {
                    val distance = abs(midi - note)
                    if (distance > HALF_WIDTH) continue
                    val shape = cos(PI / 2 * distance / HALF_WIDTH)
                    frame[((note % 12) + 12) % 12] += weight * shape * shape
                }
                weight *= HARMONIC_DECAY
            }
        }
        val largest = frame.max()
        if (largest <= 0.0) return false
        for (note in 0 until 12) frame[note] /= largest
        return true
    }

    private fun medianAround(bin: Int): Double {
        val from = maxOf(lowBin - 1, bin - NEIGHBOUR_BINS)
        val to = minOf(highBin + 1, bin + NEIGHBOUR_BINS)
        val count = to - from + 1
        for (index in 0 until count) neighbourhood[index] = levels[from + index]
        neighbourhood.sort(0, count)
        return neighbourhood[count / 2]
    }

    private fun decide(availableMicros: Long?) {
        if (evidenceSeconds < MIN_EVIDENCE_SECONDS) {
            key = null
            return
        }
        var mean = 0.0
        for (note in 0 until 12) mean += slow[note]
        mean /= 12
        var norm = 0.0
        for (note in 0 until 12) norm += (slow[note] - mean) * (slow[note] - mean)
        if (norm <= 1e-12) {
            key = null
            return
        }
        norm = sqrt(norm)
        val scores = DoubleArray(24) { candidate ->
            val tonic = candidate % 12
            val profile = if (candidate < 12) MAJOR_PROFILE else MINOR_PROFILE
            var dot = 0.0
            for (note in 0 until 12) dot += (slow[note] - mean) / norm * profile[((note - tonic) % 12 + 12) % 12]
            dot
        }
        // The slow average is what keeps the key steady; a key that does not clearly lead is unknown.
        val chosen = scores.indices.maxBy { scores[it] }
        val correlation = scores[chosen]
        val lead = correlation - scores.indices.filter { it != chosen }.maxOf { scores[it] }
        if (correlation < MIN_CORRELATION || lead < MIN_LEAD) {
            key = null
            return
        }
        val confidence = minOf(
            ((correlation - MIN_CORRELATION) / (FULL_CORRELATION - MIN_CORRELATION)).coerceIn(0.0, 1.0),
            ((lead - MIN_LEAD) / (FULL_LEAD - MIN_LEAD)).coerceIn(0.0, 1.0),
        ).toFloat()
        val window = AnalysisWindow(
            availableMicros?.let { it - windowSize * 1_000_000L / sampleRate },
            availableMicros,
            availableMicros?.let { it - windowSize * 500_000L / sampleRate },
            sampleRate, windowSize,
        )
        key = KeyEstimate(chosen % 12, if (chosen < 12) KeyMode.Major else KeyMode.Minor, confidence,
            correlation.toFloat(), lead.toFloat(), tuningCents, window)
    }

    private fun forgetKey() {
        slow.fill(0.0)
        evidenceSeconds = 0.0
        key = null
    }

    fun reset() {
        rings.forEach { it.fill(0f) }
        writeIndex = 0
        samplesSeen = 0
        sinceAnalysis = 0
        chroma.fill(0f)
        fast.fill(0.0)
        pitched = false
        tuningX = 0.0
        tuningY = 0.0
        tuningCents = 0f
        unpitchedSeconds = 0.0
        forgetKey()
    }

    internal companion object {
        /**
         * Correlation of a pitch-class [profile] with one key's profile, -1..1, or 0 for a flat
         * profile. The song scan uses it to place a key change between two known keys.
         */
        fun correlation(profile: FloatArray, tonic: Int, mode: KeyMode): Double {
            val mean = profile.sum() / 12.0
            var norm = 0.0
            for (note in 0 until 12) norm += (profile[note] - mean) * (profile[note] - mean)
            if (norm <= 1e-12) return 0.0
            norm = sqrt(norm)
            val reference = if (mode == KeyMode.Major) MAJOR_PROFILE else MINOR_PROFILE
            var dot = 0.0
            for (note in 0 until 12) dot += (profile[note] - mean) / norm * reference[((note - tonic) % 12 + 12) % 12]
            return dot
        }

        const val FRAME_SECONDS = 0.2
        const val FAST_SECONDS = 1.0
        const val KEY_SECONDS = 8.0
        const val TUNING_SECONDS = 10.0
        const val EXPIRY_SECONDS = 8.0
        const val MIN_EVIDENCE_SECONDS = 3.0
        const val LOW_HZ = 80.0
        const val HIGH_HZ = 5_000.0
        const val TUNING_LOW_HZ = 100.0
        const val TUNING_HIGH_HZ = 3_000.0
        const val LOWEST_FUNDAMENTAL_HZ = 40.0
        const val RANGE_DB = 60.0
        const val PEAK_DB = 10.0
        const val NEIGHBOUR_BINS = 25
        const val MAX_PEAKS = 256
        const val PITCHED_SHARE = 0.2
        const val SILENT_POWER = 1e-10
        const val HALF_WIDTH = 2.0 / 3.0
        const val HARMONIC_DECAY = 0.6
        const val MIN_CORRELATION = 0.5
        const val FULL_CORRELATION = 0.8
        const val MIN_LEAD = 0.05
        const val FULL_LEAD = 0.15
        val LN2 = ln(2.0)

        /** Krumhansl-Kessler probe-tone ratings, tonic first, normalised to zero mean and unit length. */
        val MAJOR_PROFILE = normalised(doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88))
        val MINOR_PROFILE = normalised(doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17))

        fun normalised(values: DoubleArray): DoubleArray {
            val mean = values.average()
            val norm = sqrt(values.sumOf { (it - mean) * (it - mean) })
            return DoubleArray(values.size) { (values[it] - mean) / norm }
        }
    }
}

/** Largest power of two no longer than 0.4 seconds, capped to the FFT's supported range. */
internal fun longWindowSize(sampleRate: Int): Int {
    val maximum = minOf((sampleRate * 0.4).toInt(), 32_768)
    var size = 4
    while (size <= maximum / 2) size *= 2
    return size
}
