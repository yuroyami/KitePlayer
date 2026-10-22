package io.github.yuroyami.kiteplayer.audioviz.viz.motion

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState

/**
 * Paces a drawing's changes of form by how much is happening in the music.
 *
 * It adds up musical activity (hits, novelty, density) over heard time. When the sum reaches the
 * budget and a hit lands, or a cycle edge passes under a usable pulse, it fires [morph] and starts
 * again. Every third morph and every surge fires [birth] as well. An accepted section fires a morph
 * at once; a drop sets [bloom] and a breakdown sets [collapse], which are the gene moves to most and
 * least with a name a drawing can read. Elapsed time alone never fires anything: a pause or a
 * silence adds no activity, and a silence drops what was added.
 */
@AudioVizAuthoringApi
public class Evolution(
    /** Activity units between morphs. About four cycles of busy music, sixteen of a ballad. *Judgement.* */
    public var morphBudget: Float = 8f,
) {
    /** True on the one frame a form change starts. */
    public var morph: Boolean = false
        private set

    /** True on the one frame a new form is born inside the picture. */
    public var birth: Boolean = false
        private set

    /** True on the frame of an accepted drop: every part to its fullest. */
    public var bloom: Boolean = false
        private set

    /** True on the frame of an accepted breakdown: every part to its sparest. */
    public var collapse: Boolean = false
        private set

    /** How many morphs have fired since the last reset. */
    public var morphs: Int = 0
        private set

    /** How many births have fired since the last reset. */
    public var births: Int = 0
        private set

    /** Activity added up since the last morph, in units of the budget. */
    public var activity: Float = 0f
        private set

    private var morphsSinceBirth = 0
    private var lastCyclePhase = -1f

    /** Reads one frame. Call once per frame, after [gestures] has read the same frame. */
    public fun update(state: VizRenderState, gestures: Gestures) {
        morph = false
        birth = false
        bloom = gestures.drop
        collapse = gestures.breakdown
        val frame = state.frame
        val heard = state.stepSeconds
        if (gestures.silence) activity = 0f
        // Reduced motion halves the pace; a pause or a silence adds nothing because heard is zero.
        val pace = 0.5f + 0.5f * state.motionScale.coerceIn(0f, 1f)
        val perSecond = (0.25f + 0.45f * frame.novelty / 2f + 0.3f * frame.density +
            0.25f * gestures.kick + 0.15f * gestures.snare).coerceAtMost(2f)
        activity += perSecond * heard * pace

        val cycleEdge = gestures.pulseUsable && lastCyclePhase >= 0f && gestures.cyclePhase < lastCyclePhase
        lastCyclePhase = gestures.cyclePhase
        val hit = gestures.kick > 0f || gestures.snare > 0f || gestures.hat > 0f
        val due = activity >= morphBudget && (hit || cycleEdge)
        if (gestures.section || due) {
            morph = true
            morphs++
            morphsSinceBirth++
            activity = 0f
        }
        if ((morph && morphsSinceBirth >= 3) || gestures.surge) {
            birth = true
            births++
            morphsSinceBirth = 0
        }
    }

    /** Forgets every count and the activity added so far. */
    public fun reset() {
        morph = false
        birth = false
        bloom = false
        collapse = false
        morphs = 0
        births = 0
        activity = 0f
        morphsSinceBirth = 0
        lastCyclePhase = -1f
    }
}
