package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Genes
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.NumberGene
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared pieces every layered drawing stands on: gestures, genes, the flat camera and travellers. */
class EngineTest {

    private fun frame(
        seconds: Float,
        bpm: Float = 120f,
        confidence: Float = 0.9f,
        kick: Float = 0f,
        drop: Boolean = false,
        section: Boolean = false,
        mood: Float = 0.5f,
        level: Float = 0.5f,
    ): SpectrumFrame {
        val beats = seconds * bpm / 60f
        return SpectrumFrame(
            ptsMicros = (seconds * 1_000_000f).toLong(),
            bands = FloatArray(8) { 0.5f },
            peaks = FloatArray(8),
            scope = FloatArray(8),
            level = level,
            bass = 0.5f,
            mid = 0.5f,
            treble = 0.5f,
            beat = kick,
            pulse = 0f,
            kick = kick,
            energy = 0.5f,
            mood = mood,
            drop = drop,
            bpm = bpm,
            beatConfidence = confidence,
            beatPhase = beats - kotlin.math.floor(beats),
            barPhase = (beats / 4f) - kotlin.math.floor(beats / 4f),
            phrasePhase = (beats / 16f) - kotlin.math.floor(beats / 16f),
            rhythm = RhythmEstimate((seconds * 1_000_000L).toLong(), (seconds * 1_000_000L).toLong(),
                Long.MAX_VALUE, 0L, bpm, confidence, confidence, confidence > 0.6f,
                beats - kotlin.math.floor(beats), 0f, 0f),
            events = if (!section && !drop) null else AudioEventDelivery(io.github.yuroyami.kiteplayer.Generation.Initial,
                0L, (seconds * 1_000_000L).toLong(), arrayOf(DeliveredAudioEvent(AudioEvent(
                    io.github.yuroyami.kiteplayer.Generation.Initial, 0L, (seconds * 1_000_000L).toLong(),
                    AudioDetection(if (drop) AudioEventKind.Drop else AudioEventKind.SectionBoundary,
                        (seconds * 1_000_000L).toLong(), (seconds * 1_000_000L).toLong(), 0.4f, 0.9f, 0.8f)), 0L))),
        )
    }

    private fun state(frame: SpectrumFrame, time: Float, future: VizFuture? = null): VizRenderState =
        VizRenderState(frame, time, 1f / 60f, VizPalette.Classic, time, future)

    @Test
    fun gesturesKeepVisualCyclesSeparateFromUnknownBarsAndPhrases() {
        val gestures = Gestures()
        var time = 0f
        repeat(60 * 16 + 30) {
            time += 1f / 60f
            gestures.update(state(frame(time), time))
        }
        println("sixteen and a half seconds at 120 bpm: ${gestures.bars} bars, ${gestures.phrases} phrases")
        assertEquals(0, gestures.bars)
        assertTrue(gestures.cycles >= 7, "pulse-locked visual cycles still advance")
        assertEquals(0, gestures.phrases)
    }

    @Test
    fun withoutATempoVisualCyclesRunFree() {
        val gestures = Gestures()
        var time = 0f
        repeat(60 * 21 + 30) {
            time += 1f / 60f
            gestures.update(state(frame(time, bpm = 0f, confidence = 0f, mood = 0f), time))
        }
        println("21.5 calm seconds with no tempo: ${gestures.bars} bars of ${gestures.barSeconds} s")
        assertEquals(5, gestures.cycles, "calm visual cycles last 4.2 seconds")
        assertEquals(0, gestures.bars)
    }

    private fun recipe(seed: Long): Genes = Genes(seed).apply {
        number("arms", 3f, 12f, 6f)
        number("spin", -1f, 1f, 0f)
        choice("layout", 4)
        toggle("echo copy", start = false)
        choice("ground", 3, start = 1)
    }

