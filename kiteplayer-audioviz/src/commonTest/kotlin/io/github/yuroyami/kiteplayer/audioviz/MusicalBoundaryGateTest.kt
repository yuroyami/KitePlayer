package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.MusicalBoundaryGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicalBoundaryGateTest {
    private fun event(sequence: Long, kind: AudioEventKind, support: Float = 0.8f,
        generation: Generation = Generation.Initial, revision: Long = 0L) =
        AudioEvent(generation, revision, sequence, AudioDetection(kind, 1_000_000L, 1_010_000L, 0.2f, support, 0.5f))

    private fun frame(vararg events: AudioEvent, generation: Generation = Generation.Initial,
        revision: Long = 0L, reset: Boolean = false): SpectrumFrame =
        SpectrumFrame(1_000_000L, FloatArray(1), FloatArray(1), FloatArray(1), 0f, 0f, 0f, 0f, 0f, 0f,
            generation = generation, analysisRevision = revision,
            events = AudioEventDelivery(generation, revision, 1_000_000L,
                events.map { DeliveredAudioEvent(it, 0L) }.toTypedArray(), reset = reset))

    @Test
    fun confidenceIdentityAndKindAreIndependentFromEventEnergy() {
        val gate = MusicalBoundaryGate()
        assertNull(gate.read(frame(event(0, AudioEventKind.EnergyRise, 1f))))
        assertNull(gate.read(frame(event(1, AudioEventKind.SectionBoundary, 0.59f))))
        val accepted = event(2, AudioEventKind.SectionBoundary, 0.6f)
        assertEquals(accepted, gate.read(frame(accepted)), "a quiet supported boundary is still valid")
        assertNull(gate.read(frame(accepted)), "the same identity cannot change a scene twice")
        assertNull(gate.read(frame(event(0, AudioEventKind.Drop))), "older identities cannot replay")
    }

    @Test
    fun coalescingConsumesAllIdentitiesAndPrefersTheDrop() {
        val gate = MusicalBoundaryGate()
        val generic = event(0, AudioEventKind.SectionBoundary)
        val drop = event(1, AudioEventKind.Drop)
        val thin = event(2, AudioEventKind.Breakdown)
        assertEquals(drop, gate.read(frame(generic, drop, thin)))
        assertNull(gate.read(frame(thin)), "unselected boundaries were consumed too")
    }

    @Test
    fun resetsDiscardCatchupAndRejectRetiredAnalysis() {
        val gate = MusicalBoundaryGate()
        val first = event(0, AudioEventKind.SectionBoundary)
        assertEquals(first, gate.read(frame(first)))
        assertNull(gate.read(frame(first, revision = 1)))
        val restarted = event(0, AudioEventKind.SectionBoundary, revision = 1)
        assertEquals(restarted, gate.read(frame(restarted, revision = 1)))
        val newGeneration = Generation(1)
        val missed = event(0, AudioEventKind.Drop, generation = newGeneration)
        assertNull(gate.read(frame(missed, generation = newGeneration, reset = true)))
        assertNull(gate.read(frame(missed, generation = newGeneration)))
        val next = event(1, AudioEventKind.SectionBoundary, generation = newGeneration)
        assertEquals(next, gate.read(frame(next, generation = newGeneration)))
    }
}
