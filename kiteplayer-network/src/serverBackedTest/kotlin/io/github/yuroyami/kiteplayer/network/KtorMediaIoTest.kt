package io.github.yuroyami.kiteplayer.network

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Ktor reader against a REAL local HTTP server: range probing decides seekability and
 * size, reads return the exact bytes, a seek costs one ranged request, and a server without
 * ranges yields an honest forward-only reader. https shares every line of this code path with
 * TLS handled by the platform engine beneath it, which is the design.
 */
class KtorMediaIoTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private fun content(size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    /** Serves [bytes] at /media, with Range support unless [ranged] is false. */
    private fun serve(bytes: ByteArray, ranged: Boolean = true, ranges: MutableList<String>? = null): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/media") {
                    val header = call.request.headers[HttpHeaders.Range]
                    if (ranged && header != null) {
                        ranges?.add(header)
                        val spec = header.removePrefix("bytes=")
                        val start = spec.substringBefore('-').toLong()
                        val endSpec = spec.substringAfter('-')
                        val end = endSpec.toLongOrNull() ?: (bytes.size - 1L)
                        call.response.header(
                            HttpHeaders.ContentRange,
                            "bytes $start-$end/${bytes.size}",
                        )
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

    private suspend fun readFully(io: KtorMediaIo, want: Int): ByteArray {
        val out = ByteArray(want)
        var at = 0
        while (at < want) {
            val r = io.read(out, at, want - at)
            check(r > 0) { "short read at $at (got $r)" }
            at += r
        }
        return out
    }

    @Test
    fun aRangedServerYieldsASeekableSizedReaderWithExactBytes() = runBlocking {
        val bytes = content(300_000)
        val ranges = mutableListOf<String>()
        val port = serve(bytes, ranges = ranges)
        val io = KtorMediaIo.open("http://127.0.0.1:$port/media")
        try {
            assertTrue(io.seekable, "a 206 answer means seekable")
            assertEquals(bytes.size.toLong(), io.size, "total size comes from Content-Range")

            val head = readFully(io, 10_000)
            for (i in 0 until 10_000) assertEquals(bytes[i], head[i], "byte $i corrupted")

            io.seek(200_000)
            val tail = readFully(io, 5_000)
            for (i in 0 until 5_000) assertEquals(bytes[200_000 + i], tail[i], "post-seek byte $i corrupted")
            assertTrue(ranges.any { it == "bytes=200000-" }, "the seek must become one ranged request, saw $ranges")
        } finally {
            io.close()
        }
    }

    @Test
    fun aServerWithoutRangesYieldsAForwardOnlyReader() = runBlocking {
        val bytes = content(64_000)
        val port = serve(bytes, ranged = false)
        val io = KtorMediaIo.open("http://127.0.0.1:$port/media")
        try {
            assertTrue(!io.seekable, "a 200 answer to a ranged probe means forward-only")
            assertEquals(bytes.size.toLong(), io.size, "size still comes from Content-Length")
            val all = readFully(io, bytes.size)
            assertTrue(all.contentEquals(bytes), "forward-only content corrupted")
            val one = ByteArray(1)
            assertEquals(-1, io.read(one, 0, 1), "the declared size bounds the stream")
        } finally {
            io.close()
        }
    }

    @Test
    fun theResolverAnswersForHttpSchemesOnlyAndCaseInsensitively() = runBlocking {
        val port = serve(content(1_000))
        val resolver = KtorMediaIoResolver()
        assertNull(resolver.resolve("/videos/movie.mkv"), "a local path is not the resolver's")
        assertNull(resolver.resolve("content://media/id"), "a content uri is not the resolver's")
        val io = resolver.resolve("HTTP://127.0.0.1:$port/media")
        assertTrue(io is KtorMediaIo, "an http uri resolves to the Ktor reader")
        io.close()
    }

    @Test
    fun itemHeadersOverrideResolverDefaultsWithoutDuplicatingHttpNames() = runBlocking {
        withTimeout<Unit>(15_000) {
            val received = CompletableDeferred<List<String>?>()
            val server = embeddedServer(CIO, port = 0) {
                routing {
                    get("/media") {
                        received.complete(call.request.headers.getAll(HttpHeaders.Authorization))
                        call.respondBytes(content(16))
                    }
                }
            }.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().first().port
                KtorMediaIoResolver(headers = mapOf("authorization" to "Bearer default")).use { resolver ->
                    val io = resolver.resolve(
                        "http://127.0.0.1:$port/media",
                        mapOf("Authorization" to "Bearer item"),
                    ) ?: error("HTTP resolver declined the URL")
                    try {
                        assertEquals(listOf("Bearer item"), received.await())
                    } finally {
                        io.close()
                    }
                }
            } finally {
                // This server is a child of runBlocking, so @AfterTest would run too late to stop it.
                server.stop(100, 500)
            }
        }
    }

    @Test
    fun aReadAfterCloseRefusesInsteadOfHangingForEver() = runBlocking {
        // SEC-6. close() cancels the scope, so a later read reached openAt, whose scope.launch
        // body never ran. Nothing wrote to the pipe and readAvailable suspended FOR EVER: no
        // error, no timeout, no thread to blame. This test would not finish before the fix.
        val bytes = content(4096)
        val port = serve(bytes)
        val io = KtorMediaIo.open("http://127.0.0.1:$port/media")
        val buffer = ByteArray(64)
        assertTrue(io.read(buffer, 0, buffer.size) > 0, "the reader works before close")
        // The seek is what makes this the real defect: it moves the position away from the open
        // body, so the read after close cannot be served from it and MUST go through openAt.
        io.seek(2048)
        io.close()

        assertFailsWith<KtorMediaIoException> { io.read(buffer, 0, buffer.size) }
        assertFailsWith<KtorMediaIoException> { io.seek(1024) }
        // Closing twice is a no-op rather than a second cancel of a dead scope.
        io.close()
    }
}
