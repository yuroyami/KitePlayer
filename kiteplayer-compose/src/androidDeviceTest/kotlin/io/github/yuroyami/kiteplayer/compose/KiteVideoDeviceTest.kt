package io.github.yuroyami.kiteplayer.compose

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
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
 * the renderer-coupled MediaCodec/OES bridge, presented through Compose's own pipeline under a
 * clip and a rotation, on the named emulator. The KV4-tagged logcat lines are the transcript the plan
 * quotes; every number is an EMULATOR number and is labelled provisional wherever it travels.
 *
 * Media may live in this package's external files directory or its internal files directory. The
 * latter lets a debuggable test APK receive the fixture through `run-as` without changing ADB's
 * privilege level, then drive the installed instrumentation directly with `am instrument`.
 */
internal class KiteVideoDeviceTest {

    @Test
    fun kiteVideoPlaysRealMediaUnderComposeModifiers() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.resolve("testmedia/sync1080p30.mp4"),
            context.filesDir.resolve("testmedia/sync1080p30.mp4"),
        )
        val clip = candidates.firstOrNull(File::isFile)
        checkNotNull(clip) { "matrix media not found at ${candidates.joinToString()}. Push it first." }

        val scenario = ActivityScenario.launch(KiteVideoTestActivity::class.java)
        try {
            lateinit var state: KiteVideoState
            scenario.onActivity { activity -> state = activity.videoState }

            val player = KitePlayer.create(
                PlayerConfig(
                    backends = phoneBackends(),
                    hardwareDecode = HwdecPolicy.Require,
                ),
            )
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
            assertTrue(
                state.presentedFrames >= MINIMUM_PRESENTED_FRAMES,
                "KiteVideo was effectively a slideshow: ${state.presentedFrames} frames",
            )
            assertTrue(
                state.presentedFrames * MINIMUM_PRESENTED_RATIO_DENOMINATOR >= stats.submittedFrames,
                "KiteVideo published too few of ${stats.submittedFrames} submitted frames",
            )
            assertEquals(0L, state.failedFrames, "the Compose video renderer failed frames")
            assertEquals(
                HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec),
                stats.hardwareDecode,
                "KiteVideo did not select its renderer-coupled MediaCodec bridge",
            )
            assertEquals(0L, cost.samples, "hardware frames must not enter the CPU conversion path")
        } finally {
            runCatching { scenario.close() }
        }
    }

    private companion object {
        private const val TAG = "KV4"
        private const val MINIMUM_PRESENTED_FRAMES = 150L
        private const val MINIMUM_PRESENTED_RATIO_DENOMINATOR = 2L
    }
}
