package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The web output side (17.14 X-12): a clock and a sink, and no renderer.
 *
 * No renderer is not a gap, it is the same shape `DesktopOutputBackend` has. Compose draws the
 * frames through KiteVideo on both, so the backend supplies only what the platform alone can
 * answer. On the web that is the page's clock and its audio device.
 */
public object WebOutputBackend : OutputBackend {
    override val clock: MonotonicClock get() = WebMonotonicClock
    override val audioSink: AudioSinkFactory = SilentPacedAudioSinkFactory
    override val videoRenderer: VideoRendererFactory? get() = null

    /** No web rasteriser yet; the text subtitle path draws through Compose above this layer. */
    override val subtitleRasterizer: SubtitleRasterizer? get() = null
}

/**
 * `performance.now()`, which is the page's monotonic clock.
 *
 * Monotonic by specification and unaffected by wall-clock changes, which is the property the
 * engine's sync needs. Its resolution is deliberately coarsened by browsers against timing attacks
 * (typically to 100 microseconds, more when the page is not cross-origin isolated), so this is a
 * millisecond-scale clock wearing nanosecond units, and the engine's tolerances are far wider
 * than that.
 */
public object WebMonotonicClock : MonotonicClock {
    override fun nanos(): Long = (performanceNow() * 1_000_000.0).toLong()
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => performance.now()")
private external fun performanceNow(): Double

/**
 * A sink that keeps time correctly and makes no sound (17.14 X-12).
 *
 * NOT the web audio sink, and the name says so. This engine is audio-mastered: the clock the video
 * path synchronises against comes from how many audio frames the sink has consumed. Without
 * something answering that contract the renderer cannot be exercised inside the engine at all, so
 * this consumes at exactly real time and writes the samples nowhere.
 *
 * KNOWN DEFECT, found by the S6-D7 review before anything played through it: this sink never
 * CALLS [render], and the engine's clock anchors only when the render callback consumes the audio
 * ring. As written, playback through the engine would hang at position zero. What this class needs
 * to honour its own name is a pump: a coroutine invoking onRender per deviceBufferFrames block on
 * a wall-clock schedule, discarding the samples. Until that lands, this sink proves only that the
 * contract is answerable on wasm. The real audible sink is an `AudioWorklet` fed by a ring (X-10).
 *
 * INTERNAL on purpose, unlike `WebOutputBackend` and `WebMonotonicClock` which mirror their public
 * desktop twins. This one is scaffolding for X-10, and publishing it would commit the library to a
 * silent sink as API. A consumer reaches it through `WebOutputBackend.audioSink` and never needs
 * the name.
 */
internal object SilentPacedAudioSinkFactory : AudioSinkFactory {
    override val name: String = "web-silent-paced"
    override suspend fun create(): AudioSink = SilentPacedAudioSink()
}

private class SilentPacedAudioSink : AudioSink {

    private var format: AudioFormat? = null
    private var render: AudioRenderCallback? = null
    private var running = false
    private var startedAtNanos = 0L
    private var framesConsumed = 0L

    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        // Accepted exactly as asked: nothing here resamples, because nothing here plays.
        format = request
        this.render = render
        framesConsumed = 0
        return request
    }

    override suspend fun start() {
        running = true
        startedAtNanos = WebMonotonicClock.nanos()
    }

    override suspend fun stop() {
        framesConsumed = pacedFrames()
        running = false
    }

    override suspend fun setPaused(paused: Boolean): Boolean {
        if (paused) {
            framesConsumed = pacedFrames()
            running = false
        } else {
            startedAtNanos = WebMonotonicClock.nanos()
            running = true
        }
        return true
    }

    /**
     * One block, because there is no device buffer to describe. Chosen to match what a browser's
     * `AudioWorklet` quantum would be (128 frames) times a small multiple, so the engine's pacing
     * decisions here resemble the ones it will make once X-10 lands a real sink.
     */
    override val deviceBufferFrames: Int = 1024

    /**
     * Zero, and honestly so: silence has no output latency because nothing reaches a speaker.
     * Reported [latencyQuality] is Unreliable rather than Exact for the same reason, so nothing
     * above this treats the number as a measurement of a device.
     */
    override fun latencyNanos(): Long = 0

    override val latencyQuality: LatencyQuality get() = LatencyQuality.Unreliable

    /** No device, so no underruns and no device-lost events are possible to report. */
    override val events: Flow<AudioSinkEvent> = MutableSharedFlow(replay = 0)

    override fun close() {
        running = false
        render = null
        format = null
    }

    override suspend fun drain() {
        framesConsumed = pacedFrames()
        running = false
    }

    /**
     * Frames the device would have consumed by now, from the wall clock rather than from a device.
     *
     * This is what makes the silence PACED rather than instant: a sink that reported everything
     * consumed immediately would run the whole file in one frame and every sync decision above it
     * would be meaningless.
     */
    private fun pacedFrames(): Long {
        val f = format ?: return 0
        if (!running) return framesConsumed
        val elapsed = WebMonotonicClock.nanos() - startedAtNanos
        return (elapsed / 1_000_000_000.0 * f.sampleRate).toLong()
    }
}
