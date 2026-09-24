package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules of the broken broadcast, checked on its numbers without drawing it. */
class GlitchSceneTest {

    @Test
    fun theBarsFillTheWidthAndALoudBandIsWider() {
        val run = Run()
        val bands = FloatArray(48) { if (it < 24) 0.45f else 0.02f }
        repeat(30) { run.next(bands = bands) }
        val edges = run.scene.edges
        assertEquals(0f, edges[0])
        assertEquals(1f, edges[GlitchScene.BARS])
        for (bar in 0 until GlitchScene.BARS) assertTrue(edges[bar + 1] > edges[bar], "slot $bar has no width")
        val loud = edges[1] - edges[0]
        val quiet = edges[GlitchScene.BARS] - edges[GlitchScene.BARS - 1]
        assertTrue(loud > quiet * 2f, "a loud band takes $loud of the width and a quiet one $quiet")
    }

    @Test
    fun theFirstFrameAndASilenceShowTheBarcodeAtThirtyPercentWithAFlatTrace() {
        val scene = GlitchScene()
        assertTrue(scene.exposure.all { it == GlitchScene.IDLE_LIGHT }, "the first frame is lit before any music")
        val run = Run(scene)
        repeat(240) { run.next(bands = FloatArray(48), level = 0f) }
        assertTrue(scene.exposure.all { abs(it - GlitchScene.IDLE_LIGHT) < 0.01f }, "silence keeps the idle light")
        assertTrue(scene.trace.all { it == 0f }, "the trace lies flat in silence")
        assertEquals(0f, scene.split)
        assertEquals(0, scene.slices)
        assertFalse(scene.staticOn)
        assertFalse(scene.moshing)
        assertTrue(scene.melt.all { it < 0f })
        assertTrue(scene.scanStrength < 0.01f, "no scan bar rolls in silence")
    }

    @Test
    fun aKickSplitsTheLayersByItsStrengthAndTheyRejoinWithin120Milliseconds() {
        fun splits(strength: Float): FloatArray {
            val run = Run()
            repeat(30) { run.next() }
            run.next(hits = listOf(AudioEventKind.LowTransient to strength))
            val out = FloatArray(9)
            out[0] = run.scene.split
            for (frame in 1..8) {
                run.next()
                out[frame] = run.scene.split
            }
            return out
        }
        val hard = splits(0.9f)
        val soft = splits(0.3f)
        assertTrue(soft[0] > 0f, "a soft kick still splits the layers")
        assertTrue(hard[0] > soft[0], "a hard kick splits them further: ${hard[0]} against ${soft[0]}")
        assertTrue(hard[7] < hard[0] * 0.01f, "117 ms after the kick the layers have nearly rejoined")
        assertEquals(0f, hard[8], "133 ms after the kick the edges are clean")
    }

    @Test
    fun aSnareTearsThreeToEightSlicesForTwoToFourFrames() {
        fun tear(strength: Float): Pair<Int, Int> {
            val run = Run()
            repeat(30) { run.next() }
            run.next(hits = listOf(AudioEventKind.BodyTransient to strength))
            val count = run.scene.slices
            for (slice in 0 until count) assertTrue(run.scene.sliceShift[slice] != 0f, "slice $slice did not move")
            var frames = 0
            while (run.scene.slices > 0 && frames < 20) {
                frames++
                run.next()
            }
            return count to frames
        }
        assertEquals(3 to 2, tear(0f))
        assertEquals(8 to 4, tear(1f))
    }

