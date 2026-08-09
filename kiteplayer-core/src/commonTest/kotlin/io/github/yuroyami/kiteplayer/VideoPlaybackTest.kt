package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The presentation schedule, driven by hand.
 *
 * Every timing decision the scheduler makes is a function of a clock it was given, so a whole session
 * can be stepped through frame by frame with a [TestClock] and a [RecordingRenderer], and every rule
 * checked against arithmetic rather than against a video and an opinion. The cases here are the ones
 * that were wrong: the drop deadline measured from the wrong frame, a repeat counted once per
 * scheduler wake-up, a queue one slot deeper than documented, a frame closed by two owners, and a
 * renderer told to draw "now" when the schedule meant a specific instant.
 */
class VideoPlaybackTest {

    /**
     * The engine under test: the documented queue bound of four, late dropping on, and no declared
     * frame rate, so every duration comes from the timestamps the test chose.
     */
    private fun playback(renderer: RecordingRenderer?, clock: TestClock) = VideoPlayback(
        renderer = renderer,
        clock = clock,
        containerFrameRate = null,
        queueCapacity = 4,
        dropPolicy = FrameDropPolicy.LateOnly,
    )

    @Test
    fun `the drop deadline is the duration of the candidate frame`() = runTest {
        val clock = TestClock()
        val renderer = RecordingRenderer()
        val video = playback(renderer, clock)

        // Variable frame rate: 20 ms, then 120 ms, then 20 ms. The frame already on screen and the
        // frame being considered have nothing to do with each other's length.
        listOf(0L, 20L, 140L, 160L).forEach { video.submit(FakeVideoFrame(pts(it))) }

        video.tick(null)
        assertEquals(listOf(pts(0)), renderer.timestamps)

        // A 100 ms wake-up delay. The frame at 20 ms lasts until 140 ms, so it is still the right
        // picture. The old rule measured the deadline with the 20 ms duration of the frame already
        // shown, found it long past, and threw away a frame that was current.
        clock.advance(100.milliseconds)
        video.tick(null)

        assertEquals(0, video.droppedFrames, "a frame is late only against its own duration")
        assertEquals(
            listOf(pts(0), pts(20)),
            renderer.timestamps,
            "the 120 ms frame is what the viewer should be looking at, so it must reach the renderer",
        )

        // The rule still drops when it should. The frame at 140 ms lasts 20 ms, and by 200 ms the one
        // after it is due too, so showing it would be replaced before it was seen.
        clock.advance(100.milliseconds)
        assertEquals(Duration.ZERO, video.tick(null), "a drop is immediate work, not a wait")
        assertEquals(1, video.droppedFrames)
        assertEquals(2, renderer.count, "the dropped frame never reached the renderer")

        video.close()
    }

    @Test
    fun `a repeated frame is counted once and not once per poll`() = runTest {
        val clock = TestClock()
        val renderer = RecordingRenderer()
        val video = playback(renderer, clock)
        listOf(100L, 140L, 180L).forEach { video.submit(FakeVideoFrame(pts(it))) }

        // Video 50 ms ahead of the master on a 40 ms frame: the law holds the picture for two periods.
        val master = pts(50)
        video.tick(master)
        assertEquals(listOf(pts(100)), renderer.timestamps)

        // Four polls while the doubled delay has not elapsed. Each one asks the law the same question
        // and gets the same answer, and none of them is a new repeat.
        repeat(4) {
            video.tick(master)
            assertEquals(0, video.repeatedFrames, "a repeat is a decision about a frame, not about a wake-up")
            clock.advance(10.milliseconds)
        }

        // The fifth poll is the one that ends the repeat, by presenting the frame after it.
        clock.advance(40.milliseconds)
        video.tick(master)

        assertEquals(1, video.repeatedFrames, "one frame held for two periods is one repeat")
        assertEquals(2, video.submittedFrames)
        video.close()
    }

    @Test
    fun `the queue never holds more than the capacity it was given`() = runTest {
        val clock = TestClock()
        val renderer = RecordingRenderer()
        val video = playback(renderer, clock)
        var worst = 0

        // The decoder runs as fast as it is allowed to. What stops it is the queue bound, and that
        // bound is what keeps a hardware decoder from holding its whole surface pool.
        val producer = launch {
            repeat(16) { index ->
                video.submit(FakeVideoFrame(pts(index * 40L)))
                worst = maxOf(worst, video.queuedFrames)
            }
        }
        yield()
        assertEquals(4, video.queuedFrames, "the bound is the capacity asked for, not the capacity plus one")

        var polls = 0
        while (renderer.count < 16 && polls++ < 200) {
            val wait = video.tick(null)
            worst = maxOf(worst, video.queuedFrames)
            clock.advanceNanos(wait.inWholeNanoseconds)
            yield()
        }
        producer.join()

        assertEquals(16, renderer.count, "every frame still reaches the renderer")
        assertEquals(4, worst, "four frames is the whole of what the queue holds; the shown frame is metadata")
        video.close()
    }

