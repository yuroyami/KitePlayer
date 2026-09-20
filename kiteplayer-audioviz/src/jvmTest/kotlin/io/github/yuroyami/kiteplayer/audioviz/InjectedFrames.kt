package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Frames built by hand, so two renders differ only by the one driver under test.
 *
 * The baseline holds every driver at a middle value with nothing happening: steady bands, no hits,
 * no supported pulse, no known key and no structure. Each driver then moves on its own. Nothing
 * here comes from an analyser, so there is no warm-up, no smoothing and no coupling between
 * drivers to confuse the result.
 */
internal object InjectedFrames {
    const val BANDS = 48
    const val SCOPE = 256

    /** The step a continuous driver steps up on, at 60 steps a second. Before it, nothing differs. */
    const val STEP_AT = 60

    /** Steps that carry a hit. All of them are after [STEP_AT]. */
    val HIT_STEPS = listOf(66, 96, 126)

    /** The step a structural event lands on. */
    const val STRUCTURE_STEP = 66

    const val HIT_STRENGTH = 0.8f
    const val HIT_CONFIDENCE = 0.9f

    private val baseBands = FloatArray(BANDS) { 0.08f + 0.34f * exp(-it / 12f) }
    private val basePeaks = FloatArray(BANDS) { (baseBands[it] * 1.15f).coerceAtMost(1f) }
    private val baseScope = FloatArray(SCOPE) { 0.3f * sin(2f * PI.toFloat() * 3f * it / SCOPE) }
    private val loudScope = FloatArray(SCOPE) { 0.9f * sin(2f * PI.toFloat() * 5f * it / SCOPE) }
    private val chroma = FloatArray(12) { if (it == 4) 1f else 0.1f }

    /**
     * The frame for [step] with [driver] moved, or the baseline when it is null.
     *
     * [strength] and [confidence] belong to the hits, for the checks that a response follows how
     * hard a hit was and that a detection without support moves nothing.
     */
    fun frame(
        driver: VizDriver?,
        step: Int,
        strength: Float = HIT_STRENGTH,
        confidence: Float = HIT_CONFIDENCE,
    ): SpectrumFrame {
        val pts = step * 1_000_000L / 60L
        // A continuous driver holds the baseline until the step, so the two runs start identical.
        val moved = if (step >= STEP_AT) driver else null
        val scale = if (moved == VizDriver.Bands) 1.9f else 1f
        val bands = if (scale == 1f) baseBands else FloatArray(BANDS) { (baseBands[it] * scale).coerceAtMost(1f) }
        val peaks = if (scale == 1f) basePeaks else FloatArray(BANDS) { (basePeaks[it] * scale).coerceAtMost(1f) }
        val level = if (moved == VizDriver.Level) 0.85f else 0.45f
        val slow = when (moved) {
            VizDriver.SlowLevel -> 0.85f
            VizDriver.Level -> 0.7f
            else -> 0.4f
        }
        val heard = confidence >= AudioDetection.HIT_CONFIDENCE
        val hits = hitsAt(driver, step, if (heard) strength else 0f)
        val pulses = pulseAt(driver, step, if (heard) strength else 0f)
        val rhythm = if (moved != VizDriver.Pulse) null else RhythmEstimate(
            ptsMicros = pts, availableMicros = pts, validUntilMicros = pts + 2_000_000L,
            revision = 1L, bpm = 120f, tempoSupport = 0.9f, beatSupport = 0.9f, usable = true,
            beatPhase = (step % 30) / 30f, alternativeBpm = 0f, alternativeSupport = 0f,
        )
        return SpectrumFrame(
            ptsMicros = pts,
            bands = bands,
            peaks = peaks,
            scope = if (moved == VizDriver.Waveform) loudScope else baseScope,
            level = level,
            bass = if (moved == VizDriver.Bass) 0.85f else 0.5f,
            mid = if (moved == VizDriver.Mid) 0.85f else 0.35f,
            treble = if (moved == VizDriver.Treble) 0.85f else 0.25f,
            beat = hits[3],
            pulse = pulses[3],
            bandsRel = bands,
            levelRel = level,
            bassRel = if (moved == VizDriver.Bass) 0.85f else 0.5f,
            midRel = if (moved == VizDriver.Mid) 0.85f else 0.35f,
            trebleRel = if (moved == VizDriver.Treble) 0.85f else 0.25f,
            kick = hits[0],
            snare = hits[1],
            hat = hits[2],
            onsetStrength = hits[3],
            novelty = if (hits[3] > 0f) 2f else 0.2f,
            kickPulse = pulses[0],
            snarePulse = pulses[1],
            hatPulse = pulses[2],
            energy = level,
            density = if (moved == VizDriver.Mood) 0.9f else 0.4f,
            mood = if (moved == VizDriver.Mood) 0.95f else 0.45f,
            loudShort = level,
            loudLong = slow,
            trend = level / slow,
            bpm = if (moved == VizDriver.Pulse) 120f else 0f,
            beatConfidence = if (moved == VizDriver.Pulse) 0.9f else 0f,
            beatPhase = if (moved == VizDriver.Pulse) (step % 30) / 30f else 0f,
            beatInSeconds = if (moved == VizDriver.Pulse) (30 - step % 30) / 60f else -1f,
            chroma = if (moved == VizDriver.Key) chroma else FloatArray(12),
            keyHue = 0.3f,
            keyConfidence = if (moved == VizDriver.Key) 1f else 0f,
            centroid = if (moved == VizDriver.Timbre) 0.85f else 0.35f,
            flatness = if (moved == VizDriver.Timbre) 0.85f else 0.3f,
            width = if (moved == VizDriver.Width) 0.9f else 0.2f,
            scopeLeft = if (moved == VizDriver.Waveform) loudScope else baseScope,
            scopeRight = baseScope,
            generation = Generation.Initial,
            hasTimestamp = true,
            events = delivery(driver, step, pts, strength, confidence),
            rhythm = rhythm,
        )
    }

