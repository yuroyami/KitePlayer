package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The HTTP reader recovers from a dropped connection: it reconnects with a `Range` request at the
 * byte it reached, reports each reconnect as a warning, and gives up when a reconnect cannot help.
 * Each case is a local server whose first response goes wrong in one way.
 */
class KtorMediaIoResilienceTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private val quick = HttpReaderPolicy(
        connectTimeout = 2.seconds,
        readTimeout = 300.milliseconds,
        maxReconnects = 2,
        initialBackoff = 50.milliseconds,
        maxBackoff = 100.milliseconds,
    )

    private val bytes = ByteArray(200_000) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    /** The `Range` and `If-Range` headers of every request, in order. The server's threads write them. */
    private val requests = Mutex()
    private val ranges = mutableListOf<String?>()
    private val ifRanges = mutableListOf<String?>()

    private suspend fun ranges(): List<String?> = requests.withLock { ranges.toList() }

    private suspend fun ifRanges(): List<String?> = requests.withLock { ifRanges.toList() }

    /**
     * The warnings a reader reported. The reader calls its sink from the read that reconnects, which
     * is the test's own coroutine, so the list needs no lock.
     */
    private val warnings = mutableListOf<PlaybackWarning>()

    /** Serves /media through [answer], and returns the address. */
    private fun serve(answer: suspend ApplicationCall.(range: String?) -> Unit): String {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/media") {
                    val range = call.request.headers[HttpHeaders.Range]
                    requests.withLock {
                        ranges += range
                        ifRanges += call.request.headers[HttpHeaders.IfRange]
                    }
                    call.answer(range)
                }
            }
        }.start(wait = false)
        servers += server
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port/media"
    }

    private fun start(range: String?): Int = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0

    /** Answers a ranged request with the bytes from its start, in full, tagged [tag] when it is not null. */
    private suspend fun ApplicationCall.respondRange(range: String?, tag: String? = null) {
        val from = start(range)
        response.header(HttpHeaders.ContentRange, "bytes $from-${bytes.size - 1}/${bytes.size}")
        tag?.let { response.header(HttpHeaders.ETag, it) }
        respondBytes(bytes.copyOfRange(from, bytes.size), status = HttpStatusCode.PartialContent)
    }

    /**
     * Declares the whole range from [range]'s start, sends [sent] bytes of it, and then either waits
     * for ever or drops the connection.
     */
    private suspend fun ApplicationCall.respondPart(
        range: String?,
        sent: Int,
        drop: Boolean,
        status: HttpStatusCode = HttpStatusCode.PartialContent,
        tag: String? = null,
    ) {
        val from = start(range)
        if (status == HttpStatusCode.PartialContent) {
            response.header(HttpHeaders.ContentRange, "bytes $from-${bytes.size - 1}/${bytes.size}")
        }
        tag?.let { response.header(HttpHeaders.ETag, it) }
        val part: OutgoingContent = object : OutgoingContent.WriteChannelContent() {
            override val status: HttpStatusCode = status
            override val contentLength: Long = (bytes.size - from).toLong()

            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(bytes.copyOfRange(from, minOf(from + sent, bytes.size)))
                channel.flush()
                if (drop) throw IllegalStateException("the test server drops the connection")
                awaitCancellation()
            }
        }
        respond(part)
    }

    private suspend fun readAll(io: KtorMediaIo): ByteArray {
        val out = ByteArray(bytes.size)
        var at = 0
        while (at < out.size) {
            val r = io.read(out, at, out.size - at)
            check(r > 0) { "short read at $at (got $r)" }
            at += r
        }
        assertEquals(-1, io.read(ByteArray(1), 0, 1), "the declared size ends the stream")
        return out
    }

    @Test
    fun aStalledResponseResumesAtTheByteItReached() = runBlocking {
        val url = serve { range -> if (range == "bytes=0-") respondPart(range, sent = 80_000, drop = false) else respondRange(range) }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val read = withTimeout(20.seconds) { readAll(io) }

            assertContentEquals(bytes, read, "the resumed bytes must continue exactly where the stall began")
            assertEquals(listOf<String?>("bytes=0-", "bytes=80000-"), ranges())
            val warning = assertIs<PlaybackWarning.SourceReconnecting>(warnings.single())
            assertEquals(80_000L, warning.position)
            assertEquals(1, warning.attempt)
        } finally {
            io.close()
        }
    }

    @Test
    fun aDroppedConnectionResumesAtTheByteItReached() = runBlocking {
        val url = serve { range -> if (range == "bytes=0-") respondPart(range, sent = 80_000, drop = true) else respondRange(range) }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val read = withTimeout(20.seconds) { readAll(io) }

            assertContentEquals(bytes, read, "the resumed bytes must continue exactly where the drop happened")
            val seen = ranges()
            assertEquals(2, seen.size, "one probe and one reconnect, saw $seen")
            assertEquals(1, warnings.size, "one warning for the one dropped connection")
        } finally {
            io.close()
        }
    }

    @Test
    fun theReconnectsOfOneReadAreBounded() = runBlocking {
        // The first response stalls after 80 000 bytes, and every reconnect stalls before its first
        // byte, so the one read that meets them makes no progress at all.
        val url = serve { range -> respondPart(range, sent = if (range == "bytes=0-") 80_000 else 0, drop = false) }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val failure = withTimeout(20.seconds) { assertFailsWith<KtorMediaIoException> { readAll(io) } }

            val message = failure.message.orEmpty()
            assertTrue("no bytes" in message || "no answer" in message, "the last failure must be a timeout: $message")
            assertEquals(quick.maxReconnects, warnings.size, "one warning per reconnect")
            assertEquals(1 + quick.maxReconnects, ranges().size, "one probe and one request per reconnect")
        } finally {
            io.close()
        }
    }

    @Test
    fun aServerThatIgnoresTheRangeIsNotTriedAgain() = runBlocking {
        // The reconnect is answered with the whole file from byte zero, which would splice wrong.
        val url = serve { range ->
            if (range == "bytes=0-") respondPart(range, sent = 80_000, drop = false) else respondBytes(bytes)
        }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val failure = withTimeout(20.seconds) { assertFailsWith<KtorMediaIoException> { readAll(io) } }

            assertTrue("200" in failure.message.orEmpty(), "the failure must name the refusal: ${failure.message}")
            assertEquals(2, ranges().size, "a refusal must not be tried again")
            assertEquals(1, warnings.size)
        } finally {
            io.close()
        }
    }

    @Test
    fun aServerWithoutRangesCannotResumeAfterItsFirstByte() = runBlocking {
        val url = serve { respondPart(null, sent = 80_000, drop = false, status = HttpStatusCode.OK) }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            assertTrue(!io.seekable, "a server without ranges gives a forward-only reader")

            withTimeout(20.seconds) { assertFailsWith<KtorMediaIoException> { readAll(io) } }

            assertEquals(1, ranges().size, "nothing to resume from, so no reconnect")
            assertTrue(warnings.isEmpty(), "no reconnect, so no warning")
        } finally {
            io.close()
        }
    }

    @Test
    fun aReconnectAsksForTheSameVersionOfTheFile() = runBlocking {
        val tag = "\"v1\""
        val url = serve { range ->
            if (range == "bytes=0-") respondPart(range, sent = 80_000, drop = false, tag = tag) else respondRange(range, tag)
        }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val read = withTimeout(20.seconds) { readAll(io) }

            assertContentEquals(bytes, read)
            assertEquals(listOf<String?>(null, tag), ifRanges(), "the reconnect must be conditional on the first response's tag")
        } finally {
            io.close()
        }
    }

    @Test
    fun aFileThatChangedIsNotSplicedIn() = runBlocking {
        // The reconnect meets a new version of the file: a server that honours If-Range sends all of it.
        val url = serve { range ->
            if (range == "bytes=0-") {
                respondPart(range, sent = 80_000, drop = false, tag = "\"v1\"")
            } else {
                response.header(HttpHeaders.ETag, "\"v2\"")
                respondBytes(bytes)
            }
        }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val failure = withTimeout(20.seconds) { assertFailsWith<KtorMediaIoException> { readAll(io) } }

            assertTrue("changed" in failure.message.orEmpty(), "the failure must say the file changed: ${failure.message}")
            assertEquals(2, ranges().size, "a changed file must not be tried again")
        } finally {
            io.close()
        }
    }

    @Test
    fun aRangeFromAFileOfAnotherSizeIsRefused() = runBlocking {
        // A server without tags still gives the total size, and a different total is a different file.
        val url = serve { range ->
            if (range == "bytes=0-") {
                respondPart(range, sent = 80_000, drop = false)
            } else {
                val from = start(range)
                response.header(HttpHeaders.ContentRange, "bytes $from-${bytes.size + 99}/${bytes.size + 100}")
                respondBytes(bytes.copyOfRange(from, bytes.size) + ByteArray(100), status = HttpStatusCode.PartialContent)
            }
        }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val failure = withTimeout(20.seconds) { assertFailsWith<KtorMediaIoException> { readAll(io) } }

            assertTrue("changed" in failure.message.orEmpty(), "the failure must say the file changed: ${failure.message}")
            assertNull(ifRanges().last(), "without a tag there is nothing to make the request conditional on")
        } finally {
            io.close()
        }
    }

    @Test
    fun aPolicyRefusesANegativeRecovery() {
        assertFailsWith<IllegalArgumentException> { HttpReaderPolicy(maxReconnects = -1) }
        assertFailsWith<IllegalArgumentException> {
            HttpReaderPolicy(initialBackoff = 2.seconds, maxBackoff = 1.seconds)
        }
    }

    @Test
    fun theBackoffDoublesUpToItsLimit() {
        val policy = HttpReaderPolicy(initialBackoff = 500.milliseconds, maxBackoff = 4.seconds)
        assertEquals(
            listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds, 4.seconds),
            (1..5).map { policy.backoff(it) },
        )
    }
}
