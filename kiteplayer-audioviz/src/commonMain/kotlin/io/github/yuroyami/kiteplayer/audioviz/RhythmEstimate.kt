package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.floor

/**
 * Causal pulse estimates. Support values are heuristic scores, not probabilities of correctness.
 * A usable beat phase does not establish meter, a downbeat or a phrase boundary.
 */
@AudioVizAuthoringApi
public class RhythmEstimate internal constructor(
    /** Media time described by [beatPhase]. */
    public val ptsMicros: Long,
    /** Media time through which input was consumed to obtain this evidence. */
    public val availableMicros: Long,
    /** Last media time for which this evidence may support a predicted beat. */
    public val validUntilMicros: Long,
    /** Changes when a conflicting pulse rate replaces the old hypothesis. */
    public val revision: Long,
    /** Candidate pulse rate in BPM, diagnostic even when [usable] is false. */
    public val bpm: Float,
    /** Periodicity and detected-event support, 0..1. */
    public val tempoSupport: Float,
    /** Alignment support for this particular beat phase, 0..1. */
    public val beatSupport: Float,
    /** Entry/exit hysteresis and evidence expiry have accepted this phase for continuous motion. */
    public val usable: Boolean,
    /** Position between pulse instants, 0..1. Meaningful for motion only while [usable]. */
    public val beatPhase: Float,
    /** A supported half/double-time interpretation, or 0 when none is supported. */
    public val alternativeBpm: Float,
    /** Unweighted evidence for [alternativeBpm], 0..1. */
    public val alternativeSupport: Float,
) {
    /** Media seconds to the next predicted pulse, or -1 when the phase is unavailable. */
    public val beatInSeconds: Float get() = if (usable && bpm > 0f) (1f - beatPhase) * 60f / bpm else -1f

    /** Project phase with its accepted rate. Evidence and hypothesis identity are never blended. */
    internal fun at(pts: Long): RhythmEstimate {
        val fresh = pts <= validUntilMicros
        val projected = beatPhase + (pts - ptsMicros).toDouble() / 1_000_000.0 * bpm / 60.0
        return RhythmEstimate(pts, availableMicros, validUntilMicros, revision, bpm,
            if (fresh) tempoSupport else 0f, if (fresh) beatSupport else 0f, usable && fresh,
            (projected - floor(projected)).toFloat(), alternativeBpm, if (fresh) alternativeSupport else 0f)
    }

    /** The same evidence with no beat progression, for a held playback clock. */
    internal fun held(): RhythmEstimate = if (!usable) this else RhythmEstimate(ptsMicros, availableMicros,
        validUntilMicros, revision, bpm, tempoSupport, beatSupport, false, beatPhase, alternativeBpm, alternativeSupport)
}
