@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * The real device behind [WebAudioSink]: an `AudioWorklet` holding a queue this side keeps full.
 *
 * Everything JS is in this file and nothing else knows the sink is a browser at all. The state it
 * hands back is one plain JS object holding the context, the node, the staging array and three
 * counters, so Kotlin reads scalars out of it rather than owning JS references it would have to
 * keep alive.
 */
internal class WebAudioWorkletDevice private constructor(
    private val state: JsAny,
    override val sampleRate: Int,
    override val channels: Int,
) : WebAudioDevice {

    override fun queuedFrames(): Int = webAudioQueued(state)

    override fun underrunFrames(): Int = webAudioSilence(state)

    override fun outputLatencySeconds(): Double? = webAudioLatencySeconds(state).takeIf { it >= 0.0 }

    /**
     * Copies one block across the JS line a sample at a time, because there is no bulk move.
     *
     * Kotlin/Wasm has no typed-array bridge: `WebMemory.readBytes` in KiteCodec loops per byte for
     * the same reason, and `BindingProof.fetchClip` says so in its own KDoc. Per sample rather than
     * per byte is the cheap version of the same limit, and the cost is real but bounded: 2048 calls
     * per block is about 96,000 a second for 48 kHz stereo, each one a direct wasm-to-JS import.
     *
     * The fix is not a smarter loop here. It is either a typed-array bridge in the language, or the
     * Worker of X-08, where `SharedArrayBuffer` becomes legal and the ring stops being copied at all.
     */
    override fun enqueue(samples: FloatArray, frames: Int) {
        val count = frames * channels
        for (i in 0 until count) webAudioStage(state, i, samples[i])
        webAudioPush(state, frames)
    }

    override fun flush(): Unit = webAudioFlush(state)

    override suspend fun resume() {
        awaitJs(webAudioResume(state))
    }

    override suspend fun suspendPlayback() {
        awaitJs(webAudioSuspend(state))
    }

    override fun close(): Unit = webAudioClose(state)

    companion object {
        /**
         * Builds the device, or returns null where Web Audio does not exist.
         *
         * Null is the honest answer on `nodejs`, which this module also targets and which has no
         * `AudioContext`. The factory falls back to the paced silent sink there rather than failing
         * to create a player at all.
         */
        suspend fun createOrNull(requestedChannels: Int, blockFrames: Int): WebAudioWorkletDevice? {
            val state = awaitJs(webAudioSetup(PROCESSOR_SOURCE, requestedChannels, blockFrames)) ?: return null
            return WebAudioWorkletDevice(
                state = state,
                sampleRate = webAudioSampleRate(state).toInt(),
                channels = webAudioChannels(state),
            )
        }
    }
}

/**
 * The paced sink stays as the fallback, and that is a working player rather than a stub.
 *
 * A browser gives the real device; `nodejs` and any embedder without Web Audio keep the silent one,
 * which still drives the clock correctly. Choosing at creation rather than at every call means the
 * decision is made once and the sink below it never branches.
 */
internal object WebAudioSinkFactory : AudioSinkFactory {
    override val name: String = "web-audioworklet"

    override suspend fun create(): AudioSink {
        val device = WebAudioWorkletDevice.createOrNull(
            requestedChannels = DEFAULT_CHANNELS,
            blockFrames = WEB_BLOCK_FRAMES,
        ) ?: return SilentPacedAudioSinkFactory.create()
        return WebAudioSink(device, CoroutineScope(Dispatchers.Default), WebMonotonicClock)
    }

    /**
     * Stereo, asked for before any media is open.
     *
     * The device is built before [AudioSink.open] knows the track's layout, so this asks for what
     * every browser can give and lets the engine remix into it. A surround web tier would have to
     * build the node later, which costs a rebuild on every format change for a layout browsers
     * rarely route correctly anyway.
     */
    private const val DEFAULT_CHANNELS = 2
}

/**
 * The `AudioWorkletProcessor`, as source, delivered through a `Blob` URL.
 *
 * Inlined rather than shipped as a file on purpose. `addModule` needs a URL, a separate `.js`
 * artifact would need an artifact layout and a deployment story, and that is X-13 and still open.
 * A `Blob` URL needs neither and works from any embedder.
 *
 * It counts frames dropped by a flush as consumed, which is what lets the Kotlin side compute its
 * queue depth as sent minus consumed and have that return to zero after a seek.
 */
