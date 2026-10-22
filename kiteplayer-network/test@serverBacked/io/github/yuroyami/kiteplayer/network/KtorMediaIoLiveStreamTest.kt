package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A live radio stream, which has no length and no ranges and comes from a Shoutcast or Icecast
 * server, outlives its server closing the connection: the reader connects again and the stream
 * goes on from the live edge (#508). Each connection sends bytes of its own number, so the test can
 * tell which answer each byte came from.
 */
class KtorMediaIoLiveStreamTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private val quick = HttpReaderPolicy(
        connectTimeout = 2.seconds,
        readTimeout = 2.seconds,
        maxReconnects = 2,
        initialBackoff = 50.milliseconds,
        maxBackoff = 100.milliseconds,
    )

    /** The `Range` header of every request, in order. The server's threads write them. */
    private val requests = Mutex()
    private val ranges = mutableListOf<String?>()

    private suspend fun ranges(): List<String?> = requests.withLock { ranges.toList() }

    /** The warnings the reader reported, from the test's own coroutine. */
    private val warnings = mutableListOf<PlaybackWarning>()

    /** Serves /radio through [answer], given the number of the connection from 1, and returns the address. */
    private fun serve(answer: suspend ApplicationCall.(connection: Int) -> Unit): String {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/radio") {
                    val connection = requests.withLock {
                        ranges += call.request.headers[HttpHeaders.Range]
                        ranges.size
                    }
                    call.answer(connection)
                }
            }
        }.start(wait = false)
        servers += server
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port/radio"
    }

    /**
     * Answers 200 with no length, as a station does, with its `icy-` headers when [station] is true.
     * Sends [sent] bytes of the [connection]'s number, then closes the response, or waits for ever
     * when [close] is false.
     */
    private suspend fun ApplicationCall.respondStream(
        connection: Int,
        sent: Int = CHUNK,
        close: Boolean = true,
        station: Boolean = true,
        type: ContentType = ContentType.Audio.MPEG,
    ) {
        if (station) {
            response.header("icy-name", "Test FM")
            response.header("icy-br", "128")
        }
        respond(object : OutgoingContent.WriteChannelContent() {
            override val status: HttpStatusCode = HttpStatusCode.OK
            override val contentType: ContentType = type

            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(ByteArray(sent) { connection.toByte() })
                channel.flush()
                if (!close) awaitCancellation()
            }
        })
    }

    /** Reads until [count] bytes or the end, and returns them. */
    private suspend fun read(io: KtorMediaIo, count: Int): ByteArray {
        val out = ByteArray(count)
        var at = 0
        while (at < count) {
            val read = io.read(out, at, count - at)
            if (read < 0) return out.copyOf(at)
            at += read
        }
        return out
    }

    @Test
    fun aLiveStreamWhoseServerClosesItConnectsAgainAndGoesOn() = runBlocking {
        val url = serve { connection -> respondStream(connection, close = connection < 3) }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val read = withTimeout(20.seconds) { read(io, 3 * CHUNK) }

            assertEquals(3 * CHUNK, read.size, "the stream ended at a close")
            for (connection in 1..3) {
                val part = read.copyOfRange((connection - 1) * CHUNK, connection * CHUNK)
                assertTrue(part.all { it == connection.toByte() }, "the bytes of answer $connection are not where they belong")
            }
            // The probe asks for a range, as for any address, and a live reconnect asks for none.
            assertEquals(listOf<String?>("bytes=0-", null, null), ranges())
            assertEquals(
                listOf(CHUNK.toLong(), 2L * CHUNK),
                warnings.map { (it as PlaybackWarning.SourceReconnecting).position },
                "one warning for each close",
            )
        } finally {
            io.close()
        }
    }

    @Test
    fun aLiveStreamWhoseStationStaysGoneEndsOnceTheReconnectsAreSpent() = runBlocking {
        val url = serve { connection ->
            if (connection == 1) respondStream(connection) else respondText("gone", status = HttpStatusCode.NotFound)
        }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val read = withTimeout(20.seconds) { read(io, 3 * CHUNK) }

            assertEquals(CHUNK, read.size, "the stream did not end where its station went")
            assertEquals(quick.maxReconnects, warnings.size, "one warning per reconnect")
            assertEquals(1 + quick.maxReconnects, ranges().size, "one probe and one request per reconnect")
        } finally {
            io.close()
        }
    }

    // Icecast answers 404 for a station whose encoder is connecting again.
    @Test
    fun aStationGoneForAMomentPlaysOn() = runBlocking {
        val url = serve { connection ->
            when (connection) {
                1 -> respondStream(connection)
                2 -> respondText("no such mount", status = HttpStatusCode.NotFound)
                else -> respondStream(connection, close = false)
            }
        }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val read = withTimeout(20.seconds) { read(io, 2 * CHUNK) }

            assertEquals(2 * CHUNK, read.size)
            assertTrue(read.copyOfRange(CHUNK, 2 * CHUNK).all { it == 3.toByte() }, "the stream did not go on from the third answer")
            assertEquals(2, warnings.size, "one warning for the close and one for the 404")
        } finally {
            io.close()
        }
    }

    @Test
    fun aLiveStreamWhoseServerKeepsFailingFailsAfterTheLimit() = runBlocking {
        val url = serve { connection ->
            if (connection == 1) respondStream(connection) else respondText("busy", status = HttpStatusCode.ServiceUnavailable)
        }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val failure = withTimeout(20.seconds) { assertFailsWith<KtorMediaIoException> { read(io, 3 * CHUNK) } }

            assertTrue("503" in failure.message.orEmpty(), "the failure must name the answer: ${failure.message}")
            assertEquals(quick.maxReconnects, warnings.size, "one warning per reconnect")
            assertEquals(1 + quick.maxReconnects, ranges().size, "one probe and one request per reconnect")
        } finally {
            io.close()
        }
    }

    @Test
    fun aReconnectAnsweredWithAnotherTypeIsRefused() = runBlocking {
        val url = serve { connection ->
            if (connection == 1) respondStream(connection) else respondText("<html>offline</html>", ContentType.Text.Html)
        }
        val io = KtorMediaIo.open(url, policy = quick)
        try {
            val failure = withTimeout(20.seconds) { assertFailsWith<KtorMediaIoException> { read(io, 3 * CHUNK) } }

            assertTrue("text/html" in failure.message.orEmpty(), "the failure must name the type: ${failure.message}")
            assertEquals(2, ranges().size, "a page of markup is not tried again")
        } finally {
            io.close()
        }
    }

    // A media server that encodes a song as it sends it answers the same way, with no length and no
    // ranges, and asking it again would play the song again from its start.
    @Test
    fun aStreamWithoutStationHeadersEndsWhereItsBodyEnds() = runBlocking {
        val url = serve { connection -> respondStream(connection, station = false) }
        val io = KtorMediaIo.open(url, policy = quick)
        io.setWarningSink { warnings += it }
        try {
            val read = withTimeout(20.seconds) { read(io, 3 * CHUNK) }

            assertEquals(CHUNK, read.size)
            assertEquals(1, ranges().size, "the end of the body was taken for a drop")
            assertTrue(warnings.isEmpty())
        } finally {
            io.close()
        }
    }

    @Test
    fun aStreamThatDeclaresItsLengthIsAFileEvenFromAStation() = runBlocking {
        val url = serve { _ ->
            response.header("icy-name", "Test FM")
            respondText("x".repeat(CHUNK), ContentType.Audio.MPEG)
        }
        val io = KtorMediaIo.open(url, policy = quick)
        try {
            val read = withTimeout(20.seconds) { read(io, 3 * CHUNK) }

            assertEquals(CHUNK, read.size)
            assertEquals(1, ranges().size, "a file of known length was asked for again at its end")
        } finally {
            io.close()
        }
    }

    private companion object {
        const val CHUNK = 50_000
    }
}
