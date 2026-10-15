package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole player drawing a 360 degree video through the Compose canvas path (#460), on the
 * clips `scripts/testmedia.sh` makes in `testmedia/spherical`.
 */
class SphereViewPlaybackTest {
    init { useSkiaGraphics() }

    private fun fixture(name: String): File {
        val dir = System.getenv("KITEPLAYER_TESTMEDIA") ?: error("no testmedia directory")
        return File(dir, name).takeIf { it.isFile } ?: error("testmedia is missing $name; run scripts/testmedia.sh")
    }

    /** The clips have no sound, so nothing ever asks for a sink. */
    private object Silence : OutputBackend {
        override val clock: MonotonicClock = MonotonicClock.System
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = "none"
            override suspend fun create(): AudioSink = error("a clip with no sound asked for a sink")
        }
    }

    private fun player() = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), Silence)))

    /** The centre pixel of the picture [state] holds, drawn as KiteVideo's draw phase draws it. */
    private fun centreOf(state: KiteVideoState, width: Int = 160, height: Int = 90): Int {
        val frame = state.acquireFrameForDraw() ?: error("no picture was published")
        try {
            val target = ImageBitmap(width, height)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(width.toFloat(), height.toFloat())) {
                drawFramePicture(frame, VideoScale.Fit, VideoTransform.Identity, FilterQuality.Low, null, state.sphere.value)
            }
            val pixels = IntArray(width * height)
            target.readPixels(pixels)
            return pixels[(height / 2) * width + width / 2]
        } finally {
            state.frameDrawFinished(frame, recorded = false)
        }
    }

    /** True when [argb] is [rgb] within the rounding of the picture's encoding. */
    private fun near(argb: Int, rgb: Int): Boolean = (0..2).all { channel ->
        abs((argb shr (8 * channel) and 0xFF) - (rgb shr (8 * channel) and 0xFF)) <= 24
    }

    private suspend fun awaitCentre(state: KiteVideoState, rgb: Int, what: String) {
        var last = 0
        try {
            withTimeout(5.seconds) {
                while (true) {
                    if (state.frame.value != null) {
                        last = centreOf(state)
                        if (near(last, rgb)) return@withTimeout
                    }
                    delay(20.milliseconds)
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$what: the centre stayed ${Integer.toHexString(last)}, not ${Integer.toHexString(rgb)}", timeout)
        }
    }

    @Test
    fun aWholeTurnOfTheViewWhileItPlaysDropsNoFrame() = runBlocking {
        val state = KiteVideoState()
        val player = player()
        try {
            player.attachRendererAndAwait(state.renderer)
            withTimeout(30.seconds) { player.open(MediaItem(fixture("spherical/equirect.mkv").absolutePath)) }
            awaitCentre(state, SpherePicture.RED and 0xFFFFFF, "the view starts at the picture's front")
            player.play()
            // Once round in three seconds, a step every 25 milliseconds, as a drag would turn it.
            for (step in 0..120) {
                player.setSphericalView(SphericalView(yaw = step * 3f))
                delay(25.milliseconds)
            }
            assertEquals(PlaybackStatus.Playing, player.state.value.status, "the clip ended before the turn did")
            player.setSphericalView(SphericalView(yaw = 90f))
            awaitCentre(state, SpherePicture.GREEN and 0xFFFFFF, "a quarter turn to the right")
            val stats = player.stats.value
            assertEquals(
                listOf(0L, 0L, 0L),
                listOf(stats.droppedFramesLate, stats.droppedFramesDecode, stats.refusedFrames),
                "late, undecoded and refused frames while the view turned",
            )
            assertTrue(stats.decodedVideoFrames > 60, "too few frames played to mean anything: ${stats.decodedVideoFrames}")
            assertTrue(player.warningHistory().none { it.warning is PlaybackWarning.ProjectionUnavailable })
        } finally {
            player.closeAndAwait()
            state.renderer.close()
        }
    }

    @Test
    fun theFilesPoseAndATileReachTheDraw() = runBlocking {
        val state = KiteVideoState()
        val player = player()
        try {
            player.attachRendererAndAwait(state.renderer)
            withTimeout(30.seconds) { player.open(MediaItem(fixture("spherical/equirect-yaw30.mkv").absolutePath)) }
            // The picture's front is 30 degrees to the right of where the view starts.
            awaitCentre(state, SpherePicture.ORANGE and 0xFFFFFF, "the view the file starts")
            player.setSphericalView(SphericalView(yaw = 30f))
            awaitCentre(state, SpherePicture.RED and 0xFFFFFF, "30 degrees to the right")

            player.stop()
            player.setSphericalView(SphericalView())
            withTimeout(30.seconds) { player.open(MediaItem(fixture("spherical/equirect-tile.mkv").absolutePath)) }
            awaitCentre(state, SpherePicture.RED and 0xFFFFFF, "the tile's front")
            player.setSphericalView(SphericalView(yaw = 80f))
            awaitCentre(state, SpherePicture.GREEN and 0xFFFFFF, "near the tile's right edge")

            // Switched to the flat picture, the view draws the whole tile again, letterboxed.
            player.setSphericalView(SphericalView(projected = false))
            withTimeout(5.seconds) { while (state.sphere.value != null) delay(20.milliseconds) }
        } finally {
            player.closeAndAwait()
            state.renderer.close()
        }
    }
}
