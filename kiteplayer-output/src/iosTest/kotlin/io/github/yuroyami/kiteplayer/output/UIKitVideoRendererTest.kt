package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CALayer
import platform.QuartzCore.kCAGravityResizeAspect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalForeignApi::class)
class UIKitVideoRendererTest {

    @Test
    fun `a slow main thread gets one block and that block presents the newest image`() = runBlocking {
        val frames = 12
        val firstWidth = 16
        val ledger = LeakLedger()
        val mainQueue = DeferredMainQueue()
        val converted = atomic(0)
        val drawnWidth = atomic(0)
        val renderer = UIKitVideoRenderer(
            convert = { frame ->
                converted.incrementAndGet()
                ByteArray(frame.size.width * frame.size.height * 4)
            },
            enqueueOnMain = mainQueue::enqueue,
            deliverImage = { image ->
                if (image != null) drawnWidth.value = CGImageGetWidth(image).toInt() else drawnWidth.value = 0
            },
        )

        try {
            repeat(frames) { index ->
                val width = firstWidth + index * 2
                assertTrue(
                    renderer.present(
                        FakeVideoFrame(Pts(index * 40_000L), VideoSize(width, 8), ledger = ledger),
                        targetNanos = 0L,
                    ),
                )
                if (index == 0) {
                    awaitTrue("the first delivery block") { mainQueue.enqueued == 1 }
                } else {
                    awaitTrue("image $index to supersede its predecessor") {
                        renderer.supersededFrames == index.toLong()
                    }
                }
            }

            assertEquals(frames, converted.value)
            assertEquals(1, mainQueue.enqueued, "$frames images must leave exactly one queued block")
            assertEquals(0, mainQueue.overwritten, "a second queued block is already unbounded work")
            assertEquals(0L, renderer.presentedFrames)
            assertEquals((frames - 1).toLong(), renderer.supersededFrames)
            assertTrue(mainQueue.runNext())
            assertEquals(firstWidth + (frames - 1) * 2, drawnWidth.value)
            assertEquals(1L, renderer.presentedFrames)
            assertEquals(0L, renderer.failedFrames)
            assertFalse(mainQueue.runNext())
        } finally {
            renderer.close()
        }

        assertEquals(frames, ledger.openCount)
        assertEquals(frames, ledger.closeCount)
        assertEquals(0, ledger.doubleCloseCount)
        assertEquals(0, ledger.liveCount)
    }

    @Test
    fun `concurrent presentation and close account for and close every frame exactly once`() = runBlocking {
        val iterations = 30
        val framesPerIteration = 24
        val ledger = LeakLedger()

        repeat(iterations) { iteration ->
            val renderer = UIKitVideoRenderer(
                convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
                enqueueOnMain = { block -> block() },
                deliverImage = {},
            )
            coroutineScope {
                launch(Dispatchers.Default) {
                    repeat(framesPerIteration) { index ->
                        renderer.present(FakeVideoFrame(Pts(index.toLong()), ledger = ledger), 0L)
                    }
                }
                launch(Dispatchers.Default) {
                    repeat(iteration % 7) { yield() }
                    renderer.close()
                }
            }
            renderer.close()
            assertEquals(
                framesPerIteration.toLong(),
                renderer.presentedFrames + renderer.supersededFrames + renderer.failedFrames,
                "iteration $iteration",
            )
        }

        assertEquals(iterations * framesPerIteration, ledger.openCount)
        assertEquals(ledger.openCount, ledger.closeCount)
        assertEquals(0, ledger.doubleCloseCount)
        assertEquals(0, ledger.liveCount)
    }

