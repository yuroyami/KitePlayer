package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SubtitleConfig
import io.github.yuroyami.kiteplayer.TrackChange
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The forced pictures of a Blu-ray subtitle track through the whole player, with real FFmpeg on
 * the JVM (#513). The film is a black picture with English sound and one English Blu-ray track
 * whose pictures, told apart by where they sit, come one a second: an ordinary one at 1 s, a forced
 * one at 2 s, a forced one beside an ordinary one at 3 s, an ordinary one at 4 s, and a clear at 5 s.
 * The fixture is muxed into Matroska by the `ffmpeg` command line, and the test skips where there
 * is none, as the live tests do.
 */
class ForcedPicturesPlaybackTest {

    @Test
    fun theSelectedTrackDrawsEveryPicture() = runBlocking {
        val drawn = drawnPictures(SubtitleConfig(), select = true) ?: return@runBlocking
        assertEquals(setOf(100, 200, 300, 600, 400), drawn)
    }

    @Test
    fun forcedOnlyDrawsTheForcedPicturesOfTheSelectedTrack() = runBlocking {
        val drawn = drawnPictures(SubtitleConfig(forcedPicturesOnly = true), select = true) ?: return@runBlocking
        assertEquals(setOf(200, 300), drawn, "the ordinary pictures drew, or a forced one did not")
    }

    @Test
    fun withSubtitlesOffTheForcedPicturesOfTheSoundsLanguageDraw() = runBlocking {
        val drawn = drawnPictures(SubtitleConfig(autoSelect = false, forcedPicturesWhenOff = true), select = false)
            ?: return@runBlocking
        assertEquals(setOf(200, 300), drawn, "the forced pictures did not draw by themselves with subtitles off")
    }

    @Test
    fun withSubtitlesOffNothingDrawsByDefault() = runBlocking {
        val drawn = drawnPictures(SubtitleConfig(autoSelect = false), select = false) ?: return@runBlocking
        assertEquals(emptySet(), drawn)
    }

    /**
     * Plays the film to its clear under [subtitles], choosing its subtitle track first when
     * [select] is set and checking that none is chosen otherwise, and gives where every picture
     * that drew sat. Null when there is no `ffmpeg` to make the film.
     */
    private suspend fun drawnPictures(subtitles: SubtitleConfig, select: Boolean): Set<Int>? {
        val ffmpeg = requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        val directory = Files.createTempDirectory("forcedpictures").toFile()
        try {
            val film = makeFilm(ffmpeg, directory)
            val player = KitePlayer.create(
                PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()), subtitles = subtitles),
            )
            try {
                player.attachRendererAndAwait(QuietScreen())
                withTimeout(30.seconds) { player.open(MediaItem(film.absolutePath)) }
                val track = player.state.value.tracks.subtitles.single()
                assertEquals("hdmv_pgs_subtitle", track.codec)
                if (select) {
                    assertIs<TrackChange.Applied>(withTimeout(10.seconds) { player.selectTrack(TrackKind.Subtitle, track.id) })
                } else {
                    assertEquals(null, player.state.value.tracks.selectedSubtitle)
                }
                val drawn = ConcurrentHashMap.newKeySet<Int>()
                watchingPictures(player, drawn) {
                    player.play()
                    withTimeout(20.seconds) {
                        while (player.position() < 5_300.milliseconds) delay(50.milliseconds)
                    }
                }
                if (!select) assertEquals(null, player.state.value.tracks.selectedSubtitle, "the forced pictures became a selection")
                return drawn.toSet()
            } finally {
                player.closeAndAwait()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Runs [block] while every picture [player] draws adds where it sits to [drawn]. */
    private suspend fun watchingPictures(player: KitePlayer, drawn: MutableSet<Int>, block: suspend () -> Unit) =
        kotlinx.coroutines.coroutineScope {
            val watcher = launch {
                player.subtitleCues.collect { cues ->
                    cues.filterIsInstance<SubtitleCue.Bitmap>().forEach { cue -> cue.regions.forEach { drawn += it.x } }
                }
            }
            try {
                block()
            } finally {
                watcher.cancel()
            }
        }

    private fun makeFilm(ffmpeg: String, directory: File): File {
        val captions = File(directory, "captions.sup")
        captions.writeBytes(
            bluRaySubtitles(
                1 to listOf(PgsCaption(100, 900, forced = false)),
                2 to listOf(PgsCaption(200, 900, forced = true)),
                3 to listOf(PgsCaption(300, 100, forced = true), PgsCaption(600, 900, forced = false)),
                4 to listOf(PgsCaption(400, 900, forced = false)),
                5 to emptyList(),
            ),
        )
        val film = File(directory, "film.mkv")
        val process = ProcessBuilder(
            ffmpeg, "-v", "error", "-y",
            "-f", "lavfi", "-i", "color=c=black:s=320x180:r=25:d=7",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=7",
            "-i", captions.absolutePath,
            "-map", "0:v", "-map", "1:a", "-map", "2:s",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-c:s", "copy",
            "-metadata:s:a:0", "language=eng", "-metadata:s:s:0", "language=eng",
            film.absolutePath,
        ).redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "ffmpeg could not write the film: $log")
        return film
    }

    /** A screen with no window that takes every picture and draws nothing. */
    private class QuietScreen : VideoRenderer {
        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

        override fun supports(format: PlayerPixelFormat): Boolean = true

        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
            frame.close()
            return true
        }

        override fun vsyncIntervalNanos(): Long? = null

        override fun setViewport(width: Int, height: Int, scale: Float) = Unit

        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit

        override val events: Flow<RendererEvent> = emptyFlow()

        override val outputSize: VideoSize? get() = null

        override fun close() = Unit
    }
}
