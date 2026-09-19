package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind

/** How one drawing gives way to the next. */
public enum class VizTransition {
    /** A plain mix. The right answer under quiet music, where anything else would be rude. */
    Crossfade,

    /** A soft ragged edge eats one picture away and leaves the other behind. */
    NoiseWipe,

    /** The new one opens out of the middle. Lands well on a beat. */
    Iris,

    /** The old one rushes past the viewer and the new one arrives from far away. For a drop. */
    ZoomThrough,

    /** Three hard alternations and it is done. Only ever right when the music is loud. */
    StrobeCut,

    /**
     * The new drawing starts from the old one's last frame, so the old picture is carried off by the
     * new drawing's own echoes. Only offered to drawings that feed their frames back.
     */
    WarpHandoff,
}

/**
 * Changes scenes only at a supported musical boundary. Tempo, elapsed time and energy-only
 * flags cannot authorize a change. A seeded choice matches the scene to current energy.
 */
public class VizDirector(
    private val catalogue: List<Visualization>,
    seed: Long = 20_260_910L,
    /** Minimum time between automatic changes. Reaching it does not schedule a change. */
    private val minimumHoldSeconds: Float = 8f,
) {
    init {
        require(catalogue.isNotEmpty()) { "the director needs something to choose from" }
        require(minimumHoldSeconds.isFinite() && minimumHoldSeconds >= 0f)
    }

    private var random = seed
    private val recent = ArrayDeque<String>()

    /** What is on screen. */
    public var current: Visualization = catalogue.first()
        private set

    /** What is arriving, while a change is under way. */
    public var incoming: Visualization? = null
        private set

    /** How the change is being made. */
    public var transition: VizTransition = VizTransition.Crossfade
        private set

    /** How far through the change we are, 0 to 1. */
    public var progress: Float = 0f
        private set

    /** True while [incoming] is on its way in. */
    public val changing: Boolean get() = incoming != null

    /**
     * A way of changing to use every time, or null to let the music decide.
     *
     * A drawing that cannot arrive that way still gets a way it can: the hand-off, for instance, needs
     * a drawing with echoes to hand them to.
     */
    public var preferred: VizTransition? = null

    /** Seconds until the next change, or -1 when it is not known. */
    public var nextChangeSeconds: Float = -1f
        private set

    private val boundaries = MusicalBoundaryGate()
    private var heldSeconds = 0f
    private var transitionSeconds = 0f
    private var transitionLength = 1f

    /** Starts on a named drawing, or the first one if the name is not in the list. */
    public fun startWith(name: String) {
        current = catalogue.firstOrNull { it.name == name } ?: catalogue.first()
        remember(current)
    }

    /**
     * Moves it on by one frame. Call this once per drawn frame, before drawing.
     *
     * A seek or track change does not cut a change already under way: it finishes over its planned
     * duration. Boundaries delivered meanwhile are consumed, not queued for later.
     */
    public fun advance(frame: SpectrumFrame, deltaSeconds: Float) {
        val dt = deltaSeconds.takeIf { it.isFinite() && it > 0f } ?: return
        heldSeconds += dt
        // Consume identities even during a transition or cooldown, so they cannot replay later.
        val boundary = boundaries.read(frame)
        nextChangeSeconds = -1f
        if (incoming != null) {
            transitionSeconds += dt
            progress = (transitionSeconds / transitionLength).coerceIn(0f, 1f)
            if (progress >= 1f) finish()
            return
        }
        if (boundary == null || heldSeconds < minimumHoldSeconds) return
        when (boundary.detection.kind) {
            AudioEventKind.Drop -> {
                val next = pick(upFrom(moodBucket(frame)))
                begin(next, allowed(VizTransition.ZoomThrough, next), frame, pulses = 4)
            }
            AudioEventKind.Breakdown -> {
                val next = pick(VizEnergy.Calm)
                begin(next, allowed(VizTransition.Crossfade, next), frame, pulses = 8)
            }
            AudioEventKind.SectionBoundary -> {
                val next = pick(moodBucket(frame))
                begin(next, chooseTransition(frame, next), frame, pulses = 8)
            }
            else -> Unit
        }
    }

    /** Puts a chosen drawing on screen at once, as when someone picks one by hand. */
    public fun show(visualization: Visualization) {
        incoming = null
        progress = 0f
        current = visualization
        remember(visualization)
        heldSeconds = 0f
    }

    private fun begin(next: Visualization, how: VizTransition, frame: SpectrumFrame, pulses: Int) {
        if (next.name == current.name) return
        incoming = next
        transition = how
        progress = 0f
        transitionSeconds = 0f
        // A pulse-scaled artistic duration does not claim the transition ends on a downbeat.
        val length = if (how == VizTransition.WarpHandoff) 1 else pulses
        transitionLength = if (frame.rhythm?.usable == true) {
            (length * 60f / frame.rhythm.bpm).coerceIn(0.4f, 6f)
        } else {
            1.6f
        }
    }

    private fun finish() {
        current = incoming ?: current
        remember(current)
        incoming = null
        progress = 0f
        heldSeconds = 0f
    }

    /** Which shelf of the catalogue suits the music right now. */
    private fun moodBucket(frame: SpectrumFrame): VizEnergy = when {
        frame.mood > 0.6f -> VizEnergy.High
        frame.mood > 0.3f -> VizEnergy.Mid
        else -> VizEnergy.Calm
    }

    private fun upFrom(bucket: VizEnergy): VizEnergy = when (bucket) {
        VizEnergy.Calm -> VizEnergy.Mid
        else -> VizEnergy.High
    }

    /** A way to change that suits the music and that [next] can arrive by. */
    private fun chooseTransition(frame: SpectrumFrame, next: Visualization): VizTransition {
        val offered = next.transitions
        preferred?.let { if (it in offered) return it }
        val suited = when {
            frame.mood > 0.7f -> LIVELY
            frame.mood > 0.45f -> MOVING
            else -> QUIET
        }
        val choices = suited.filter { it in offered }
        if (choices.isEmpty()) return VizTransition.Crossfade
        return choices[(nextRandom() * choices.size).toInt().coerceIn(0, choices.size - 1)]
    }

    /** [how], unless a preferred way was set and [next] can arrive by it. */
    private fun allowed(how: VizTransition, next: Visualization): VizTransition {
        val wanted = preferred ?: return how
        return if (wanted in next.transitions) wanted else how
    }

    /**
     * Picks from the right shelf, avoiding anything shown lately.
     *
     * Falls back to the whole catalogue when a shelf is empty or everything on it has been seen
     * recently, because showing something twice is better than showing nothing.
     */
    private fun pick(bucket: VizEnergy): Visualization {
        val shelf = catalogue.filter { it.bucket == bucket && it.name !in recent }
        val from = shelf.ifEmpty { catalogue.filter { it.name !in recent } }.ifEmpty { catalogue }
        return from[(nextRandom() * from.size).toInt().coerceIn(0, from.size - 1)]
    }

    private fun remember(visualization: Visualization) {
        recent.addLast(visualization.name)
        while (recent.size > REMEMBERED) recent.removeFirst()
    }

    /** A repeatable number between 0 and 1. Seeded, so a run can be played back exactly. */
    private fun nextRandom(): Float {
        random = random * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        return ((random ushr 33).toInt() and 0x7FFFFF) / 8_388_608f
    }

    private companion object {
        /** How many drawings back to avoid repeating. */
        const val REMEMBERED = 8

        val LIVELY = listOf(VizTransition.StrobeCut, VizTransition.Iris, VizTransition.NoiseWipe, VizTransition.WarpHandoff)
        val MOVING = listOf(VizTransition.Iris, VizTransition.NoiseWipe, VizTransition.WarpHandoff)
        val QUIET = listOf(VizTransition.Crossfade)
    }
}
