package io.github.yuroyami.kiteplayer

import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.network.HttpReaderPolicy
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Waiting for a lost network and opening the item again (#461), with the whole player: real HTTP
 * through the Ktor reader, FFmpeg and the engine, a silent sound device paced on the clock, and a
 * local server that cuts every connection while it is down, as a network that went does.
 */
class NetworkRecoveryPlaybackTest {

    @Test
    fun anItemTheServerCutsOffWaitsAndPlaysOnFromItsPositionWhenTheServerIsBack() = runBlocking {
        val file = requireTestMedia(
            sequenceOf(
                System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
                File("testmedia/$MEDIA"),
                File("../testmedia/$MEDIA"),
            ).filterNotNull().firstOrNull { it.isFile },
            "no $MEDIA to play; run scripts/testmedia.sh",
        )
        CuttingServer(file).use { server ->
            val client = HttpClient()
            val resolver = KtorMediaIoResolver(
                client, policy = HttpReaderPolicy(connectTimeout = 2.seconds, readTimeout = 2.seconds, maxReconnects = 0),
            )
            val output = SilentOutput()
            val network = SwitchedNetwork()
            val player = KitePlayer.create(
                PlayerConfig(
                    backends = Backends(KiteFFmpegMediaBackend(), output),
                    network = NetworkConfig(
                        ioResolver = resolver,
                        // A small window, so the reader cannot have the whole file before the cut.
                        ioCache = IoCachePolicy(readChunkBytes = 64 * 1024, backWindowBytes = 256 * 1024, forwardWindowBytes = 512 * 1024),
                        recovery = NetworkRecovery(maxWait = 60.seconds, status = network),
                    ),
                    hardwareDecode = HwdecPolicy.Off,
                    buffer = BufferPolicy(totalDuration = 1.seconds, stallTimeout = 4.seconds),
                    progressInterval = 20.milliseconds,
                ),
            )
            try {
                withTimeout(15.seconds) { player.open(MediaItem("${server.root}/$MEDIA")) }
                player.play()
                awaitThat("playing past 2 s", 15.seconds) { player.position() >= 2.seconds }
                server.up = false
                network.set(false)
                awaitThat("the wait for the network", 15.seconds) { player.state.value.reconnecting }
                val lostAt = player.position()
                assertEquals(null, player.state.value.error, "the player does not fail")
                val heardBefore = output.nonSilentFrames.get()
                delay(3.seconds)
                assertTrue(player.state.value.reconnecting, "still waiting while the server is down")
                val askedBefore = server.asked.size
                server.up = true
                network.set(true)
                awaitThat("playing again", 15.seconds) {
                    player.state.value.status == PlaybackStatus.Playing && player.position() >= lostAt + 500.milliseconds
                }
                assertTrue(server.asked.size > askedBefore, "the item was asked for again")
                assertTrue(server.asked.drop(askedBefore).any { it != null && it > 0 }, "from where it was, not from its start: ${server.asked}")
                assertTrue(output.nonSilentFrames.get() - heardBefore >= 12_000, "sound came out again")
                assertTrue(!player.state.value.reconnecting)
                println("cut off at $lostAt, opened again, and played on to ${player.position()}")
            } finally {
                try {
                    withContext(NonCancellable) { withTimeout(10.seconds) { player.closeAndAwait() } }
                } finally {
                    output.close()
                    resolver.close()
                    client.close()
                }
            }
        }
    }

    private suspend fun awaitThat(what: String, limit: Duration, condition: () -> Boolean) {
        val reached = withTimeoutOrNull(limit) {
            while (!condition()) delay(10)
            true
        }
        assertTrue(reached == true, "no $what within $limit")
    }

    /** A network status the test switches with the server. */
    private class SwitchedNetwork : NetworkStatus {
        private val watchers = CopyOnWriteArrayList<(Boolean) -> Unit>()

        @Volatile
        private var online = true

        override fun watch(onChange: (online: Boolean) -> Unit): AutoCloseable {
            watchers += onChange
            onChange(online)
            return AutoCloseable { watchers -= onChange }
        }

        fun set(value: Boolean) {
            online = value
            watchers.forEach { it(value) }
        }
    }

    /**
     * Serves [file] with ranges, paced a little faster than it plays. While [up] is false it cuts
     * every response in the middle and closes every new connection before answering. [asked] holds
     * the first byte of each request.
     */
    private class CuttingServer(private val file: File) : AutoCloseable {
        @Volatile
        var up = true
        val asked = CopyOnWriteArrayList<Long?>()
        private val body = file.readBytes()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "recovery-fixture-http").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    if (!up) return@createContext
                    val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
                    val first = range?.substringBefore('-')?.toIntOrNull() ?: 0
                    asked += range?.let { first.toLong() }
                    if (first !in body.indices) {
                        exchange.responseHeaders.add("Content-Range", "bytes */${body.size}")
                        exchange.sendResponseHeaders(416, -1)
                        return@createContext
                    }
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $first-${body.lastIndex}/${body.size}")
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, (body.size - first).toLong())
                    val out = exchange.responseBody
                    var at = first
                    while (at < body.size) {
                        // Cut in the middle: the reader sees a response that ended before its size.
                        if (!up) return@createContext
                        val count = minOf(CHUNK, body.size - at)
                        out.write(body, at, count)
                        out.flush()
                        at += count
                        Thread.sleep(CHUNK_PACE_MS)
                    }
                    out.close()
                }
            }
            this.executor = this@CuttingServer.executor
            start()
        }
        val root = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            up = false
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "the HTTP fixture executor did not stop" }
        }
    }

    /** A device whose callback is paced on the real clock, with no sound card, counting what it heard. */
    private class SilentOutput : OutputBackend, AutoCloseable {
        override val clock: MonotonicClock = MonotonicClock.System
        val nonSilentFrames = AtomicLong()
        private val sinks = CopyOnWriteArrayList<PacedSink>()
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = "recovery PCM capture"
            override suspend fun create(): AudioSink = PacedSink().also { sinks += it }
        }

        override fun close() = sinks.forEach { it.close() }

        private inner class PacedSink : AudioSink {
            private lateinit var format: AudioFormat
            private lateinit var callback: AudioRenderCallback

            @Volatile
            private var running = false
            private var worker: Thread? = null

            override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
                format = request
                callback = render
                return request
            }

            override suspend fun start() = resume()
            override suspend fun stop() = halt()
            override suspend fun drain() = halt()
            override suspend fun setPaused(paused: Boolean): Boolean {
                if (paused) halt() else resume()
                return true
            }

            override val deviceBufferFrames: Int get() = if (::format.isInitialized) format.sampleRate / 100 else 480
            override fun latencyNanos(): Long = 10_000_000L
            override val latencyQuality: LatencyQuality = LatencyQuality.Exact
            override val events: Flow<AudioSinkEvent> = emptyFlow()
            override fun close() = halt()

            private fun resume() {
                if (running) return
                running = true
                val since = clock.nanos()
                worker = thread(isDaemon = true, name = "recovery-pcm-capture") {
                    val frames = deviceBufferFrames
                    val buffer = Samples(format, frames)
                    var pulled = 0L
                    while (running) {
                        val due = (clock.nanos() - since) * format.sampleRate / 1_000_000_000L
                        while (running && pulled + frames <= due) {
                            buffer.samples.fill(0f)
                            pulled += frames
                            val deadline = since + pulled * 1_000_000_000L / format.sampleRate + latencyNanos()
                            val written = callback.onRender(buffer, frames, deadline)
                            var heard = 0L
                            for (frame in 0 until written) {
                                if ((0 until format.channels).any { abs(buffer.samples[frame * format.channels + it]) > 0.001f }) heard++
                            }
                            nonSilentFrames.addAndGet(heard)
                        }
                        Thread.sleep(2)
                    }
                }
            }

            private fun halt() {
                running = false
                worker?.join(2_000)
                worker = null
            }
        }
    }

    private class Samples(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
        val samples = FloatArray(frames * format.channels)
        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
            source.copyInto(samples, destinationFrameOffset * format.channels, sourceOffset, sourceOffset + frames * format.channels)
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            samples.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
        }
    }

    private companion object {
        const val MEDIA = "baseline.mkv"

        // 64 KiB every 28 ms is about 2.3 MB a second, a little faster than the clip's 2 MB.
        const val CHUNK = 64 * 1024
        const val CHUNK_PACE_MS = 28L
    }
}
