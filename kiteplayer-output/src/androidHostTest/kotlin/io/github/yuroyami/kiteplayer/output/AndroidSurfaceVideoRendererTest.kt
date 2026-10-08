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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Ownership, validation, swizzle and event arms of the surface renderer, over the [CanvasTarget] seam
 * with no Android graphics anywhere. The frame ledger ends at zero in every arm, exactly like
 * the fallback suite's, and pending work never exceeds one frame by construction of the slot.
 */

/**
 * The placement contract on the Android surface renderer, through a canvas that records every draw.
 * The canvas seam takes an overlay image's rectangle in canvas pixels and never turns it, so the
 * recorded rectangle is where the image lands.
 */
class AndroidSurfaceOverlayPlacementTest : OverlayPlacementContractTest() {

    override fun reportedOutput(scene: Scene): io.github.yuroyami.kiteplayer.VideoSize? {
        val target = FakeTarget(canvasWidth = scene.outputWidth, canvasHeight = scene.outputHeight)
        val renderer = renderer(target, blankConverter())
        return try {
            runBlocking { renderer.present(sceneFrame(scene), 0L) }
            awaitPresented(renderer, 1)
            renderer.outputSize
        } finally {
            renderer.close()
        }
    }

    override fun compose(scene: Scene, overlay: io.github.yuroyami.kiteplayer.spi.SubtitleOverlay): Composite {
        val target = FakeTarget(canvasWidth = scene.outputWidth, canvasHeight = scene.outputHeight)
        val renderer = renderer(target, blankConverter())
        try {
            renderer.setScaleMode(scene.scale)
            renderer.setTransform(scene.transform)
            runBlocking {
                renderer.setOverlay(overlay)
                renderer.present(sceneFrame(scene), 0L)
            }
            // Presented means posted, so every draw on that canvas has finished.
            awaitPresented(renderer, 1)
            val canvas = synchronized(target.canvases) { target.canvases.last() }
            val boxes = overlay.images.indices.map { index ->
                canvas.drawnOverlays.firstOrNull { it.imageIndex == index }?.let { draw ->
                    Box(draw.left, draw.top, draw.drawWidth, draw.drawHeight)
                }
            }
            return Composite(canvas.width, canvas.height, boxes)
        } finally {
            renderer.close()
        }
    }

    private fun sceneFrame(scene: Scene) = TestFrame(
        width = scene.picture.width,
        height = scene.picture.height,
        rotationDegrees = scene.rotationDegrees,
        parNum = scene.picture.pixelAspectNumerator,
        parDen = scene.picture.pixelAspectDenominator,
    )

    private fun blankConverter(): (VideoFrame) -> ByteArray = { frame -> ByteArray(frame.size.width * frame.size.height * 4) }
}

/**
 * The other Android path: the codec owns the video Surface, so a separate layer draws the
 * subtitles. That layer is the overlay's output, and only its host knows its size.
 */
class AndroidSurfaceSubtitleLayerTest {

