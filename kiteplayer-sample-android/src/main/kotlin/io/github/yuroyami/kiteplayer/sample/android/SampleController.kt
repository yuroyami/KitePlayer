package io.github.yuroyami.kiteplayer.sample.android

import android.content.Context
import android.view.Surface
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecMediaBackend
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.output.AndroidOutputBackend
import io.github.yuroyami.kiteplayer.output.AndroidSurfaceVideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sample-private glue (S1.c.6 steps 4 to 6): the backend triple assembled exactly the way any
 * application would, one renderer over the caller's Surface, and the smoke workflow the plan's
 * jq oracle reads. Nothing here is a reusable view or a public API; S1.d owns that shape.
 */
internal class SampleController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var player: KitePlayer? = null
    private var renderer: AndroidSurfaceVideoRenderer? = null

    /** The conversion lambda accepts only the backend's own frame; anything else is a wiring bug. */
    private val convert: (VideoFrame) -> ByteArray = { frame ->
        require(frame is KiteCodecVideoFrame) {
            "the sample converts KiteCodecVideoFrame only, got ${frame::class.simpleName}"
        }
        SoftwareConverter.toRgba(frame)
    }

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
        PlayerConfig(
            backends = Backends(
                backend = KiteCodecMediaBackend(),
                output = AndroidOutputBackend,
            ),
        ),
    )

    /** The ordinary controls: open the private copy paused, with the picture on [surface]. */
    fun openNormally(surface: Surface) {
        scope.launch {
            val p = createPlayer()
            player = p
            val r = AndroidSurfaceVideoRenderer(surface, convert)
            renderer = r
            p.attachRenderer(r)
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

    /** surfaceDestroyed: detach and close the renderer BEFORE the Surface goes back. */
    fun onSurfaceDestroyed() {
        player?.detachRenderer()
        renderer?.close()
        renderer = null
    }

    fun onBackground() {
        pause()
    }

    /** Activity teardown: player first, then any renderer, each once. */
    fun shutdown() {
        val p = player
        player = null
        p?.close()
        renderer?.close()
        renderer = null
        scope.cancel()
    }

    /**
     * The smoke of S1.c.6 step 6: open, play, one precise seek to 5000 ms confirmed inside
     * 5000..5034 ms with a later presentation, Ended, close player and renderer, and only then
     * the atomic eleven-key oracle.
     */
    fun runSmoke(surface: Surface) {
        scope.launch {
            var seekRequested = false
            var seekLanded = false
            var surfaceFrame = false
            var terminal = "Failed"
            var teardownCompleted = false
            val p = createPlayer()
            val r = AndroidSurfaceVideoRenderer(surface, convert)
            try {
                withTimeout(45_000) {
                    p.attachRenderer(r)
                    p.open(MediaItem(uri = materialiseClip().absolutePath))
                    p.play()
                    p.state.first { it.status == PlaybackStatus.Playing }

                    seekRequested = true
                    p.seek(5_000.milliseconds, SeekMode.Precise)
                    val presentedAtSeek = r.presentedFrames
                    val landed = p.progress.value.position.inWholeMilliseconds
                    if (landed in 5_000..5_034) {
                        /* A later presentation proves the landed picture reached the Surface. */
                        while (r.presentedFrames <= presentedAtSeek) {
                            kotlinx.coroutines.delay(10)
                        }
                        seekLanded = true
                        surfaceFrame = r.presentedFrames > 0
                    }

                    p.state.first { it.status == PlaybackStatus.Ended || it.status == PlaybackStatus.Failed }
                    terminal = p.state.value.status.toString()
                }
            } catch (_: Throwable) {
                terminal = p.state.value.status.toString()
            } finally {
                val stats = p.stats.value
                val presented = r.presentedFrames
                runCatching { withTimeout(12_000) { p.closeAndAwait() } }
                    .onSuccess { teardownCompleted = true }
                runCatching { r.close() }
                if (!teardownCompleted) teardownCompleted = false
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
