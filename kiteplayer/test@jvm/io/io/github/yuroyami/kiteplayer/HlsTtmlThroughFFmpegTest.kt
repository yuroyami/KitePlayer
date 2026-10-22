package io.github.yuroyami.kiteplayer

import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSourceFactory
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TTML subtitles in HLS through the real FFmpeg backend (#439). The `hls/fmp4` fixture is the
 * picture, and an IMSC rendition beside it, which its variant names as `stpp.ttml.im1t`, holds a
 * cue every two seconds in TTML segments. The automatic transport serves the rendition as WebVTT,
 * so FFmpeg lists it and reads its cues at the times the documents name. The same master naming
 * the rendition as WebVTT, with no `stpp`, plays the WebVTT segments as before.
 */
class HlsTtmlThroughFFmpegTest {

    private val media: File = listOfNotNull(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, "hls") },
        File("testmedia/hls"),
        File("../testmedia/hls"),
    ).firstOrNull { File(it, "fmp4.m3u8").isFile }
        ?: error("testmedia/hls is missing; run scripts/testmedia.sh")

    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun master(codecs: String) = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Español",LANGUAGE="es",URI="subs.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360,CODECS="$codecs",SUBTITLES="subs"
        fmp4.m3u8
    """.trimIndent() + "\n"

    /** Six two second segments, each a document of the cue that starts it. */
    private fun subtitles(extension: String) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        for (n in 0 until 6) append("#EXTINF:2.000000,\nsub-$n.$extension\n")
        append("#EXT-X-ENDLIST\n")
    }

    private fun ttml(n: Int) =
        """<?xml version="1.0" encoding="utf-8"?><tt xmlns="http://www.w3.org/ns/ttml"><body><div>""" +
            """<p begin="00:00:${(2 * n).toString().padStart(2, '0')}.000" end="00:00:${(2 * n + 1).toString().padStart(2, '0')}.000">Cue $n<br/>line two</p>""" +
            "</div></body></tt>"

    private fun vtt(n: Int) = "WEBVTT\n\n00:00:${(2 * n).toString().padStart(2, '0')}.000 --> 00:00:${(2 * n + 1).toString().padStart(2, '0')}.000\nCue $n\n\n"

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/")
            asked += path
            val body = when {
                path == "ttml.m3u8" -> master("avc1.42c01e,mp4a.40.2,stpp.ttml.im1t").encodeToByteArray()
                path == "vtt.m3u8" -> master("avc1.42c01e,mp4a.40.2").encodeToByteArray()
                path == "subs.m3u8" -> subtitles(if (asked.any { it == "ttml.m3u8" }) "ttml" else "vtt").encodeToByteArray()
                path.startsWith("sub-") && path.endsWith(".ttml") -> ttml(path.removePrefix("sub-").substringBefore('.').toInt()).encodeToByteArray()
                path.startsWith("sub-") && path.endsWith(".vtt") -> vtt(path.removePrefix("sub-").substringBefore('.').toInt()).encodeToByteArray()
                else -> File(media, path).takeIf { it.isFile && it.parentFile == media }?.readBytes()
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
            val first = range?.substringBefore('-')?.toLong() ?: 0L
            val last = range?.substringAfter('-')?.toLongOrNull()?.coerceAtMost(body.size - 1L) ?: (body.size - 1L)
            val part = body.copyOfRange(first.toInt(), last.toInt() + 1)
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (path.endsWith(".m3u8")) exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${body.size}")
                exchange.sendResponseHeaders(206, part.size.toLong())
            } else {
                exchange.sendResponseHeaders(200, part.size.toLong())
            }
            exchange.responseBody.use { it.write(part) }
        }
        executor = Executors.newCachedThreadPool()
        start()
    }
    private val root = "http://127.0.0.1:${server.address.port}"
    private val client = HttpClient()

    @AfterTest
    fun stop() {
        client.close()
        server.stop(0)
    }

    private fun withAutomaticSource(path: String, test: suspend (PlayerMediaSource) -> Unit) = runBlocking {
        val reader = KtorMediaIoResolver(client).resolve("$root/$path")
        val source = KiteFFmpegSourceFactory().open(MediaItem("$root/$path", io = { checkNotNull(reader) }))
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            test(source)
        } finally {
            source.close()
        }
    }

    /** The subtitle packets of the first [seconds] of picture, as their time in seconds and their text. */
    private suspend fun PlayerMediaSource.subtitlesFor(seconds: Double): List<Pair<Double, String>> {
        val kinds = streams.associate { it.index to it.kind }
        val out = mutableListOf<Pair<Double, String>>()
        var firstVideo = Double.NaN
        while (true) {
            val packet = readPacket() ?: break
            packet.use {
                val at = (it.pts?.micros ?: 0L) / 1e6
                when (kinds[it.streamIndex]) {
                    TrackKind.Video -> {
                        if (firstVideo.isNaN()) firstVideo = at
                        if (at - firstVideo > seconds) return out
                    }
                    TrackKind.Subtitle -> out += at to it.copyBytes().decodeToString()
                    else -> Unit
                }
            }
        }
        return out
    }

    @Test
    fun anImscRenditionPlaysAsSubtitlesAtTheTimesItsDocumentsName() = withAutomaticSource("ttml.m3u8") { source ->
        val subtitle = source.streams.singleOrNull { it.kind == TrackKind.Subtitle }
        assertNotNull(subtitle, "the TTML rendition is not a stream: ${source.streams.map { it.kind to it.codec }}")
        assertEquals("es", subtitle.language)
        val cues = source.subtitlesFor(seconds = 6.0)
        assertTrue(cues.size >= 3, "only $cues arrived in the first 6 s")
        assertTrue(cues.all { (at, _) -> abs(at - 2 * kotlin.math.round(at / 2)) < 0.1 }, "the cues are not at the times the documents name: $cues")
        val (at, text) = cues.first { it.second.startsWith("Cue 1") }
        assertEquals(2.0, at, 0.1)
        assertEquals("Cue 1\nline two", text.trim())
        assertTrue(asked.any { it == "sub-1.ttml" }, "the TTML segment was not read: $asked")
    }

    @Test
    fun aWebVttRenditionPlaysAsBefore() = withAutomaticSource("vtt.m3u8") { source ->
        assertNotNull(source.streams.singleOrNull { it.kind == TrackKind.Subtitle })
        val cues = source.subtitlesFor(seconds = 6.0)
        assertTrue(cues.any { it.second.trim() == "Cue 1" }, "the WebVTT cues did not arrive: $cues")
        assertTrue(asked.any { it == "sub-1.vtt" }, "the WebVTT segment was not read: $asked")
    }
}
