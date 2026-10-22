package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.ProjectionKind
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A 360 degree video's projection, as the FFmpeg backend reports it and the player lists it (#460),
 * on the fixtures `scripts/testmedia.sh` makes in `testmedia/spherical`.
 */
class SphericalVideoTest {

    private fun fixture(name: String): File {
        val dir = formatMatrixMediaDir() ?: error("no testmedia directory")
        return File(dir, name).takeIf { it.isFile }
            ?: error("testmedia is missing $name; run scripts/testmedia.sh")
    }

    private suspend fun projectionOf(name: String): VideoProjection? =
        KiteFFmpegMediaBackend().open(MediaItem(fixture(name).absolutePath)).use { session ->
            session.source.streams.single { it.kind == TrackKind.Video }.projection
        }

    @Test
    fun theBackendReportsEachFixturesProjectionAndPose() = runBlocking {
        assertEquals(VideoProjection(ProjectionKind.Equirectangular), projectionOf("spherical/equirect.mkv"))
        assertEquals(
            VideoProjection(ProjectionKind.Equirectangular, yaw = 30.0, pitch = 0.0, roll = 0.0),
            projectionOf("spherical/equirect-yaw30.mkv"),
        )
        // The middle half of the width: a quarter of the whole picture lies beyond each side.
        assertEquals(
            VideoProjection(ProjectionKind.EquirectangularTile(left = 0.25, top = 0.0, right = 0.25, bottom = 0.0)),
            projectionOf("spherical/equirect-tile.mkv"),
        )
    }

    @Test
    fun aFlatClipReportsNoProjection() = runBlocking {
        assertEquals(null, projectionOf("editions/film.mkv"), "a Matroska file with no Projection element")
        assertEquals(null, projectionOf("sync1080p30.mp4"), "an MP4 file with no spherical boxes")
    }

    @Test
    fun theVideoTrackListsTheProjectionAndADrawerOfFlatPicturesIsWarnedOnce() = runBlocking {
        val renderer = Pictures()
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            player.attachRendererAndAwait(renderer)
            withTimeout(30.seconds) { player.open(MediaItem(fixture("spherical/equirect-yaw30.mkv").absolutePath)) }
            val track = player.state.value.tracks.selectedTrack(TrackKind.Video)
            assertEquals(VideoProjection(ProjectionKind.Equirectangular, yaw = 30.0), track?.projection)

            player.play()
            withTimeout(10.seconds) { while (renderer.shown.size < 10) delay(20.milliseconds) }
            // The renderer draws the whole picture flat, as it always has.
            assertTrue(renderer.shown.all { it == VideoSize(512, 256) }, "${renderer.shown.distinct()}")
            val warnings = player.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.ProjectionUnavailable>()
            assertEquals(listOf(ProjectionKind.Equirectangular), warnings.map { it.kind })
        } finally {
            player.closeAndAwait()
        }
    }

    /** Draws nothing, and keeps the size of each picture it is handed. */
    private class Pictures : VideoRenderer {
        val shown: MutableList<VideoSize> = CopyOnWriteArrayList()

        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

        override fun supports(format: PlayerPixelFormat): Boolean = true

        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = frame.use {
            shown += frame.size
            true
        }

        override fun vsyncIntervalNanos(): Long? = null

        override fun setViewport(width: Int, height: Int, scale: Float) = Unit

        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit

        override val events: Flow<RendererEvent> = emptyFlow()

        override fun close() = Unit
    }
}
