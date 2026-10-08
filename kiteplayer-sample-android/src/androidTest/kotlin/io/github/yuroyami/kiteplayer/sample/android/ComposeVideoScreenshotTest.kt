package io.github.yuroyami.kiteplayer.sample.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The paused picture of Compose-drawn video comes back when the activity returns from the
 * background, as the direct view's does (#300). The Compose screen gives the video no view to
 * locate, so the check looks for the test clip's strongly saturated colours anywhere on screen,
 * which the rest of the screen does not have.
 */
@RunWith(AndroidJUnit4::class)
class ComposeVideoScreenshotTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aPausedComposePictureComesBackWhenTheActivityReturnsFromTheBackground() {
        ActivityScenario.launch(ComposeVideoActivity::class.java).use { scenario ->
            // The activity opens the bundled clip paused, so its first frame stays on screen.
            awaitVivid("the first frame on screen")
            repeat(3) { round ->
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitVivid("the paused picture back after return ${round + 1}")
            }
        }
    }

    private fun awaitVivid(what: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        var last = -1.0
        while (SystemClock.uptimeMillis() < deadline) {
            last = vividShare()
            if (last > VIVID_SHARE) return
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("timed out waiting for $what: the vivid share of the screen stayed at $last")
    }

    /** The share of a grid over the whole screenshot whose colour spans more than [VIVID_SPAN]. */
    private fun vividShare(): Double {
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return 0.0
        try {
            var vivid = 0
            for (row in 0 until GRID) {
                for (column in 0 until GRID) {
                    val pixel = shot.getPixel(shot.width * column / GRID, shot.height * row / GRID)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (maxOf(r, g, b) - minOf(r, g, b) > VIVID_SPAN) vivid++
                }
            }
            return vivid.toDouble() / (GRID * GRID)
        } finally {
            shot.recycle()
        }
    }

    private companion object {
        const val GRID = 48
        /**
         * The default controls lie over a paused picture and darken it by 40 percent, so a colour
         * that spanned 150 spans 90 there. The screen's own colours span less, but for one line of
         * light blue text, which covers far less than [VIVID_SHARE] of the grid.
         */
        const val VIVID_SPAN = 85
        const val VIVID_SHARE = 0.03
        const val TIMEOUT_MILLIS = 40_000L
        const val POLL_MILLIS = 250L
    }
}
