package io.github.yuroyami.kiteplayer.network

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A Shoutcast or Icecast station's song titles (#423): the reader asks for them, takes every title
 * block out of the bytes, and hands each new song over once, with the read that starts after its
 * block. The station sends four runs of [INTERVAL] audio bytes, each a letter of its own, and a block
 * after each of the first three: a song, nothing new, and a second song in windows-1251.
 */
class KtorMediaIoIcyTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
    }

    @Volatile private var asked: String? = null

    private fun block(text: ByteArray): ByteArray {
        val length = (text.size + 15) / 16
        return byteArrayOf(length.toByte()) + text + ByteArray(length * 16 - text.size)
    }

    private fun station(metaInterval: Boolean = true): String {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/radio") {
                    asked = call.request.headers[ICY_METADATA_HEADER]
                    call.response.header("icy-name", "Test FM")
                    call.response.header("icy-genre", "Jazz")
                    if (metaInterval) call.response.header(ICY_METAINT_HEADER, INTERVAL.toString())
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val status: HttpStatusCode = HttpStatusCode.OK
                        override val contentType: ContentType = ContentType.Audio.MPEG

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            // "Кино - Звезда" in windows-1251, written out, as a station in Russia sends it.
                            val cyrillic = intArrayOf(0xCA, 0xE8, 0xED, 0xEE, 0x20, 0x2D, 0x20, 0xC7, 0xE2, 0xE5, 0xE7, 0xE4, 0xE0)
                                .map { it.toByte() }.toByteArray()
                            val second = "StreamTitle='".encodeToByteArray() + cyrillic + "';StreamUrl='';".encodeToByteArray()
                            val blocks = listOf(
                                block("StreamTitle='Miles - Don't Stop';StreamUrl='http://fm.test';".encodeToByteArray()),
                                byteArrayOf(0),
                                block(second),
                            )
                            for (run in 0 until 4) {
                                channel.writeFully(ByteArray(INTERVAL) { ('A' + run).code.toByte() })
                                if (metaInterval && run < blocks.size) channel.writeFully(blocks[run])
                                channel.flush()
                            }
                        }
                    })
                }
            }
        }.start(wait = false)
        servers += server
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port/radio"
    }

    /** Reads [count] audio bytes in reads of up to 300, with the tags each read brought, by the read's first byte. */
    private suspend fun readAll(io: KtorMediaIo, count: Int): Pair<ByteArray, Map<Int, Map<String, String>>> {
        val out = ByteArray(count)
        val tags = LinkedHashMap<Int, Map<String, String>>()
        var at = 0
        while (at < count) {
            val read = io.read(out, at, minOf(300, count - at))
            if (read < 0) break
            io.takeTags()?.let { tags[at] = it }
            at += read
        }
        return out.copyOf(at) to tags
    }

    @Test
    fun theTitlesComeOutOfTheBytesAndEachSongIsHandedOverOnceAtItsFirstByte() = runBlocking {
        val io = KtorMediaIo.open(station())
        try {
            val (bytes, tags) = withTimeout(10.seconds) { readAll(io, 4 * INTERVAL) }
            assertEquals("1", asked, "the reader did not ask for the titles")
            assertEquals(4 * INTERVAL, bytes.size)
            for (run in 0 until 4) {
                assertTrue(
                    bytes.copyOfRange(run * INTERVAL, (run + 1) * INTERVAL).all { it == ('A' + run).code.toByte() },
                    "a title block's bytes reached the audio in run $run",
                )
            }
            assertEquals(listOf(0, INTERVAL, 3 * INTERVAL), tags.keys.toList(), "the tags came at $tags")
            assertEquals(mapOf("icy-name" to "Test FM", "icy-genre" to "Jazz"), tags[0])
            assertEquals(mapOf("StreamTitle" to "Miles - Don't Stop", "StreamUrl" to "http://fm.test"), tags[INTERVAL])
            assertEquals("Кино - Звезда", tags[3 * INTERVAL]?.get("StreamTitle"), "the windows-1251 title read as ${tags[3 * INTERVAL]}")
            assertTrue(!io.seekable)
        } finally {
            io.close()
        }
    }

    @Test
    fun aServerThatSendsNoBlocksIsReadAsItIs() = runBlocking {
        val io = KtorMediaIo.open(station(metaInterval = false))
        try {
            val (bytes, tags) = withTimeout(10.seconds) { readAll(io, 4 * INTERVAL) }
            assertEquals(4 * INTERVAL, bytes.size)
            assertEquals(listOf(0), tags.keys.toList())
            assertNull(tags[0]?.get("StreamTitle"))
        } finally {
            io.close()
        }
    }

    @Test
    fun anItemThatSaysNotToAskIsNotAsked() = runBlocking {
        val io = KtorMediaIo.open(station(metaInterval = false), headers = mapOf("icy-metadata" to "0"))
        try {
            withTimeout(10.seconds) { readAll(io, INTERVAL) }
            assertEquals("0", asked)
        } finally {
            io.close()
        }
    }

    private companion object {
        const val INTERVAL = 1000
    }
}
