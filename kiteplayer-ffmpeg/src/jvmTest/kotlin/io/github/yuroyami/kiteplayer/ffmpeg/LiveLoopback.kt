package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * What the live tests play through and what they measure with (#395).
 *
 * The clip flashes white and beeps at the start of every second for a tenth of a second, so the
 * gap between a beep as heard and the flash as shown is the sync error, read off the output rather
 * than asked of the engine. Each beep's pitch names its second, 400 Hz plus 200 Hz for each second
 * modulo 8, so a beep heard says which moment of the sender's media it is, which is how far behind
 * the sender the player is.
 */

/** The `ffmpeg` command line on PATH, or null when there is none and a live test must skip. */
internal val ffmpegCli: String? by lazy {
    runCatching {
        val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
        process.inputStream.readBytes()
        if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) "ffmpeg" else null
    }.getOrNull()
}

/** A running `ffmpeg` command line whose output goes to [log]. */
internal class FFmpegProcess(arguments: List<String>, val log: File) : AutoCloseable {
    private val process: Process = ProcessBuilder(listOf(checkNotNull(ffmpegCli)) + arguments)
        .redirectErrorStream(true)
        .redirectOutput(log)
        .start()

    val isAlive: Boolean get() = process.isAlive

    /** Waits for the command to end and gives its exit code, or null when it was still running. */
    fun waitFor(seconds: Long): Int? = if (process.waitFor(seconds, TimeUnit.SECONDS)) process.exitValue() else null

    fun logText(): String = runCatching { log.readText().takeLast(2_000) }.getOrDefault("")

    @Volatile
    private var frozen = false

    /** Stops the command where it is, connections open, as a sender that hangs without hanging up. */
    fun freeze() {
        check(ProcessBuilder("kill", "-STOP", process.pid().toString()).start().waitFor() == 0) { "the sender did not stop" }
        frozen = true
    }

    override fun close() {
        // A stopped process takes no signal but a kill until it runs again.
        if (frozen) runCatching { ProcessBuilder("kill", "-CONT", process.pid().toString()).start().waitFor() }
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

/** The marker clips, made once per test run by the command line. */
internal object MarkerClip {
    val dir: File by lazy { kotlin.io.path.createTempDirectory("kite-live").toFile().apply { deleteOnExit() } }

    /** The pitch of the beep at the start of [second]. */
    fun pitchOf(second: Int): Int = 400 + 200 * (second % 8)

    /** The second, modulo 8, that a beep of [hertz] begins. */
    fun secondOf(hertz: Double): Int = ((hertz - 400) / 200).roundToInt().coerceIn(0, 7)

    /** [seconds] of the marker clip, H.264 and AAC in MP4, a keyframe every half second. */
    fun make(seconds: Int): File {
        val file = File(dir, "marker-$seconds.mp4")
        if (file.isFile) return file
        // Quoted, so the commas stay inside each expression. No shell reads these arguments.
        val flash = "drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='lt(mod(t,1),0.1)'"
        val beep = "aevalsrc='0.8*sin(2*PI*(400+200*mod(floor(t),8))*t)*lt(mod(t,1),0.1)':s=48000:c=stereo:d=$seconds"
        FFmpegProcess(
            listOf(
                "-v", "error", "-y",
                "-f", "lavfi", "-i", "color=c=black:s=320x240:r=30:d=$seconds,$flash",
                "-f", "lavfi", "-i", beep,
                "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "15", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-shortest", file.absolutePath,
            ),
            File(dir, "marker-$seconds.log"),
        ).use { maker ->
            check(maker.waitFor(120) == 0 && file.isFile) { "the marker clip was not made: ${maker.logText()}" }
        }
        return file
    }
}

/** A beep as heard: when its first loud sample became audible, and its pitch. */
internal data class HeardBeep(val atNanos: Long, val hertz: Double)

/**
 * A sound device with no sound card: a thread pulls the engine's samples at the pace of the clock,
 * one buffer of 10 ms at a time on an exact running total, and reports for each buffer the instant
 * its last frame becomes audible, one buffer later. It listens for the beeps as it pulls.
 */
internal class PacedOutput : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    val beeps: MutableList<HeardBeep> = CopyOnWriteArrayList()

    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "paced"
        override suspend fun create(): AudioSink = PacedSink()
    }

    private inner class PacedSink : AudioSink {
        private var render: AudioRenderCallback? = null
        private var format: AudioFormat? = null

        @Volatile
        private var running = false
        private var puller: Thread? = null

        /** Frames pulled since the pace was last set, and when it was set. */
        private var pulled = 0L
        private var since = 0L

        // The beep listener, on the puller thread only: the quiet frames since the last loud one,
        // and for the beep in progress, when it began, its frames so far and its sign changes.
        private var quiet = Long.MAX_VALUE
        private var inBeep = false
        private var onsetNanos = 0L
        private var span = 0
        private var crossings = 0
        private var lastSign = 0

        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            this.render = render
            format = request
            return request
        }

