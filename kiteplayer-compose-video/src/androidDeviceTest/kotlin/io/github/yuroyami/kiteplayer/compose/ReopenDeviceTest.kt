package io.github.yuroyami.kiteplayer.compose

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
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

                    val firstOpenNanos = measure { player.open(MediaItem(clip.absolutePath)) }
                    player.play()
                    delay(2_000)
                    player.pause()
                    delay(200)

                    // Exactly what a host app does for file number two.
                    val secondOpenNanos = measure {
                        player.stop()
                        player.open(MediaItem(clip.absolutePath))
                    }

                    val firstMs = firstOpenNanos / 1_000_000
                    val secondMs = secondOpenNanos / 1_000_000
                    Log.i(TAG, "firstOpenMs=$firstMs secondOpenMs=$secondMs")
                    Log.i(TAG, "statusAfterSecond=${player.state.value.status}")
                    Log.i(TAG, "durationAfterSecond=${player.state.value.duration}")

                    assertTrue(
                        player.state.value.status == PlaybackStatus.Paused,
                        "the replacement open left status ${player.state.value.status}",
                    )
                    assertTrue(
                        secondMs < firstMs + 3_000,
                        "the second open took ${secondMs}ms against ${firstMs}ms for the first: " +
                            "replacing the media must prime the new pipeline, not wait out the " +
                            "initial-fill and first-frame deadlines",
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

    private companion object {
        const val TAG = "KiteReopen"
    }
}
