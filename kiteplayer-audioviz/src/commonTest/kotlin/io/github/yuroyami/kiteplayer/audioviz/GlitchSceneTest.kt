package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Evolution
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The rules of the broken broadcast, checked on its numbers without drawing it. */
class GlitchSceneTest {

    @Test
    fun theBlocksFillTheScreenAndALoudBandIsBrighter() {
        val scene = GlitchScene()
        scene.layout(width = 1920f, height = 1080f)
        assertEquals(64, scene.columns)
        assertEquals(16, scene.rows)
        assertTrue(1920f / scene.columns >= GlitchScene.BLOCK_PX, "a block is ${1920f / scene.columns} px wide")
        val tall = 1080f * (1f - GlitchScene.TRACE_BAND) / scene.rows
        assertTrue(tall >= GlitchScene.BLOCK_PX, "a block is $tall px high")
        // One loud band, the 25th of 48, for a second: its row of the newest column is the brightest.
        val run = Run(scene)
        val bands = FloatArray(48) { if (it == 24) 0.6f else 0.02f }
        repeat(60) { run.next(bands = bands) }
        val newest = scene.columns - 1
        val brightest = (0 until scene.rows).maxBy { scene.blockLight(newest, it) }
        assertEquals(24 * scene.rows / 48, brightest, "the loud band lights its own row")
    }

    @Test
    fun theFirstFrameAndASilenceShowTheScreenAtThirtyPercentWithAFlatTrace() {
        val scene = GlitchScene()
        assertTrue(scene.light.all { it == GlitchScene.IDLE_LIGHT }, "the first frame is lit before any music")
        val run = Run(scene)
        repeat(240) { run.next(bands = FloatArray(48), level = 0f) }
        assertTrue(scene.light.all { abs(it - GlitchScene.IDLE_LIGHT) < 0.01f }, "silence keeps the idle light")
        assertTrue(scene.trace.all { it == 0f }, "the trace lies flat in silence")
        assertEquals(0f, scene.split)
        assertEquals(0, scene.slices)
        assertFalse(scene.staticOn)
        assertFalse(scene.moshing)
        assertEquals(-1, scene.slipRow)
        assertTrue(scene.scanStrength < 0.01f, "no scan bar rolls in silence")
    }

    @Test
    fun theColumnsScrollAndAColumnIsTheBandsOfItsMoment() {
        val scene = GlitchScene()
        scene.layout(1920f, 1080f)
        val run = Run(scene)
        // The lowest sixteenth of the bands for 1.5 s, then the highest sixteenth for 2.5 s.
        val low = FloatArray(48) { if (it <= 2) 0.6f else 0.02f }
        val high = FloatArray(48) { if (it >= 45) 0.6f else 0.02f }
        repeat(90) { run.next(bands = low) }
        repeat(150) { run.next(bands = high) }
        val newest = scene.columns - 1
        val back = newest - 48
        println("glitch: now low ${scene.blockLight(newest, 0)} high ${scene.blockLight(newest, 15)}; " +
            "three seconds back low ${scene.blockLight(back, 0)} high ${scene.blockLight(back, 15)}")
        assertTrue(scene.blockLight(newest, 15) > scene.blockLight(newest, 0) + 0.2f, "the newest column shows the high bands")
        assertTrue(scene.blockLight(back, 0) > scene.blockLight(back, 15) + 0.2f, "three seconds back shows the low bands")
        // A pause holds the columns where they are.
        val before = scene.light.copyOf()
        repeat(60) { run.next(bands = high, held = true) }
        assertTrue(scene.light.contentEquals(before), "a paused picture does not scroll")
    }

