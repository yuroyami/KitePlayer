package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.IoCachePolicy
import io.github.yuroyami.kiteplayer.NetworkConfig
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Calls only core playback APIs. Nothing references the optional provider to keep it reachable. */
class AutomaticNetworkTest {
    @Test
    fun dependencyPresenceSuppliesAReaderToTheDirectCoreFactory(): Unit = runBlocking {
        withTimeout<Unit>(15_000) {
            val expected = byteArrayOf(0, 42, 127, -128, -1)
            var authorization: String? = null
            val server = embeddedServer(CIO, port = 0) {
                routing {
                    get("/media") {
                        authorization = call.request.headers["Authorization"]
                        call.respondBytes(expected)
                    }
                }
            }.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            var reader: MediaIo? = null
            var received: ByteArray? = null
            val backend = object : MediaBackend {
                override suspend fun open(media: MediaItem): BackendSession {
                    reader = media.io?.open()
                    reader?.let { io ->
                        val bytes = ByteArray(expected.size)
                        var at = 0
                        while (at < bytes.size) {
                            val count = io.read(bytes, at, bytes.size - at)
                            check(count > 0) { "short HTTP body" }
                            at += count
                        }
                        received = bytes
                    }
                    // Stop after observing the transport seam. Core owns cleanup of this failed open.
                    error("fixture has no decoder")
                }
            }
            val output = object : OutputBackend {
                override val clock: MonotonicClock = MonotonicClock.System
                override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
                    override val name: String = "unused test output"
                    override suspend fun create(): AudioSink = error("fixture never decodes")
                }
            }
            val player = KitePlayer.create(PlayerConfig(
                backends = Backends(backend, output),
                network = NetworkConfig(ioCache = IoCachePolicy(enabled = false)),
            ))
            try {
                runCatching { player.open(MediaItem("http://127.0.0.1:$port/media", headers = mapOf("Authorization" to "Bearer item"))) }
                assertNotNull(reader, "adding network must supply HTTP IO through direct KitePlayer.create")
                assertContentEquals(expected, received)
                assertEquals("Bearer item", authorization, "automatic transport must retain item authentication")
                assertFails("core must close the automatic reader after the backend refuses") {
                    reader!!.read(ByteArray(1), 0, 1)
                }
            } finally {
                player.closeAndAwait()
                server.stop(100, 500)
            }
        }
    }

    /** The automatic transport takes the store of the player's own configuration (#547). */
    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun theStoreOfThePlayersConfigurationReachesTheAutomaticTransport(): Unit = runBlocking {
        withTimeout<Unit>(30_000) {
            val segment = ByteArray(20_000) { (it * 3).toByte() }
            val asked = AtomicReference(listOf<String>())
            fun note(what: String) {
                while (true) {
                    val old = asked.load()
                    if (asked.compareAndSet(old, old + what)) return
                }
            }
            val server = embeddedServer(CIO, port = 0) {
                routing {
                    get("/list.m3u8") {
                        note("list")
                        call.respondBytes("#EXTM3U\n#EXTINF:4,\nseg.ts\n#EXT-X-ENDLIST\n".encodeToByteArray())
                    }
                    get("/seg.ts") {
                        note("segment")
                        call.response.headers.append("Cache-Control", "max-age=3600")
                        call.respondBytes(segment)
                    }
                }
            }.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val store = assertNotNull(directorySegmentStore(MemoryStoreFiles(), "/cache", 1_000_000))
            suspend fun readAll(io: MediaIo): ByteArray {
                var out = ByteArray(0)
                val chunk = ByteArray(8_192)
                while (true) {
                    val count = io.read(chunk, 0, chunk.size)
                    if (count < 0) return out
                    out += chunk.copyOf(count)
                }
            }
            // One player lifetime: the backend reads the playlist and then the segment it names.
            suspend fun lifetime(): ByteArray? {
                var received: ByteArray? = null
                val backend = object : MediaBackend {
                    override suspend fun open(media: MediaItem): BackendSession {
                        val io = checkNotNull(media.io).open()
                        readAll(io)
                        received = checkNotNull(io.openRelated("http://127.0.0.1:$port/seg.ts")).use { readAll(it) }
                        error("fixture has no decoder")
                    }
                }
                val output = object : OutputBackend {
                    override val clock: MonotonicClock = MonotonicClock.System
                    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
                        override val name: String = "unused test output"
                        override suspend fun create(): AudioSink = error("fixture never decodes")
                    }
                }
                val player = KitePlayer.create(PlayerConfig(backends = Backends(backend, output), network = NetworkConfig(segmentStore = store)))
                try {
                    runCatching { player.open(MediaItem("http://127.0.0.1:$port/list.m3u8")) }
                } finally {
                    player.closeAndAwait()
                }
                return received
            }
            try {
                assertContentEquals(segment, lifetime())
                assertContentEquals(segment, lifetime(), "the second player did not get the segment")
                assertEquals(listOf("list", "segment", "list"), asked.load(), "the second player's segment did not come from the store")
                assertEquals(20_000L, store.sizeBytes)
            } finally {
                store.close()
                server.stop(100, 500)
            }
        }
    }
}
