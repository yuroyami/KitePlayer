package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.*
import kotlin.math.*
import kotlin.test.*

class NeonLoFiWorldTest {
    private fun state(frame: SpectrumFrame, time: Float, dt: Float = 1f / 60f, motion: Float = 1f) =
        VizRenderState(frame, time, dt, VizPalette.Vapor).also { it.motionScale = motion }
    private fun run(world: NeonLoFiWorld, seconds: Float, fps: Int = 60, value: Float = 0.6f, start: Float = 0f,
        motion: Float = 1f, settings: FloatArray = world.controls.copyOf()) {
        for (i in 1..(seconds * fps).toInt()) {
            val t = start + i.toFloat() / fps
            world.advance(state(neonFrame((t * 1e6).toLong(), value), t, 1f / fps, motion), settings, 0.37f)
        }
    }
    @Test fun heldFreezesEveryActorWhileSilenceRecordsZerosAndSettles() {
        val w = NeonLoFiWorld(); run(w, 5f)
        val before = w.lanes.copyOf(); val profile = w.profile.copyOf(); val travel = w.flight.travel
        val time = w.time; val newest = w.history.newestMicros; val phase = w.flight.phase
        repeat(240) { w.advance(state(neonFrame(5_000_000, held = true), 6f + it / 60f), w.controls, 0.9f) }
        assertContentEquals(before, w.lanes); assertContentEquals(profile, w.profile)
        assertEquals(travel, w.flight.travel); assertEquals(time, w.time); assertEquals(newest, w.history.newestMicros)
        assertEquals(phase, w.flight.phase)
        run(w, 4f, value = 0f, start = 10f)
        assertTrue(w.lanes.all { it < 0.0001f }); assertEquals(0f, w.flight.velocity)
        assertContentEquals(profile, w.profile)
        assertEquals(0f, w.history.valueAt(14_000_000, 0))
    }
    @Test fun seekingWhilePausedClearsOldHistoryWithoutReplayingHits() {
        val w = NeonLoFiWorld(); run(w, 5f)
        w.advance(state(neonFrame(1_000_000, held = true, revision = 2), 6f), w.controls, 0.37f)
        assertEquals(0, w.history.count)
        assertTrue(w.lanes.all { it == 0f }); assertTrue(w.profile.all { it == 0f })
        assertEquals(0L, w.eventCount)
    }
    @Test fun cameraStopAndReducedMotionPreserveLocalMusicAndHistory() {
        for (motion in listOf(0f, 0.3f, 1f)) {
            val w = NeonLoFiWorld(); val settings = w.controls.copyOf().apply { this[6] = 0f }
            run(w, 5f, motion = motion, settings = settings)
            assertEquals(0.0, w.flight.travel)
            assertTrue(w.lanes.sum() > 1f); assertTrue(w.history.count > 10)
        }
        val still = NeonLoFiWorld(); run(still, 5f, motion = 0f)
        assertEquals(0.0, still.flight.travel); assertEquals(0f, still.time); assertEquals(0f, still.flight.bank)
        val reduced = NeonLoFiWorld(); run(reduced, 5f, motion = 0.3f)
        val full = NeonLoFiWorld(); run(full, 5f)
        assertEquals(full.flight.travel * 0.3, reduced.flight.travel, 0.001)
    }
    @Test fun noRenderingOrRepeatedFrameCanConsumeAnEventTwice() {
        val w = NeonLoFiWorld(); run(w, 1f)
        fun event(sequence: Long, micros: Long, kind: AudioEventKind, strength: Float) = DeliveredAudioEvent(
            AudioEvent(Generation.Initial, 0, sequence, AudioDetection(kind, micros, micros, strength, 0.95f, 0.8f)), 0)
        val items = arrayOf(event(0, 1_010_000, AudioEventKind.LowTransient, 0.9f),
            event(1, 1_010_000, AudioEventKind.Onset, 0.9f),
            event(2, 1_042_000, AudioEventKind.LowTransient, 0.2f),
            event(3, 1_060_000, AudioEventKind.HighTransient, 0.6f))
        val bands = FloatArray(64) { 0.5f }
        val frame = SpectrumFrame(1_066_667, bands, bands, FloatArray(0), 0.5f, 0.5f, 0.5f, 0.5f, 0f, 0f,
            events = AudioEventDelivery(Generation.Initial, 0, 1_066_667, items))
        val sample = state(frame, 1.066667f, 1f / 15f)
        w.advance(sample, w.controls, 0.37f)
        assertEquals(4L, w.eventCount); assertEquals(2L, w.compoundCount)
        assertTrue(w.impulses[0] > 0.9f * 0.32f * exp(-0.057f * 5f), "Weak attack after strong still contributes")
        assertTrue(w.impulses[0] < 0.4f, "One attack's generic detection must not double its regional impulse")
        val impulses = w.impulses.copyOf()
        repeat(5) { w.advance(sample, w.controls, 0.37f) }
        assertContentEquals(impulses, w.impulses); assertEquals(4L, w.eventCount)
        w.advance(state(frame, 1.08f), w.controls, 0.37f)
        assertEquals(4L, w.eventCount)
    }
    @Test fun fullSpectrumFloorRespondsWithNoTravelNoEventsAndNoPrivateGain() {
        val low = NeonLoFiWorld(); val high = NeonLoFiWorld()
        run(low, 2f, value = 0.2f, motion = 0f); run(high, 2f, value = 0.8f, motion = 0f)
        for (lane in 0..15) assertTrue(high.floorHeight(lane, 20f) > low.floorHeight(lane, 20f) * 1.8f)
        assertEquals(0L, low.eventCount); assertEquals(0L, high.eventCount)
    }
    @Test fun independentBassAndBodyMeasurementsApplyLocalFloorLoad() {
        fun sample(bass: Float, mid: Float): NeonLoFiWorld {
            val w = NeonLoFiWorld(); val bands = FloatArray(64) { 0.2f }
            for (i in 0..120) {
                val frame = SpectrumFrame(i * 16_667L, bands, bands, FloatArray(0), 0.5f, bass, mid, 0f, 0f, 0f)
                w.advance(state(frame, i / 60f), w.controls, 0.37f)
            }
            return w
        }
        val base = sample(0f, 0f); val low = sample(0.8f, 0f); val body = sample(0f, 0.8f)
        assertTrue(low.floorHeight(1, 20f) > base.floorHeight(1, 20f) + 0.1f)
        assertEquals(base.floorHeight(8, 20f), low.floorHeight(8, 20f), 0.00001f)
        assertTrue(body.floorHeight(8, 20f) > base.floorHeight(8, 20f) + 0.1f)
        assertEquals(base.floorHeight(1, 20f), body.floorHeight(1, 20f), 0.00001f)
    }
    @Test fun automaticRegionsNeedEvidenceAndSixtySecondDwell() {
        val region = NeonLoFiRegions()
        repeat(240 * 60) { region.advance(1f / 60, 1f, -1, 2f, 0.37f, 0.5f, 0.5f, 0.5f, false) }
        assertEquals(0, region.starts, "A held texture never becomes a timed carousel")
        region.advance(0.016f, 1f, -1, 2f, 0.37f, 0.5f, 0.5f, 0.5f, true)
        assertEquals(1, region.starts)
        repeat(59 * 60) { region.advance(1f / 60, 1f, -1, 2f, 0.37f, 0.8f, 0.9f, 0.1f, true) }
        assertEquals(1, region.starts)
        repeat(2 * 60) { region.advance(1f / 60, 1f, -1, 2f, 0.37f, 0.8f, 0.9f, 0.1f, true) }
        assertEquals(2, region.starts)
    }
    @Test fun retargetsStayContinuousAndWeightsConserveTheScene() {
        val region = NeonLoFiRegions()
        var previous = region.weights.copyOf()
        repeat(1800) { i ->
            region.advance(1f / 60, 1f, (i / 137) % 4, 1f, 0.37f, 0.5f, 0.5f, 0.5f, false)
            assertEquals(1f, region.weights.sum(), 0.0001f)
            assertTrue(region.weights.all { it in -0.00001f..1.00001f })
            if (i > 0) for (j in 0..3) assertTrue(abs(region.weights[j] - previous[j]) < 0.01f)
            previous = region.weights.copyOf()
        }
    }
    @Test fun resetReplaysTheSameConfiguredWorldWithoutLeakingThePreviousRoute() {
        val w = NeonLoFiWorld()
        fun replay(): List<Float> {
            for (i in 0..900) w.advance(state(neonFrame(i * 16_667L), i / 60f), w.controls, 0.84f)
            return w.lanes.toList() + w.profile.toList() + w.regions.weights.toList() +
                listOf(w.flight.travel.toFloat(), w.flight.bank, w.flight.layout, w.time)
        }
        val first = replay(); w.reset(); assertEquals(first, replay())
    }
    @Test fun simulationCadenceKeepsHistoryAndLocalGeometryConsistent() {
        val runs = listOf(15, 30, 60, 120).map { fps -> NeonLoFiWorld().also { run(it, 10f, fps) } }
        for (w in runs.drop(1)) {
            assertEquals(runs[0].history.newestMicros, w.history.newestMicros)
            assertContentEquals(runs[0].history.bands, w.history.bands)
            for (i in 0..15) assertEquals(runs[0].lanes[i], w.lanes[i], 0.0001f)
            assertEquals(runs[0].flight.travel, w.flight.travel, 0.25)
        }
    }
}
