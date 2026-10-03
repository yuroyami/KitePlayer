package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.network.KtorMediaIoException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Redirects of the DASH door's requests, against two local servers on two origins.
 *
 * The manifest server is the trusted origin. The other server records every request it gets, so
 * "refused" here means that it got none, not only that the call failed.
 */
class DashRedirectTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val otherRequests = MutableStateFlow(emptyList<String>())
    private val trustedRequests = MutableStateFlow(emptyList<String>())
    private var trustedPort = 0
    private var otherPort = 0

    private val client = HttpClient { defaultRequest { header("X-Api-Key", "canary") } }

    private val file = ByteArray(4096) { index -> (index * 7 + 3).toByte() }

    /**
     * A manifest whose one representation is the single segment at [segment]. In [mimeType]
     * video/mp4 it plays through the HLS stand-in, and in video/x-flv, which that path does not take,
     * through the one-stream door.
     */
    private fun segmentList(segment: String, mimeType: String = "video/mp4") = """
        <MPD type="static" mediaPresentationDuration="PT2S">
            <Period>
                <AdaptationSet contentType="video" mimeType="$mimeType">
                    <Representation id="v" bandwidth="1">
                        <SegmentList><SegmentURL media="$segment"/></SegmentList>
                    </Representation>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    /** A manifest whose one representation is the single file at [file]. */
    private fun singleFile(file: String) = """
        <MPD type="static" mediaPresentationDuration="PT2S">
            <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                    <Representation id="v" bandwidth="1"><BaseURL>$file</BaseURL></Representation>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    private suspend fun ApplicationCall.redirectTo(location: String?) {
        location?.let { response.header(HttpHeaders.Location, it) }
        respond(HttpStatusCode.Found, "")
    }

    private suspend fun ApplicationCall.respondRanged(bytes: ByteArray) {
        val start = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
        response.header(HttpHeaders.ContentRange, "bytes $start-${bytes.size - 1}/${bytes.size}")
        respondBytes(bytes.copyOfRange(start, bytes.size), status = HttpStatusCode.PartialContent)
    }

    // Built in a plain function: Ktor 3's embeddedServer captures a suspend caller's Job.
    @BeforeTest
    fun startServers() {
        val other = embeddedServer(CIO, port = 0) {
            routing {
                get("/{path...}") {
                    otherRequests.update { it + call.request.uri }
                    when (call.request.uri) {
                        "/movie.mpd" -> call.respondText(segmentList("/collect"))
                        "/movie.mp4" -> call.respondRanged(file)
                        else -> call.respondText("OTHER")
                    }
                }
            }
        }.start(wait = false)
        servers += other
        otherPort = runBlocking { other.engine.resolvedConnectors().first().port }
        val elsewhere = "http://127.0.0.1:$otherPort"

        val trusted = embeddedServer(CIO, port = 0) {
            routing {
                get("/{path...}") {
                    val uri = call.request.path()
                    trustedRequests.update { it + uri }
                    when {
                        uri == "/list.mpd" -> call.respondText(segmentList(checkNotNull(call.request.queryParameters["seg"])))
                        uri == "/flv.mpd" -> call.respondText(segmentList(checkNotNull(call.request.queryParameters["seg"]), "video/x-flv"))
                        uri == "/file.mpd" -> call.respondText(singleFile(checkNotNull(call.request.queryParameters["file"])))
                        uri == "/to-other.m4s" -> call.redirectTo("$elsewhere/collect")
                        uri == "/to-other.mpd" -> call.redirectTo("$elsewhere/movie.mpd")
                        uri == "/to-other.mp4" -> call.redirectTo("$elsewhere/movie.mp4")
                        uri == "/moved.m4s" -> call.redirectTo("/real.m4s")
                        uri == "/real.m4s" -> call.respondText("REAL")
                        uri.startsWith("/hop/") -> call.redirectTo("/hop/${uri.substringAfter("/hop/").toInt() + 1}")
                        uri == "/loop/a" -> call.redirectTo("/loop/b")
                        uri == "/loop/b" -> call.redirectTo("/loop/a")
                        uri == "/nowhere.m4s" -> call.redirectTo(null)
                        // The first read of this file is served, and every later one is redirected.
                        uri == "/later.mp4" ->
                            if (call.request.headers[HttpHeaders.Range] == "bytes=0-") {
                                call.respondRanged(file)
                            } else {
                                call.redirectTo("$elsewhere/movie.mp4")
                            }
                        else -> call.respond(HttpStatusCode.NotFound, "")
                    }
                }
            }
        }.start(wait = false)
        servers += trusted
        trustedPort = runBlocking { trusted.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServers() {
        client.close()
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private val trusted get() = "http://127.0.0.1:$trustedPort"

    private suspend fun readAll(io: MediaIo): ByteArray {
        val out = ArrayList<Byte>()
        val buffer = ByteArray(1024)
        while (true) {
            val count = io.read(buffer, 0, buffer.size)
            if (count < 0) return out.toByteArray()
            for (i in 0 until count) out += buffer[i]
        }
    }

    /**
     * The segments of the item for [mpdUrl], read as the player reads them. Through the HLS
     * stand-in that is the master playlist, then the media playlist it names, then each segment
     * that one names, every one of them opened through the stand-in as FFmpeg opens them.
     */
    private suspend fun readSegments(mpdUrl: String, policy: DashUrlPolicy): ByteArray {
        val io = checkNotNull(Dash.mediaItemFor(mpdUrl, client, policy).io).open()
        try {
            if (io.contentType != DashHlsMediaIo.HLS_MEDIA_TYPE) return readAll(io)
            val master = readAll(io).decodeToString()
            val playlist = master.lines().first { it.startsWith("https://") }
            val media = readAll(checkNotNull(io.openRelated(playlist))).decodeToString()
            val out = ArrayList<Byte>()
            for (segment in media.lines().filter { it.isNotBlank() && !it.startsWith("#") }) {
                val reader = checkNotNull(io.openRelated(segment))
                try {
                    out += readAll(reader).toList()
                } finally {
                    reader.close()
                }
            }
            return out.toByteArray()
        } finally {
            io.close()
        }
    }

    @Test
    fun sameOriginRefusesASegmentRedirectToAnotherOriginBeforeItIsRequested() = runBlocking {
        val refusal = assertFailsWith<DashUrlRefusedException> {
            readSegments("$trusted/list.mpd?seg=/to-other.m4s", DashUrlPolicy.SameOrigin)
        }
        assertTrue("sameOriginOnly" in refusal.message.orEmpty(), refusal.message)
        assertEquals(emptyList(), otherRequests.value, "the other origin got a request")
    }

    @Test
    fun theOneStreamDoorRefusesTheSameRedirect() = runBlocking {
        val refusal = assertFailsWith<DashUrlRefusedException> {
            readSegments("$trusted/flv.mpd?seg=/to-other.m4s", DashUrlPolicy.SameOrigin)
        }
        assertTrue("sameOriginOnly" in refusal.message.orEmpty(), refusal.message)
        assertEquals(emptyList(), otherRequests.value, "the other origin got a request")
    }

    @Test
    fun theDefaultPolicyFollowsTheSameRedirectToAnotherCdn() = runBlocking {
        val bytes = readSegments("$trusted/list.mpd?seg=/to-other.m4s", DashUrlPolicy.Default)
        assertEquals("OTHER", bytes.decodeToString())
        assertEquals(listOf("/collect"), otherRequests.value)
    }

    @Test
    fun sameOriginFollowsARedirectInsideTheManifestsOrigin() = runBlocking {
        val bytes = readSegments("$trusted/list.mpd?seg=/moved.m4s", DashUrlPolicy.SameOrigin)
        assertEquals("REAL", bytes.decodeToString())
    }

    @Test
    fun sameOriginRefusesAManifestRedirectToAnotherOrigin() = runBlocking {
        assertFailsWith<DashUrlRefusedException> { Dash.manifest("$trusted/to-other.mpd", client, DashUrlPolicy.SameOrigin) }
        assertEquals(emptyList(), otherRequests.value, "the other origin got a request")
        // The default policy follows it, and reads the other origin's manifest.
        val manifest = Dash.manifest("$trusted/to-other.mpd", client, DashUrlPolicy.Default)
        assertEquals(1, manifest.periods.size)
        assertEquals(listOf("/movie.mpd"), otherRequests.value)
    }

    @Test
    fun aChainStopsAfterFiveRedirects() = runBlocking {
        val refusal = assertFailsWith<DashUrlRefusedException> {
            readSegments("$trusted/list.mpd?seg=/hop/0", DashUrlPolicy.Default)
        }
        assertTrue("more than 5" in refusal.message.orEmpty(), refusal.message)
        val hops = trustedRequests.value.filter { it.startsWith("/hop/") }
        assertEquals((0..5).map { "/hop/$it" }, hops, "the sixth redirect must not be requested")
    }

    @Test
    fun aChainThatComesBackToAnAddressItAskedForStops() = runBlocking {
        val refusal = assertFailsWith<DashUrlRefusedException> {
            readSegments("$trusted/list.mpd?seg=/loop/a", DashUrlPolicy.Default)
        }
        assertTrue("already asked for" in refusal.message.orEmpty(), refusal.message)
        assertEquals(listOf("/loop/a", "/loop/b"), trustedRequests.value.filter { it.startsWith("/loop/") })
    }

    @Test
    fun aRedirectWithNoAddressFailsTheSegment() = runBlocking {
        // Through the HLS stand-in the segment is the HTTP reader's, which names the answer.
        val failure = assertFailsWith<KtorMediaIoException> {
            readSegments("$trusted/list.mpd?seg=/nowhere.m4s", DashUrlPolicy.SameOrigin)
        }
        assertTrue("302" in failure.message.orEmpty(), failure.message)
        assertFailsWith<IllegalArgumentException> { readSegments("$trusted/flv.mpd?seg=/nowhere.m4s", DashUrlPolicy.SameOrigin) }
        assertEquals(emptyList(), otherRequests.value)
    }

    @Test
    fun aSingleFileRepresentationFollowsTheSameRule() = runBlocking {
        val item = Dash.mediaItemFor("$trusted/file.mpd?file=/to-other.mp4", client, DashUrlPolicy.SameOrigin)
        assertFailsWith<DashUrlRefusedException> { checkNotNull(item.io).open() }
        assertEquals(emptyList(), otherRequests.value, "the other origin got a request")
        // The default policy reads the file from the other origin.
        val io = checkNotNull(Dash.mediaItemFor("$trusted/file.mpd?file=/to-other.mp4", client).io).open()
        try {
            assertContentEquals(file, readAll(io))
        } finally {
            io.close()
        }
    }

    @Test
    fun aRefusedRedirectOnALaterRangeIsNotRetried() = runBlocking {
        val io = checkNotNull(Dash.mediaItemFor("$trusted/file.mpd?file=/later.mp4", client, DashUrlPolicy.SameOrigin).io).open()
        try {
            io.read(ByteArray(16), 0, 16)
            io.seek(3000)
            assertFailsWith<DashUrlRefusedException> { io.read(ByteArray(16), 0, 16) }
        } finally {
            io.close()
        }
        assertEquals(emptyList(), otherRequests.value, "the other origin got a request")
        // One request for the open and one for the seek. A retry would have asked again.
        assertEquals(2, trustedRequests.value.count { it == "/later.mp4" })
    }

    @Test
    fun theCallersClientStillFollowsItsOwnRedirectsAfterARefusal() = runBlocking {
        assertFailsWith<DashUrlRefusedException> {
            readSegments("$trusted/list.mpd?seg=/to-other.m4s", DashUrlPolicy.SameOrigin)
        }
        // The check listened on the client only for its own request.
        assertEquals("OTHER", client.get("$trusted/to-other.m4s").bodyAsText())
        assertEquals(listOf("/collect"), otherRequests.value)
    }
}
