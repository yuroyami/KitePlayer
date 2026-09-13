package io.github.yuroyami.kiteplayer.output

import android.graphics.SurfaceTexture
import android.view.Surface
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ownership, validation, swizzle and event arms of the surface renderer, over the [CanvasTarget] seam
 * with no Android graphics anywhere. The frame ledger ends at zero in every arm, exactly like
 * the fallback suite's, and pending work never exceeds one frame by construction of the slot.
 */

class AndroidSurfaceOverlayTest {

    @Test
    fun anOverlayCompositesAboveThePictureThroughTheFrameTransform() {
        val target = FakeTarget(canvasWidth = 200, canvasHeight = 100)
        val renderer = AndroidSurfaceVideoRenderer(
            convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
            target = target,
        )
        try {
            kotlinx.coroutines.runBlocking {
                renderer.setOverlay(
                    io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
                        images = listOf(
                            io.github.yuroyami.kiteplayer.spi.OverlayImage(
                                x = 10,
                                y = 20,
                                bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(4, 2, ByteArray(4 * 2 * 4)),
                            ),
                        ),
                        viewportWidth = 100,
                        viewportHeight = 50,
                        contentHash = 7L,
                    ),
                )
                // A 100x50 frame fits the 200x100 canvas exactly at 2x scale.
                renderer.present(TestFrame(width = 100, height = 50), 0L)
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (target.canvases.lastOrNull()?.drawnOverlays?.isEmpty() != false) {
                    check(System.nanoTime() < deadline) { "no overlay was composited" }
                    Thread.sleep(2)
                }
            }
            val draw = target.canvases.last().drawnOverlays.single()
            assertEquals(20f, draw.left, "x scales by the frame transform")
            assertEquals(40f, draw.top)
            assertEquals(8f, draw.drawWidth)
            assertEquals(4f, draw.drawHeight)
            assertEquals(7L, draw.contentHash)
        } finally {
            renderer.close()
        }
    }

    // Overlay coordinates map into the PRE-turn draw rectangle and turn with the
    // picture; unrotated they sat on the post-turn rectangle with the wrong scale on both axes.
    @Test
    fun aRotatedFrameCarriesItsOverlayThroughTheSameTurn() {
        val target = FakeTarget(canvasWidth = 90, canvasHeight = 160)
        val renderer = AndroidSurfaceVideoRenderer(
            convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
            target = target,
        )
        try {
            kotlinx.coroutines.runBlocking {
                renderer.setOverlay(
                    io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
                        images = listOf(
                            io.github.yuroyami.kiteplayer.spi.OverlayImage(
                                x = 150,
                                y = 80,
                                bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(10, 10, ByteArray(10 * 10 * 4)),
                            ),
                        ),
                        viewportWidth = 160,
                        viewportHeight = 90,
                        contentHash = 9L,
                    ),
                )
                /* A 160x90 frame turned 90 degrees fills the 90x160 canvas exactly. */
                renderer.present(TestFrame(width = 160, height = 90, rotationDegrees = 90), 0L)
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (target.canvases.lastOrNull()?.drawnOverlays?.isEmpty() != false) {
                    check(System.nanoTime() < deadline) { "no overlay was composited" }
                    Thread.sleep(2)
                }
            }
            val canvas = target.canvases.last()
            val draw = canvas.drawnOverlays.single()
            /* Pre-turn rect: drawLeft = -35, drawTop = 35, at 1:1 scale in unrotated space. */
            assertEquals(115f, draw.left, "x maps into the pre-turn rectangle at unrotated scale")
            assertEquals(115f, draw.top)
            assertEquals(10f, draw.drawWidth, "a quarter turn must not squash the overlay")
            assertEquals(10f, draw.drawHeight)
            assertEquals(90, canvas.drawnOverlayLayouts.single().rotationDegrees, "the turn rides the draw")
        } finally {
            renderer.close()
        }
    }
}

