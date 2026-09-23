package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** Choreography contracts, independent of renderer, GPU, frame rate and genre. */
class GlitchSceneTest {
    @Test fun spectrumRegionsRemainIndependentWithAllTravelStopped() {
        fun scene(region: Int): GlitchScene {
            val bands = FloatArray(48) { if (it / 16 == region) 0.8f else 0.1f }
            return GlitchScene().apply {
                advance(state(frame(0, bands = bands, low = if (region == 0) 0.8f else 0.1f,
                    body = if (region == 1) 0.8f else 0.1f,
                    air = if (region == 2) 0.8f else 0.1f), motion = 0f),
                    travelSpeed = 0f, rotationSpeed = 0f)
            }
        }
        val low = scene(0); val body = scene(1); val high = scene(2)
        assertTrue(low.bands[8] > body.bands[8] * 5)
        assertTrue(body.bands[32] > high.bands[32] * 5)
        assertTrue(high.bands[56] > low.bands[56] * 5)
        assertTrue(low.bass > body.bass * 5)
        assertTrue(body.body > high.body * 5)
        assertTrue(high.air > low.air * 5)
        for (scene in listOf(low, body, high)) {
            assertEquals(0f, scene.travel); assertEquals(0f, scene.turn)
        }
    }

    @Test fun sustainedTextureDoesNotBecomeATimedCarousel() {
        val scene = GlitchScene()
        for (i in 0..3600) scene.advance(state(frame(i * 16_667L), i / 60f))
        assertEquals(0, scene.transitions)
        assertEquals(0L, scene.acceptedHits)
        assertTrue(scene.travel > 0f, "A sustained sound can travel without inventing musical cues")
    }

    @Test fun genuineTransientsCanRecomposeAnUnstudiedSongAfterResidency() {
        val active = GlitchScene(); val disabled = GlitchScene()
        var sequence = 0L
        for (i in 0..900) {
            val time = i * 16_667L
            val events = if (i % 15 == 0) arrayOf(event(sequence++, time, AudioEventKind.LowTransient)) else emptyArray()
            val sample = state(frame(time, events = events), i / 60f, if (i == 0) 0f else 1f / 60f)
            active.advance(sample)
            disabled.advance(sample, changes = 0f)
            if (i < 360) assertEquals(0, active.transitions, "Fallback scene changes need six seconds of residence")
        }
        assertTrue(active.acceptedHits > 30)
        assertTrue(active.transitions > 0, "Real recurring transients can reorganize the composition")
        assertEquals(0, disabled.transitions, "Scene change control can hold one composition")
    }

    @Test fun materialFeatureChangeCanRecomposeWithoutInventingABeat() {
        val scene = GlitchScene()
        for (i in 0..480) {
            scene.advance(state(frame(i * 16_667L, value = if (i < 400) 0.2f else 0.85f,
                centroid = if (i < 400) 0.1f else 0.9f), i / 60f))
        }
        assertEquals(0L, scene.acceptedHits)
        assertEquals(1, scene.transitions)
    }

    @Test fun mappedBoundariesRespondOnceAndEnergyRiseIsNotASection() {
        val scene = GlitchScene()
        val rise = event(0, 100_000, AudioEventKind.EnergyRise, source = AudioEventSource.SongMap)
        scene.advance(state(frame(100_000, events = arrayOf(rise)), 0.1f))
        assertEquals(0, scene.transitions)
        val uncertain = event(1, 110_000, AudioEventKind.Drop, confidence = 0.59f, source = AudioEventSource.SongMap)
        scene.advance(state(frame(110_000, events = arrayOf(uncertain)), 0.11f))
        assertEquals(0, scene.transitions)
        val drop = event(2, 120_000, AudioEventKind.Drop, source = AudioEventSource.SongMap)
        scene.advance(state(frame(120_000, events = arrayOf(drop)), 0.12f))
        assertEquals(1, scene.transitions); assertEquals(4, scene.composition)
        val before = scene.weights.copyOf()
        scene.advance(state(frame(130_000, events = arrayOf(drop)), 0.13f))
        assertEquals(1, scene.transitions)
        for (i in before.indices) assertTrue(abs(scene.weights[i] - before[i]) < 0.03f)
        // A different source still cannot interrupt a dissolve twenty milliseconds later.
        val breakdown = event(0, 140_000, AudioEventKind.Breakdown, source = AudioEventSource.LiveStructure)
        scene.advance(state(frame(140_000, events = arrayOf(breakdown)), 0.14f))
        assertEquals(1, scene.transitions); assertEquals(4, scene.composition)
        for (pts in 200_000L..1_600_000L step 100_000L) scene.advance(state(frame(pts), dt = 0.1f))
        // The suppressed cue is consumed, not replayed after the cooldown. A fresh cue acts.
        scene.advance(state(frame(1_620_000, events = arrayOf(breakdown))))
        assertEquals(1, scene.transitions)
        val next = event(1, 1_650_000, AudioEventKind.Breakdown, source = AudioEventSource.LiveStructure)
        scene.advance(state(frame(1_650_000, events = arrayOf(next))))
        assertEquals(2, scene.transitions); assertEquals(2, scene.composition)
    }

