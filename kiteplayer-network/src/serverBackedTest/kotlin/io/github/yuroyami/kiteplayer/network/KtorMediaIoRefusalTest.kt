package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.SourceRefusal
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * A signed address that expires (#453): once the server starts answering 403, the reader hands
 * the refusal over once, whether its own next range or a reader it opened met it, and a 404 is no
 * refusal a fresh address could cure.
 */
class KtorMediaIoRefusalTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @Volatile private var expired = false

    private val file = ByteArray(64 * 1024) { (it % 251).toByte() }

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private fun serve(): String {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/movie") {
                    if (expired) return@get call.respond(HttpStatusCode.Forbidden, "")
                    val start = call.request.header(HttpHeaders.Range)?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-${file.size - 1}/${file.size}")
                    call.respondBytes(file.copyOfRange(start, file.size), status = HttpStatusCode.PartialContent)
                }
                get("/segment") {
                    if (expired) return@get call.respond(HttpStatusCode.Unauthorized, "")
                    call.respondBytes(ByteArray(16))
                }
                get("/missing") { call.respond(HttpStatusCode.NotFound, "") }
            }
        }.start(wait = false)
        servers += server
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port"
    }

    @Test
    fun aRefusedRangeOfTheItemIsHandedOverOnce() = runBlocking {
        val root = serve()
        val io = KtorMediaIo.open("$root/movie")
        try {
            assertEquals(1024, io.read(ByteArray(1024), 0, 1024))
            assertNull(io.takeRefusal(), "a refusal was reported before any")
            expired = true
            io.seek(32 * 1024L)
            assertFails { io.read(ByteArray(1024), 0, 1024) }
            assertEquals(SourceRefusal("$root/movie", 403), io.takeRefusal())
            assertNull(io.takeRefusal(), "the refusal was handed over twice")
        } finally {
            io.close()
        }
    }

    @Test
    fun aReaderItOpenedThatIsRefusedReportsThroughTheItemsReader() = runBlocking {
        val root = serve()
        val io = KtorMediaIo.open("$root/movie")
        try {
            assertFails { io.openRelated("$root/missing") }
            assertNull(io.takeRefusal(), "a 404 was taken for a refusal")
            expired = true
            assertFails { io.openRelated("$root/segment") }
            assertEquals(SourceRefusal("$root/segment", 401), io.takeRefusal())
        } finally {
            io.close()
        }
    }
}
