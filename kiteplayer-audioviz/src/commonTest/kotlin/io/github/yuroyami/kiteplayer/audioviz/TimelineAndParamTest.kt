package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.Generation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Blending readings for fast displays, and the settings a drawing exposes. */
class TimelineAndParamTest {

    init { useSkiaGraphics() }

    private fun reading(pts: Long, value: Float, kick: Float = 0f, beatPhase: Float = 0f, generation: Generation = Generation.Initial, revision: Long = 0L,
        availability: AnalysisAvailability = AnalysisAvailability.Ready): SpectrumFrame =
        SpectrumFrame(
            ptsMicros = pts,
            bands = FloatArray(4) { value },
            peaks = FloatArray(4) { value },
            scope = FloatArray(4),
            level = value,
            bass = 0f,
            mid = 0f,
            treble = 0f,
            beat = 0f,
            pulse = 0f,
            kick = kick,
            beatPhase = beatPhase,
            generation = generation,
            analysisRevision = revision,
            availability = availability,
        )

    @Test
    fun warmupDoesNotCreateInterpolatedLevelsOrAnAvailableFuture() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0f, availability = AnalysisAvailability.WarmingUp))
        assertNull(timeline.interpolated(0L))
        assertNull(timeline.ahead(-5_000L, 0.005f))
        assertEquals(0f, timeline.availableAheadSeconds(-5_000L))
        timeline.push(reading(10_000L, 1f))
        assertNull(timeline.interpolated(5_000L))
        assertEquals(1f, timeline.interpolated(10_000L)?.level)
    }

    @Test
    fun unavailableMeasurementsBreakInterpolationAndCannotEmitEvents() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0.2f))
        timeline.push(reading(10_000L, 1f, kick = 1f, availability = AnalysisAvailability.Unavailable))
        timeline.push(reading(20_000L, 0.8f))
        assertNull(timeline.interpolated(5_000L))
        assertNull(timeline.interpolated(15_000L))
        assertEquals(0.2f, timeline.interpolated(0L)?.level)
        assertEquals(0f, timeline.sample(20_000L, 0L)?.kick)
    }

    @Test
    fun aMomentBetweenTwoReadingsIsBlended() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0f, kick = 1f))
        timeline.push(reading(10_000L, 1f))
        val middle = timeline.interpolated(5_000L)!!
        assertTrue(abs(middle.bands[0] - 0.5f) < 0.001f, "halfway should be half, was ${middle.bands[0]}")
        assertTrue(abs(middle.level - 0.5f) < 0.001f, "levels blend too, was ${middle.level}")
        assertEquals(1f, middle.kick, "a kick belongs to the reading it landed in and is not blended away")
    }

    @Test
    fun beforeTheFirstReadingThereIsNothing() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(10_000L, 1f))
        assertNull(timeline.interpolated(5_000L), "nothing has been heard yet at that moment")
    }

    @Test
    fun pastTheLastReadingTheLastIsHeld() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0.2f))
        timeline.push(reading(10_000L, 0.8f))
        val late = timeline.interpolated(50_000L)!!
        assertTrue(abs(late.bands[0] - 0.8f) < 0.001f, "past the end it should hold the newest, was ${late.bands[0]}")
    }

    @Test
    fun unavailableFutureDoesNotPretendToBeTheNewestAnalysis() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0.2f))
        timeline.push(reading(10_000L, 0.8f))
        assertNull(timeline.ahead(0L, 0.5f), "a retained past frame is not half a second of lookahead")
    }

    @Test
    fun drawingHelperPreservesUnavailableLookahead() {
        val state = VizRenderState(reading(0L, 1f, kick = 1f), 0f, 0.016f, VizPalette.Prism)
        assertNull(state.ahead(0.05f), "the current kick must not masquerade as a future kick")
    }

    @Test
    fun staleFeaturesExpireInsteadOfHoldingAnOldKickForever() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0.8f, kick = 1f))
        assertNull(timeline.interpolated(1_000_000L), "starved analysis must become unavailable")
    }

    @Test
    fun retiredWorkCannotRepopulateAResetTimeline() {
        val timeline = SpectrumTimeline(8)
        val retired = reading(0L, 1f)
        timeline.push(retired)
        timeline.reset(Generation(1))
        timeline.push(retired)
        assertNull(timeline.newest())
        timeline.push(reading(0L, 0.2f, generation = Generation(1), revision = timeline.revision))
        timeline.push(reading(10_000L, 0.8f, generation = Generation(1), revision = timeline.revision))
        assertEquals(Generation(1), timeline.interpolated(5_000L)?.generation)
        timeline.reset(Generation.Initial)
        assertEquals(Generation(1), timeline.generation, "an old reset must not roll back the timeline")
    }

    @Test
    fun aLocalResetRejectsBothOldFramesAndCapturedPublishers() {
        val timeline = SpectrumTimeline(8)
        val oldPublisher = timeline.publisher()
        val oldFrame = reading(0L, 1f)
        timeline.push(oldFrame)
        timeline.clear()
        timeline.push(oldFrame)
        assertNull(timeline.newest(), "the same audio generation does not revive retired analysis")
        assertTrue(!oldPublisher.push(oldFrame), "a retired publisher must not target the new history")
        val fresh = reading(10_000L, 0.5f, revision = timeline.revision)
        assertTrue(timeline.publisher().push(fresh))
        assertEquals(fresh, timeline.newest())
    }

    @Test
    fun negativeMediaTimeIsNotMissingAndUnknownFramesAreNotQueued() {
        val timeline = SpectrumTimeline(8)
        timeline.push(SpectrumFrame.silent(4, 4))
        assertNull(timeline.newest())
        timeline.push(reading(-20_000L, 0.1f))
        timeline.push(reading(-10_000L, 0.9f, kick = 1f))
        assertEquals(1f, timeline.sample(-10_000L, -15_000L)?.kick)
        assertEquals(-15_000L, timeline.interpolated(-15_000L)?.ptsMicros)
    }

    @Test
    fun aBeatPositionTakesTheShortWayRound() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0f, beatPhase = 0.9f))
        timeline.push(reading(10_000L, 0f, beatPhase = 0.1f))
        val middle = timeline.interpolated(5_000L)!!
        val fromTop = minOf(middle.beatPhase, 1f - middle.beatPhase)
        assertTrue(fromTop < 0.01f, "halfway from 0.9 to 0.1 is the top of the beat, not 0.5, was ${middle.beatPhase}")
    }

    @Test
    fun aHitBetweenDisplayFramesIsDeliveredExactlyOnce() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0.1f))
        timeline.push(reading(10_000L, 0.8f, kick = 0.9f))
        timeline.push(reading(20_000L, 0.2f))
        timeline.push(reading(30_000L, 0.3f))
        assertEquals(0.9f, timeline.sample(25_000L, 5_000L)!!.kick)
        assertEquals(0f, timeline.sample(28_000L, 25_000L)!!.kick)
        assertEquals(0f, timeline.sample(28_000L, 28_000L)!!.kick)
    }

    @Test
    fun aQueuedHitDoesNotFireBeforeItIsAudible() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0.1f))
        timeline.push(reading(10_000L, 0.8f, kick = 0.9f))
        assertEquals(0f, timeline.sample(9_000L, 0L)!!.kick)
        assertEquals(0.9f, timeline.sample(10_000L, 9_000L)!!.kick)
        assertEquals(0f, timeline.sample(11_000L, 10_000L)!!.kick)
    }

    @Test
    fun seekingDoesNotReplayOldHits() {
        val timeline = SpectrumTimeline(8)
        timeline.push(reading(0L, 0.1f, kick = 1f))
        timeline.push(reading(200_000L, 0.2f))
        assertEquals(0f, timeline.sample(200_000L, 900_000L)!!.kick)
    }

    @Test
    fun aSettingStaysInsideItsRange() {
        val param = VizParam("Speed", 0.5f, 2f, 1f)
        param.value = 10f
        assertEquals(2f, param.value)
        param.value = -3f
        assertEquals(0.5f, param.value)
        param.reset()
        assertEquals(1f, param.value)
    }

    @Test
    fun someDrawingsCanBeTuned() {
        val tunable = VizCatalog.create().filter { it.params.isNotEmpty() }
        println("drawings with settings: ${tunable.joinToString { "${it.name} (${it.params.joinToString { p -> p.name }})" }}")
        assertTrue(tunable.size >= 6, "at least a handful of drawings should expose settings, found ${tunable.size}")
        for (drawing in tunable) {
            for (param in drawing.params) {
                assertTrue(param.value in param.min..param.max, "${drawing.name} ${param.name} starts out of range")
            }
        }
    }
}
