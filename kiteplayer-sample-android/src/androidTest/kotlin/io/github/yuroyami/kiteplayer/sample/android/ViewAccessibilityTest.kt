package io.github.yuroyami.kiteplayer.sample.android

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.view.KitePlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a screen reader reads from the direct view (#307). The activity gives the view its player
 * before the open, when the state is "No media", and never tells the view anything again. So a
 * state that reaches "Paused" came from the view following the player by itself.
 */
@RunWith(AndroidJUnit4::class)
class ViewAccessibilityTest {

    @Test
    fun theViewSaysItIsTheVideoAndFollowsThePlayerByItself() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: KitePlayerView
            scenario.onActivity { view = it.findViewById(R.id.player_view) }

            // The activity opens the bundled clip paused.
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
            var heard: CharSequence? = null
            var label: CharSequence? = null
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    heard = view.stateDescription
                    label = view.contentDescription
                }
                if (heard?.startsWith("Paused, ") == true) break
                SystemClock.sleep(POLL_MILLIS)
            }
            assertEquals("Video", label?.toString())
            assertTrue("the state a screen reader hears: $heard", heard?.startsWith("Paused, 0:00 of ") == true)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 40_000L
        const val POLL_MILLIS = 100L
    }
}
