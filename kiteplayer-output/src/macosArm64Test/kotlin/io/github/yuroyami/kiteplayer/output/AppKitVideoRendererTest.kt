package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.AppKit.NSImage
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
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
import kotlin.test.assertNull
import kotlin.test.assertNotNull
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

    @Test
    fun `a quarter turn swaps the drawn dimensions and takes every pixel with it`() = runBlocking {
        val storedWidth = 4
        val storedHeight = 2
        // Every pixel its own colour: red carries the column and green the row. Where a colour lands
        // says which way the picture turned. A size alone would only say that it changed shape, and a
        // turn that went the wrong way or landed off the canvas changes shape correctly.
        val source = ByteArray(storedWidth * storedHeight * 4)
        for (y in 0 until storedHeight) {
            for (x in 0 until storedWidth) {
                val at = (y * storedWidth + x) * 4
                source[at] = (10 + x * 40).toByte()
                source[at + 1] = (10 + y * 40).toByte()
                source[at + 2] = 0
                source[at + 3] = -1
            }
        }

        // Where the stored pixel at (x, y) has to end up, per rotation. Both sides count y from the top.
        val landings: List<Pair<Int, (Int, Int) -> Pair<Int, Int>>> = listOf(
            0 to { x, y -> x to y },
            90 to { x, y -> (storedHeight - 1 - y) to x },
            180 to { x, y -> (storedWidth - 1 - x) to (storedHeight - 1 - y) },
            270 to { x, y -> y to (storedWidth - 1 - x) },
            // Not a quarter turn, so it is drawn as stored rather than refused. A display matrix can
            // describe any affine transform and this renderer draws four of them.
            45 to { x, y -> x to y },
        )

        for ((rotation, landing) in landings) {
            val ledger = LeakLedger()
            val drawn = atomic<NSImage?>(null)
            val renderer = AppKitVideoRenderer(
                convert = { source },
                enqueueOnMain = { block -> block() },
                showImage = { image -> drawn.value = image },
            )
            try {
                val frame = FakeVideoFrame(
                    pts = Pts(0),
                    size = VideoSize(storedWidth, storedHeight),
                    rotationDegrees = rotation,
                    ledger = ledger,
                )
                assertTrue(renderer.present(frame, targetNanos = 0L), "an open renderer accepts every frame")
                awaitTrue("the frame rotated $rotation degrees to be drawn") { drawn.value != null }

                val image = assertNotNull(drawn.value)
                val quarterTurned = rotation == 90 || rotation == 270
                val expectedWidth = if (quarterTurned) storedHeight else storedWidth
                val expectedHeight = if (quarterTurned) storedWidth else storedHeight
                // The size an image view lays the picture out at, which is where a non-square pixel
                // aspect would show. These frames are square-pixelled, so it is the pixel size too.
                assertEquals(
                    expectedWidth,
                    image.size.useContents { width }.toInt(),
                    "the image laid out at $rotation degrees is the wrong width",
                )
                assertEquals(
                    expectedHeight,
                    image.size.useContents { height }.toInt(),
                    "the image laid out at $rotation degrees is the wrong height",
                )

                val pixels = readBack(image)
                assertEquals(expectedWidth, pixels.width, "the drawn pixels at $rotation degrees are the wrong width")
                assertEquals(
                    expectedHeight,
                    pixels.height,
                    "the drawn pixels at $rotation degrees are the wrong height",
                )
                for (y in 0 until storedHeight) {
                    for (x in 0 until storedWidth) {
                        val (toX, toY) = landing(x, y)
                        val at = (y * storedWidth + x) * 4
                        assertEquals(
                            (source[at].toInt() and 0xFF) to (source[at + 1].toInt() and 0xFF),
                            pixels.redAndGreenAt(toX, toY),
                            "at $rotation degrees the pixel at ($x $y) has to land at ($toX $toY)",
                        )
                    }
                }
            } finally {
                renderer.close()
            }
            assertEquals(1, ledger.openCount)
            assertEquals(1, ledger.closeCount, "a turned frame is still closed exactly once")
            assertEquals(0, ledger.doubleCloseCount)
        }
    }

    @Test
    fun `the rotated fixtures own geometry draws with its dimensions swapped`() = runBlocking {
        // The numbers of `rotated90ccw.mp4`, measured by `RotationTest` in kiteplayer-ffmpeg on the real
        // file: 320x240 stored, and a display matrix that arrives as 270 clockwise degrees. That test
        // cannot drive this renderer, because the constructor a test can inject into is internal and
        // `internal` does not cross a Gradle module boundary, so the two halves of the claim meet here,
        // on the fixture's own geometry. This half is the one the register words as the output
        // dimensions swapping.
        val storedWidth = 320
        val storedHeight = 240
        val rotation = 270
        val source = ByteArray(storedWidth * storedHeight * 4)
        // One marker per corner, so a turn that went the wrong way is caught and not only a shape change.
        val corners = listOf(0 to 0, storedWidth - 1 to 0, 0 to storedHeight - 1, storedWidth - 1 to storedHeight - 1)
        for ((index, corner) in corners.withIndex()) {
            val at = (corner.second * storedWidth + corner.first) * 4
            source[at] = (40 + index * 40).toByte()
            source[at + 1] = (200 - index * 40).toByte()
            source[at + 3] = -1
        }

        val ledger = LeakLedger()
        val drawn = atomic<NSImage?>(null)
        val renderer = AppKitVideoRenderer(
            convert = { source },
            enqueueOnMain = { block -> block() },
            showImage = { image -> drawn.value = image },
        )
        try {
            val frame = FakeVideoFrame(
                pts = Pts(0),
                size = VideoSize(storedWidth, storedHeight),
                rotationDegrees = rotation,
                ledger = ledger,
            )
            assertTrue(renderer.present(frame, targetNanos = 0L), "an open renderer accepts every frame")
            awaitTrue("the fixture's frame to be drawn") { drawn.value != null }

            val image = assertNotNull(drawn.value)
            assertEquals(storedHeight, image.size.useContents { width }.toInt(), "240 wide after a quarter turn")
            assertEquals(storedWidth, image.size.useContents { height }.toInt(), "320 high after a quarter turn")
            val pixels = readBack(image)
            assertEquals(storedHeight, pixels.width, "the drawn pixels are 240 wide")
            assertEquals(storedWidth, pixels.height, "the drawn pixels are 320 high")
            // 270 clockwise sends the stored pixel at (x, y) to (y, storedWidth - 1 - x).
            for (corner in corners) {
                val (x, y) = corner
                val at = (y * storedWidth + x) * 4
                assertEquals(
                    (source[at].toInt() and 0xFF) to (source[at + 1].toInt() and 0xFF),
                    pixels.redAndGreenAt(y, storedWidth - 1 - x),
                    "the corner stored at ($x $y) has to land at ($y ${storedWidth - 1 - x})",
                )
            }
        } finally {
            renderer.close()
        }
        assertEquals(1, ledger.openCount)
        assertEquals(1, ledger.closeCount)
        assertEquals(0, ledger.doubleCloseCount)
    }

    /**
     * The pixels of a drawn image, read back through a bitmap this test owns.
     *
     * Reading the bytes rather than trusting the size is what makes the rotation assertions real. The
     * read-back context is device RGB, the same as the renderer's, and it is the image's own pixel size,
     * so nothing is scaled and no colour is converted on the way in: the bytes that come out are the
     * bytes that were drawn.
     */
    /**
     * The CG compositor's transform math (S2.c, the Apple twin of the Android 885ccc0 arm): an
     * overlay authored against a 4x2 viewport lands on the drawn image at the same spot, above
     * the picture. Red carries proof the picture is underneath; white proves the overlay won.
     */
    @Test
    fun `an overlay draws above the picture at its authored spot`() = runBlocking {
        val storedWidth = 4
        val storedHeight = 2
        val red = ByteArray(storedWidth * storedHeight * 4)
        for (index in red.indices step 4) {
            red[index] = -1
            red[index + 3] = -1
        }
        val drawn = atomic<NSImage?>(null)
        val renderer = AppKitVideoRenderer(
            convert = { red },
            enqueueOnMain = { block -> block() },
            showImage = { image -> drawn.value = image },
        )
        try {
            renderer.setOverlay(
                io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
                    images = listOf(
                        io.github.yuroyami.kiteplayer.spi.OverlayImage(
                            x = 1,
                            y = 0,
                            bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(
                                2, 1, ByteArray(2 * 1 * 4) { 0xFF.toByte() },
                            ),
                        ),
                    ),
                    viewportWidth = storedWidth,
                    viewportHeight = storedHeight,
                    contentHash = 7L,
                ),
            )
            val ledger = LeakLedger()
            val frame = FakeVideoFrame(
                pts = Pts(0),
                size = VideoSize(storedWidth, storedHeight),
                rotationDegrees = 0,
                ledger = ledger,
            )
            assertTrue(renderer.present(frame, targetNanos = 0L))
            awaitTrue("the composited frame was drawn") { drawn.value != null }
            val pixels = readBack(assertNotNull(drawn.value))
            assertEquals(
                255 to 255,
                pixels.redAndGreenAt(1, 0),
                "the overlay pixel at (1,0) must be white above the red picture",
            )
            assertEquals(
                255 to 0,
                pixels.redAndGreenAt(0, 1),
                "the picture outside the overlay must stay red",
            )
        } finally {
            renderer.close()
        }
    }

    /**
     * [close] blocks the caller, which on both platforms is the UI thread, so it
     * must never wait on work it could have refused to start.
     *
     * The window driven here is the real one: a conversion is already running, a cue edge has
     * armed the paused-picture redraw, and the close lands between them. The redraw is a full
     * image build for a picture nobody will ever see, and the closing thread used to wait for it.
     * One delivery, not two, is the whole assertion.
     */
    @Test
    fun `a close does not wait on a redraw it could refuse to start`() = runBlocking {
        val gate = atomic(false)
        val converting = atomic(false)
        val enqueued = atomic(0)
        val ledger = LeakLedger()
        val renderer = AppKitVideoRenderer(
            convert = {
                converting.value = true
                while (!gate.value) { /* held until the close is known to have begun */ }
                redPixels(4, 2)
            },
            enqueueOnMain = { block ->
                enqueued.incrementAndGet()
                block()
            },
            showImage = { },
        )

        assertTrue(
            renderer.present(
                FakeVideoFrame(
                    pts = Pts(0),
                    size = VideoSize(4, 2),
                    rotationDegrees = 0,
                    ledger = ledger,
                ),
                targetNanos = 0L,
            ),
        )
        awaitTrue("the worker picked the frame up") { converting.value }
        // Arms the paused-picture redraw while the conversion above is still held.
        renderer.setOverlay(null)

        val closeEntered = atomic(false)
        val closeReturned = atomic(false)
        coroutineScope {
            launch(Dispatchers.Default) {
                closeEntered.value = true
                renderer.close()
                closeReturned.value = true
            }
            launch(Dispatchers.Default) {
                // Probing with a present would leave a frame in the slot and suppress the very
                // redraw this test is about, so the handshake is the flag plus a margin: between
                // it and `closed` being set there are three atomic writes and a channel close.
                awaitTrue("the close was entered") { closeEntered.value }
                delay(200)
                gate.value = true
            }
        }
        awaitTrue("close returned") { closeReturned.value }

        assertEquals(
            1,
            enqueued.value,
            "the conversion already running still delivers; the redraw behind it must not",
        )
    }

    // The CPU fallback owed the one colour-matrix law. Saturation 0 is the
    // cheapest unambiguous probe: pure red becomes its own BT.709 luma in all three channels.
    @Test
    fun `the CPU fallback applies the picture controls`() = runBlocking {
        val drawn = atomic<NSImage?>(null)
        val draws = atomic(0)
        val renderer = AppKitVideoRenderer(
            convert = { redPixels(4, 2) },
            enqueueOnMain = { block -> block() },
            showImage = { image ->
                drawn.value = image
                draws.incrementAndGet()
            },
        )
        try {
            renderer.setAdjustments(io.github.yuroyami.kiteplayer.VideoAdjustments(saturation = 0f))
            assertTrue(
                renderer.present(
                    FakeVideoFrame(
                        pts = Pts(0),
                        size = VideoSize(4, 2),
                        rotationDegrees = 0,
                        ledger = LeakLedger(),
                    ),
                    targetNanos = 0L,
                ),
            )
            awaitTrue("the frame was drawn") { draws.value >= 1 }
            val pixels = readBack(assertNotNull(drawn.value))
            // 0.2126 * 255 = 54.2, rounded to 54 on both channels.
            assertEquals(54 to 54, pixels.redAndGreenAt(0, 0), "saturation 0 leaves BT.709 luma")
        } finally {
            renderer.close()
        }
    }

    // The framing half: an aspect override reshapes the PRESENTED picture.
    @Test
    fun `the CPU fallback honours an aspect override`() = runBlocking {
        val drawn = atomic<NSImage?>(null)
        val draws = atomic(0)
        val renderer = AppKitVideoRenderer(
            convert = { redPixels(4, 2) },
            enqueueOnMain = { block -> block() },
            showImage = { image ->
                drawn.value = image
                draws.incrementAndGet()
            },
        )
        try {
            renderer.setTransform(io.github.yuroyami.kiteplayer.VideoTransform(aspectOverride = 4f))
            assertTrue(
                renderer.present(
                    FakeVideoFrame(
                        pts = Pts(0),
                        size = VideoSize(4, 2),
                        rotationDegrees = 0,
                        ledger = LeakLedger(),
                    ),
                    targetNanos = 0L,
                ),
            )
            awaitTrue("the frame was drawn") { draws.value >= 1 }
            val image = assertNotNull(drawn.value)
            assertEquals(8, image.size.useContents { width }.toInt(), "2 high at 4:1 is 8 wide")
            assertEquals(2, image.size.useContents { height }.toInt(), "the height is untouched")
        } finally {
            renderer.close()
        }
    }

    // The framing half: pan moves the drawn picture and the canvas keeps the rest.
    @Test
    fun `the CPU fallback honours zoom and pan`() = runBlocking {
        val drawn = atomic<NSImage?>(null)
        val draws = atomic(0)
        val renderer = AppKitVideoRenderer(
            convert = { redPixels(4, 2) },
            enqueueOnMain = { block -> block() },
            showImage = { image ->
                drawn.value = image
                draws.incrementAndGet()
            },
        )
        try {
            // Half a width to the right: the left half of the canvas is left behind empty.
            renderer.setTransform(io.github.yuroyami.kiteplayer.VideoTransform(panX = 0.5f))
            assertTrue(
                renderer.present(
                    FakeVideoFrame(
                        pts = Pts(0),
                        size = VideoSize(4, 2),
                        rotationDegrees = 0,
                        ledger = LeakLedger(),
                    ),
                    targetNanos = 0L,
                ),
            )
            awaitTrue("the frame was drawn") { draws.value >= 1 }
            val pixels = readBack(assertNotNull(drawn.value))
            assertEquals(0 to 0, pixels.redAndGreenAt(0, 0), "the picture moved off this column")
            assertEquals(255 to 0, pixels.redAndGreenAt(3, 0), "and onto this one")
        } finally {
            renderer.close()
        }
    }

    /** An opaque all-red picture, the probe every colour test above draws. */
    private fun redPixels(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(width * height * 4)
        for (index in bytes.indices step 4) {
            bytes[index] = -1
            bytes[index + 3] = -1
        }
        return bytes
    }

    // RgbaBitmap's own contract admits storage LARGER than width*height*4, so a
    // fallback that demands exact equality drops a legal overlay without a word.
    @Test
    fun `an overlay bitmap with slack storage is still drawn`() = runBlocking {
        val storedWidth = 4
        val storedHeight = 2
        val red = ByteArray(storedWidth * storedHeight * 4)
        for (index in red.indices step 4) {
            red[index] = -1
            red[index + 3] = -1
        }
        val drawn = atomic<NSImage?>(null)
        val draws = atomic(0)
        val renderer = AppKitVideoRenderer(
            convert = { red },
            enqueueOnMain = { block -> block() },
            showImage = { image ->
                drawn.value = image
                draws.incrementAndGet()
            },
        )
        try {
            val ledger = LeakLedger()
            renderer.setOverlay(
                io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
                    images = listOf(
                        io.github.yuroyami.kiteplayer.spi.OverlayImage(
                            x = 1,
                            y = 0,
                            // Sixteen slack bytes past the minimum: legal by RgbaBitmap's require.
                            bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(
                                2, 1, ByteArray(2 * 1 * 4 + 16) { 0xFF.toByte() },
                            ),
                        ),
                    ),
                    viewportWidth = storedWidth,
                    viewportHeight = storedHeight,
                    contentHash = 13L,
                ),
            )
            assertTrue(
                renderer.present(
                    FakeVideoFrame(
                        pts = Pts(0),
                        size = VideoSize(storedWidth, storedHeight),
                        rotationDegrees = 0,
                        ledger = ledger,
                    ),
                    targetNanos = 0L,
                ),
            )
            awaitTrue("the frame was drawn") { draws.value >= 1 }
            val pixels = readBack(assertNotNull(drawn.value))
            assertEquals(
                255 to 255,
                pixels.redAndGreenAt(1, 0),
                "a bitmap with slack storage carries the same pixels and must still be composited",
            )
        } finally {
            renderer.close()
        }
    }

    // The M4 surge's headline finally pinned: a cue arriving while
    // the picture is paused must redraw the retained frame with no new present() at all.
    @Test
    fun `an overlay set after the last frame redraws the retained picture`() = runBlocking {
        val storedWidth = 4
        val storedHeight = 2
        val red = ByteArray(storedWidth * storedHeight * 4)
        for (index in red.indices step 4) {
            red[index] = -1
            red[index + 3] = -1
        }
        val drawn = atomic<NSImage?>(null)
        val draws = atomic(0)
        val renderer = AppKitVideoRenderer(
            convert = { red },
            enqueueOnMain = { block -> block() },
            showImage = { image ->
                drawn.value = image
                draws.incrementAndGet()
            },
        )
        try {
            val ledger = LeakLedger()
            val frame = FakeVideoFrame(
                pts = Pts(0),
                size = VideoSize(storedWidth, storedHeight),
                rotationDegrees = 0,
                ledger = ledger,
            )
            assertTrue(renderer.present(frame, targetNanos = 0L))
            awaitTrue("the plain frame was drawn") { draws.value >= 1 }
            val before = draws.value

            // The pause: no further present. The cue edge alone must produce a new image.
            renderer.setOverlay(
                io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
                    images = listOf(
                        io.github.yuroyami.kiteplayer.spi.OverlayImage(
                            x = 1,
                            y = 0,
                            bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(
                                2, 1, ByteArray(2 * 1 * 4) { 0xFF.toByte() },
                            ),
                        ),
                    ),
                    viewportWidth = storedWidth,
                    viewportHeight = storedHeight,
                    contentHash = 8L,
                ),
            )
            awaitTrue("the paused picture redraws for the cue") { draws.value > before }
            val pixels = readBack(assertNotNull(drawn.value))
            assertEquals(
                255 to 255,
                pixels.redAndGreenAt(1, 0),
                "the redraw must carry the overlay above the retained picture",
            )
            assertEquals(
                255 to 0,
                pixels.redAndGreenAt(0, 1),
                "the retained picture itself survives the redraw",
            )
        } finally {
            renderer.close()
        }
    }

    private fun readBack(image: NSImage): DrawnPixels {
        val cgImage = image.CGImageForProposedRect(null, null, null) ?: fail("the drawn image has no bitmap")
        val pixelWidth = CGImageGetWidth(cgImage).toInt()
        val pixelHeight = CGImageGetHeight(cgImage).toInt()
        val bytes = ByteArray(pixelWidth * pixelHeight * 4)
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: fail("no device RGB colour space")
        try {
            bytes.usePinned { pinned ->
                val context = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = pixelWidth.toULong(),
                    height = pixelHeight.toULong(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (pixelWidth * 4).toULong(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
                ) ?: fail("no read-back context")
                try {
                    CGContextDrawImage(
                        context,
                        CGRectMake(0.0, 0.0, pixelWidth.toDouble(), pixelHeight.toDouble()),
                        cgImage,
                    )
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
        return DrawnPixels(pixelWidth, pixelHeight, bytes)
    }

    // ── The renderer that DID it is the one that says so ────────────────────────────────────

    /**
     * Collects this renderer's events into a channel, and does not return until the collector is
     * SUBSCRIBED.
     *
     * The events flow has no replay, so anything emitted before a subscriber exists is dropped.
     * Starting a collector and presenting a frame straight after is a race that a loaded machine
     * loses, which is exactly how this test first failed. `onSubscription` is the flow's own
     * answer to it, and the channel keeps the events off a list two threads would share.
     */
    private suspend fun CoroutineScope.eventsOf(renderer: AppKitVideoRenderer): Pair<Channel<RendererEvent>, Job> {
        val events = Channel<RendererEvent>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            (renderer.events as SharedFlow<RendererEvent>)
                .onSubscription { subscribed.complete(Unit) }
                .collect { events.send(it) }
        }
        subscribed.await()
        return events to job
    }

    @Test
    fun `a converter that tone maps makes the renderer say so exactly once`() = runBlocking {
        val ledger = LeakLedger()
        val renderer = AppKitVideoRenderer(
            convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
            toneMapped = { true },
            enqueueOnMain = { block -> block() },
            showImage = { },
        )
        coroutineScope {
            val (events, collector) = eventsOf(renderer)
            try {
                val hdr = ColorSpaceInfo(transfer = ColorTransfer.Pq, primaries = ColorPrimaries.Bt2020)
                repeat(3) { index ->
                    renderer.present(FakeVideoFrame(Pts(index.toLong()), colorSpace = hdr, ledger = ledger), 0L)
                }
                val first = withTimeout(10_000) { events.receive() }
                val announced = first as? RendererEvent.ToneMapEngaged
                assertNotNull(announced, "the first event must be the tone map, was $first")
                assertEquals("Pq", announced.transfer, "the SOURCE transfer travels with the event")
                assertEquals(-1, announced.streamIndex, "a renderer is handed frames, not streams")
                // Two more frames were presented and neither may speak. The engine latches too,
                // but a renderer shouting per frame makes its own logs unreadable.
                assertNull(
                    withTimeoutOrNull(500) { events.receive() },
                    "once per renderer, not once per frame",
                )
            } finally {
                collector.cancel()
                renderer.close()
            }
        }
    }

    @Test
    fun `a converter that does not tone map stays silent even on an HDR frame`() = runBlocking {
        val ledger = LeakLedger()
        val renderer = AppKitVideoRenderer(
            convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
            enqueueOnMain = { block -> block() },
            showImage = { },
        )
        coroutineScope {
            val (events, collector) = eventsOf(renderer)
            try {
                val hdr = ColorSpaceInfo(transfer = ColorTransfer.Pq, primaries = ColorPrimaries.Bt2020)
                renderer.present(FakeVideoFrame(Pts(0), colorSpace = hdr, ledger = ledger), 0L)
                assertNull(
                    withTimeoutOrNull(500) { events.receive() },
                    "HDR metadata is not the trigger; only a renderer that ROLLED IT OFF may speak",
                )
            } finally {
                collector.cancel()
                renderer.close()
            }
        }
    }

    private class DrawnPixels(val width: Int, val height: Int, private val rgba: ByteArray) {
        /** Row zero is the top row, which is the order the renderer's own converter writes. */
        fun redAndGreenAt(x: Int, y: Int): Pair<Int, Int> {
            val at = (y * width + x) * 4
            return (rgba[at].toInt() and 0xFF) to (rgba[at + 1].toInt() and 0xFF)
        }
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
        override val rotationDegrees: Int = 0,
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
