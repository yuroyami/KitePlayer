package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ownership, validation and publish arms of the KiteVideo renderer (S1.d.3 step 6), with
 * the image seam faked so no real bitmap exists anywhere. The frame ledger ends closed-exactly-
 * once in every arm, like its three sibling suites.
 */
private class TestFrame(
    width: Int = 4,
    height: Int = 2,
    override val rotationDegrees: Int = 0,
) : VideoFrame {
    override val pts: Pts = Pts(0)
    override val duration: Pts? = null
    override val size: VideoSize = VideoSize(width, height)
    override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Rgba
    override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.Unspecified
    override val hardwareSurface: HwSurfaceKind? = null
    override val generation: Generation = Generation(0)

    @Volatile var closes = 0
        private set

    override fun close() {
        closes += 1
    }
}

private class FakeImage(override val width: Int, override val height: Int) : ImageBitmap {
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha: Boolean = false
    override fun prepareToDraw() = Unit
    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = error("the fake image holds no pixels")
}

private class Harness(
    val convert: (VideoFrame) -> ByteArray = { frame ->
        ByteArray(frame.size.width * frame.size.height * 4)
    },
    val makeImage: (ByteArray, Int, Int) -> ImageBitmap = { _, w, h -> FakeImage(w, h) },
) {
    val published = CopyOnWriteArrayList<KiteVideoFrame?>()
    val publishThreads = CopyOnWriteArrayList<String>()
    val renderer = KiteVideoRenderer(
        convert = convert,
        makeImage = makeImage,
        publish = { frame ->
            published += frame
            publishThreads += Thread.currentThread().name
        },
    )

    fun awaitPublished(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (published.size < count) {
            check(System.nanoTime() < deadline) { "nothing published within the deadline" }
            Thread.sleep(2)
        }
    }

    fun awaitFailed(count: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (renderer.failedFrames < count) {
            check(System.nanoTime() < deadline) { "no failure counted within the deadline" }
            Thread.sleep(2)
        }
    }
}

class KiteVideoRendererTest {

    @Test
    fun aPresentedFrameIsConvertedPublishedAndClosedOnce() = runBlocking {
        val h = Harness()
        val frame = TestFrame(width = 6, height = 4, rotationDegrees = 90)
        assertTrue(h.renderer.present(frame, 0L))
        h.awaitPublished(1)
        val out = h.published.single()!!
        assertEquals(6, out.image.width)
        assertEquals(4, out.image.height)
        assertEquals(VideoSize(6, 4), out.size)
        assertEquals(90, out.rotationDegrees)
        assertEquals(1, frame.closes)
        assertEquals(1L, h.renderer.presentedFrames)
        assertEquals(0L, h.renderer.failedFrames)
        h.renderer.close()
    }

    @Test
    fun theNewestFrameWinsAndTheDisplacedOneIsClosedAndCounted() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val h = Harness(convert = { frame ->
            entered.countDown()
            check(gate.await(10, TimeUnit.SECONDS)) { "the gate never opened" }
            ByteArray(frame.size.width * frame.size.height * 4)
        })
        val plug = TestFrame()
        val displaced = TestFrame()
        val kept = TestFrame()

        assertTrue(h.renderer.present(plug, 0L))
        check(entered.await(10, TimeUnit.SECONDS)) { "the worker never started" }
        // The worker is inside convert(plug); these two race nothing.
        assertTrue(h.renderer.present(displaced, 0L))
        assertTrue(h.renderer.present(kept, 0L))
        assertEquals(1, displaced.closes)
        assertEquals(1L, h.renderer.supersededFrames)
        gate.countDown()

        h.awaitPublished(2)
        assertEquals(1, plug.closes)
        assertEquals(1, kept.closes)
        assertEquals(2L, h.renderer.presentedFrames)
        h.renderer.close()
    }

    @Test
    fun aThrowingConverterCountsTheFrameAndTheWorkerSurvives() = runBlocking {
        var first = true
        val h = Harness(convert = { frame ->
            if (first) {
                first = false
                error("deliberate conversion failure")
            }
            ByteArray(frame.size.width * frame.size.height * 4)
        })
        val bad = TestFrame()
        assertTrue(h.renderer.present(bad, 0L))
        h.awaitFailed(1)
        assertEquals(1, bad.closes)

        val good = TestFrame()
        assertTrue(h.renderer.present(good, 0L))
        h.awaitPublished(1)
        assertEquals(1L, h.renderer.presentedFrames)
        assertEquals(1L, h.renderer.failedFrames)
        h.renderer.close()
    }

    @Test
    fun aShortConversionIsRefusedNeverPublished() = runBlocking {
        val h = Harness(convert = { ByteArray(3) })
        val frame = TestFrame(width = 4, height = 2)
        assertTrue(h.renderer.present(frame, 0L))
        h.awaitFailed(1)
        assertEquals(1, frame.closes)
        assertEquals(0L, h.renderer.presentedFrames)
        h.renderer.close()
        // Only the close-time null was ever published.
        assertEquals(listOf(null), h.published.toList())
    }

    @Test
    fun aFailedImageBuildCountsTheFrame() = runBlocking {
        val h = Harness(makeImage = { _, _, _ -> error("deliberate image failure") })
        val frame = TestFrame()
        assertTrue(h.renderer.present(frame, 0L))
        h.awaitFailed(1)
        assertEquals(1, frame.closes)
        assertEquals(0L, h.renderer.presentedFrames)
        h.renderer.close()
    }

    @Test
    fun publishRunsOnTheRendererWorkerNeverTheCaller() = runBlocking {
        val h = Harness()
        assertTrue(h.renderer.present(TestFrame(), 0L))
        h.awaitPublished(1)
        val publisher = h.publishThreads.first()
        assertTrue(publisher.contains("kitevideo"), "published from $publisher")
        h.renderer.close()
    }

    @Test
    fun closePublishesNullRefusesLateFramesAndIsIdempotent() = runBlocking {
        val h = Harness()
        assertTrue(h.renderer.present(TestFrame(), 0L))
        h.awaitPublished(1)
        h.renderer.close()
        assertNull(h.published.last())

        val late = TestFrame()
        assertFalse(h.renderer.present(late, 0L))
        assertEquals(1, late.closes)
        assertEquals(1L, h.renderer.failedFrames)

        h.renderer.close()
        assertEquals(1, h.published.count { it == null })
    }

    @Test
    fun aDegenerateFrameSizeIsRefused() = runBlocking {
        val h = Harness()
        val frame = TestFrame(width = 0, height = 2)
        assertTrue(h.renderer.present(frame, 0L))
        h.awaitFailed(1)
        assertEquals(1, frame.closes)
        assertEquals(0L, h.renderer.presentedFrames)
        h.renderer.close()
    }
}
