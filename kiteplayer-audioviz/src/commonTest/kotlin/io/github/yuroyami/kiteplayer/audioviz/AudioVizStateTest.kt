package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration

/** The state behind the composable: which moment it draws, and what a hand on the controls changes. */
class AudioVizStateTest {
    private val worker = ManualVizDispatcher()
    private val feed = AudioVizFeed(worker)

    @AfterTest
    fun closeFeed() {
        feed.close()
        worker.runAll()
    }

    private fun hear(fromMicros: Long, seconds: Float) {
        feed.hear(fromMicros, seconds, afterBlock = worker::runAll)
    }


    init { useSkiaGraphics() } // before the catalogue, which builds drawings

    @Test
    fun `the picture shows the analysis for the moment the player is at`() {
        var position = 0L
        val state = AudioVizState(feed = feed, clock = { VizClockReading(position) })
        assertTrue(state.nextFrame().ptsMicros < 0, "before any sound the picture should be silent")

        hear(fromMicros = 0, seconds = 2f)
        position = 1_000_000
        val frame = state.nextFrame()
        // A little ahead of the reported position, since a drawn frame reaches the eye a moment later.
        assertTrue(
            frame.ptsMicros in 1_000_000..1_030_000,
            "at 1 s the picture drew the analysis stamped ${frame.ptsMicros}",
        )
    }

    @Test
    fun `display delay advances media by the effective rate and stops on pause`() {
        var rate = 1.0
        val state = AudioVizState(feed = feed) { VizClockReading(250_000L, rate) }
        hear(0L, 1f)
        state.displayDelay = 20.milliseconds
        for (speed in listOf(0.5, 1.0, 2.0, 0.0)) {
            rate = speed
            val frame = state.nextFrame()
            val wanted = 250_000L + (speed * 20_000L).toLong()
            assertTrue(kotlin.math.abs(frame.ptsMicros - wanted) <= 1L, "at $speed expected $wanted got ${frame.ptsMicros}")
            if (speed == 0.0) assertEquals(0f, frame.beat)
        }
    }

    @Test
    fun `a paused picture stops beat progression but keeps the tempo diagnostic`() {
        var rate = 1.0
        val state = AudioVizState(feed = feed) { VizClockReading(7_000_000L, rate) }
        hear(0L, 8f)
        val playing = state.nextFrame()
        assertEquals(true, playing.rhythm?.usable, "the drum loop should have a usable pulse by 7 s")
        rate = 0.0
        val paused = state.nextFrame()
        assertEquals(false, assertNotNull(paused.rhythm).usable, "a held clock has no beat progression")
        assertEquals(-1f, paused.beatInSeconds)
        assertEquals(0f, paused.beatConfidence)
        assertEquals(playing.rhythm?.bpm, paused.rhythm?.bpm, "the rate estimate stays visible")
        rate = 1.0
        assertEquals(true, state.nextFrame().rhythm?.usable, "resuming restores the accepted pulse")
    }

    @Test
    fun `missing clocks and retired generations cannot draw queued audio`() {
        var reading = VizClockReading(250_000L)
        val state = AudioVizState(feed = feed) { reading }
        hear(0L, 1f)
        assertTrue(state.nextFrame().hasTimestamp)
        reading = VizClockReading(null)
        assertFalse(state.nextFrame().hasTimestamp)
        reading = VizClockReading(250_000L, generation = Generation(1))
        assertFalse(state.nextFrame().hasTimestamp)
    }

    @Test
    fun `display cadence changes the estimate but a stall never advances the audio clock`() {
        val state = AudioVizState(feed = feed) { VizClockReading(250_000L) }
        hear(0L, 1f)
        state.nextFrame(1_000_000_000L)
        val fastDisplay = state.nextFrame(1_008_333_333L)
        assertTrue(fastDisplay.ptsMicros in 258_332L..258_334L)
        val stalled = state.nextFrame(1_508_333_333L)
        assertEquals(fastDisplay.ptsMicros, stalled.ptsMicros)
        assertTrue(state.estimatedDisplayDelay.inWholeNanoseconds in 8_333_332L..8_333_334L)
    }

    @Test
    fun `one missed callback does not change the estimated refresh period`() {
        val state = AudioVizState(feed = feed) { VizClockReading(250_000L) }
        var host = 1_000_000_000L
        repeat(7) {
            state.nextFrame(host)
            host += 16_666_667L
        }
        host += 16_666_667L
        state.nextFrame(host)
        assertEquals(16_666_667L, state.estimatedDisplayDelay.inWholeNanoseconds)
    }

    private fun keyOf(delivered: DeliveredAudioEvent): String {
        val detection = delivered.event.detection
        return "${detection.kind}:${detection.ptsMicros}:${delivered.event.sequence}"
    }

