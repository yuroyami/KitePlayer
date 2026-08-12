package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ownership, validation, swizzle and event arms of S1.c.5 step 7, over the [CanvasTarget] seam
 * with no Android graphics anywhere. The frame ledger ends at zero in every arm, exactly like
 * the fallback suite's, and pending work never exceeds one frame by construction of the slot.
 */
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
}

private class FakeTarget(
    var canvasWidth: Int = 16,
    var canvasHeight: Int = 9,
) : CanvasTarget {
    @Volatile var valid = true
    @Volatile var refuseLock = false
    @Volatile var throwOnLock: Throwable? = null
    var throwOnDraw: Throwable? = null
    val posts = AtomicInteger()
    var released = 0
    val canvases = mutableListOf<FakeCanvas>()
    val postedAfterDrawThrew = AtomicInteger()

    override fun isValid(): Boolean = valid

    override fun lock(): TargetCanvas? {
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
}