    @Test fun onePhysicalAttackIsNotDoubledByGenericAndRegionalDetections() {
        val scene = GlitchScene()
        val events = arrayOf(
            event(0, 1_010_000, AudioEventKind.LowTransient, strength = 0.7f),
            event(1, 1_010_000, AudioEventKind.Onset, strength = 0.7f),
            event(2, 1_042_000, AudioEventKind.LowTransient, strength = 0.2f),
        )
        val sample = state(frame(1_066_667, events = events), 1f)
        scene.advance(sample)
        assertEquals(2L, scene.acceptedHits)
        assertEquals(0.9f, scene.lowAccent, 0.00001f)
        val before = snapshot(scene)
        repeat(5) { scene.advance(sample) }
        assertEquals(before, snapshot(scene), "Echo and front cannot integrate the same instant twice")
        scene.advance(state(frame(1_083_334, events = events), 1.016667f))
        assertEquals(2L, scene.acceptedHits, "A new display time cannot replay the same event identity")
        val travel = scene.travel
        scene.advance(state(frame(1_083_334,
            events = arrayOf(event(3, 1_080_000, AudioEventKind.HighTransient))), 1.016667f))
        assertEquals(travel, scene.travel, "A different frame at one display instant adds no elapsed time")
        assertEquals(3L, scene.acceptedHits)
        assertTrue(scene.highAccent > 0.5f)
    }

    @Test fun heldSelectionSeedsSpectrumAndManualControlsDoNotAdvanceTime() {
        val scene = GlitchScene()
        val held = state(frame(1_000_000, value = 0.6f, held = true), 1f)
        scene.advance(held)
        assertTrue(scene.bands.all { it == 0.6f })
        assertTrue(scene.history.all { it > 0.5f })
        val before = snapshot(scene)
        repeat(120) { scene.advance(state(frame(1_000_000, value = 0.1f, held = true), 2f + it / 60f)) }
        assertEquals(before, snapshot(scene))
        scene.configure(compositionMode = 3, response = 0.5f)
        assertEquals(3, scene.composition)
        assertEquals(0.95f, scene.weights[3])
        assertTrue(scene.bands.all { it == 0.3f })
        assertEquals(0f, scene.travel); assertEquals(0f, scene.turn)
        scene.advance(held, response = 1f, compositionMode = 5)
        assertEquals(2, scene.composition); assertEquals(0.95f, scene.weights[2])
        assertTrue(scene.bands.all { it == 0.6f })
        assertEquals(0L, scene.acceptedHits)
    }

    @Test fun seekAndAnalysisIdentityClearOldAccentsEvenWhilePaused() {
        val scene = GlitchScene()
        scene.advance(state(frame(5_000_000,
            events = arrayOf(event(0, 5_000_000, AudioEventKind.LowTransient))), 1f))
        assertTrue(scene.lowAccent > 0f)
        scene.advance(state(frame(1_000_000, value = 0.15f, held = true, revision = 1), 2f))
        assertEquals(0f, scene.lowAccent); assertEquals(0f, scene.travel)
        assertEquals(0L, scene.acceptedHits)
        assertTrue(scene.bands.all { abs(it - 0.15f) < 0.00001f })
        val current = snapshot(scene)
        scene.advance(state(frame(5_000_000, value = 1f, revision = 0), 3f))
        assertEquals(current, snapshot(scene), "A retired revision cannot replace the current seek")
        scene.advance(state(frame(0, value = 0.2f, held = true, generation = Generation(1)), 4f))
        assertTrue(scene.bands.all { abs(it - 0.2f) < 0.00001f })
        assertEquals(0f, scene.turn)
    }

    @Test fun catchupResetDiscardsOldEventsAndTheRepeatedDeliveryDoesNotResetAgain() {
        val scene = GlitchScene()
        val old = event(0, 1_000_000, AudioEventKind.Drop, source = AudioEventSource.SongMap)
        val sample = frame(1_000_000, events = arrayOf(old), reset = true)
        scene.advance(state(sample, 1f))
        assertEquals(0, scene.transitions)
        val firstTravel = scene.travel
        scene.advance(state(sample, 1.016667f))
        assertTrue(scene.travel > firstTravel, "One reset delivery must not erase each subsequent display frame")
        scene.advance(state(frame(1_016_667, events = arrayOf(old)), 1.033334f))
        assertEquals(0, scene.transitions)
    }

