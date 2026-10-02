@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.output.WebWorkletAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.js.JsAny
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * A player that runs in a Web Worker, so the page's own thread stays free while it opens, decodes
 * and draws (#100).
 *
 * ```kotlin
 * val player = KitePlayerWorker.start(canvas)
 * player.open(MediaItem("https://example.com/movie.mp4"))
 * button.onclick = { player.play() }
 * ```
 *
 * The worker holds the engine, the codec module and the readers. The page keeps only what a worker
 * cannot have: the audio device, whose sound goes from the worker to the device without passing
 * through the page, and the canvas, whose drawing is handed to the worker. Frames and sound never
 * cross between the two threads; commands and state do.
 *
 * An `http`, `https` or `blob` item plays here, read with range requests that only a worker may
 * make. The page's own `KitePlayer` cannot open one. A relative address is read against the page's.
 *
 * This is the first part of the worker player. It has the commands below and no others yet, and
 * an item crosses as its address, headers, format hint, open options, start position and titles.
 * An item with its own reader, external subtitles, a filter or a demux policy is refused with
 * [PlaybackError.ConfigurationInvalid]. [state] carries no tracks, chapters or subtitle cues yet.
 *
 * The page's own `KitePlayer` is unchanged and stays the web default.
 */
public class KitePlayerWorker private constructor(
    private val worker: JsAny,
    private val audio: WebWorkletAudio?,
) : AutoCloseable {

    private val stateFlow = MutableStateFlow(PlayerSnapshot())
    private val progressFlow = MutableStateFlow(Progress())
    private val eventFlow = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)

    /** The player's state, as the worker last sent it. */
    public val state: StateFlow<PlayerSnapshot> = stateFlow.asStateFlow()

    /** The position and how far ahead the worker has read, as the worker last sent them. */
    public val progress: StateFlow<Progress> = progressFlow.asStateFlow()

    /** What happened in the player. A worker that dies sends [PlayerEvent.Failed] from here. */
    public val events: SharedFlow<PlayerEvent> = eventFlow.asSharedFlow()

    private val pending = HashMap<Int, CompletableDeferred<Unit>>()
    private var nextId = 1
    private var media: MediaItem? = null
    private var closeId: Int? = null
    private var dead: PlaybackError? = null
    private val closed: Boolean get() = closeId != null

    /** Set by [start] until the worker has answered the first message. */
    private var started: CompletableDeferred<Unit>? = null

    /**
     * Opens [media], replacing whatever was open, and returns once the worker has opened it.
     *
     * @throws PlaybackException with [PlaybackError.ConfigurationInvalid] for an item with a part
     *         that cannot cross to the worker, or with the error the open failed with.
     */
    public suspend fun open(media: MediaItem) {
        val record = ItemRecord.of(media).getOrThrow()
        // The worker reads a relative address against its own, so it is made whole here, where it
        // means what the page meant.
        val item = record.copy(uri = pageAddress(record.uri))
        this.media = media
        call { PageMessage.Open(it, item) }
    }

    /**
     * Starts or resumes playback. Call it from a user gesture's own handler: the browser starts the
     * page's audio device only there, and this starts it before it tells the worker.
     */
    public fun play() {
        checkOpen()
        audio?.resume()
        send(PageMessage.Play)
    }

    /** Pauses playback. */
    public fun pause() {
        checkOpen()
        send(PageMessage.Pause)
    }

    /** Seeks to [to], precisely, and returns once the worker has landed there. */
    public suspend fun seek(to: Duration) {
        call { PageMessage.Seek(it, to.inWholeMicroseconds) }
    }

    /** Stops playback and closes what was open. The player stays usable. */
    public suspend fun stop() {
        call { PageMessage.Stop(it) }
    }

    /**
     * Sizes the canvas's drawing buffer to [width] by [height] CSS pixels at [scale] device pixels
     * each, as `VideoRenderer.setViewport` does. The canvas belongs to the worker, so the page sets
     * its size through here rather than on the element.
     */
    public fun setViewport(width: Int, height: Int, scale: Float) {
        checkOpen()
        send(PageMessage.Viewport(width, height, scale))
    }

    /**
     * Asks the worker to close the player and end, and returns at once. The worker ends when the
     * player has closed. [closeAndAwait] waits for that.
     */
    override fun close() {
        if (closed) return
        val id = nextId++
        closeId = id
        if (dead != null) {
            finish()
            return
        }
        pending[id] = CompletableDeferred()
        send(PageMessage.Close(id))
    }

    /** [close], returning once the worker has closed the player and ended. */
    public suspend fun closeAndAwait() {
        close()
        closeId?.let { pending[it] }?.await()
    }

    private fun checkOpen() {
        check(!closed) { "the player is closed" }
    }

    /** Sends the command [build] makes with a fresh id, and waits for its one reply. */
    private suspend fun call(build: (Int) -> PageMessage) {
        checkOpen()
        dead?.let { throw PlaybackException(it) }
        val id = nextId++
        val reply = CompletableDeferred<Unit>()
        pending[id] = reply
        send(build(id))
        reply.await()
    }

    private fun send(message: PageMessage) {
        if (dead == null) workerPostTo(worker, message.encode())
    }

    private fun receive(data: JsAny?) {
        when (val message = decodeWorkerMessage(data) ?: return) {
            WorkerMessage.Hello -> Unit
            WorkerMessage.Ready -> started?.complete(Unit)
            is WorkerMessage.InitFailed ->
                started?.completeExceptionally(PlaybackException(PlaybackError.Internal(message.message)))
            is WorkerMessage.Reply -> {
                val reply = pending.remove(message.id) ?: return
                val error = message.error
                if (error == null) reply.complete(Unit) else reply.completeExceptionally(PlaybackException(error.toError()))
                if (message.id == closeId) finish()
            }
            is WorkerMessage.State -> stateFlow.value = message.state.toSnapshot(media)
            is WorkerMessage.Progress -> progressFlow.value = Progress(
                position = message.positionMicros.microseconds,
                bufferedAhead = message.bufferedAheadMicros.microseconds,
            )
            is WorkerMessage.Event -> eventFlow.tryEmit(message.event.toEvent())
            is WorkerMessage.Audio -> if (message.resume) audio?.resume() else audio?.suspend()
        }
    }

    /**
     * The worker died, or its script could not load. Every command waiting on it fails, and the
     * player reports [PlaybackStatus.Failed], rather than a player that stops answering.
     */
    private fun died(detail: String) {
        if (dead != null) return
        val error = PlaybackError.Internal("the player's worker stopped: $detail")
        dead = error
        started?.completeExceptionally(PlaybackException(error))
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(PlaybackException(error)) }
        if (closed) {
            finish()
            return
        }
        stateFlow.update { it.copy(status = PlaybackStatus.Failed) }
        eventFlow.tryEmit(PlayerEvent.Failed(error))
        workerTerminate(worker)
    }

    /** Ends the worker and the page's audio once the close is answered, or the worker is gone. */
    private fun finish() {
        workerTerminate(worker)
        audio?.close()
        closeId?.let { pending.remove(it) }?.complete(Unit)
    }

    public companion object {
        /**
         * Starts a worker player, hands it [canvas] and the page's audio, and returns once it can
         * play.
         *
         * @param canvas the `HTMLCanvasElement` to draw on, or null for sound only. Its drawing
         *        passes to the worker for good, so the page cannot draw on it afterwards.
         * @param workerUrl the worker binary, `kiteplayer-web-worker.mjs`, as the page serves it.
         * @param codecUrl the codec module, `kite.mjs`, as the worker should load it. A relative
         *        address is read against the worker's address, not the page's.
         * @throws PlaybackException with [PlaybackError.Internal] when the worker cannot load, or
         *         cannot load the codec module.
         */
        public suspend fun start(
            canvas: JsAny?,
            workerUrl: String = "./kiteplayer-web-worker.mjs",
            codecUrl: String = "./kite.mjs",
        ): KitePlayerWorker {
            val audio = WebWorkletAudio.createOrNull()
            val offscreen = canvas?.let(::canvasTransfer)
            val hello = CompletableDeferred<Unit>()
            var player: KitePlayerWorker? = null
            val worker = workerCreate(
                workerUrl,
                { data ->
                    val current = player
                    if (current != null) {
                        current.receive(data)
                    } else if (decodeWorkerMessage(data) == WorkerMessage.Hello) {
                        hello.complete(Unit)
                    }
                },
                { detail ->
                    val current = player
                    if (current != null) {
                        current.died(detail)
                    } else {
                        hello.completeExceptionally(PlaybackException(PlaybackError.Internal("the player's worker stopped: $detail")))
                    }
                },
            )
            try {
                // The worker sets its listener once its code has loaded. A message sent before that
                // is lost, so the first one waits for the worker to say it is listening.
                hello.await()
                val created = KitePlayerWorker(worker, audio)
                val ready = CompletableDeferred<Unit>()
                created.started = ready
                player = created
                val init = PageMessage.Init(
                    codecUrl = codecUrl,
                    sampleRate = audio?.sampleRate ?: 0,
                    channels = audio?.channels ?: 0,
                    latencySeconds = audio?.outputLatencySeconds,
                ).encode()
                workerPostInit(worker, init, offscreen, audio?.workerPort)
                ready.await()
                created.started = null
                return created
            } catch (failure: Throwable) {
                workerTerminate(worker)
                audio?.close()
                throw failure
            }
        }
    }
}