    @Test
    fun aHatMeltsOnlyTheBrightestBarsAboveAFixedLevel() {
        val quietBands = FloatArray(48) { 0.05f }
        val quiet = Run()
        repeat(60) { quiet.next(bands = quietBands) }
        quiet.next(bands = quietBands, hits = listOf(AudioEventKind.HighTransient to 0.9f))
        assertTrue(quiet.scene.melt.all { it < 0f }, "nothing melts in a passage with no bright bar")

        val loudBands = FloatArray(48) { if (it < 20) 0.3f else 0.05f }
        val loud = Run()
        repeat(60) { loud.next(bands = loudBands) }
        loud.next(bands = loudBands, hits = listOf(AudioEventKind.HighTransient to 0.9f))
        val melting = (0 until GlitchScene.BARS).filter { loud.scene.melt[it] >= 0f }
        assertTrue(melting.size in 1..6, "one hat melts a few bars, not ${melting.size}")
        assertTrue(melting.all { loud.scene.glow[it] >= 0.16f }, "only bright bars melt")
        repeat(20) { loud.next(bands = loudBands) }
        assertTrue(loud.scene.melt.all { it < 0f }, "a melt runs out and the bar is clean again")
    }

    @Test
    fun aSectionChangesTheChannelBehindThreeFramesOfStatic() {
        val run = Run()
        repeat(30) { run.next() }
        run.next(structure = AudioEventKind.SectionBoundary)
        assertEquals(1, run.scene.channel)
        var frames = 0
        while (run.scene.staticOn && frames < 10) {
            frames++
            run.next()
        }
        assertEquals(3, frames, "the static lasts three frames at sixty a second")
    }

    @Test
    fun aBreakdownCollapsesIntoALineUntilTheNextSection() {
        val run = Run()
        repeat(30) { run.next() }
        run.next(structure = AudioEventKind.Breakdown)
        assertTrue(run.scene.breakdown)
        assertFalse(run.scene.staticOn, "signal loss is not a channel change")
        repeat(30) { run.next() }
        assertEquals(1f, run.scene.collapse, 0.001f)
        assertEquals(0, run.scene.channel)
        run.next(structure = AudioEventKind.SectionBoundary)
        assertFalse(run.scene.breakdown)
        assertEquals(0f, run.scene.collapse)
        assertEquals(1, run.scene.channel, "the full picture returns on a new channel")
    }

    @Test
    fun aDropFreezesTheFrameUntilAFirstBeatAtLeastHalfACycleLater() {
        val run = Run()
        repeat(30) { run.next() }
        run.next(structure = AudioEventKind.Drop)
        assertTrue(run.scene.moshing)
        assertTrue(run.scene.takeMoshStart())
        assertFalse(run.scene.takeMoshStart(), "the frame freezes once")
        assertFalse(run.scene.staticOn, "the drop is its own moment, with no static")
        assertEquals(1, run.scene.channel, "the new picture comes on a new channel")
        var seconds = 0f
        var moved = 0f
        while (run.scene.moshing && seconds < 12f) {
            run.next()
            seconds += DELTA
            moved += run.scene.takeMoshSeconds()
        }
        val cycle = run.scene.cycleSeconds
        assertTrue(seconds >= 0.5f * cycle - DELTA, "the datamosh held for $seconds s of a $cycle s cycle")
        assertTrue(seconds <= 2f * cycle + DELTA, "the datamosh held for $seconds s of a $cycle s cycle")
        assertTrue(moved > 0f, "the blocks moved while the music played")
    }

    @Test
    fun aPausedDatamoshHoldsAndASilenceEndsIt() {
        val run = Run()
        repeat(30) { run.next() }
        run.next(structure = AudioEventKind.Drop)
        run.scene.takeMoshSeconds()
        repeat(600) { run.next(held = true) }
        assertTrue(run.scene.moshing, "a paused player keeps its last picture")
        assertEquals(0f, run.scene.takeMoshSeconds(), "nothing moves while paused")
        repeat(240) { run.next(bands = FloatArray(48), level = 0f) }
        assertFalse(run.scene.moshing, "a silence settles to the clean picture")
    }

