package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Quarter-tone unit-height triangles. Working arrays belong to one analysis worker. */
internal class LogFrequencyFilterbank(sampleRate: Int, fftSize: Int) {
    init {
        require(sampleRate in 1_000..768_000)
        require(fftSize in 4..32768 && fftSize and (fftSize - 1) == 0)
    }

    private val spacing = sampleRate.toDouble() / fftSize
    private val anchors = buildList {
        val upperHz = minOf(16_000.0, 0.95 * sampleRate / 2)
        // 27.5 Hz is four octaves below the tuning reference.
        var step = -96
        while (true) {
            val hz = 440.0 * 2.0.pow(step / 24.0)
            if (hz > upperHz) break
            val bin = floor(hz / spacing + 0.5).toInt().coerceIn(0, fftSize / 2)
            if (isEmpty() || last() != bin) add(bin)
            step++
        }
    }.toIntArray()

    val size: Int = (anchors.size - 2).coerceAtLeast(0)
    val values = FloatArray(size)
    private val weights = Array(size) { index ->
        val low = lowerBin(index)
        val centre = centreBin(index)
        val high = upperBin(index)
        FloatArray(high - low + 1) { offset ->
            val bin = low + offset
            if (bin <= centre) (bin - low).toFloat() / (centre - low)
            else (high - bin).toFloat() / (high - centre)
        }
    }

    fun lowerBin(index: Int): Int = anchors[index]
    fun centreBin(index: Int): Int = anchors[index + 1]
    fun upperBin(index: Int): Int = anchors[index + 2]
    fun centreHz(index: Int): Double = centreBin(index) * spacing

    /** Calibrated tone amplitudes, with a fixed detector-only compression scale. Input is borrowed. */
    fun measure(magnitudes: FloatArray) {
        require(size == 0 || magnitudes.size >= upperBin(size - 1))
        for (index in values.indices) {
            val low = lowerBin(index)
            val triangle = weights[index]
            var sum = 0.0
            for (offset in triangle.indices) {
                if (triangle[offset] == 0f) continue
                val magnitude = magnitudes[low + offset]
                if (magnitude.isFinite() && magnitude > 0f) sum += magnitude.toDouble() * triangle[offset]
            }
            values[index] = log10(1.0 + 512.0 * sum).toFloat()
        }
    }
}

/** Positive growth against a width-three maximum of a past log-frequency observation. */
internal class MaximumFilteredFlux(bandCount: Int, private val lagFrames: Int) {
    init {
        require(bandCount in 1..512)
        require(lagFrames in 1..8192)
    }

    val growth = FloatArray(bandCount)
    private val history = Array(lagFrames) { FloatArray(bandCount) }
    private var writeIndex = 0
    private var filled = 0

    fun feed(values: FloatArray) {
        require(values.size == growth.size)
        val previous = history[writeIndex]
        // Compute the whole difference before replacing any neighbour in the comparison frame.
        for (index in growth.indices) {
            var maximum = 0f
            for (neighbour in maxOf(0, index - 1)..minOf(values.lastIndex, index + 1)) {
                maximum = maxOf(maximum, previous[neighbour])
            }
            val current = values[index].let { if (it.isFinite()) it.coerceAtLeast(0f) else 0f }
            growth[index] = if (filled == lagFrames) (current - maximum).coerceAtLeast(0f) else 0f
        }
        for (index in previous.indices) {
            previous[index] = values[index].let { if (it.isFinite()) it.coerceAtLeast(0f) else 0f }
        }
        writeIndex = (writeIndex + 1) % lagFrames
        if (filled < lagFrames) filled++
    }

    fun reset() {
        history.forEach { it.fill(0f) }
        growth.fill(0f)
        writeIndex = 0
        filled = 0
    }
}

/** Hann half-height comparison distance, rounded to a whole hop as in the baseline method. */
internal fun superFluxLag(fftSize: Int, hop: Int): Int {
    require(fftSize in 4..32768 && fftSize and (fftSize - 1) == 0)
    require(hop in 1..fftSize)
    val window = Fft.hannWindow(fftSize)
    val first = window.indexOfFirst { it > 0.5f }
    return floor((fftSize / 2.0 - first) / hop + 0.5).toInt().coerceAtLeast(1)
}

/** Causal local-maximum / past-mean-plus-offset picker with a 30 ms combination interval. */
internal class CausalFluxPeakPicker(hopSeconds: Double, private val offset: Float) {
    init {
        require(hopSeconds.isFinite() && hopSeconds in (1.0 / 768_000)..32.768)
        require(offset.isFinite() && offset > 0f)
    }

    private val maximumFrames = ceil(0.030 / hopSeconds).toInt().coerceAtLeast(1)
    private val meanFrames = ceil(0.100 / hopSeconds).toInt().coerceAtLeast(1)
    private val combinationFrames = maximumFrames
    private val history = FloatArray(maxOf(maximumFrames, meanFrames))
    private var writeIndex = 0
    private var filled = 0
    private var sinceEvent = Int.MAX_VALUE
    var confidence = 0f
        private set
    var surprise = 0f
        private set

    fun feed(input: Float): Boolean {
        val value = if (input.isFinite()) input.coerceAtLeast(0f) else 0f
        var maximum = 0f
        var sum = 0.0
        val meanCount = minOf(filled, meanFrames)
        for (age in 1..filled) {
            val previous = history[(writeIndex - age + history.size) % history.size]
            if (age <= maximumFrames) maximum = maxOf(maximum, previous)
            if (age <= meanFrames) sum += previous
        }
        val mean = if (meanCount > 0) sum / meanCount else 0.0
        val threshold = mean + offset
        if (sinceEvent < Int.MAX_VALUE) sinceEvent++
        val detected = value > maximum && value > threshold && sinceEvent >= combinationFrames
        confidence = if (detected) ((value - threshold) / threshold).coerceIn(0.0, 1.0).toFloat() else 0f
        surprise = ((value - mean) / maxOf(mean, offset.toDouble()) / 4).coerceIn(0.0, 1.0).toFloat()
        if (detected) sinceEvent = 0
        history[writeIndex] = value
        writeIndex = (writeIndex + 1) % history.size
        if (filled < history.size) filled++
        return detected
    }

    fun reset() {
        history.fill(0f)
        writeIndex = 0
        filled = 0
        sinceEvent = Int.MAX_VALUE
        confidence = 0f
        surprise = 0f
    }
}
