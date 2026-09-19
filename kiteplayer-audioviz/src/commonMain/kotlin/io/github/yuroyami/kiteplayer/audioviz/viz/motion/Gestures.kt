package io.github.yuroyami.kiteplayer.audioviz.viz.motion

import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.MusicalBoundaryGate
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState

/** Detected transients and supported structural edges, with separate continuous visual cycles. */
@AudioVizAuthoringApi
public class Gestures {
    public var kick: Float = 0f
        private set
    public var snare: Float = 0f
        private set
    public var hat: Float = 0f
        private set
    /** An accepted structural boundary. Neither elapsed time nor pulse counting produces this. */
    public var section: Boolean = false
        private set
    public var sections: Int = 0
        private set
    public var drop: Boolean = false
        private set
    public var breakdown: Boolean = false
        private set
    /** True while the overall driver has remained below 0.02 for two seconds. */
    public var silence: Boolean = false
        private set

    /** Artistic four-pulse cycle, with level-driven free motion when rhythm is unavailable. */
    public var cyclePhase: Float = 0f
        private set
    /** Artistic sixteen-pulse cycle. This does not describe a musical phrase. */
    public var slowCyclePhase: Float = 0f
        private set
    public var cycles: Int = 0
        private set
    public var slowCycles: Int = 0
        private set
    /** Nominal duration used for continuous parameter responses, not a measured bar length. */
    public var cycleSeconds: Float = 2f
        private set
    public val beatSeconds: Float get() = cycleSeconds / 4f

    @Deprecated("Pulse tracking does not establish downbeats. Use section for structural changes.")
    public val bar: Boolean get() = false
    @Deprecated("Pulse tracking does not establish phrases. Use section for structural changes.")
    public val phrase: Boolean get() = false
    @Deprecated("No bars are inferred. Use cycles for artistic cycle counts.")
    public val bars: Int get() = 0
    @Deprecated("No phrases are inferred. Use sections for structural events or slowCycles for motion.")
    public val phrases: Int get() = 0
    @Deprecated("No bar phase is inferred. Use cyclePhase for continuous artistic motion.")
    public val barPhase: Float get() = 0f
    @Deprecated("No phrase phase is inferred. Use slowCyclePhase for continuous artistic motion.")
    public val phrasePhase: Float get() = 0f
    @Deprecated("Use cycleSeconds. A visual cycle is not necessarily a musical bar.")
    public val barSeconds: Float get() = cycleSeconds

    private var boundaries = MusicalBoundaryGate()
    private val cycleClock = MusicClock(4f)
    private val slowClock = MusicClock(16f)
    private var quietFor = 0f
    private val step = DisplayStep()

    /**
     * Reads one frame. Repeating the same state does not repeat an edge. A new frame at an instant
     * already read delivers its edges without advancing the cycles again.
     */
    public fun update(state: VizRenderState) {
        val dt = (step.of(state) ?: return).coerceAtMost(0.1f)
        val frame = state.frame
        kick = frame.kick
        snare = frame.snare
        hat = frame.hat
        val boundary = boundaries.read(frame)
        section = boundary != null
        if (section) sections++
        drop = boundary?.detection?.kind == AudioEventKind.Drop
        breakdown = boundary?.detection?.kind == AudioEventKind.Breakdown
        quietFor = if (frame.level < 0.02f) quietFor + dt else 0f
        silence = quietFor > 2f
        val freeSeconds = (4.2f - 2.4f * frame.mood).coerceIn(1.8f, 4.2f)
        cycleSeconds = if (frame.rhythm?.usable == true) 240f / frame.rhythm.bpm else freeSeconds
        val next = cycleClock.advance(dt, frame, 1f / freeSeconds)
        val slow = slowClock.advance(dt, frame, 1f / (freeSeconds * 4f))
        if (next < cyclePhase - 0.5f) cycles++
        if (slow < slowCyclePhase - 0.5f) slowCycles++
        cyclePhase = next
        slowCyclePhase = slow
    }

    public fun reset() {
        kick = 0f
        snare = 0f
        hat = 0f
        section = false
        sections = 0
        drop = false
        breakdown = false
        silence = false
        cycles = 0
        slowCycles = 0
        cycleSeconds = 2f
        cyclePhase = 0f
        slowCyclePhase = 0f
        cycleClock.reset()
        slowClock.reset()
        boundaries = MusicalBoundaryGate()
        quietFor = 0f
        step.reset()
    }
}
