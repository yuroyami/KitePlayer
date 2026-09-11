package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Works out how fast the music is and where we are inside the bar.
 *
 * Onsets alone say when something happened. They do not say when the NEXT thing will happen, and
 * a drawing that wants to turn once per bar or land a cut on the downbeat needs that.
 *
 * Three steps. The onset strengths of the last few seconds are kept as a curve. Sliding that
 * curve against itself finds the delay at which it best matches, and that delay is the beat
 * length: this is autocorrelation, and it is the whole of tempo finding. Then a running phase
 * counts forward at that speed and is nudged whenever a real onset lands near where one was
 * expected, so the phase keeps going through a fill where nothing plays.
 *
 * Nothing here is trusted blindly. [confidence] says how strongly the curve preferred one delay
 * over the others, and music without a steady beat scores low, which is the signal for a drawing
 * to go back to running on its own clock.
 */
internal class TempoTracker(
    /** How many analyses happen per second. Sets every window length here. */
    private val analysesPerSecond: Float,
) {
    /** Eight seconds of onset history is four bars at 120 beats a minute, which is enough to be sure. */
    private val historySize = (analysesPerSecond * 8f).toInt().coerceAtLeast(64)
    private val history = FloatArray(historySize)
    private var writeIndex = 0
    private var filled = 0

    private val shortestLag = (analysesPerSecond * 60f / FASTEST_BPM).toInt().coerceAtLeast(2)
    private val longestLag = (analysesPerSecond * 60f / SLOWEST_BPM).toInt().coerceAtMost(historySize / 2)

    private var sinceEstimate = 0

    /** One score per delay tried, kept so the winning peak can be read between whole numbers. */
    private val scores = FloatArray((longestLag - shortestLag + 1).coerceAtLeast(1))

    private var curveMean = 0f
    private var curveVariance = 1f

    /** Whether an onset landed on each recent analysis, so the beat can be corroborated. */
    private val onsetWindow = FloatArray((analysesPerSecond * 4f).toInt().coerceAtLeast(16))
    private var onsetIndex = 0
    private var onsetFilled = 0

    private fun onsetsPerSecond(): Float {
        if (onsetFilled == 0) return 0f
        var hits = 0f
        for (index in 0 until onsetFilled) hits += onsetWindow[index]
        return hits / (onsetFilled / analysesPerSecond)
    }

    /** Beats per minute, or 0 before the first estimate. */
    var bpm: Float = 0f
        private set

    /** How peaked the match was, 0 to 1. Below about 0.4 the tempo is a guess. */
    var confidence: Float = 0f
        private set

    /** Where we are between one beat and the next, 0 to 1. */
    var beatPhase: Float = 0f
        private set

    /** Where we are in a four beat bar, 0 to 1. */
    var barPhase: Float = 0f
        private set

    /** Where we are in a sixteen beat phrase, 0 to 1. */
    var phrasePhase: Float = 0f
        private set

    /** Seconds until the next beat is expected, or -1 with no tempo. */
    var beatInSeconds: Float = -1f
        private set

    /** True on the analysis where [beatPhase] wrapped, so a caller can count bars. */
    var beatTicked: Boolean = false
        private set

    var barTicked: Boolean = false
        private set

    private var beatCount = 0
    private val barWeights = FloatArray(4)
    private val phraseWeights = FloatArray(16)
    private var barOffset = 0
    private var phraseOffset = 0

    /**
     * [novelty] is the continuous "energy is arriving" curve, which is what the tempo is found in.
     * [onsetStrength] is the gated onset, which is what the running beat position is nudged by.
     */
    fun feed(novelty: Float, onsetStrength: Float, deltaSeconds: Float) {
        onsetWindow[onsetIndex] = if (onsetStrength > 0f) 1f else 0f
        onsetIndex = (onsetIndex + 1) % onsetWindow.size
        if (onsetFilled < onsetWindow.size) onsetFilled++

        history[writeIndex] = novelty
        writeIndex = (writeIndex + 1) % historySize
        if (filled < historySize) filled++

        // Half a second between estimates. More often costs work and answers the same thing.
        if (++sinceEstimate >= (analysesPerSecond * 0.5f).toInt().coerceAtLeast(1)) {
            sinceEstimate = 0
            estimate()
        }

        advancePhase(onsetStrength, deltaSeconds)
    }

    /** Slides the onset curve against itself and keeps the delay that matched best. */
    private fun estimate() {
        // Two and a half seconds is two and a half beats even at the slowest tempo, which is enough
        // to find one. Waiting for half the history would put the first answer four seconds in.
        if (filled < historySize * 3 / 10 || longestLag <= shortestLag) return

        // The average is taken out first, and the result divided by how much the curve varies.
        // Both matter. The curve never goes below zero, so without removing its average every
        // delay matches every other delay simply because everything is positive, and a signal
        // with no rhythm in it at all comes back looking confidently periodic. What is left is
        // the ordinary correlation coefficient: near zero for noise, and high only where the
        // curve genuinely repeats.
        var mean = 0f
        for (index in 0 until filled) mean += at(index)
        mean /= filled
        var variance = 0f
        for (index in 0 until filled) {
            val centred = at(index) - mean
            variance += centred * centred
        }
        variance /= filled
        if (variance <= 1e-12f) {
            confidence = 0f
            return
        }
        curveMean = mean
        curveVariance = variance

        var best = 0f
        var bestLag = 0
        for (lag in shortestLag..longestLag) {
            val match = correlationAt(lag)
            scores[lag - shortestLag] = match
            val score = match * tempoPreference(analysesPerSecond * 60f / lag)
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || best <= 1e-6f) {
            confidence = 0f
            return
        }

        // A repeating curve on its own is not enough. Sustained music has textures that repeat
        // without anything being played on a beat, and a pad with no percussion in it at all can
        // produce a curve that lines up convincingly. A tempo is a rate of EVENTS, so it only
        // counts when events are actually being detected: no hits, no beat, whatever the shape of
        // the curve says.
        val support = (onsetsPerSecond() / SUPPORTING_ONSETS_PER_SECOND).coerceIn(0f, 1f)
        confidence = (scores[bestLag - shortestLag] / CONFIDENT_MATCH).coerceIn(0f, 1f) * support

        val candidate = analysesPerSecond * 60f / refineLag(bestLag)
        bpm = settle(candidate)
        alignPhase()
    }

    /**
     * Finds where in the beat the onsets land, and moves the running position there.
     *
     * The running position on its own only fixes small errors: onsets that land near a beat nudge
     * it, so if it starts half a beat out, every onset looks like a syncopation and it never finds
     * its way back. This reads the last eight beats of the onset curve at one beat's spacing, from
     * every possible starting point, and the starting point that collects the most is where the
     * beats fall.
     */
    private fun alignPhase() {
        if (bpm <= 0f || confidence < 0.25f) return
        val lag = analysesPerSecond * 60f / bpm
        val offsets = lag.toInt().coerceAtLeast(1)
        var best = -1f
        var bestOffset = 0
        var total = 0f
        for (offset in 0 until offsets) {
            var sum = 0f
            for (beat in 0 until ALIGN_BEATS) {
                val back = filled - 1 - (offset + beat * lag)
                if (back < 1f) break
                val lower = back.toInt()
                sum += at(lower) + (at(lower + 1) - at(lower)) * (back - lower)
            }
            total += sum
            if (sum > best) {
                best = sum
                bestOffset = offset
            }
        }
        // No place stands out, which is what a rubato passage looks like. Leave the position be.
        if (best < total / offsets * 1.5f) return
        // The newest analysis sits this far past the last beat.
        val target = bestOffset / lag
        var error = beatPhase - target
        error -= kotlin.math.round(error)
        beatPhase -= error * ALIGN_PULL
        // Crossing a beat line while moving keeps the count of beats honest.
        if (beatPhase >= 1f) {
            beatPhase -= 1f
            beatCount++
        } else if (beatPhase < 0f) {
            beatPhase += 1f
            beatCount--
        }
    }

    /** How alike the curve is with itself [lag] apart, from -1 to 1. */
    private fun correlationAt(lag: Int): Float {
        var sum = 0f
        var count = 0
        var index = filled - 1
        while (index - lag >= 0) {
            sum += (at(index) - curveMean) * (at(index - lag) - curveMean)
            count++
            index--
        }
        if (count == 0) return 0f
        return sum / count / curveVariance
    }

    /**
     * The peak read between whole analyses.
     *
     * One analysis is about eleven milliseconds, so at a normal tempo a whole-numbered delay can
     * only ever land within about three beats per minute of the truth. Fitting a curve through the
     * winning delay and its two neighbours recovers the fraction in between, which is the
     * difference between a rotation that stays with the music and one that slips a beat a minute.
     */
    private fun refineLag(bestLag: Int): Float {
        val at = bestLag - shortestLag
        if (at <= 0 || at >= scores.size - 1) return bestLag.toFloat()
        val before = scores[at - 1]
        val here = scores[at]
        val after = scores[at + 1]
        val curve = before - 2f * here + after
        if (curve >= -1e-12f) return bestLag.toFloat()
        val shift = (0.5f * (before - after) / curve).coerceIn(-0.5f, 0.5f)
        return bestLag + shift
    }

    /**
     * Keeps the tempo steady between estimates.
     *
     * Two things go wrong without this. A tracker flips between a tempo and half of it, because
     * both match a drum pattern equally well. And it wanders by a beat or two each estimate,
     * which makes anything locked to the bar visibly stutter. So the old answer is kept unless
     * the new one is genuinely different, and the octave nearest the middle of the range wins.
     */
    private fun settle(candidate: Float): Float {
        var chosen = candidate
        while (chosen < SLOWEST_BPM) chosen *= 2f
        while (chosen > FASTEST_BPM) chosen /= 2f
        if (bpm <= 0f) return chosen

        // Same tempo, read slightly differently: move most of the way and stay smooth.
        if (abs(chosen - bpm) < bpm * 0.06f) return bpm + (chosen - bpm) * 0.3f

        // A doubling or a halving of what we already had is not new information.
        val doubled = chosen * 2f
        val halved = chosen * 0.5f
        if (abs(doubled - bpm) < bpm * 0.06f && doubled <= FASTEST_BPM) return bpm
        if (abs(halved - bpm) < bpm * 0.06f && halved >= SLOWEST_BPM) return bpm

        return chosen
    }

    /** Runs the phase forward, and pulls it towards any onset that lands near a beat. */
    private fun advancePhase(onsetStrength: Float, deltaSeconds: Float) {
        beatTicked = false
        barTicked = false
        if (bpm <= 0f) {
            beatInSeconds = -1f
            return
        }

        val period = 60f / bpm
        beatPhase += deltaSeconds / period
        if (beatPhase >= 1f) {
            beatPhase -= beatPhase.toInt().toFloat()
            beatTicked = true
            beatCount++
            if (beatCount % 4 == 0) barTicked = true
        }

        if (onsetStrength > 0.15f && confidence > 0.25f) {
            // How far off the beat this onset was, as a signed number from -0.5 to 0.5.
            var error = beatPhase
            if (error > 0.5f) error -= 1f
            // Only nudge for onsets that are close to a beat. A syncopated hit is not a mistake.
            if (abs(error) < 0.25f) {
                beatPhase -= error * 0.12f * onsetStrength
                if (beatPhase < 0f) {
                    beatPhase += 1f
                    beatCount--
                }
            }
            collectDownbeat(onsetStrength)
        }

        barPhase = ((beatCount % 4) + beatPhase) / 4f
        phrasePhase = ((beatCount % 16) + beatPhase) / 16f
        beatInSeconds = (1f - beatPhase) * period
    }

    /**
     * Learns which beat of the bar carries the weight, so a bar starts where a listener hears it.
     *
     * Strong onsets are added to whichever of the four beats they landed on, and to whichever of
     * the sixteen beats of the phrase. The heaviest slot is the downbeat. Old evidence fades, so
     * a change of section moves the answer instead of being outvoted by the whole song.
     */
    private fun collectDownbeat(onsetStrength: Float) {
        // An onset just before a beat belongs to that beat, not the one it is finishing.
        val nearest = if (beatPhase > 0.5f) beatCount + 1 else beatCount
        val beatSlot = ((nearest % 4) + 4) % 4
        val phraseSlot = ((nearest % 16) + 16) % 16
        for (slot in barWeights.indices) barWeights[slot] *= 0.996f
        for (slot in phraseWeights.indices) phraseWeights[slot] *= 0.999f
        barWeights[beatSlot] += onsetStrength
        phraseWeights[phraseSlot] += onsetStrength

        var heaviest = 0
        for (slot in barWeights.indices) if (barWeights[slot] > barWeights[heaviest]) heaviest = slot
        barOffset = heaviest
        var heaviestPhrase = 0
        for (slot in phraseWeights.indices) {
            if (phraseWeights[slot] > phraseWeights[heaviestPhrase]) heaviestPhrase = slot
        }
        phraseOffset = heaviestPhrase
    }

    /** Where the bar actually starts, once the downbeat has been found. */
    fun alignedBarPhase(): Float {
        if (bpm <= 0f) return 0f
        val slot = ((beatCount - barOffset) % 4 + 4) % 4
        return (slot + beatPhase) / 4f
    }

    fun alignedPhrasePhase(): Float {
        if (bpm <= 0f) return 0f
        val slot = ((beatCount - phraseOffset) % 16 + 16) % 16
        return (slot + beatPhase) / 16f
    }

    private fun at(index: Int): Float {
        val oldest = writeIndex - filled
        return history[((oldest + index) % historySize + historySize) % historySize]
    }

    /**
     * How much a tempo is preferred before the music is consulted.
     *
     * Every tempo has a double and a half that fit the same drum pattern, so something has to
     * break the tie. People hear tempo near 120, so that is what gets the benefit of the doubt.
     */
    private fun tempoPreference(perMinute: Float): Float {
        val distance = ln(perMinute / PREFERRED_BPM)
        return exp(-distance * distance / (2f * 0.55f * 0.55f))
    }

    fun reset() {
        history.fill(0f)
        writeIndex = 0
        filled = 0
        sinceEstimate = 0
        bpm = 0f
        confidence = 0f
        curveMean = 0f
        curveVariance = 1f
        onsetWindow.fill(0f)
        onsetIndex = 0
        onsetFilled = 0
        beatPhase = 0f
        barPhase = 0f
        phrasePhase = 0f
        beatInSeconds = -1f
        beatCount = 0
        barWeights.fill(0f)
        phraseWeights.fill(0f)
        barOffset = 0
        phraseOffset = 0
    }

    private companion object {
        const val SLOWEST_BPM = 60f
        const val FASTEST_BPM = 180f
        const val PREFERRED_BPM = 120f

        /** How closely the curve has to repeat before the tempo is called certain. */
        const val CONFIDENT_MATCH = 0.45f

        /** Onsets a second needed before a repeating curve is believed to be a beat. */
        const val SUPPORTING_ONSETS_PER_SECOND = 1.5f

        /** How many beats back the position is checked against. */
        const val ALIGN_BEATS = 8

        /** How much of the way to the found position each check moves. */
        const val ALIGN_PULL = 0.5f
    }
}
