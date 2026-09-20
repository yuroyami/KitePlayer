package io.github.yuroyami.kiteplayer.audioviz.viz.motion

import io.github.yuroyami.kiteplayer.audioviz.AudioDetection
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.MusicalBoundaryGate
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState

/**
 * Confident transients and supported structural edges, with separate continuous visual cycles.
 *
 * A hit is a transient the detector supports, whatever its level: see [AudioDetection.isHit]. The
 * strength of a hit says how much the picture should answer. The names below are the bands the
 * detectors watch. They do not identify instruments.
 */
@AudioVizAuthoringApi
public class Gestures {
    /** Strength of the strongest low-transient hit in this frame, 0 when there is none. */
    public var kick: Float = 0f
        private set
    /** Strength of the strongest body-transient hit in this frame. */
    public var snare: Float = 0f
        private set
    /** Strength of the strongest high-transient hit in this frame. */
    public var hat: Float = 0f
        private set
    /** How many low-transient hits this frame carried. Several land in one frame at a low frame rate. */
    public var kicks: Int = 0
        private set
    /** How many body-transient hits this frame carried. */
    public var snares: Int = 0
        private set
    /** How many high-transient hits this frame carried. */
    public var hats: Int = 0
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
    /**
     * Whether the cycles follow a supported pulse rather than running free.
     *
     * Free cycles are for continuous motion. Anything discrete, such as spawning a ring on a cycle
     * edge, needs this: without a supported pulse those edges are a beat train the music does not
     * have.
     */
    public var pulseUsable: Boolean = false
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

    /**
     * How much this frame's hits of one kind ask the picture for, added up and capped.
     *
     * A drawing multiplies its own count by this, so a hard hit asks for more than a soft one and
     * a dense run of hits in one frame cannot flood the picture. A hit never asks for nothing: a
     * high transient carries little of a mix's energy even in loud music, so scaling a response by
     * strength alone leaves it too small to see. The floor keeps it visible; the rest still ranks
     * by how hard the hit was.
     */
    internal val kickAccent: Float get() = accentOf(kicks, kickTotal)
    internal val snareAccent: Float get() = accentOf(snares, snareTotal)
    internal val hatAccent: Float get() = accentOf(hats, hatTotal)

    private fun accentOf(count: Int, total: Float): Float =
        if (count == 0) 0f else (count * LEAST_ACCENT + total * (1f - LEAST_ACCENT)).coerceAtMost(MOST_ACCENT)

    private var kickTotal = 0f
    private var snareTotal = 0f
    private var hatTotal = 0f
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
        readHits(frame)
        // A reduced-motion setting has to suppress flashes, and a drawing flashes mostly through
        // what it draws on a hit rather than through the light it is given. Holding the hits back
        // is the one lever that reaches every drawing without each of them knowing about it.
        val calm = 0.25f + 0.75f * state.motionScale.coerceIn(0f, 1f)
        if (calm < 1f) {
            kick *= calm; snare *= calm; hat *= calm
            kickTotal *= calm; snareTotal *= calm; hatTotal *= calm
        }
        val boundary = boundaries.read(frame)
        section = boundary != null
        if (section) sections++
        drop = boundary?.detection?.kind == AudioEventKind.Drop
        breakdown = boundary?.detection?.kind == AudioEventKind.Breakdown
        quietFor = if (frame.level < 0.02f) quietFor + dt else 0f
        silence = quietFor > 2f
        val freeSeconds = (4.2f - 2.4f * frame.mood).coerceIn(1.8f, 4.2f)
        pulseUsable = frame.rhythm?.usable == true
        cycleSeconds = if (pulseUsable) 240f / checkNotNull(frame.rhythm).bpm else freeSeconds
        val next = cycleClock.advance(dt, frame, 1f / freeSeconds)
        val slow = slowClock.advance(dt, frame, 1f / (freeSeconds * 4f))
        if (next < cyclePhase - 0.5f) cycles++
        if (slow < slowCyclePhase - 0.5f) slowCycles++
        cyclePhase = next
        slowCyclePhase = slow
    }

    /**
     * Reads this frame's hits from its delivered records, which keep every hit rather than the
     * strongest of each kind.
     *
     * The cursor hands a record to one view once, a repeated frame at an instant already read
     * never reaches this, and a catch-up reset skips the burst it missed instead of replaying it.
     * A raw analysis carries detections rather than a delivery. A frame built by hand carries
     * neither, and its scalar fields count as one hit each, because they have no confidence.
     */
    private fun readHits(frame: SpectrumFrame) {
        kick = 0f; snare = 0f; hat = 0f
        kicks = 0; snares = 0; hats = 0
        kickTotal = 0f; snareTotal = 0f; hatTotal = 0f
        val delivery = frame.events
        val detections = frame.detections
        when {
            delivery != null -> for (index in 0 until delivery.size) add(delivery[index].event.detection)
            detections != null -> for (index in 0 until detections.size) add(detections[index])
            else -> {
                if (frame.kick > 0f) { kick = frame.kick; kicks = 1; kickTotal = frame.kick }
                if (frame.snare > 0f) { snare = frame.snare; snares = 1; snareTotal = frame.snare }
                if (frame.hat > 0f) { hat = frame.hat; hats = 1; hatTotal = frame.hat }
            }
        }
    }

    private fun add(detection: AudioDetection) {
        if (!detection.isHit) return
        val strength = detection.strength.coerceIn(0f, 1f)
        when (detection.kind) {
            AudioEventKind.LowTransient -> { kick = maxOf(kick, strength); kicks++; kickTotal += strength }
            AudioEventKind.BodyTransient -> { snare = maxOf(snare, strength); snares++; snareTotal += strength }
            AudioEventKind.HighTransient -> { hat = maxOf(hat, strength); hats++; hatTotal += strength }
            else -> Unit
        }
    }

    public fun reset() {
        kick = 0f
        snare = 0f
        hat = 0f
        kicks = 0
        snares = 0
        hats = 0
        kickTotal = 0f
        snareTotal = 0f
        hatTotal = 0f
        section = false
        sections = 0
        drop = false
        breakdown = false
        silence = false
        cycles = 0
        slowCycles = 0
        cycleSeconds = 2f
        pulseUsable = false
        cyclePhase = 0f
        slowCyclePhase = 0f
        cycleClock.reset()
        slowClock.reset()
        boundaries = MusicalBoundaryGate()
        quietFor = 0f
        step.reset()
    }

    private companion object {
        /** The most one frame's hits of one kind can ask a drawing for. *Judgement.* */
        const val MOST_ACCENT = 2f

        /** What the softest hit still asks for, as a share of a full-strength one. *Judgement.* */
        const val LEAST_ACCENT = 0.35f
    }
}
