package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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

    /**
     * Built in a plain function: Ktor 3's embeddedServer captures a suspend caller's Job. [file] is
     * served at /movie.mp4 with Range support.
     */
    private fun serveMpd(mpd: String, file: ByteArray = ByteArray(0)): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/movie.mpd") { call.respondText(mpd) }
                get("/movie.mp4") {
                    val range = call.request.headers[HttpHeaders.Range]?.removePrefix("bytes=")
                    if (range == null) {
                        call.respondBytes(file)
                    } else {
                        val start = range.substringBefore('-').toInt()
                        val end = range.substringAfter('-').toIntOrNull() ?: (file.size - 1)
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/${file.size}")
                        call.respondBytes(file.copyOfRange(start, end + 1), status = HttpStatusCode.PartialContent)
                    }
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    /** Two Periods of four one-second segments in [mimeType], named by [extension]. */
    private fun twoPeriods(mimeType: String, extension: String) = """
        <?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT8S">
            <Period duration="PT4S">
                <AdaptationSet contentType="video" mimeType="$mimeType">
                    <SegmentTemplate media="ad-${'$'}Number${'$'}.$extension" startNumber="1" timescale="1" duration="1"/>
                    <Representation id="ad" bandwidth="1"/>
                </AdaptationSet>
            </Period>
            <Period duration="PT4S">
                <AdaptationSet contentType="video" mimeType="$mimeType">
                    <SegmentTemplate media="main-${'$'}Number${'$'}.$extension" startNumber="1" timescale="1" duration="1"/>
                    <Representation id="main" bandwidth="1"/>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    // A two-period presentation used to play period one and stop, silently. Periods the HLS path
    // carries now play joined (#403); the one-stream door still plays exactly one, so it refuses.
    @Test
    fun aMultiPeriodManifestTheOneStreamDoorWouldTruncateIsRefusedTyped() = runBlocking {
        val port = serveMpd(twoPeriods("video/x-flv", "flv"))
        val client = HttpClient()
        try {
            val failure = assertFailsWith<DashUnsupportedException> {
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

    @Test
    fun aMultiPeriodManifestTheHlsPathCarriesIsNotRefused() = runBlocking {
        val port = serveMpd(twoPeriods("video/mp2t", "ts"))
        val client = HttpClient()
        try {
            Dash.mediaItemFor("http://127.0.0.1:$port/movie.mpd", client)
            Unit
        } finally {
            client.close()
        }
    }

    // Digital rights management is out of scope by decision (#404): an encrypted presentation
    // used to be played as if it were clear, which decodes to noise.
    @Test
    fun anEncryptedManifestIsRefusedTyped() = runBlocking {
        val port = serveMpd(
            """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/mp4">
                        <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
                        <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                        <SegmentTemplate media="v-${'$'}Number${'$'}.m4s" timescale="1" duration="2"/>
                        <Representation id="v" bandwidth="2"/>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        val client = HttpClient()
        try {
            val failure = assertFailsWith<DashUnsupportedException> {
                Dash.mediaItemFor("http://127.0.0.1:$port/movie.mpd", client)
            }
            assertTrue("encrypted" in failure.message.orEmpty(), "the refusal says why: ${failure.message}")
            assertTrue("edef8ba9" in failure.message.orEmpty(), "the refusal names the system: ${failure.message}")
        } finally {
            client.close()
        }
    }

    // Video and audio in sets of their own, in a container the HLS path does not take. The
    // one-stream door plays one set, so the video used to play silent. Fragmented MP4, MPEG-TS
    // and WebM play through HLS instead (DashHlsTest).
    @Test
    fun aSeparateAudioSetIsRefusedTypedNotPlayedSilent() = runBlocking {
        val port = serveMpd(
            """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/x-flv">
                        <SegmentTemplate media="v-${'$'}Number${'$'}.flv" timescale="1" duration="2"/>
                        <Representation id="v" bandwidth="2"/>
                    </AdaptationSet>
                    <AdaptationSet contentType="audio" mimeType="audio/x-flv">
                        <SegmentTemplate media="a-${'$'}Number${'$'}.flv" timescale="1" duration="2"/>
                        <Representation id="a" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        val client = HttpClient()
        try {
            val failure = assertFailsWith<DashUnsupportedException> {
                Dash.mediaItemFor("http://127.0.0.1:$port/movie.mpd", client)
            }
            assertTrue("audio" in failure.message.orEmpty(), "the refusal names the audio: ${failure.message}")
        } finally {
            client.close()
        }
    }

    // A live manifest of fragmented MP4 plays through HLS (DashHlsTest). One that HLS cannot
    // carry is refused, because the one-stream door has no live window.
    @Test
    fun aLiveManifestIsRefusedTyped() = runBlocking {
        val port = serveMpd(
            """
            <MPD type="dynamic">
                <Period>
                    <AdaptationSet contentType="video">
                        <SegmentTemplate media="v-${'$'}Number${'$'}.flv" timescale="1" duration="2"/>
                        <Representation id="v" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        val client = HttpClient()
        try {
            val failure = assertFailsWith<DashUnsupportedException> {
                Dash.mediaItemFor("http://127.0.0.1:$port/movie.mpd", client)
            }
            assertTrue("live" in failure.message.orEmpty(), "the refusal says why: ${failure.message}")
        } finally {
            client.close()
        }
    }

    // A single-file representation used to be fetched whole into memory and refused above the
    // segment ceiling. It now streams with range requests, so a long file plays and seeks. One
    // with a SegmentBase plays through HLS instead (DashHlsTest), so this one names none.
    @Test
    fun aSingleFileRepresentationStreamsWithRangesAndSeeks() = runBlocking {
        val file = ByteArray(4096) { index -> (index * 7 + 3).toByte() }
        val port = serveMpd(
            """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/mp4">
                        <Representation id="v" bandwidth="1">
                            <BaseURL>movie.mp4</BaseURL>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
            file,
        )
        val client = HttpClient()
        try {
            // A ceiling far below the file: one whole fetch would be refused.
            val item = Dash.mediaItemFor("http://127.0.0.1:$port/movie.mpd", client, maxSegmentBytes = 1024)
            val io = checkNotNull(item.io).open()
            try {
                assertTrue(io.seekable, "a ranged single file is seekable")
                assertEquals(4096L, io.size)
                io.seek(3000)
                val read = ByteArray(16)
                var at = 0
                while (at < read.size) {
                    val count = io.read(read, at, read.size - at)
                    check(count > 0) { "short read at $at" }
                    at += count
                }
                assertContentEquals(file.copyOfRange(3000, 3016), read)
            } finally {
                io.close()
            }
        } finally {
            client.close()
        }
    }

    // The XML reader's own length limit follows the byte ceiling the caller raised.
    @Test
    fun aManifestLongerThanTheDefaultParsesUnderARaisedCeiling() = runBlocking {
        val padding = "x".repeat(9 * 1024 * 1024)
        val port = serveMpd(
            "<MPD type=\"static\" mediaPresentationDuration=\"PT2S\"><!--$padding-->" +
                "<Period><AdaptationSet contentType=\"video\"><Representation id=\"v\" bandwidth=\"1\"/>" +
                "</AdaptationSet></Period></MPD>",
        )
        val client = HttpClient()
        try {
            val manifest = Dash.manifest("http://127.0.0.1:$port/movie.mpd", client, maxManifestBytes = 16L shl 20)
            assertEquals("v", manifest.periods.single().adaptationSets.single().representations.single().id)
        } finally {
            client.close()
        }
    }

    // The resolver owns the client it lazily created, so it must be closeable.
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