    @Test
    fun `a frame from a superseded generation never reaches the renderer`() = runTest {
        val ledger = LeakLedger()
        val clock = TestClock()
        val renderer = RecordingRenderer()
        val video = playback(renderer, clock)

        video.submit(FakeVideoFrame(pts(0), Generation.Initial, ledger = ledger))
        video.flush(Generation(1))
        assertEquals(0, ledger.liveCount, "the flush releases what the queue was holding")

        // A frame the decoder had already produced when the seek happened. Arriving after the flush is
        // routine, and the last hop before the screen is where it is caught.
        video.submit(FakeVideoFrame(pts(40), Generation.Initial, ledger = ledger))
        video.submit(FakeVideoFrame(pts(1_000), Generation(1), ledger = ledger))

        assertEquals(Duration.ZERO, video.tick(null), "discarding stale work is immediate work, not a wait")
        assertEquals(0, renderer.count)
        video.tick(null)

        assertEquals(listOf(pts(1_000)), renderer.timestamps, "only the current generation is presented")
        assertEquals(Generation(1), renderer.presentations.single().generation)
        assertEquals(0, ledger.liveCount)
        assertEquals(0, ledger.doubleCloseCount)
        video.close()
    }

    @Test
    fun `a schedule that fell behind is re-anchored instead of catching up in a burst`() = runTest {
        val clock = TestClock()
        val renderer = RecordingRenderer()
        val video = playback(renderer, clock)
        listOf(0L, 40L, 80L, 120L).forEach { video.submit(FakeVideoFrame(pts(it))) }

        video.tick(null)

        // Five seconds of nothing: a slow seek, a suspended process, a filter graph rebuild.
        clock.advance(5.seconds)
        video.tick(null)

        assertEquals(2, video.submittedFrames)
        assertEquals(0, video.droppedFrames)
        assertEquals(
            5.seconds.inWholeNanoseconds,
            renderer.presentations.last().targetNanos,
            "the old schedule is meaningless, so it is abandoned and the frame is aimed at now",
        )

        // The frame after it is a full period away from the new anchor rather than already overdue.
        // Without re-anchoring, everything buffered would be presented back to back and the viewer
        // would see a fast-forward glitch.
        assertEquals(40.milliseconds, video.tick(null))
        assertEquals(2, video.submittedFrames)
        video.close()
    }

    @Test
    fun `a frame is aimed at the instant the schedule chose and not at the moment of handover`() = runTest {
        val clock = TestClock()
        val renderer = RecordingRenderer()
        val video = playback(renderer, clock)
        listOf(0L, 40L, 80L, 120L).forEach { video.submit(FakeVideoFrame(pts(it))) }

        // A scheduler that wakes three milliseconds late every time, which is what a real one does.
        video.tick(null)
        repeat(3) {
            clock.advance(43.milliseconds)
            video.tick(null)
        }

        assertEquals(listOf(pts(0), pts(40), pts(80), pts(120)), renderer.timestamps)
        assertEquals(
            listOf(0L, 40L, 80L, 120L).map { it.milliseconds.inWholeNanoseconds },
            renderer.targets,
            "a renderer that can aim at a time needs the intended instant, not how late the wake-up was",
        )
        assertEquals(0, video.droppedFrames)
        video.close()
    }

    @Test
    fun `every frame is closed exactly once whichever way it leaves the pipeline`() = runTest {
        val ledger = LeakLedger()
        val clock = TestClock()
        val renderer = RecordingRenderer()
        val video = playback(renderer, clock)

        // Presented and dropped.
        listOf(0L, 40L, 80L, 120L).forEach { video.submit(FakeVideoFrame(pts(it), ledger = ledger)) }
        video.tick(null)
        clock.advance(130.milliseconds)
        video.tick(null)
        assertEquals(1, video.droppedFrames)
        video.tick(null)

        // Flushed: the frame at 120 ms is still queued when the seek happens.
        video.flush(Generation(1))

        // Superseded, then presented in the new generation.
        video.submit(FakeVideoFrame(pts(200), Generation.Initial, ledger = ledger))
        video.submit(FakeVideoFrame(pts(240), Generation(1), ledger = ledger))
        video.tick(null)
        video.tick(null)

        // Closed with the pipeline.
        video.submit(FakeVideoFrame(pts(280), Generation(1), ledger = ledger))
        video.close()

        assertEquals(listOf(pts(0), pts(80), pts(240)), renderer.timestamps)
        assertEquals(3, video.submittedFrames)
        assertEquals(7, ledger.openCount)
        assertEquals(
            7,
            ledger.closeCount,
            "presented, dropped, superseded, flushed and closed: five ways out, each closing its frame",
        )
        assertEquals(
            0,
            ledger.doubleCloseCount,
            "and never twice: the queue keeps no frame it has handed to a renderer",
        )
        assertEquals(0, ledger.liveCount)
    }

    @Test
    fun `with no renderer attached the schedule owns the frames and closes them`() = runTest {
        val ledger = LeakLedger()
        val clock = TestClock()
        val video = playback(renderer = null, clock = clock)
        listOf(0L, 40L, 80L).forEach { video.submit(FakeVideoFrame(pts(it), ledger = ledger)) }

        video.tick(null)
        clock.advance(40.milliseconds)
        video.tick(null)

        assertEquals(2, video.headlessFrames, "frames nothing drew are counted apart from frames a renderer took")
        assertEquals(0, video.submittedFrames)
        assertEquals(1, ledger.liveCount, "only the frame still queued is still open")

        video.close()
        assertEquals(0, ledger.liveCount)
        assertEquals(0, ledger.doubleCloseCount)
    }
}
