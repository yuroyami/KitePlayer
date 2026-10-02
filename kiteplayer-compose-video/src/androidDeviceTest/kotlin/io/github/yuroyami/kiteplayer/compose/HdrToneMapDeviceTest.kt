package io.github.yuroyami.kiteplayer.compose

import android.os.Build
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NeedsPushedMedia
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Compose GPU path asks MediaCodec to roll HDR off to SDR, because its bitmap is SDR, and it
 * now says so (#23): a PQ clip raises `PlaybackWarning.HdrToneMapped`, on every open of the same
 * renderer.
 */
@NeedsPushedMedia
internal class HdrToneMapDeviceTest {

    @Test
    fun theComposeGpuPathWarnsThatItToneMappedOnEveryOpen() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "the Compose GPU path requires API 31, found API ${Build.VERSION.SDK_INT}"
        }
        val context = InstrumentationRegistry.getInstrumentation().context
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.resolve("testmedia/colors-pq.mp4"),
            context.filesDir.resolve("testmedia/colors-pq.mp4"),
        )
        val clip = checkNotNull(candidates.firstOrNull(File::isFile)) {
            "colors-pq.mp4 not found at ${candidates.joinToString()}. Push it first."
        }
        val scenario = ActivityScenario.launch(KiteVideoTestActivity::class.java)
        try {
            lateinit var state: KiteVideoState
            lateinit var consumerReady: java.util.concurrent.CountDownLatch
            scenario.onActivity { activity ->
                state = activity.videoState
                consumerReady = activity.composeConsumerReady
            }
            check(consumerReady.await(10, TimeUnit.SECONDS)) { "KiteVideo did not bind its Window consumer" }
            val player = KitePlayer.create(PlayerConfig(backends = mobileBackends(), hardwareDecode = HwdecPolicy.Require))
            try {
                runBlocking {
                    player.attachRenderer(state.renderer)
                    repeat(2) { round ->
                        player.open(MediaItem(clip.absolutePath))
                        player.play()
                        val end = withTimeout(30_000) {
                            player.state.first { it.status == PlaybackStatus.Ended || it.status == PlaybackStatus.Failed }
                        }
                        assertEquals(PlaybackStatus.Ended, end.status, "round $round failed: ${end.error}")
                        player.stop()
                        // The renderer repeats its announcement at most once a second.
                        if (round == 0) delay(1_200)
                    }
                }
                val toneMapped = player.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.HdrToneMapped>()
                Log.i(TAG, "HDR device=${Build.MANUFACTURER} ${Build.MODEL} warnings=$toneMapped")
                assertEquals(2, toneMapped.size, "one tone map warning per open, got $toneMapped")
                assertTrue(toneMapped.all { it.transfer.equals("pq", ignoreCase = true) }, "the warning names PQ: $toneMapped")
            } finally {
                runBlocking { withTimeout(15_000) { player.closeAndAwait() } }
                state.renderer.close()
            }
        } finally {
            scenario.close()
        }
    }

    private companion object {
        const val TAG = "KiteHdrDevice"
    }
}
