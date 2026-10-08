package io.github.yuroyami.kiteplayer

import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSourceFactory
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.github.yuroyami.kiteplayer.network.fileSegmentStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real HLS and DASH presentations read through FFmpeg with a segment store (#547), from a server
 * that counts its requests. Each test reads a presentation with no store, then with a store, then
 * again in a new lifetime: a new client, a new resolver and a new store object over the same
 * directory. The packets of all three must be the same, and the last lifetime must take its media
 * and initialization segments from the store.
 */
class SegmentStoreThroughFFmpegTest {

    /** One request as the server saw it and answered it. */
    private data class Asked(val path: String, val range: String?, val condition: String?, val status: Int) {
        val isMedia: Boolean get() = !path.endsWith(".m3u8") && !path.endsWith(".mpd") && !path.endsWith(".key")
    }

    /**
     * Serves [media] with ranges and an entity tag for each file. With [lifetime] every answer is
     * fresh for an hour, and without it a stored file must be asked about before each use.
     */
    private class Server(private val media: File, private val lifetime: Boolean) : AutoCloseable {
        private val asked = CopyOnWriteArrayList<Asked>()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "segment-store-http").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    val path = exchange.requestURI.path.removePrefix("/")
                    val range = exchange.requestHeaders.getFirst("Range")
                    val condition = exchange.requestHeaders.getFirst("If-None-Match")
                    fun answer(status: Int, length: Long) {
                        asked += Asked(path, range, condition, status)
                        exchange.sendResponseHeaders(status, length)
                    }
                    val file = File(media, path)
                    if (!file.isFile || file.parentFile != media) {
                        answer(404, -1)
                        return@createContext
                    }
                    val body = file.readBytes()
                    val tag = "\"${file.name}-${body.size}\""
                    exchange.responseHeaders.add("ETag", tag)
                    if (lifetime) exchange.responseHeaders.add("Cache-Control", "max-age=3600")
                    when {
                        path.endsWith(".mpd") -> exchange.responseHeaders.add("Content-Type", "application/dash+xml")
                        path.endsWith(".m3u8") -> exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                    }
                    if (condition == tag) {
                        answer(304, -1)
                        return@createContext
                    }
                    val spec = range?.removePrefix("bytes=")
                    val first = spec?.substringBefore('-')?.toIntOrNull() ?: 0
                    val last = spec?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(body.lastIndex) ?: body.lastIndex
                    if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${body.size}")
                    answer(if (range == null) 200 else 206, (last - first + 1).toLong())
                    exchange.responseBody.use { it.write(body, first, last - first + 1) }
                }
            }
            this.executor = this@Server.executor
            start()
        }
        val root = "http://127.0.0.1:${server.address.port}"

        /** The requests since the last call. */
        fun take(): List<Asked> = asked.toList().also { asked.clear() }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun fixtures(folder: String, probe: String): File = requireTestMedia(
        sequenceOf(
            System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, folder) },
            File("testmedia/$folder"),
            File("../testmedia/$folder"),
        ).filterNotNull().firstOrNull { File(it, probe).isFile },
        "testmedia/$folder/$probe is missing; run scripts/testmedia.sh",
    )

    /**
     * One lifetime: reads every picture and sound packet of [uri] through FFmpeg, from the start or
     * from the keyframe at [seekMicros], and closes everything it made. Each packet is told by its
     * stream, its times, its size and a checksum of its bytes.
     */
    private fun read(uri: String, directory: File?, seekMicros: Long? = null): List<String> = runBlocking {
        val store = directory?.let { assertNotNull(fileSegmentStore(it.path, maxBytes = 512L * 1024 * 1024), "the directory gave no store") }
        val client = HttpClient()
        val resolver = KtorMediaIoResolver(client, segmentStore = store)
        try {
            val reader = checkNotNull(resolver.resolve(uri))
            val source = KiteFFmpegSourceFactory().open(MediaItem(uri, io = { reader }))
            try {
                source.selectStreams(
                    source.streams.filter { it.kind == TrackKind.Video || it.kind == TrackKind.Audio }.map { it.index }.toSet(),
                )
                seekMicros?.let { source.seekToKeyframe(Pts(it)) }
                buildList {
                    while (true) {
                        val packet = source.readPacket() ?: break
                        packet.use {
                            val checksum = CRC32().apply { update(it.copyBytes()) }.value
                            add("${it.streamIndex} ${it.pts?.micros} ${it.dts?.micros} ${it.sizeBytes} $checksum")
                        }
                    }
                }
            } finally {
                source.close()
            }
        } finally {
            resolver.close()
            client.close()
            store?.close()
        }
    }

    /** What the three lifetimes of one presentation asked the server for. */
    private class Lifetimes(val plain: List<Asked>, val filling: List<Asked>, val reusing: List<Asked>, val directory: File)

    /**
     * Reads [name] with no store, with a store, and again over the same directory, from
     * [seekMicros] in the last lifetime when it is set. The packets must match the read with no
     * store, and the filling lifetime must not ask the server more than a player with no store asks.
     */
    private fun lifetimes(media: File, name: String, lifetime: Boolean = true, seekMicros: Long? = null, check: (Lifetimes) -> Unit) {
        val directory = Files.createTempDirectory("kite-segments").toFile()
        try {
            Server(media, lifetime).use { server ->
                val uri = "${server.root}/$name"
                val plain = read(uri, directory = null)
                val plainAsked = server.take()
                assertTrue(plain.size > 100, "the presentation gave only ${plain.size} packets")
                assertEquals(plain, read(uri, directory), "the packets differ with a store that is empty")
                val fillingAsked = server.take()
                // A file that is opened again in the same lifetime is already found in the store, so
                // the store may take requests away. It never adds one, and never asks a condition yet.
                assertTrue(
                    fillingAsked.size <= plainAsked.size && fillingAsked.none { it.condition != null },
                    "a store with nothing in it added requests: $fillingAsked instead of $plainAsked",
                )
                assertEquals(plainAsked.map { it.path }.toSet(), fillingAsked.map { it.path }.toSet())
                val expected = if (seekMicros == null) plain else read(uri, directory = null, seekMicros).also { server.take() }
                assertEquals(expected, read(uri, directory, seekMicros), "the packets from the store differ from an uncached read")
                check(Lifetimes(plainAsked, fillingAsked, server.take(), directory))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertOnlyPlaylistsAsked(lifetimes: Lifetimes, vararg playlists: String) {
        assertTrue(lifetimes.filling.any { it.isMedia }, "the first lifetime asked for no segments: ${lifetimes.filling}")
        assertEquals(emptyList(), lifetimes.reusing.filter { it.isMedia }, "a fresh stored segment was asked for again")
        for (playlist in playlists) {
            assertTrue(lifetimes.reusing.any { it.path == playlist && it.condition == null && it.status != 304 }, "$playlist was not fetched again: ${lifetimes.reusing}")
        }
    }

    @Test
    fun hlsWithSeparateSoundReadsItsSegmentsFromTheStoreInALaterLifetime() =
        lifetimes(fixtures("hls", "alt.m3u8"), "alt.m3u8") { assertOnlyPlaylistsAsked(it, "alt.m3u8", "alt-0.m3u8", "alt-1.m3u8") }

    @Test
    fun hlsSegmentsWithNoLifetimeSendOnlyOneValidationEach() =
        lifetimes(fixtures("hls", "alt.m3u8"), "alt.m3u8", lifetime = false) { lifetimes ->
            val media = lifetimes.reusing.filter { it.isMedia }
            val segments = lifetimes.filling.filter { it.isMedia }.map { it.path }.distinct()
            assertTrue(segments.size >= 12, "the first lifetime asked for $segments")
            assertEquals(segments.sorted(), media.map { it.path }.sorted(), "a stale segment must send exactly one request")
            assertTrue(media.all { it.condition != null && it.status == 304 }, "a request was more than a validation: $media")
            // A playlist is never stored, so it is never asked about: it is fetched.
            assertTrue(lifetimes.reusing.filter { !it.isMedia }.all { it.condition == null }, "a playlist was validated: ${lifetimes.reusing}")
        }

    @Test
    fun hlsSeeksToAStoredSegmentAfterReopeningAndReadsTheSamePackets() =
        lifetimes(fixtures("hls", "alt.m3u8"), "alt.m3u8", seekMicros = 8_000_000) { assertOnlyPlaylistsAsked(it, "alt.m3u8") }

    @Test
    fun hlsByteRangesOfOneFileAreStoredAsRangesAndReadAgain() =
        lifetimes(fixtures("hls", "fmp4.m3u8"), "fmp4.m3u8") { lifetimes ->
            assertOnlyPlaylistsAsked(lifetimes, "fmp4.m3u8")
            // FFmpeg reads the initialization through one reader and every fragment through another,
            // so the one file is stored as the ranges that were read, and together they are the file.
            val stored = lifetimes.directory.walk().filter { it.name.endsWith(".span") }.toList()
            assertTrue(stored.size >= 2, "the ranges of the one file were not stored as spans: ${stored.map { it.name }}")
            assertEquals(File(fixtures("hls", "fmp4.m3u8"), "fmp4.m4s").length(), stored.sumOf { it.length() }, "the spans are not the file: ${stored.map { it.name }}")
        }

    @Test
    fun hlsKeysAreFetchedInEveryLifetimeAndNeverStored() {
        val media = fixtures("hls", "aes.m3u8")
        lifetimes(media, "aes.m3u8") { lifetimes ->
            assertOnlyPlaylistsAsked(lifetimes, "aes.m3u8")
            val key = lifetimes.reusing.filter { it.path == "aes.key" }
            assertTrue(key.isNotEmpty() && key.all { it.condition == null && it.status in listOf(200, 206) }, "the key was not fetched: ${lifetimes.reusing}")
            val bytes = File(media, "aes.key").readBytes().toList()
            val holders = lifetimes.directory.walk().filter { it.isFile }.filter { file ->
                file.length() >= bytes.size && file.readBytes().toList().windowed(bytes.size).any { it == bytes }
            }.toList()
            assertEquals(emptyList(), holders, "a key is in a stored file")
        }
    }

    @Test
    fun dashWithSeparateSetsReadsItsSegmentsFromTheStoreInALaterLifetime() =
        lifetimes(fixtures("dash", "separate.mpd"), "separate.mpd") { lifetimes ->
            assertOnlyPlaylistsAsked(lifetimes, "separate.mpd")
            val asked = lifetimes.filling.filter { it.isMedia }.map { it.path }
            assertTrue(asked.any { it.endsWith("-init.m4s") }, "no initialization segment was read: $asked")
            // The picture and the sound are sets of their own, and both were read.
            assertTrue(asked.map { it.substringBeforeLast('-') }.distinct().size >= 2, "one set only was read: $asked")
        }

    @Test
    fun dashSeeksToAStoredSegmentAfterReopeningAndReadsTheSamePackets() =
        lifetimes(fixtures("dash", "separate.mpd"), "separate.mpd", seekMicros = 60_000_000) { assertOnlyPlaylistsAsked(it, "separate.mpd") }

    @Test
    fun dashSegmentsWithNoLifetimeSendOnlyValidations() =
        lifetimes(fixtures("dash", "separate.mpd"), "separate.mpd", lifetime = false) { lifetimes ->
            val media = lifetimes.reusing.filter { it.isMedia }
            assertTrue(media.isNotEmpty() && media.all { it.condition != null && it.status == 304 }, "a request was more than a validation: $media")
            assertTrue(lifetimes.reusing.any { it.path == "separate.mpd" && it.condition == null }, "the manifest was not fetched")
        }

    @Test
    fun dashPeriodsJoinedIntoOnePresentationAreStoredAsTheServerSentThem() =
        lifetimes(fixtures("dash", "periods-ladder.mpd"), "periods-ladder.mpd") { assertOnlyPlaylistsAsked(it, "periods-ladder.mpd") }

    @Test
    fun dashSingleFilesReadByTheirIndexAreStoredAsRanges() =
        lifetimes(fixtures("dash", "webm-ondemand.mpd"), "webm-ondemand.mpd") { assertOnlyPlaylistsAsked(it, "webm-ondemand.mpd") }
}
