package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.AudioEvent
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame

/** One scene decision per delivered batch. This gate does not recognize musical structure. */
internal class MusicalBoundaryGate {
    private var generation: Generation? = null
    private var revision = -1L
    private var sequence = -1L

    fun read(frame: SpectrumFrame): AudioEvent? {
        val old = generation
        if (old != null && (frame.generation < old || frame.generation == old && frame.analysisRevision < revision)) return null
        if (old != frame.generation || revision != frame.analysisRevision) {
            generation = frame.generation
            revision = frame.analysisRevision
            sequence = -1L
        }
        val delivery = frame.events ?: return null
        if (delivery.generation != frame.generation || delivery.analysisRevision != frame.analysisRevision) return null
        var chosen: AudioEvent? = null
        for (index in 0 until delivery.size) {
            val event = delivery[index].event
            if (event.generation != frame.generation || event.analysisRevision != frame.analysisRevision ||
                event.sequence <= sequence) continue
            sequence = event.sequence
            if (delivery.reset || event.detection.confidence < 0.6f || !event.detection.confidence.isFinite()) continue
            val rank = priority(event.detection.kind)
            if (rank > 0 && (chosen == null || rank > priority(chosen.detection.kind))) chosen = event
        }
        return chosen
    }

    private fun priority(kind: AudioEventKind): Int = when (kind) {
        AudioEventKind.Drop -> 3
        AudioEventKind.Breakdown -> 2
        AudioEventKind.SectionBoundary -> 1
        else -> 0
    }
}
