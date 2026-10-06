package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.AudioConfig
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One channel of a transport stream multiplex plays with its own sound (#505), with real FFmpeg on
 * the JVM. The fixture has two channels: ChannelA, numbered 101, with a moving test picture and
 * French sound, and ChannelB, numbered 202, with a plain red picture and English sound. English is
 * preferred, so a player that ranked every sound of the file would play ChannelB's beside
 * ChannelA's picture. The fixture is written by the `ffmpeg` command line, and the test skips where
 * there is none, as the live tests do.
 */
class ProgramSelectionTest {

    @Test
    fun theFirstChannelPlaysWithItsOwnSoundAndASwitchChangesBoth() = runBlocking {
        val ffmpeg = requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        withFixture(ffmpeg) { file ->
            withPlayer { player, screen ->
                withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath)) }
                val opened = player.state.value.tracks
                assertEquals(listOf(101, 202), opened.programs.map { it.number })
                assertEquals(listOf("ChannelA", "ChannelB"), opened.programs.map { it.name })
                assertEquals(listOf(TrackId(0), TrackId(1)), opened.programs[0].tracks)
                assertEquals(listOf(TrackId(2), TrackId(3)), opened.programs[1].tracks)
                assertEquals(101, opened.selectedProgram)
                assertEquals(TrackId(0), opened.selectedVideo)
                assertEquals(TrackId(1), opened.selectedAudio, "ChannelA's French sound, not ChannelB's English")

                player.play()
                delay(600.milliseconds)
                assertTrue(screen.lastRedness() < RED_V, "ChannelA's picture is not the red one: ${screen.lastRedness()}")

                withTimeout(30.seconds) { player.selectProgram(202) }
                delay(600.milliseconds)
                val switched = player.state.value.tracks
                assertEquals(202, switched.selectedProgram)
                assertEquals(TrackId(2), switched.selectedVideo)
                assertEquals(TrackId(3), switched.selectedAudio)
                assertEquals(PlaybackStatus.Playing, player.state.value.status)
                assertTrue(screen.lastRedness() > RED_V, "ChannelB's red picture shows: ${screen.lastRedness()}")
            }
        }
    }

    @Test
    fun anItemThatNamesTheSecondChannelOpensOnIt() = runBlocking {
        val ffmpeg = requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        withFixture(ffmpeg) { file ->
            withPlayer { player, _ ->
                withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath, demux = DemuxPolicy(program = 202))) }
                val tracks = player.state.value.tracks
                assertEquals(202, tracks.selectedProgram)
                assertEquals(TrackId(2), tracks.selectedVideo)
                assertEquals(TrackId(3), tracks.selectedAudio)
            }
        }
    }

    private suspend fun withPlayer(block: suspend (KitePlayer, RednessScreen) -> Unit) {
        val screen = RednessScreen()
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()),
                audio = AudioConfig(preferredLanguages = listOf("eng")),
            ),
        )
        try {
            player.attachRendererAndAwait(screen)
            block(player, screen)
        } finally {
            player.closeAndAwait()
        }
    }

    private suspend fun withFixture(ffmpeg: String, block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("programs").toFile()
        try {
            val file = File(directory, "two.ts")
            val process = ProcessBuilder(
                ffmpeg, "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25:duration=8",
                "-f", "lavfi", "-i", "color=c=red:size=160x90:rate=25:duration=8",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=8",
                "-f", "lavfi", "-i", "sine=frequency=880:duration=8",
                "-map", "0:v", "-map", "2:a", "-map", "1:v", "-map", "3:a",
                "-c:v", "libx264", "-preset", "veryfast", "-g", "25", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-metadata:s:a:0", "language=fre", "-metadata:s:a:1", "language=eng",
                "-program", "title=ChannelA:program_num=101:st=0:st=1",
                "-program", "title=ChannelB:program_num=202:st=2:st=3",
                "-f", "mpegts", file.absolutePath,
            ).redirectErrorStream(true).start()
            val log = process.inputStream.readBytes().decodeToString()
            assertEquals(0, process.waitFor(), "ffmpeg could not write the fixture: $log")
            block(file)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * A screen with no window that keeps how red the last picture was: the mean of its V plane over
     * the left half of each row, which the fixture's 80 chroma columns always fill, so the padding
     * past a row's end never counts.
     */
    private class RednessScreen : VideoRenderer {
        @Volatile
        private var redness = -1.0

        fun lastRedness(): Double = redness

        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

        override fun supports(format: PlayerPixelFormat): Boolean = true

        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = frame.use {
            if (frame !is SoftwareReadableFrame || frame.planeCount < 3) return false
            val stride = frame.planeStride(2)
            val rows = frame.planeHeight(2)
            val v = ByteArray(stride * rows)
            frame.copyPlane(2, v, 0)
            var sum = 0L
            for (row in 0 until rows) for (column in 0 until SAMPLED_COLUMNS) sum += v[row * stride + column].toInt() and 0xFF
            redness = sum.toDouble() / (rows * SAMPLED_COLUMNS)
            true
        }

        override fun vsyncIntervalNanos(): Long? = null

        override fun setViewport(width: Int, height: Int, scale: Float) = Unit

        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit

        override val events: Flow<RendererEvent> = emptyFlow()

        override val outputSize: VideoSize? get() = null

        override fun close() = Unit
    }

    private companion object {
        /**
         * A V plane mean above this is the red picture, whose V is 240 in limited range. The
         * moving test picture's reads about 131 in the same columns.
         */
        const val RED_V = 200.0

        const val SAMPLED_COLUMNS = 40
    }
}