private class TestFrame(
    width: Int = 4,
    height: Int = 2,
    override val rotationDegrees: Int = 0,
    parNum: Int = 1,
    parDen: Int = 1,
    val onClose: (TestFrame) -> Unit = {},
) : VideoFrame {
    override val pts: Pts = Pts(0)
    override val duration: Pts? = null
    override val size: VideoSize = VideoSize(width, height, parNum, parDen)
    override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Rgba
    override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.Unspecified
    override val hardwareSurface: HwSurfaceKind? = null
    override val generation: Generation = Generation(0)

    var closes = 0
        private set

    override fun close() {
        closes += 1
        onClose(this)
    }
}

/** A canvas the test scripts: validity, lock refusals, throwing locks, draws and posts. */
private class FakeCanvas(override val width: Int, override val height: Int) : TargetCanvas {
    var cleared = 0
    val drawnPictures = mutableListOf<IntArray>()
    val drawnLayouts = mutableListOf<FrameLayout>()
    var throwOnDraw: Throwable? = null

    override fun clearToBlack() {
        cleared += 1
    }

    override fun drawFrame(argb: IntArray, sourceWidth: Int, sourceHeight: Int, layout: FrameLayout) {
        throwOnDraw?.let { throw it }
        drawnPictures += argb.copyOf()
        drawnLayouts += layout
    }

    data class OverlayDraw(
        val width: Int,
        val height: Int,
        val left: Float,
        val top: Float,
        val drawWidth: Float,
        val drawHeight: Float,
        val contentHash: Long,
    )

    val drawnOverlays = mutableListOf<OverlayDraw>()

    override fun drawOverlayImage(
        rgba: ByteArray,
        width: Int,
        height: Int,
        left: Float,
        top: Float,
        drawWidth: Float,
        drawHeight: Float,
        contentHash: Long,
        imageIndex: Int,
        layout: FrameLayout,
    ) {
        drawnOverlays += OverlayDraw(width, height, left, top, drawWidth, drawHeight, contentHash)
        drawnOverlayLayouts += layout
    }

    val drawnOverlayLayouts = mutableListOf<FrameLayout>()
}

private class FakeTarget(
    var canvasWidth: Int = 16,
    var canvasHeight: Int = 9,
) : CanvasTarget {
    @Volatile var valid = true
    @Volatile var refuseLock = false
    @Volatile var throwOnLock: Throwable? = null
    /** Runs on the drawing thread at the start of every lock, so a test can hold it there. */
    @Volatile var onLock: (() -> Unit)? = null
    var throwOnDraw: Throwable? = null
    val posts = AtomicInteger()
    var released = 0
    val canvases = mutableListOf<FakeCanvas>()
    val postedAfterDrawThrew = AtomicInteger()

    /** The colour matrix this target was last told to draw video through. */
    @Volatile var drawnColorMatrix: FloatArray? = null

    override fun isValid(): Boolean = valid

    override fun setVideoColorMatrix(matrix: FloatArray?) {
        drawnColorMatrix = matrix
    }

    override fun lock(): TargetCanvas? {
        onLock?.invoke()
        throwOnLock?.let { throw it }
        if (refuseLock) return null
        val canvas = FakeCanvas(canvasWidth, canvasHeight)
        canvas.throwOnDraw = throwOnDraw
        synchronized(canvases) { canvases += canvas }
        return canvas
    }

    override fun post(canvas: TargetCanvas) {
        posts.incrementAndGet()
        if ((canvas as FakeCanvas).throwOnDraw != null) postedAfterDrawThrew.incrementAndGet()
    }

    override fun release() {
        released += 1
    }
}

/** RGBA bytes for a size, tightly packed, red-left/blue-right style split at the midline. */
private fun rgbaBytes(width: Int, height: Int, fill: (x: Int) -> Triple<Int, Int, Int>): ByteArray {
    val out = ByteArray(width * height * 4)
    var at = 0
    for (y in 0 until height) for (x in 0 until width) {
        val (r, g, b) = fill(x)
        out[at] = r.toByte(); out[at + 1] = g.toByte(); out[at + 2] = b.toByte(); out[at + 3] = -1
        at += 4
    }
    return out
}

