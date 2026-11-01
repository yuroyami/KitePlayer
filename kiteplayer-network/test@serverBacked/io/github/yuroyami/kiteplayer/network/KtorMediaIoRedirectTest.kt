package io.github.yuroyami.kiteplayer.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val KEY = "X-Api-Key"

/**
 * An item's headers stay on the item's server when a request is redirected. Ktor by itself keeps
 * every header but `Authorization`, so a header that holds a credential reached the other host.
 *
 * `localhost` and 127.0.0.1 are two hosts to the client, though one machine.
 */
class KtorMediaIoRedirectTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val bytes = ByteArray(1000) { (it * 7).toByte() }
    private val item = mapOf(KEY to "secret")
    private val itemCookie = mapOf(HttpHeaders.Cookie to "session=secret")

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    /** What a server was sent, one entry for each request, in order. */
    private class Seen {
        val keys = MutableStateFlow(emptyList<String?>())
        val cookies = MutableStateFlow(emptyList<String?>())

        fun note(call: ApplicationCall) {
            keys.update { it + call.request.headers[KEY] }
            cookies.update { it + call.request.headers[HttpHeaders.Cookie] }
        }
    }

    /** A server whose /media answers ranges, and whose /away redirects to what [away] names. */
    private fun serve(seen: Seen, away: () -> String = { "/media" }): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/media") {
                    seen.note(call)
                    val start = call.request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-${bytes.size - 1}/${bytes.size}")
                    call.respondBytes(bytes.copyOfRange(start, bytes.size), status = HttpStatusCode.PartialContent)
                }
                get("/away") {
                    seen.note(call)
                    call.respondRedirect(away())
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun aRedirectToAnotherHostLeavesTheItemsHeadersBehind() = runBlocking {
        val elsewhere = Seen()
        val otherPort = serve(elsewhere)
        val home = Seen()
        val port = serve(home) { "http://localhost:$otherPort/media" }
        KtorMediaIo.open("http://127.0.0.1:$port/away", headers = item).close()
        assertEquals(listOf<String?>("secret"), home.keys.value, "the header did not reach the item's server")
        assertEquals(listOf<String?>(null), elsewhere.keys.value, "the header followed the redirect to another host")
    }

    @Test
    fun aRedirectToAnotherPortLeavesTheItemsHeadersBehind() = runBlocking {
        val elsewhere = Seen()
        val otherPort = serve(elsewhere)
        val home = Seen()
        val port = serve(home) { "http://127.0.0.1:$otherPort/media" }
        KtorMediaIo.open("http://127.0.0.1:$port/away", headers = item).close()
        assertEquals(listOf<String?>(null), elsewhere.keys.value, "the header followed the redirect to another port")
    }

    @Test
    fun aRedirectOnTheItemsServerKeepsItsHeaders() = runBlocking {
        val home = Seen()
        val port = serve(home)
        KtorMediaIo.open("http://127.0.0.1:$port/away", headers = item).close()
        assertEquals(listOf<String?>("secret", "secret"), home.keys.value, "a redirect on one server lost the header")
    }

    @Test
    fun aRedirectedSeekLeavesTheItemsHeadersBehind() = runBlocking {
        val elsewhere = Seen()
        val otherPort = serve(elsewhere)
        val home = Seen()
        val port = serve(home) { "http://localhost:$otherPort/media" }
        KtorMediaIo.open("http://127.0.0.1:$port/away", headers = item).use { io ->
            io.seek(500)
            val into = ByteArray(10)
            assertTrue(io.read(into, 0, into.size) > 0, "the seek read nothing")
            assertEquals(bytes[500], into[0], "the seek did not land on its byte")
        }
        assertEquals(listOf<String?>("secret", "secret"), home.keys.value, "the open and the seek both start on the item's server")
        assertEquals(listOf<String?>(null, null), elsewhere.keys.value, "the header followed a redirect to another host")
    }

    @Test
    fun aRelatedReaderThatIsRedirectedLeavesTheItemsHeadersBehind() = runBlocking {
        val elsewhere = Seen()
        val otherPort = serve(elsewhere)
        val home = Seen()
        val port = serve(home) { "http://localhost:$otherPort/media" }
        val root = KtorMediaIo.open("http://127.0.0.1:$port/media", headers = item)
        try {
            assertNotNull(root.openRelated("http://127.0.0.1:$port/away")).close()
        } finally {
            root.close()
        }
        assertEquals(listOf<String?>("secret", "secret"), home.keys.value, "the root and its related read on the item's server")
        assertEquals(listOf<String?>(null), elsewhere.keys.value, "the header followed the redirect to another host")
    }

    /** The other host chose the address, so the item's credential must not go back with it. */
    @Test
    fun aRedirectBackToTheItemsServerStaysWithoutItsHeaders() = runBlocking {
        val home = Seen()
        var homePort = 0
        val elsewhere = Seen()
        val otherPort = serve(elsewhere) { "http://127.0.0.1:$homePort/media" }
        homePort = serve(home) { "http://localhost:$otherPort/away" }
        KtorMediaIo.open("http://127.0.0.1:$homePort/away", headers = item).close()
        assertEquals(listOf<String?>(null), elsewhere.keys.value, "the header followed the redirect to another host")
        assertEquals(listOf<String?>("secret", null), home.keys.value, "the header came back after the other host")
    }

    @Test
    fun aResolverDefaultFollowsARedirect() = runBlocking {
        val elsewhere = Seen()
        val otherPort = serve(elsewhere)
        val port = serve(Seen()) { "http://localhost:$otherPort/media" }
        KtorMediaIoResolver(headers = mapOf(KEY to "default")).use { resolver ->
            assertNotNull(resolver.resolve("http://127.0.0.1:$port/away")).close()
        }
        assertEquals(listOf<String?>("default"), elsewhere.keys.value, "a resolver default goes to every address")
    }

    @Test
    fun anItemHeaderThatReplacesAResolverDefaultStaysBehind() = runBlocking {
        val elsewhere = Seen()
        val otherPort = serve(elsewhere)
        val home = Seen()
        val port = serve(home) { "http://localhost:$otherPort/media" }
        KtorMediaIoResolver(headers = mapOf(KEY to "default")).use { resolver ->
            assertNotNull(resolver.resolve("http://127.0.0.1:$port/away", item)).close()
        }
        assertEquals(listOf<String?>("secret"), home.keys.value, "the item's value did not reach the item's server")
        assertEquals(listOf<String?>(null), elsewhere.keys.value, "the item's value followed the redirect to another host")
    }

    @Test
    fun anItemsCookieStaysBehindOnTheReadersOwnClient() = runBlocking {
        val elsewhere = Seen()
        val otherPort = serve(elsewhere)
        val home = Seen()
        val port = serve(home) { "http://localhost:$otherPort/media" }
        KtorMediaIo.open("http://127.0.0.1:$port/away", headers = itemCookie).close()
        assertEquals(listOf<String?>("session=secret"), home.cookies.value, "the cookie did not reach the item's server")
        assertEquals(listOf<String?>(null), elsewhere.cookies.value, "the cookie followed the redirect to another host")
    }

    @Test
    fun anItemsCookieStaysBehindOnTheCallersClient() = runBlocking {
        val elsewhere = Seen()
        val otherPort = serve(elsewhere)
        val home = Seen()
        val port = serve(home) { "http://localhost:$otherPort/media" }
        val client = HttpClient()
        try {
            KtorMediaIo.open("http://127.0.0.1:$port/away", client, itemCookie).close()
        } finally {
            client.close()
        }
        assertEquals(listOf<String?>("session=secret"), home.cookies.value, "the cookie did not reach the item's server")
        assertEquals(listOf<String?>(null), elsewhere.cookies.value, "the cookie followed the redirect to another host")
    }

    @Test
    fun aCallersOwnRequestOnTheSameClientIsLeftAlone() = runBlocking {
        val elsewhere = Seen()
        val otherPort = serve(elsewhere)
        val port = serve(Seen()) { "http://localhost:$otherPort/media" }
        val client = HttpClient()
        try {
            KtorMediaIo.open("http://127.0.0.1:$port/media", client, item).close()
            // The Darwin engine resumes on the main queue, which a plain runBlocking thread never serves.
            withContext(Dispatchers.Default) { client.get("http://127.0.0.1:$port/away") { header(KEY, "theirs") } }
        } finally {
            client.close()
        }
        assertEquals(listOf<String?>("theirs"), elsewhere.keys.value, "the reader changed a request that is not its own")
    }
}
