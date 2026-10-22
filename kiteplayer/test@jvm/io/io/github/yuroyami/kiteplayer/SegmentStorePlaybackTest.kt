package io.github.yuroyami.kiteplayer

import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kiteplayer.network.fileSegmentStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole player with a segment store in its own configuration (#547): real HTTP through the
 * automatic transport, FFmpeg, the engine, a screen that reads the pictures and a sound device on
 * the real clock. A second lifetime over the same directory shows the same pictures from segments
 * the server is never asked for again, and two players share one store.
 */
class SegmentStorePlaybackTest {

    private data class Asked(val path: String, val range: String?)

    /** Serves the HLS fixtures with ranges, an entity tag, and a lifetime of an hour. */
    private class Server(private val media: File) : AutoCloseable {
        val asked = CopyOnWriteArrayList<Asked>()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "segment-store-http").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    val path = exchange.requestURI.path.removePrefix("/")
                    val range = exchange.requestHeaders.getFirst("Range")
                    asked += Asked(path, range)
                    val file = File(media, path)
                    if (!file.isFile || file.parentFile != media) {
                        exchange.sendResponseHeaders(404, -1)
                        return@createContext
                    }
                    val body = file.readBytes()
                    exchange.responseHeaders.add("ETag", "\"${file.name}-${body.size}\"")
                    exchange.responseHeaders.add("Cache-Control", "max-age=3600")
                    if (path.endsWith(".m3u8")) exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                    val first = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $first-${body.lastIndex}/${body.size}")
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, (body.size - first).toLong())
                    // A player that closes stops reading, which is not a failure of the test.
                    runCatching { exchange.responseBody.use { it.write(body, first, body.size - first) } }
                }
            }
            this.executor = this@Server.executor
            start()
        }
        val root = "http://127.0.0.1:${server.address.port}"

        fun take(): List<Asked> = asked.toList().also { asked.clear() }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private val media: File by lazy {
        requireTestMedia(
            sequenceOf(
                System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, "hls") },
                File("testmedia/hls"),
                File("../testmedia/hls"),
            ).filterNotNull().firstOrNull { File(it, "alt.m3u8").isFile },
            "testmedia/hls/alt.m3u8 is missing; run scripts/testmedia.sh",
        )
    }

    /** One player with its screen and sound device. */
    private class Playing(val player: KitePlayer, val backend: ObservedBackend, val output: CapturingOutput, val screen: CapturingScreen)

    /**
     * One lifetime of [count] players that share [store]: each opens the presentation, plays past
     * 1.5 s, seeks to 8 s and plays on, and then everything closes. Returns each player's pictures.
     */
    private suspend fun lifetime(uri: String, store: SegmentStore, count: Int = 1): List<List<Picture>> {
        val playing = List(count) {
            val backend = ObservedBackend()
            val output = CapturingOutput()
            val player = KitePlayer.create(
                PlayerConfig(
                    backends = Backends(backend, output),
                    network = NetworkConfig(segmentStore = store),
                    hardwareDecode = HwdecPolicy.Off,
                    buffer = BufferPolicy(totalDuration = 4.seconds, stallTimeout = 10.seconds),
                    progressInterval = 20.milliseconds,
                ),
            )
            Playing(player, backend, output, CapturingScreen())
        }
        try {
            for (each in playing) {
                withTimeout(15.seconds) {
                    each.player.attachRendererAndAwait(each.screen)
                    each.player.open(MediaItem(uri))
                }
                each.player.play()
            }
            for (each in playing) {
                await("at the start", each) {
                    each.player.position() >= 1500.milliseconds && each.screen.pictures.count { it.ptsMicros in 0L..1_500_000L } >= 10
                }
            }
            for (each in playing) withTimeout(15.seconds) { each.player.seek(8.seconds, SeekMode.Precise) }
            for (each in playing) {
                await("after the seek to 8 s", each) {
                    each.player.position() >= 9.seconds && each.screen.pictures.count { it.ptsMicros in 8_000_000L..9_000_000L } >= 10
                }
            }
            return playing.map { it.screen.pictures.toList() }
        } finally {
            for (each in playing) {
                each.backend.source.get()?.interrupt()
                runCatching { withContext(NonCancellable) { withTimeout(10.seconds) { each.player.closeAndAwait() } } }
                each.output.close()
                each.screen.close()
            }
        }
    }

    private suspend fun await(phase: String, playing: Playing, ready: () -> Boolean) {
        val reached = withTimeoutOrNull(20.seconds) {
            while (true) {
                playing.output.failure.get()?.let { throw AssertionError("the sound device failed $phase", it) }
                check(playing.player.state.value.error == null) { "the player failed $phase: ${playing.player.state.value.error}" }
                if (ready()) break
                delay(10)
            }
            true
        }
        assertTrue(reached == true, "no pictures $phase: ${playing.player.state.value}, ${playing.player.progress.value}")
    }

    private fun openStore(directory: File): SegmentStore =
        assertNotNull(fileSegmentStore(directory.path, maxBytes = 256L * 1024 * 1024), "the directory gave no store")

    /** The checksum of each picture by its time. The same media decodes to the same pictures. */
    private fun List<Picture>.byTime(): Map<Long, Long> = associate { it.ptsMicros to it.checksum }

    private fun assertSamePictures(expected: List<Picture>, actual: List<Picture>, what: String) {
        val first = expected.byTime()
        val second = actual.byTime()
        val shared = first.keys intersect second.keys
        assertTrue(shared.size >= 20, "$what share only ${shared.size} picture times")
        assertEquals(emptyList(), shared.filter { first[it] != second[it] }.sorted(), "$what differ at these times")
    }

    @Test
    fun aSecondLifetimeShowsTheSamePicturesFromSegmentsItNeverAsksFor() = runBlocking {
        val directory = Files.createTempDirectory("kite-segments").toFile()
        try {
            Server(media).use { server ->
                val uri = "${server.root}/alt.m3u8"
                val first = openStore(directory).let { store -> try { lifetime(uri, store).single() } finally { store.close() } }
                val asked = server.take()
                assertTrue(asked.any { it.path == "alt-0-0.ts" } && asked.any { it.path == "alt-1-0.ts" }, "the first lifetime read no segment: $asked")
                assertTrue(directory.walk().any { it.name.endsWith(".span") }, "the first lifetime stored nothing")

                val second = openStore(directory).let { store -> try { lifetime(uri, store).single() } finally { store.close() } }
                val again = server.take()
                assertSamePictures(first, second, "the first lifetime and the one from the store")
                // The playlists are always fetched. A segment that was read is never asked for from its start again.
                for (playlist in listOf("alt.m3u8", "alt-0.m3u8", "alt-1.m3u8")) {
                    assertTrue(again.any { it.path == playlist }, "$playlist was not fetched in the second lifetime: $again")
                }
                val known = asked.map { it.path }.filter { it.endsWith(".ts") }.toSet()
                assertEquals(
                    emptyList(), again.filter { it.path in known && it.range == "bytes=0-" },
                    "a stored segment was downloaded again from its start",
                )
                // The first two seconds were played in both lifetimes, and only the first one fetched them.
                assertEquals(emptyList(), again.filter { it.path == "alt-0-0.ts" || it.path == "alt-1-0.ts" }, "the first segments were asked for again")
                assertTrue(second.count { it.ptsMicros in 0L..1_500_000L } >= 10, "the stored first segment showed no pictures")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun twoPlayersShareOneStoreAndALaterLifetimeReadsWhatTheyStored() = runBlocking {
        val directory = Files.createTempDirectory("kite-segments").toFile()
        try {
            Server(media).use { server ->
                val uri = "${server.root}/alt.m3u8"
                val store = openStore(directory)
                val both = try { lifetime(uri, store, count = 2) } finally { store.close() }
                server.take()
                assertSamePictures(both[0], both[1], "the two players that share the store")

                val later = openStore(directory).let { again -> try { lifetime(uri, again).single() } finally { again.close() } }
                assertSamePictures(both[0], later, "the players that filled the store and the one that read it")
                val again = server.take()
                assertEquals(emptyList(), again.filter { it.path == "alt-0-0.ts" || it.path == "alt-1-0.ts" }, "the first segments were asked for again")
                assertEquals(emptyList(), again.filter { it.path.endsWith(".ts") && it.range == "bytes=0-" }.filter { it.path.matches(Regex("alt-[01]-[0145]\\.ts")) })
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
