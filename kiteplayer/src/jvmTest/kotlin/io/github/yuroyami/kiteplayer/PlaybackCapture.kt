package io.github.yuroyami.kiteplayer

import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

// What the whole-player tests on the desktop JVM watch a real player through: a backend that notes
// each decoded sound buffer, a screen that reads each picture, a sound device paced on the real
// clock with no sound card, and an HTTP server of fixture files.

internal data class Timed(val ptsMicros: Long, val generation: Generation)

/** Observe real decoded PCM timestamps without modifying buffers or changing their ownership. */
internal class ObservedBackend : MediaBackend {
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

/** A picture the screen was handed, at [atNanos] of the system clock, [lateNanos] after the time the engine gave it. */
internal data class Picture(
    val ptsMicros: Long, val generation: Generation, val checksum: Long, val size: VideoSize,
    val atNanos: Long, val lateNanos: Long,
)

internal class CapturingScreen : VideoRenderer {
    val pictures = CopyOnWriteArrayList<Picture>()
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()
    override fun supports(format: PlayerPixelFormat): Boolean = format != PlayerPixelFormat.Opaque
    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = frame.use {
        val now = MonotonicClock.System.nanos()
        val decoded = frame as? SoftwareReadableFrame ?: return false
        val pixels = ByteArray(decoded.planeStride(0) * decoded.planeHeight(0))
        decoded.copyPlane(0, pixels)
        val checksum = CRC32().apply { update(pixels) }.value
        pictures += Picture(frame.pts.micros, frame.generation, checksum, frame.size, now, now - targetNanos)
        true
    }
    override fun vsyncIntervalNanos(): Long? = null
    override fun setViewport(width: Int, height: Int, scale: Float) = Unit
    override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit
    override val events: Flow<RendererEvent> = emptyFlow()
    override fun close() = Unit
}

/** A device callback paced on the engine's real clock, with no sound card or audible output. */
internal class CapturingOutput(
    /** Frames at the start that [longestSilence] leaves out: an encoder's first packets fade in. */
    private val silenceAfter: Long = 0,
) : OutputBackend, AutoCloseable {
    override val clock: MonotonicClock = MonotonicClock.System
    val nonSilentFrames = AtomicLong()

    /** The longest run of silent frames the device was given after its first frame of sound and [silenceAfter]. */
    val longestSilence = AtomicLong()
    private val silentRun = AtomicLong()

    /** How many frames the device had been given when the run of [longestSilence] ended. */
    val longestSilenceAt = AtomicLong()
    private val given = AtomicLong()
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
                            for (frame in 0 until frames) {
                                given.incrementAndGet()
                                if ((0 until format.channels).any { channel -> abs(buffer.samples[frame * format.channels + channel]) > 0.001f }) {
                                    nonSilent++
                                    silentRun.set(0)
                                } else if (nonSilentFrames.get() + nonSilent > silenceAfter) {
                                    val run = silentRun.incrementAndGet()
                                    if (run > longestSilence.get()) {
                                        longestSilence.set(run)
                                        longestSilenceAt.set(given.get())
                                    }
                                }
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

internal class FloatBuffer(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
    val samples = FloatArray(frames * format.channels)
    override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
        source.copyInto(samples, destinationFrameOffset * format.channels, sourceOffset, sourceOffset + frames * format.channels)
    }
    override fun writeSilence(frameOffset: Int, frames: Int) {
        samples.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
    }
}

/** The same range-serving fixture protocol as DashThroughHlsTest, with an owned executor. */
internal class FixtureServer(private val media: File) : AutoCloseable {
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
