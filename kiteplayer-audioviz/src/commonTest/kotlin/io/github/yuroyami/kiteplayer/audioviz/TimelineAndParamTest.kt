package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Blending readings for fast displays, and the settings a drawing exposes. */
class TimelineAndParamTest {

    init { useSkiaGraphics() }

    private fun reading(pts: Long, value: Float, kick: Float = 0f, beatPhase: Float = 0f): SpectrumFrame =
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
        )

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
