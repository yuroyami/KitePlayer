package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import platform.darwin.KERN_SUCCESS
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_port_deallocate
import platform.darwin.mach_task_self_
import platform.darwin.task_threads
import platform.darwin.thread_act_array_tVar
import platform.darwin.thread_act_tVar
import platform.darwin.vm_deallocate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The renderer's two handovers, driven from two threads at once.
 *
 * Neither of the things checked here is visible in a playing video, which is why both were wrong for
 * as long as they were. A frame stranded by a close is a leaked decoder buffer, not a missing picture;
 * an unbounded main queue is a memory graph nobody looks at until a slow machine stutters. Both are
 * ordinary races, so both are driven from real threads rather than reasoned about: the first closes the
 * renderer a hundred times while frames keep arriving and counts every frame in and out, and the second
 * stands in for a main thread that is busy and counts what the renderer put on it.
 *
 * The window is not part of either test. The renderer is built through its internal constructor with
 * the main queue and the image view replaced by things a test can inspect, which is what makes the
 * timing deterministic rather than a matter of how loaded this machine is.
 */
@OptIn(ExperimentalForeignApi::class)
class AppKitVideoRendererTest {

    @Test
    fun `closing while frames arrive closes every frame once and leaves no thread behind`() = runBlocking {
        val iterations = 100
        val framesPerIteration = 32
        val ledger = LeakLedger()

        // Dispatchers.Default starts its threads on demand, so it is brought up to full size before the
        // baseline is taken. Otherwise its pool would be counted as this renderer's leak.
        coroutineScope {
            repeat(64) { launch(Dispatchers.Default) { delay(20) } }
        }
        delay(100)
        val threadsBefore = liveThreadCount()

        repeat(iterations) { iteration ->
            val drawn = atomic(0L)
            val renderer = AppKitVideoRenderer(
                convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
                // A main thread that is never behind. Delivery then runs on the conversion worker, which
                // close() joins, so by the end of an iteration every frame is fully accounted for.
                enqueueOnMain = { block -> block() },
                showImage = { drawn.incrementAndGet() },
            )

            coroutineScope {
                launch(Dispatchers.Default) {
                    repeat(framesPerIteration) { index ->
                        renderer.present(FakeVideoFrame(Pts(index * 40_000L), ledger = ledger), targetNanos = 0L)
                    }
                }
                launch(Dispatchers.Default) {
                    // A different point in the run each iteration, so across the hundred the close lands
                    // before the first frame, between two of them, and after the last one.
                    repeat(iteration % 9) { yield() }
                    renderer.close()
                }
            }
            // Idempotent, and the reason the accounting below is final: whichever of the two closes ran
            // first did all the work, and the second one returns having done nothing.
            renderer.close()

            val accounted = renderer.presentedFrames + renderer.supersededFrames + renderer.failedFrames
            assertEquals(
                framesPerIteration.toLong(),
                accounted,
                "iteration $iteration: every frame is drawn, superseded or failed, and exactly one of them",
            )
            assertEquals(
                drawn.value,
                renderer.presentedFrames,
                "iteration $iteration: a drawn frame is one that reached the image view, nothing else",
            )
        }

        assertEquals(iterations * framesPerIteration, ledger.openCount)
        assertEquals(
            ledger.openCount,
            ledger.closeCount,
            "present takes ownership whether it accepts or refuses, so every frame must be closed",
        )
        assertEquals(0, ledger.doubleCloseCount, "a frame with two owners is a use after free waiting to happen")
        assertEquals(0, ledger.liveCount, "a frame stranded in a slot is a leaked decoder buffer")

        delay(200)
        val threadsAfter = liveThreadCount()
        assertTrue(
            threadsAfter <= threadsBefore + 4,
            "closing a renderer must end its conversion thread: $threadsBefore threads before " +
                "$iterations renderers, $threadsAfter after, and a leak would show close to $iterations more",
        )
    }

