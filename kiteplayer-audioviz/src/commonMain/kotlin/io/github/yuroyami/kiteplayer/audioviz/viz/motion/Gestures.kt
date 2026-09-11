package io.github.yuroyami.kiteplayer.audioviz.viz.motion

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import kotlin.math.floor

/**
 * The frame turned into edges a drawing reacts to once: drums, bar and phrase lines, drops,
 * breakdowns and silence.
 *
 * Without a sure tempo the bars run on a free clock that is slower for calm music, and a phrase
 * is four of them, so everything that waits for a bar still happens.
 */
@AudioVizAuthoringApi
public class Gestures {
    public var kick: Float = 0f
        private set
    public var snare: Float = 0f
        private set
    public var hat: Float = 0f
        private set
    public var bar: Boolean = false
        private set
    public var phrase: Boolean = false
        private set
    public var drop: Boolean = false
        private set
    public var breakdown: Boolean = false
        private set

    /** True while nothing has been playing for two seconds. */
    public var silence: Boolean = false
        private set
    public var bars: Int = 0
        private set
    public var phrases: Int = 0
        private set
    public var barSeconds: Float = 2f
        private set

    /** Where we are in the bar and in the phrase, 0 to 1, from the tempo or from the free clock. */
    public var barPhase: Float = 0f
        private set
    public var phrasePhase: Float = 0f
        private set

    public val beatSeconds: Float get() = barSeconds / 4f

    private var lastBarPhase = 0f
    private var lastPhrasePhase = 0f
    private var freeBar = 0f
    private var freeBars = 0
    private var quietFor = 0f
    private var wasBreakdown = false
    private var advancedAt = Float.NaN

    /** Reads one frame. Safe to call twice in a frame. */
    public fun update(state: VizRenderState) {
        if (state.timeSeconds == advancedAt) return
        advancedAt = state.timeSeconds
        val frame = state.frame
        val dt = state.deltaSeconds
        kick = frame.kick
        snare = frame.snare
        hat = frame.hat
        drop = frame.drop
        breakdown = frame.breakdown && !wasBreakdown
        wasBreakdown = frame.breakdown
        quietFor = if (frame.level < SILENT_LEVEL) quietFor + dt else 0f
        silence = quietFor > 2f
        bar = false
        phrase = false
        if (frame.beatConfidence > 0.4f && frame.bpm > 0f) {
            barSeconds = (240f / frame.bpm).coerceIn(0.8f, 6f)
            barPhase = frame.barPhase
            phrasePhase = frame.phrasePhase
            if (barPhase < lastBarPhase - 0.5f) {
                bar = true
                bars++
            }
            if (lastPhrasePhase > 0.75f && phrasePhase < 0.25f) {
                phrase = true
                phrases++
            }
        } else {
            barSeconds = (4.2f - 2.4f * frame.mood).coerceIn(1.8f, 4.2f)
            freeBar += dt / barSeconds
            if (freeBar >= 1f) {
                freeBar -= floor(freeBar)
                bar = true
                bars++
                freeBars++
                if (freeBars % 4 == 0) {
                    phrase = true
                    phrases++
                }
            }
            barPhase = freeBar
            phrasePhase = ((freeBars % 4) + freeBar) / 4f
        }
        lastBarPhase = barPhase
        lastPhrasePhase = phrasePhase
    }

    public fun reset() {
        kick = 0f
        snare = 0f
        hat = 0f
        bar = false
        phrase = false
        drop = false
        breakdown = false
        silence = false
        bars = 0
        phrases = 0
        barSeconds = 2f
        barPhase = 0f
        phrasePhase = 0f
        lastBarPhase = 0f
        lastPhrasePhase = 0f
        freeBar = 0f
        freeBars = 0
        quietFor = 0f
        wasBreakdown = false
        advancedAt = Float.NaN
    }

    private companion object {
        const val SILENT_LEVEL = 0.02f
    }
}