private const val PROCESSOR_SOURCE = """
class KiteSinkProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const opts = (options && options.processorOptions) || {};
    this.channels = opts.channels || 2;
    this.reportEvery = opts.reportEvery || 4;
    this.queue = [];
    this.head = 0;
    this.queuedFrames = 0;
    this.consumedFrames = 0;
    this.silenceFrames = 0;
    this.ticks = 0;
    this.port.onmessage = (e) => {
      const d = e.data;
      if (d.cmd === 'push') {
        this.queue.push(d.samples);
        this.queuedFrames += (d.samples.length / this.channels) | 0;
      } else if (d.cmd === 'flush') {
        this.consumedFrames += this.queuedFrames;
        this.queuedFrames = 0;
        this.queue.length = 0;
        this.head = 0;
        this.report();
      }
    };
  }
  report() {
    this.port.postMessage({ consumed: this.consumedFrames, silence: this.silenceFrames });
  }
  process(inputs, outputs) {
    const out = outputs[0];
    if (!out || out.length === 0) return true;
    const frames = out[0].length;
    const channels = this.channels;
    let written = 0;
    while (written < frames && this.queue.length > 0) {
      const blk = this.queue[0];
      const blockFrames = (blk.length / channels) | 0;
      let take = blockFrames - this.head;
      if (take > frames - written) take = frames - written;
      for (let f = 0; f < take; f++) {
        const src = (this.head + f) * channels;
        for (let c = 0; c < channels; c++) {
          const plane = out[c];
          if (plane) plane[written + f] = blk[src + c];
        }
      }
      written += take;
      this.head += take;
      this.queuedFrames -= take;
      this.consumedFrames += take;
      if (this.head >= blockFrames) { this.queue.shift(); this.head = 0; }
    }
    if (written < frames) {
      for (let c = 0; c < channels; c++) { const plane = out[c]; if (plane) plane.fill(0, written); }
      this.silenceFrames += frames - written;
    }
    this.ticks++;
    if (this.ticks % this.reportEvery === 0) this.report();
    return true;
  }
}
registerProcessor('kite-sink', KiteSinkProcessor);
"""

/**
 * Creates the context, loads the processor and connects the node. Resolves to null with no Web Audio.
 *
 * `sent` is tracked here rather than in the worklet so that queue depth is known the instant a block
 * is handed over. The worklet's report only ever moves `consumed`, so the difference is stale by at
 * most one report interval and always in the direction of over-estimating what is still queued.
 */
@JsFun(
    """(src, wanted, blockFrames) => {
      const Ctor = (typeof AudioContext !== 'undefined') ? AudioContext
                 : (typeof webkitAudioContext !== 'undefined') ? webkitAudioContext : null;
      if (!Ctor || typeof Blob === 'undefined' || typeof URL === 'undefined') return Promise.resolve(null);
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
        const s = {
          ctx: ctx, node: node, channels: channels,
          staging: new Float32Array(blockFrames * channels),
          sent: 0, consumed: 0, silence: 0,
        };
        node.port.onmessage = (e) => { s.consumed = e.data.consumed; s.silence = e.data.silence; };
        try { ctx.destination.channelCount = channels; } catch (e) {}
        node.connect(ctx.destination);
        return s;
      }).catch(() => { try { URL.revokeObjectURL(url); ctx.close(); } catch (e) {} return null; });
    }""",
)
private external fun webAudioSetup(source: String, channels: Int, blockFrames: Int): Promise<JsAny?>

@JsFun("(s) => s.ctx.sampleRate")
private external fun webAudioSampleRate(state: JsAny): Double

@JsFun("(s) => s.channels")
private external fun webAudioChannels(state: JsAny): Int

/** Sent minus consumed, and never negative: a stale report must not read as a drained queue. */
@JsFun("(s) => Math.max(0, s.sent - s.consumed)")
private external fun webAudioQueued(state: JsAny): Int

@JsFun("(s) => s.silence")
private external fun webAudioSilence(state: JsAny): Int

/** `outputLatency` where the engine measures it, `baseLatency` where it does not, -1 for neither. */
@JsFun(
    """(s) => (typeof s.ctx.outputLatency === 'number') ? s.ctx.outputLatency
             : (typeof s.ctx.baseLatency === 'number') ? s.ctx.baseLatency : -1""",
)
private external fun webAudioLatencySeconds(state: JsAny): Double

@JsFun("(s, i, v) => { s.staging[i] = v; }")
private external fun webAudioStage(state: JsAny, index: Int, value: Float)

/** `slice` because the staging array is reused and `postMessage` must not hand over a live view. */
@JsFun(
    """(s, frames) => {
      const n = frames * s.channels;
      s.node.port.postMessage({ cmd: 'push', samples: s.staging.slice(0, n) });
      s.sent += frames;
    }""",
)
private external fun webAudioPush(state: JsAny, frames: Int)

@JsFun("(s) => { s.node.port.postMessage({ cmd: 'flush' }); }")
private external fun webAudioFlush(state: JsAny)

@JsFun("(s) => s.ctx.resume()")
private external fun webAudioResume(state: JsAny): Promise<JsAny?>

@JsFun("(s) => s.ctx.suspend()")
private external fun webAudioSuspend(state: JsAny): Promise<JsAny?>

@JsFun("(s) => { try { s.node.disconnect(); } catch (e) {} try { s.ctx.close(); } catch (e) {} }")
private external fun webAudioClose(state: JsAny)

/**
 * Awaits a JS promise, treating a rejection as null.
 *
 * The same shape `BindingProof.awaitInt` uses. A rejected `resume()` means the browser refused the
 * gesture, which is a state this sink already handles by playing nothing, so there is nothing here
 * that a thrown exception would tell a caller that null does not.
 */
private suspend fun awaitJs(promise: Promise<JsAny?>): JsAny? =
    suspendCoroutine { continuation ->
        promise.then(
            onFulfilled = { value -> continuation.resumeWith(Result.success(value)); value },
            onRejected = { error -> continuation.resumeWith(Result.success(null)); error },
        )
    }
