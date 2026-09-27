package io.github.yuroyami.kiteplayer.sample.android

import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.view.KitePlayerView
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the direct view shows, read from screenshots the instrumentation takes: [KitePlayerView.secure]
 * keeps the video out of them (#19), and a paused picture comes back on a new surface (#300). The
 * instrumentation cannot capture a secure layer. What a person sees on the glass is not something
 * a screenshot can check.
 */
@RunWith(AndroidJUnit4::class)
class ViewScreenshotTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aSecureViewIsBlackInAScreenshotAndClearingItShowsThePictureAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: KitePlayerView
            scenario.onActivity { view = it.findViewById(R.id.player_view) }

            // The activity opens the bundled clip paused, so its first frame stays on the view.
            awaitShare(view, "the first frame in the screenshot") { it > PICTURE_SHARE }

            // The control: a secure window is black in these screenshots, which proves they cannot
            // capture a secure layer. Without it, a flag that does nothing and a screenshot that
            // sees secure content would look the same.
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            awaitShare(view, "a black video area in the screenshot of a secure window") { it < BLACK_SHARE }
            scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            awaitShare(view, "the picture back after the window stops being secure") { it > PICTURE_SHARE }

            instrumentation.runOnMainSync { view.secure = true }
            awaitShare(view, "a black video area in the screenshot of a secure view") { it < BLACK_SHARE }

            instrumentation.runOnMainSync { view.secure = false }
            awaitShare(view, "the picture back in the screenshot after secure is cleared") { it > PICTURE_SHARE }
        }
    }

    @Test
    fun aPausedPictureComesBackWhenTheActivityReturnsFromTheBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: KitePlayerView
            scenario.onActivity { view = it.findViewById(R.id.player_view) }
            awaitShare(view, "the first frame in the screenshot") { it > PICTURE_SHARE }

            // Stopped, the activity's window goes and the view loses its surface; resumed, a new one
            // comes. Five times, as step 4 of the device run sheet asks.
            repeat(5) { round ->
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitShare(view, "the paused picture back after return ${round + 1}") { it > PICTURE_SHARE }
            }
        }
    }

    /** Takes screenshots until [accept] takes the bright share of the picture's area, or fails. */
    private fun awaitShare(view: View, what: String, accept: (Double) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        var last = -1.0
        while (SystemClock.uptimeMillis() < deadline) {
            last = pictureBrightShare(instrumentation, view)
            if (accept(last)) return
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("timed out waiting for $what: the bright share stayed at $last")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 40_000L
        const val POLL_MILLIS = 250L
    }
}
