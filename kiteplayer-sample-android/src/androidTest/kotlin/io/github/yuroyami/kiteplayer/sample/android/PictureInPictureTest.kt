package io.github.yuroyami.kiteplayer.sample.android

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.view.KitePlayerView
import io.github.yuroyami.kiteplayer.view.enterPictureInPicture
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Picture in picture from the direct view's own parameters (#20), with the upright 16:9 clip the
 * sample bundles. Whether the transition starts from the video area is a look, not something the
 * test can read.
 */
@RunWith(AndroidJUnit4::class)
class PictureInPictureTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun theViewsParametersOpenAWindowWithThePicturesAspect() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (activity, view) = activityAndView(scenario)
            play(view)
            var entered = false
            instrumentation.runOnMainSync { entered = view.enterPictureInPicture(activity) }
            assertTrue("the OS refused picture in picture", entered)
            await("the activity in picture in picture") { onMain { activity.isInPictureInPictureMode } }
            await("a window at the clip's 16:9") { aspectOf(activity) in 1.72..1.84 }
        }
    }

    @Test
    fun leavingTheAppWhileItPlaysOpensTheWindowByItself() {
        assumeTrue("auto-enter needs Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (activity, view) = activityAndView(scenario)
            play(view)
            // Auto-enter comes from the parameters the view keeps current as the play state moves.
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            await("picture in picture after going home") { onMain { activity.isInPictureInPictureMode } }
        }
    }

    private fun activityAndView(scenario: ActivityScenario<MainActivity>): Pair<Activity, KitePlayerView> {
        lateinit var activity: Activity
        lateinit var view: KitePlayerView
        scenario.onActivity {
            activity = it
            view = it.findViewById(R.id.player_view)
        }
        return activity to view
    }

    /** Waits for the bundled clip to open, plays it, and waits until it plays. */
    private fun play(view: KitePlayerView) {
        await("the clip opened") { onMain { view.player?.state?.value?.status } == PlaybackStatus.Paused }
        instrumentation.runOnMainSync { view.player?.play() }
        await("the clip playing") { onMain { view.player?.state?.value?.status } == PlaybackStatus.Playing }
    }

    private fun aspectOf(activity: Activity): Double {
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            width = activity.window.decorView.width
            height = activity.window.decorView.height
        }
        return if (height == 0) 0.0 else width.toDouble() / height
    }

    private fun <T> onMain(read: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun await(what: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 40_000L
        const val POLL_MILLIS = 200L
    }
}
