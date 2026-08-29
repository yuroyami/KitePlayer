@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSourceFactory
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.MediaSink
import io.github.yuroyami.kiteffmpeg.PixelFormat
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import io.ktor.client.HttpClient
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The adaptive layer's first tier, executed whole: a REAL transport stream is cut into REAL
 * segments behind a REAL local HTTP server, described by an MPD this module's own XML parser
 * reads, fetched by Ktor in plan order, and demuxed by REAL FFmpeg across every segment
 * boundary as one stream. This is the media3 shape D-4's un-parking chose: Kotlin segment
 * logic feeding the decoder, never FFmpeg's dash demuxer.
 */
class DashEndToEndTest {

    private val tmpFiles = mutableListOf<String>()
    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
        tmpFiles.forEach { remove(it) }
        tmpFiles.clear()
    }

    private fun resolvePort(server: EmbeddedServer<*, *>): Int =
        runBlocking { server.engine.resolvedConnectors().first().port }

    /**
     * The server is built in this PLAIN function on purpose: constructed inside the test's
     * suspend body, Ktor 3's embeddedServer captures the calling Job as its parent, and the
     * test's runBlocking then waits forever for a server whose stop only runs in @AfterTest.
     * The http e2e test dodged the same trap by the same shape.
     */
    private fun startDashServer(
        mpd: String,
        segments: List<ByteArray>,
        fetched: MutableList<String>,
    ): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/vod/movie.mpd") { call.respondText(mpd) }
                for (i in 1..4) {
                    get("/vod/seg-$i.ts") {
                        fetched += "seg-$i.ts"
                        call.respondBytes(segments[i - 1])
                    }
                }
            }
        }.start(wait = false)
        servers += server
        return resolvePort(server)
    }

    private fun tmp(name: String): String {
        val root = sequenceOf("TMPDIR", "TEMP", "TMP")
            .mapNotNull { getenv(it)?.toKString() }
            .firstOrNull { it.isNotBlank() }
            ?: error("No temporary directory")
        return "${root.trimEnd('/', '\\')}/kiteplayer-dash-$name".also { tmpFiles += it }
    }

    private fun yuvFrame(width: Int, height: Int, index: Int): ByteArray {
        val y = ByteArray(width * height) { i -> (((i * 1103515245 + index * 12345) ushr 13) and 0xFF).toByte() }
        val u = ByteArray(width * height / 4) { 100.toByte() }
        val v = ByteArray(width * height / 4) { (140 + index % 40).toByte() }
        return y + u + v
    }

    private fun writeTransportStream(path: String, frames: Int): ByteArray {
        MediaSink.open(path).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 320, height = 240,
                    frameRate = Rational(30, 1),
                    bitrateBps = 2_000_000,
                )
            )
            runBlocking {
                enc.drive(
                    (0 until frames).asFlow().map { i ->
                        Frame.ofVideo(
                            bytes = yuvFrame(320, 240, i),
                            width = 320, height = 240,
                            pixelFormat = PixelFormat.Yuv420p,
                            ptsMicros = i * 1_000_000L / 30,
                        )
                    }
                )
            }
        }
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

    @Test
    fun `an mpd of real ts segments plays as one stream across every boundary`() = runBlocking {
        val bytes = writeTransportStream(tmp("dash.ts"), frames = 120)
        // Four segments, each a whole number of 188-byte ts packets: a transport stream is
        // byte-concatenatable exactly at packet boundaries, which is what makes this MPD real.
        val packetSize = 188
        val totalPackets = bytes.size / packetSize
        assertEquals(0, bytes.size % packetSize, "a transport stream is whole 188-byte packets")
        val perSegment = (totalPackets / 4) * packetSize
        val segments = (0 until 4).map { i ->
            val from = i * perSegment
            val to = if (i == 3) bytes.size else (i + 1) * perSegment
            bytes.copyOfRange(from, to)
        }

        val fetched = mutableListOf<String>()
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/mp2t">
                        <SegmentTemplate media="seg-${'$'}Number${'$'}.ts" startNumber="1" timescale="1" duration="1"/>
                        <Representation id="v" bandwidth="2000000" width="320" height="240"/>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()

        val port = startDashServer(mpd, segments, fetched)

        val client = HttpClient()
        val item = Dash.mediaItemFor("http://127.0.0.1:$port/vod/movie.mpd", client)
        val source = KiteFFmpegSourceFactory().open(item)
        try {
            val video = source.streams.firstOrNull { it.kind == TrackKind.Video }
                ?: error("no video stream demuxed from the DASH stream")
            source.selectStreams(setOf(video.index))
            var packets = 0
            var lastPtsMicros = Long.MIN_VALUE
            while (true) {
                val packet = source.readPacket() ?: break
                packets++
                packet.pts?.micros?.let { pts -> if (pts > lastPtsMicros) lastPtsMicros = pts }
                (packet as? AutoCloseable)?.close()
            }
            assertTrue(packets >= 110, "expected ~120 packets across all segments, got $packets")
            assertTrue(
                lastPtsMicros >= 3_000_000L,
                "the last packets must come from the final segment; last pts was ${lastPtsMicros}us",
            )
            assertEquals(
                listOf("seg-1.ts", "seg-2.ts", "seg-3.ts", "seg-4.ts"),
                fetched,
                "the plan must fetch every segment exactly once, in order",
            )
        } finally {
            source.close()
            client.close()
        }
    }
}
