@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteffmpeg.KiteFFmpegWeb
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.libass.KiteLibassWeb
import io.github.yuroyami.kiteplayer.mobile.WebCanvasRendererFactory
import io.github.yuroyami.kiteplayer.output.WebOutputBackend
import io.github.yuroyami.kiteplayer.output.workerOutputBackend
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.js.JsAny

/**
 * The worker's side of [KitePlayerWorker] (#100). The worker binary's `main` calls this and returns;
 * the worker then lives on the messages the page sends it.
 *
 * It answers the page's first message by loading the codec module and building the player, with
 * the canvas and the audio port that came beside that message. From then on it runs each call and
 * replies to it once, applies each control and reports a refused one as a warning, and it sends the
 * page the player's state, progress, statistics and events as they change.
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
            is PageMessage.Call -> call(message.id, message.command)
            is PageMessage.Close -> close(message.id)
            is PageMessage.Send -> send(message.control)
        }
    }

    private suspend fun start(init: PageMessage.Init, canvas: JsAny?, port: JsAny?) {
        check(player == null) { "this worker already has a player" }
        // Beside the codec module and without holding back Ready: an ASS track that opens before
        // libass lands is kept until it does, and drawn with the built-in styling if it never
        // does, after ten seconds or 64 MB of waiting data.
        init.libassUrl?.let { url -> scope.launch { runCatching { KiteLibassWeb.load(url) } } }
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
        scope.launch { created.state.collect { post(WorkerMessage.State(it)) } }
        scope.launch { created.progress.collect { post(WorkerMessage.Progressed(it)) } }
        scope.launch { created.stats.collect { post(WorkerMessage.Stats(it)) } }
        scope.launch { created.events.collect { post(WorkerMessage.Event(it)) } }
    }

    /** Runs [command] on the player and replies to [id] once, whatever happens. */
    private fun call(id: Int, command: Command) {
        val target = player
        if (target == null || closing) {
            post(WorkerMessage.Reply(id, Failure.State("the worker has no player to run ${command.member}")))
            return
        }
        scope.launch {
            val outcome = runCatching { target.perform(command) }
            post(WorkerMessage.Reply(id, outcome.exceptionOrNull()?.let(Failure::of), outcome.getOrNull()))
        }
    }

    /**
     * Applies [control]. Nobody waits for it, so a refusal is posted as the warning the player
     * itself uses for a control it cannot honour, named by the member the page called.
     */
    private fun send(control: Control) {
        val target = player ?: return
        if (closing) return
        runCatching { target.perform(control) }.onFailure { refused ->
            val detail = refused.message ?: refused.toString()
            post(WorkerMessage.Event(PlayerEvent.Warning(PlaybackWarning.CommandRefused(control.member, detail))))
        }
    }

    private suspend fun KitePlayer.perform(command: Command): Answer? {
        when (command) {
            is Command.Open -> open(command.item)
            is Command.OpenQueue -> openQueue(command.items, command.startIndex)
            is Command.Seek -> seek(command.to, command.mode)
            Command.Stop -> stop()
            Command.Next -> next()
            Command.Previous -> previous()
            is Command.AddToQueue -> addToQueue(command.items, command.index)
            is Command.RemoveFromQueue -> removeFromQueue(command.index)
            is Command.MoveInQueue -> moveInQueue(command.from, command.to)
            Command.ClearQueue -> clearQueue()
            is Command.StepFrame -> stepFrame(command.direction)
            is Command.SeekToChapter -> seekToChapter(command.index)
            Command.NextChapter -> nextChapter()
            Command.PreviousChapter -> previousChapter()
            is Command.SelectTrack -> return Answer.Change(selectTrack(command.kind, command.track))
            is Command.SelectSecondarySubtitle -> return Answer.Change(selectSecondarySubtitle(command.track))
            is Command.SelectVariant -> selectVariant(command.index)
            is Command.AddExternalSubtitle -> return Answer.Track(addExternalSubtitle(command.source))
            is Command.ReloadExternalSubtitle -> reloadExternalSubtitle(command.track, command.encoding)
            Command.DiagnosticsDump -> return Answer.Text(diagnosticsDump())
            Command.SupportBundle -> return Answer.Text(supportBundle())
            Command.WarningHistory -> return Answer.Warnings(warningHistory())
        }
        return null
    }

    private fun KitePlayer.perform(control: Control) {
        when (control) {
            Control.Play -> play()
            Control.Pause -> pause()
            is Control.SetViewport -> renderer?.setViewport(control.width, control.height, control.scale)
            is Control.RequestSeek -> requestSeek(control.to, control.mode)
            is Control.SetSpeed -> setSpeed(control.value)
            is Control.SetPreservePitch -> setPreservePitch(control.value)
            is Control.SetVolume -> setVolume(control.value)
            is Control.SetDuckLevel -> setDuckLevel(control.level)
            is Control.SetBalance -> setBalance(control.value)
            is Control.SetMuted -> setMuted(control.value)
            is Control.SetVideoEnabled -> setVideoEnabled(control.enabled)
            is Control.SetLoop -> setLoop(control.mode)
            is Control.SetShuffle -> setShuffle(control.enabled, control.seed)
            is Control.SetAbLoop -> setAbLoop(control.a, control.b)
            is Control.SetVideoScale -> setVideoScale(control.mode)
            is Control.SetVideoAdjustments -> setVideoAdjustments(control.value)
            is Control.SetRenderQuality -> setRenderQuality(control.value)
            is Control.SetVideoTransform -> setVideoTransform(control.value)
            is Control.SetHdrPolicy -> setHdrPolicy(control.value)
            is Control.SetSubtitleDelay -> setSubtitleDelay(control.value)
            is Control.SetSubtitleScale -> setSubtitleScale(control.value)
            is Control.SetSubtitleStyle -> setSubtitleStyle(control.value)
            is Control.SetSubtitlePosition -> setSubtitlePosition(control.value)
            is Control.SetSubtitleSafeArea -> setSubtitleSafeArea(control.value)
            is Control.SetAudioDelay -> setAudioDelay(control.value)
            is Control.SetSleepTimer -> setSleepTimer(control.timer, control.fade)
            is Control.SetEqualizer -> setEqualizer(control.settings)
            is Control.SetMarkers -> setMarkers(control.markers)
        }
    }

    /** Closes the player, replies, and ends the worker. A second close is answered at once. */
    private fun close(id: Int) {
        if (closing) {
            post(WorkerMessage.Reply(id))
            return
        }
        closing = true
        scope.launch {
            val outcome = runCatching { player?.closeAndAwait() }
            renderer?.close()
            post(WorkerMessage.Reply(id, outcome.exceptionOrNull()?.let(Failure::of)))
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
