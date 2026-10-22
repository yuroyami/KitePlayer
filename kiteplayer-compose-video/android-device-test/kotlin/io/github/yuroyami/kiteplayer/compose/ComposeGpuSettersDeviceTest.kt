package io.github.yuroyami.kiteplayer.compose

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.FlashGuard
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NeedsPushedMedia
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.RenderQuality
import io.github.yuroyami.kiteplayer.VideoScaler
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the engine sets on [KiteVideo]'s renderer reaches the GL blit under it, on the path that
 * draws MediaCodec pictures: the render quality (#560) and the flash guard (#500). Each test plays
 * `strobe-5hz.mp4`, a 640 by 360 black and white strobe, and looks at what came out.
 *
 * Runs on a phone. The flash guard half reads the screen while a clip plays in real time, which
 * the CI emulator's graphics host cannot be trusted with (#301).
 */
@NeedsPushedMedia
internal class ComposeGpuSettersDeviceTest {

    private class Played(
        /** The widest image the renderer handed to Compose. */
        val widestImage: Int,
        /** The red of the centre of the screen at each reading, with its time in milliseconds. */
        val reds: List<Pair<Long, Int>>,
        val endedAtMillis: Long,
    )

    /** Plays the strobe once through the GPU path, with [configure] applied before it opens. */
    private fun play(configure: (KitePlayer) -> Unit): Played {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "the Compose GPU path requires API 31, found API ${Build.VERSION.SDK_INT}"
        }
        val context = InstrumentationRegistry.getInstrumentation().context
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.resolve("testmedia/strobe-5hz.mp4"),
            context.filesDir.resolve("testmedia/strobe-5hz.mp4"),
        )
        val clip = checkNotNull(candidates.firstOrNull(File::isFile)) {
            "strobe-5hz.mp4 not found at ${candidates.joinToString()}. Push it first."
        }
        val copier = HandlerThread("pixel-copy").apply { start() }
        val scenario = ActivityScenario.launch(KiteVideoTestActivity::class.java)
        try {
            lateinit var state: KiteVideoState
            lateinit var window: Window
            lateinit var consumerReady: CountDownLatch
            scenario.onActivity { activity ->
                state = activity.videoState
                window = activity.window
                consumerReady = activity.composeConsumerReady
            }
            check(consumerReady.await(10, TimeUnit.SECONDS)) { "KiteVideo did not bind its Window consumer" }
            val player = KitePlayer.create(PlayerConfig(backends = mobileBackends(), hardwareDecode = HwdecPolicy.Require))
            try {
                return runBlocking {
                    player.attachRenderer(state.renderer)
                    configure(player)
                    player.open(MediaItem(clip.absolutePath))
                    player.play()
                    val startedAt = System.nanoTime()
                    val reds = ArrayList<Pair<Long, Int>>()
                    var widest = 0
                    val status = withTimeout(30_000) {
                        var now = player.state.value.status
                        while (now != PlaybackStatus.Ended && now != PlaybackStatus.Failed) {
                            widest = maxOf(widest, state.frame.value?.image?.width ?: 0)
                            centreRed(window, Handler(copier.looper))?.let { red ->
                                reds += (System.nanoTime() - startedAt) / 1_000_000 to red
                            }
                            delay(8)
                            now = player.state.value.status
                        }
                        now
                    }
                    assertEquals(PlaybackStatus.Ended, status, "the strobe failed: ${player.state.value.error}")
                    Played(widest, reds, (System.nanoTime() - startedAt) / 1_000_000)
                }
            } finally {
                runBlocking { withTimeout(15_000) { player.closeAndAwait() } }
                state.renderer.close()
            }
        } finally {
            scenario.close()
            copier.quitSafely()
        }
    }

    /** The red of the pixel at the centre of [window], or null when the screen could not be read. */
    private fun centreRed(window: Window, handler: Handler): Int? {
        val view = window.decorView
        if (view.width < 4 || view.height < 4) return null
        val x = view.width / 2
        val y = view.height / 2
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(window, Rect(x, y, x + 2, y + 2), bitmap, { result = it; done.countDown() }, handler)
        if (!done.await(2, TimeUnit.SECONDS) || result != PixelCopy.SUCCESS) return null
        return Color.red(bitmap.getPixel(0, 0))
    }

    /** The readings of the last two seconds, when a strobe's run has long started. */
    private fun Played.late(): List<Int> = reds.filter { it.first in (endedAtMillis - 2_300)..(endedAtMillis - 300) }.map { it.second }

    @Test
    fun theKernelReachesTheBlitWhichThenEnlargesThePicture() {
        val plain = play { }
        val kernel = play { it.setRenderQuality(RenderQuality(scaler = VideoScaler.CatmullRom)) }
        Log.i(TAG, "widest image: plain=${plain.widestImage} kernel=${kernel.widestImage}")
        assertEquals(640, plain.widestImage, "with no kernel the image has the size of the clip")
        assertTrue(kernel.widestImage > 640, "the kernel never reached the blit: the image is ${kernel.widestImage} wide")
    }

    @Test
    fun aStrobeIsDimmedOnTheScreenWithTheGuardOnAndWholeWithItOff() {
        val whole = play { it.setFlashGuard(FlashGuard.Off) }.late()
        val guarded = play { it.setFlashGuard(FlashGuard.On) }.late()
        Log.i(TAG, "late reds: whole=${whole.size} readings up to ${whole.maxOrNull()}, guarded=${guarded.size} up to ${guarded.maxOrNull()}")
        assertTrue(whole.size > 20 && guarded.size > 20, "too few readings: ${whole.size} and ${guarded.size}")
        assertTrue(whole.max() > 200, "the strobe's white reads ${whole.max()} with the guard off")
        // White is drawn at about a third once the run starts.
        assertTrue(guarded.max() in 40..130, "the strobe's white reads ${guarded.max()} with the guard on")
        assertTrue(guarded.min() < 20, "black is still black: ${guarded.min()}")
    }

    private companion object {
        const val TAG = "KiteComposeGpu"
    }
}
