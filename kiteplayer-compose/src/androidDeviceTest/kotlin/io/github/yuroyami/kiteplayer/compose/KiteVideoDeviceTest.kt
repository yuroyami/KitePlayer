package io.github.yuroyami.kiteplayer.compose

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.phone.phoneBackends
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measured KiteVideo run of the 17.4.6 rider (A3): real playback, hardware-decoded through
 * FFmpeg's mediacodec path, presented through Compose's own pipeline under a clip and a
 * rotation, on the named emulator. The KV4-tagged logcat lines are the transcript the plan
 * quotes; every number is an EMULATOR number and is labelled provisional wherever it travels.
 *
 * Media arrives by the S1.e.4 recipe: install, push into this package's external files dir,
 * fix ownership (adb root; chown -R appId:ext_data_rw; adb unroot), then drive the installed
 * instrumentation directly with am instrument.
 */
internal class KiteVideoDeviceTest {

    @Test
    fun kiteVideoPlaysRealMediaUnderComposeModifiers() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val mediaDir = checkNotNull(context.getExternalFilesDir(null)) {
            "the instrumentation context has no external files directory"
        }.resolve("testmedia")
        val clip = File(mediaDir, "sync1080p30.mp4")
        check(clip.isFile) { "matrix media not found at $clip. Push it first." }

        val scenario = ActivityScenario.launch(KiteVideoTestActivity::class.java)
        try {
            lateinit var state: KiteVideoState
            scenario.onActivity { activity -> state = activity.videoState }

            val player = KitePlayer.create(PlayerConfig(backends = phoneBackends()))
            val (stats, terminal) = runBlocking {
                try {
                    player.attachRenderer(state.renderer)
                    player.open(MediaItem(clip.absolutePath))
                    player.play()
                    val ended = withTimeout(90_000) {
                        player.state.first {
                            it.status == PlaybackStatus.Ended || it.status == PlaybackStatus.Failed
                        }
                    }
                    ended.error?.let { failure -> Log.i(TAG, "error=$failure") }
                    player.stats.value to ended.status
                } finally {
                    withTimeout(15_000) { player.closeAndAwait() }
                }
            }
            state.renderer.close()
            val cost = state.frameCost

            Log.i(TAG, "decodedFrames=${stats.decodedVideoFrames}")
            Log.i(TAG, "submittedFrames=${stats.submittedFrames}")
            Log.i(TAG, "publishedFrames=${state.presentedFrames}")
            Log.i(TAG, "supersededFrames=${state.supersededFrames}")
            Log.i(TAG, "failedFrames=${state.failedFrames}")
            Log.i(TAG, "audioUnderruns=${stats.audioUnderruns}")
            Log.i(TAG, "hardwareDecode=${stats.hardwareDecode}")
            Log.i(TAG, "costSamples=${cost.samples}")
            Log.i(TAG, "costAverageNanos=${cost.averageNanos}")
            Log.i(TAG, "costWorstNanos=${cost.worstNanos}")
            Log.i(TAG, "terminal=$terminal")

            assertEquals(PlaybackStatus.Ended, terminal, "playback must end, not fail")
            assertTrue(state.presentedFrames > 0, "KiteVideo published no frame")
            assertEquals(0L, state.failedFrames, "the software path failed frames")
            assertTrue(cost.samples > 0, "no cost was measured")
            assertTrue(cost.averageNanos in 1..cost.worstNanos, "the cost snapshot is incoherent")
            assertEquals(
                state.presentedFrames,
                cost.samples,
                "every published frame is a cost sample and nothing else is",
            )
        } finally {
            runCatching { scenario.close() }
        }
    }

    private companion object {
        private const val TAG = "KV4"
    }
}
