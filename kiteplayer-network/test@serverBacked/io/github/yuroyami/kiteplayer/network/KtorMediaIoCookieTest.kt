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
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A cookie a stream's server sets is sent back by every later read of the same item, and by no
 * other item (#449), as a CDN that protects its segments with a session cookie needs.
 */
class KtorMediaIoCookieTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val bytes = ByteArray(500) { it.toByte() }

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    /** /playlist sets the session; /segment answers 403 without it. Records each Cookie it sees. */
    private fun serve(seen: MutableList<String?>): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/playlist") {
                    seen += call.request.headers[HttpHeaders.Cookie]
                    call.response.header(HttpHeaders.SetCookie, "session=abc; Path=/")
                    call.respondBytes(bytes)
                }
                get("/segment") {
                    val cookie = call.request.headers[HttpHeaders.Cookie]
                    seen += cookie
                    if (cookie?.contains("session=abc") == true) {
                        call.respondBytes(bytes)
                    } else {
                        call.respondBytes(ByteArray(0), status = HttpStatusCode.Forbidden)
                    }
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun aSegmentCarriesTheCookieThePlaylistSet() = runBlocking {
        val seen = mutableListOf<String?>()
        val port = serve(seen)
        KtorMediaIo.open("http://127.0.0.1:$port/playlist").use { playlist ->
            val segment = assertNotNull(playlist.openRelated("http://127.0.0.1:$port/segment"))
            segment.use { assertEquals(bytes.size.toLong(), it.size) }
        }
        assertEquals(listOf(null, "session=abc"), seen)
    }

    @Test
    fun anotherItemStartsWithNoCookie() = runBlocking {
        val seen = mutableListOf<String?>()
        val port = serve(seen)
        KtorMediaIo.open("http://127.0.0.1:$port/playlist").close()
        assertFailsWith<KtorMediaIoException> { KtorMediaIo.open("http://127.0.0.1:$port/segment").close() }
        assertNull(seen.last(), "the second item's first request carried the first item's session")
    }

    @Test
    fun aCookieTheItemSetsStillGoesOut() = runBlocking {
        val seen = mutableListOf<String?>()
        val port = serve(seen)
        KtorMediaIo.open("http://127.0.0.1:$port/segment", headers = mapOf("Cookie" to "session=abc")).close()
        assertEquals("session=abc", seen.single())
    }
}