    @Test
    fun aDropTheQueueAlreadyHoldsStallsThePictureBeforeItLands() {
        fun changes(ahead: Float?): Int {
            val run = Run(future = ahead?.let { dropIn(it) })
            var count = 0
            var before = run.scene.edges.copyOf()
            for (frame in 0 until 60) {
                val bands = FloatArray(48) { band -> 0.2f + 0.15f * sin(frame * 0.4f + band * 0.3f) }
                run.next(bands = bands)
                if (!run.scene.edges.contentEquals(before)) count++
                before = run.scene.edges.copyOf()
            }
            return count
        }
        val free = changes(null)
        val stalled = changes(0.3f)
        assertTrue(free >= 59, "without a queued drop the picture takes every frame, it took $free")
        assertTrue(stalled < free / 3, "0.3 s before a drop the picture stalls, it still took $stalled of 60")
    }

    @Test
    fun resetReplaysTheSameMusicTheSameWay() {
        val scene = GlitchScene()
        fun play(): List<Float> {
            val run = Run(scene)
            for (frame in 0 until 240) {
                val bands = FloatArray(48) { band -> 0.25f + 0.2f * sin(frame * 0.1f + band * 0.2f) }
                val hits = when (frame % 30) {
                    0 -> listOf(AudioEventKind.LowTransient to 0.8f)
                    15 -> listOf(AudioEventKind.BodyTransient to 0.6f, AudioEventKind.HighTransient to 0.7f)
                    else -> emptyList()
                }
                run.next(bands = bands, hits = hits, structure = if (frame == 100) AudioEventKind.Drop else null)
            }
            return scene.edges.toList() + scene.exposure.toList() + scene.white.toList() + scene.melt.toList() +
                scene.sliceTop.toList() + scene.sliceShift.toList() +
                listOf(scene.split, scene.collapse, scene.scanAt, scene.channel.toFloat(), scene.slices.toFloat())
        }
        val first = play()
        scene.reset()
        assertEquals(first, play())
    }

    /** One run of frames at sixty a second, with the gestures the drawing's kit would keep. */
    private class Run(val scene: GlitchScene = GlitchScene(), private val future: VizFuture? = null) {
        private val gestures = Gestures()
        private val random = Rng(5L)
        private var frame = 0
        private var sequence = 0L

        fun next(
            bands: FloatArray = FloatArray(48) { 0.25f },
            level: Float = 0.5f,
            hits: List<Pair<AudioEventKind, Float>> = emptyList(),
            structure: AudioEventKind? = null,
            held: Boolean = false,
        ) {
            frame++
            val pts = frame * 1_000_000L / 60L
            val events = ArrayList<DeliveredAudioEvent>()
            for ((kind, strength) in hits) events += delivered(kind, pts, strength, AudioEventSource.LiveTransient)
            if (structure != null) events += delivered(structure, pts, 0.7f, AudioEventSource.LiveStructure)
            val analysis = SpectrumFrame(
                pts, bands, bands, FloatArray(128), level, level, level, level, 0f, 0f,
                energy = level, mood = 0.5f, density = 0.4f, held = held,
                events = AudioEventDelivery(Generation.Initial, 0L, pts, events.toTypedArray()),
            )
            val state = VizRenderState(analysis, frame * DELTA, DELTA, VizPalette.Prism, future = future)
            gestures.update(state)
            scene.advance(state, gestures, random)
        }

        private fun delivered(kind: AudioEventKind, pts: Long, strength: Float, source: AudioEventSource) =
            DeliveredAudioEvent(
                AudioEvent(Generation.Initial, 0L, ++sequence, AudioDetection(kind, pts, pts, strength, 0.9f, 0.5f), source),
                0L,
            )
    }

    private companion object {
        const val DELTA = 1f / 60f

        /** A queue that always holds a drop [seconds] ahead. */
        fun dropIn(seconds: Float): VizFuture = object : VizFuture {
            private val drop = AudioEvent(Generation.Initial, 0L, 1L,
                AudioDetection(AudioEventKind.Drop, 0L, 0L, 0.7f, 0.9f, 0.5f), AudioEventSource.LiveStructure)

            override fun at(secondsAhead: Float): SpectrumFrame? = null
            override val nextOnsetSeconds: Float get() = -1f
            override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? =
                if (kind == AudioEventKind.Drop) UpcomingAudioEvent(drop, seconds) else null
        }
    }
}
