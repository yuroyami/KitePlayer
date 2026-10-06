package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.spi.visibleSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Assume.assumeTrue

/**
 * A crop that a Matroska track states with its `PixelCrop` elements reaches every frame, and the
 * player reports the picture that is left (#497). FFmpeg's decoder never applies such a crop, so
 * without this a 1088-line coded picture showed its eight padding rows. The fixtures are written by
 * the `ffmpeg` command line and given their crop by `mkvpropedit`; the tests skip where either is
 * missing.
 */
class ContainerCropTest {

    @Test
    fun aCodedPictureOf1088LinesShowsItsTenEightyAtSixteenByNine() = withCroppedFixture(
        width = 1920, height = 1088, "pixel-crop-bottom=8",
    ) { file ->
        val seen = play(file)
        assertEquals(VideoSize(1920, 1080), seen.reported, "the player reports the picture that is left")
        assertEquals(16f / 9f, seen.reported!!.displayAspect, "at exactly 16:9")
        assertEquals(VideoSize(1920, 1088), seen.frame.size, "the frame keeps its stored size")
        assertEquals(PictureCrop(bottom = 8), seen.frame.crop)
        assertEquals(VideoSize(1920, 1080), seen.frame.visible)
        assertEquals(VideoSize(1920, 1080), seen.trackSize, "the track list says the same")
    }

    @Test
    fun barsTheFileHidesAreNotPartOfThePicture() = withCroppedFixture(
        width = 1920, height = 1080, "pixel-crop-top=140", "pixel-crop-bottom=140",
    ) { file ->
        val seen = play(file)
        assertEquals(VideoSize(1920, 800), seen.reported)
        assertEquals(PictureCrop(top = 140, bottom = 140), seen.frame.crop)
    }

    @Test
    fun aFileWithNoCropIsShownWhole() = withCroppedFixture(width = 1920, height = 1080) { file ->
        val seen = play(file)
        assertEquals(VideoSize(1920, 1080), seen.reported)
        assertNull(seen.frame.crop)
        assertTrue(seen.warnings.none { it is PlaybackWarning.CropIgnored }, "nothing to warn about: ${seen.warnings}")
    }

    /**
     * FFmpeg refuses a whole Matroska file whose crop leaves nothing of the size its track
     * states, so the case that reaches the player is a track that states a larger picture than
     * the one coded: 200 rows fit the 400 the track claims and leave nothing of the 192 decoded.
     */
    @Test
    fun aCropThatLeavesNothingIsIgnoredWithAWarning() = withCroppedFixture(
        width = 320, height = 192, "pixel-height=400", "pixel-crop-top=100", "pixel-crop-bottom=100",
    ) { file ->
        val seen = play(file)
        assertEquals(VideoSize(320, 192), seen.reported, "the whole picture, rather than none")
        assertNull(seen.frame.crop, "a frame never carries a crop that does not fit it")
        val ignored = seen.warnings.filterIsInstance<PlaybackWarning.CropIgnored>()
        assertEquals(1, ignored.size, "warned once: ${seen.warnings}")
        assertEquals(0, ignored.single().streamIndex)
    }

    private class Seen(
        val reported: VideoSize?,
        val trackSize: VideoSize?,
        val frame: FrameFacts,
        val warnings: List<PlaybackWarning>,
    )

    private class FrameFacts(val size: VideoSize, val crop: PictureCrop?, val visible: VideoSize)

    private fun play(file: File): Seen = runBlocking {
        val recorder = FirstFrameRecorder()
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            player.attachRendererAndAwait(recorder)
            withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath)) }
            val frame = withTimeout(15.seconds) { recorder.first.await() }
            val state = player.state.value
            Seen(
                reported = state.videoSize,
                trackSize = state.tracks.all.firstOrNull { it.videoSize != null }?.videoSize,
                frame = frame,
                warnings = player.warningHistory().map { it.warning },
            )
        } finally {
            player.closeAndAwait()
        }
    }

    /** Remembers the first frame it is handed, before it closes it. */
    private class FirstFrameRecorder : VideoRenderer {
        val first = CompletableDeferred<FrameFacts>()

        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

        override fun supports(format: PlayerPixelFormat): Boolean = true

        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
            frame.use { first.complete(FrameFacts(it.size, it.crop, it.visibleSize)) }
            return true
        }

        override fun vsyncIntervalNanos(): Long? = null

        override fun setViewport(width: Int, height: Int, scale: Float) = Unit

        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit

        override val events: Flow<RendererEvent> = emptyFlow()

        override val outputSize: VideoSize? get() = null

        override fun close() = Unit
    }

    /** A one second Matroska picture of [width] by [height], with [crop] set on its track by `mkvpropedit`. */
    private fun withCroppedFixture(width: Int, height: Int, vararg crop: String, block: (File) -> Unit) {
        val ffmpeg = requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        val propedit = listOf("/usr/bin/mkvpropedit", "/opt/homebrew/bin/mkvpropedit", "/usr/local/bin/mkvpropedit")
            .firstOrNull { File(it).canExecute() }
        assumeTrue("no mkvpropedit on this host", crop.isEmpty() || propedit != null)
        val directory = Files.createTempDirectory("containercrop").toFile()
        try {
            val file = File(directory, "crop.mkv")
            run(
                ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=${width}x$height:r=25:d=1",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", file.absolutePath,
            )
            if (crop.isNotEmpty()) {
                val edits = crop.flatMap { listOf("--set", it) }
                run(propedit!!, file.absolutePath, "--edit", "track:v1", *edits.toTypedArray())
            }
            block(file)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "${command.first()} failed: $log")
    }
}
