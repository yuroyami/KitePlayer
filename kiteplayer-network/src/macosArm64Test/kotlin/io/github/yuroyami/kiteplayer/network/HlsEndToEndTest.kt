@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSource
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSourceFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HLS over a real local HTTP server, through the Ktor reader and real FFmpeg (#209): the playlist
 * arrives after a redirect, the master playlist keeps one variant, the segments and the key come
 * through the reader's related opens, and a byte range becomes a ranged request. The streams are
 * the `testmedia/hls` fixtures.
 */
@OptIn(ExperimentalAtomicApi::class)
class HlsEndToEndTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val requests = AtomicReference(listOf<String>())

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private fun readFile(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, SEEK_SET)
            val bytes = ByteArray(size)
            if (size > 0) bytes.usePinned { pinned -> check(fread(pinned.addressOf(0), 1uL, size.toULong(), file).toInt() == size) }
            return bytes
        } finally {
            fclose(file)
        }
    }

    /** Serves `testmedia/hls` at `/hls/`, with ranges and each file's media type, and `/start` as a redirect to the master. */
    private fun serve(dir: String): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/start") { call.respondRedirect("/hls/ts.m3u8") }
                get("/hls/{name}") {
                    val name = call.parameters["name"].orEmpty()
                    val range = call.request.headers[HttpHeaders.Range]
                    requests.store(requests.load() + "$name ${range.orEmpty()}".trim())
                    val bytes = readFile("$dir/$name") ?: return@get call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
                    val type = when {
                        name.endsWith(".m3u8") -> ContentType.parse("application/vnd.apple.mpegurl")
                        name.endsWith(".ts") -> ContentType.parse("video/mp2t")
                        name.endsWith(".m4s") -> ContentType.parse("video/iso.segment")
                        else -> ContentType.Application.OctetStream
                    }
                    if (range != null) {
                        val spec = range.removePrefix("bytes=")
                        val start = spec.substringBefore('-').toInt()
                        val end = spec.substringAfter('-').toIntOrNull() ?: (bytes.size - 1)
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/${bytes.size}")
                        call.respondBytes(bytes.copyOfRange(start, end + 1), type, HttpStatusCode.PartialContent)
                    } else {
                        call.respondBytes(bytes, type)
                    }
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    /**
     * Opens [url] through a resolver as the engine does, seeks to [seekTo] microseconds when it is
     * set, reads every packet, and returns the video and audio counts.
     */
    private suspend fun play(url: String, seekTo: Long? = null): Pair<Int, Int> {
        val resolver = KtorMediaIoResolver()
        try {
            val source = KiteFFmpegSourceFactory().open(
                MediaItem(url, io = { checkNotNull(resolver.resolve(url)) }),
            ) as KiteFFmpegSource
            try {
                source.selectStreams(source.streams.map { it.index }.toSet())
                seekTo?.let { source.seekToKeyframe(io.github.yuroyami.kiteplayer.Pts(it)) }
                val kinds = source.streams.associate { it.index to it.kind }
                var video = 0
                var audio = 0
                while (true) {
                    val packet = source.readPacket() ?: break
                    when (kinds[packet.streamIndex]) {
                        TrackKind.Video -> video++
                        TrackKind.Audio -> audio++
                        else -> Unit
                    }
                    packet.close()
                }
                return video to audio
            } finally {
                source.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun withServer(test: suspend (port: Int) -> Unit) = runBlocking {
        val media = getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"
        checkNotNull(readFile("$media/hls/ts.m3u8")) { "testmedia/hls is missing; run scripts/testmedia.sh" }
        test(serve("$media/hls"))
    }

    @Test
    fun aRedirectedMasterPlaylistPlaysOneVariant() = withServer { port ->
        val (video, audio) = play("http://127.0.0.1:$port/start")
        assertTrue(video >= 300, "only $video video packets of 360 arrived")
        assertTrue(audio > 0, "no sound arrived")
        val names = requests.load()
        assertTrue(names.any { it.startsWith("ts-1-5.ts") }, "the last segment of the chosen variant was not read: $names")
        assertTrue(names.none { it.startsWith("ts-0") }, "the other variant was read: $names")
    }

    @Test
    fun anEncryptedStreamDecryptsOverHttp() = withServer { port ->
        val (video, _) = play("http://127.0.0.1:$port/hls/aes.m3u8")
        assertTrue(video >= 200, "only $video video packets arrived")
        assertTrue(requests.load().any { it.startsWith("aes.key") }, "the key was not requested: ${requests.load()}")
    }

    @Test
    fun byteRangeFragmentsPlayAndASeekBecomesARangedRequest() = withServer { port ->
        // FFmpeg reads a run of fragments of one file through one reader, so playing from the start
        // asks for the file from byte 0 only.
        val (video, audio) = play("http://127.0.0.1:$port/hls/fmp4.m3u8")
        assertTrue(video >= 300, "only $video video packets arrived")
        assertTrue(audio > 0, "no sound arrived")
        requests.store(emptyList())
        val (afterSeek, _) = play("http://127.0.0.1:$port/hls/fmp4.m3u8", seekTo = 8_000_000)
        assertTrue(afterSeek in 60..200, "$afterSeek video packets arrived after a seek to 8 s of 12")
        val ranged = requests.load().filter { it.startsWith("fmp4.m4s bytes=") && !it.endsWith("bytes=0-") }
        assertTrue(ranged.isNotEmpty(), "the seek read no fragment with a range: ${requests.load()}")
    }
}
