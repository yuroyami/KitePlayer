package io.github.yuroyami.kiteplayer.sample.android

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.HdrPolicy
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.VideoDynamicRange
import io.github.yuroyami.kiteplayer.view.KitePlayerView
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * HDR on the direct view (#68): on a display that supports HDR10, a PQ clip goes to the Surface as
 * HDR and the player reports it shown, and under `HdrPolicy.ToneMap` the next open asks MediaCodec
 * for SDR and the player reports it tone mapped. Push the clip to `files/testmedia/hdr10.mp4` first.
 */
@RunWith(AndroidJUnit4::class)
class HdrViewTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun anHdrClipShowsAsHdrAndToneMapsWhenAsked() {
        val context = instrumentation.targetContext
        // The display first: one without HDR10, such as the CI emulator's, skips the test whether
        // or not the clip was pushed.
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        assumeTrue("this display shows no HDR10", Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes)
        val clip = File(context.filesDir, "testmedia/hdr10.mp4")
        check(clip.isFile) { "push an HDR10 clip to ${clip.absolutePath} first" }

        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SOURCE, MainActivity.SOURCE_PATH)
            .putExtra(MainActivity.EXTRA_PATH, clip.absolutePath)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var view: KitePlayerView
            scenario.onActivity { activity ->
                // A locked phone draws no activity, so the test shows over the lock screen.
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                view = activity.findViewById(R.id.player_view)
            }
            val player = await("the clip opened") { onMain { view.player }?.takeIf { it.state.value.status == PlaybackStatus.Paused } }
            instrumentation.runOnMainSync { player.play() }
            await("the clip shown as HDR") { player.takeIf { it.state.value.videoDynamicRange == VideoDynamicRange.High } }
            Log.i(TAG, "shown: ${hdrLayers()}")

            player.setHdrPolicy(HdrPolicy.ToneMap)
            reopen(player, clip)
            await("the clip tone mapped") { player.takeIf { it.state.value.videoDynamicRange == VideoDynamicRange.ToneMapped } }
            Log.i(TAG, "tone mapped: ${hdrLayers()}")
            val warnings = player.warningHistory().map { it.warning }
            check(warnings.any { it is PlaybackWarning.HdrToneMapped }) { "no tone map warning: $warnings" }
        }
    }

    private fun reopen(player: KitePlayer, clip: File) = runBlocking {
        player.stop()
        player.open(MediaItem(clip.absolutePath))
        player.play()
    }

    /** The compositor's description of this app's video layers, which names their dataspace. */
    private fun hdrLayers(): String {
        val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys SurfaceFlinger").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }
        return dump.lineSequence()
            .filter { "dataspace" in it.lowercase() && ("SurfaceView" in it || "kiteplayer" in it) }
            .take(6)
            .joinToString(" | ")
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
        const val TAG = "KiteHdrView"
        const val TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 200L
    }
}
