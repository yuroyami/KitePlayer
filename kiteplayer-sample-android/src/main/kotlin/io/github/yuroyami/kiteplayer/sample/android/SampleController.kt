package io.github.yuroyami.kiteplayer.sample.android

import android.content.Context
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.phone.KitePlayerView
import io.github.yuroyami.kiteplayer.phone.phoneBackends
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sample-private glue, re-consumed through the phone aggregate (S1.d.4): the player assembled
 * from `phoneBackends()`, the picture handled entirely by [KitePlayerView], and the smoke
 * workflow the plan's jq oracle reads. The surface lifecycle code this class carried in S1.c.6
 * is gone, because owning that lifecycle is exactly what the reusable view is for.
 */
internal class SampleController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var player: KitePlayer? = null

    /** Copies the bundled clip to app-private storage and returns its path. */
    private fun materialiseClip(): File {
        val out = File(context.filesDir, "sync1080p30.mp4")
        if (!out.isFile || out.length() == 0L) {
            context.assets.open("sync1080p30.mp4").use { asset ->
                out.outputStream().use { asset.copyTo(it) }
            }
        }
        return out
    }

    private fun createPlayer(): KitePlayer = KitePlayer.create(
        PlayerConfig(backends = phoneBackends()),
    )

    /** The ordinary controls: open the private copy paused, with the picture on [view]. */
    fun openNormally(view: KitePlayerView) {
        scope.launch {
            val p = createPlayer()
            player = p
            // The view's members are main-thread only, like every Android view.
            withContext(Dispatchers.Main) { view.player = p }
            p.open(MediaItem(uri = materialiseClip().absolutePath))
        }
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekToFiveSeconds() {
        scope.launch { player?.seek(5_000.milliseconds, SeekMode.Precise) }
    }

    fun onBackground() {
        pause()
    }

    /**
     * Activity teardown. The view detaches itself when its window goes; the player is the one
     * thing left to close.
     */
    fun shutdown() {
        val p = player
        player = null
        p?.close()
        scope.cancel()
    }

    /**
     * The S1.c.6 smoke, re-run through the view (S1.d.4 step 4): open, play, one precise seek to
     * 5000 ms confirmed inside 5000..5034 ms with a later presentation, Ended, teardown, and only
     * then the atomic eleven-key oracle. The presentation evidence now comes from the view's
     * cumulative counters instead of a hand-built renderer.
     */
    fun runSmoke(view: KitePlayerView) {
        scope.launch {
            var seekRequested = false
            var seekLanded = false
            var surfaceFrame = false
            var terminal = "Failed"
            var teardownCompleted = false
            val p = createPlayer()
            try {
                withTimeout(45_000) {
                    withContext(Dispatchers.Main) { view.player = p }
                    p.open(MediaItem(uri = materialiseClip().absolutePath))
                    p.play()
                    p.state.first { it.status == PlaybackStatus.Playing }

                    seekRequested = true
                    p.seek(5_000.milliseconds, SeekMode.Precise)
                    val presentedAtSeek = view.presentedFrames
                    // position(), not progress.value: progress republishes on an interval, so its
                    // sample is stale for up to one interval after a seek. The iOS smoke always
                    // read position(); the S1.c.6 Android smoke passed on sample-timing luck.
                    val landed = p.position().inWholeMilliseconds
                    if (landed in 5_000..5_034) {
                        /* A later presentation proves the landed picture reached the view. */
                        while (view.presentedFrames <= presentedAtSeek) {
                            kotlinx.coroutines.delay(10)
                        }
                        seekLanded = true
                        surfaceFrame = view.presentedFrames > 0
                    }

                    p.state.first { it.status == PlaybackStatus.Ended || it.status == PlaybackStatus.Failed }
                    terminal = p.state.value.status.toString()
                }
            } catch (_: Throwable) {
                terminal = p.state.value.status.toString()
            } finally {
                val stats = p.stats.value
                val presented = view.presentedFrames
                runCatching { withTimeout(12_000) { p.closeAndAwait() } }
                    .onSuccess { teardownCompleted = true }
                runCatching { withContext(Dispatchers.Main) { view.player = null } }
                SmokeResult(
                    seekRequested = seekRequested,
                    seekLanded = seekLanded,
                    terminalState = terminal,
                    decodedFrames = stats.decodedVideoFrames,
                    submittedFrames = stats.submittedFrames,
                    presentedFrames = presented,
                    surfaceFrame = surfaceFrame,
                    audioUnderruns = stats.audioUnderruns,
                    hardwareDecode = SmokeResult.label(stats.hardwareDecode),
                    teardownCompleted = teardownCompleted,
                ).writeAtomically(context.filesDir)
            }
        }
    }
}