        override suspend fun start() = resume()

        override suspend fun stop() = halt()

        override suspend fun drain() = halt()

        override suspend fun setPaused(paused: Boolean): Boolean {
            if (paused) halt() else resume()
            return true
        }

        override val deviceBufferFrames: Int get() = (format?.sampleRate ?: 48_000) / 100

        override fun latencyNanos(): Long = BUFFER_NANOS

        override val latencyQuality: LatencyQuality = LatencyQuality.Exact

        override val events: Flow<AudioSinkEvent> = emptyFlow()

        override fun close() = halt()

        private fun resume() {
            if (running) return
            val format = checkNotNull(format)
            val callback = checkNotNull(render)
            running = true
            pulled = 0
            since = clock.nanos()
            puller = thread(isDaemon = true, name = "paced-sink") {
                val frames = deviceBufferFrames
                val buffer = FloatBuffer(format, frames)
                while (running) {
                    val due = (clock.nanos() - since) * format.sampleRate / 1_000_000_000L
                    while (running && pulled + frames <= due) {
                        buffer.clear()
                        pulled += frames
                        val deadline = since + pulled * 1_000_000_000L / format.sampleRate + BUFFER_NANOS
                        callback.onRender(buffer, frames, deadline)
                        listen(buffer, frames, deadline, format.sampleRate)
                    }
                    Thread.sleep(2)
                }
            }
        }

        private fun halt() {
            running = false
            puller?.join(2_000)
            puller = null
        }

        private fun listen(buffer: FloatBuffer, frames: Int, deadline: Long, rate: Int) {
            val gap = rate / 50L
            for (frame in 0 until frames) {
                val sample = buffer.samples[frame * buffer.channels]
                val loud = abs(sample) > 0.2f
                if (loud && !inBeep && quiet > rate / 2) {
                    inBeep = true
                    onsetNanos = deadline - (frames - 1 - frame) * 1_000_000_000L / rate
                    span = 0
                    crossings = 0
                    lastSign = 0
                }
                quiet = if (loud) 0 else if (quiet == Long.MAX_VALUE) quiet else quiet + 1
                if (!inBeep) continue
                span++
                val sign = if (sample > 0f) 1 else if (sample < 0f) -1 else 0
                if (sign != 0 && lastSign != 0 && sign != lastSign) crossings++
                if (sign != 0) lastSign = sign
                // Twenty milliseconds of quiet end the beep, and the quiet is not part of it.
                if (quiet == gap) {
                    inBeep = false
                    val seconds = (span - gap).toDouble() / rate
                    if (seconds > 0.05) beeps += HeardBeep(onsetNanos, crossings / (2 * seconds))
                }
            }
        }
    }

    private class FloatBuffer(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
        val channels = format.channels
        val samples = FloatArray(frames * channels)

        fun clear() = samples.fill(0f)

        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
            source.copyInto(samples, destinationFrameOffset * channels, sourceOffset, sourceOffset + frames * channels)
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            samples.fill(0f, frameOffset * channels, (frameOffset + frames) * channels)
        }
    }

    private companion object {
        const val BUFFER_NANOS = 10_000_000L
    }
}

/**
 * A screen with no window: it takes every frame, notes when each white flash would be on screen,
 * which is its target or now when the frame came late, and closes the frame.
 */
internal class FlashRecorder : VideoRenderer {
    val flashes: MutableList<Long> = CopyOnWriteArrayList()
    private var lastBright = false
    private var luma = ByteArray(0)

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = true

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        try {
            if (frame !is SoftwareReadableFrame) return false
            val size = frame.planeStride(0) * frame.planeHeight(0)
            if (luma.size < size) luma = ByteArray(size)
            frame.copyPlane(0, luma)
            var sum = 0L
            var count = 0
            for (index in 0 until size step 97) {
                sum += luma[index].toInt() and 0xFF
                count++
            }
            val bright = count > 0 && sum / count > 128
            if (bright && !lastBright) flashes += maxOf(targetNanos, MonotonicClock.System.nanos())
            lastBright = bright
            return true
        } finally {
            frame.close()
        }
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float) = Unit

    override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit

    override val events: Flow<RendererEvent> = emptyFlow()

    override val outputSize: VideoSize? get() = null

    override fun close() = Unit
}

/**
 * How late each beep was heard after the flash it belongs to was shown, in milliseconds, for every
 * beep with a flash within half a second of it. Positive is sound behind the picture.
 */
internal fun syncOffsetsMillis(beeps: List<HeardBeep>, flashes: List<Long>): List<Double> = beeps.mapNotNull { beep ->
    val flash = flashes.minByOrNull { abs(it - beep.atNanos) } ?: return@mapNotNull null
    val offset = (beep.atNanos - flash) / 1e6
    offset.takeIf { abs(it) < 500 }
}
