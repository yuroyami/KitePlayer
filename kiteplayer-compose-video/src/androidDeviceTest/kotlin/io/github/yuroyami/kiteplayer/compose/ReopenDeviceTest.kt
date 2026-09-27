package io.github.yuroyami.kiteplayer.compose

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NeedsPushedMedia
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Replacing the media on a live player, which is what loading a second file in a host app does.
 *
 * The scripted core harness already proves the stop/open ordering is correct, so what this adds is
 * the only part that harness cannot script: the real decoder and the real renderer surviving the
 * handover. A second open that has to wait out the initial-fill and first-frame deadlines still
 * reports Paused with a duration, so the failure is invisible to a status check and shows up only
 * as wall-clock time.
 */
@NeedsPushedMedia
internal class ReopenDeviceTest {

    @Test
    fun replacingTheMediaOpensPromptlyWithTheRendererAttached() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val clip = listOfNotNull(
            context.getExternalFilesDir(null)?.resolve("testmedia/sync1080p30.mp4"),
            context.filesDir.resolve("testmedia/sync1080p30.mp4"),
        ).firstOrNull(File::isFile)
        checkNotNull(clip) { "fixture not found; push testmedia/sync1080p30.mp4 first" }

        val scenario = ActivityScenario.launch(KiteVideoTestActivity::class.java)
        try {
            lateinit var state: KiteVideoState
            lateinit var consumerReady: java.util.concurrent.CountDownLatch
            scenario.onActivity { activity ->
                state = activity.videoState
                consumerReady = activity.composeConsumerReady
            }
            check(consumerReady.await(30, TimeUnit.SECONDS)) {
                "KiteVideo did not bind its Window consumer"
            }

            val player = KitePlayer.create(PlayerConfig(backends = mobileBackends()))
            try {
                runBlocking {
                    player.attachRenderer(state.renderer)

                    val firstOpenNanos = measureWatched("the first open") { player.open(MediaItem(clip.absolutePath)) }
                    player.play()
                    delay(2_000)
                    player.pause()
                    delay(200)

                    // Exactly what a host app does for file number two.
                    val stopNanos = measureWatched("the stop") { player.stop() }
                    val reopenNanos = measureWatched("the second open") { player.open(MediaItem(clip.absolutePath)) }
                    val secondOpenNanos = stopNanos + reopenNanos

                    val firstMs = firstOpenNanos / 1_000_000
                    val secondMs = secondOpenNanos / 1_000_000
                    val warnings = player.warningHistory()
                    Log.i(TAG, "firstOpenMs=$firstMs secondOpenMs=$secondMs (stop ${stopNanos / 1_000_000}, open ${reopenNanos / 1_000_000})")
                    Log.i(TAG, "statusAfterSecond=${player.state.value.status}")
                    Log.i(TAG, "durationAfterSecond=${player.state.value.duration}")
                    Log.i(TAG, "warnings=$warnings")

                    assertTrue(
                        player.state.value.status == PlaybackStatus.Paused,
                        "the replacement open left status ${player.state.value.status}",
                    )
                    assertTrue(
                        secondMs < firstMs + 3_000,
                        "the second open took ${secondMs}ms against ${firstMs}ms for the first: " +
                            "replacing the media must prime the new pipeline, not wait out the " +
                            "initial-fill and first-frame deadlines. The stop took " +
                            "${stopNanos / 1_000_000}ms and the open ${reopenNanos / 1_000_000}ms; " +
                            "warnings: $warnings",
                    )
                }
            } finally {
                runBlocking { withTimeout(15_000) { player.closeAndAwait() } }
                state.renderer.close()
            }
        } finally {
            scenario.close()
        }
    }

    private inline fun measure(block: () -> Unit): Long {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        block()
        return SystemClock.elapsedRealtimeNanos() - startedAt
    }

    /**
     * [measure], and when [block] runs past [WATCHDOG_MILLIS], every thread's stack goes to the log
     * once. The slow second open showed once in three emulator runs, so the evidence has to be
     * taken from inside the run that is slow.
     */
    private inline fun measureWatched(what: String, block: () -> Unit): Long {
        val done = CountDownLatch(1)
        Thread {
            if (!done.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "$what has run for $WATCHDOG_MILLIS ms; the stack of every thread follows")
                Thread.getAllStackTraces().forEach { (thread, stack) ->
                    Log.w(TAG, "\"${thread.name}\" ${thread.state}\n" + stack.joinToString("\n") { "    at $it" })
                }
            }
        }.apply { isDaemon = true }.start()
        try {
            return measure(block)
        } finally {
            done.countDown()
        }
    }

    private companion object {
        const val TAG = "KiteReopen"
        const val WATCHDOG_MILLIS = 3_000L
    }
}