    /** Strengths of the low, body and high hits and the onset on this step. */
    private fun hitsAt(driver: VizDriver?, step: Int, strength: Float): FloatArray {
        val out = FloatArray(4)
        if (step !in HIT_STEPS) return out
        when (driver) {
            VizDriver.LowHit -> out[0] = strength
            VizDriver.BodyHit -> out[1] = strength
            VizDriver.HighHit -> out[2] = strength
            VizDriver.Onset -> out[3] = strength
            else -> Unit
        }
        return out
    }

    /** The falling envelopes the analyser would publish after those hits. */
    private fun pulseAt(driver: VizDriver?, step: Int, strength: Float): FloatArray {
        val out = FloatArray(4)
        val last = HIT_STEPS.lastOrNull { it <= step } ?: return out
        val seconds = (step - last) / 60f
        when (driver) {
            VizDriver.LowHit -> out[0] = strength * exp(-seconds / 0.18f)
            VizDriver.BodyHit -> out[1] = strength * exp(-seconds / 0.12f)
            VizDriver.HighHit -> out[2] = strength * exp(-seconds / 0.055f)
            VizDriver.Onset -> out[3] = strength * exp(-seconds / 0.07f)
            else -> Unit
        }
        return out
    }

    private fun delivery(driver: VizDriver?, step: Int, pts: Long, strength: Float, confidence: Float): AudioEventDelivery {
        val kind = when (driver) {
            VizDriver.LowHit -> AudioEventKind.LowTransient
            VizDriver.BodyHit -> AudioEventKind.BodyTransient
            VizDriver.HighHit -> AudioEventKind.HighTransient
            VizDriver.Onset -> AudioEventKind.Onset
            VizDriver.Section -> AudioEventKind.SectionBoundary
            VizDriver.Drop -> AudioEventKind.Drop
            VizDriver.Breakdown -> AudioEventKind.Breakdown
            else -> null
        }
        val structural = kind == AudioEventKind.SectionBoundary || kind == AudioEventKind.Drop ||
            kind == AudioEventKind.Breakdown
        val lands = if (structural) step == STRUCTURE_STEP else step in HIT_STEPS
        val events = if (kind == null || !lands) {
            emptyArray()
        } else {
            val source = if (structural) AudioEventSource.LiveStructure else AudioEventSource.LiveTransient
            arrayOf(
                DeliveredAudioEvent(
                    AudioEvent(
                        Generation.Initial, 0L, step.toLong(),
                        AudioDetection(
                            kind, pts, pts,
                            if (structural) 0.7f else strength,
                            if (structural) 0.8f else confidence, 0.5f,
                        ),
                        source,
                    ),
                    0L,
                ),
            )
        }
        return AudioEventDelivery(Generation.Initial, 0L, pts, events)
    }

}
