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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What FFmpeg's HLS reader downloads for audio renditions the player does not read (#455). The
 * master names one picture and four audio renditions, each the `hls/alt` sound under its own name.
 * Read with the picture and one sound, the reader downloads the other three only as far as the
 * open's look at them, so the player can leave the sounds nobody hears unread. A sound asked for
 * later, with a seek of the reads, arrives from where the reads went back to.
 */
class HlsAudioRenditionsTest {

    private val media: File = listOfNotNull(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, "hls") },
        File("testmedia/hls"),
        File("../testmedia/hls"),
    ).firstOrNull { File(it, "alt.m3u8").isFile }
        ?: error("testmedia/hls is missing; run scripts/testmedia.sh")

    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val master = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n")
        for (n in 0 until 4) {
            append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"sound\",NAME=\"sound $n\",LANGUAGE=\"l$n\",")
            append(if (n == 0) "DEFAULT=YES," else "DEFAULT=NO,")
            append("URI=\"a$n.m3u8\"\n")
        }
        append("#EXT-X-STREAM-INF:BANDWIDTH=435600,RESOLUTION=320x180,CODECS=\"avc1.42c00d,mp4a.40.2\",AUDIO=\"sound\"\n")
        append("alt-0.m3u8\n")
    }

    /** The sound's playlist under rendition [n], its segments named `a<n>-...` so each request says whose it is. */
    private fun soundPlaylist(n: Int): String =
        File(media, "alt-1.m3u8").readText().lines().joinToString("\n") { if (it.endsWith(".ts")) "a$n-$it" else it }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/")
            asked += path
            val body = when {
                path == "master.m3u8" -> master.encodeToByteArray()
                path.matches(Regex("a[0-3]\\.m3u8")) -> soundPlaylist(path[1].digitToInt()).encodeToByteArray()
                path.matches(Regex("a[0-3]-.*\\.ts")) -> File(media, path.substring(3)).readBytes()
                else -> File(media, path).takeIf { it.isFile && it.parentFile == media }?.readBytes()
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            if (path.endsWith(".m3u8")) exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
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

    private fun segmentsOf(n: Int): List<String> = synchronized(asked) { asked.filter { it.startsWith("a$n-") } }

    private fun withSource(test: suspend (PlayerMediaSource) -> Unit) = runBlocking {
        val reader = KtorMediaIoResolver(client).resolve("$root/master.m3u8")
        val source = KiteFFmpegSourceFactory().open(MediaItem("$root/master.m3u8", io = { checkNotNull(reader) }))
        try {
            test(source)
        } finally {
            source.close()
        }
    }

    /** Reads until a packet of [stream] at or past [seconds] arrives, and answers its time. */
    private suspend fun PlayerMediaSource.readUntil(stream: Int, seconds: Double): Double {
        while (true) {
            val packet = assertNotNull(readPacket(), "the stream ended before $seconds s of stream $stream")
            packet.use {
                val at = (it.pts?.micros ?: 0L) / 1e6
                if (it.streamIndex == stream && at >= seconds) return at
            }
        }
    }

    @Test
    fun theSoundsNotReadAreNotDownloadedPastTheOpensLook() = withSource { source ->
        assertTrue(source.separateAudioRenditions, "the HLS source did not say its sounds are downloads of their own")
        val video = source.streams.first { it.kind == TrackKind.Video }.index
        val sounds = source.streams.filter { it.kind == TrackKind.Audio }
        assertEquals(4, sounds.size, "${source.streams.map { it.kind to it.language }}")
        val heard = sounds.first { it.language == "l0" }.index
        source.selectStreams(setOf(video, heard))
        val afterOpen = (1..3).associateWith { segmentsOf(it).size }
        source.readUntil(video, 10.0)
        assertTrue(segmentsOf(0).size >= 5, "the sound heard was not read: ${segmentsOf(0)}")
        for (n in 1..3) {
            assertEquals(afterOpen.getValue(n), segmentsOf(n).size, "sound $n was downloaded after the open: ${segmentsOf(n)}")
        }
    }

    @Test
    fun aSoundAskedForLaterArrivesFromWhereTheReadsWentBack() = withSource { source ->
        val video = source.streams.first { it.kind == TrackKind.Video }.index
        val sounds = source.streams.filter { it.kind == TrackKind.Audio }
        val first = sounds.first { it.language == "l0" }.index
        val later = sounds.first { it.language == "l2" }.index
        source.selectStreams(setOf(video, first))
        source.readUntil(video, 8.0)
        // As the engine's switch does: the new sound joins the reads, which go back to the moment playing.
        source.selectStreams(setOf(video, first, later))
        source.seekToKeyframe(Pts(3_000_000))
        val arrived = source.readUntil(later, 0.0)
        assertTrue(arrived in 1.5..4.0, "the new sound arrived at $arrived s, not where the reads went back to")
        assertTrue(segmentsOf(2).isNotEmpty(), "the new sound was never downloaded")
    }
}