    @Test
    fun neighbouringBlocksNeverStepByMoreThanHalf() {
        val scene = GlitchScene()
        scene.layout(1920f, 1080f)
        val run = Run(scene)
        repeat(600) { frame ->
            val bands = FloatArray(48) { band ->
                val h = (frame * 7_919 + band * 104_729) * 1_103_515_245
                ((h ushr 8) and 0xFFFF) / 65_535f * 0.9f
            }
            run.next(bands = bands, level = 0.9f)
        }
        var steepest = 0f
        for (column in 0 until scene.columns) for (row in 0 until scene.rows) {
            val here = scene.blockLight(column, row)
            if (column > 0) steepest = maxOf(steepest, abs(here - scene.blockLight(column - 1, row)))
            if (row > 0) steepest = maxOf(steepest, abs(here - scene.blockLight(column, row - 1)))
        }
        println("glitch: the steepest step between neighbouring blocks is $steepest")
        assertTrue(steepest <= GlitchScene.MOST_STEP_LIGHT + 1e-5f, "neighbouring blocks step by $steepest")
    }

    @Test
    fun aSmallScreenKeepsBlocksAtTwentyFourPixels() {
        val scene = GlitchScene()
        scene.layout(160f, 90f)
        assertEquals(6, scene.columns)
        // Three rows would fit, but the rows are even so the ribbon's band sits at the middle.
        assertEquals(2, scene.rows)
        assertTrue((90f * (1f - GlitchScene.TRACE_BAND)) / scene.rows >= GlitchScene.BLOCK_PX)
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
        fun reach(novelty: Float): Float {
            val run = Run()
            repeat(30) { run.next(novelty = novelty) }
            run.next(hits = listOf(AudioEventKind.BodyTransient to 0.6f), novelty = novelty)
            return (0 until run.scene.slices).sumOf { abs(run.scene.sliceShift[it]).toDouble() }.toFloat()
        }
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
        val changed = reach(0.9f)
        val steady = reach(0.1f)
        assertTrue(changed > steady * 2f, "a tear reaches further when the spectrum changed: $changed against $steady")
    }

    @Test
    fun aHatSlipsOneRowForTwoToFourFrames() {
        val run = Run()
        repeat(30) { run.next() }
        repeat(6) { hat ->
            run.next(hits = listOf(AudioEventKind.HighTransient to 0.8f))
            assertTrue(run.scene.slipRow in 0 until run.scene.rows, "hat $hat slipped row ${run.scene.slipRow}")
            assertTrue(run.scene.slipShift != 0f, "hat $hat slipped its row by nothing")
            var frames = 1
            while (run.scene.slipRow >= 0 && frames < 10) {
                run.next()
                if (run.scene.slipRow >= 0) frames++
            }
            assertTrue(frames in 2..4, "hat $hat slipped its row for $frames frames")
            assertEquals(0f, run.scene.slipShift)
        }
    }

    @Test
    fun theFormsFollowTheMusic() {
        val run = Run()
        repeat(30) { run.next() }
        assertEquals("Broadcast", GlitchScene.FORMS[run.scene.form])
        val reach = run.scene.trackingReach
        run.scene.morph()
        assertEquals("Tracking", GlitchScene.FORMS[run.scene.form])
        assertEquals(reach * 2f, run.scene.trackingReach, 1e-6f)
        run.scene.morph()
        assertEquals("Mosh", GlitchScene.FORMS[run.scene.form])
        // A freeze starts within one cycle, with no drop.
        var frames = 0
        val most = (run.scene.cycleSeconds / DELTA).toInt() + 2
        while (!run.scene.moshing && frames < most) {
            run.next()
            frames++
        }
        assertTrue(run.scene.moshing, "the Mosh form froze nothing in a cycle")
        run.next(structure = AudioEventKind.Breakdown)
        assertEquals("Dead air", GlitchScene.FORMS[run.scene.form])
        assertTrue(run.scene.deadAir)
        repeat(30) { run.next() }
        assertTrue(run.scene.collapse > 0.5f, "dead air collapsed only to ${run.scene.collapse}")
        run.next(structure = AudioEventKind.SectionBoundary)
        assertEquals("Broadcast", GlitchScene.FORMS[run.scene.form])
    }

