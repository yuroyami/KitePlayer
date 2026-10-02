package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the HLS path asks of the reader (#209): the address that answered after a redirect, the
 * content type, and related readers, with the item's headers kept on the item's own server.
 */
@OptIn(ExperimentalAtomicApi::class)
class KtorMediaIoRelatedTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private val quick = HttpReaderPolicy(initialBackoff = 10.milliseconds, maxBackoff = 20.milliseconds)

    /** A server whose `/seg` answers 16 bytes and records the headers it was sent, and whose `/flaky` fails once. */
    private class Recorder {
        val authorizations = AtomicReference(listOf<String?>())
        val defaults = AtomicReference(listOf<String?>())
        val flakyCalls = AtomicInt(0)
        val missingCalls = AtomicInt(0)
    }

    private fun serve(recorder: Recorder = Recorder()): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/old") { call.respondRedirect("/new/index.m3u8") }
                get("/new/index.m3u8") {
                    call.respondText("#EXTM3U\n", ContentType.parse("application/vnd.apple.mpegurl"))
                }
                get("/seg") {
                    recorder.authorizations.store(recorder.authorizations.load() + call.request.headers[HttpHeaders.Authorization])
                    recorder.defaults.store(recorder.defaults.load() + call.request.headers["X-Default"])
                    call.respondBytes(ByteArray(16) { it.toByte() })
                }
                get("/flaky") {
                    if (recorder.flakyCalls.incrementAndFetch() == 1) {
                        call.respondText("busy", status = HttpStatusCode.ServiceUnavailable)
                    } else {
                        call.respondBytes(ByteArray(16))
                    }
                }
                get("/missing") {
                    recorder.missingCalls.incrementAndFetch()
                    call.respondText("no", status = HttpStatusCode.NotFound)
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun aReaderKnowsTheAddressThatAnsweredAndItsContentType() = runBlocking {
        val port = serve()
        val io = KtorMediaIo.open("http://127.0.0.1:$port/old")
        try {
            assertEquals("http://127.0.0.1:$port/new/index.m3u8", io.location)
            assertTrue(io.contentType?.startsWith("application/vnd.apple.mpegurl") == true, "the type was ${io.contentType}")
        } finally {
            io.close()
        }
    }

    @Test
    fun itemHeadersGoOnlyToTheItemsServerAndDefaultsGoEverywhere() = runBlocking {
        val home = Recorder()
        val other = Recorder()
        val homePort = serve(home)
        val otherPort = serve(other)
        KtorMediaIoResolver(headers = mapOf("X-Default" to "d")).use { resolver ->
            val root = assertNotNull(resolver.resolve("http://127.0.0.1:$homePort/seg", mapOf("Authorization" to "Bearer item")))
            try {
                assertNotNull(root.openRelated("http://127.0.0.1:$homePort/seg")).close()
                assertNotNull(root.openRelated("http://127.0.0.1:$otherPort/seg")).close()
            } finally {
                root.close()
            }
        }
        assertEquals(listOf<String?>("Bearer item", "Bearer item"), home.authorizations.load(), "the root and its related read on the same server")
        assertEquals(listOf<String?>("d", "d"), home.defaults.load())
        assertEquals(listOf<String?>(null), other.authorizations.load(), "the item's header went to another server")
        assertEquals(listOf<String?>("d"), other.defaults.load(), "a resolver default goes to every server")
    }

    @Test
    fun aRelatedOpenTriesAgainAfterAServerError() = runBlocking {
        val recorder = Recorder()
        val port = serve(recorder)
        val root = KtorMediaIo.open("http://127.0.0.1:$port/seg", policy = quick)
        val warnings = AtomicReference(listOf<PlaybackWarning>())
        root.setWarningSink { warning -> warnings.store(warnings.load() + warning) }
        try {
            assertNotNull(root.openRelated("http://127.0.0.1:$port/flaky")).close()
        } finally {
            root.close()
        }
        assertEquals(2, recorder.flakyCalls.load())
        assertTrue(warnings.load().any { it is PlaybackWarning.SourceReconnecting }, "no warning said it tried again: ${warnings.load()}")
    }

    @Test
    fun aMissingAddressIsNotTriedAgain() = runBlocking {
        val recorder = Recorder()
        val port = serve(recorder)
        val root = KtorMediaIo.open("http://127.0.0.1:$port/seg", policy = quick)
        try {
            assertFailsWith<KtorMediaIoException> { root.openRelated("http://127.0.0.1:$port/missing") }
        } finally {
            root.close()
        }
        assertEquals(1, recorder.missingCalls.load())
    }

    @Test
    fun onlyHttpAddressesOpenAndNothingOpensAfterClose() = runBlocking {
        val port = serve()
        val root = KtorMediaIo.open("http://127.0.0.1:$port/seg")
        assertNull(root.openRelated("file:///etc/passwd"))
        assertNull(root.openRelated("ftp://127.0.0.1/seg"))
        root.close()
        assertFailsWith<KtorMediaIoException> { root.openRelated("http://127.0.0.1:$port/seg") }
        Unit
    }
}