    @Test
    fun supportedSectionsChangeTheRecipeAndEveryFourthChangesMore() {
        val genes = recipe(7L)
        val gestures = Gestures()
        var time = 0f
        val perSection = ArrayList<Int>()
        var before = 0
        repeat(60 * 34) { index ->
            time += 1f / 60f
            gestures.update(state(frame(time, section = index > 0 && index % 480 == 0), time))
            genes.advance(gestures, 1f / 60f)
            if (gestures.section) {
                perSection += genes.changes - before
                before = genes.changes
            }
        }
        println("changes at each section: $perSection")
        assertTrue(perSection.size >= 4, "the fixture injects four supported section boundaries")
        assertTrue(perSection.all { it >= 1 }, "every section changes something: $perSection")
        assertTrue(perSection[3] >= 3, "the fourth section changes three or more: $perSection")
    }

    @Test
    fun theSameSeedReplaysTheSameRecipe() {
        fun run(): List<Float> {
            val genes = recipe(11L)
            val gestures = Gestures()
            var time = 0f
            repeat(60 * 40) { index ->
                time += 1f / 60f
                gestures.update(state(frame(time, section = index > 0 && index % 480 == 0), time))
                genes.advance(gestures, 1f / 60f)
            }
            assertTrue(genes.changes > 0, "seeded comparison must include mutations")
            return genes.all.map { if (it is NumberGene) it.value else (it as io.github.yuroyami.kiteplayer.audioviz.viz.motion.ChoiceGene).value.toFloat() }
        }
        assertEquals(run(), run(), "a seeded recipe replays exactly")
    }

    @Test
    fun aDropSendsEveryGeneToItsBusiestEnd() {
        val genes = recipe(3L)
        val gestures = Gestures()
        gestures.update(state(frame(1f, drop = true), 1f))
        genes.advance(gestures, 1f / 60f)
        val arms = genes.all.first { it.name == "arms" } as NumberGene
        assertEquals(12f, arms.target, "a drop aims the arms at their most")
    }

    @Test
    fun theCameraPunchPeaksOnAKickItSawComing() {
        val onset = 1f
        val step = 0.001f
        val camera = Camera2D(wander = 0f, roll = 0f, shake = 0f, cuts = false)
        var time = 0f
        var highest = 0f
        var highestAt = 0f
        var landed = false
        val event = AudioEvent(io.github.yuroyami.kiteplayer.Generation.Initial, 0L, 0L,
            AudioDetection(AudioEventKind.LowTransient, 1_000_000L, 1_010_000L, 1f, 0.8f, 0.5f))
        while (time < 1.5f) {
            val away = onset - time
            val kick = if (!landed && time >= onset) 1f else 0f
            if (kick > 0f) landed = true
            val future = object : VizFuture {
                override fun at(secondsAhead: Float): SpectrumFrame = frame(time + secondsAhead)
                override val nextOnsetSeconds: Float = if (away > 0f) away else -1f
                override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? =
                    if (away > 0f && away <= 0.1f && kind == AudioEventKind.LowTransient) UpcomingAudioEvent(event, away) else null
            }
            val current = frame(time, kick = kick)
            camera.advance(VizRenderState(current, time, step, VizPalette.Classic, time, future))
            if (camera.zoom > highest) {
                highest = camera.zoom
                highestAt = time
            }
            time += step
        }
        val error = highestAt - onset
        println("the camera's punch peaked ${error * 1000} ms from the kick, at zoom $highest")
        assertTrue(abs(error) < 0.01f, "timed by the queue the punch should land on the kick, was ${error * 1000} ms out")
    }

    @Test
    fun aTravellerArrivesOnTime() {
        val travellers = Travellers(4)
        val slot = travellers.spawn(0f, 0.5f, 1f, 0.5f, seconds = 0.5f)
        var time = 0f
        var halfway = -1f
        while (travellers.alive[slot]) {
            travellers.advance(1f / 60f)
            time += 1f / 60f
            if (halfway < 0f && travellers.x[slot] >= 0.5f) halfway = time
        }
        println("a half second traveller was half way at $halfway s and gone at $time s")
        assertTrue(abs(time - 0.5f) < 1f / 30f, "it should arrive after half a second, took $time")
        assertTrue(abs(halfway - 0.25f) < 1f / 30f, "and be half way at a quarter second, was $halfway")
    }
}
