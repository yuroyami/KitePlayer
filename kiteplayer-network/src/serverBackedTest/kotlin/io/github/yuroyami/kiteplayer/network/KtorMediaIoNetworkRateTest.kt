package io.github.yuroyami.kiteplayer.network

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The network rate a reader measures for the variant step up (#376). */
class KtorMediaIoNetworkRateTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    /** `/index` answers a few bytes, and `/slow` 768 KiB at 64 KiB every 20 ms, which is at most 26 Mbit/s. */
    private fun serve(): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/index") { call.respondBytes(ByteArray(64)) }
                get("/slow") {
                    call.respondBytesWriter(contentLength = 786_432L) {
                        repeat(12) {
                            delay(20)
                            writeFully(ByteArray(65_536))
                            flush()
                        }
                    }
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private suspend fun KtorMediaIo.readAll() {
        val sink = ByteArray(65_536)
        while (read(sink, 0, sink.size) >= 0) Unit
    }

    @Test
    fun aReaderMeasuresTheRateOfItsDownloads(): Unit = runBlocking {
        val port = serve()
        KtorMediaIo.open("http://127.0.0.1:$port/slow").use { io ->
            io.readAll()
            val rate = assertNotNull(io.networkBitsPerSecond())
            // Never faster than the server sends. The low bound leaves room for a busy machine.
            assertTrue(rate in 2_000_000..30_000_000, "$rate")
        }
    }

    @Test
    fun theReadersItOpensCountForTheReaderThatOpenedThem(): Unit = runBlocking {
        val port = serve()
        KtorMediaIo.open("http://127.0.0.1:$port/index").use { playlist ->
            playlist.readAll()
            assertNull(playlist.networkBitsPerSecond(), "64 bytes say nothing about the link")
            val segment = assertNotNull(playlist.openRelated("http://127.0.0.1:$port/slow"))
            segment.use { (it as KtorMediaIo).readAll() }
            assertNotNull(playlist.networkBitsPerSecond(), "the segment's bytes count for the playlist")
        }
    }
}