private fun exactConverter(): (VideoFrame) -> ByteArray = { frame ->
    rgbaBytes(frame.size.width, frame.size.height) { Triple(0x10, 0x20, 0x30) }
}

private fun renderer(target: FakeTarget, convert: (VideoFrame) -> ByteArray = exactConverter()) =
    AndroidSurfaceVideoRenderer(convert = convert, target = target)

private fun awaitPresented(r: AndroidSurfaceVideoRenderer, atLeast: Long, timeoutMs: Long = 5_000) {
    val startedAt = System.nanoTime()
    while (r.presentedFrames < atLeast) {
        if (System.nanoTime() - startedAt > timeoutMs * 1_000_000L) {
            throw AssertionError("presented=${r.presentedFrames}, wanted $atLeast")
        }
        Thread.sleep(1)
    }
}

class AndroidSurfaceVideoRendererTest {

    @Test
    fun `newest of one hundred wins with ninety nine exact closes`() = runBlocking {
        val target = FakeTarget()
        /* A converter that blocks until every present() has landed, so the slot swap is what
         * decides who gets drawn, deterministically. */
        val gate = CountDownLatch(1)
        val frames = (1..100).map { TestFrame() }
        val r = AndroidSurfaceVideoRenderer(
            convert = { frame ->
                gate.await(5, TimeUnit.SECONDS)
                exactConverter()(frame)
            },
            target = target,
        )
        frames.forEach { assertTrue(r.present(it, 0)) }
        gate.countDown()
        awaitPresented(r, 1)
        r.close()
        val totalCloses = frames.sumOf { it.closes }
        assertEquals(100, totalCloses, "every frame closed exactly once")
        assertTrue(frames.all { it.closes == 1 })
        /* One or two frames can be drawn depending on when the worker takes the slot; everyone
         * else was superseded. The ledger is exact either way. */
        assertEquals(100, (r.presentedFrames + r.supersededFrames + r.failedFrames).toInt())
        assertTrue(r.supersededFrames >= 98, "the queue never builds; newest wins")
    }

    @Test
    fun `a throwing converter counts the frame failed and does not kill the worker`() = runBlocking {
        val target = FakeTarget()
        var first = true
        val r = AndroidSurfaceVideoRenderer(
            convert = { frame ->
                if (first) { first = false; throw IllegalStateException("boom") }
                exactConverter()(frame)
            },
            target = target,
        )
        val bad = TestFrame()
        val good = TestFrame()
        assertTrue(r.present(bad, 0))
        withTimeout(5_000) { r.events.filterIsInstance<RendererEvent.Failed>().first() }
        assertTrue(r.present(good, 0))
        awaitPresented(r, 1)
        r.close()
        assertEquals(1, bad.closes)
        assertEquals(1, good.closes)
        assertEquals(1L, r.failedFrames)
        assertEquals(1L, r.presentedFrames)
    }

    @Test
    fun `short and oversized converter results are typed failures, never a partial draw`() = runBlocking {
        val target = FakeTarget()
        var calls = 0
        val r = AndroidSurfaceVideoRenderer(
            convert = { frame ->
                calls += 1
                when (calls) {
                    1 -> ByteArray(7) /* short */
                    2 -> ByteArray(frame.size.width * frame.size.height * 4 + 1) /* oversized */
                    else -> exactConverter()(frame)
                }
            },
            target = target,
        )
        val short = TestFrame()
        val long = TestFrame()
        assertTrue(r.present(short, 0))
        withTimeout(5_000) { r.events.filterIsInstance<RendererEvent.Failed>().first() }
        assertTrue(r.present(long, 0))
        withTimeout(5_000) { r.events.filterIsInstance<RendererEvent.Failed>().take(2).toList() }
        r.close()
        assertEquals(2L, r.failedFrames)
        assertEquals(0L, r.presentedFrames, "no partial picture is ever drawn")
        assertEquals(0, synchronized(target.canvases) { target.canvases.sumOf { it.drawnPictures.size } })
        assertEquals(1, short.closes)
        assertEquals(1, long.closes)
    }

