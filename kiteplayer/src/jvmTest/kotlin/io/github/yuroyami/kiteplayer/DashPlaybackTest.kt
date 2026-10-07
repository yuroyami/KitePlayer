package io.github.yuroyami.kiteplayer

import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.network.HttpReaderPolicy
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.AudioDecoderFactory
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole player, not just its packet reader, plays separate DASH audio and video sets (#295).
 * Real HTTP, DASH translation, FFmpeg codecs and engine feed a silent clock-paced sound device
 * and a screen that reads the decoded pixels. Both continue at 60 s after a precise seek.
 */
class DashPlaybackTest {

    @Test
    fun separateSetsReachTheSoundDeviceAndScreenBeforeAndAfterASeek() = runBlocking {
        val media = requireTestMedia(
            sequenceOf(
                System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, "dash") },
                File("testmedia/dash"),
                File("../testmedia/dash"),
            ).filterNotNull().firstOrNull { File(it, "separate.mpd").isFile },
            "testmedia/dash/separate.mpd is missing; run scripts/testmedia.sh",
        )
        // This fixture is never optional in testmedia.sh. A partial fixture must fail, not skip.
        val required = listOf("separate.mpd") + (0..2).flatMap { representation ->
            listOf("separate-$representation-init.m4s") + (1..35).map { segment ->
                "separate-$representation-${segment.toString().padStart(5, '0')}.m4s"
            }
        }
        assertEquals(emptyList(), required.filterNot { File(media, it).isFile }, "the separate DASH fixture is incomplete")

        FixtureServer(media).use { server ->
            val client = HttpClient()
            val resolver = KtorMediaIoResolver(
                client, policy = HttpReaderPolicy(connectTimeout = 5.seconds, readTimeout = 5.seconds, maxReconnects = 0),
            )
            val backend = ObservedBackend()
            val output = CapturingOutput()
            val screen = CapturingScreen()
            val player = KitePlayer.create(
                PlayerConfig(
                    backends = Backends(backend, output),
                    network = NetworkConfig(ioResolver = resolver),
                    hardwareDecode = HwdecPolicy.Off,
                    buffer = BufferPolicy(totalDuration = 4.seconds, stallTimeout = 10.seconds),
                    progressInterval = 20.milliseconds,
                ),
            )
            try {
                withTimeout(15.seconds) {
                    player.attachRendererAndAwait(screen)
                    player.open(MediaItem("${server.root}/separate.mpd"))
                }
                assertNotNull(player.state.value.tracks.selectedAudio, "the separate audio set was not selected")
                assertTrue(player.state.value.tracks.all.any { it.kind == TrackKind.Video }, "no video track")
                player.play()
                awaitOutput("at the start", player, output) {
                    val pictures = screen.pictures.filter { it.ptsMicros in 0L..3_000_000L }
                    player.position() >= 1.seconds && output.nonSilentFrames.get() >= 24_000 &&
                        pictures.size >= 15 && pictures.last().ptsMicros - pictures.first().ptsMicros >= 500_000L
                }
                val firstPictures = screen.pictures.toList()
                assertTrue(firstPictures.map { it.checksum }.distinct().size >= 2, "the renderer saw no changing decoded picture")
                assertTrue(backend.audio.any { it.ptsMicros in 0L..2_000_000L }, "no audio decoded near the beginning")
                val firstGeneration = firstPictures.last().generation
                val stoppedBeforeSeek = output.stops.get()

                withTimeout(15.seconds) { player.seek(60.seconds, SeekMode.Precise) }
                assertTrue(output.stops.get() > stoppedBeforeSeek, "the seek never stopped and discarded the old sound")
                val heardAfterSeekReturned = output.nonSilentFrames.get()
                // A progress flow can still contain its old value after a seek. Require a new
                // decoded generation at the target and fresh non-silent PCM after seek returned.
                awaitOutput("after seeking to 60 s", player, output) {
                    val pictures = screen.pictures.filter { it.generation != firstGeneration && it.ptsMicros in 60_000_000L..64_000_000L }
                    val sound = backend.audio.filter { it.generation != firstGeneration && it.ptsMicros in 59_900_000L..64_000_000L }
                    player.position() >= 61.seconds &&
                        output.nonSilentFrames.get() - heardAfterSeekReturned >= 24_000 &&
                        pictures.size >= 15 && pictures.last().ptsMicros - pictures.first().ptsMicros >= 500_000L &&
                        sound.size >= 20 && sound.last().ptsMicros - sound.first().ptsMicros >= 500_000L
                }
                val after = screen.pictures.filter { it.generation != firstGeneration }
                assertTrue(after.first().ptsMicros in 60_000_000L..60_100_000L, "the first picture after the precise seek was at ${after.first().ptsMicros} us")
                assertTrue(after.map { it.checksum }.distinct().size >= 2, "video stopped changing after the seek")
                assertEquals(PlaybackStatus.Playing, player.state.value.status)
                assertTrue(server.asked.any { it.startsWith("separate-1-0003") }, "no video segment near 60 s was requested: ${server.asked}")
                assertTrue(server.asked.any { it.startsWith("separate-2-0003") }, "no audio segment near 60 s was requested: ${server.asked}")
                println("DASH played non-silent PCM and decoded video, sought to 60 s, and advanced to ${player.position()}")
            } finally {
                // Interrupt a native read as well as cancelling a coroutine, including on timeout.
                backend.source.get()?.interrupt()
                try {
                    withContext(NonCancellable) { withTimeout(10.seconds) { player.closeAndAwait() } }
                } finally {
                    try {
                        output.close()
                    } finally {
                        try {
                            screen.close()
                        } finally {
                            try {
                                resolver.close()
                            } finally {
                                client.close()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun awaitOutput(
        phase: String,
        player: KitePlayer,
        output: CapturingOutput,
        ready: () -> Boolean,
    ) {
        val reached = withTimeoutOrNull(15.seconds) {
            while (true) {
                output.failure.get()?.let { throw AssertionError("the sound device failed $phase", it) }
                check(player.state.value.error == null) { "player failed $phase: ${player.state.value.error}" }
                if (ready()) break
                delay(10)
            }
            true
        }
        assertTrue(reached == true, "no progressing picture and non-silent sound $phase: ${player.state.value}, ${player.progress.value}; nonSilentFrames=${output.nonSilentFrames.get()}")
    }

    private data class Timed(val ptsMicros: Long, val generation: Generation)

    /** Observe real decoded PCM timestamps without modifying buffers or changing their ownership. */
    private class ObservedBackend : MediaBackend {
        val source = AtomicReference<PlayerMediaSource?>()
        val audio = CopyOnWriteArrayList<Timed>()
        override suspend fun open(media: MediaItem): BackendSession {
            val real = KiteFFmpegMediaBackend().open(media)
            source.set(real.source)
            return object : BackendSession by real {
                override fun close() {
                    this@ObservedBackend.source.compareAndSet(real.source, null)
                    real.close()
                }
                override val audioDecoders: List<AudioDecoderFactory> = real.audioDecoders.map { factory ->
                    object : AudioDecoderFactory by factory {
                        override suspend fun create(stream: PlayerStreamInfo): AudioDecoder? {
                            val decoder = factory.create(stream) ?: return null
                            return object : AudioDecoder by decoder {
                                override suspend fun receive(): AudioBuffer? = decoder.receive()?.also {
                                    audio += Timed(it.pts.micros, it.generation)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private data class Picture(val ptsMicros: Long, val generation: Generation, val checksum: Long)

    private class CapturingScreen : VideoRenderer {
        val pictures = CopyOnWriteArrayList<Picture>()
        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()
        override fun supports(format: PlayerPixelFormat): Boolean = format != PlayerPixelFormat.Opaque
        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = frame.use {
            val decoded = frame as? SoftwareReadableFrame ?: return false
            val pixels = ByteArray(decoded.planeStride(0) * decoded.planeHeight(0))
            decoded.copyPlane(0, pixels)
            val checksum = CRC32().apply { update(pixels) }.value
            pictures += Picture(frame.pts.micros, frame.generation, checksum)
            true
        }
        override fun vsyncIntervalNanos(): Long? = null
        override fun setViewport(width: Int, height: Int, scale: Float) = Unit
        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit
        override val events: Flow<RendererEvent> = emptyFlow()
        override fun close() = Unit
    }

    /** A device callback paced on the engine's real clock, with no sound card or audible output. */
    private class CapturingOutput : OutputBackend, AutoCloseable {
        override val clock: MonotonicClock = MonotonicClock.System
        val nonSilentFrames = AtomicLong()
        val stops = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        private val sinks = CopyOnWriteArrayList<CapturingSink>()
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = "DASH PCM capture"
            override suspend fun create(): AudioSink = CapturingSink().also { sinks += it }
        }
        override fun close() = sinks.forEach { it.close() }

        private inner class CapturingSink : AudioSink {
            private lateinit var format: AudioFormat
            private lateinit var callback: AudioRenderCallback
            @Volatile private var running = false
            private var worker: Thread? = null
            override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
                format = request
                callback = render
                return request
            }
            override suspend fun start() = resume()
            override suspend fun stop() { halt(); stops.incrementAndGet() }
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
                worker = thread(isDaemon = true, name = "dash-pcm-capture") {
                    try {
                        val frames = deviceBufferFrames
                        val buffer = FloatBuffer(format, frames)
                        var pulled = 0L
                        while (running) {
                            val due = (clock.nanos() - since) * format.sampleRate / 1_000_000_000L
                            while (running && pulled + frames <= due) {
                                buffer.samples.fill(0f)
                                pulled += frames
                                val deadline = since + pulled * 1_000_000_000L / format.sampleRate + latencyNanos()
                                val written = callback.onRender(buffer, frames, deadline)
                                check(written in 0..frames) { "the callback wrote $written of $frames frames" }
                                buffer.writeSilence(written, frames - written)
                                var nonSilent = 0L
                                for (frame in 0 until written) {
                                    if ((0 until format.channels).any { channel -> abs(buffer.samples[frame * format.channels + channel]) > 0.001f }) nonSilent++
                                }
                                nonSilentFrames.addAndGet(nonSilent)
                            }
                            Thread.sleep(2)
                        }
                    } catch (problem: Throwable) {
                        failure.compareAndSet(null, problem)
                    }
                }
            }
            private fun halt() {
                running = false
                worker?.let {
                    it.join(2_000)
                    check(!it.isAlive) { "the PCM capture callback did not stop" }
                }
                worker = null
            }
        }
    }

    private class FloatBuffer(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
        val samples = FloatArray(frames * format.channels)
        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
            source.copyInto(samples, destinationFrameOffset * format.channels, sourceOffset, sourceOffset + frames * format.channels)
        }
        override fun writeSilence(frameOffset: Int, frames: Int) {
            samples.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
        }
    }

    /** The same range-serving fixture protocol as DashThroughHlsTest, with an owned executor. */
    private class FixtureServer(private val media: File) : AutoCloseable {
        val asked = CopyOnWriteArrayList<String>()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "dash-fixture-http").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    val path = exchange.requestURI.path.removePrefix("/")
                    asked += path
                    val file = File(media, path)
                    if (!file.isFile || file.parentFile != media) {
                        exchange.sendResponseHeaders(404, -1)
                        return@createContext
                    }
                    val body = file.readBytes()
                    val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
                    val first = range?.substringBefore('-')?.toIntOrNull() ?: 0
                    val last = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(body.lastIndex) ?: body.lastIndex
                    if (first !in body.indices || last < first) {
                        exchange.responseHeaders.add("Content-Range", "bytes */${body.size}")
                        exchange.sendResponseHeaders(416, -1)
                        return@createContext
                    }
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    if (path.endsWith(".mpd")) exchange.responseHeaders.add("Content-Type", "application/dash+xml")
                    if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${body.size}")
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, (last - first + 1).toLong())
                    exchange.responseBody.use { it.write(body, first, last - first + 1) }
                }
            }
            this.executor = this@FixtureServer.executor
            start()
        }
        val root = "http://127.0.0.1:${server.address.port}"
        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "the HTTP fixture executor did not stop" }
        }
    }
}
