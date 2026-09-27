package io.github.yuroyami.kiteplayer.sample.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.view.KitePlayerView
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [KitePlayerView.secure] keeps the video out of a screenshot, and clearing it brings the picture
 * back (#19). The screenshot comes from the instrumentation, which cannot capture a secure layer.
 * What a person sees on the glass is not something a screenshot can check.
 */
@RunWith(AndroidJUnit4::class)
class SecureViewScreenshotTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aSecureViewIsBlackInAScreenshotAndClearingItShowsThePictureAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: KitePlayerView
            scenario.onActivity { view = it.findViewById(R.id.player_view) }

            // The activity opens the bundled clip paused, so its first frame stays on the view.
            awaitShare(view, "the first frame in the screenshot") { it > PICTURE_SHARE }

            instrumentation.runOnMainSync { view.secure = true }
            awaitShare(view, "a black video area in the screenshot of a secure view") { it < BLACK_SHARE }

            instrumentation.runOnMainSync { view.secure = false }
            awaitShare(view, "the picture back in the screenshot after secure is cleared") { it > PICTURE_SHARE }
        }
    }

    /** Takes screenshots until [accept] takes the bright share of the picture's area, or fails. */
    private fun awaitShare(view: View, what: String, accept: (Double) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        var last = -1.0
        while (SystemClock.uptimeMillis() < deadline) {
            last = brightShare(view)
            if (accept(last)) return
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("timed out waiting for $what: the bright share stayed at $last")
    }

    /**
     * The share of sampled pixels brighter than near black, in the middle 60 percent of where the
     * 16:9 clip sits when it is fitted into the view. The performance overlay sits at the view's
     * top edge, outside that area.
     */
    private fun brightShare(view: View): Double {
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return 0.0
        try {
            val origin = IntArray(2)
            var width = 0
            var height = 0
            instrumentation.runOnMainSync {
                view.getLocationOnScreen(origin)
                width = view.width
                height = view.height
            }
            val fitsWidth = width * 9 <= height * 16
            val pictureWidth = if (fitsWidth) width else height * 16 / 9
            val pictureHeight = if (fitsWidth) width * 9 / 16 else height
            val left = origin[0] + (width - pictureWidth) / 2 + pictureWidth / 5
            val top = origin[1] + (height - pictureHeight) / 2 + pictureHeight / 5
            var bright = 0
            var sampled = 0
            for (row in 0 until GRID) {
                for (column in 0 until GRID) {
                    val x = left + pictureWidth * 3 / 5 * column / (GRID - 1)
                    val y = top + pictureHeight * 3 / 5 * row / (GRID - 1)
                    if (x !in 0 until shot.width || y !in 0 until shot.height) continue
                    val pixel = shot.getPixel(x, y)
                    val level = maxOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
                    if (level > NEAR_BLACK) bright++
                    sampled++
                }
            }
            return if (sampled == 0) 0.0 else bright.toDouble() / sampled
        } finally {
            shot.recycle()
        }
    }

    private companion object {
        const val GRID = 16
        const val NEAR_BLACK = 40
        const val PICTURE_SHARE = 0.5
        const val BLACK_SHARE = 0.02
        const val TIMEOUT_MILLIS = 40_000L
        const val POLL_MILLIS = 250L
    }
}