    @Test
    fun `red stays red and blue stays blue through the swizzle`() = runBlocking {
        val target = FakeTarget(canvasWidth = 4, canvasHeight = 2)
        val r = AndroidSurfaceVideoRenderer(
            convert = { frame ->
                rgbaBytes(frame.size.width, frame.size.height) { x ->
                    if (x < frame.size.width / 2) Triple(0xFF, 0, 0) else Triple(0, 0, 0xFF)
                }
            },
            target = target,
        )
        assertTrue(r.present(TestFrame(width = 4, height = 2), 0))
        awaitPresented(r, 1)
        r.close()
        val picture = synchronized(target.canvases) { target.canvases.flatMap { it.drawnPictures } }.first()
        val red = picture[0]
        val blue = picture[3]
        assertEquals(0xFFFF0000.toInt(), red, "left half is red in ARGB")
        assertEquals(0xFF0000FF.toInt(), blue, "right half is blue in ARGB")
    }

    @Test
    fun `an invalid surface refuses the frame and reports one lost transition`() = runBlocking {
        val target = FakeTarget()
        target.valid = false
        val r = renderer(target)
        val a = TestFrame()
        val b = TestFrame()
        assertFalse(r.present(a, 0))
        assertFalse(r.present(b, 0))
        val lost = withTimeout(5_000) { r.events.filterIsInstance<RendererEvent.SurfaceLost>().first() }
        assertTrue(lost.detail.isNotEmpty())
        r.close()
        assertEquals(1, a.closes)
        assertEquals(1, b.closes)
        assertEquals(2L, r.failedFrames)
        /* One transition, two refusals: the replayed feed carries exactly one SurfaceLost. */
        assertEquals(1, r.events.let { flow -> runBlocking { flow.take(1).toList() } }.size)
    }

    @Test
    fun `a lock exception is a loss and the first later post says available`() = runBlocking {
        val target = FakeTarget()
        target.throwOnLock = IllegalStateException("surface died")
        val r = renderer(target)
        assertTrue(r.present(TestFrame(), 0))
        withTimeout(5_000) { r.events.filterIsInstance<RendererEvent.SurfaceLost>().first() }
        target.throwOnLock = null
        assertTrue(r.present(TestFrame(), 0))
        withTimeout(5_000) { r.events.filterIsInstance<RendererEvent.SurfaceAvailable>().first() }
        awaitPresented(r, 1)
        r.close()
    }

    @Test
    fun `a draw exception still reaches the post`() = runBlocking {
        val target = FakeTarget()
        target.throwOnDraw = IllegalStateException("draw blew up")
        val r = renderer(target)
        assertTrue(r.present(TestFrame(), 0))
        withTimeout(5_000) { r.events.filterIsInstance<RendererEvent.Failed>().first() }
        r.close()
        assertEquals(1, target.postedAfterDrawThrew.get(), "a successful lock always reaches unlockCanvasAndPost")
        assertEquals(1L, r.failedFrames)
    }

    @Test
    fun `close before the worker takes the slot closes the stranded frame exactly once`() = runBlocking {
        val target = FakeTarget()
        val gate = CountDownLatch(1)
        val r = AndroidSurfaceVideoRenderer(
            convert = { frame -> gate.await(5, TimeUnit.SECONDS); exactConverter()(frame) },
            target = target,
        )
        val first = TestFrame()
        val stranded = TestFrame()
        assertTrue(r.present(first, 0))
        Thread.sleep(20) /* let the worker take the first and block in the converter */
        assertTrue(r.present(stranded, 0))
        gate.countDown()
        r.close()
        assertEquals(1, first.closes)
        assertEquals(1, stranded.closes)
    }

    @Test
    fun `present racing close is owned by exactly one path`() = runBlocking {
        repeat(50) {
            val target = FakeTarget()
            val r = renderer(target)
            val frame = TestFrame()
            val racer = Thread { r.close() }
            racer.start()
            r.present(frame, 0)
            racer.join()
            assertEquals(1, frame.closes, "the racing frame is closed exactly once, by one owner")
        }
    }

