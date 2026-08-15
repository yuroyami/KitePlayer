package io.github.yuroyami.kiteplayer.sample.android

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.view.KitePlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sample-private glue for the mobile stack: one Activity-scoped player assembled from
 * `mobileBackends()`, renderer-neutral controls, and the direct-view smoke workflow the plan's jq
 * oracle reads. Each Activity deliberately owns and installs its own presentation API.
 */
internal class SampleController(
    private val context: Context,
    hardwareDecode: HwdecPolicy = HwdecPolicy.Auto,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val openStarted = AtomicBoolean()
    private val closed = AtomicBoolean()

    /** One Activity-scoped player. Presentation ownership remains visible in each Activity. */
    val player: KitePlayer = createPlayer(hardwareDecode)

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

    private fun createPlayer(hardwareDecode: HwdecPolicy): KitePlayer = KitePlayer.create(
        PlayerConfig(
            hardwareDecode = hardwareDecode,
            backends = mobileBackends(),
        ),
    )

    /** The ordinary controls: open the private copy paused, with the picture on [view]. */
    fun openNormally(view: KitePlayerView) {
        if (!openStarted.compareAndSet(false, true)) return
        scope.launch {
            // The view's members are main-thread only, like every Android view.
            withContext(Dispatchers.Main) { view.player = player }
            player.open(MediaItem(uri = materialiseClip().absolutePath))
        }
    }

    /** Opens after a Compose host has installed its presentation path. */
    fun openNormally(renderer: VideoRenderer? = null) {
        if (!openStarted.compareAndSet(false, true)) return
        scope.launch {
            renderer?.let(player::attachRenderer)
            player.open(MediaItem(uri = materialiseClip().absolutePath))
        }
    }

    fun play() {
        if (openStarted.get()) player.play()
    }

    fun pause() {
        if (openStarted.get()) player.pause()
    }

    fun seekToFiveSeconds() {
        if (openStarted.get()) {
            scope.launch { player.seek(5_000.milliseconds, SeekMode.Precise) }
        }
    }

    fun onBackground() {
        pause()
    }

    /** Samples engine and renderer totals once a second for the Android performance baseline. */
    fun observePerformance(view: KitePlayerView, onSample: (String) -> Unit) {
        scope.launch {
            var previousPresented: Long? = null
            var previousSampleNanos: Long? = null
            while (isActive) {
                delay(1_000)
                val stats = player.stats.value
                val renderer = withContext(Dispatchers.Main) {
                    Triple(view.presentedFrames, view.failedFrames, view.supersededFrames)
                }
                val presented = renderer.first
                val sampleNanos = SystemClock.elapsedRealtimeNanos()
                val previousCount = previousPresented
                val previousNanos = previousSampleNanos
                previousPresented = presented
                previousSampleNanos = sampleNanos
                if (previousCount == null || previousNanos == null) continue

                val elapsedNanos = (sampleNanos - previousNanos).coerceAtLeast(1L)
                val presentedFps = (presented - previousCount).coerceAtLeast(0L) *
                    1_000_000_000.0 / elapsedNanos.toDouble()
                val dropped = stats.droppedFramesLate + stats.droppedFramesDecode
                val line = String.format(
                    Locale.US,
                    "presented=%.1f decoded=%.1f engineDrop=%d outputFail=%d superseded=%d",
                    presentedFps,
                    stats.videoDecodeFps,
                    dropped,
                    renderer.second,
                    renderer.third,
                )
                Log.i("KitePerf", line)
                withContext(Dispatchers.Main) { onSample(line) }
            }
        }
    }

    /**
     * Activity teardown after MainActivity has released the view-owned renderer. The player is
     * independently caller-owned and closes here.
     */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        // Close first. In particular, cancellation must never strand the smoke player's workers
        // before its non-cancellable finally block can await teardown and write the oracle.
        try {
            player.close()
        } finally {
            scope.cancel()
        }
    }

    /**
     * The S1.c.6 smoke, re-run through the view (S1.d.4 step 4): open, play, one precise seek to
     * 5000 ms confirmed inside 5000..5034 ms with a later presentation, Ended, teardown, and only
     * then the atomic eleven-key oracle. The presentation evidence now comes from the view's
     * cumulative counters instead of a hand-built renderer.
     */
    fun runSmoke(view: KitePlayerView) {
        if (!openStarted.compareAndSet(false, true)) return
        scope.launch {
            var seekRequested = false
            var seekLanded = false
            var surfaceFrame = false
            var terminal = "Failed"
            var teardownCompleted = false
            val p = player
            try {
                withTimeout(45_000) {
                    withContext(Dispatchers.Main) { view.player = p }
                    p.open(MediaItem(uri = materialiseClip().absolutePath))
                    p.play()
                    p.state.first { it.status == PlaybackStatus.Playing }

                    seekRequested = true
                    p.seek(5_000.milliseconds, SeekMode.Precise)
                    val presentedAtSeek = withContext(Dispatchers.Main) { view.presentedFrames }
                    // position(), not progress.value: progress republishes on an interval, so its
                    // sample is stale for up to one interval after a seek. The iOS smoke always
                    // read position(); the S1.c.6 Android smoke passed on sample-timing luck.
                    val landed = p.position().inWholeMilliseconds
                    if (landed in 5_000..5_034) {
                        /* A later presentation proves the landed picture reached the view. */
                        while (withContext(Dispatchers.Main) { view.presentedFrames } <= presentedAtSeek) {
                            kotlinx.coroutines.delay(10)
                        }
                        seekLanded = true
                        surfaceFrame = withContext(Dispatchers.Main) { view.presentedFrames } > 0
                    }

                    p.state.first { it.status == PlaybackStatus.Ended || it.status == PlaybackStatus.Failed }
                    terminal = p.state.value.status.toString()
                }
            } catch (_: Throwable) {
                terminal = p.state.value.status.toString()
            } finally {
                withContext(NonCancellable) {
                    val stats = p.stats.value
                    val presented = withContext(Dispatchers.Main) { view.presentedFrames }
                    // Renderer/view ownership ends before player ownership. The oracle calls
                    // teardown complete only if both boundaries actually completed.
                    val rendererReleased = runCatching {
                        withContext(Dispatchers.Main) { view.player = null }
                    }.isSuccess
                    val playerReleased = runCatching {
                        withTimeout(12_000) { p.closeAndAwait() }
                    }.isSuccess
                    teardownCompleted = rendererReleased && playerReleased
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
}
