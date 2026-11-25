package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A sender that goes quiet in the middle of a stream that FFmpeg reads with its own protocol ends
 * the read with [PlaybackError.SourceStalled], the error a MediaIo reader's stall gives, rather
 * than a generic demuxer failure (#863).
 */
class StalledReadTest {

    @Test
    fun aTcpSenderThatGoesQuietStallsTheRead() = runBlocking {
        val clip = File(formatMatrixMediaDir() ?: error("testmedia missing; run scripts/testmedia.sh"), "tsoffset1400.ts")
        val bytes = clip.readBytes()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val held = mutableListOf<Socket>()
        thread(isDaemon = true, name = "quiet-sender") {
            runCatching {
                val socket = server.accept()
                synchronized(held) { held += socket }
                // Enough for the open to probe the streams and read a few packets, then nothing.
                socket.getOutputStream().apply {
                    write(bytes, 0, minOf(bytes.size, 256 * 1024))
                    flush()
                }
            }
        }
        val item = MediaItem(
            "tcp://127.0.0.1:${server.localPort}",
            openOptions = mapOf("rw_timeout" to "1000000"),
        )
        val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            val failure = try {
                while (true) {
                    val packet = source.readPacket() ?: fail("the stream ended instead of stalling")
                    packet.close()
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } catch (stalled: PlaybackException) {
                stalled
            }
            val error = assertIs<PlaybackError.SourceStalled>(failure?.error, "the quiet sender gave ${failure?.error}")
            assertTrue(error.uri.startsWith("tcp://127.0.0.1"), "the error names ${error.uri}")
            assertTrue(error.stalledFor >= 0.9.seconds, "the read waited ${error.stalledFor}")
        } finally {
            source.close()
            server.close()
            synchronized(held) { held.forEach { runCatching { it.close() } } }
        }
    }
}