    @Test
    fun aSeparateSubtitleLayerIsSizedByItsHostAndNotByTheVideoCanvas() {
        val handed = java.util.Collections.synchronizedList(mutableListOf<io.github.yuroyami.kiteplayer.spi.SubtitleOverlay?>())
        val target = FakeTarget(canvasWidth = 1920, canvasHeight = 1080)
        val renderer = AndroidSurfaceVideoRenderer(
            convert = exactConverter(),
            target = target,
            overlayConsumer = { handed += it },
        )
        try {
            assertNull(renderer.outputSize, "no host has said how large the subtitle layer is")
            renderer.setViewport(540, 1200, 2f)
            assertEquals(io.github.yuroyami.kiteplayer.VideoSize(1080, 2400), renderer.outputSize)

            val overlay = io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
                images = listOf(
                    io.github.yuroyami.kiteplayer.spi.OverlayImage(
                        x = 0,
                        y = 2360,
                        bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(4, 4, ByteArray(4 * 4 * 4)),
                    ),
                ),
                viewportWidth = 1080,
                viewportHeight = 2400,
                contentHash = 3L,
            )
            runBlocking {
                renderer.setOverlay(overlay)
                renderer.present(TestFrame(width = 1920, height = 1080), 0L)
            }
            awaitPresented(renderer, 1)
            assertEquals(
                io.github.yuroyami.kiteplayer.VideoSize(1080, 2400),
                renderer.outputSize,
                "drawing into the video canvas must not change the size of the subtitle layer",
            )
            assertEquals(listOf<io.github.yuroyami.kiteplayer.spi.SubtitleOverlay?>(overlay), handed.toList())
            assertTrue(
                target.canvases.all { it.drawnOverlays.isEmpty() },
                "an overlay handed to the layer must not be drawn into the video as well",
            )
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
    override val mirrored: Boolean = false,
    override val crop: io.github.yuroyami.kiteplayer.PictureCrop? = null,
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

    /** One overlay image as the renderer asked for it: its destination rectangle in canvas pixels. */
    data class OverlayDraw(
        val imageIndex: Int,
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
    ) {
        drawnOverlays += OverlayDraw(imageIndex, left, top, drawWidth, drawHeight, contentHash)
    }
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

    /** What the variant choice hears about HDR (#447): only a direct path, on an HDR display, under Auto. */
    @Test
    fun `the variant choice hears HDR only from a direct path on an HDR display under Auto`() {
        val software = AndroidSurfaceVideoRenderer(convert = exactConverter(), target = FakeTarget())
        val direct = AndroidSurfaceVideoRenderer(convert = exactConverter(), target = FakeTarget(), codecTarget = MediaCodecSurfaceTarget())
        try {
            software.setDisplayHdr(intArrayOf(android.view.Display.HdrCapabilities.HDR_TYPE_HDR10), 4f)
            assertFalse(software.showsHdr, "the software path tone maps every frame")
            assertFalse(direct.showsHdr, "a display nobody described is standard range")
            direct.setDisplayHdr(IntArray(0), 1f)
            assertFalse(direct.showsHdr)
            direct.setDisplayHdr(intArrayOf(android.view.Display.HdrCapabilities.HDR_TYPE_HLG), 1f)
            assertTrue(direct.showsHdr)
            direct.setHdrPolicy(io.github.yuroyami.kiteplayer.HdrPolicy.ToneMap)
            assertFalse(direct.showsHdr, "a renderer told to tone map said it shows HDR")
        } finally {
            software.close()
            direct.close()
        }
    }

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
    fun `a mirrored frame reaches the canvas as a mirrored layout and a plain one does not`() = runBlocking {
        val target = FakeTarget()
        val r = AndroidSurfaceVideoRenderer(convert = exactConverter(), target = target)
        try {
            assertTrue(r.present(TestFrame(rotationDegrees = 90, mirrored = true), 0))
            awaitPresented(r, 1)
            assertTrue(r.present(TestFrame(), 0))
            awaitPresented(r, 2)
        } finally {
            r.close()
        }
        val layouts = synchronized(target.canvases) { target.canvases.flatMap { it.drawnLayouts } }
        assertEquals(listOf(90 to true, 0 to false), layouts.map { it.rotationDegrees to it.mirrored })
    }

    @Test
    fun `a cropped frame is drawn from the part its crop leaves and the view hears that shape`() = runBlocking {
        val target = FakeTarget(canvasWidth = 16, canvasHeight = 9)
        val heard = mutableListOf<Triple<VideoSize, Int, io.github.yuroyami.kiteplayer.PictureCrop?>>()
        val r = AndroidSurfaceVideoRenderer(
            convert = exactConverter(),
            target = target,
            geometryConsumer = { size, turn, crop -> synchronized(heard) { heard += Triple(size, turn, crop) } },
        )
        try {
            // 16 by 10 stored with one padding row at the bottom: 16 by 9, which fills the canvas.
            assertTrue(r.present(TestFrame(width = 16, height = 10, crop = io.github.yuroyami.kiteplayer.PictureCrop(bottom = 1)), 0))
            awaitPresented(r, 1)
        } finally {
            r.close()
        }
        val layout = synchronized(target.canvases) { target.canvases.flatMap { it.drawnLayouts } }.single()
        assertEquals(listOf(0, 0, 16, 9), listOf(layout.left, layout.top, layout.right, layout.bottom))
        assertEquals(listOf(0, 0, 16, 9), listOf(layout.sourceLeft, layout.sourceTop, layout.sourceRight, layout.sourceBottom))
        // The renderer cuts the crop itself, so the view must not cut it again.
        assertEquals(listOf(Triple(VideoSize(16, 9), 0, null)), synchronized(heard) { heard.toList() })
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
            target = SwitchingSurfaceCanvasTarget(codec, createDelegate = { screen }),
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
            target = SwitchingSurfaceCanvasTarget(codec, createDelegate = { FakeTarget().also { synchronized(screens) { screens += it } } }),
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

    @Test
    fun `a converter that tone maps makes the renderer say so`() = runBlocking {
        val target = FakeTarget()
        val renderer = AndroidSurfaceVideoRenderer(
            convert = exactConverter(),
            target = target,
            toneMapped = { true },
        )
        try {
            assertTrue(renderer.present(TestFrame(), 0))
            awaitPresented(renderer, 1)
            // The flow replays its last events, so this collector still hears it.
            val announced = withTimeout(5_000) { renderer.events.filterIsInstance<RendererEvent.ToneMapEngaged>().first() }
            assertEquals(ColorSpaceInfo.Unspecified.transfer.name, announced.transfer)
        } finally {
            renderer.close()
        }
    }
}

/**
 * The engine says no picture plays (#530), and the renderer takes the last one off: the frames it
 * still holds are let go unseen, and the Surface shows black with the cues over it, or, where a
 * decoder may take the Surface again, is blanked through EGL so the decoder still can.
 */
class AndroidSurfacePictureClearTest {

    private fun cue(hash: Long) = io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
        images = listOf(
            io.github.yuroyami.kiteplayer.spi.OverlayImage(
                x = 2,
                y = 6,
                bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(4, 2, ByteArray(4 * 2 * 4)),
            ),
        ),
        viewportWidth = 16,
        viewportHeight = 9,
        contentHash = hash,
    )

    private fun awaitPosts(target: FakeTarget, atLeast: Int, timeoutMs: Long = 5_000) {
        val startedAt = System.nanoTime()
        while (target.posts.get() < atLeast) {
            if (System.nanoTime() - startedAt > timeoutMs * 1_000_000L) {
                throw AssertionError("posts=${target.posts.get()}, wanted $atLeast")
            }
            Thread.sleep(1)
        }
    }

    private fun canvases(target: FakeTarget): List<FakeCanvas> = synchronized(target.canvases) { target.canvases.toList() }

    @Test
    fun aClearLetsGoOfEveryFrameItHoldsAndDrawsNoneOfThem() {
        val target = FakeTarget()
        val converting = CountDownLatch(1)
        val finishConverting = CountDownLatch(1)
        val renderer = AndroidSurfaceVideoRenderer(
            convert = { frame ->
                converting.countDown()
                finishConverting.await(10, TimeUnit.SECONDS)
                exactConverter()(frame)
            },
            target = target,
        )
        val beingConverted = TestFrame()
        val waiting = TestFrame()
        try {
            assertTrue(runBlocking { renderer.present(beingConverted, 0) })
            assertTrue(converting.await(5, TimeUnit.SECONDS), "the drawing thread never took the frame")
            assertTrue(runBlocking { renderer.present(waiting, 0) })

            renderer.clearPicture()
            assertEquals(1, waiting.closes, "the waiting frame was let go at the clear")
            finishConverting.countDown()
            awaitPosts(target, 1)
            assertEquals(1, beingConverted.closes)
            assertEquals(0L, renderer.presentedFrames, "the frame converted across the clear was not drawn")
            assertEquals(2L, renderer.supersededFrames)
            val blank = canvases(target).single()
            assertEquals(1, blank.cleared)
            assertTrue(blank.drawnPictures.isEmpty())
        } finally {
            finishConverting.countDown()
            renderer.close()
        }
    }

    @Test
    fun aClearedSurfaceShowsItsCuesUntilThePictureComesBack() {
        val target = FakeTarget()
        val renderer = renderer(target)
        try {
            runBlocking {
                renderer.setOverlay(cue(hash = 1))
                renderer.present(TestFrame(), 0)
            }
            awaitPresented(renderer, 1)
            val drawn = target.posts.get()

            renderer.clearPicture()
            awaitPosts(target, drawn + 1)
            val blank = canvases(target).last()
            assertEquals(1, blank.cleared)
            assertTrue(blank.drawnPictures.isEmpty(), "black, not the last picture")
            assertEquals(listOf(1L), blank.drawnOverlays.map { it.contentHash }, "the cue stays over the black")

            runBlocking { renderer.setOverlay(cue(hash = 2)) }
            awaitPosts(target, drawn + 2)
            assertEquals(listOf(2L), canvases(target).last().drawnOverlays.map { it.contentHash }, "a new cue shows at once")
            runBlocking { renderer.setOverlay(null) }
            awaitPosts(target, drawn + 3)
            assertTrue(canvases(target).last().drawnOverlays.isEmpty(), "and a cue that ends goes at once")

            runBlocking { renderer.present(TestFrame(), 0) }
            awaitPresented(renderer, 2)
            val withPicture = target.posts.get()
            runBlocking { renderer.setOverlay(cue(hash = 3)) }
            awaitPosts(target, withPicture + 1)
            val again = canvases(target).last()
            assertEquals(1, again.drawnPictures.size, "once a picture is back, a cue draws over it and not over black")
            assertEquals(listOf(3L), again.drawnOverlays.map { it.contentHash })
        } finally {
            renderer.close()
        }
    }

    @Test
    fun aSurfaceADecoderMayTakeAgainIsBlankedWithoutACanvas() {
        val codec = MediaCodecSurfaceTarget()
        val screen = FakeTarget()
        val blanked = java.util.concurrent.LinkedBlockingQueue<Surface>()
        val renderer = AndroidSurfaceVideoRenderer(
            convert = exactConverter(),
            target = SwitchingSurfaceCanvasTarget(codec, createDelegate = { screen }, blankSurface = { blanked += it; true }),
            codecTarget = codec,
            overlayConsumer = {},
        )
        val surface = liveSurface()
        try {
            renderer.setSurface(surface)
            renderer.clearPicture()
            assertTrue(blanked.poll(5, TimeUnit.SECONDS) === surface, "the Surface was blanked through EGL")
            Thread.sleep(100)
            assertEquals(0, screen.posts.get(), "no canvas took the Surface from the next decoder")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun aSurfaceThatEglCannotTakeIsBlankedWithACanvas() {
        val codec = MediaCodecSurfaceTarget()
        val screen = FakeTarget()
        val renderer = AndroidSurfaceVideoRenderer(
            convert = exactConverter(),
            target = SwitchingSurfaceCanvasTarget(codec, createDelegate = { screen }, blankSurface = { false }),
            codecTarget = codec,
            overlayConsumer = {},
        )
        try {
            renderer.setSurface(liveSurface())
            renderer.clearPicture()
            awaitPosts(screen, 1)
            val blank = canvases(screen).single()
            assertEquals(1, blank.cleared)
            assertTrue(blank.drawnPictures.isEmpty())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun aClearAfterCloseDoesNothing() {
        val target = FakeTarget()
        val renderer = renderer(target)
        renderer.close()
        renderer.clearPicture()
        assertEquals(0, target.posts.get())
    }
}

/**
 * A software picture held for seconds, as cover art or a slideshow is, takes a change of its look at
 * once (#541): the renderer keeps its pixels and draws them again, playing or not, so the engine has
 * nothing to decode for it.
 */
class AndroidSurfaceHeldPictureTest {

    private fun cue(hash: Long) = io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
        images = listOf(
            io.github.yuroyami.kiteplayer.spi.OverlayImage(
                x = 2,
                y = 6,
                bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(4, 2, ByteArray(4 * 2 * 4)),
            ),
        ),
        viewportWidth = 16,
        viewportHeight = 9,
        contentHash = hash,
    )

    private fun awaitPosts(target: FakeTarget, atLeast: Int, timeoutMs: Long = 5_000) {
        val startedAt = System.nanoTime()
        while (target.posts.get() < atLeast) {
            if (System.nanoTime() - startedAt > timeoutMs * 1_000_000L) {
                throw AssertionError("posts=${target.posts.get()}, wanted $atLeast")
            }
            Thread.sleep(1)
        }
    }

    private fun canvases(target: FakeTarget): List<FakeCanvas> = synchronized(target.canvases) { target.canvases.toList() }

    private val stripes: (VideoFrame) -> ByteArray = { frame ->
        rgbaBytes(frame.size.width, frame.size.height) { x -> if (x % 2 == 0) Triple(0xFF, 0, 0) else Triple(0, 0, 0xFF) }
    }

    @Test
    fun aNewCueRedrawsTheHeldPictureWithItsOwnPixels() {
        val target = FakeTarget()
        val renderer = renderer(target, stripes)
        try {
            runBlocking { renderer.present(TestFrame(), 0) }
            awaitPresented(renderer, 1)
            val first = canvases(target).single()
            assertTrue(first.drawnOverlays.isEmpty())

            runBlocking { renderer.setOverlay(cue(hash = 7)) }
            awaitPosts(target, 2)
            val again = canvases(target).last()
            assertEquals(1, again.drawnPictures.size, "the held picture was not drawn under the new cue")
            assertTrue(first.drawnPictures.single().contentEquals(again.drawnPictures.single()), "the redraw drew other pixels")
            assertEquals(first.drawnLayouts.single(), again.drawnLayouts.single())
            assertEquals(listOf(7L), again.drawnOverlays.map { it.contentHash })
            assertEquals(1L, renderer.presentedFrames, "a redraw is not a frame")
            assertEquals(0L, renderer.failedFrames)

            runBlocking { renderer.setOverlay(null) }
            awaitPosts(target, 3)
            assertTrue(canvases(target).last().drawnOverlays.isEmpty(), "a cue that ends stayed on the held picture")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun aChangeOfScaleFramingOrAdjustmentsRedrawsTheHeldPicture() {
        val target = FakeTarget(canvasWidth = 16, canvasHeight = 16)
        val renderer = renderer(target, stripes)
        try {
            runBlocking { renderer.present(TestFrame(width = 4, height = 2), 0) }
            awaitPresented(renderer, 1)
            val fitted = canvases(target).single().drawnLayouts.single()

            renderer.setScaleMode(io.github.yuroyami.kiteplayer.VideoScale.Stretch)
            awaitPosts(target, 2)
            assertTrue(fitted != canvases(target).last().drawnLayouts.single(), "the new scale was not drawn")

            renderer.setTransform(io.github.yuroyami.kiteplayer.VideoTransform(zoom = 2f))
            awaitPosts(target, 3)

            renderer.setAdjustments(VideoAdjustments(brightness = 0.2f))
            awaitPosts(target, 4)
            assertNotNull(target.drawnColorMatrix, "the adjustments were not drawn")
            assertEquals(1L, renderer.presentedFrames)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun theSameScaleAgainDrawsNothing() {
        val target = FakeTarget()
        val renderer = renderer(target)
        try {
            runBlocking { renderer.present(TestFrame(), 0) }
            awaitPresented(renderer, 1)
            renderer.setScaleMode(io.github.yuroyami.kiteplayer.VideoScale.Fit)
            Thread.sleep(200)
            assertEquals(1, target.posts.get())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun nothingIsRedrawnBeforeTheFirstPicture() {
        val target = FakeTarget()
        val renderer = renderer(target)
        try {
            runBlocking { renderer.setOverlay(cue(hash = 1)) }
            renderer.setScaleMode(io.github.yuroyami.kiteplayer.VideoScale.Fill)
            Thread.sleep(200)
            assertEquals(0, target.posts.get())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun theRendererSaysItRedrawsOnlyWhileASoftwarePictureShows() {
        val software = renderer(FakeTarget())
        val codec = MediaCodecSurfaceTarget()
        val direct = AndroidSurfaceVideoRenderer(
            convert = exactConverter(),
            target = SwitchingSurfaceCanvasTarget(codec, createDelegate = { FakeTarget() }),
            codecTarget = codec,
            overlayConsumer = {},
        )
        try {
            assertTrue(software.redrawsHeldPicture, "a renderer that only draws software pictures keeps each one")
            assertFalse(direct.redrawsHeldPicture, "nothing has shown that the picture will be a software one")
            direct.setSurface(liveSurface())
            assertTrue(runBlocking { direct.present(TestFrame(), 0) })
            awaitPresented(direct, 1)
            assertTrue(direct.redrawsHeldPicture, "the software picture on screen is kept")
        } finally {
            software.close()
            direct.close()
        }
    }
}

/** The software path measures what it converts and dims a flashing run (#500). */
class AndroidSurfaceFlashGuardTest {

    /** Presents a black and white strobe, three pictures each at 30 a second, and answers each picture's colour matrix. */
    private fun matricesOfAStrobe(mode: io.github.yuroyami.kiteplayer.FlashGuard?, pictures: Int = 60): Pair<List<FloatArray?>, FakeTarget> = runBlocking {
        val target = FakeTarget()
        val shade = java.util.concurrent.atomic.AtomicInteger(0)
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val r = AndroidSurfaceVideoRenderer(
            convert = { frame -> rgbaBytes(frame.size.width, frame.size.height) { Triple(shade.get(), shade.get(), shade.get()) } },
            target = target,
            flashNanos = { clock.get() },
        )
        try {
            if (mode != null) r.setFlashGuard(mode)
            val matrices = (0 until pictures).map { index ->
                shade.set(if (index / 3 % 2 == 0) 0 else 255)
                clock.set(index * 1_000_000_000L / 30)
                assertTrue(r.present(TestFrame(), 0))
                awaitPresented(r, index + 1L)
                target.drawnColorMatrix
            }
            matrices to target
        } finally {
            r.close()
        }
    }

    @Test
    fun `a strobe is dimmed from the picture that makes its run`() {
        val (matrices, _) = matricesOfAStrobe(io.github.yuroyami.kiteplayer.FlashGuard.On)
        assertTrue(matrices.take(21).all { it == null }, "the strobe is drawn whole before its run")
        // The seventh leg is on picture 21, and from there every encoded value is drawn at about a third.
        matrices.drop(21).forEachIndexed { index, matrix ->
            val m = assertNotNull(matrix, "picture ${index + 21} was drawn whole")
            assertTrue(m[0] in 0.25f..0.40f && m[6] == m[0] && m[12] == m[0], "the factor was ${m[0]}")
            assertEquals(1f, m[18], "the alpha row is left alone")
        }
    }

    @Test
    fun `a strobe is drawn whole when the guard is off or only follows a system setting`() {
        assertTrue(matricesOfAStrobe(io.github.yuroyami.kiteplayer.FlashGuard.Off).first.all { it == null })
        // Android has no system setting to follow, so the default guards nothing.
        assertTrue(matricesOfAStrobe(mode = null).first.all { it == null })
        assertTrue(matricesOfAStrobe(io.github.yuroyami.kiteplayer.FlashGuard.FollowSystem).first.all { it == null })
    }

    @Test
    fun `the flash factor multiplies the picture controls and leaves a steady picture alone`() {
        assertNull(dimColorMatrix(null, 1f))
        val brighter = FloatArray(20).also { it[0] = 1f; it[6] = 1f; it[12] = 1f; it[18] = 1f; it[4] = 51f }
        assertSame(brighter, dimColorMatrix(brighter, 1f))
        val dimmed = assertNotNull(dimColorMatrix(brighter, 0.5f))
        assertEquals(0.5f, dimmed[0])
        assertEquals(25.5f, dimmed[4], "the offset is dimmed with the matrix")
        assertEquals(1f, dimmed[18])
        assertEquals(1f, brighter[0], "the renderer's own matrix is not written to")
        val alone = assertNotNull(dimColorMatrix(null, 0.5f))
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 1f), listOf(alone[0], alone[6], alone[12], alone[18]))

        assertNull(dimGlAdjust(null, 1f))
        val gl = assertNotNull(dimGlAdjust(null, 0.5f))
        assertEquals(listOf(0.5f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 1f), gl.take(13))
        assertEquals(0f, gl[14], "the gamma stays off")
        val packed = assertNotNull(GlState.packGlAdjust(io.github.yuroyami.kiteplayer.VideoAdjustments(brightness = 0.2f)))
        val both = assertNotNull(dimGlAdjust(packed, 0.5f))
        (0 until 12).forEach { assertEquals(packed[it] * 0.5f, both[it], "coefficient $it") }
        // A gamma alone leaves the matrix off, and the factor then switches it on as itself.
        val gamma = assertNotNull(GlState.packGlAdjust(io.github.yuroyami.kiteplayer.VideoAdjustments(gamma = 2f)))
        val gammaDimmed = assertNotNull(dimGlAdjust(gamma, 0.5f))
        assertEquals(listOf(0.5f, 1f, gamma[13], 1f), listOf(gammaDimmed[0], gammaDimmed[12], gammaDimmed[13], gammaDimmed[14]))
    }
}
