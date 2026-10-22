package io.github.yuroyami.kiteplayer.network

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An address written `http://user:pass@host/...` logs in with Basic credentials, as FFmpeg's own
 * http reader does (#448), only to its own origin, and never says the password.
 */
class KtorMediaIoLoginTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val bytes = ByteArray(1000) { (it * 7).toByte() }

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    /** Serves /media only to [expected] credentials, records every Authorization, and redirects /away to [away]. */
    private fun serve(expected: String, seen: MutableList<String?>, away: String? = null): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/media") {
                    val authorization = call.request.headers[HttpHeaders.Authorization]
                    seen += authorization
                    if (authorization != expected) {
                        call.respondBytes(ByteArray(0), status = HttpStatusCode.Unauthorized)
                    } else {
                        call.respondBytes(bytes)
                    }
                }
                get("/open") {
                    seen += call.request.headers[HttpHeaders.Authorization]
                    call.respondBytes(bytes)
                }
                get("/away") {
                    seen += call.request.headers[HttpHeaders.Authorization]
                    call.respondRedirect(away ?: "/media")
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun aUserAndPasswordInTheAddressLogIn() = runBlocking {
        val seen = mutableListOf<String?>()
        val port = serve("Basic dXNlcjpwYXNz", seen)
        KtorMediaIo.open("http://user:pass@127.0.0.1:$port/media").use { io ->
            assertEquals(bytes.size.toLong(), io.size)
            assertTrue(io.location.contains("127.0.0.1:$port/media"), "location ${io.location}")
            assertTrue("pass" !in io.location, "the password reached the location: ${io.location}")
        }
        assertEquals("Basic dXNlcjpwYXNz", seen.first())
    }

    @Test
    fun anEncodedPasswordIsSentAsItIsSpelled() = runBlocking {
        val seen = mutableListOf<String?>()
        // user "a b" and password "p@ss:w" -> "a b:p@ss:w"
        val port = serve("Basic YSBiOnBAc3M6dw==", seen)
        KtorMediaIo.open("http://a%20b:p%40ss%3Aw@127.0.0.1:$port/media").use { io ->
            assertEquals(bytes.size.toLong(), io.size)
        }
    }

    @Test
    fun withoutTheLoginTheServerRefuses() = runBlocking {
        val seen = mutableListOf<String?>()
        val port = serve("Basic dXNlcjpwYXNz", seen)
        assertFailsWith<KtorMediaIoException> { KtorMediaIo.open("http://127.0.0.1:$port/media").close() }
        assertNull(seen.first())
    }

    @Test
    fun anAuthorizationTheItemSetsWins() = runBlocking {
        val seen = mutableListOf<String?>()
        val port = serve("Bearer token", seen)
        KtorMediaIo.open(
            "http://user:pass@127.0.0.1:$port/media",
            headers = mapOf("authorization" to "Bearer token"),
        ).close()
        assertEquals("Bearer token", seen.first())
    }

    @Test
    fun aRedirectToAnotherHostDoesNotCarryTheLogin() = runBlocking {
        val elsewhere = mutableListOf<String?>()
        val otherPort = serve("unused", elsewhere)
        val here = mutableListOf<String?>()
        // localhost and 127.0.0.1 are two origins to the client, though one machine.
        val port = serve("unused", here, away = "http://localhost:$otherPort/open")
        KtorMediaIo.open("http://user:pass@127.0.0.1:$port/away").close()
        assertEquals("Basic dXNlcjpwYXNz", here.first(), "the login did not reach its own origin")
        assertEquals(listOf<String?>(null), elsewhere, "the login followed the redirect to another host")
    }

    @Test
    fun anAddressWithoutALoginIsLeftAlone() {
        assertNull(basicLogin("http://127.0.0.1/media"))
        assertNull(basicLogin("file:///tmp/a:b@c"))
        val login = basicLogin("https://user:pass@example.com:8443/a/b?c=d")!!
        assertEquals("https://example.com:8443/a/b?c=d", login.uri)
        assertEquals("Basic dXNlcjpwYXNz", login.authorization)
    }
}
