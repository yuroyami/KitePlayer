package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.ln

/**
 * Finds the moments a listener would call a beat, and says which drum it sounded like.
 *
 * Loudness alone is a poor signal: a sustained chord is loud without anything happening. What a
 * beat sounds like is a sudden ARRIVAL of energy, so this measures how much each bin GREW since
 * the last analysis and adds those growths up. That total is called spectral flux, and it spikes
 * on a drum hit and stays flat through a held note.
 *
 * A fixed threshold cannot work across quiet and loud music, so the flux is compared to the
 * average of the recent past instead. Anything well above its own recent average is an onset.
 *
 * Two details stop it reporting rhythm that is not there. The growths are measured on a LOG
 * scale, because a tone sitting between two FFT bins makes those bins wobble as its phase drifts,
 * and on a linear scale that wobble looks like a steady pulse. And a candidate must clear an
 * absolute floor as well as the relative one, because during near-silence the recent average is
 * tiny and anything at all clears a threshold built from it.
 *
 * The same method runs four times over different parts of the spectrum. One over everything is
 * the general [beat]. Three narrow ones answer whether it was a [kick], a [snare] or a [hat],
 * which lets a drawing move its camera on the kick and flicker its highlights on the hats
 * instead of doing the same thing for every hit in the bar.
 */
internal class BeatDetector(
    private val binCount: Int,
    sampleRate: Int = 48_000,
    fftSize: Int = 2048,
) {
    private val binsPerHz = fftSize.toFloat() / sampleRate

    private fun binFor(hz: Float): Int = (hz * binsPerHz).toInt().coerceIn(1, binCount - 1)

    /** Everything: the general onset, whatever was hit. */
    private val whole = FluxBand(binCount, binFor(30f), binCount, refractory = 6)

    /**
     * Kick drums and the low thump of a bass line.
     *
     * Held back harder than the others. This stretch of the spectrum is only a handful of bins
     * wide, so a bass line moving under the drums looks a lot like another kick, and without a
     * long enough gap between allowed hits it would report three kicks a beat.
     */
    private val low = FluxBand(binCount, binFor(40f), binFor(120f), refractory = 10, sensitivity = 1.75f)

    /**
     * Snares. Their body sits with the voice and their crack sits far above it, so this reads two
     * stretches at once: one detector over both is what tells a snare from a tom.
     */
    private val body = FluxBand(
        binCount,
        binFor(150f),
        binFor(400f),
        secondFrom = binFor(2_000f),
        secondTo = binFor(5_000f),
        refractory = 7,
        sensitivity = 1.6f,
    )

    /** Hats, shakers and the top of a cymbal. Allowed to fire often, because they do. */
    private val air = FluxBand(binCount, binFor(6_000f), binCount, refractory = 3, sensitivity = 1.3f)

    /** The log-scale copy of the newest spectrum, shared by all four so it is built once. */
    private val compressed = FloatArray(binCount)

    var kick: Float = 0f
        private set

    var snare: Float = 0f
        private set

    var hat: Float = 0f
        private set

    /** How far past its floor the onset went, on a log scale. A soft hit reads soft. */
    var strength: Float = 0f
        private set

    /**
     * How much energy is arriving right now, whether or not it counted as an onset.
     *
     * [strength] is zero on all but a handful of analyses, because an onset has to clear a
     * threshold and then wait out a gap before another is allowed. That is the right answer for
     * "did a drum just hit", and the wrong one for working out a tempo: over eight seconds it
     * leaves a few dozen numbers to find a pattern in, and which of them happen to line up is
     * mostly luck. This is the same measurement without the gate, so it has something to say on
     * every analysis and a tempo can actually be found in it.
     */
    var novelty: Float = 0f
        private set

    /**
     * Takes this analysis's magnitudes and answers how strong a general onset it is, 0 to 1.
     *
     * Zero is the normal answer. [magnitudes] is not kept: log-scale copies are held instead.
     * Read [kick], [snare], [hat] and [strength] afterwards for the same analysis.
     */
    fun feed(magnitudes: FloatArray, usableBins: Int): Float {
        for (bin in 0 until usableBins) compressed[bin] = ln(1f + LOG_SCALE * magnitudes[bin])

        val beat = whole.feed(compressed)
        kick = low.feed(compressed)
        snare = body.feed(compressed)
        hat = air.feed(compressed)
        strength = whole.lastStrength
        novelty = whole.lastNovelty
        return beat
    }

    fun reset() {
        whole.reset()
        low.reset()
        body.reset()
        air.reset()
        kick = 0f
        snare = 0f
        hat = 0f
        strength = 0f
        novelty = 0f
    }

    private companion object {
        const val LOG_SCALE = 500f
    }
}

