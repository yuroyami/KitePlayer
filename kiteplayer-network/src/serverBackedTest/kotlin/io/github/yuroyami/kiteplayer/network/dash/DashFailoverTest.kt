package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A manifest's backup server, through both DASH doors (#440). The manifest names two `BaseURL`s,
 * the first of which answers 503 to everything: every segment comes from the second, none is
 * skipped, and the first is asked once and never again.
 */
class DashFailoverTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val downRequests = MutableStateFlow(emptyList<String>())
    private val upRequests = MutableStateFlow(emptyList<String>())
    private var downPort = 0
    private var upPort = 0
    private val client = HttpClient()

    private fun manifest(mimeType: String) = """
        <MPD type="static" mediaPresentationDuration="PT6S">
            <BaseURL>http://127.0.0.1:$downPort/cdn/</BaseURL>
            <BaseURL>http://127.0.0.1:$upPort/cdn/</BaseURL>
            <Period>
                <AdaptationSet contentType="video" mimeType="$mimeType">
                    <Representation id="v" bandwidth="1">
                        <SegmentList duration="2" timescale="1">
                            <SegmentURL media="a.m4s"/><SegmentURL media="b.m4s"/><SegmentURL media="c.m4s"/>
                        </SegmentList>
                    </Representation>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    // Built in a plain function: Ktor 3's embeddedServer captures a suspend caller's Job.
    @BeforeTest
    fun startServers() {
        val down = embeddedServer(CIO, port = 0) {
            routing {
                get("/{path...}") {
                    downRequests.update { it + call.request.path() }
                    call.respond(HttpStatusCode.ServiceUnavailable, "")
                }
            }
        }.start(wait = false)
        servers += down
        downPort = runBlocking { down.engine.resolvedConnectors().first().port }
        val up = embeddedServer(CIO, port = 0) {
            routing {
                get("/{path...}") {
                    val path = call.request.path()
                    upRequests.update { it + path }
                    when (path) {
                        "/mp4.mpd" -> call.respondText(manifest("video/mp4"))
                        "/flv.mpd" -> call.respondText(manifest("video/x-flv"))
                        "/cdn/a.m4s" -> call.respondText("A")
                        "/cdn/b.m4s" -> call.respondText("B")
                        "/cdn/c.m4s" -> call.respondText("C")
                        else -> call.respond(HttpStatusCode.NotFound, "")
                    }
                }
            }
        }.start(wait = false)
        servers += up
        upPort = runBlocking { up.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServers() {
        client.close()
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private suspend fun readAll(io: MediaIo): ByteArray {
        val out = ArrayList<Byte>()
        val buffer = ByteArray(1024)
        while (true) {
            val count = io.read(buffer, 0, buffer.size)
            if (count < 0) return out.toByteArray()
            for (i in 0 until count) out += buffer[i]
        }
    }

    /** The segments of [mpdUrl]'s item, read as the player reads them, through the HLS stand-in when it is one. */
    private suspend fun readSegments(mpdUrl: String): String {
        val io = checkNotNull(Dash.mediaItemFor(mpdUrl, client).io).open()
        try {
            if (io.contentType != "application/vnd.apple.mpegurl") return readAll(io).decodeToString()
            val master = readAll(io).decodeToString()
            val playlist = master.lines().first { it.startsWith("https://") }
            val media = readAll(checkNotNull(io.openRelated(playlist))).decodeToString()
            val out = StringBuilder()
            for (segment in media.lines().filter { it.isNotBlank() && !it.startsWith("#") }) {
                val reader = checkNotNull(io.openRelated(segment))
                try {
                    out.append(readAll(reader).decodeToString())
                } finally {
                    reader.close()
                }
            }
            return out.toString()
        } finally {
            io.close()
        }
    }

    @Test
    fun theHlsDoorReadsEverySegmentFromTheBackupAndAsksTheFailedServerOnce() = runBlocking {
        assertEquals("ABC", readSegments("http://127.0.0.1:$upPort/mp4.mpd"))
        assertEquals(listOf("/cdn/a.m4s"), downRequests.value, "the failed server was asked again")
        assertEquals(listOf("/cdn/a.m4s", "/cdn/b.m4s", "/cdn/c.m4s"), upRequests.value.filter { it.startsWith("/cdn/") })
    }

    @Test
    fun theOneStreamDoorDoesTheSame() = runBlocking {
        assertEquals("ABC", readSegments("http://127.0.0.1:$upPort/flv.mpd"))
        assertEquals(listOf("/cdn/a.m4s"), downRequests.value, "the failed server was asked again")
        assertEquals(listOf("/cdn/a.m4s", "/cdn/b.m4s", "/cdn/c.m4s"), upRequests.value.filter { it.startsWith("/cdn/") })
    }
}
