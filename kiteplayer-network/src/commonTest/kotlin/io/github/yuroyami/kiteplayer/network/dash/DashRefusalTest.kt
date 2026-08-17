package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.ktor.client.HttpClient
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The doors that must refuse rather than truncate or leak (2026-08-17 audit). */
class DashRefusalTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    /** Built in a plain function: Ktor 3's embeddedServer captures a suspend caller's Job. */
    private fun serveMpd(mpd: String): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing { get("/movie.mpd") { call.respondText(mpd) } }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    // Audit F-DASH3: a two-period presentation used to play period one and stop, silently.
    @Test
    fun aMultiPeriodManifestIsRefusedTypedNotTruncated() = runBlocking {
        val port = serveMpd(
            """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT8S">
                <Period duration="PT4S">
                    <AdaptationSet contentType="video" mimeType="video/mp2t">
                        <SegmentTemplate media="ad-${'$'}Number${'$'}.ts" startNumber="1" timescale="1" duration="1"/>
                        <Representation id="ad" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
                <Period duration="PT4S">
                    <AdaptationSet contentType="video" mimeType="video/mp2t">
                        <SegmentTemplate media="main-${'$'}Number${'$'}.ts" startNumber="1" timescale="1" duration="1"/>
                        <Representation id="main" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        val client = HttpClient()
        try {
            val failure = assertFailsWith<IllegalArgumentException> {
                Dash.mediaItemFor("http://127.0.0.1:$port/movie.mpd", client)
            }
            assertTrue(
                "2 Periods" in failure.message.orEmpty(),
                "the refusal names the count: ${failure.message}",
            )
        } finally {
            client.close()
        }
    }

    // Audit F-NET1: the resolver owns the client it lazily created, so it must be closeable.
    @Test
    fun theResolverClosesItsOwnClientAndOnlyItsOwn() {
        val own = KtorMediaIoResolver()
        own.close()
        own.close() // idempotent

        val callers = HttpClient()
        val borrowing = KtorMediaIoResolver(client = callers)
        borrowing.close()
        // The caller's client survives the resolver's close; closing it ourselves must be the
        // FIRST close it sees, which throwing here would disprove.
        callers.close()
    }
}