    @Test fun missingAnalysisIsNotSilenceOrABreakdownAndReducedMotionRetainsSpectrum() {
        val scene = GlitchScene()
        scene.advance(state(frame(0), motion = 0f))
        assertTrue(scene.bands.sum() > 20f)
        assertEquals(0f, scene.travel)
        for (i in 1..120) scene.advance(state(frame(i * 16_667L,
            available = AnalysisAvailability.Unavailable), i / 60f))
        assertEquals(0, scene.transitions)
        assertEquals(0L, scene.acceptedHits)
        assertTrue(scene.level < 0.001f)
    }

    @Test fun historyAndContinuousMotionHaveTheSameCadenceAtFifteenThroughOneHundredTwentyHz() {
        fun run(fps: Int): GlitchScene = GlitchScene().apply {
            for (i in 0..fps * 4) {
                val t = i.toFloat() / fps
                advance(state(frame(i * 1_000_000L / fps, value = 0.2f + t * 0.1f,
                    density = 0.3f, centroid = 0.3f), t, if (i == 0) 0f else 1f / fps), changes = 0f)
            }
        }
        val reference = run(60)
        for (fps in listOf(15, 30, 120)) {
            val other = run(fps)
            assertEquals(reference.historySamples, other.historySamples)
            for (i in reference.history.indices) assertEquals(reference.history[i], other.history[i], 0.00001f)
            assertEquals(reference.travel, other.travel, 0.015f)
            assertEquals(reference.turn, other.turn, 0.005f)
        }
    }

    @Test fun resetReplaysTheSameMusicWithoutKeepingThePreviousComposition() {
        val scene = GlitchScene()
        fun run(): List<Float> {
            for (i in 0..300) scene.advance(state(frame(i * 16_667L,
                value = 0.5f + 0.25f * sin(i * 0.04f)), i / 60f))
            return snapshot(scene)
        }
        val first = run()
        scene.reset()
        assertEquals(first, run())
    }

    @Test fun invalidSpectrumCannotPoisonTheSceneAndLayerWeightsStayBounded() {
        val scene = GlitchScene()
        val bands = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -5f, 3f)
        for (i in 0..120) scene.advance(state(frame(i * 16_667L, bands = bands), i / 60f),
            response = if (i % 2 == 0) Float.NaN else 2f)
        assertTrue(snapshot(scene).all { it.isFinite() })
        assertTrue(scene.bands.all { it in 0f..1f })
        assertTrue(scene.weights.all { it in 0f..1f })
        assertTrue(scene.weights.sum() > 1f, "Additive layer strengths are not an opacity crossfade")
    }

    private fun snapshot(scene: GlitchScene): List<Float> = scene.bands.toList() + scene.history.toList() +
        scene.weights.toList() + listOf(scene.level, scene.bass, scene.body, scene.air, scene.lowAccent,
            scene.bodyAccent, scene.highAccent, scene.pressure, scene.travel, scene.turn, scene.hue,
            scene.composition.toFloat(), scene.transitions.toFloat(), scene.acceptedHits.toFloat(), scene.historySamples.toFloat())

    private fun state(frame: SpectrumFrame, time: Float = frame.ptsMicros / 1_000_000f,
        dt: Float = 1f / 60f, motion: Float = 1f): VizRenderState =
        VizRenderState(frame, time, dt, VizPalette.Prism).also { it.motionScale = motion }

    private fun frame(pts: Long, value: Float = 0.6f, bands: FloatArray = FloatArray(64) { value },
        low: Float = value, body: Float = value, air: Float = value,
        held: Boolean = false, density: Float = 0.35f, centroid: Float = 0.4f,
        generation: Generation = Generation.Initial, revision: Long = 0L,
        available: AnalysisAvailability = AnalysisAvailability.Ready,
        events: Array<AudioEvent>? = null, reset: Boolean = false): SpectrumFrame =
        SpectrumFrame(pts, bands, bands, FloatArray(0), value, low, body, air, 0f, 0f,
            energy = value, mood = 0.4f, density = density, centroid = centroid,
            generation = generation, analysisRevision = revision, held = held, availability = available,
            events = events?.let { AudioEventDelivery(generation, revision, pts,
                it.map { event -> DeliveredAudioEvent(event, 0L) }.toTypedArray(), reset = reset) })

    private fun event(sequence: Long, pts: Long, kind: AudioEventKind, strength: Float = 0.8f,
        confidence: Float = 0.9f, source: AudioEventSource = AudioEventSource.LiveTransient): AudioEvent =
        AudioEvent(Generation.Initial, 0L, sequence, AudioDetection(kind, pts, pts, strength, confidence, 0.8f), source)
}
