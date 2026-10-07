package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.StepDirection
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A precise seek skips the frames nothing is predicted from on its way to the target (#468), with
 * real FFmpeg on the JVM: 12 s of H.264 at 30 frames a second with a keyframe at 0 and at 10 s, and
 * three B-frames between references that nothing is built on, so a seek to 9.5 s decodes up from
 * the keyframe at 0. The fixture is written by the `ffmpeg` command line, and the test skips where
 * there is none, as the live tests do.
 */
class SeekRunUpSkipTest {

    @Test
    fun theDecoderSkipsTheRunUpAndGivesTheSamePicturesFromTheTarget() = runBlocking {
        val ffmpeg = requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        withFixture(ffmpeg) { file ->
            val full = runUp(file, skip = false)
            val skipped = runUp(file, skip = true)
            println(
                "run up to 9.5 s: ${full.decodedBelow} frames decoded in ${full.firstAtTargetMs} ms without " +
                    "skipping, ${skipped.decodedBelow} in ${skipped.firstAtTargetMs} ms with it",
            )
            assertEquals(TARGET_INDEX, full.decodedBelow, "without skipping every frame below the target decodes")
            assertTrue(
                skipped.decodedBelow * 2 < full.decodedBelow,
                "the run up skips its B-frames: ${skipped.decodedBelow} of ${full.decodedBelow} decoded",
            )
            assertEquals(full.kept.map { it.first }, skipped.kept.map { it.first }, "the same frames from the target on")
            full.kept.zip(skipped.kept).forEach { (expected, actual) ->
                assertContentEquals(expected.second, actual.second, "the picture at ${expected.first} us")
            }
        }
    }

    @Test
    fun thePlayerLandsOnTheSamePictureAndAStepBackStillLands() = runBlocking {
        val ffmpeg = requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        withFixture(ffmpeg) { file ->
            val reference = runUp(file, skip = false, keepFrom = TARGET_US - 100_000)
            val atTarget = reference.kept.first { it.first >= TARGET_US }
            val before = reference.kept.last { it.first < TARGET_US }

            val recorder = PictureRecorder()
            val player = KitePlayer.create(
                PlayerConfig(
                    backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()),
                    // The run-up oracle uses software decoding, and this test measures its skip work.
                    hardwareDecode = HwdecPolicy.Off,
                    progressInterval = 50.milliseconds,
                    statsInterval = 20.milliseconds,
                ),
            )
            try {
                player.attachRendererAndAwait(recorder)
                withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath)) }
                delay(300.milliseconds)
                val decodedBefore = player.stats.value.decodedVideoFrames
                recorder.pictures.clear()

                player.seek(TARGET_US.microseconds, SeekMode.Precise)
                delay(300.milliseconds)

                val landed = assertNotNull(recorder.pictures.firstOrNull(), "the seek showed a picture")
                assertEquals(atTarget.first, landed.first, "the landing is the frame at the target")
                assertContentEquals(atTarget.second, landed.second, "and the same picture as a decode that skipped nothing")
                val decoded = player.stats.value.decodedVideoFrames - decodedBefore
                assertTrue(decoded < TARGET_INDEX, "the seek decoded $decoded frames, no fewer than the $TARGET_INDEX below its target, so it skipped none")

                recorder.pictures.clear()
                player.stepFrame(StepDirection.Backward)
                delay(300.milliseconds)
                val stepped = assertNotNull(recorder.pictures.lastOrNull(), "the step showed a picture")
                assertEquals(before.first, stepped.first, "the step lands on the frame before the target")
                assertContentEquals(before.second, stepped.second, "and the same picture as a decode that skipped nothing")
            } finally {
                player.closeAndAwait()
            }
        }
    }

    private class RunUp(val decodedBelow: Int, val firstAtTargetMs: Long, val kept: List<Pair<Long, ByteArray>>)

    /**
     * Decodes [file] from its first keyframe up to [KEEP] frames past the target, skipping the frames
     * nothing is built on below the target when [skip] says so, the way the engine asks. Keeps each
     * picture from [keepFrom] on.
     */
    private suspend fun runUp(file: File, skip: Boolean, keepFrom: Long = TARGET_US): RunUp =
        KiteFFmpegMediaBackend().open(MediaItem(file.absolutePath)).use { session ->
            val source = session.source
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Video })
            source.selectStreams(setOf(stream.index))
            source.seekToKeyframe(Pts(TARGET_US))
            val decoder: VideoDecoder = assertNotNull(session.videoDecoders.first().create(stream, HwdecPolicy.Off))
            decoder.use {
                val started = System.nanoTime()
                var firstAtTargetMs = -1L
                var decodedBelow = 0
                val kept = mutableListOf<Pair<Long, ByteArray>>()
                var afterTarget = 0
                fun take(frame: VideoFrame) = frame.use {
                    val us = frame.pts.micros
                    if (us < TARGET_US) decodedBelow++ else {
                        if (firstAtTargetMs < 0) firstAtTargetMs = (System.nanoTime() - started) / 1_000_000
                        afterTarget++
                    }
                    if (us >= keepFrom) kept += us to (frame as SoftwareReadableFrame).pixels()
                }
                while (afterTarget < KEEP) {
                    val packet = assertNotNull(source.readPacket(), "the media ended before the frames past the target")
                    packet.use {
                        val pts = packet.pts?.micros
                        decoder.skipNonReferenceFrames(skip && pts != null && pts < TARGET_US)
                        while (!decoder.send(packet)) take(assertNotNull(decoder.receive(), "a full decoder gave nothing"))
                    }
                    while (true) take(decoder.receive() ?: break)
                }
                RunUp(decodedBelow, firstAtTargetMs, kept.sortedBy { it.first })
            }
        }

    private suspend fun withFixture(ffmpeg: String, block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("seek-run-up").toFile()
        try {
            val file = File(directory, "run-up.mp4")
            val process = ProcessBuilder(
                ffmpeg, "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30:duration=12",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=12",
                "-c:v", "libx264", "-preset", "veryfast", "-bf", "3", "-x264-params", "b-pyramid=none",
                "-g", "300", "-keyint_min", "300", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-c:a", "aac", file.absolutePath,
            ).redirectErrorStream(true).start()
            val log = process.inputStream.readBytes().decodeToString()
            assertEquals(0, process.waitFor(), "ffmpeg could not write the fixture: $log")
            block(file)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** A screen with no window that keeps each picture it is handed, with its time. */
    private class PictureRecorder : VideoRenderer {
        val pictures: MutableList<Pair<Long, ByteArray>> = CopyOnWriteArrayList()

        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

        override fun supports(format: PlayerPixelFormat): Boolean = true

        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = frame.use {
            if (frame !is SoftwareReadableFrame) return false
            pictures += frame.pts.micros to frame.pixels()
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
        /** 9.5 s, the 285th frame at 30 a second, so 285 frames lie below it. */
        const val TARGET_US = 9_500_000L
        const val TARGET_INDEX = 285

        /** Frames compared from the target on. */
        const val KEEP = 8
    }
}

/** Active samples in the oracle's planar format, including every chroma sample and excluding row padding. */
private fun SoftwareReadableFrame.pixels(): ByteArray {
    assertEquals(PlayerPixelFormat.Yuv420p, planeFormat, "the byte oracle requires software-decoded planar YUV420")
    return tightlyPackedPlanes()
}
