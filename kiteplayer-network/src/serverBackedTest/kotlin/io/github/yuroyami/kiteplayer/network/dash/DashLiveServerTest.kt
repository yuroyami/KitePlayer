package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A live manifest read through the real HTTP stack (#404): its window counted on the time its
 * `UTCTiming` server answers in a `Date` header, and its refreshes fetched from its `Location`.
 */
class DashLiveServerTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val requests = MutableStateFlow(listOf<String>())

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    /**
     * A presentation that became available at one minute past 1970, whose time server says it is
     * one minute and one second past. By the device's clock it would be decades in.
     */
    private val manifest = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="1970-01-01T00:01:00Z"
             minimumUpdatePeriod="PT1S" timeShiftBufferDepth="PT10S">
            <Location>moved.mpd</Location>
            <UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-head:2014" value="time"/>
            <Period start="PT0S">
                <AdaptationSet contentType="video" mimeType="video/mp4">
                    <SegmentTemplate media="v-${'$'}Number${'$'}.m4s" initialization="v-init.mp4" startNumber="1" timescale="1000" duration="2000"/>
                    <Representation id="v" bandwidth="800000"/>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    /** Built in a plain function: Ktor 3's embeddedServer captures a suspend caller's Job. */
    private fun serve(): String {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/{path...}") {
                    val path = call.request.uri
                    requests.update { it + path }
                    when (path) {
                        "/live.mpd", "/moved.mpd" -> call.respondText(manifest, ContentType.parse("application/dash+xml"))
                        "/time" -> {
                            call.response.header(HttpHeaders.Date, "Thu, 01 Jan 1970 00:02:01 GMT")
                            call.respondBytes(ByteArray(0))
                        }
                        else -> call.respondBytes(ByteArray(0), status = io.ktor.http.HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
        servers += server
        return "http://127.0.0.1:" + runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun aLiveWindowCountsOnTheTimeServersClockAndRefreshesFromTheLocation() = runBlocking {
        val root = serve()
        val client = HttpClient()
        try {
            withTimeout(15_000) {
                val io = assertNotNull(Dash.mediaItemFor("$root/live.mpd", client).io).open()
                io.use {
                    val variant = addresses(readAll(io).decodeToString()).single()
                    val playlist = readAll(assertNotNull(io.openRelated(variant))).decodeToString()
                    // 61 s in: the newest whole segment ends at 60 s, and the buffer reaches back 10 s.
                    assertEquals((26..30).map { "$root/v-$it.m4s" }, addresses(playlist), playlist)
                    assertTrue("/time" in requests.value, "the time server was not asked: ${requests.value}")
                    delay(1_100)
                    readAll(assertNotNull(io.openRelated(variant)))
                    assertEquals(listOf("/live.mpd", "/moved.mpd"), requests.value.filter { it.endsWith(".mpd") })
                }
            }
        } finally {
            client.close()
        }
    }

    private fun addresses(playlist: String): List<String> = playlist.lines().filter { it.isNotBlank() && !it.startsWith("#") }

    private suspend fun readAll(io: MediaIo): ByteArray {
        var out = ByteArray(0)
        val buffer = ByteArray(4096)
        while (true) {
            val count = io.read(buffer, 0, buffer.size)
            if (count < 0) break
            out += buffer.copyOf(count)
        }
        return out
    }
}
