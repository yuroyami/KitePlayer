package io.github.yuroyami.kiteplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop renderer's last step, which had no test at all.
 *
 * On the JVM the pure-Compose path is not the experimental sibling it is on Android and iOS, it is
 * the ONLY path that draws: `KitePlayerSurface` on this target is an empty box, because there is no
 * desktop equivalent of a SurfaceView to hand a native renderer. Synkplay's desktop build ships one
 * engine now and it renders through here, so this seam carries the whole picture.
 *
 * Decoding is proven elsewhere (`DesktopPlaybackTest` opens a real device and plays). What this
 * suite covers is the hand-off nothing else touches: RGBA bytes to a Skia raster to a Compose
 * `ImageBitmap`, at the exact sizes and strides the renderer uses.
 */
class DesktopFrameImageTest {

    private fun rgba(width: Int, height: Int, tint: Int): ByteArray =
        ByteArray(width * height * 4) { i ->
            when (i % 4) {
                0 -> tint.toByte()
                1 -> (255 - tint).toByte()
                2 -> ((i / 4) % 256).toByte()
                else -> 0xFF.toByte()
            }
        }

    @Test
    fun `a frame becomes an image bitmap of exactly the frame's size`() {
        val pool = FrameImagePool()
        try {
            val image = pool.imageFor(rgba(320, 180, 64), 320, 180)
            assertEquals(320, image.image.width)
            assertEquals(180, image.image.height)
        } finally {
            pool.release()
        }
    }

    @Test
    fun `a full 1080p frame survives the raster, which is the size that actually plays`() {
        val pool = FrameImagePool()
        try {
            val image = pool.imageFor(rgba(1920, 1080, 200), 1920, 1080)
            assertEquals(1920, image.image.width)
            assertEquals(1080, image.image.height)
        } finally {
            pool.release()
        }
    }

    @Test
    fun `each frame is its own image, because Skia copies at construction`() {
        // The jvm pool documents that there is nothing to reuse: Skia takes the bytes when the
        // image is made. If that ever changed to a shared buffer, two frames would alias and the
        // picture would show the newest frame everywhere at once.
        val pool = FrameImagePool()
        try {
            val first = pool.imageFor(rgba(64, 64, 10), 64, 64)
            val second = pool.imageFor(rgba(64, 64, 250), 64, 64)
            assertTrue(first.image !== second.image, "two frames must not share one bitmap")
        } finally {
            pool.release()
        }
    }

    @Test
    fun `a subtitle overlay rasterises at its own size, independently of the video frame`() {
        val overlay = overlayImageBitmap(rgba(200, 40, 128), 200, 40)
        assertEquals(200, overlay.width)
        assertEquals(40, overlay.height)
    }

    @Test
    fun `releasing an empty pool is safe, which is the teardown path after a failed open`() {
        FrameImagePool().release()
    }
}
