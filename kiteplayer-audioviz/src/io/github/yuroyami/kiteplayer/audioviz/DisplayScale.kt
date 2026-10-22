package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** One causal or fixed-song reference shared by every energy driver. Power units throughout. */
internal class DisplayScale {
    var referencePower = 0.01
        private set
    var gain = 100.0
        private set
    var gainLimited = false
        private set
    val handingOver: Boolean get() = fixedReference != null && handoverSeconds < 2.0
    private var fixedReference: Double? = null
    private var handoverSeconds = 2.0
    private var fromLogGain = ln(gain)

    fun setReference(power: Double?) {
        require(power == null || power.isFinite() && power > 0.0) { "reference power must be positive and finite" }
        if (power == fixedReference) return
        fixedReference = power
        fromLogGain = ln(gain)
        handoverSeconds = if (power == null) 2.0 else 0.0
        if (power == null) referencePower = 1.0 / gain
    }

    fun advance(momentaryPower: Double?, digitalSilence: Boolean, seconds: Double) {
        require(seconds.isFinite() && seconds >= 0)
        val fixed = fixedReference
        if (fixed != null) {
            referencePower = fixed
            handoverSeconds = minOf(2.0, handoverSeconds + seconds)
            val target = boundedGain(fixed)
            gain = exp(fromLogGain + (ln(target) - fromLogGain) * (handoverSeconds / 2.0))
        } else {
            if (!digitalSilence && momentaryPower != null && momentaryPower.isFinite() && momentaryPower > 0) {
                val tau = if (momentaryPower > referencePower) 1.0 else 15.0
                referencePower += (momentaryPower - referencePower) * (1 - exp(-seconds / tau))
            }
            gain = boundedGain(referencePower)
        }
    }

    /** Clear causal history at a gap, while retaining the supplied same-song fixed reference. */
    fun reset() {
        if (fixedReference == null) {
            referencePower = 0.01
            gain = 100.0
            gainLimited = false
        }
    }

    private fun boundedGain(reference: Double): Double {
        val candidate = 1.0 / reference
        val bounded = candidate.coerceIn(MIN_GAIN, MAX_GAIN)
        gainLimited = candidate != bounded
        return bounded
    }
}

private val MIN_GAIN = 10.0.pow(-2.4)
private val MAX_GAIN = 10.0.pow(2.4)
private val HEIGHT_FLOOR = 10.0.pow(-1.25)

/** The standard's artistic compression of normalised linear power, with a -50 dB floor. */
internal fun powerHeight(power: Double): Double =
    ((sqrt(sqrt(power.coerceAtLeast(0.0))) - HEIGHT_FLOOR) / (1.0 - HEIGHT_FLOOR)).coerceIn(0.0, 1.0)

/** Fast and slow height envelopes, plus a separately held and decaying power peak. */
internal class EnergyEnvelope {
    var fast = 0.0
        private set
    var slow = 0.0
        private set
    private var peakPower = 0.0
    private var holdSeconds = 0.0
    val peak: Double get() = powerHeight(peakPower)

    fun advance(normalisedPower: Double, seconds: Double) {
        val target = powerHeight(normalisedPower)
        fast += (target - fast) * (1 - exp(-seconds / if (target > fast) 0.01 else 0.12))
        slow += (target - slow) * (1 - exp(-seconds / if (target > slow) 0.1 else 2.0))
        if (normalisedPower >= peakPower) {
            peakPower = normalisedPower
            holdSeconds = 0.5
        } else {
            val decaySeconds = (seconds - holdSeconds).coerceAtLeast(0.0)
            holdSeconds = (holdSeconds - seconds).coerceAtLeast(0.0)
            peakPower = maxOf(normalisedPower, peakPower * exp(-12.0 / 10 * ln(10.0) * decaySeconds))
        }
    }

    fun reset() {
        fast = 0.0
        slow = 0.0
        peakPower = 0.0
        holdSeconds = 0.0
    }

    fun snapshot(): EnergyDriver = EnergyDriver(fast.toFloat(), slow.toFloat(), peak.toFloat())
}
