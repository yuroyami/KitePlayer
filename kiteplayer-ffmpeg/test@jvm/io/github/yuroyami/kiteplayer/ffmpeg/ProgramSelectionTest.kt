package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.AudioConfig
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.Generation
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
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
                val first = screen.awaitFrame()
                assertTrue(first.redness < RED_V, "ChannelA's actual picture is not the red one: $first")

                withTimeout(30.seconds) { player.selectProgram(202) }
                val second = screen.awaitFrame(after = first)
                val playing = withTimeout(10.seconds) { player.state.first { it.status == PlaybackStatus.Playing } }
                val switched = playing.tracks
                assertEquals(202, switched.selectedProgram)
                assertEquals(TrackId(2), switched.selectedVideo)
                assertEquals(TrackId(3), switched.selectedAudio)
                assertEquals(PlaybackStatus.Playing, playing.status)
                assertTrue(second.redness > RED_V, "ChannelB's actual red picture shows in a new generation: $second")
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

    /** One accepted picture, published atomically; no sentinel can stand in for a frame. */
    private data class ObservedFrame(
        val count: Long,
        val generation: Generation,
        val format: PlayerPixelFormat,
        val redness: Double,
    )

    /**
     * Samples the left half of the V chroma row, excluding padding. Software H.264 frames are
     * planar YUV420P; VideoToolbox downloads can be NV12, where V is every other byte of plane 1.
     * The frame's planeFormat describes that readable copy even when pixelFormat is Opaque.
     */
    private class RednessScreen : VideoRenderer {
        private val accepted = AtomicLong()

        @Volatile
        private var latest: ObservedFrame? = null

        @Volatile
        private var failure: String? = null

        suspend fun awaitFrame(after: ObservedFrame? = null): ObservedFrame {
            val observed = withTimeoutOrNull(10.seconds) {
                var candidate = latest
                while (candidate == null || (after != null &&
                        (candidate.count <= after.count || candidate.generation <= after.generation))) {
                    failure?.let { throw AssertionError(it) }
                    delay(10.milliseconds)
                    candidate = latest
                }
                failure?.let { throw AssertionError(it) }
                candidate
            }
            return observed ?: throw AssertionError(
                "no actual picture arrived after $after; accepted=${accepted.get()}, latest=$latest, failure=$failure",
            )
        }

        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

        override fun supports(format: PlayerPixelFormat): Boolean =
            format == PlayerPixelFormat.Yuv420p || format == PlayerPixelFormat.Nv12 || format == PlayerPixelFormat.Opaque

        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = frame.use {
            if (frame !is SoftwareReadableFrame) {
                failure = "the test renderer received no readable planes: ${frame.pixelFormat}"
                return false
            }
            val format = frame.planeFormat
            val plane: Int
            val step: Int
            val offset: Int
            when (format) {
                PlayerPixelFormat.Yuv420p -> { plane = 2; step = 1; offset = 0 }
                PlayerPixelFormat.Nv12 -> { plane = 1; step = 2; offset = 1 }
                else -> {
                    failure = "the test renderer cannot sample V from $format (${frame.planeCount} planes)"
                    return false
                }
            }
            val stride = frame.planeStride(plane)
            val rows = frame.planeHeight(plane)
            val v = ByteArray(stride * rows)
            frame.copyPlane(plane, v, 0)
            var sum = 0L
            for (row in 0 until rows) for (column in 0 until SAMPLED_COLUMNS) {
                sum += v[row * stride + column * step + offset].toInt() and 0xFF
            }
            latest = ObservedFrame(
                accepted.incrementAndGet(), frame.generation, format,
                sum.toDouble() / (rows * SAMPLED_COLUMNS),
            )
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
