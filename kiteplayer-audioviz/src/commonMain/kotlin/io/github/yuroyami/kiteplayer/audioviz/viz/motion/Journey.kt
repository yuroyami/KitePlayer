package io.github.yuroyami.kiteplayer.audioviz.viz.motion

import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.AudioEvent
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import kotlin.math.abs
import kotlin.math.exp

/**
 * Persistent material weights, not picture opacity. Consumers interpolate the positions of the
 * same vertices or the camera's destination. The soundtrack chooses a destination; minimum dwell,
 * recent-visit memory and a zero-velocity arrival keep it from turning into a random slideshow.
 */
internal class Journey(
    private val count: Int,
    private val seed: Long,
    private val adjacent: (Int, Int) -> Boolean = { _, _ -> true },
) {
    val weights = FloatArray(count).apply { this[0] = 1f }
    var target: Int = 0
        private set
    var transitions: Int = 0
        private set
    private val from = weights.copyOf()
    private val visited = FloatArray(count)
    private var random = Rng(seed)
    private var progress = 1f
    private var duration = 5f
    private var dwell = 0f
    private var exploration = 0f
    private var previousActivity = 0f
    private var activity = 0f
    private var contrast = 0f
    private var anticipated: AudioEvent? = null

    fun advance(state: VizRenderState, gestures: Gestures, affinity: FloatArray,
        automatic: Boolean, selected: Int, pace: Float = 1f) {
        if (state.frame.held) return
        val dt = state.deltaSeconds.coerceIn(0f, 0.1f) * state.frame.audible * state.motionScale
        if (dt <= 0f) return
        val sensed = (state.frame.density * 0.45f + state.drive * 0.35f +
            state.frame.novelty.coerceIn(0f, 2f) * 0.1f).coerceIn(0f, 1f)
        activity += (sensed - activity) * (1f - exp(-dt * 1.4f))
        contrast += (abs(activity - previousActivity) - contrast) * (1f - exp(-dt * 0.6f))
        previousActivity += (activity - previousActivity) * (1f - exp(-dt * 0.08f))
        dwell += dt
        exploration += dt * (0.2f + 0.8f * activity) * pace
        for (i in visited.indices) visited[i] *= exp(-dt / 45f)
        if (!automatic && selected.coerceIn(0, count - 1) != target) {
            moveTo(selected.coerceIn(0, count - 1), 4.5f / pace)
        } else if (automatic && progress >= 1f && dwell > 9f / pace) {
            // Continuous exploration is artistic, never labelled as a beat or section detection.
            val structural = gestures.section || gestures.drop || gestures.breakdown || approachingBoundary(state)
            if (structural || (contrast > 0.14f && dwell > 14f / pace) || exploration > 11f) {
                var best = target
                var bestScore = -1f
                for (i in 0 until count) {
                    if (i == target || !adjacent(target, i)) continue
                    val score = affinity[i].coerceAtLeast(0f) * (0.72f + random.next() * 0.56f) /
                        (1f + 1.8f * visited[i])
                    if (score > bestScore) { bestScore = score; best = i }
                }
                if (best != target && bestScore > 0f) {
                    moveTo(best, (6.5f - 2.5f * activity) / pace)
                }
            }
        }
        if (progress < 1f) {
            progress = (progress + dt / duration).coerceAtMost(1f)
            val t = progress
            val blend = t * t * t * (10f + t * (-15f + 6f * t))
            for (i in weights.indices) weights[i] = from[i] * (1f - blend) + if (i == target) blend else 0f
        }
    }

    private fun approachingBoundary(state: VizRenderState): Boolean {
        val future = state.future ?: return false
        for (kind in BOUNDARIES) {
            val upcoming = future.nextEvent(kind) ?: continue
            val event = upcoming.event
            if (upcoming.secondsUntil !in 0f..1.2f || event.detection.confidence < 0.6f ||
                !event.detection.confidence.isFinite() || event.sameIdentity(anticipated) ||
                event.generation != state.frame.generation || event.analysisRevision != state.frame.analysisRevision) continue
            anticipated = event
            return true
        }
        return false
    }

    private fun moveTo(next: Int, seconds: Float) {
        weights.copyInto(from)
        visited[target] = 1f
        target = next
        progress = 0f
        duration = seconds.coerceIn(2.5f, 12f)
        dwell = 0f
        exploration = 0f
        transitions++
    }

    fun reset() {
        weights.fill(0f); weights[0] = 1f
        weights.copyInto(from); visited.fill(0f)
        random = Rng(seed); target = 0; transitions = 0; progress = 1f
        dwell = 0f; exploration = 0f; activity = 0f; previousActivity = 0f; contrast = 0f
        anticipated = null
    }

    private companion object {
        val BOUNDARIES = arrayOf(AudioEventKind.Drop, AudioEventKind.Breakdown, AudioEventKind.SectionBoundary)
    }
}