    @Test
    fun `a reader that skips display frames still gets every event once`() {
        var position = 0L
        val state = AudioVizState(feed = feed) { VizClockReading(position) }
        state.displayDelay = Duration.ZERO
        hear(0L, 8f)
        val slow = state.frameSource()
        val everyFrame = HashSet<String>()
        val slowGot = ArrayList<String>()
        val skippingFrameProperty = HashSet<String>()
        var displayFrames = 0
        fun readSlow() {
            slow.take().events?.let { for (i in 0 until it.size) slowGot += keyOf(it[i]) }
        }
        // A 240 Hz display, read every fourth frame, as a surface capped at 60 would.
        while (position < 7_000_000L) {
            position += 1_000_000L / 240
            val frame = state.nextFrame()
            frame.events?.let { for (i in 0 until it.size) everyFrame += keyOf(it[i]) }
            if (displayFrames % 4 == 0) {
                readSlow()
                frame.events?.let { for (i in 0 until it.size) skippingFrameProperty += keyOf(it[i]) }
            }
            displayFrames++
        }
        readSlow()
        assertTrue(everyFrame.size > 10, "the drum loop should deliver events, had ${everyFrame.size}")
        assertTrue(skippingFrameProperty.size < everyFrame.size,
            "reading the frame property on a slower clock loses events, had ${skippingFrameProperty.size} of ${everyFrame.size}")
        assertEquals(slowGot.size, slowGot.toSet().size, "an event was delivered twice")
        assertEquals(everyFrame, slowGot.toSet(), "the slow reader must get every event")
    }

    @Test
    fun `two readers of one state do not take events from each other`() {
        var position = 0L
        val state = AudioVizState(feed = feed) { VizClockReading(position) }
        state.displayDelay = Duration.ZERO
        hear(0L, 8f)
        val first = state.frameSource()
        val second = state.frameSource()
        val firstGot = HashSet<String>()
        val secondGot = HashSet<String>()
        var displayFrames = 0
        while (position < 7_000_000L) {
            position += 1_000_000L / 120
            state.nextFrame()
            if (displayFrames % 2 == 0) first.take().events?.let { for (i in 0 until it.size) firstGot += keyOf(it[i]) }
            if (displayFrames % 3 == 0) second.take().events?.let { for (i in 0 until it.size) secondGot += keyOf(it[i]) }
            displayFrames++
        }
        first.take().events?.let { for (i in 0 until it.size) firstGot += keyOf(it[i]) }
        second.take().events?.let { for (i in 0 until it.size) secondGot += keyOf(it[i]) }
        assertTrue(firstGot.size > 10, "the drum loop should deliver events, had ${firstGot.size}")
        assertEquals(firstGot, secondGot, "both readers must get the same events")
    }

    @Test
    fun `the same display frame gives a reader the same frame twice`() {
        var position = 0L
        val state = AudioVizState(feed = feed) { VizClockReading(position) }
        hear(0L, 2f)
        val source = state.frameSource()
        position = 500_000L
        state.nextFrame()
        assertSame(source.take(), source.take())
        position = 516_000L
        state.nextFrame()
        assertTrue(source.take().ptsMicros > 500_000L)
    }

    @Test
    fun `views sharing analysis keep independent event intervals`() {
        var position = 0L
        val first = AudioVizState(feed = feed) { VizClockReading(position) }
        val second = AudioVizState(feed = feed) { VizClockReading(position) }
        first.displayDelay = Duration.ZERO
        second.displayDelay = Duration.ZERO
        fun event(at: Long, strength: Float) = SpectrumFrame(
            at, FloatArray(4), FloatArray(4), FloatArray(4), 0f, 0f, 0f, 0f, 0f, 0f,
            kick = strength,
            detections = AudioDetections(at + 20_000L, at, if (strength > 0f) arrayOf(
                AudioDetection(AudioEventKind.LowTransient, at, at + 20_000L, strength, 0.8f, 0.2f),
            ) else emptyArray()),
        )
        feed.timeline.push(event(0L, 0f))
        feed.timeline.push(event(10_000L, 0.8f))
        feed.timeline.push(event(20_000L, 0f))
        first.nextFrame()
        second.nextFrame()
        position = 15_000L
        assertEquals(0.8f, first.nextFrame().kick)
        assertEquals(0f, first.nextFrame().kick)
        assertEquals(0.8f, second.nextFrame().kick, "the first view consumed the second view's event")
        assertEquals(0f, second.nextFrame().kick)
    }

    @Test
    fun `choosing a drawing by hand shows it at once even while the director runs`() {
        val state = AudioVizState(feed = feed, clock = { VizClockReading(0L) })
        state.directed = true
        val chosen = state.catalogue[5]
        state.drawing = chosen
        assertSame(chosen, state.showing)
        assertSame(chosen, state.director.current)
    }

    @Test
    fun `turning the director off keeps the drawing it was showing`() {
        val state = AudioVizState(feed = feed, clock = { VizClockReading(0L) })
        state.directed = true
        val pickedByTheDirector = state.catalogue[7]
        state.director.show(pickedByTheDirector)
        state.directed = false
        assertSame(pickedByTheDirector, state.drawing)
        assertSame(pickedByTheDirector, state.showing)
    }

    @Test
    fun `mutate changes the recipe of the drawing on screen`() {
        val state = AudioVizState(feed = feed, clock = { VizClockReading(0L) })
        val drawing = state.catalogue.first { it.genes != null }
        state.drawing = drawing
        val genes = assertNotNull(drawing.genes)
        val before = genes.changes
        state.mutate()
        assertTrue(genes.changes > before, "mutate left the recipe as it was")
    }
}
