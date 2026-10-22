@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * The page's half of the sound of a player that runs in a Web Worker (#100): the `AudioContext`
 * and the `AudioWorklet`, which only a page may own, and the port a worker writes the sound to.
 *
 * An application uses `KitePlayerWorker` in `kiteplayer`, which builds this itself. The worker
 * writes blocks to [workerPort] and the worklet reports back on it, so the page's thread is not in
 * the path of the sound at all.
 */
public class WebWorkletAudio private constructor(private val state: JsAny) {

    /** The worker's end of the channel to the worklet. Transfer it to the worker, once. */
    public val workerPort: JsAny get() = webWorkletPort(state)

    /** The rate of the page's audio device, which the worker's sink resamples to. */
    public val sampleRate: Int get() = webWorkletSampleRate(state).toInt()

    /** The channels the destination carries, which may be fewer than were asked for. */
    public val channels: Int get() = webWorkletChannels(state)

    /** Seconds between handing a frame over and hearing it, or null when the browser gives no figure. */
    public val outputLatencySeconds: Double? get() = webWorkletLatency(state).takeIf { it >= 0.0 }

    /**
     * Starts the device. A browser starts audio only inside a user gesture, so call this from the
     * gesture's own handler, not after an await.
     */
    public fun resume(): Unit = webWorkletResume(state)

    /** Stops the device without closing it, as a pause does. */
    public fun suspend(): Unit = webWorkletSuspend(state)

    /** Disconnects the worklet and closes the context. */
    public fun close(): Unit = webWorkletClose(state)

    public companion object {
        /**
         * Builds the context and the worklet, or returns null where Web Audio does not exist, as in
         * `nodejs`. [channels] is what is asked of the destination; [WebWorkletAudio.channels] says
         * what it gives.
         */
        public suspend fun createOrNull(channels: Int = 2): WebWorkletAudio? =
            awaitJs(webWorkletSetup(PROCESSOR_SOURCE, channels), discardLate = ::webWorkletClose)?.let(::WebWorkletAudio)
    }
}

/**
 * The output backend of a player that runs in a Web Worker (#100): the worker's clock, and a sink
 * that writes to the page's worklet through [port], the [WebWorkletAudio.workerPort] the page
 * transferred. [sampleRate], [channels] and [outputLatencySeconds] are the page's figures.
 *
 * A worker cannot start or stop the page's device, so [control] asks the page to: true to resume
 * and false to suspend. The page decides; a browser starts audio only inside a user gesture.
 *
 * An application uses `KitePlayerWorker` in `kiteplayer`, which builds this inside its worker.
 */
public fun workerOutputBackend(
    port: JsAny,
    sampleRate: Int,
    channels: Int,
    outputLatencySeconds: Double?,
    control: (resume: Boolean) -> Unit,
): OutputBackend {
    val shared = WorkletPort(port, channels)
    return object : OutputBackend {
        override val clock: MonotonicClock get() = WebMonotonicClock
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = "web-worker-audioworklet"
            override suspend fun create(): AudioSink = WebAudioSink(
                WorkletPortDevice(shared, sampleRate, channels, outputLatencySeconds, control),
                CoroutineScope(Dispatchers.Default),
                WebMonotonicClock,
            )
        }
        // A worker has an OffscreenCanvas, so text cues are drawn there too (#559).
        override val subtitleRasterizer: SubtitleRasterizer? get() = WebSubtitleRasterizer.orNull()
    }
}

/**
 * The worker's end of the channel to the worklet, with the counters of everything sent over it.
 * One per backend, so a second sink on the same port counts on from where the first one stopped,
 * as the worklet's own count does.
 */
internal class WorkletPort(port: JsAny, channels: Int) {
    val state: JsAny = webPortSetup(port, channels, WEB_BLOCK_FRAMES)
}

/** A [WebAudioDevice] over the page's worklet, through [port]. Closing it leaves the port to the backend. */
internal class WorkletPortDevice(
    private val port: WorkletPort,
    override val sampleRate: Int,
    override val channels: Int,
    private val latencySeconds: Double?,
    private val control: (Boolean) -> Unit,
) : WebAudioDevice {

    override fun queuedFrames(): Int = webAudioQueued(port.state)

    override fun underrunFrames(): Int = webAudioSilence(port.state)

    override fun outputLatencySeconds(): Double? = latencySeconds

    /** The same per-sample crossing as the page's sink, now on the worker's thread instead of the page's. */
    override fun enqueue(samples: FloatArray, frames: Int) {
        val count = frames * channels
        for (i in 0 until count) webPortStage(port.state, i, samples[i])
        webPortPush(port.state, frames)
    }

    override fun flush(): Unit = webPortFlush(port.state)

    override suspend fun resume(): Unit = control(true)

    override suspend fun suspendPlayback(): Unit = control(false)

    override fun close(): Unit = webPortFlush(port.state)
}

