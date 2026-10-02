@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteffmpeg.KiteFFmpegWeb
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.mobile.WebCanvasRendererFactory
import io.github.yuroyami.kiteplayer.output.WebOutputBackend
import io.github.yuroyami.kiteplayer.output.workerOutputBackend
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.js.JsAny
import kotlin.time.Duration.Companion.microseconds

/**
 * The worker's side of [KitePlayerWorker] (#100). The worker binary's `main` calls this and returns;
 * the worker then lives on the messages the page sends it.
 *
 * It answers the page's first message by loading the codec module and building the player, with
 * the canvas and the audio port that came beside that message. From then on it runs each command
 * and replies to it once, and it sends the page the player's state, progress and events as they
 * change.
 *
 * Network addresses are read with synchronous range requests, which a worker may make, so an
 * `http`, `https` or `blob` item plays here although the page's own player cannot open one.
 */
public fun runKitePlayerWorker() {
    val runtime = WorkerRuntime(MainScope())
    workerOnMessage(runtime::receive)
    workerPost(WorkerMessage.Hello.encode())
}

/** One player in one worker, built by the first message and driven by the rest. */
private class WorkerRuntime(private val scope: CoroutineScope) {

    private var player: KitePlayer? = null
    private var renderer: VideoRenderer? = null
    private var closing = false

    fun receive(data: JsAny?) {
        val message = decodePageMessage(data) ?: return
        when (message) {
            is PageMessage.Init -> scope.launch {
                runCatching { start(message, data?.let { jsField(it, "canvas") }, data?.let { jsField(it, "port") }) }
                    .onSuccess { post(WorkerMessage.Ready) }
                    .onFailure { post(WorkerMessage.InitFailed(it.message ?: it.toString())) }
            }
            is PageMessage.Open -> command(message.id) { open(message.item.toItem()) }
            is PageMessage.Seek -> command(message.id) { seek(message.positionMicros.microseconds) }
            is PageMessage.Stop -> command(message.id) { stop() }
            is PageMessage.Close -> close(message.id)
            PageMessage.Play -> player?.let { runCatching { it.play() } }
            PageMessage.Pause -> player?.let { runCatching { it.pause() } }
            is PageMessage.Viewport -> renderer?.setViewport(message.width, message.height, message.scale)
        }
    }

    private suspend fun start(init: PageMessage.Init, canvas: JsAny?, port: JsAny?) {
        check(player == null) { "this worker already has a player" }
        KiteFFmpegWeb.load(init.codecUrl)
        // With no port the page has no Web Audio, and the web output's own fallback is a silent
        // clock that keeps the picture moving.
        val output = port?.let {
            workerOutputBackend(it, init.sampleRate, init.channels, init.latencySeconds) { resume ->
                post(WorkerMessage.Audio(resume))
            }
        } ?: WebOutputBackend
        val defaults = PlayerConfig()
        val created = KitePlayer(
            defaults.copy(
                backends = Backends(backend = KiteFFmpegMediaBackend(), output = output),
                network = defaults.network.copy(ioResolver = SyncHttpResolver(defaults.buffer.stallTimeout)),
            ),
        )
        player = created
        if (canvas != null) {
            val made = WebCanvasRendererFactory(canvas).create()
            renderer = made
            created.attachRendererAndAwait(made)
        }
        scope.launch {
            created.state.map(StateRecord::of).distinctUntilChanged().collect { post(WorkerMessage.State(it)) }
        }
        scope.launch {
            created.progress.collect {
                post(WorkerMessage.Progress(it.position.inWholeMicroseconds, it.bufferedAhead.inWholeMicroseconds))
            }
        }
        scope.launch {
            created.events.collect { event -> EventRecord.of(event)?.let { post(WorkerMessage.Event(it)) } }
        }
    }

    /** Runs [block] on the player and replies to [id] once, whatever happens. */
    private fun command(id: Int, block: suspend KitePlayer.() -> Unit) {
        val target = player
        if (target == null || closing) {
            post(WorkerMessage.Reply(id, ErrorRecord.of(IllegalStateException("the worker has no player to run this"))))
            return
        }
        scope.launch {
            val outcome = runCatching { target.block() }
            post(WorkerMessage.Reply(id, outcome.exceptionOrNull()?.let(ErrorRecord::of)))
        }
    }

    /** Closes the player, replies, and ends the worker. A second close is answered at once. */
    private fun close(id: Int) {
        if (closing) {
            post(WorkerMessage.Reply(id, null))
            return
        }
        closing = true
        scope.launch {
            val outcome = runCatching { player?.closeAndAwait() }
            renderer?.close()
            post(WorkerMessage.Reply(id, outcome.exceptionOrNull()?.let(ErrorRecord::of)))
            scope.cancel()
            workerClose()
        }
    }

    private fun post(message: WorkerMessage) = workerPost(message.encode())
}

@JsFun("(receive) => { self.onmessage = (e) => receive(e.data); }")
private external fun workerOnMessage(receive: (JsAny?) -> Unit)

@JsFun("(message) => { self.postMessage(message); }")
private external fun workerPost(message: JsAny)

@JsFun("() => { self.close(); }")
private external fun workerClose()
