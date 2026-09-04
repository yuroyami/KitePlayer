@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The FFmpeg backend under the seek sequence, on real threads, with a real clip.
 *
 * The engine's own real-thread stress test lives in `kiteplayer-core` and drives the whole session core;
 * this one is the half that only this module can run, because only this module links FFmpeg and can reach
 * the test media. What it hammers is the part of a seek that touches native state: a demuxer cursor moved
 * from one thread while decoders confined to two others are flushed to a new epoch, over and over, then
 * closed while frames are still in flight.
 *
 * The order below is the engine's own: flush each decoder on the thread that
 * owns it, with the new generation, then move the cursor, then read again. Doing it in any other order
 * against real libavcodec state is how a player ends up decoding a frame from before the seek and showing
 * it after.
 */
@OptIn(ExperimentalForeignApi::class)
class BackendSeekStressTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    @Test
    fun `seeking and closing the real backend on real threads keeps every timestamp honest`() = runBlocking {
        val demuxContext = newSingleThreadContext("stress-ffmpeg-demux")
        val videoContext = newSingleThreadContext("stress-ffmpeg-video")
        val audioContext = newSingleThreadContext("stress-ffmpeg-audio")
        val random = Random(11)

        val backend = KiteFFmpegMediaBackend()
        val session: BackendSession = withContext(demuxContext) {
            backend.open(MediaItem("$mediaDir/sync1080p30.mp4"))
        }
        var epoch = Generation.Initial
        var videoDecoder: VideoDecoder? = null
        var audioDecoder: AudioDecoder? = null
        var framesDecoded = 0
        var buffersDecoded = 0

        try {
            val source = session.source
            val duration = assertNotNull(source.duration, "the fixture declares a duration")
            val video = assertNotNull(
                source.streams.firstOrNull { it.kind == io.github.yuroyami.kiteplayer.TrackKind.Video },
                "the fixture has a video stream",
            )
            val audio = assertNotNull(
                source.streams.firstOrNull { it.kind == io.github.yuroyami.kiteplayer.TrackKind.Audio },
                "the fixture has an audio stream",
            )
            assertTrue(source.seekable, "the fixture is seekable, which is what makes this test possible")

            videoDecoder = withContext(videoContext) {
                assertNotNull(session.videoDecoders.first().create(video, HwdecPolicy.Auto))
            }
            audioDecoder = withContext(audioContext) {
                assertNotNull(session.audioDecoders.first().create(audio))
            }
            withContext(demuxContext) { source.selectStreams(setOf(video.index, audio.index)) }

            repeat(SEEKS) { attempt ->
                // Read a little first, so every seek lands on a pipeline that has real state in it: packets
                // read, frames buffered inside libavcodec, timestamps already handed out.
                val decoded = withContext(demuxContext) {
                    pump(source, video.index, audio.index, videoContext, audioContext, videoDecoder, audioDecoder)
                }
                framesDecoded += decoded.frames
                buffersDecoded += decoded.buffers

                val target = Pts(random.nextLong(0, duration.micros))
                epoch = epoch.next()

                // The seek order, on the threads that own each piece.
                withContext(videoContext) { videoDecoder.flush(epoch) }
                withContext(audioContext) { audioDecoder.flush(epoch) }
                withContext(demuxContext) { source.seekToKeyframe(target) }

                // The first frame of the new epoch has to carry the new epoch and land at or before the
                // target, because a keyframe seek is documented to go backwards, never forwards.
                val landing = withContext(demuxContext) {
                    firstVideoFrameAfterSeek(source, video.index, videoContext, videoDecoder)
                }
                assertNotNull(landing, "attempt $attempt decoded nothing after seeking to $target")
                assertEquals(
                    epoch,
                    landing.generation,
                    "a frame that came out after the flush carried the old epoch, which means a frame " +
                        "decoded before the seek was relabelled rather than dropped",
                )
                assertTrue(
                    landing.pts.micros <= target.micros + ONE_FRAME_US,
                    "attempt $attempt asked for ${target.micros} us and landed at ${landing.pts.micros} us, " +
                        "which is past it: a keyframe seek must land at or before its target",
                )
                assertTrue(
                    landing.pts.micros >= 0,
                    "attempt $attempt landed at ${landing.pts.micros} us, and the engine's timeline starts " +
                        "at zero",
                )
            }

            assertTrue(
                framesDecoded > SEEKS && buffersDecoded > SEEKS,
                "the run has to have decoded real media: $framesDecoded frames and $buffersDecoded buffers",
            )
        } finally {
            // Closed on the threads that own them, in the order the engine closes them: decoders first, so
            // nothing is using the demuxer's context when it goes.
            videoDecoder?.let { withContext(videoContext) { it.close() } }
            audioDecoder?.let { withContext(audioContext) { it.close() } }
            withContext(demuxContext) { session.close() }
            demuxContext.close()
            videoContext.close()
            audioContext.close()
        }
    }

    private class Decoded(val frames: Int, val buffers: Int)

    /**
     * Reads a handful of packets and decodes what comes out, closing everything.
     *
     * A refused packet is offered again after draining rather than dropped, which is the canonical loop:
     * a decoder that accepts nothing and produces nothing has no legal state to be in.
     */
    private suspend fun pump(
        source: io.github.yuroyami.kiteplayer.spi.PlayerMediaSource,
        videoIndex: Int,
        audioIndex: Int,
        videoContext: kotlin.coroutines.CoroutineContext,
        audioContext: kotlin.coroutines.CoroutineContext,
        videoDecoder: VideoDecoder,
        audioDecoder: AudioDecoder,
    ): Decoded {
        var frames = 0
        var buffers = 0
        repeat(PACKETS_PER_ROUND) {
            val packet = source.readPacket() ?: return Decoded(frames, buffers)
            try {
                when (packet.streamIndex) {
                    videoIndex -> frames += withContext(videoContext) { feedVideo(videoDecoder, packet) }
                    audioIndex -> buffers += withContext(audioContext) { feedAudio(audioDecoder, packet) }
                }
            } finally {
                packet.close()
            }
        }
        return Decoded(frames, buffers)
    }

    private suspend fun feedVideo(decoder: VideoDecoder, packet: PlayerPacket): Int {
        var count = 0
        while (!decoder.send(packet)) {
            val frame = decoder.receive() ?: refusedNothing()
            frame.close()
            count++
        }
        while (true) {
            val frame = decoder.receive() ?: break
            frame.close()
            count++
        }
        return count
    }

    private suspend fun feedAudio(decoder: AudioDecoder, packet: PlayerPacket): Int {
        var count = 0
        while (!decoder.send(packet)) {
            val buffer = decoder.receive() ?: refusedNothing()
            buffer.close()
            count++
        }
        while (true) {
            val buffer = decoder.receive() ?: break
            buffer.close()
            count++
        }
        return count
    }

    /** The first video frame the pipeline produces after a seek, with everything else released. */
    private suspend fun firstVideoFrameAfterSeek(
        source: io.github.yuroyami.kiteplayer.spi.PlayerMediaSource,
        videoIndex: Int,
        videoContext: kotlin.coroutines.CoroutineContext,
        decoder: VideoDecoder,
    ): Landing? {
        repeat(PACKETS_PER_ROUND * 4) {
            val packet = source.readPacket() ?: return null
            if (packet.streamIndex != videoIndex) {
                packet.close()
                return@repeat
            }
            val landing = try {
                withContext(videoContext) {
                    var found: Landing? = null
                    while (!decoder.send(packet)) {
                        val frame = decoder.receive() ?: refusedNothing()
                        if (found == null) found = Landing(frame.pts, frame.generation)
                        frame.close()
                    }
                    if (found == null) {
                        val frame = decoder.receive()
                        if (frame != null) {
                            found = Landing(frame.pts, frame.generation)
                            frame.close()
                        }
                    }
                    found
                }
            } finally {
                packet.close()
            }
            if (landing != null) return landing
        }
        return null
    }

    private class Landing(val pts: Pts, val generation: Generation)

    private fun refusedNothing(): Nothing =
        error("the decoder refused a packet and produced nothing; this violates the codec contract")

    private companion object {
        /** Seeks per run. Enough that every keyframe interval of the fixture is hit more than once. */
        const val SEEKS = 30

        /** Packets read between seeks, so each one lands on a pipeline with real state in it. */
        const val PACKETS_PER_ROUND = 24

        /** One frame of the 30 fps fixture, which is the tolerance a landing gets. */
        const val ONE_FRAME_US = 33_334L
    }
}
