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
    private fun bandsFrame(t: Float, bands: FloatArray, held: Boolean = false) = SpectrumFrame(
        (t * 1e6).toLong(), bands, bands, FloatArray(0), 0.5f, 0f, 0f, 0f, 0f, 0f, energy = 0.5f, held = held)

    @Test fun theSunHasThirtyTwoGapsFromTheBands() {
        assertEquals(32, NeonLoFiWorld.GAPS)
        val low = NeonLoFiWorld()
        val bass = FloatArray(48) { if (it < 3) 0.9f else 0.02f }
        for (i in 1..60) low.advance(state(bandsFrame(i / 60f, bass), i / 60f), low.controls, 0.37f)
        assertTrue(low.gaps[0] > 0.5f, "the bottom gap is ${low.gaps[0]}")
        assertTrue(low.gaps[31] < 0.1f, "the top gap is ${low.gaps[31]}")
        val high = NeonLoFiWorld()
        val treble = FloatArray(48) { if (it >= 40) 0.9f else 0.02f }
        for (i in 1..60) high.advance(state(bandsFrame(i / 60f, treble), i / 60f), high.controls, 0.37f)
        assertTrue(high.gaps[31] > 0.5f, "the top gap is ${high.gaps[31]}")
        assertTrue(high.gaps[0] < 0.1f, "the bottom gap is ${high.gaps[0]}")
    }

    @Test fun theTerracesAreTheSpectrumBackInTime() {
        val w = NeonLoFiWorld()
        val settings = w.controls.copyOf().apply { this[6] = 0f }
        val first = FloatArray(48) { if (it in 9..11) 0.9f else 0.02f }
        val second = FloatArray(48) { if (it in 36..38) 0.9f else 0.02f }
        var t = 0f
        repeat(30) { t += 1f / 60f; w.advance(state(bandsFrame(t, first), t), settings, 0.37f) }
        repeat(180) { t += 1f / 60f; w.advance(state(bandsFrame(t, second), t), settings, 0.37f) }
        val travel = w.flight.travel.toFloat()
        assertEquals(0f, travel)
        val nearHigh = w.floorHeight(12, travel + 1f); val nearLow = w.floorHeight(3, travel + 1f)
        val backHigh = w.floorHeight(12, travel + 40f); val backLow = w.floorHeight(3, travel + 40f)
        assertTrue(nearHigh > nearLow, "now the high lane stands taller: $nearHigh against $nearLow")
        assertTrue(backLow > backHigh, "3.3 s back the low lane stands taller: $backLow against $backHigh")
        // A paused player stamps no row.
        val terrace = w.terrace.copyOf()
        repeat(120) { t += 1f / 60f; w.advance(state(bandsFrame(t, first, held = true), t), settings, 0.37f) }
        assertContentEquals(terrace, w.terrace)
    }

    @Test fun theTerracesAreContinuousBetweenRows() {
        val w = NeonLoFiWorld()
        val settings = w.controls.copyOf().apply { this[6] = 0f }
        for (i in 1..300) {
            val t = i / 60f
            // A new loud lane every half second, so neighbouring rows differ.
            val loud = (i / 30) % 16
            val bands = FloatArray(48) { if (it / 3 == loud) 0.9f else 0.05f }
            w.advance(state(bandsFrame(t, bands), t), settings, 0.37f)
        }
        var checked = 0
        for (lane in 0..15) {
            var fine = 0f; var row = 0f
            var z = 2f
            while (z < 44f) {
                fine = max(fine, abs(w.floorHeight(lane, z + 0.1f) - w.floorHeight(lane, z)))
                row = max(row, abs(w.floorHeight(lane, z + 3f) - w.floorHeight(lane, z)))
                z += 0.1f
            }
            // Only the lanes that were loud inside the last four seconds change between rows.
            if (row < 0.05f) continue
            checked++
            assertTrue(fine < row * 0.25f, "lane $lane jumps $fine in a tenth of a unit against $row a row")
        }
        assertTrue(checked >= 6, "only $checked lanes changed between rows")
    }

    /** A world fed a steady rising spectrum, so every lane stands at its own height. */
    private fun rising(seconds: Float, cycle: Float = 2f, w: NeonLoFiWorld = NeonLoFiWorld(), start: Float = 0f,
        before: (Int) -> Unit = {}): NeonLoFiWorld {
        val bands = FloatArray(48) { 0.05f + 0.9f * it / 47f }
        val settings = w.controls.copyOf().apply { this[6] = 0f }
        for (i in 1..(seconds * 60).toInt()) {
            before(i)
            val t = start + i / 60f
            w.advance(state(bandsFrame(t, bands), t), settings, 0.37f, cycle = cycle)
        }
        return w
    }

    @Test fun theFormIsTheRegionAndAMorphRelaysTheLanes() {
        val w = rising(2f)
        assertTrue(NeonLoFiWorld.FORMS[w.form] in listOf("Highway", "Mountain pass", "City", "Coast"))
        assertEquals((0 until 4).maxBy { w.regions.weights[it] }, w.form)
        val z = w.flight.travel.toFloat() + 10f
        val top = w.floorHeight(15, z)
        val bottom = w.floorHeight(0, z)
        assertTrue(top > bottom + 0.2f, "the lanes differ: $top against $bottom")
        w.morph()
        var most = 0f
        rising(2.1f, w = w, start = 2f) { most = max(most, w.laneMix) }
        assertTrue(most > 0.9f, "the relay eased only to $most")
        assertTrue(w.laneMirror)
        assertEquals(0f, w.laneMix)
        assertEquals(top, w.floorHeight(0, z), 0.02f, "lane 0 now stands where lane 15 stood")
        assertFalse(w.towerSwap)
        w.morph()
        rising(2.1f, w = w, start = 4.1f)
        assertTrue(w.towerSwap, "the second morph swaps the towers")
        assertTrue(w.laneMirror, "the second morph leaves the lanes")
    }

    @Test fun aBirthRaisesATowerClusterAhead() {
        val w = rising(2f)
        assertTrue(w.clusterAge < 0f)
        w.birth()
        assertEquals(0f, w.clusterAge)
        rising(2.05f, w = w, start = 2f)
        assertEquals(1f, w.clusterAge)
        val paint = NeonLoFiPaint().also { it.prepare(state(bandsFrame(4.1f, FloatArray(48) { 0.5f }), 4.1f), w) }
        val decor = NeonLoFiDecor().also { it.build(w, paint, 960f, 540f, false) }
        assertEquals(4, decor.clusterTowers)
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
    @Test fun controlsFollowTheSlidersWhilePaused() {
        val w = NeonLoFiWorld(); run(w, 5f)
        val before = w.controls[3]
        val settings = w.controls.copyOf().apply { this[3] = before + 0.5f }
        repeat(180) { w.advance(state(neonFrame(5_000_000, held = true), 6f + it / 60f), settings, 0.9f) }
        assertEquals(settings[3], w.controls[3], 0.01f)
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
