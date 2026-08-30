package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch

/**
 * The web output side: a clock and a sink, and no renderer.
 *
 * No renderer is not a gap, it is the same shape `DesktopOutputBackend` has. Compose draws the
 * frames through KiteVideo on both, so the backend supplies only what the platform alone can
 * answer. On the web that is the page's clock and its audio device.
 *
 * The sink is audible now. [WebAudioSinkFactory] gives an `AudioWorklet` in a browser and
 * falls back to [SilentPacedAudioSinkFactory] where Web Audio does not exist, so `nodejs` and any
 * embedder without it still get a player whose clock runs.
 */
public object WebOutputBackend : OutputBackend {
    override val clock: MonotonicClock get() = WebMonotonicClock
    override val audioSink: AudioSinkFactory = WebAudioSinkFactory
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
 * A sink that keeps time correctly and makes no sound.
 *
 * NOT the web audio sink, and the name says so. This engine is audio-mastered: the clock the video
 * path synchronises against comes from how many audio frames the sink has consumed. Without
 * something answering that contract the renderer cannot be exercised inside the engine at all, so
 * this consumes at exactly real time and writes the samples nowhere.
 *
 * It PUMPS, and that is the whole of why it works. The engine's clock anchors when the render
 * callback consumes the audio ring, so a sink that merely holds the callback would leave playback
 * frozen at position zero with the ring backing up behind it. The S6-D7 review caught exactly that
 * in the first version of this class. So a coroutine calls [AudioRenderCallback.onRender] for one
 * [deviceBufferFrames] block at a time, on a wall-clock schedule, and throws the samples away.
 *
 * The real audible sink is [WebAudioSinkFactory]'s `AudioWorklet`. This one is no
 * longer the web's sink: it is the fallback where Web Audio does not exist, which is `nodejs` and
 * any embedder without an `AudioContext`, and it is still what makes the engine's own pacing
 * testable without a browser.
 *
 * INTERNAL on purpose, unlike `WebOutputBackend` and `WebMonotonicClock` which mirror their public
 * desktop twins. Publishing it would commit the library to a silent sink as API, and it is a
 * fallback rather than a choice a caller should be able to make. A consumer reaches it through
 * `WebOutputBackend.audioSink` and never needs the name.
 */
internal object SilentPacedAudioSinkFactory : AudioSinkFactory {
    override val name: String = "web-silent-paced"
    override suspend fun create(): AudioSink =
        SilentPacedAudioSink(CoroutineScope(Dispatchers.Default), WebMonotonicClock)
}

/**
 * The scope and the clock are parameters, not globals, and that is what makes this testable.
 *
 * A pump asserted against the wall clock is a flaky test on a loaded machine. Handed a test scope
 * and a clock driven by its virtual time, the same code is exact: the block count and every
 * deadline become arithmetic. `AudioPath.kt` makes the same choice for the same reason, and says
 * so: the engine's own clock policy is tested with a virtual clock.
 */
internal class SilentPacedAudioSink(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
) : AudioSink {

    private var format: AudioFormat? = null
    private var render: AudioRenderCallback? = null
    private var pump: Job? = null

    /** The discard buffer the callback writes into. One block, reused, never read. */
    private var block: DiscardBuffer? = null

    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        // Accepted exactly as asked: nothing here resamples, because nothing here plays.
        format = request
        this.render = render
        block = DiscardBuffer(request, deviceBufferFrames)
        return request
    }

    override suspend fun start() {
        if (pump != null) return
        pump = scope.launch { pumpLoop() }
    }

    override suspend fun stop() {
        pump?.cancelAndJoin()
        pump = null
    }

    override suspend fun setPaused(paused: Boolean): Boolean {
        if (paused) stop() else start()
        return true
    }

    /**
     * Consumes one block per block-duration, which is what makes the silence PACED.
     *
     * A loop with no wait would drain the whole file in one turn and every sync decision above it
     * would be meaningless. The deadline handed to the callback is the engine's own clock plus the
     * time until this block would reach a speaker, which is the same quantity a real device
     * reports and is what the engine anchors against.
     */
    private suspend fun pumpLoop() {
        val f = format ?: return
        val callback = render ?: return
        val buffer = block ?: return
        val frames = deviceBufferFrames
        val blockNanos = frames.toLong() * 1_000_000_000L / f.sampleRate
        var nextDeadline = clock.nanos() + blockNanos
        while (coroutineContext.isActive) {
            val written = callback.onRender(buffer, frames, nextDeadline)
            // A short block means the rest is silence and writing it is the sink's obligation.
            // There is nothing to write it into here, so this only keeps the contract honest.
            if (written < frames) buffer.writeSilence(written.coerceAtLeast(0), frames - written.coerceAtLeast(0))
            nextDeadline += blockNanos
            val waitNanos = nextDeadline - clock.nanos()
            // Behind schedule: do not sleep negative and do not try to catch up in one burst.
            delay(if (waitNanos > 0) waitNanos / 1_000_000 else 0)
        }
    }

    /**
     * One block, because there is no device buffer to describe. Sized as eight `AudioWorklet`
     * quanta (128 frames), so the engine's pacing decisions here resemble the ones it will make
     * now that a real sink exists.
     */
    override val deviceBufferFrames: Int = 1024

    /**
     * Zero, and honestly so: silence has no output latency because nothing reaches a speaker.
     * [latencyQuality] is Unreliable rather than Exact for the same reason, so nothing above this
     * treats the number as a measurement of a device.
     */
    override fun latencyNanos(): Long = 0

    override val latencyQuality: LatencyQuality get() = LatencyQuality.Unreliable

    /** No device, so no underrun and no device-lost event is possible to report. */
    override val events: Flow<AudioSinkEvent> = MutableSharedFlow(replay = 0)

    override suspend fun drain() {
        stop()
    }

    override fun close() {
        pump?.cancel()
        pump = null
        render = null
        format = null
        block = null
    }
}

/**
 * An [AudioSinkBuffer] that accepts every write and keeps nothing.
 *
 * Deliberately not a no-op on its own arguments: it bounds-checks, because the render callback is
 * engine code and a buffer that ignored a bad offset would hide a real defect on the one platform
 * that cannot hear the result.
 */
private class DiscardBuffer(
    override val format: AudioFormat,
    private val capacityFrames: Int,
) : AudioSinkBuffer {

    override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
        check(destinationFrameOffset + frames <= capacityFrames) {
            "interleaved write of $frames frames at $destinationFrameOffset exceeds $capacityFrames"
        }
        check(sourceOffset + frames * format.channels <= source.size) {
            "interleaved write reads past the end of its source"
        }
    }

    override fun writePlane(channel: Int, source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
        check(channel in 0 until format.channels) { "plane $channel is outside ${format.channels} channels" }
        check(destinationFrameOffset + frames <= capacityFrames) {
            "plane write of $frames frames at $destinationFrameOffset exceeds $capacityFrames"
        }
        check(sourceOffset + frames <= source.size) { "plane write reads past the end of its source" }
    }

    override fun writeSilence(frameOffset: Int, frames: Int) {
        check(frameOffset + frames <= capacityFrames) {
            "silence of $frames frames at $frameOffset exceeds $capacityFrames"
        }
    }
}
