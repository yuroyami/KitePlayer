package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two things worth pinning here need no window, which is why they are pinned here rather than
 * in the demo: that every frame is closed exactly once on every path, and that the picture lands
 * where the shared geometry law says.
 *
 * A leak on a refusal path is 3.11 MB per 1080p frame and would be invisible until a long session
 * ran out of memory, so each refusal has its own case rather than being covered by one happy path.
 */
class AwtCanvasVideoRendererTest {

    /** Counts its own closes, so a double close is as visible as a missing one. */
    private class CountingFrame(
        override val size: VideoSize = VideoSize(64, 32),
        override val rotationDegrees: Int = 0,
        private val color: ColorSpaceInfo = ColorSpaceInfo(),
    ) : VideoFrame {
        var closes = 0
            private set
        override val pts: io.github.yuroyami.kiteplayer.Pts = io.github.yuroyami.kiteplayer.Pts(0)
        override val duration: io.github.yuroyami.kiteplayer.Pts? = null
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
        override val colorSpace: ColorSpaceInfo = color
        override val hardwareSurface: io.github.yuroyami.kiteplayer.spi.HwSurfaceKind? = null
        override val generation: io.github.yuroyami.kiteplayer.Generation =
            io.github.yuroyami.kiteplayer.Generation(0)
        override fun close() {
            closes++
        }
    }

    private fun renderer(
        toneMaps: Boolean = false,
        paint: (IntArray, Int, Int) -> Boolean = { _, _, _ -> true },
    ) = AwtCanvasVideoRenderer(
        painter = object : AwtFramePainter {
            override fun paintArgb(
                frame: VideoFrame,
                destination: IntArray,
                width: Int,
                height: Int,
            ): Boolean = paint(destination, width, height)

            override fun toneMapped(frame: VideoFrame): Boolean = toneMaps
        },
    )

    // ── KP-TONEMAP-WARN: the renderer that DID it is the one that says so ───────────────────

    @Test
    fun `a painter that tone maps makes the renderer say so, once`() = runTest {
        val r = renderer(toneMaps = true)
        r.setCanvas(java.awt.Canvas())
        val seen = mutableListOf<RendererEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.events.toList(seen) }
        val hdr = ColorSpaceInfo(transfer = ColorTransfer.Pq, primaries = ColorPrimaries.Bt2020)
        repeat(3) { r.present(CountingFrame(color = hdr), 0L) }
        collector.cancel()