    @Test
    fun `a slow main thread is given one delivery block and draws the newest image`() = runBlocking {
        val frames = 12
        val firstWidth = 16
        val ledger = LeakLedger()
        val mainQueue = DeferredMainQueue()
        val converted = atomic(0)
        val drawnWidths = mutableListOf<Double>()

        val renderer = AppKitVideoRenderer(
            convert = { frame ->
                converted.incrementAndGet()
                ByteArray(frame.size.width * frame.size.height * 4)
            },
            enqueueOnMain = { block -> mainQueue.enqueue(block) },
            // Each frame gets its own width, so the image that reaches the view names the frame it
            // came from and "the newest one wins" is an equality rather than an impression.
            showImage = { image -> drawnWidths += image.size.useContents { width } },
        )

        try {
            repeat(frames) { index ->
                val frame = FakeVideoFrame(
                    pts = Pts(index * 40_000L),
                    size = VideoSize(width = firstWidth + index * 2, height = 8),
                    ledger = ledger,
                )
                assertTrue(renderer.present(frame, targetNanos = 0L), "an open renderer accepts every frame")

                // Each frame is converted before the next one is presented, so the frame slot never
                // supersedes anything and every count below is about the image slot alone.
                if (index == 0) {
                    awaitTrue("the first image to be finished queues one delivery block") {
                        mainQueue.enqueued == 1
                    }
                } else {
                    awaitTrue("image $index replaces the one still waiting") {
                        renderer.supersededFrames == index.toLong()
                    }
                }
            }

            assertEquals(frames, converted.value, "the worker keeps converting while the main thread is busy")
            assertEquals(1, mainQueue.enqueued, "$frames images, and the main thread was given one block")
            assertEquals(0, mainQueue.overwritten, "a second block behind the first is the unbounded work itself")
            assertEquals(0L, renderer.presentedFrames, "nothing is drawn while the block is still waiting")
            assertEquals(
                (frames - 1).toLong(),
                renderer.supersededFrames,
                "every image but the newest was replaced in the slot, and each one is counted",
            )

            assertTrue(mainQueue.runNext(), "one delivery block was waiting")
            assertEquals(
                listOf((firstWidth + (frames - 1) * 2).toDouble()),
                drawnWidths,
                "the block draws the newest image and only that one",
            )
            assertEquals(1L, renderer.presentedFrames)
            assertEquals(0L, renderer.failedFrames)
            assertFalse(mainQueue.runNext(), "the block emptied the slot, so nothing is left to run")
        } finally {
            renderer.close()
        }

        assertEquals(frames, ledger.openCount)
        assertEquals(frames, ledger.closeCount, "an image being replaced does not excuse the frame from being closed")
        assertEquals(0, ledger.doubleCloseCount)
        assertEquals(0, ledger.liveCount)
    }

    /** Polls [condition] for five seconds, which is far longer than any step here needs. */
    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        repeat(5_000) {
            if (condition()) return
            delay(1)
        }
        fail("waited five seconds for $what, and it never happened")
    }

    /**
     * A main queue that runs nothing until this test says so.
     *
     * That is what a busy main thread looks like from the renderer's side, and it is the only way to
     * ask the question this test asks: how much work does the renderer leave sitting on it.
     */
    private class DeferredMainQueue {
        private val waiting = atomic<Delivery?>(null)
        private val queued = atomic(0)
        private val replaced = atomic(0)

        /** Blocks handed over in total. */
        val enqueued: Int get() = queued.value

        /** Blocks queued while another was still waiting, which must never happen. */
        val overwritten: Int get() = replaced.value

        fun enqueue(block: () -> Unit) {
            if (waiting.getAndSet(Delivery(block)) != null) replaced.incrementAndGet()
            queued.incrementAndGet()
        }

        fun runNext(): Boolean {
            val delivery = waiting.getAndSet(null) ?: return false
            delivery.block()
            return true
        }
    }

    private class Delivery(val block: () -> Unit)

    /**
     * Counts every frame created and closed.
     *
     * The close flag is atomic rather than a plain field because the whole point is to catch two owners
     * closing one frame from two threads, which is exactly the case a plain field would miss.
     */
    private class LeakLedger {
        private val opened = atomic(0)
        private val closed = atomic(0)
        private val doubleClosed = atomic(0)

        fun onOpen() {
            opened.incrementAndGet()
        }

        fun onClose(alreadyClosed: Boolean) {
            if (alreadyClosed) doubleClosed.incrementAndGet() else closed.incrementAndGet()
        }

        val openCount: Int get() = opened.value
        val closeCount: Int get() = closed.value
        val doubleCloseCount: Int get() = doubleClosed.value
        val liveCount: Int get() = opened.value - closed.value
    }

    private class FakeVideoFrame(
        override val pts: Pts,
        override val size: VideoSize = VideoSize(16, 16),
        override val generation: Generation = Generation.Initial,
        override val duration: Pts? = null,
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p,
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.guessFor(16),
        override val hardwareSurface: Nothing? = null,
        private val ledger: LeakLedger,
    ) : VideoFrame {
        private val isClosed = atomic(false)

        init {
            ledger.onOpen()
        }

        override fun close() {
            ledger.onClose(alreadyClosed = !isClosed.compareAndSet(expect = false, update = true))
        }

        override fun toString(): String = "Frame($pts, $generation)"
    }
}

/**
 * How many threads this process has right now, asked of the kernel.
 *
 * A leaked `newSingleThreadContext` is invisible in every other way: no test fails, nothing is
 * reported, and the cost only shows up as a thread per renderer in a player that reopens its output on
 * every track change. Mach answers the question directly, and the thread rights and the array it hands
 * back are given straight back so that counting does not itself leak.
 */
@OptIn(ExperimentalForeignApi::class)
private fun liveThreadCount(): Int = memScoped {
    val threads = alloc<thread_act_array_tVar>()
    val count = alloc<mach_msg_type_number_tVar>()
    val status = task_threads(mach_task_self_, threads.ptr, count.ptr)
    if (status != KERN_SUCCESS) error("task_threads failed with $status, so the thread count is unknown")
    val total = count.value.toInt()
    val ports = threads.value ?: return@memScoped total
    for (index in 0 until total) mach_port_deallocate(mach_task_self_, ports[index])
    vm_deallocate(
        mach_task_self_,
        ports.rawValue.toLong().convert(),
        (total.toLong() * sizeOf<thread_act_tVar>()).convert(),
    )
    total
}
