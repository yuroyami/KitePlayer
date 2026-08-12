package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S1.c.5 step 8, on the named emulator: a real SurfaceView hosted only by [RendererTestActivity],
 * one asymmetric red-left/blue-right frame observed through PixelCopy with black letterbox, the
 * same frame at 90 degrees with the axes swapped, then Surface destruction with the next present
 * refusing plus one SurfaceLost, without touching audio. The renderer is closed before the
 * Activity gives the Surface back, which is the caller contract the KDoc states.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSurfaceVideoRendererDeviceTest {

    private class DeviceFrame(
        width: Int,
        height: Int,
        override val rotationDegrees: Int,
    ) : VideoFrame {
        override val pts: Pts = Pts(0)
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(width, height)
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Rgba
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.Unspecified
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation(0)
        override fun close() = Unit
    }

    /** Left half red, right half blue, tightly packed RGBA in the frame's own orientation. */
    private fun redLeftBlueRight(frame: VideoFrame): ByteArray {
        val w = frame.size.width
        val h = frame.size.height
        val out = ByteArray(w * h * 4)
        var at = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x < w / 2) {
                out[at] = -1 /* 0xFF red */
            } else {
                out[at + 2] = -1 /* 0xFF blue */
            }
            out[at + 3] = -1
            at += 4
        }
        return out
    }

    private fun pixelCopy(view: android.view.SurfaceView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(view, bitmap, { copyResult ->
            result = copyResult
            done.countDown()
        }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS), "PixelCopy must complete")
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun assertNear(expected: Int, actual: Int, what: String) {
        /* The software canvas filters when scaling; centre samples stay saturated but not exact. */
        assertTrue(
            abs(Color.red(expected) - Color.red(actual)) <= 24 &&
                abs(Color.green(expected) - Color.green(actual)) <= 24 &&
                abs(Color.blue(expected) - Color.blue(actual)) <= 24,
            "$what: expected ~${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
        )
    }

    @Test
    fun drawsAsymmetricFrameRotatesAndSurvivesSurfaceLoss(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(RendererTestActivity::class.java)
        try {
            var activity: RendererTestActivity? = null
            scenario.onActivity { activity = it }
            val host = activity!!
            assertTrue(host.surfaceCreated.await(10, TimeUnit.SECONDS), "the Surface must be created")

            val renderer = AndroidSurfaceVideoRenderer(
                surface = host.surfaceView.holder.surface,
                convert = ::redLeftBlueRight,
            )
            /* A 2:1 frame on a portrait-ish canvas letterboxes top and bottom. */
            assertTrue(renderer.present(DeviceFrame(320, 160, rotationDegrees = 0), 0))
            val started = System.nanoTime()
            while (renderer.presentedFrames < 1 && System.nanoTime() - started < 5_000_000_000L) {
                Thread.sleep(10)
            }
            assertEquals(1L, renderer.presentedFrames)
            Thread.sleep(100) /* one composition */

            val view = host.surfaceView
            val flat = pixelCopy(view)
            val cx = view.width / 2
            val cy = view.height / 2
            val quarter = view.width / 4
            assertNear(Color.RED, flat.getPixel(cx - quarter, cy), "left half is red")
            assertNear(Color.BLUE, flat.getPixel(cx + quarter, cy), "right half is blue")
            assertNear(Color.BLACK, flat.getPixel(cx, 2), "the letterbox above is black")
            assertNear(Color.BLACK, flat.getPixel(cx, view.height - 3), "the letterbox below is black")

            /* The same picture turned 90 degrees clockwise: red moves to the TOP half. */
            assertTrue(renderer.present(DeviceFrame(320, 160, rotationDegrees = 90), 0))
            while (renderer.presentedFrames < 2 && System.nanoTime() - started < 10_000_000_000L) {
                Thread.sleep(10)
            }
            assertEquals(2L, renderer.presentedFrames)
            Thread.sleep(100)
            val turned = pixelCopy(view)
            val quarterY = view.height / 4
            assertNear(Color.RED, turned.getPixel(cx, cy - quarterY), "after 90 degrees red is on top")
            assertNear(Color.BLUE, turned.getPixel(cx, cy + quarterY), "after 90 degrees blue is below")

            /* Destroy the Surface out from under the renderer: refusal plus one SurfaceLost. */
            scenario.onActivity { it.setContentView(android.view.View(it)) }
            assertTrue(host.surfaceDestroyed.await(10, TimeUnit.SECONDS), "the Surface must be destroyed")
            assertFalse(renderer.present(DeviceFrame(320, 160, rotationDegrees = 0), 0))
            withTimeout(5_000) {
                renderer.events.filterIsInstance<RendererEvent.SurfaceLost>().first()
            }
            /* Close the renderer before the Activity finishes releasing everything: the caller
             * contract, honored by the test exactly as an application must. */
            renderer.close()
        } finally {
            scenario.close()
        }
    }
}
