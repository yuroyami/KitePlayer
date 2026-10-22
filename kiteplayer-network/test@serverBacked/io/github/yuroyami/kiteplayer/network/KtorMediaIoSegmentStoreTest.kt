package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.SegmentStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The HTTP reader with a segment store (#547), against a server that counts its requests: what is
 * stored, when a stored segment answers with no request, when the server is asked whether it
 * changed, and what never reaches the store.
 */
@OptIn(ExperimentalAtomicApi::class)
class KtorMediaIoSegmentStoreTest {

    /** One request as the server saw it. */
    private data class Seen(
        val path: String,
        val range: String? = "bytes=0-",
        val ifNoneMatch: String? = null,
        val ifModifiedSince: String? = null,
        val ifRange: String? = null,
    )

    /** What the server answers for one path. [body] may depend on the request. */
    private class Served(
        val headers: Map<String, String> = emptyMap(),
        val ranges: Boolean = true,
        val type: String = "video/mp2t",
        val redirect: String? = null,
        /** False for a server that answers a range whatever `If-Range` says. */
        val honoursIfRange: Boolean = true,
        val body: (ApplicationCall) -> ByteArray,
    )

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val served = AtomicReference(mapOf<String, Served>())
    private val log = AtomicReference(listOf<Seen>())
    private val files = MemoryStoreFiles()
    private var now = 1_000_000_000L
    private val quick = HttpReaderPolicy(initialBackoff = 10.milliseconds, maxBackoff = 20.milliseconds, maxReconnects = 1)
    private var port = 0

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private fun bytes(count: Int, seed: Int): ByteArray = ByteArray(count) { (it * 7 + seed).toByte() }

    private fun serve(path: String, answer: Served) {
        served.store(served.load() + (path to answer))
    }

    private fun serve(path: String, body: ByteArray, headers: Map<String, String>, ranges: Boolean = true) =
        serve(path, Served(headers, ranges) { body })

    private fun start() {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/{path...}") {
                    val request = call.request
                    val path = request.path()
                    log.store(
                        log.load() + Seen(
                            path, request.headers[HttpHeaders.Range], request.headers[HttpHeaders.IfNoneMatch],
                            request.headers[HttpHeaders.IfModifiedSince], request.headers[HttpHeaders.IfRange],
                        ),
                    )
                    val answer = served.load()[path]
                    if (answer == null) {
                        call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
                        return@get
                    }
                    if (answer.redirect != null) {
                        call.respondRedirect(answer.redirect)
                        return@get
                    }
                    answer.headers.forEach { (name, value) -> call.response.header(name, value) }
                    val tag = answer.headers[HttpHeaders.ETag]
                    val modified = answer.headers[HttpHeaders.LastModified]
                    val unchanged = (tag != null && request.headers[HttpHeaders.IfNoneMatch] == tag) ||
                        (modified != null && request.headers[HttpHeaders.IfModifiedSince] == modified)
                    if (unchanged) {
                        call.respondBytes(ByteArray(0), status = HttpStatusCode.NotModified)
                        return@get
                    }
                    val body = answer.body(call)
                    val type = ContentType.parse(answer.type)
                    val from = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
                    val sameFile = !answer.honoursIfRange || request.headers[HttpHeaders.IfRange].let { it == null || it == tag || it == modified }
                    if (from == null || !answer.ranges || !sameFile) {
                        call.respondBytes(body, type)
                    } else {
                        call.response.header(HttpHeaders.ContentRange, "bytes $from-${body.size - 1}/${body.size}")
                        call.respondBytes(body.copyOfRange(from, body.size), type, HttpStatusCode.PartialContent)
                    }
                }
            }
        }.start(wait = false)
        servers += server
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun requests(path: String): List<Seen> = log.load().filter { it.path == path }

    private fun newStore(maxBytes: Long = 1_000_000, namespace: String = "", private: Boolean = false): SegmentStore =
        assertNotNull(directorySegmentStore(files, "/cache", maxBytes, namespace, private))

    /** A finished playlist at `/list.m3u8` that names [segments], and a key when [key] is set. */
    private fun playlist(vararg segments: String, ended: Boolean = true, key: String? = null) {
        val text = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n")
            key?.let { append("#EXT-X-KEY:METHOD=AES-128,URI=\"$it\"\n") }
            for (segment in segments) append("#EXTINF:4,\n$segment\n")
            if (ended) append("#EXT-X-ENDLIST\n")
        }
        serve("/list.m3u8", Served(type = "application/vnd.apple.mpegurl") { text.encodeToByteArray() })
    }

    private suspend fun readAll(io: MediaIo, limit: Int = Int.MAX_VALUE): ByteArray {
        var out = ByteArray(0)
        val chunk = ByteArray(4_096)
        while (out.size < limit) {
            val read = io.read(chunk, 0, minOf(chunk.size, limit - out.size))
            if (read < 0) break
            out += chunk.copyOf(read)
        }
        return out
    }

    /**
     * One player lifetime: opens the playlist as an item, reads it, as the backend does, and runs
     * [block] with the item's reader. [store] is null for a player with no store.
     */
    private suspend fun <T> play(
        store: SegmentStore?,
        headers: Map<String, String> = emptyMap(),
        meter: DownloadMeter = DownloadMeter(),
        warnings: MutableList<PlaybackWarning> = mutableListOf(),
        block: suspend (KtorMediaIo) -> T,
    ): T {
        val reuse = store?.let { SegmentReuse(it) { now } }
        val root = KtorMediaIo.open(url("/list.m3u8"), null, headers, quick, redirects = null, meter = meter, reuse = reuse)
        root.setWarningSink { warnings += it }
        try {
            readAll(root)
            return block(root)
        } finally {
            root.close()
        }
    }

    private suspend fun KtorMediaIo.segment(path: String): ByteArray = assertNotNull(openRelated(url(path))).use { readAll(it) }

    @Test
    fun aFreshSegmentIsReadFromTheStoreWithNoRequestInALaterLifetime() = runBlocking {
        start()
        val body = bytes(50_000, seed = 1)
        playlist("seg1.ts")
        serve("/seg1.ts", body, mapOf(HttpHeaders.CacheControl to "max-age=3600", HttpHeaders.ETag to "\"v1\""))

        val first = newStore()
        assertContentEquals(body, play(first) { it.segment("/seg1.ts") })
        assertEquals(50_000L, first.sizeBytes)
        first.close()
        assertEquals(1, requests("/seg1.ts").size)

        // A new store object over the same directory, a new reader, a new client.
        val second = newStore()
        val meter = DownloadMeter()
        play(second, meter = meter) { root -> assertContentEquals(body, root.segment("/seg1.ts")) }
        val playlistBytes = meter.measuredBytes()
        assertEquals(1, requests("/seg1.ts").size, "a fresh stored segment sent a request")
        assertEquals(2, requests("/list.m3u8").size, "the playlist was not fetched again")
        assertTrue(playlistBytes < 1_000, "bytes from the store reached the network measure: $playlistBytes")
        second.close()
    }

    @Test
    fun aStaleSegmentAsksOnceAndA304KeepsItsBytes() = runBlocking {
        start()
        val body = bytes(20_000, seed = 2)
        playlist("seg1.ts")
        // An entity tag and no lifetime: stored, and never fresh.
        serve("/seg1.ts", body, mapOf(HttpHeaders.ETag to "\"v1\""))
        val store = newStore()
        assertContentEquals(body, play(store) { it.segment("/seg1.ts") })
        val meter = DownloadMeter()
        assertContentEquals(body, play(store, meter = meter) { it.segment("/seg1.ts") })
        assertEquals(
            listOf(Seen("/seg1.ts"), Seen("/seg1.ts", ifNoneMatch = "\"v1\"")),
            requests("/seg1.ts"),
            "a stale segment must send exactly one conditional request",
        )
        assertTrue(meter.measuredBytes() < 1_000, "the stored bytes were downloaded again: ${meter.measuredBytes()}")
        store.close()
    }

    @Test
    fun aSegmentWithOnlyADateOfChangeIsFreshForATenthOfItsAgeAndThenAsks() = runBlocking {
        start()
        val body = bytes(8_000, seed = 3)
        playlist("seg1.ts")
        // The response says it was made at the device's time, 1000 s after the file last changed.
        serve(
            "/seg1.ts", body,
            mapOf(HttpHeaders.LastModified to "Sun, 09 Sep 2001 01:30:00 GMT", HttpHeaders.Date to "Sun, 09 Sep 2001 01:46:40 GMT"),
        )
        val store = newStore()
        play(store) { it.segment("/seg1.ts") }
        now += 99
        assertContentEquals(body, play(store) { it.segment("/seg1.ts") })
        assertEquals(1, requests("/seg1.ts").size, "the segment was asked for within its heuristic lifetime")
        now += 1
        assertContentEquals(body, play(store) { it.segment("/seg1.ts") })
        assertEquals(Seen("/seg1.ts", ifModifiedSince = "Sun, 09 Sep 2001 01:30:00 GMT"), requests("/seg1.ts").last())
        assertEquals(2, requests("/seg1.ts").size)
        store.close()
    }

    @Test
    fun aChangedEntityReplacesEveryStoredRangeAndNeverMixesWithTheOld() = runBlocking {
        start()
        val old = bytes(30_000, seed = 4)
        val new = bytes(30_000, seed = 5)
        playlist("seg1.ts")
        serve("/seg1.ts", old, mapOf(HttpHeaders.ETag to "\"old\""))
        val store = newStore()
        // Only the first part is read, so the store holds a range of the old entity.
        play(store) { root -> assertNotNull(root.openRelated(url("/seg1.ts"))).use { readAll(it, limit = 10_000) } }
        assertEquals(10_000L, store.sizeBytes)

        serve("/seg1.ts", new, mapOf(HttpHeaders.ETag to "\"new\""))
        assertContentEquals(new, play(store) { it.segment("/seg1.ts") }, "old and new bytes were mixed")
        assertEquals(30_000L, store.sizeBytes, "the old range stayed beside the new entity")

        assertContentEquals(new, play(store) { it.segment("/seg1.ts") })
        assertEquals(Seen("/seg1.ts", ifNoneMatch = "\"new\""), requests("/seg1.ts").last())

        // The whole entity is stored now. Only the start of the next one is read before the reader closes.
        val newest = bytes(30_000, seed = 9)
        serve("/seg1.ts", newest, mapOf(HttpHeaders.ETag to "\"newest\""))
        play(store) { root -> assertNotNull(root.openRelated(url("/seg1.ts"))).use { readAll(it, limit = 10_000) } }
        assertEquals(10_000L, store.sizeBytes, "the whole of the entity before stayed under the new one")
        assertContentEquals(newest, play(store) { it.segment("/seg1.ts") }, "the rest came from the entity before")
        store.close()
    }

    @Test
    fun aChangeFoundInTheMiddleOfAFreshEntryFailsTheReadAndRemovesTheEntry() = runBlocking {
        start()
        val old = bytes(30_000, seed = 6)
        val new = bytes(30_000, seed = 7)
        val fresh = mapOf(HttpHeaders.CacheControl to "max-age=3600")
        playlist("seg1.ts")
        serve("/seg1.ts", old, fresh + (HttpHeaders.ETag to "\"old\""))
        val store = newStore()
        play(store) { root -> assertNotNull(root.openRelated(url("/seg1.ts"))).use { readAll(it, limit = 10_000) } }

        serve("/seg1.ts", new, fresh + (HttpHeaders.ETag to "\"new\""))
        // The stored start is still fresh, and the rest must come from the server, which has another file now.
        assertFailsWith<KtorMediaIoException> { play(store) { it.segment("/seg1.ts") } }
        assertEquals(Seen("/seg1.ts", range = "bytes=10000-", ifRange = "\"old\""), requests("/seg1.ts").last())
        assertEquals(0L, store.sizeBytes, "the ranges of the old entity stayed")

        assertContentEquals(new, play(store) { it.segment("/seg1.ts") })
        store.close()
    }

    @Test
    fun aChangedDateOfChangeOnARangeRemovesAnEntryThatHasNoEntityTag() = runBlocking {
        start()
        val old = bytes(30_000, seed = 23)
        val new = bytes(30_000, seed = 24)
        val fresh = mapOf(HttpHeaders.CacheControl to "max-age=3600")
        playlist("seg1.ts")
        serve("/seg1.ts", old, fresh + (HttpHeaders.LastModified to "Mon, 01 Jan 2024 00:00:00 GMT"))
        val store = newStore()
        play(store) { root -> assertNotNull(root.openRelated(url("/seg1.ts"))).use { readAll(it, limit = 10_000) } }
        assertEquals(10_000L, store.sizeBytes)

        // This server answers the range of the new file, whatever the request's If-Range says.
        val changed = fresh + (HttpHeaders.LastModified to "Tue, 02 Jan 2024 00:00:00 GMT")
        serve("/seg1.ts", Served(changed, honoursIfRange = false) { new })
        assertFailsWith<KtorMediaIoException> { play(store) { it.segment("/seg1.ts") } }
        assertEquals(Seen("/seg1.ts", range = "bytes=10000-", ifRange = "Mon, 01 Jan 2024 00:00:00 GMT"), requests("/seg1.ts").last())
        assertEquals(0L, store.sizeBytes, "the range of the old file stayed")

        assertContentEquals(new, play(store) { it.segment("/seg1.ts") })
        store.close()
    }

    @Test
    fun aRangeOfASegmentIsStoredAndReadAgainAsARange() = runBlocking {
        start()
        val body = bytes(40_000, seed = 8)
        playlist("media.mp4")
        serve("/media.mp4", body, mapOf(HttpHeaders.CacheControl to "max-age=3600", HttpHeaders.ETag to "\"v1\""))
        suspend fun KtorMediaIo.range(from: Int, count: Int): ByteArray = assertNotNull(openRelated(url("/media.mp4"))).use { io ->
            io.seek(from.toLong())
            readAll(io, limit = count)
        }
        val store = newStore()
        assertContentEquals(body.copyOfRange(10_000, 14_000), play(store) { it.range(10_000, 4_000) })
        assertEquals(4_000L, store.sizeBytes, "only the bytes that were read are stored")
        val before = requests("/media.mp4").size

        store.close()
        val again = newStore()
        assertContentEquals(body.copyOfRange(10_000, 14_000), play(again) { it.range(10_000, 4_000) })
        assertContentEquals(body.copyOfRange(11_000, 12_000), play(again) { it.range(11_000, 1_000) })
        assertEquals(before, requests("/media.mp4").size, "a stored range sent a request")

        // A range the store lacks is one ranged request, guarded by the stored entity tag.
        assertContentEquals(body.copyOfRange(20_000, 21_000), play(again) { it.range(20_000, 1_000) })
        assertEquals(Seen("/media.mp4", range = "bytes=20000-", ifRange = "\"v1\""), requests("/media.mp4").last())
        assertEquals(before + 1, requests("/media.mp4").size)

        // A read that starts in a stored range and runs past it joins both sources.
        assertContentEquals(body.copyOfRange(13_000, 16_000), play(again) { it.range(13_000, 3_000) })
        assertEquals(Seen("/media.mp4", range = "bytes=14000-", ifRange = "\"v1\""), requests("/media.mp4").last())
        again.close()
    }

    @Test
    fun aServerWithNoRangesIsStoredOnlyWhole() = runBlocking {
        start()
        val body = bytes(20_000, seed = 9)
        val headers = mapOf(HttpHeaders.CacheControl to "max-age=3600")
        playlist("seg1.ts")
        serve("/seg1.ts", body, headers, ranges = false)
        val store = newStore()
        // Half of it is no use later, because the rest could not be asked for.
        play(store) { root -> assertNotNull(root.openRelated(url("/seg1.ts"))).use { readAll(it, limit = 5_000) } }
        assertContentEquals(body, play(store) { it.segment("/seg1.ts") })
        assertEquals(2, requests("/seg1.ts").size)
        assertContentEquals(body, play(store) { it.segment("/seg1.ts") })
        assertEquals(2, requests("/seg1.ts").size, "the whole stored segment sent a request")
        store.close()
    }

    @Test
    fun onlyWhatAFinishedPlaylistNamedAsASegmentIsStored() = runBlocking {
        start()
        val headers = mapOf(HttpHeaders.CacheControl to "max-age=3600")
        playlist("seg1.ts", key = "key.bin")
        serve("/seg1.ts", bytes(5_000, seed = 10), headers)
        serve("/key.bin", "sixteen byte key".encodeToByteArray(), headers)
        serve("/other.ts", bytes(5_000, seed = 11), headers)
        val store = newStore()
        repeat(2) {
            play(store) { root ->
                root.segment("/seg1.ts")
                root.segment("/key.bin")
                root.segment("/other.ts")
                root.segment("/list.m3u8")
            }
        }
        assertEquals(1, requests("/seg1.ts").size)
        assertEquals(2, requests("/key.bin").size, "a key was read from the store")
        assertEquals(2, requests("/other.ts").size, "an address no playlist named was read from the store")
        assertEquals(4, requests("/list.m3u8").size, "a playlist was read from the store")
        assertEquals(5_000L, store.sizeBytes)
        val key = "sixteen byte key".encodeToByteArray().toList()
        assertTrue(files.files.values.none { stored -> stored.toList().windowed(key.size).any { it == key } }, "a key is in a stored file")
        store.close()
    }

    @Test
    fun aLivePlaylistStoresNothingAndIsFetchedAtEveryRefresh() = runBlocking {
        start()
        val headers = mapOf(HttpHeaders.CacheControl to "max-age=3600")
        playlist("seg1.ts", ended = false)
        serve("/seg1.ts", bytes(5_000, seed = 12), headers)
        val store = newStore()
        play(store) { root ->
            repeat(3) {
                // The backend reloads a live playlist through the item's reader, as it opens a segment.
                root.segment("/list.m3u8")
                root.segment("/seg1.ts")
            }
        }
        assertEquals(4, requests("/list.m3u8").size, "a live playlist refresh did not reach the server")
        assertEquals(3, requests("/seg1.ts").size)
        assertEquals(0L, store.sizeBytes, "a live presentation stored a segment")
        store.close()
    }

    @Test
    fun aResponseThatForbidsStorageOrHasNothingToJudgeItByIsNotStored() = runBlocking {
        start()
        playlist("forbidden.ts", "bare.ts", "star.ts")
        serve("/forbidden.ts", bytes(5_000, seed = 13), mapOf(HttpHeaders.CacheControl to "no-store, max-age=3600", HttpHeaders.ETag to "\"v1\""))
        serve("/bare.ts", bytes(5_000, seed = 14), emptyMap())
        serve("/star.ts", bytes(5_000, seed = 15), mapOf(HttpHeaders.CacheControl to "max-age=3600", HttpHeaders.Vary to "*"))
        val store = newStore()
        repeat(2) {
            play(store) { root ->
                root.segment("/forbidden.ts")
                root.segment("/bare.ts")
                root.segment("/star.ts")
            }
        }
        assertEquals(listOf(Seen("/forbidden.ts"), Seen("/forbidden.ts")), requests("/forbidden.ts"))
        assertEquals(2, requests("/bare.ts").size)
        assertEquals(2, requests("/star.ts").size)
        assertEquals(0L, store.sizeBytes)
        store.close()
    }

    @Test
    fun anotherNamespaceCannotReadAnEntry() = runBlocking {
        start()
        val body = bytes(5_000, seed = 16)
        playlist("seg1.ts")
        serve("/seg1.ts", body, mapOf(HttpHeaders.CacheControl to "max-age=3600"))
        newStore(namespace = "account-1").also { store -> play(store) { it.segment("/seg1.ts") } }.close()
        val other = newStore(namespace = "account-2")
        assertContentEquals(body, play(other) { it.segment("/seg1.ts") })
        assertEquals(2, requests("/seg1.ts").size, "another namespace read the entry")
        other.close()
        val same = newStore(namespace = "account-1")
        play(same) { it.segment("/seg1.ts") }
        assertEquals(2, requests("/seg1.ts").size, "the namespace that wrote the entry did not find it")
        same.close()
    }

    @Test
    fun aRequestWithALoginIsStoredOnlyInAPrivateStoreAndNeverReadByAnotherLogin() = runBlocking {
        start()
        playlist("seg1.ts")
        serve(
            "/seg1.ts",
            Served(mapOf(HttpHeaders.CacheControl to "max-age=3600")) { call ->
                (call.request.headers[HttpHeaders.Authorization] ?: "nobody").encodeToByteArray() + bytes(2_000, seed = 17)
            },
        )
        val alice = mapOf(HttpHeaders.Authorization to "Bearer alice")
        val bob = mapOf(HttpHeaders.Authorization to "Bearer bob")

        // A store that is not private to one account keeps nothing for a request with a login.
        val shared = newStore()
        repeat(2) { play(shared, alice) { it.segment("/seg1.ts") } }
        assertEquals(2, requests("/seg1.ts").size, "a login's answer was read from a store that is not private")
        assertEquals(0L, shared.sizeBytes)
        shared.close()

        val private = newStore(private = true)
        val first = play(private, alice) { it.segment("/seg1.ts") }
        assertContentEquals(first, play(private, alice) { it.segment("/seg1.ts") })
        assertEquals(3, requests("/seg1.ts").size, "the same login did not find its entry")
        val other = play(private, bob) { it.segment("/seg1.ts") }
        assertEquals(4, requests("/seg1.ts").size, "another login read the entry")
        assertTrue(other.decodeToString(0, 10).startsWith("Bearer bob"), "another login got the first one's bytes")
        val none = play(private) { it.segment("/seg1.ts") }
        assertTrue(none.decodeToString(0, 6).startsWith("nobody"), "a request with no login got a login's bytes")
        private.close()
    }

    @Test
    fun aResponseThatVariesByARequestHeaderIsReadOnlyByARequestWithTheSameValue() = runBlocking {
        start()
        playlist("seg1.ts")
        serve(
            "/seg1.ts",
            Served(mapOf(HttpHeaders.CacheControl to "max-age=3600", HttpHeaders.Vary to "X-Device")) { call ->
                (call.request.headers["X-Device"] ?: "none").encodeToByteArray() + bytes(2_000, seed = 18)
            },
        )
        val store = newStore()
        val phone = play(store, mapOf("X-Device" to "phone")) { it.segment("/seg1.ts") }
        val tv = play(store, mapOf("X-Device" to "tv")) { it.segment("/seg1.ts") }
        assertEquals(2, requests("/seg1.ts").size, "a request with another value of the varied header read the entry")
        assertTrue(tv.decodeToString(0, 2) == "tv" && phone.decodeToString(0, 5) == "phone")
        // Each value keeps its own entry.
        assertContentEquals(phone, play(store, mapOf("X-Device" to "phone")) { it.segment("/seg1.ts") })
        assertContentEquals(tv, play(store, mapOf("x-device" to "tv")) { it.segment("/seg1.ts") })
        assertEquals(2, requests("/seg1.ts").size)
        store.close()
    }

    @Test
    fun withNoStoreTheRequestsAreWhatTheyWereBefore() = runBlocking {
        start()
        val body = bytes(20_000, seed = 19)
        playlist("seg1.ts")
        serve("/seg1.ts", body, mapOf(HttpHeaders.CacheControl to "max-age=3600", HttpHeaders.ETag to "\"v1\""))
        repeat(2) {
            play(store = null) { root ->
                root.segment("/seg1.ts")
                assertNotNull(root.openRelated(url("/seg1.ts"))).use { io ->
                    io.seek(5_000)
                    readAll(io, limit = 100)
                }
            }
        }
        val once = listOf(
            Seen("/list.m3u8"),
            Seen("/seg1.ts"),
            Seen("/seg1.ts"),
            Seen("/seg1.ts", range = "bytes=5000-", ifRange = "\"v1\""),
        )
        assertEquals(once + once, log.load())
    }

    @Test
    fun aStoreThatFailsFallsBackToTheNetworkWithOneWarning() = runBlocking {
        start()
        val body = bytes(20_000, seed = 20)
        playlist("seg1.ts", "seg2.ts")
        val headers = mapOf(HttpHeaders.CacheControl to "max-age=3600")
        serve("/seg1.ts", body, headers)
        serve("/seg2.ts", body, headers)
        val store = newStore()
        play(store) { it.segment("/seg1.ts") }
        files.failing = true
        val warnings = mutableListOf<PlaybackWarning>()
        play(store, warnings = warnings) { root ->
            assertContentEquals(body, root.segment("/seg1.ts"), "a failed store failed the read")
            assertContentEquals(body, root.segment("/seg2.ts"))
            assertContentEquals(body, root.segment("/seg1.ts"))
        }
        assertEquals(1, warnings.filterIsInstance<PlaybackWarning.SegmentStoreFailed>().size, "the warnings were $warnings")
        assertEquals(3, requests("/seg1.ts").size)
        files.failing = false
        store.close()
    }

    @Test
    fun aStoreThatWasClosedUnderAPlayerFallsBackToTheNetworkWithOneWarning() = runBlocking {
        start()
        val body = bytes(20_000, seed = 22)
        playlist("seg1.ts", "seg2.ts")
        val headers = mapOf(HttpHeaders.CacheControl to "max-age=3600")
        serve("/seg1.ts", body, headers)
        serve("/seg2.ts", body, headers)
        val store = newStore()
        play(store) { it.segment("/seg1.ts") }
        // A closed store refuses to open an entry, which is a failure at the first step of a segment's open.
        store.close()
        val warnings = mutableListOf<PlaybackWarning>()
        play(store, warnings = warnings) { root ->
            assertContentEquals(body, root.segment("/seg1.ts"), "a closed store failed the open")
            assertContentEquals(body, root.segment("/seg2.ts"))
        }
        assertEquals(1, warnings.filterIsInstance<PlaybackWarning.SegmentStoreFailed>().size, "the warnings were $warnings")
        assertEquals(2, requests("/seg1.ts").size)
    }

    @Test
    fun aStoreThatFailsInTheMiddleOfAReadLetsTheReadFinish() = runBlocking {
        start()
        val body = bytes(60_000, seed = 21)
        playlist("seg1.ts")
        serve("/seg1.ts", body, mapOf(HttpHeaders.CacheControl to "max-age=3600"))
        val store = newStore()
        val warnings = mutableListOf<PlaybackWarning>()
        play(store, warnings = warnings) { root ->
            assertNotNull(root.openRelated(url("/seg1.ts"))).use { io ->
                val start = readAll(io, limit = 10_000)
                files.failing = true
                assertContentEquals(body, start + readAll(io))
            }
        }
        assertEquals(1, warnings.filterIsInstance<PlaybackWarning.SegmentStoreFailed>().size, "the warnings were $warnings")
        files.failing = false
        store.close()
    }

    @Test
    fun theLimitHoldsAcrossSeveralSegmentsAndWhatWasEvictedIsFetchedAgain() = runBlocking {
        start()
        val headers = mapOf(HttpHeaders.CacheControl to "max-age=3600")
        val names = (1..5).map { "seg$it.ts" }
        playlist(*names.toTypedArray())
        names.forEachIndexed { index, name -> serve("/$name", bytes(10_000, seed = 30 + index), headers) }
        val store = newStore(maxBytes = 30_000)
        play(store) { root ->
            // The first segment is still being read while the others fill the store past its limit.
            val active = assertNotNull(root.openRelated(url("/seg1.ts")))
            val start = readAll(active, limit = 4_000)
            for (name in names.drop(1)) {
                root.segment("/$name")
                assertTrue(store.sizeBytes <= 30_000, "the store holds ${store.sizeBytes} bytes")
            }
            assertContentEquals(bytes(10_000, seed = 30), start + readAll(active), "the read that was active did not finish")
            active.close()
        }
        assertTrue(store.sizeBytes <= 30_000, "the store holds ${store.sizeBytes} bytes")
        // The last two and the one that was held stayed. The two used longest ago went.
        play(store) { root ->
            for (index in listOf(0, 4, 3)) assertContentEquals(bytes(10_000, seed = 30 + index), root.segment("/${names[index]}"))
            assertEquals(listOf(1, 1, 1, 1, 1), names.map { requests("/$it").size }, "an entry that should have stayed was fetched again")
            // What was evicted is fetched again.
            for (index in listOf(1, 2)) assertContentEquals(bytes(10_000, seed = 30 + index), root.segment("/${names[index]}"))
        }
        assertEquals(listOf(1, 2, 2, 1, 1), names.map { requests("/$it").size })
        assertTrue(store.sizeBytes <= 30_000)
        store.close()
    }

    @Test
    fun twoPlayersShareOneStoreAndClearNeverCutsARead() = runBlocking {
        start()
        val body = bytes(40_000, seed = 40)
        playlist("seg1.ts")
        serve("/seg1.ts", body, mapOf(HttpHeaders.CacheControl to "max-age=3600"))
        val store = newStore()
        play(store) { one ->
            play(store) { two ->
                // Both read the same segment at once, a piece each in turn.
                val a = assertNotNull(one.openRelated(url("/seg1.ts")))
                val b = assertNotNull(two.openRelated(url("/seg1.ts")))
                var fromA = ByteArray(0)
                var fromB = ByteArray(0)
                while (fromA.size < body.size || fromB.size < body.size) {
                    fromA += readAll(a, limit = 3_000)
                    fromB += readAll(b, limit = 5_000)
                }
                a.close()
                b.close()
                assertContentEquals(body, fromA)
                assertContentEquals(body, fromB)
            }
        }
        assertEquals(40_000L, store.sizeBytes, "two writers of one segment left more than the segment")
        play(store) { root ->
            val reader = assertNotNull(root.openRelated(url("/seg1.ts")))
            val start = readAll(reader, limit = 10_000)
            store.clear()
            assertContentEquals(body, start + readAll(reader), "clear cut a read")
            reader.close()
            assertEquals(2, requests("/seg1.ts").size, "the read after clear went to the network")
            // What was cleared is fetched again and stored again.
            assertContentEquals(body, root.segment("/seg1.ts"))
        }
        assertEquals(3, requests("/seg1.ts").size)
        assertEquals(40_000L, store.sizeBytes)
        store.close()
    }

    @Test
    fun aRedirectedSegmentKeepsTheAddressThatAnsweredItsTypeAndItsSize() = runBlocking {
        start()
        val body = bytes(5_000, seed = 50)
        playlist("moved.ts")
        serve("/moved.ts", Served(redirect = "/seg1.ts") { ByteArray(0) })
        serve("/seg1.ts", body, mapOf(HttpHeaders.CacheControl to "max-age=3600"))
        val store = newStore()
        val first = play(store) { root ->
            assertNotNull(root.openRelated(url("/moved.ts"))).use { Triple(it.location, it.contentType, readAll(it).toList()) }
        }
        assertEquals(url("/seg1.ts"), first.first)
        val second = play(store) { root ->
            assertNotNull(root.openRelated(url("/moved.ts"))).use { io ->
                assertEquals(5_000L, io.size)
                Triple(io.location, io.contentType, readAll(io).toList())
            }
        }
        assertEquals(first, second)
        assertEquals(1, requests("/moved.ts").size, "the stored segment was asked for again")
        assertEquals(1, requests("/seg1.ts").size)
        // The entry belongs to the address that was asked for, not to the one that answered.
        play(store) { it.segment("/seg1.ts") }
        assertEquals(2, requests("/seg1.ts").size, "an address no playlist named was read from the store")
        assertNull(requests("/moved.ts").getOrNull(1))
        store.close()
    }
}
