package io.github.yuroyami.kiteplayer.network

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A server may answer the open's range over the whole file with 200 and Accept-Ranges, and serve
 * every later range with 206. The reader is seekable then, as FFmpeg's own http reader takes it
 * (#870), and one that offers ranges and then serves none refuses the seek by name.
 */
class KtorMediaIoAcceptRangesTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    private val file = ByteArray(64 * 1024) { (it % 251).toByte() }

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private fun serve(): String {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                // 200 for a range from byte 0, 206 for any other.
                get("/whole-first") {
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    val start = call.request.header(HttpHeaders.Range)?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    if (start == 0) return@get call.respondBytes(file)
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-${file.size - 1}/${file.size}")
                    call.respondBytes(file.copyOfRange(start, file.size), status = HttpStatusCode.PartialContent)
                }
                // Offers ranges and always answers 200.
                get("/liar") {
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.respondBytes(file)
                }
                get("/plain") { call.respondBytes(file) }
            }
        }.start(wait = false)
        servers += server
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port"
    }

    @Test
    fun aWholeFileAnswerThatOffersRangesIsSeekable() = runBlocking {
        val io = KtorMediaIo.open("${serve()}/whole-first")
        try {
            assertTrue(io.seekable, "a 200 with Accept-Ranges and a length is seekable")
            assertEquals(file.size.toLong(), io.size)
            io.seek(40_000L)
            val read = ByteArray(1000)
            assertEquals(1000, io.read(read, 0, 1000))
            assertContentEquals(file.copyOfRange(40_000, 41_000), read)
        } finally {
            io.close()
        }
    }

    @Test
    fun aWholeFileAnswerWithoutRangesStaysUnseekable() = runBlocking {
        val io = KtorMediaIo.open("${serve()}/plain")
        try {
            assertFalse(io.seekable)
        } finally {
            io.close()
        }
    }

    @Test
    fun aServerThatOffersRangesAndServesNoneRefusesTheSeekByName() = runBlocking {
        val io = KtorMediaIo.open("${serve()}/liar")
        try {
            io.seek(40_000L)
            val failure = assertFails { io.read(ByteArray(1000), 0, 1000) }
            assertTrue("cannot seek" in failure.message.orEmpty(), "the refusal says why: ${failure.message}")
        } finally {
            io.close()
        }
    }
}
