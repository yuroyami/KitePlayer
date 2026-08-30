package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
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
    ) : VideoFrame {
        var closes = 0
            private set
        override val pts: io.github.yuroyami.kiteplayer.Pts = io.github.yuroyami.kiteplayer.Pts(0)
        override val duration: io.github.yuroyami.kiteplayer.Pts? = null
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo()
        override val hardwareSurface: io.github.yuroyami.kiteplayer.spi.HwSurfaceKind? = null
        override val generation: io.github.yuroyami.kiteplayer.Generation =
            io.github.yuroyami.kiteplayer.Generation(0)
        override fun close() {
            closes++
        }
    }

    private fun renderer(
        paint: (IntArray, Int, Int) -> Boolean = { _, _, _ -> true },
    ) = AwtCanvasVideoRenderer(
        painter = { _, destination, width, height -> paint(destination, width, height) },
    )

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
}
