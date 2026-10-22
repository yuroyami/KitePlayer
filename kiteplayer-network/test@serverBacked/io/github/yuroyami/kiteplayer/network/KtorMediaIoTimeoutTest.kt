package io.github.yuroyami.kiteplayer.network

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
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Every wait of the HTTP reader has a limit: the connection and headers of the probe, the next
 * bytes of a response, and the request a seek makes. Each case is a local server that stops
 * answering at one of those points.
 */
class KtorMediaIoTimeoutTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    /** No reconnects: these cases prove the limits themselves, and recovery has its own suite. */
    private val quick = HttpReaderPolicy(
        connectTimeout = 500.milliseconds,
        readTimeout = 500.milliseconds,
        maxReconnects = 0,
    )

    private fun content(size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    /** Serves /media through [answer], and returns the port. */
    private fun serve(answer: suspend ApplicationCall.() -> Unit): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing { get("/media") { call.answer() } }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    /** Answers a ranged request with the bytes from its start, in full. */
    private suspend fun ApplicationCall.respondRange(bytes: ByteArray) {
        val start = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
        response.header(HttpHeaders.ContentRange, "bytes $start-${bytes.size - 1}/${bytes.size}")
        respondBytes(bytes.copyOfRange(start, bytes.size), status = HttpStatusCode.PartialContent)
    }

    /** Sends the headers and the first [sent] bytes of the whole range, then nothing more. */
    private suspend fun ApplicationCall.respondThenStall(bytes: ByteArray, sent: Int) {
        response.header(HttpHeaders.ContentRange, "bytes 0-${bytes.size - 1}/${bytes.size}")
        val stalling: OutgoingContent = object : OutgoingContent.WriteChannelContent() {
            override val status: HttpStatusCode = HttpStatusCode.PartialContent
            override val contentLength: Long = bytes.size.toLong()

            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(bytes.copyOfRange(0, sent))
                channel.flush()
                awaitCancellation()
            }
        }
        respond(stalling)
    }

    private suspend fun readFully(io: KtorMediaIo, want: Int) {
        val out = ByteArray(want)
        var at = 0
        while (at < want) {
            val r = io.read(out, at, want - at)
            check(r > 0) { "short read at $at (got $r)" }
            at += r
        }
    }

    @Test
    fun anOpenAgainstAServerThatNeverAnswersFailsWithinTheConnectTimeout() = runBlocking {
        val port = serve { awaitCancellation() }
        val started = TimeSource.Monotonic.markNow()

        val failure = withTimeout(10.seconds) {
            assertFailsWith<KtorMediaIoException> { KtorMediaIo.open("http://127.0.0.1:$port/media", policy = quick) }
        }

        assertTrue(started.elapsedNow() < 5.seconds, "the open waited ${started.elapsedNow()}")
        assertTrue("no answer" in failure.message.orEmpty(), "the failure must say what timed out: ${failure.message}")
    }

    @Test
    fun aReadThatReceivesNothingFailsWithinTheReadTimeout() = runBlocking {
        val bytes = content(64_000)
        val port = serve { respondThenStall(bytes, sent = 32_000) }
        val io = KtorMediaIo.open("http://127.0.0.1:$port/media", policy = quick)
        try {
            readFully(io, 32_000)
            val started = TimeSource.Monotonic.markNow()

            val failure = withTimeout(10.seconds) {
                assertFailsWith<KtorMediaIoException> { io.read(ByteArray(1_024), 0, 1_024) }
            }

            assertTrue(started.elapsedNow() < 5.seconds, "the read waited ${started.elapsedNow()}")
            assertTrue("no bytes" in failure.message.orEmpty(), "the failure must say what timed out: ${failure.message}")
        } finally {
            io.close()
        }
    }

    @Test
    fun aSeekToAServerThatStopsAnsweringFailsWithinTheConnectTimeout() = runBlocking {
        val bytes = content(300_000)
        // The probe is answered; the ranged request that the seek makes never is.
        val port = serve {
            if (request.headers[HttpHeaders.Range] == "bytes=0-") respondRange(bytes) else awaitCancellation()
        }
        val io = KtorMediaIo.open("http://127.0.0.1:$port/media", policy = quick)
        try {
            io.seek(200_000)
            val started = TimeSource.Monotonic.markNow()

            val failure = withTimeout(10.seconds) {
                assertFailsWith<KtorMediaIoException> { io.read(ByteArray(1_024), 0, 1_024) }
            }

            assertTrue(started.elapsedNow() < 5.seconds, "the seek waited ${started.elapsedNow()}")
            assertTrue("no answer" in failure.message.orEmpty(), "the failure must say what timed out: ${failure.message}")
        } finally {
            io.close()
        }
    }

    @Test
    fun aPolicyRefusesALimitThatIsNotPositive() {
        assertFailsWith<IllegalArgumentException> { HttpReaderPolicy(connectTimeout = 0.seconds) }
        assertFailsWith<IllegalArgumentException> { HttpReaderPolicy(readTimeout = (-1).seconds) }
    }
}
