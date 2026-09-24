package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Journey
import kotlin.math.*
import kotlin.test.*

internal fun journeyState(frame: Int, busy: Boolean = true, held: Boolean = false, motion: Float = 1f): VizRenderState {
    val level = if (busy) 0.78f else 0.13f
    val bands = FloatArray(40) { level * (0.55f + 0.45f * sin(it * 0.43f + frame * 0.027f)) }
    val sound = SpectrumFrame(frame * 1_000_000L / 60, bands, bands, FloatArray(256) { sin(it * 0.06f) * level },
        level, level, level * 0.75f, level * 0.5f, 0f, 0f,
        kick = if (busy && frame % 30 == 0) 0.8f else 0f,
        hat = if (busy && frame % 13 == 0) 0.65f else 0f,
        energy = level, mood = level, density = if (busy) 0.85f else 0.04f,
        novelty = if (busy) 1.3f else 0.02f, loudShort = level, loudLong = level,
        width = 0.4f, held = held)
    return VizRenderState(sound, frame / 60f, 1f / 60f, VizPalette.Prism).apply { motionScale = motion }
}

class JourneyMotionTest {
    @Test
    fun retargetingNeverTeleportsMaterialAndHoldingFreezesIt() {
        val journey = Journey(5, 7L)
        val gestures = Gestures()
        var previous = journey.weights.copyOf()
        for (frame in 1..900) {
            val state = journeyState(frame)
            gestures.update(state)
            val selected = if (frame < 200) 2 else if (frame < 400) 4 else 1
            journey.advance(state, gestures, FloatArray(5) { 1f }, false, selected)
            assertEquals(1f, journey.weights.sum(), 0.00001f)
            for (i in previous.indices) {
                assertTrue(journey.weights[i].isFinite() && journey.weights[i] in 0f..1f)
                assertTrue(abs(journey.weights[i] - previous[i]) < 0.02f, "A new destination must not cut the material")
            }
            previous = journey.weights.copyOf()
        }
        assertEquals(1f, journey.weights[1], 0.00001f)
        repeat(60) { journey.advance(journeyState(901 + it, held = true), gestures, FloatArray(5) { 1f }, false, 0) }
        assertContentEquals(previous, journey.weights)
    }

    @Test
    fun explorationHasMemoryAndIsNotTheSameOrderedLoopForEverySong() {
        fun route(busy: Boolean): List<Int> {
            val journey = Journey(5, 72L); val gestures = Gestures()
            val route = mutableListOf(0)
            val affinity = if (busy) floatArrayOf(0.6f, 0.8f, 1.3f, 0.4f, 0.3f)
                else floatArrayOf(0.5f, 0.3f, 0.1f, 0.9f, 1.1f)
            for (frame in 1..14400) {
                val state = journeyState(frame, busy); gestures.update(state)
                journey.advance(state, gestures, affinity, true, 0)
                if (journey.target != route.last()) route += journey.target
            }
            return route
        }
        val calm = route(false); val lively = route(true)
        assertTrue(calm.distinct().size >= 3 && lively.distinct().size >= 3)
        assertNotEquals(calm, lively)
        assertEquals(lively, route(true), "The same soundtrack and reset must reproduce a journey")
    }

    @Test
    fun aConfidentPrestudiedBoundaryStartsOneSmoothPreparation() {
        fun run(confidence: Float): Journey {
            val journey = Journey(3, 11L); val gestures = Gestures()
            val frame = journeyState(1, false).frame
            val event = AudioEvent(frame.generation, frame.analysisRevision, 1L,
                AudioDetection(AudioEventKind.Drop, 11_000_000L, 11_000_000L, 0.8f, confidence, 0.9f),
                AudioEventSource.SongMap)
            val future = object : VizFuture {
                override fun at(secondsAhead: Float): SpectrumFrame? = null
                override val nextOnsetSeconds: Float = -1f
                override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? =
                    if (kind == AudioEventKind.Drop) UpcomingAudioEvent(event, 0.8f) else null
            }
            for (i in 1..1200) {
                val plain = journeyState(i, false)
                val state = VizRenderState(plain.frame, plain.timeSeconds, plain.deltaSeconds, plain.palette,
                    future = if (i > 600) future else null)
                gestures.update(state)
                journey.advance(state, gestures, floatArrayOf(0.2f, 1f, 0.4f), true, 0)
            }
            return journey
        }
        assertEquals(1, run(0.9f).transitions)
        assertEquals(0, run(0.3f).transitions)
    }
}