/**
 * The context and the worklet, as the page's sink builds them, with a channel whose one end goes to
 * the worklet and whose other end is kept for the worker. Resolves to null with no Web Audio.
 */
@JsFun(
    """(src, wanted) => {
      const Ctor = (typeof AudioContext !== 'undefined') ? AudioContext
                 : (typeof webkitAudioContext !== 'undefined') ? webkitAudioContext : null;
      if (!Ctor || typeof Blob === 'undefined' || typeof URL === 'undefined' || typeof MessageChannel === 'undefined') {
        return Promise.resolve(null);
      }
      let ctx;
      try { ctx = new Ctor(); } catch (e) { return Promise.resolve(null); }
      if (!ctx.audioWorklet) { try { ctx.close(); } catch (e) {} return Promise.resolve(null); }
      const max = ctx.destination.maxChannelCount || 2;
      const channels = Math.max(1, Math.min(wanted, max));
      const url = URL.createObjectURL(new Blob([src], { type: 'application/javascript' }));
      return ctx.audioWorklet.addModule(url).then(() => {
        URL.revokeObjectURL(url);
        const node = new AudioWorkletNode(ctx, 'kite-sink', {
          numberOfInputs: 0,
          numberOfOutputs: 1,
          outputChannelCount: [channels],
          processorOptions: { channels: channels, reportEvery: 4 },
        });
        try { ctx.destination.channelCount = channels; } catch (e) {}
        node.connect(ctx.destination);
        const channel = new MessageChannel();
        node.port.postMessage({ cmd: 'port', port: channel.port1 }, [channel.port1]);
        return { ctx: ctx, node: node, channels: channels, workerPort: channel.port2 };
      }).catch(() => { try { URL.revokeObjectURL(url); ctx.close(); } catch (e) {} return null; });
    }""",
)
private external fun webWorkletSetup(source: String, channels: Int): Promise<JsAny?>

@JsFun("(s) => s.workerPort")
private external fun webWorkletPort(state: JsAny): JsAny

@JsFun("(s) => s.ctx.sampleRate")
private external fun webWorkletSampleRate(state: JsAny): Double

@JsFun("(s) => s.channels")
private external fun webWorkletChannels(state: JsAny): Int

@JsFun(
    """(s) => (typeof s.ctx.outputLatency === 'number') ? s.ctx.outputLatency
             : (typeof s.ctx.baseLatency === 'number') ? s.ctx.baseLatency : -1""",
)
private external fun webWorkletLatency(state: JsAny): Double

@JsFun("(s) => { s.ctx.resume().catch(() => {}); }")
private external fun webWorkletResume(state: JsAny)

@JsFun("(s) => { s.ctx.suspend().catch(() => {}); }")
private external fun webWorkletSuspend(state: JsAny)

@JsFun("(s) => { try { s.node.disconnect(); } catch (e) {} try { s.ctx.close(); } catch (e) {} }")
private external fun webWorkletClose(state: JsAny)

/**
 * The worker's state for one port: the staging array and the three counters the page's sink keeps,
 * with the worklet's reports arriving on the port itself. Same field names as the page's state, so
 * the queue and silence readers of [WebAudioWorkletDevice] read both.
 */
@JsFun(
    """(port, channels, blockFrames) => {
      const s = { port: port, channels: channels, staging: new Float32Array(blockFrames * channels), sent: 0, consumed: 0, silence: 0 };
      port.onmessage = (e) => {
        const d = e.data;
        if (d && typeof d.consumed === 'number') { s.consumed = d.consumed; s.silence = d.silence; }
      };
      return s;
    }""",
)
private external fun webPortSetup(port: JsAny, channels: Int, blockFrames: Int): JsAny

@JsFun("(s, i, v) => { s.staging[i] = v; }")
private external fun webPortStage(state: JsAny, index: Int, value: Float)

@JsFun(
    """(s, frames) => {
      const n = frames * s.channels;
      s.port.postMessage({ cmd: 'push', samples: s.staging.slice(0, n) });
      s.sent += frames;
    }""",
)
private external fun webPortPush(state: JsAny, frames: Int)

@JsFun("(s) => { s.port.postMessage({ cmd: 'flush' }); }")
private external fun webPortFlush(state: JsAny)