    @Test
    fun `double close is a no-op and releases target storage once`() = runBlocking {
        val target = FakeTarget()
        val r = renderer(target)
        assertTrue(r.present(TestFrame(), 0))
        awaitPresented(r, 1)
        r.close()
        r.close()
        assertEquals(1, target.released)
    }

    /** The display's interval is fed by the view; 120 Hz must answer as nanoseconds. */
    @Test
    fun theFedRefreshRateAnswersAsAnInterval() {
        val renderer = AndroidSurfaceVideoRenderer(
            convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
            target = FakeTarget(canvasWidth = 64, canvasHeight = 64),
        )
        assertEquals(null, renderer.vsyncIntervalNanos(), "nothing fed yet: honestly unknown")
        renderer.setDisplayRefreshRate(120f)
        assertEquals(8_333_333L, renderer.vsyncIntervalNanos())
        renderer.setDisplayRefreshRate(0f)
        assertEquals(null, renderer.vsyncIntervalNanos(), "a detached view feeds zero, which is unknown")
    }
}

/** A Surface the host stubs report as live. Its other methods are never reached here. */
private fun liveSurface(): Surface = object : Surface(null as SurfaceTexture?) {
    override fun isValid(): Boolean = true
}

/**
 * The Surface callbacks run on Android's main thread, so [AndroidSurfaceVideoRenderer.setSurface]
 * must never wait for the drawing thread to get around to it. A phone once sat 5 seconds in
 * `surfaceChanged` waiting for exactly that, and Android reported the app as not responding.
 */
class AndroidSurfaceSetSurfaceTest {

    private class Rig(convert: (VideoFrame) -> ByteArray = exactConverter()) {
        val codec = MediaCodecSurfaceTarget()
        val screen = FakeTarget()
        val renderer = AndroidSurfaceVideoRenderer(
            convert = convert,
            target = SwitchingSurfaceCanvasTarget(codec) { screen },
            codecTarget = codec,
        )
    }

