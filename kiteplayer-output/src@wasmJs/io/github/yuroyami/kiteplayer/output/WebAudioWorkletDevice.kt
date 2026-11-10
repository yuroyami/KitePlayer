@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * The real device behind [WebAudioSink]: an `AudioWorklet` holding a queue this side keeps full.
 *
 * Everything JS is in this file and nothing else knows the sink is a browser at all. The state it
 * hands back is one plain JS object holding the context, the node and three counters, so Kotlin reads scalars out of it rather than owning JS references it would have to
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
     * Sends one block to the worklet in two crossings, packed as [packSamples] packs it, with its
     * buffer transferred to the worklet rather than copied (#909).
     *
     * Kotlin/Wasm has no typed-array bridge, and the block used to cross a sample at a time, about
     * 96,000 calls a second for 48 kHz stereo, then copied twice more by `slice` and `postMessage`.
     * A worker does not remove the copy by itself: it gets `SharedArrayBuffer` only under the same
     * cross-origin isolation as its page, which the default artifact does not ask for.
     */
    override fun enqueue(samples: FloatArray, frames: Int) {
        if (frames <= 0) return
        webAudioPush(state, samplesOf(packSamples(samples, frames * channels)), frames)
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
            installWebAudioUnlock()
            val state = awaitJs(
                webAudioSetup(PROCESSOR_SOURCE, requestedChannels, blockFrames),
                discardLate = ::webAudioClose,
            ) ?: return null
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

    /** Stereo: the node is built with [DEFAULT_CHANNELS] before any media is open (#466). */
    override fun outputChannelCount(): Int = DEFAULT_CHANNELS

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
 * artifact would need an artifact layout and a deployment story, and that is still open.
 * A `Blob` URL needs neither and works from any embedder.
 *
 * It counts frames dropped by a flush as consumed, which is what lets the Kotlin side compute its
 * queue depth as sent minus consumed and have that return to zero after a seek.
 *
 * A `port` command hands it a `MessagePort` that it then listens and reports on instead of its own:
 * that is how a player in a Web Worker writes to the page's worklet directly (#100).
 */
internal const val PROCESSOR_SOURCE = """
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
    this.out = this.port;
    const handle = (e) => {
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
      } else if (d.cmd === 'port') {
        this.out = d.port;
        this.out.onmessage = handle;
        this.report();
      }
    };
    this.port.onmessage = handle;
  }
  report() {
    this.out.postMessage({ consumed: this.consumedFrames, silence: this.silenceFrames });
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
          sent: 0, consumed: 0, silence: 0, paused: false, closed: false,
        };
        node.port.onmessage = (e) => { s.consumed = e.data.consumed; s.silence = e.data.silence; };
        // A browser starts audio only inside a user gesture, and the engine resumes the context
        // after an await, outside the click that asked for play (#910). So the context is resumed
        // from the page's own gestures too, the first stage of the same click included. Capture
        // phase, so a handler that stops the event still lets it through.
        const doc = globalThis.document;
        if (doc && doc.addEventListener) {
          s.unlock = () => globalThis.__kiteAudioUnlock(s);
          s.gestures = ['pointerdown', 'keydown', 'touchend', 'mousedown'];
          for (const type of s.gestures) doc.addEventListener(type, s.unlock, { capture: true, passive: true });
        }
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
internal external fun webAudioQueued(state: JsAny): Int

@JsFun("(s) => s.silence")
internal external fun webAudioSilence(state: JsAny): Int

/** `outputLatency` where the engine measures it, `baseLatency` where it does not, -1 for neither. */
@JsFun(
    """(s) => (typeof s.ctx.outputLatency === 'number') ? s.ctx.outputLatency
             : (typeof s.ctx.baseLatency === 'number') ? s.ctx.baseLatency : -1""",
)
private external fun webAudioLatencySeconds(state: JsAny): Double

/** Hands [samples], a fresh `Float32Array`, to the worklet, transferring its buffer rather than copying it. */
@JsFun(
    """(s, samples, frames) => {
      s.node.port.postMessage({ cmd: 'push', samples: samples }, [samples.buffer]);
      s.sent += frames;
    }""",
)
private external fun webAudioPush(state: JsAny, samples: JsAny, frames: Int)

@JsFun("(s) => { s.node.port.postMessage({ cmd: 'flush' }); }")
private external fun webAudioFlush(state: JsAny)

@JsFun("(s) => { s.paused = false; return s.ctx.resume(); }")
private external fun webAudioResume(state: JsAny): Promise<JsAny?>

@JsFun("(s) => { s.paused = true; return s.ctx.suspend(); }")
private external fun webAudioSuspend(state: JsAny): Promise<JsAny?>

@JsFun(
    """(s) => {
      s.closed = true;
      const doc = globalThis.document;
      if (doc && s.unlock) for (const type of s.gestures) doc.removeEventListener(type, s.unlock, { capture: true });
      try { s.node.disconnect(); } catch (e) {}
      try { s.ctx.close(); } catch (e) {}
    }""",
)
private external fun webAudioClose(state: JsAny)

/**
 * What a gesture on the page does to the device's context [state] (#910): resumes a context the
 * browser holds suspended, inside the gesture, unless the player paused it or the device closed.
 * A resume before play plays only silence, because the feeder fills the queue after its own
 * resume; a resume while paused would play what was left queued, so a paused device is left alone.
 * Installed as a page global, so the gesture listeners and the tests call the same code.
 */
internal fun webAudioUnlock(state: JsAny) {
    installWebAudioUnlock()
    callWebAudioUnlock(state)
}

@JsFun(
    """() => {
      if (globalThis.__kiteAudioUnlock) return;
      globalThis.__kiteAudioUnlock = (s) => {
        if (s.closed || s.paused || !s.ctx || s.ctx.state !== 'suspended') return;
        try { const p = s.ctx.resume(); if (p && p.catch) p.catch(() => {}); } catch (e) {}
      };
    }""",
)
internal external fun installWebAudioUnlock()

@JsFun("(s) => globalThis.__kiteAudioUnlock(s)")
private external fun callWebAudioUnlock(state: JsAny)

/**
 * Awaits a JS promise, treating a rejection as null.
 *
 * The same shape `BindingProof.awaitInt` uses. A rejected `resume()` means the browser refused the
 * gesture, which is a state this sink already handles by playing nothing, so there is nothing here
 * that a thrown exception would tell a caller that null does not.
 *
 * Cancellable. A promise may stay pending as long as it likes, as `resume()` may on a page nobody
 * has touched yet, and a caller that stops waiting must not keep waiting with it (#479). What a
 * promise fulfils with after its caller stopped waiting goes to [discardLate], so a device built
 * for a caller who left is closed rather than left running.
 */
internal suspend fun awaitJs(promise: Promise<JsAny?>, discardLate: ((JsAny) -> Unit)? = null): JsAny? =
    suspendCancellableCoroutine { continuation ->
        promise.then(
            onFulfilled = { value ->
                continuation.resume(value) { _, late, _ -> late?.let { discardLate?.invoke(it) } }
                value
            },
            onRejected = { error ->
                continuation.resume(null) { _, _, _ -> }
                error
            },
        )
    }
