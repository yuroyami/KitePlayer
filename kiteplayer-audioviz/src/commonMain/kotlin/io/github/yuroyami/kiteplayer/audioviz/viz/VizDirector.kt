package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame

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
 * Chooses what to show and when to change it.
 *
 * A plain timer that moves to the next drawing in the list has two problems. Changes land in the
 * middle of a phrase, where they read as a glitch rather than as a
 * decision. And the drawing that arrives has nothing to do with what the music is doing, so a
 * strobe turns up under a piano intro about as often as anywhere else.
 *
 * This waits for the top of a phrase, picks from the drawings whose energy matches the music, and
 * reacts to the two moments in a track that a person would react to: a drop, where it moves up a
 * gear at once, and a breakdown, where it drops back and softens.
 *
 * It is seeded, so a run can be repeated exactly.
 */
public class VizDirector(
    private val catalogue: List<Visualization>,
    seed: Long = 20_260_910L,
    /** How many phrases to sit on one drawing at least. */
    private val leastPhrases: Int = 2,
    /** And at most, before it moves on regardless. */
    private val mostPhrases: Int = 4,
    /** Seconds between changes when there is no tempo to count phrases with. */
    private val secondsWithoutTempo: Float = 22f,
) {
    init {
        require(catalogue.isNotEmpty()) { "the director needs something to choose from" }
        require(leastPhrases in 1..mostPhrases) { "leastPhrases must be 1..mostPhrases" }
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

    private var phrasesHeld = 0
    private var lastPhrasePosition = 0f
    private var phraseTurned = false
    private var heldSeconds = 0f
    private var transitionSeconds = 0f
    private var transitionLength = 1f
    private var wanted = 0

    /** Starts on a named drawing, or the first one if the name is not in the list. */
    public fun startWith(name: String) {
        current = catalogue.firstOrNull { it.name == name } ?: catalogue.first()
        remember(current)
    }

    /** Moves it on by one frame. Call this once per drawn frame, before drawing. */
    public fun advance(frame: SpectrumFrame, deltaSeconds: Float) {
        heldSeconds += deltaSeconds

        if (incoming != null) {
            transitionSeconds += deltaSeconds
            progress = (transitionSeconds / transitionLength).coerceIn(0f, 1f)
            if (progress >= 1f) finish()
            return
        }

        countPhrases(frame)
        updateCountdown(frame)

        // A drop is not something to wait a phrase for. It is the moment itself.
        if (frame.drop && heldSeconds > 4f) {
            val next = pick(upFrom(moodBucket(frame)))
            begin(next, allowed(VizTransition.ZoomThrough, next), frame, bars = 1)
            return
        }
        if (frame.breakdown && heldSeconds > 8f && current.bucket != VizEnergy.Calm) {
            val next = pick(VizEnergy.Calm)
            begin(next, allowed(VizTransition.Crossfade, next), frame, bars = 4)
            return
        }
        if (dueForAChange(frame)) {
            val next = pick(moodBucket(frame))
            begin(next, chooseTransition(frame, next), frame, bars = 2)
        }
    }

    /** Puts a chosen drawing on screen at once, as when someone picks one by hand. */
    public fun show(visualization: Visualization) {
        incoming = null
        progress = 0f
        current = visualization
        remember(visualization)
        heldSeconds = 0f
        phrasesHeld = 0
    }

    private fun countPhrases(frame: SpectrumFrame) {
        phraseTurned = false
        if (frame.beatConfidence <= 0.4f) return
        // The phrase position runs 0 to 1 and wraps. A wrap is the top of a phrase.
        // Small phase corrections are not new phrases. Only a wrap through zero counts.
        if (lastPhrasePosition > 0.75f && frame.phrasePhase < 0.25f) {
            phrasesHeld++
            phraseTurned = true
        }
        lastPhrasePosition = frame.phrasePhase
    }

    private fun dueForAChange(frame: SpectrumFrame): Boolean {
        val tracked = frame.beatConfidence > 0.4f
        if (!tracked) return heldSeconds >= secondsWithoutTempo
        if (phrasesHeld < leastPhrases) return false
        if (phrasesHeld >= mostPhrases) return true
        // Somewhere between the two, decided once per phrase, on the frame the phrase turns over.
        return phraseTurned && nextRandom() < 0.4f
    }

    private fun updateCountdown(frame: SpectrumFrame) {
        nextChangeSeconds = if (frame.beatConfidence > 0.4f && frame.bpm > 0f) {
            val phraseSeconds = 16f * 60f / frame.bpm
            val left = (leastPhrases - phrasesHeld).coerceAtLeast(0)
            left * phraseSeconds + (1f - frame.phrasePhase) * phraseSeconds
        } else {
            (secondsWithoutTempo - heldSeconds).coerceAtLeast(0f)
        }
    }

    private fun begin(next: Visualization, how: VizTransition, frame: SpectrumFrame, bars: Int) {
        if (next.name == current.name) return
        incoming = next
        transition = how
        progress = 0f
        transitionSeconds = 0f
        // Changes last a whole number of bars, so the new drawing lands where the music does.
        // A hand-off happens on its first frame; the rest of it is the new drawing's echoes fading.
        val length = if (how == VizTransition.WarpHandoff) 1 else bars
        transitionLength = if (frame.bpm > 0f && frame.beatConfidence > 0.4f) {
            (length * 4f * 60f / frame.bpm).coerceIn(0.4f, 6f)
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
        phrasesHeld = 0
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