        val announced = seen.filterIsInstance<RendererEvent.ToneMapEngaged>()
        assertEquals(1, announced.size, "once per renderer, not once per frame: got $announced")
        assertEquals("Pq", announced.single().transfer, "the SOURCE transfer travels with the event")
        assertEquals(-1, announced.single().streamIndex, "a renderer is handed frames, not streams")
    }

    @Test
    fun `a painter that does not tone map says nothing, HDR frame or not`() = runTest {
        val r = renderer(toneMaps = false)
        r.setCanvas(java.awt.Canvas())
        val seen = mutableListOf<RendererEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.events.toList(seen) }
        val hdr = ColorSpaceInfo(transfer = ColorTransfer.Pq, primaries = ColorPrimaries.Bt2020)
        r.present(CountingFrame(color = hdr), 0L)
        collector.cancel()
        assertTrue(
            seen.filterIsInstance<RendererEvent.ToneMapEngaged>().isEmpty(),
            "HDR metadata is not the trigger; only a renderer that ROLLED IT OFF may speak",
        )
    }

    @Test
    fun `a frame the painter refused announces nothing`() = runTest {
        val r = renderer(paint = { _, _, _ -> false }, toneMaps = true)
        r.setCanvas(java.awt.Canvas())
        val seen = mutableListOf<RendererEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.events.toList(seen) }
        val hdr = ColorSpaceInfo(transfer = ColorTransfer.Pq, primaries = ColorPrimaries.Bt2020)
        r.present(CountingFrame(color = hdr), 0L)
        collector.cancel()
        assertTrue(
            seen.filterIsInstance<RendererEvent.ToneMapEngaged>().isEmpty(),
            "nothing was shown, so nothing was tone mapped for the viewer",
        )
    }

    @Test
    fun `a frame presented with no canvas is closed exactly once and counted failed`() = runTest {
        val r = renderer()
        val frame = CountingFrame()
        assertFalse(r.present(frame, 0L))
        assertEquals(1, frame.closes)
        assertEquals(1L, r.failedFrames)
        assertEquals(0L, r.presentedFrames)
    }

    @Test
    fun `a zero sized frame is refused and closed rather than reaching the painter`() = runTest {
        var painterCalls = 0
        val r = renderer { _, _, _ -> painterCalls++; true }
        r.setCanvas(java.awt.Canvas())
        val frame = CountingFrame(size = VideoSize(0, 0))
        assertFalse(r.present(frame, 0L))
        assertEquals(1, frame.closes)
        assertEquals(0, painterCalls)
        assertEquals(1L, r.failedFrames)
    }

    @Test
    fun `a painter that declines the frame still closes it exactly once`() = runTest {
        val r = renderer { _, _, _ -> false }
        r.setCanvas(java.awt.Canvas())
        val frame = CountingFrame()
        assertFalse(r.present(frame, 0L))
        assertEquals(1, frame.closes)
        assertEquals(1L, r.failedFrames)
    }

    @Test
    fun `a painter that throws does not leak the frame`() = runTest {
        val r = renderer { _, _, _ -> throw IllegalStateException("converter blew up") }
        r.setCanvas(java.awt.Canvas())
        val frame = CountingFrame()
        assertFalse(r.present(frame, 0L))
        assertEquals(1, frame.closes)
        assertEquals(1L, r.failedFrames)
    }

    @Test
    fun `a closed renderer refuses and closes rather than painting`() = runTest {
        val r = renderer()
        r.setCanvas(java.awt.Canvas())
        r.close()
        val frame = CountingFrame()
        assertFalse(r.present(frame, 0L))
        assertEquals(1, frame.closes)
    }

    @Test
    fun `geometry is reported from the frame rather than from the canvas`() = runTest {
        var reported: Pair<VideoSize, Int>? = null
        val r = AwtCanvasVideoRenderer(
            painter = { _, _, _, _ -> true },
            onVideoGeometry = { size, rotation -> reported = size to rotation },
        )
        r.setCanvas(java.awt.Canvas())
        r.present(CountingFrame(size = VideoSize(1920, 1080), rotationDegrees = 90), 0L)
        assertEquals(VideoSize(1920, 1080) to 90, reported)
    }

    @Test
    fun `the picture is letterboxed by the shared geometry law, not stretched`() {
        // Composed into a plain image, because the arithmetic is the thing under test and a canvas
        // would only add a window to it.
        val canvasWidth = 400
        val canvasHeight = 400
        val target = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB)
        val picture = BufferedImage(160, 80, BufferedImage.TYPE_INT_RGB)
        val g2 = picture.createGraphics()
        g2.color = Color.WHITE
        g2.fillRect(0, 0, 160, 80)
        g2.dispose()

        val layout = frameLayout(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            size = VideoSize(160, 80),
            rotationDegrees = 0,
        )!!
        val g = target.createGraphics()
        AwtCanvasPresenter.compose(g, canvasWidth, canvasHeight, picture, layout, overlay = null)
        g.dispose()

        // A 2:1 picture in a square canvas fills the width and is centred vertically, so the top
        // and bottom rows are letterbox and the middle row is picture.
        assertEquals(Color.BLACK.rgb, target.getRGB(canvasWidth / 2, 2))
        assertEquals(Color.BLACK.rgb, target.getRGB(canvasWidth / 2, canvasHeight - 3))
        assertEquals(Color.WHITE.rgb, target.getRGB(canvasWidth / 2, canvasHeight / 2))
        assertTrue(layout.width == canvasWidth, "the wide axis should fill exactly: $layout")
    }

    @Test
    fun `the letterbox is repainted so a narrower picture cannot leave the old one showing`() {
        val target = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        val wide = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        wide.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 400, 200); dispose() }
        val narrow = BufferedImage(100, 400, BufferedImage.TYPE_INT_RGB)
        narrow.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 100, 400); dispose() }

        val g = target.createGraphics()
        AwtCanvasPresenter.compose(
            g, 400, 400, wide,
            frameLayout(400, 400, VideoSize(400, 200), 0)!!, null,
        )
        // Now a tall narrow picture: the wide one's pixels must not survive at the left edge.
        AwtCanvasPresenter.compose(
            g, 400, 400, narrow,
            frameLayout(400, 400, VideoSize(100, 400), 0)!!, null,
        )
        g.dispose()
        assertEquals(Color.BLACK.rgb, target.getRGB(5, 200))
        assertEquals(Color.WHITE.rgb, target.getRGB(200, 200))
    }

    // ── resize under playback ──────────────────────────────────────────────────────────────

    @Test
    fun `a canvas the size of its strategy is left alone and a resized one is rebuilt`() {
        // AWT never reports a stale BufferStrategy: it draws the new frame into the old buffers
        // and the picture is clipped or stretched until something rebuilds it.
        assertTrue(
            AwtCanvasPresenter.strategyIsStale(hasStrategy = false, 0, 0, 640, 360),
            "no strategy at all always needs one",
        )
        assertFalse(
            AwtCanvasPresenter.strategyIsStale(hasStrategy = true, 640, 360, 640, 360),
            "an unchanged canvas must not rebuild on every frame",
        )
        assertTrue(AwtCanvasPresenter.strategyIsStale(hasStrategy = true, 640, 360, 800, 360))
        assertTrue(AwtCanvasPresenter.strategyIsStale(hasStrategy = true, 640, 360, 640, 480))
    }

    @Test
    fun `the renderer listens to its canvas for resizes and lets go of it`() {
        // A resize while PAUSED has no frame arriving to trigger a repaint, so the renderer has
        // to hear about it. The other half matters more: a listener left behind keeps this
        // renderer alive for as long as the canvas the view already threw away.
        val r = renderer()
        val first = java.awt.Canvas()
        val second = java.awt.Canvas()

        r.setCanvas(first)
        assertEquals(1, first.componentListeners.size, "the renderer must hear its canvas resize")

        r.setCanvas(second)
        assertEquals(0, first.componentListeners.size, "and must let go of the previous one")
        assertEquals(1, second.componentListeners.size)

        r.close()
        assertEquals(0, second.componentListeners.size, "close releases the canvas entirely")
    }

    @Test
    fun `setting the same canvas twice does not stack listeners`() {
        val r = renderer()
        val canvas = java.awt.Canvas()
        r.setCanvas(canvas)
        r.setCanvas(canvas)
        assertEquals(1, canvas.componentListeners.size)
        r.close()
    }

    @Test
    fun `a resize repaints the retained picture rather than waiting for a frame`() {
        // Nothing is displayable here, so the paint itself stops at the peer check; what this
        // pins is that the resize REACHES the renderer at all, which is the wiring that was
        // missing. The paint path itself is covered by the compose arms above.
        val r = renderer()
        val canvas = java.awt.Canvas()
        r.setCanvas(canvas)
        canvas.setSize(320, 180)
        canvas.dispatchEvent(
            java.awt.event.ComponentEvent(canvas, java.awt.event.ComponentEvent.COMPONENT_RESIZED),
        )
        r.close()
    }
}