/**
 * Spectral flux over one stretch of the spectrum, or two when a sound lives in two places.
 *
 * Everything about the method is in [BeatDetector]'s notes. This is one instance of it.
 */
private class FluxBand(
    binCount: Int,
    private val from: Int,
    private val to: Int,
    private val secondFrom: Int = -1,
    private val secondTo: Int = -1,
    historySize: Int = 43,
    private val sensitivity: Float = 1.4f,
    private val refractory: Int = 6,
) {
    private val width = (to - from).coerceAtLeast(1) +
        if (secondFrom >= 0) (secondTo - secondFrom).coerceAtLeast(1) else 0

    /** Flux below this is never an onset however quiet the recent past was. Scales with the bins. */
    private val absoluteFloor = width * 0.004f

    private val previous = FloatArray(binCount)
    private val history = FloatArray(historySize)
    private var historyIndex = 0
    private var historyFilled = 0
    private var sinceBeat = Int.MAX_VALUE

    /** How far the last onset cleared its floor, 0 to 1. Zero when the last analysis was not one. */
    var lastStrength: Float = 0f
        private set

    /** How far above its recent average the flux is, with no threshold and no waiting. */
    var lastNovelty: Float = 0f
        private set

    fun feed(compressed: FloatArray): Float {
        var flux = growthOver(compressed, from, to)
        if (secondFrom >= 0) flux += growthOver(compressed, secondFrom, secondTo)

        var average = 0f
        if (historyFilled > 0) {
            for (index in 0 until historyFilled) average += history[index]
            average /= historyFilled
        }

        history[historyIndex] = flux
        historyIndex = (historyIndex + 1) % history.size
        if (historyFilled < history.size) historyFilled++

        if (sinceBeat < Int.MAX_VALUE) sinceBeat++
        lastStrength = 0f
        lastNovelty = 0f

        // Nothing to compare against yet, and a silent passage has no beats to find.
        if (historyFilled < history.size / 2 || average <= 1e-7f) return 0f

        // Measured before any gate, so it has a value on every analysis.
        lastNovelty = ((flux - average) / (average + 1e-7f)).coerceIn(0f, 4f)
        if (sinceBeat < refractory) return 0f

        val threshold = maxOf(average * sensitivity, absoluteFloor)
        if (flux <= threshold) return 0f

        sinceBeat = 0
        // How much of the hit is real rather than borrowed from a quiet recent past. Eight times
        // the floor is as hard as a hit gets, so that reads 1.
        lastStrength = (ln(1f + flux / absoluteFloor) / ln(9f)).coerceIn(0f, 1f)
        // How far past the threshold it went, so a kick reads stronger than a hi-hat.
        val excess = ((flux - threshold) / (threshold + 1e-7f)).coerceIn(0f, 1f)
        // Scaled by how big the hit was in absolute terms as well. The threshold is built from the
        // recent past, so during quiet music a very small arrival of energy clears it easily and
        // would otherwise be reported as a full strength drum. A pad has no drums in it and should
        // say so.
        return excess * lastStrength
    }

    private fun growthOver(compressed: FloatArray, first: Int, last: Int): Float {
        var flux = 0f
        for (bin in first until last) {
            val growth = compressed[bin] - previous[bin]
            if (growth > 0f) flux += growth
            previous[bin] = compressed[bin]
        }
        return flux
    }

    fun reset() {
        previous.fill(0f)
        history.fill(0f)
        historyIndex = 0
        historyFilled = 0
        sinceBeat = Int.MAX_VALUE
        lastStrength = 0f
        lastNovelty = 0f
    }
}