/** [uri] read against the page's address when it has no scheme of its own, and as it is otherwise. */
@JsFun(
    """(uri) => {
      if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(uri) || typeof document === 'undefined') return uri;
      try { return new URL(uri, document.baseURI).href; } catch (e) { return uri; }
    }""",
)
private external fun pageAddress(uri: String): String

/** The canvas's drawing, handed over as an `OffscreenCanvas` that can be transferred to the worker. */
@JsFun("(canvas) => canvas.transferControlToOffscreen()")
private external fun canvasTransfer(canvas: JsAny): JsAny

/** A module worker, with [receive] for its messages and [failed] for its death or a script that does not load. */
@JsFun(
    """(url, receive, failed) => {
      const worker = new Worker(url, { type: 'module' });
      worker.onmessage = (e) => receive(e.data);
      worker.onerror = (e) => { e.preventDefault(); failed((e && e.message) ? e.message : 'the worker failed to load or threw'); };
      worker.onmessageerror = () => failed('a message from the worker could not be read');
      return worker;
    }""",
)
private external fun workerCreate(url: String, receive: (JsAny?) -> Unit, failed: (String) -> Unit): JsAny

@JsFun("(worker, message) => { worker.postMessage(message); }")
private external fun workerPostTo(worker: JsAny, message: JsAny)

/** The first message, with the canvas and the audio port beside it, both transferred. */
@JsFun(
    """(worker, message, canvas, port) => {
      const transfer = [];
      if (canvas) { message.canvas = canvas; transfer.push(canvas); }
      if (port) { message.port = port; transfer.push(port); }
      worker.postMessage(message, transfer);
    }""",
)
private external fun workerPostInit(worker: JsAny, message: JsAny, canvas: JsAny?, port: JsAny?)

@JsFun("(worker) => { worker.terminate(); }")
private external fun workerTerminate(worker: JsAny)
