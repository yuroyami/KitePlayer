package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.round

/** Causal pulse-rate and phase estimates. No meter, downbeat or phrase inference occurs here. */
internal class TempoTracker(private val analysesPerSecond: Float) {
    init { require(analysesPerSecond.isFinite() && analysesPerSecond > 0f) }

    private val historySize = (analysesPerSecond * 6f).toInt().coerceAtLeast(64)
    private val history = FloatArray(historySize)
    private val attacks = FloatArray(historySize)
    private var writeIndex = 0
    private var filled = 0
    private val shortestLag = (analysesPerSecond * 60f / FASTEST_BPM).toInt().coerceAtLeast(2)
    private val longestLag = (analysesPerSecond * 60f / SLOWEST_BPM).toInt().coerceAtMost(historySize / 2)
    private val scores = FloatArray((longestLag - shortestLag + 1).coerceAtLeast(1))
    private val folded = FloatArray(longestLag + 2)
    private var sinceEstimate = 0
    private var sinceAttack = Float.POSITIVE_INFINITY
    private var curveMean = 0f
    private var curveVariance = 0f
    private var phaseSupport = 0f
    private val lock = RhythmLock()

    /** Diagnostic candidate, retained after expiry. It is not permission to predict beats. */
    var bpm: Float = 0f
        private set
    /** Correlation support, not a probability of the tempo being correct. */
    var tempoConfidence: Float = 0f
        private set
    /** Phase alignment support, distinct from periodicity support and from event energy. */
    var confidence: Float = 0f
        private set
    var alternativeBpm: Float = 0f
        private set
    var alternativeConfidence: Float = 0f
        private set
    var revision: Long = 0L
        private set
    val usable: Boolean get() = lock.usable
    val remainingEvidenceSeconds: Float get() =
        (maxOf(1f, if (bpm > 0f) 120f / bpm else 1f) - sinceAttack).coerceAtLeast(0f)
    var beatPhase: Float = 0f
        private set
    var beatInSeconds: Float = -1f
        private set
    var beatTicked: Boolean = false
        private set

    // Compatibility accessors deliberately report unknown. A pulse counter is not musical meter.
    val barTicked: Boolean get() = false
    fun alignedBarPhase(): Float = 0f
    fun alignedPhrasePhase(): Float = 0f

    /** One uniformly spaced activation sample. [onsetStrength] indicates a detected attack. */
    fun feed(novelty: Float, onsetStrength: Float, deltaSeconds: Float) {
        val dt = deltaSeconds.takeIf { it.isFinite() && it > 0f } ?: return
        val attack = onsetStrength.isFinite() && onsetStrength > 0f
        sinceAttack = if (attack) 0f else sinceAttack + dt
        history[writeIndex] = if (novelty.isFinite()) novelty.coerceAtLeast(0f) else 0f
        attacks[writeIndex] = if (attack) 1f else 0f
        writeIndex = (writeIndex + 1) % historySize
        if (filled < historySize) filled++

        beatTicked = false
        if (bpm > 0f && evidenceFresh()) {
            val next = beatPhase + dt * bpm / 60f
            beatTicked = usable && next >= 1f
            beatPhase = next - floor(next)
        }
        if (++sinceEstimate >= (analysesPerSecond * 0.5f).toInt().coerceAtLeast(1)) {
            sinceEstimate = 0
            estimate()
        }
        if (!evidenceFresh()) {
            confidence = 0f
            tempoConfidence = 0f
            alternativeConfidence = 0f
        }
        lock.update(confidence, dt, valid = evidenceFresh() && bpm > 0f)
        if (!usable) beatTicked = false
        beatInSeconds = if (usable) (1f - beatPhase) * 60f / bpm else -1f
    }

    private fun evidenceFresh(): Boolean = sinceAttack <= maxOf(1f, if (bpm > 0f) 120f / bpm else 1f)

    private fun estimate() {
        if (filled < analysesPerSecond * 2f || longestLag <= shortestLag) return
        var mean = 0f
        for (index in 0 until filled) mean += at(index)
        mean /= filled
        var variance = 0f
        for (index in 0 until filled) {
            val centred = at(index) - mean
            variance += centred * centred
        }
        variance /= filled
        curveMean = mean
        curveVariance = variance
        if (variance <= 1e-12f) {
            confidence = 0f
            tempoConfidence = 0f
            return
        }

        var best = 0f
        var bestLag = 0
        for (lag in shortestLag..longestLag) {
            val match = if (filled >= lag * 3) correlationAt(lag).coerceIn(0f, 1f) else 0f
            scores[lag - shortestLag] = match
        }
        for (lag in shortestLag..longestLag) {
            val index = lag - shortestLag
            val match = scores[index]
            if (match <= 0f || index > 0 && scores[index - 1] > match ||
                index < scores.lastIndex && scores[index + 1] > match) continue
            // A multiple of the pulse interval explains only a fraction of equal attacks.
            // The small prior cannot outweigh that missing evidence.
            val coverage = alignment(refineLag(lag), move = false)
            val score = match * (0.5f + 0.5f * coverage) * tempoPreference(analysesPerSecond * 60f / lag)
            if (score > best) { best = score; bestLag = lag }
        }
        if (bestLag == 0) {
            confidence = 0f
            tempoConfidence = 0f
            return
        }

        val candidate = (analysesPerSecond * 60f / refineLag(bestLag)).coerceIn(SLOWEST_BPM, FASTEST_BPM)
        if (bpm > 0f && abs(candidate - bpm) > bpm * 0.08f) { lock.reset(); revision++ }
        bpm = if (bpm > 0f && abs(candidate - bpm) < bpm * 0.06f) bpm + (candidate - bpm) * 0.3f else candidate
        val period = analysesPerSecond * 60f / bpm
        val coverage = alignment(period, move = true)
        var hits = 0f
        for (index in 0 until filled) hits += at(index, attacks)
        val expectedHits = filled / period
        val eventSupport = (hits / expectedHits.coerceAtLeast(1f)).coerceIn(0f, 1f)
        tempoConfidence = (scores[bestLag - shortestLag] / 0.65f).coerceIn(0f, 1f) * eventSupport
        confidence = tempoConfidence * phaseSupport * (coverage / 0.5f).coerceIn(0f, 1f)
        alternativeBpm = 0f
        alternativeConfidence = 0f
        for (factor in listOf(0.5f, 2f)) {
            val alternate = bpm * factor
            if (alternate !in SLOWEST_BPM..FASTEST_BPM) continue
            val lag = round(analysesPerSecond * 60f / alternate).toInt()
            if (lag !in shortestLag..longestLag) continue
            val support = scores[lag - shortestLag] * eventSupport
            if (support > alternativeConfidence && support >= scores[bestLag - shortestLag] * 0.8f) {
                alternativeBpm = alternate
                alternativeConfidence = support.coerceIn(0f, 1f)
            }
        }
    }