    @Test
    fun aBirthTearsANewSetInFromTheTop() {
        val scene = GlitchScene()
        scene.layout(1920f, 1080f)
        val run = Run(scene)
        repeat(30) { run.next() }
        val old = scene.set
        scene.birth()
        assertEquals(0f, scene.wipeY)
        run.next()
        run.next()
        assertTrue(scene.wipeY in 0f..0.99f, "the wipe is part way down at ${scene.wipeY}")
        assertNotEquals(scene.setOfRow(0), scene.setOfRow(scene.rows - 1), "the rows above the wipe show the new set")
        assertEquals(old, scene.setOfRow(0), "the bottom row keeps the old set until the wipe reaches it")
        repeat(30) { run.next() }
        assertEquals(1f, scene.wipeY)
        assertTrue((0 until scene.rows).all { scene.setOfRow(it) == scene.set }, "the new set covers the screen")
        assertNotEquals(old, scene.set)
    }

    @Test
    fun theKeyBiasesTheNextSet() {
        fun next(keyHue: Float): Int {
            val run = Run()
            repeat(30) { run.next(keyHue = keyHue) }
            run.next(keyHue = keyHue, structure = AudioEventKind.SectionBoundary)
            return run.scene.set
        }
        assertNotEquals(next(0.1f), next(0.6f), "the key does not change which set comes next")
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
            var before = run.scene.light.copyOf()
            for (frame in 0 until 60) {
                val bands = FloatArray(48) { band -> 0.2f + 0.15f * sin(frame * 0.4f + band * 0.3f) }
                run.next(bands = bands)
                if (!run.scene.light.contentEquals(before)) count++
                before = run.scene.light.copyOf()
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
            return scene.light.toList() + scene.white.toList() + listOf(scene.slipRow.toFloat(), scene.slipShift) +
                scene.sliceTop.toList() + scene.sliceShift.toList() +
                listOf(scene.split, scene.collapse, scene.scanAt, scene.channel.toFloat(), scene.slices.toFloat(),
                    scene.form.toFloat(), scene.set.toFloat())
        }
        val first = play()
        scene.reset()
        assertEquals(first, play())
    }

    /** One run of frames at sixty a second, with the gestures the drawing's kit would keep. */
    private class Run(val scene: GlitchScene = GlitchScene(), private val future: VizFuture? = null) {
        private val gestures = Gestures()
        private val evolution = Evolution()
        private val random = Rng(5L)
        private var frame = 0
        private var sequence = 0L

        fun next(
            bands: FloatArray = FloatArray(48) { 0.25f },
            level: Float = 0.5f,
            hits: List<Pair<AudioEventKind, Float>> = emptyList(),
            structure: AudioEventKind? = null,
            held: Boolean = false,
            novelty: Float = 0.2f,
            keyHue: Float = 0f,
        ) {
            frame++
            val pts = frame * 1_000_000L / 60L
            val events = ArrayList<DeliveredAudioEvent>()
            for ((kind, strength) in hits) events += delivered(kind, pts, strength, AudioEventSource.LiveTransient)
            if (structure != null) events += delivered(structure, pts, 0.7f, AudioEventSource.LiveStructure)
            val analysis = SpectrumFrame(
                pts, bands, bands, FloatArray(128), level, level, level, level, 0f, 0f,
                energy = level, mood = 0.5f, novelty = novelty, density = 0.4f, held = held,
                keyHue = keyHue, keyConfidence = 1f,
                events = AudioEventDelivery(Generation.Initial, 0L, pts, events.toTypedArray()),
            )
            val state = VizRenderState(analysis, frame * DELTA, DELTA, VizPalette.Prism, future = future)
            gestures.update(state)
            evolution.update(state, gestures)
            scene.advance(state, gestures, evolution, random)
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
