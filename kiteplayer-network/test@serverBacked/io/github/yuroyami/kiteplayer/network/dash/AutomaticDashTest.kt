package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolverProvider
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The automatic transport plays a DASH manifest it recognises by its content type, its path or
 * its first bytes, and leaves media alone (#400).
 */
class AutomaticDashTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    /** Every request the server saw, as path and query, with its Authorization header. */
    private val requests = MutableStateFlow(listOf<Pair<String, String?>>())
    private val asked: List<Pair<String, String?>> get() = requests.value

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    private val segment = ByteArray(3000) { (it * 7).toByte() }
    private val media = ByteArray(5000) { (it * 13).toByte() }

    /** Built in a plain function: Ktor 3's embeddedServer captures a suspend caller's Job. */
    private fun serve(): String {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/{path...}") {
                    val path = call.request.uri
                    requests.update { it + (path to call.request.headers["Authorization"]) }
                    val manifest = MANIFEST.encodeToByteArray()
                    when {
                        path.startsWith("/watch") -> call.respondBytes(manifest, ContentType.Text.Plain)
                        path.startsWith("/typed") -> call.respondBytes(manifest, ContentType.parse("application/dash+xml"))
                        path.startsWith("/vod/manifest.mpd") -> call.respondBytes(manifest, ContentType.Application.OctetStream)
                        path.startsWith("/clip") -> call.respondBytes(media, ContentType.Application.OctetStream)
                        path.startsWith("/v-") || path.startsWith("/a-") -> call.respondBytes(segment, ContentType.Application.OctetStream)
                        else -> call.respondBytes(ByteArray(0), status = io.ktor.http.HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
        servers += server
        return "http://127.0.0.1:" + runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @Test
    fun aManifestBehindAnAddressWithNoExtensionPlaysAsHls() = runBlocking {
        val root = serve()
        withTimeout(15_000) {
            KtorMediaIoResolver().use { resolver ->
                val io = assertNotNull(resolver.resolve("$root/watch?id=7"))
                io.use { checkPresentation(root, it) }
            }
        }
    }

    @Test
    fun theAutomaticProviderPlaysItToo() = runBlocking {
        val root = serve()
        withTimeout(15_000) {
            val io = assertNotNull(KtorMediaIoResolverProvider().create().resolve("$root/watch", emptyMap()))
            io.use { checkPresentation(root, it) }
        }
    }

    @Test
    fun aManifestDeclaredByItsTypeOrItsPathPlays() = runBlocking {
        val root = serve()
        withTimeout(15_000) {
            KtorMediaIoResolver().use { resolver ->
                for (address in listOf("$root/typed", "$root/vod/manifest.mpd?token=x")) {
                    val io = assertNotNull(resolver.resolve(address))
                    io.use { assertEquals("application/vnd.apple.mpegurl", it.contentType, "$address did not play as DASH") }
                }
            }
        }
    }

    @Test
    fun mediaWithNoExtensionStaysMediaAndCostsOneRequest() = runBlocking {
        val root = serve()
        withTimeout(15_000) {
            KtorMediaIoResolver().use { resolver ->
                val io = assertNotNull(resolver.resolve("$root/clip"))
                io.use { assertContentEquals(media, readAll(it), "the media came back changed") }
            }
            assertEquals(listOf("/clip"), asked.map { it.first }, "the media was asked for more than once")
        }
    }

    @Test
    fun theItemHeadersReachTheManifestAndTheSegmentsOfItsOrigin() = runBlocking {
        val root = serve()
        withTimeout(15_000) {
            KtorMediaIoResolver().use { resolver ->
                val io = assertNotNull(resolver.resolve("$root/watch", mapOf("Authorization" to "Bearer item")))
                io.use {
                    val variant = playlistAddresses(readAll(it).decodeToString()).first()
                    val segmentAddress = playlistAddresses(readAll(assertNotNull(it.openRelated(variant))).decodeToString())
                        .first { address -> address.contains("/v-") }
                    readAll(assertNotNull(it.openRelated(segmentAddress)))
                }
            }
            val manifest = asked.first { it.first.startsWith("/watch") }
            assertEquals("Bearer item", manifest.second, "the manifest request lost the item's header")
            val segment = asked.first { it.first.startsWith("/v-") }
            assertEquals("Bearer item", segment.second, "a segment of the manifest's origin lost the item's header")
        }
    }

    /** The reader is the HLS stand-in of [MANIFEST], and its segments are the server's. */
    private suspend fun checkPresentation(root: String, io: MediaIo) {
        assertEquals("application/vnd.apple.mpegurl", io.contentType, "the manifest did not play as DASH")
        val master = readAll(io).decodeToString()
        assertTrue("#EXT-X-STREAM-INF" in master, "not a master playlist: $master")
        assertTrue("#EXT-X-MEDIA:TYPE=AUDIO" in master, "the sound set is missing: $master")
        val variant = playlistAddresses(master).first()
        val playlist = readAll(assertNotNull(io.openRelated(variant), "the variant playlist was refused")).decodeToString()
        assertTrue("$root/v-hi-1.m4s" in playlist, "the segments do not resolve against the manifest's address: $playlist")
        val bytes = readAll(assertNotNull(io.openRelated("$root/v-hi-1.m4s"), "a segment was refused"))
        assertContentEquals(segment, bytes)
    }

    private fun playlistAddresses(playlist: String): List<String> =
        playlist.lines().filter { it.isNotBlank() && !it.startsWith("#") }

    private suspend fun readAll(io: MediaIo): ByteArray {
        var out = ByteArray(0)
        val buffer = ByteArray(8192)
        while (true) {
            val count = io.read(buffer, 0, buffer.size)
            if (count < 0) break
            out += buffer.copyOf(count)
        }
        return out
    }

    private companion object {
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- A manifest as a packager writes it, behind whatever address the CDN gives it. -->
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT8S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/mp4">
                        <SegmentTemplate media="v-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s"
                                         initialization="v-${'$'}RepresentationID${'$'}-init.m4s" timescale="1000" duration="4000"/>
                        <Representation id="hi" bandwidth="900000" codecs="avc1.64001e" width="640" height="360"/>
                    </AdaptationSet>
                    <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
                        <SegmentTemplate media="a-${'$'}Number${'$'}.m4s" initialization="a-init.m4s" timescale="1000" duration="4000"/>
                        <Representation id="a" bandwidth="96000" codecs="mp4a.40.2"/>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()
    }
}