    /** Fold activation into one period, preserving fractional lag. Returns explained activation. */
    private fun alignment(lag: Float, move: Boolean): Float {
        val count = kotlin.math.ceil(lag).toInt().coerceIn(2, folded.size)
        folded.fill(0f)
        var total = 0f
        for (index in 0 until filled) {
            val back = (filled - 1 - index).toFloat()
            val phase = back / lag - floor(back / lag)
            val bin = phase * count
            val lower = bin.toInt().coerceAtMost(count - 1)
            val value = at(index)
            folded[lower] += value * (1f - (bin - lower))
            folded[(lower + 1) % count] += value * (bin - lower)
            total += value
        }
        if (total <= 1e-12f) { if (move) phaseSupport = 0f; return 0f }
        var best = 0
        for (index in 1 until count) if (folded[index] > folded[best]) best = index
        val before = folded[(best + count - 1) % count]
        val here = folded[best]
        val after = folded[(best + 1) % count]
        var covered = 0f
        // About +/- 30 ms, independent of tempo. Only a concentration of activation supports phase.
        val radius = (analysesPerSecond * 0.03f * count / lag).toInt().coerceAtLeast(1)
        for (offset in -radius..radius) covered += folded[(best + offset + count) % count]
        if (move) {
            phaseSupport = ((covered / total - (radius * 2f + 1f) / count) / 0.45f).coerceIn(0f, 1f)
            val curve = before - 2f * here + after
            val shift = if (curve < -1e-12f) (0.5f * (before - after) / curve).coerceIn(-0.5f, 0.5f) else 0f
            val target = (best + shift) / count
            var error = target - beatPhase
            error -= round(error)
            // While acquiring no drawing is locked. Continuous clients perform their own handover.
            val next = beatPhase + error * if (usable) 0.25f else 1f
            beatPhase = next - floor(next)
        }
        return (covered / total).coerceIn(0f, 1f)
    }

    private fun correlationAt(lag: Int): Float {
        var sum = 0f
        var count = 0
        for (index in lag until filled) {
            sum += (at(index) - curveMean) * (at(index - lag) - curveMean)
            count++
        }
        return if (count == 0) 0f else sum / count / curveVariance
    }

    private fun refineLag(bestLag: Int): Float {
        val index = bestLag - shortestLag
        if (index <= 0 || index >= scores.lastIndex) return bestLag.toFloat()
        val before = scores[index - 1]
        val here = scores[index]
        val after = scores[index + 1]
        val curve = before - 2f * here + after
        if (curve >= -1e-12f) return bestLag.toFloat()
        return bestLag + (0.5f * (before - after) / curve).coerceIn(-0.5f, 0.5f)
    }

    private fun at(index: Int, values: FloatArray = history): Float =
        values[((writeIndex - filled + index) % historySize + historySize) % historySize]

    private fun tempoPreference(perMinute: Float): Float {
        val distance = ln(perMinute / 120f)
        return 0.95f + 0.05f * exp(-distance * distance / 2f)
    }

    fun reset() {
        history.fill(0f)
        attacks.fill(0f)
        writeIndex = 0
        filled = 0
        sinceEstimate = 0
        sinceAttack = Float.POSITIVE_INFINITY
        curveMean = 0f
        curveVariance = 0f
        phaseSupport = 0f
        bpm = 0f
        confidence = 0f
        tempoConfidence = 0f
        alternativeBpm = 0f
        alternativeConfidence = 0f
        beatPhase = 0f
        beatInSeconds = -1f
        beatTicked = false
        revision = 0L
        lock.reset()
    }

    private companion object {
        const val SLOWEST_BPM = 40f
        const val FASTEST_BPM = 240f
    }
}

/** Entry and exit timers use elapsed analysis time, never frame counts. */
internal class RhythmLock {
    var usable: Boolean = false
        private set
    private var held = 0f

    fun update(confidence: Float, deltaSeconds: Float, valid: Boolean = true) {
        if (!valid || !confidence.isFinite()) { reset(); return }
        val qualifies = if (usable) confidence < 0.4f else confidence >= 0.6f
        held = if (qualifies) held + deltaSeconds.coerceAtLeast(0f) else 0f
        if (held >= 1f) { usable = !usable; held = 0f }
    }

    fun reset() { usable = false; held = 0f }
}
