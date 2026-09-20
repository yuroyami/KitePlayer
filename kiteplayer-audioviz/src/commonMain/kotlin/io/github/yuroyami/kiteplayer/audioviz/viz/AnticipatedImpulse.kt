package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.AudioEvent
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.UpcomingAudioEvent
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring

/**
 * Applies each low transient hit once, whether it was first seen through lookahead or delivery.
 *
 * A detection the detector does not support is not a hit, and moves nothing: see
 * [io.github.yuroyami.kiteplayer.audioviz.AudioDetection.isHit].
 */
internal class AnticipatedImpulse(private val spring: Spring) {
    private val pending = ArrayDeque<AudioEvent>(16)
    private var lastAnticipated: AudioEvent? = null
    private var generation: Generation? = null
    private var revision = 0L

    fun apply(frame: SpectrumFrame, upcoming: UpcomingAudioEvent?, scale: Float) {
        if (generation != frame.generation || revision != frame.analysisRevision || frame.events?.reset == true) {
            reset()
            generation = frame.generation
            revision = frame.analysisRevision
        }
        val delivery = frame.events
        if (delivery != null) {
            for (index in 0 until delivery.size) {
                val event = delivery[index].event
                if (event.detection.kind != AudioEventKind.LowTransient || !event.detection.isHit) continue
                val anticipated = pending.indexOfFirst { sameIdentity(it, event) }
                if (anticipated >= 0) pending.removeAt(anticipated)
                else spring.kick(event.detection.strength * scale)
            }
        } else if (frame.kick > 0f) {
            // Legacy author-created frames have no event identity or multiplicity.
            if (pending.isNotEmpty()) pending.removeFirst() else spring.kick(frame.kick * scale)
        }

        val future = upcoming ?: return
        val event = future.event
        if (event.generation != generation || event.analysisRevision != revision ||
            event.detection.kind != AudioEventKind.LowTransient || !event.detection.isHit ||
            event.detection.strength <= 0f ||
            future.secondsUntil !in 0f..spring.peakDelay || sameIdentity(lastAnticipated, event) ||
            pending.any { sameIdentity(it, event) }) return
        spring.kick(event.detection.strength * scale)
        // The player exposes 100 ms ahead and discards late/stalled bursts. Sixteen slots cover
        // that horizon plus the 250 ms catch-up budget at the detector's 30 ms combination floor.
        if (pending.size == 16) pending.removeFirst()
        pending.addLast(event)
        lastAnticipated = event
    }

    fun reset() {
        pending.clear()
        lastAnticipated = null
        generation = null
        revision = 0L
    }

    private fun sameIdentity(first: AudioEvent?, second: AudioEvent): Boolean = second.sameIdentity(first)
}
