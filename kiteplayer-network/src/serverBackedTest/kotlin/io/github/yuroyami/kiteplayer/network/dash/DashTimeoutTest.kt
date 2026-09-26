package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.HttpReaderPolicy
import io.github.yuroyami.kiteplayer.network.KtorMediaIoException
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The DASH door limits its own waits, so a silent server fails it on every engine (#242). */
class DashTimeoutTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private val quick = HttpReaderPolicy(connectTimeout = 500.milliseconds, readTimeout = 500.milliseconds)

    private val mpd = """
        <?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT2S">
            <Period>
                <AdaptationSet contentType="video" mimeType="video/mp2t">
                    <SegmentTemplate media="seg-${'$'}Number${'$'}.ts" startNumber="1" timescale="1" duration="1"/>
                    <Representation id="main" bandwidth="1"/>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    /** Built in a plain function: Ktor 3's embeddedServer captures a suspend caller's Job. */
    private fun serve(silentManifest: Boolean): Int {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/movie.mpd") {
                    if (silentManifest) awaitCancellation()
                    call.respondText(mpd, ContentType.Application.Xml)
                }
                get("/seg-{n}.ts") {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val status: HttpStatusCode = HttpStatusCode.OK
                        override val contentLength: Long = 1_000L

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            channel.writeFully(ByteArray(100))
                            channel.flush()
                            awaitCancellation()
                        }
                    })
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun aManifestServerThatNeverAnswersFailsAtTheConnectTimeout() = runBlocking {
        val port = serve(silentManifest = true)
        val client = HttpClient()
        try {
            val started = TimeSource.Monotonic.markNow()
            assertFailsWith<KtorMediaIoException> {
                Dash.manifest("http://127.0.0.1:$port/movie.mpd", client, readerPolicy = quick)
            }
            val took = started.elapsedNow()
            assertTrue(took < 3.seconds, "the manifest fetch must end near the connect timeout, took $took")
        } finally {
            client.close()
        }
    }

    @Test
    fun aSegmentThatStopsSendingFailsTheReadAtTheReadTimeout() = runBlocking {
        val port = serve(silentManifest = false)
        val client = HttpClient()
        try {
            val item = Dash.mediaItemFor("http://127.0.0.1:$port/movie.mpd", client, readerPolicy = quick)
            val reader = checkNotNull(item.io).open()
            try {
                val started = TimeSource.Monotonic.markNow()
                assertFailsWith<KtorMediaIoException> { reader.read(ByteArray(4096), 0, 4096) }
                val took = started.elapsedNow()
                assertTrue(took < 3.seconds, "the segment read must end near the read timeout, took $took")
            } finally {
                reader.close()
            }
        } finally {
            client.close()
        }
    }
}
