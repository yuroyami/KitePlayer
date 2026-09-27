package io.github.yuroyami.kiteplayer.sample.android

import android.app.Instrumentation
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.view.KitePlayerView
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Step 3 of the device run sheet (#11): scrub hard, and the picture always comes back with nothing
 * that needs a pause to recover. The scrub is a dragged seek bar: a seek every 50 to 150 ms for
 * 30 seconds while the clip plays, mostly the coalescing kind and now and then a precise one.
 */
@RunWith(AndroidJUnit4::class)
class ScrubTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun afterAHardScrubTheClipPlaysOnWithItsPicture() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: KitePlayerView
            scenario.onActivity { view = it.findViewById(R.id.player_view) }
            val player = await("the clip opened") { onMain { view.player }?.takeIf { it.state.value.status == PlaybackStatus.Paused } }
            val durationMillis = checkNotNull(player.state.value.duration) { "the clip has no duration" }.inWholeMilliseconds
            instrumentation.runOnMainSync { player.play() }
            await("the clip playing") { player.takeIf { it.state.value.status == PlaybackStatus.Playing } }

            val random = Random(SEED)
            val scrubEnd = SystemClock.uptimeMillis() + SCRUB_MILLIS
            var seeks = 0
            while (SystemClock.uptimeMillis() < scrubEnd) {
                val target = random.nextLong(0, durationMillis - 1_000).milliseconds
                val mode = if (random.nextInt(8) == 0) SeekMode.Precise else SeekMode.KeyframeThenRefine
                player.seekLater(target, mode)
                seeks++
                SystemClock.sleep(random.nextLong(50, 150))
            }

            // Recovery without a pause: still playing, the position moving, the picture on screen.
            await("the clip playing after $seeks seeks") { player.takeIf { it.state.value.status == PlaybackStatus.Playing } }
            val before = player.position()
            SystemClock.sleep(1_000)
            val after = player.position()
            check(after > before || player.state.value.status == PlaybackStatus.Ended) {
                "the position stood at $before after the scrub: ${player.state.value}"
            }
            await("the picture after the scrub") { pictureBrightShare(instrumentation, view).takeIf { it > PICTURE_SHARE } }
            check(player.state.value.error == null) { "the scrub ended in ${player.state.value.error}" }
        }
    }

    private fun <T> onMain(read: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun <T : Any> await(what: String, read: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            read()?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        const val SEED = 20260927
        const val SCRUB_MILLIS = 30_000L
        const val TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 200L
    }
}