    /** Calls setSurface on its own thread and says whether it came back within [waitMs]. */
    private fun setSurfaceReturns(renderer: AndroidSurfaceVideoRenderer, surface: Surface?, waitMs: Long): Boolean {
        val returned = CountDownLatch(1)
        thread(name = "fake-main-thread") {
            renderer.setSurface(surface)
            returned.countDown()
        }
        return returned.await(waitMs, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `a surface change returns while the drawing thread is busy`() {
        val drawing = CountDownLatch(1)
        val finishDrawing = CountDownLatch(1)
        val rig = Rig(convert = { frame ->
            drawing.countDown()
            finishDrawing.await(10, TimeUnit.SECONDS)
            exactConverter()(frame)
        })
        val surface = liveSurface()
        try {
            rig.renderer.setSurface(surface)
            assertTrue(runBlocking { rig.renderer.present(TestFrame(), 0) })
            assertTrue(drawing.await(5, TimeUnit.SECONDS), "the drawing thread never took the frame")
            // surfaceChanged hands over the same Surface object with a new size.
            assertTrue(
                setSurfaceReturns(rig.renderer, surface, waitMs = 2_000),
                "setSurface waited for a frame that was still being drawn",
            )
        } finally {
            finishDrawing.countDown()
            rig.renderer.close()
        }
    }

    @Test
    fun `a surface change does not wait for a canvas being locked`() {
        val locking = CountDownLatch(1)
        val finishLock = CountDownLatch(1)
        val rig = Rig()
        rig.screen.onLock = {
            locking.countDown()
            finishLock.await(10, TimeUnit.SECONDS)
        }
        val surface = liveSurface()
        try {
            rig.renderer.setSurface(surface)
            assertTrue(runBlocking { rig.renderer.present(TestFrame(), 0) })
            assertTrue(locking.await(5, TimeUnit.SECONDS), "the drawing thread never locked a canvas")
            // Well under the one second a destroyed Surface is allowed to wait.
            assertTrue(
                setSurfaceReturns(rig.renderer, surface, waitMs = 500),
                "setSurface waited for a canvas that was still being locked",
            )
        } finally {
            finishLock.countDown()
            rig.renderer.close()
        }
    }

    @Test
    fun `a destroyed surface waits for the canvas already locked to be posted`() {
        val locking = CountDownLatch(1)
        val finishLock = CountDownLatch(1)
        val rig = Rig()
        rig.screen.onLock = {
            locking.countDown()
            finishLock.await(10, TimeUnit.SECONDS)
        }
        try {
            rig.renderer.setSurface(liveSurface())
            assertTrue(runBlocking { rig.renderer.present(TestFrame(), 0) })
            assertTrue(locking.await(5, TimeUnit.SECONDS), "the drawing thread never locked a canvas")
            val returned = CountDownLatch(1)
            val postsWhenReturned = AtomicInteger(-1)
            thread(name = "fake-main-thread") {
                rig.renderer.setSurface(null)
                postsWhenReturned.set(rig.screen.posts.get())
                returned.countDown()
            }
            assertFalse(returned.await(200, TimeUnit.MILLISECONDS), "surfaceDestroyed returned with a canvas still out")
            finishLock.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertEquals(1, postsWhenReturned.get(), "the canvas was posted before surfaceDestroyed returned")
        } finally {
            finishLock.countDown()
            rig.renderer.close()
        }
    }

    @Test
    fun `a destroyed surface stops waiting when the draw cannot finish`() {
        val locking = CountDownLatch(1)
        val finishLock = CountDownLatch(1)
        val rig = Rig()
        // A lock that only finishes when the main thread moves on, like a buffer the platform
        // cannot hand back until the main thread draws.
        rig.screen.onLock = {
            locking.countDown()
            finishLock.await(20, TimeUnit.SECONDS)
        }
        try {
            rig.renderer.setSurface(liveSurface())
            assertTrue(runBlocking { rig.renderer.present(TestFrame(), 0) })
            assertTrue(locking.await(5, TimeUnit.SECONDS), "the drawing thread never locked a canvas")
            assertTrue(
                setSurfaceReturns(rig.renderer, null, waitMs = 4_000),
                "surfaceDestroyed waited on a draw that needs the main thread",
            )
            val lost = runBlocking {
                withTimeout(5_000) { rig.renderer.events.filterIsInstance<RendererEvent.SurfaceLost>().first() }
            }
            assertTrue(lost.detail.isNotEmpty())
        } finally {
            finishLock.countDown()
            rig.renderer.close()
        }
    }
}

/** Brightness, contrast, saturation and hue must reach whichever Surface the view draws into. */
class AndroidSurfaceAdjustmentsTest {

    @Test
    fun `picture adjustments reach every surface the view draws into`() {
        val codec = MediaCodecSurfaceTarget()
        val screens = mutableListOf<FakeTarget>()
        val renderer = AndroidSurfaceVideoRenderer(
            convert = exactConverter(),
            target = SwitchingSurfaceCanvasTarget(codec) { FakeTarget().also { synchronized(screens) { screens += it } } },
            codecTarget = codec,
        )
        try {
            renderer.setAdjustments(VideoAdjustments(brightness = 0.2f))
            renderer.setSurface(liveSurface())
            assertTrue(runBlocking { renderer.present(TestFrame(), 0) })
            awaitPresented(renderer, 1)
            // A new Surface gets a new drawing target, which must be told too.
            renderer.setSurface(liveSurface())
            assertTrue(runBlocking { renderer.present(TestFrame(), 0) })
            awaitPresented(renderer, 2)
            val drawnWith = synchronized(screens) { screens.map { it.drawnColorMatrix } }
            assertEquals(2, drawnWith.size, "each Surface gets its own drawing target")
            drawnWith.forEach { assertNotNull(it, "the picture controls never reached the Surface") }

            renderer.setAdjustments(VideoAdjustments.Identity)
            assertTrue(runBlocking { renderer.present(TestFrame(), 0) })
            awaitPresented(renderer, 3)
            assertNull(synchronized(screens) { screens.last() }.drawnColorMatrix, "neutral controls clear the filter")
        } finally {
            renderer.close()
        }
    }
}
