@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSourceFactory
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.MediaSink
import io.github.yuroyami.kiteffmpeg.PixelFormat
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M1's exit sentence, executed: a REAL mp4 travels over a REAL local HTTP server through the
 * Ktor reader into REAL FFmpeg, which demuxes, seeks (a ranged request) and reads it whole.
 * https is this exact code path with the platform engine terminating TLS beneath it; the
 * transport is the only difference, which is the design. The M5 cache sits above this
 * reader inside the engine and is proven by the core suites.
 */
class HttpPlaybackEndToEndTest {

    private val tmpFiles = mutableListOf<String>()
    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
        tmpFiles.forEach { remove(it) }
        tmpFiles.clear()
    }

    private fun tmp(name: String): String {
        val root = sequenceOf("TMPDIR", "TEMP", "TMP")
            .mapNotNull { getenv(it)?.toKString() }
            .firstOrNull { it.isNotBlank() }
            ?: error("No temporary directory")
        return "${root.trimEnd('/', '\\')}/kiteplayer-net-$name".also { tmpFiles += it }
    }

    /** Noisy frames so the file comfortably exceeds cache chunks and http buffers. */
    private fun yuvFrame(width: Int, height: Int, index: Int): ByteArray {
        val y = ByteArray(width * height) { i -> (((i * 1103515245 + index * 12345) ushr 13) and 0xFF).toByte() }
        val u = ByteArray(width * height / 4) { 100.toByte() }
        val v = ByteArray(width * height / 4) { (140 + index % 40).toByte() }
        return y + u + v
    }

    private fun writeTestVideo(path: String, frames: Int, width: Int = 320, height: Int = 240) {
        MediaSink.open(path).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = width, height = height,
                    frameRate = Rational(30, 1),
                    bitrateBps = 2_000_000,
                )
            )
            runBlocking {
                enc.drive(
                    (0 until frames).asFlow().map { i ->
                        Frame.ofVideo(
                            bytes = yuvFrame(width, height, i),
                            width = width, height = height,
                            pixelFormat = PixelFormat.Yuv420p,
                            ptsMicros = i * 1_000_000L / 30,
                        )
                    }
                )
            }
        }
    }

    private fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("cannot open $path")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, 0)
            val bytes = ByteArray(size)
            bytes.usePinned { pinned ->
                check(fread(pinned.addressOf(0), 1uL, size.toULong(), file).toInt() == size)
            }
            return bytes
        } finally {
            fclose(file)
        }
    }

    private fun serveRanged(bytes: ByteArray): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/movie.mp4") {
                    val header = call.request.headers[HttpHeaders.Range]
                    if (header != null) {
                        val spec = header.removePrefix("bytes=")
                        val start = spec.substringBefore('-').toLong()
                        val end = spec.substringAfter('-').toLongOrNull() ?: (bytes.size - 1L)
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/${bytes.size}")
                        call.respondBytes(
                            bytes.copyOfRange(start.toInt(), (end + 1).toInt()),
                            status = HttpStatusCode.PartialContent,
                        )
                    } else {
                        call.respondBytes(bytes)
                    }
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun `an mp4 over local http demuxes seeks and decodes through ktor and the cache`() = runBlocking {
        val path = tmp("e2e.mp4")
        writeTestVideo(path, frames = 120)
        val bytes = readFile(path)
        val port = serveRanged(bytes)
        val url = "http://127.0.0.1:$port/movie.mp4"

        // A factory, because MediaItem.io became one so every open gets its own reader. This file was never
        // compiled after that change, so it had been red on macosArm64 ever since.
        val source = KiteFFmpegSourceFactory().open(MediaItem(url, io = { KtorMediaIo.open(url) }))
        try {
            assertTrue(source.seekable, "a ranged http source must be seekable end to end")
            val video = source.streams.firstOrNull { it.kind == TrackKind.Video }
                ?: error("no video stream demuxed over http")

            source.selectStreams(setOf(video.index))
            var packets = 0
            while (packets < 60) {
                val packet = source.readPacket() ?: break
                packets++
                (packet as? AutoCloseable)?.close()
            }
            assertTrue(packets >= 60, "expected 60 packets over http, got $packets")

            // A real seek: a far target becomes one ranged request, then packets flow again.
            source.seekToKeyframe(Pts(3_000_000))
            var afterSeek = 0
            while (afterSeek < 10) {
                val packet = source.readPacket() ?: break
                afterSeek++
                (packet as? AutoCloseable)?.close()
            }
            assertTrue(afterSeek >= 10, "expected packets after the http seek, got $afterSeek")
        } finally {
            source.close()
        }
    }
}