    @Test
    fun `quarter turns swap dimensions and move every source pixel`() = runBlocking {
        val width = 4
        val height = 2
        val source = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val at = (y * width + x) * 4
                source[at] = (10 + x * 40).toByte()
                source[at + 1] = (10 + y * 40).toByte()
                source[at + 3] = -1
            }
        }
        val landings: List<Pair<Int, (Int, Int) -> Pair<Int, Int>>> = listOf(
            0 to { x, y -> x to y },
            90 to { x, y -> (height - 1 - y) to x },
            180 to { x, y -> (width - 1 - x) to (height - 1 - y) },
            270 to { x, y -> y to (width - 1 - x) },
            45 to { x, y -> x to y },
        )

        for ((rotation, landing) in landings) {
            val ledger = LeakLedger()
            val drawn = atomic<DrawnPixels?>(null)
            val renderer = UIKitVideoRenderer(
                convert = { source },
                enqueueOnMain = { block -> block() },
                deliverImage = { image -> drawn.value = image?.let(::readBack) },
            )
            try {
                assertTrue(renderer.present(FakeVideoFrame(Pts(0), VideoSize(width, height), rotation, ledger), 0L))
                awaitTrue("the $rotation degree image") { drawn.value != null }
                val pixels = assertNotNull(drawn.value)
                val quarterTurned = rotation == 90 || rotation == 270
                assertEquals(if (quarterTurned) height else width, pixels.width)
                assertEquals(if (quarterTurned) width else height, pixels.height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val (toX, toY) = landing(x, y)
                        val at = (y * width + x) * 4
                        assertEquals(
                            (source[at].toInt() and 0xFF) to (source[at + 1].toInt() and 0xFF),
                            pixels.redAndGreenAt(toX, toY),
                            "$rotation degrees: ($x, $y) must land at ($toX, $toY)",
                        )
                    }
                }
                assertEquals(1L, renderer.presentedFrames)
            } finally {
                renderer.close()
            }
            assertEquals(1, ledger.closeCount)
            assertEquals(0, ledger.doubleCloseCount)
        }
    }

    /**
     * S4.c's simulator proof, returned from the S2 pause: a white cue composites ABOVE the red
     * picture in the delivered image, in display space, and the picture survives beside it.
     */
    @Test
    fun `an overlay composites above the picture in the delivered image`() = runBlocking {
        val width = 64
        val height = 32
        val red = ByteArray(width * height * 4).also {
            var at = 0
            while (at < it.size) {
                it[at] = -1     /* R */
                it[at + 3] = -1 /* A */
                at += 4
            }
        }
        val ledger = LeakLedger()
        val drawn = atomic<DrawnPixels?>(null)
        val renderer = UIKitVideoRenderer(
            convert = { red },
            enqueueOnMain = { block -> block() },
            deliverImage = { image -> drawn.value = image?.let(::readBack) },
        )
        try {
            renderer.setOverlay(
                io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
                    images = listOf(
                        io.github.yuroyami.kiteplayer.spi.OverlayImage(
                            x = 24,
                            y = 12,
                            bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(
                                16,
                                8,
                                ByteArray(16 * 8 * 4) { -1 },
                            ),
                        ),
                    ),
                    viewportWidth = width,
                    viewportHeight = height,
                    contentHash = 4711L,
                ),
            )
            assertTrue(renderer.present(FakeVideoFrame(Pts(0), VideoSize(width, height), 0, ledger), 0L))
            awaitTrue("the composited image") { drawn.value != null }
            val pixels = assertNotNull(drawn.value)
            assertEquals(
                255 to 255,
                pixels.redAndGreenAt(31, 15),
                "the cue centre must be white above the picture",
            )
            assertEquals(
                255 to 0,
                pixels.redAndGreenAt(2, 2),
                "the picture must stay red beside the cue",
            )
        } finally {
            renderer.close()
        }
        assertEquals(1, ledger.closeCount)
    }

    @Test
    fun `pixel aspect is baked into image geometry before rotation`() = runBlocking {
        val size = VideoSize(width = 6, height = 4, pixelAspectNumerator = 2, pixelAspectDenominator = 1)
        for ((rotation, expected) in listOf(0 to (12 to 4), 90 to (4 to 12), 270 to (4 to 12))) {
            val ledger = LeakLedger()
            val dimensions = atomic<Pair<Int, Int>?>(null)
            val renderer = UIKitVideoRenderer(
                convert = { ByteArray(size.width * size.height * 4) },
                enqueueOnMain = { block -> block() },
                deliverImage = { image ->
                    if (image == null) {
                        dimensions.value = null
                    } else {
                        dimensions.value = CGImageGetWidth(image).toInt() to CGImageGetHeight(image).toInt()
                    }
                },
            )
            try {
                renderer.present(FakeVideoFrame(Pts(0), size, rotation, ledger), 0L)
                awaitTrue("the aspect-correct $rotation degree image") { dimensions.value != null }
                assertEquals(expected, dimensions.value)
            } finally {
                renderer.close()
            }
            assertEquals(1, ledger.closeCount)
            assertEquals(0, ledger.doubleCloseCount)
        }
    }

    @Test
    fun `invalid images fail safely and the renderer advertises only software formats`() = runBlocking {
        val contract = UIKitVideoRenderer(
            convert = { ByteArray(0) },
            enqueueOnMain = { block -> block() },
            deliverImage = {},
        )
        try {
            assertEquals(emptySet(), contract.supportedHardwareSurfaces())
            for (format in PlayerPixelFormat.entries) {
                assertEquals(format != PlayerPixelFormat.Opaque, contract.supports(format), format.name)
            }
            assertNull(contract.vsyncIntervalNanos())
            contract.setViewport(320, 240, 2f)
            contract.setOverlay(null)
            assertNull(withTimeoutOrNull(20) { contract.events.firstOrNull() })
        } finally {
            contract.close()
        }

        val cases = listOf(
            VideoSize(0, 8) to { _: VideoFrame -> ByteArray(0) },
            VideoSize(Int.MAX_VALUE, Int.MAX_VALUE) to { _: VideoFrame -> ByteArray(0) },
            VideoSize(1, 1, Int.MAX_VALUE, 1) to { _: VideoFrame -> ByteArray(4) },
            VideoSize(8, 8) to { _: VideoFrame -> ByteArray(8 * 8 * 4 - 1) },
            VideoSize(8, 8) to { _: VideoFrame -> ByteArray(8 * 8 * 4 + 1) },
            VideoSize(8, 8) to { _: VideoFrame -> error("conversion refused") },
        )
        for ((index, case) in cases.withIndex()) {
            val ledger = LeakLedger()
            val renderer = UIKitVideoRenderer(
                convert = case.second,
                enqueueOnMain = { block -> block() },
                deliverImage = { fail("invalid case $index must not deliver") },
            )
            try {
                assertTrue(renderer.present(FakeVideoFrame(Pts(index.toLong()), case.first, ledger = ledger), 0L))
                awaitTrue("invalid case $index to fail") { renderer.failedFrames == 1L }
                assertEquals(0L, renderer.presentedFrames)
            } finally {
                renderer.close()
            }
            assertEquals(1, ledger.closeCount)
            assertEquals(0, ledger.doubleCloseCount)
        }
    }

    @Test
    fun `close drains a waiting image clears the seam and makes its queued block inert`() = runBlocking {
        val ledger = LeakLedger()
        val queue = DeferredMainQueue()
        val borrowedPresent = atomic(false)
        val clearCalls = atomic(0)
        val renderer = UIKitVideoRenderer(
            convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
            enqueueOnMain = queue::enqueue,
            deliverImage = { image ->
                if (image == null) clearCalls.incrementAndGet() else borrowedPresent.value = true
            },
        )
        renderer.present(FakeVideoFrame(Pts(0), ledger = ledger), 0L)
        awaitTrue("one queued delivery") { queue.enqueued == 1 }

        renderer.close()
        renderer.close()
        assertFalse(renderer.present(FakeVideoFrame(Pts(1), ledger = ledger), 0L))
        assertEquals(1, clearCalls.value, "only the first close clears deterministic seam bookkeeping")
        assertFalse(borrowedPresent.value)
        assertEquals(2L, renderer.failedFrames, "the drained image and rejected post-close frame both fail")
        assertTrue(queue.runNext(), "the already queued block still exists")
        assertFalse(borrowedPresent.value, "a queued block cannot deliver after close returns")
        assertFalse(queue.runNext())
        assertEquals(2, ledger.closeCount)
        assertEquals(0, ledger.doubleCloseCount)
    }

    @Test
    fun `a rejected main enqueue releases its image and leaves the worker usable`() = runBlocking {
        val ledger = LeakLedger()
        val enqueueCalls = atomic(0)
        val renderer = UIKitVideoRenderer(
            convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
            enqueueOnMain = {
                enqueueCalls.incrementAndGet()
                error("queue refused")
            },
            deliverImage = { fail("nothing was enqueued") },
        )
        try {
            repeat(2) { index ->
                assertTrue(renderer.present(FakeVideoFrame(Pts(index.toLong()), ledger = ledger), 0L))
                awaitTrue("enqueue rejection ${index + 1}") { renderer.failedFrames == (index + 1).toLong() }
            }
            assertEquals(2, enqueueCalls.value)
            assertEquals(0L, renderer.presentedFrames)
        } finally {
            renderer.close()
        }
        assertEquals(2, ledger.closeCount)
        assertEquals(0, ledger.doubleCloseCount)
    }

    @Test
    fun `the production callback fills a real caller layer and close leaves those contents intact`() = runBlocking {
        val layer = CALayer()
        val ledger = LeakLedger()
        val renderer = UIKitVideoRenderer(layer, convert = { frame ->
            ByteArray(frame.size.width * frame.size.height * 4) { index ->
                if (index % 4 == 3) -1 else (index and 0x7F).toByte()
            }
        })
        renderer.present(FakeVideoFrame(Pts(0), VideoSize(10, 6), ledger = ledger), 0L)
        awaitTrue("the real CALayer contents", pumpMainRunLoop = true) { layer.contents != null }
        assertEquals(kCAGravityResizeAspect, layer.contentsGravity)
        assertEquals(1L, renderer.presentedFrames)

        renderer.close()
        assertNotNull(layer.contents, "the caller-owned layer retains its last image after renderer close")
        assertEquals(1, ledger.closeCount)
        assertEquals(0, ledger.doubleCloseCount)
        layer.contents = null
    }

    private fun readBack(image: CGImageRef): DrawnPixels {
        val width = CGImageGetWidth(image).toInt()
        val height = CGImageGetHeight(image).toInt()
        val bytes = ByteArray(width * height * 4)
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: fail("no RGB colour space")
        try {
            bytes.usePinned { pinned ->
                val context = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = width.toULong(),
                    height = height.toULong(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (width * 4).toULong(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
                ) ?: fail("no read-back context")
                try {
                    CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
        return DrawnPixels(width, height, bytes)
    }

    private suspend fun awaitTrue(
        what: String,
        pumpMainRunLoop: Boolean = false,
        condition: () -> Boolean,
    ) {
        repeat(5_000) {
            if (condition()) return
            if (pumpMainRunLoop) CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.001, true)
            delay(1)
        }
        fail("waited five seconds for $what")
    }

    private class DrawnPixels(val width: Int, val height: Int, private val rgba: ByteArray) {
        fun redAndGreenAt(x: Int, y: Int): Pair<Int, Int> {
            val at = (y * width + x) * 4
            return (rgba[at].toInt() and 0xFF) to (rgba[at + 1].toInt() and 0xFF)
        }
    }

    private class DeferredMainQueue {
        private val waiting = atomic<Delivery?>(null)
        private val queued = atomic(0)
        private val replaced = atomic(0)

        val enqueued: Int get() = queued.value
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
        override val rotationDegrees: Int = 0,
        private val ledger: LeakLedger,
        override val generation: Generation = Generation.Initial,
        override val duration: Pts? = null,
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p,
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.guessFor(size.height),
        override val hardwareSurface: Nothing? = null,
    ) : VideoFrame {
        private val isClosed = atomic(false)

        init {
            ledger.onOpen()
        }

        override fun close() {
            ledger.onClose(alreadyClosed = !isClosed.compareAndSet(expect = false, update = true))
        }
    }
}
