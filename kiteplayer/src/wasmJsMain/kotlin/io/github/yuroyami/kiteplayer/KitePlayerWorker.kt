@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.output.WebDisplayAwake
import io.github.yuroyami.kiteplayer.output.WebWorkletAudio
import io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import io.github.yuroyami.kiteplayer.view.KitePlayerPictureInPicture
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
 * The worker holds the engine, the codec module, the libass module and the readers. The page keeps
 * only what a worker cannot have: the audio device, whose sound goes from the worker to the device
 * without passing through the page, and the canvas, whose drawing is handed to the worker. Frames,
 * sound and subtitles never cross between the two threads; commands and state do.
 *
 * An `http`, `https` or `blob` item plays here, read with range requests that only a worker may
 * make. The page's own `KitePlayer` cannot open one. A relative address, of an item or of one of its
 * subtitles, is read against the page's.
 *
 * ### The same calls as `KitePlayer`
 *
 * Every member below has the name, the parameters, the defaults and the meaning of the `KitePlayer`
 * member of the same name, and a call that fails throws what `KitePlayer` would throw:
 * [PlaybackException], [IllegalArgumentException], [IllegalStateException] or
 * [UnsupportedOperationException]. [state], [progress], [stats] and [events] carry what the worker's
 * player publishes, and `state.media` and `state.queue` hold the items this facade was given rather
 * than copies of them.
 *
 * Two differences come from the thread between the page and the player:
 *
 * - A setter, such as [setSpeed], returns at once and is checked by the player in the worker, so
 *   the rule is written once. A value the player refuses does not throw at the call, as it does on
 *   `KitePlayer`: it arrives on [events] as [PlaybackWarning.CommandRefused], naming the setter.
 *   That warning is not in [warningHistory], which is the worker player's own.
 * - [diagnosticsDump], [supportBundle] and [warningHistory] ask the worker, so they suspend.
 *
 * ### What does not cross
 *
 * - `subtitleCues`: the worker draws the subtitles on the canvas itself.
 * - `coverArt`: the media session of the page's own player shows it, and a worker has none.
 * - `position()` and `audioClock()`: read [progress] instead. `transportMark` and `awaitClose`.
 * - `timeOfDayAt`, `positionAtTimeOfDay`, `seekToTimeOfDay` and `timeOfDayClock`: read the time
 *   of day of the position, and the first and last moments the stream dates, from [progress].
 * - `inspect`, `scanAudio`, `captureFrame`, `thumbnailAt`, recording, `memento` and `restore`.
 * - Attaching or detaching a renderer or an audio tap, and `setExternalClock`.
 * - An item, or an external subtitle or a thumbnail file, with its own reader: it is refused with
 *   [PlaybackError.ConfigurationInvalid]. Give it an address instead.
 * - A `PlayerConfig`: the worker builds its player on the default one.
 *
 * The page's own `KitePlayer` is unchanged and stays the web default.
 */
public class KitePlayerWorker private constructor(
    private val worker: JsAny,
    private val audio: WebWorkletAudio?,
    private val canvas: JsAny?,
    private val keepDisplayAwake: Boolean,
) : AutoCloseable {

    /** True while this page holds its screen awake for the worker's picture (#238). */
    private var holdingDisplay = false

    /** Holds the page's screen awake while the worker draws a playing picture on its canvas (#238). */
    private fun holdDisplay(snapshot: PlayerSnapshot?) {
        val wanted = keepDisplayAwake && canvas != null && dead == null && !closed &&
            snapshot != null && snapshot.status == PlaybackStatus.Playing && snapshot.videoSize != null
        if (wanted == holdingDisplay) return
        holdingDisplay = wanted
        WebDisplayAwake.setHeld(wanted)
    }

    private val stateFlow = MutableStateFlow(PlayerSnapshot())
    private val progressFlow = MutableStateFlow(Progress())
    private val statsFlow = MutableStateFlow(PlaybackStats())
    private val eventFlow = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)

    /** The player's state, as the worker last sent it. Position is not in it; see [progress]. */
    public val state: StateFlow<PlayerSnapshot> = stateFlow.asStateFlow()

    /** The position and how far ahead the worker has read, as the worker last sent them. */
    public val progress: StateFlow<Progress> = progressFlow.asStateFlow()

    /** Diagnostics, as the worker last sent them, once a second by default. */
    public val stats: StateFlow<PlaybackStats> = statsFlow.asStateFlow()

    /**
     * What happened in the player. A worker that dies sends [PlayerEvent.Failed] from here, and a
     * setter the player refused sends [PlaybackWarning.CommandRefused].
     */
    public val events: SharedFlow<PlayerEvent> = eventFlow.asSharedFlow()

    private val pending = HashMap<Int, CompletableDeferred<Answer?>>()
    private var nextId = 1
    private var closeId: Int? = null
    private var dead: PlaybackError? = null
    private val closed: Boolean get() = closeId != null

    /**
     * Each item as it crossed to the worker, mapped to the caller's own. The worker's snapshot and
     * its [PlayerEvent.Opened] are mapped back through this, so the page reads the objects it gave,
     * relative addresses and all. An item the worker changed, or one this map has forgotten, reads
     * as it crossed.
     */
    private val sentItems = HashMap<MediaItem, MediaItem>()

    /** Set by [start] until the worker has answered the first message. */
    private var started: CompletableDeferred<Unit>? = null

    /**
     * Opens [media] and returns once the first frame is ready and the player is paused on it.
     *
     * @throws PlaybackException with [PlaybackError.ConfigurationInvalid] for an item with a reader
     *         of its own, which cannot cross to the worker, or with the error the open failed with.
     */
    public suspend fun open(media: MediaItem) {
        val crossing = crossing(media)
        sentItems.clear()
        sentItems[crossing] = media
        call(Command.Open(crossing))
    }

    /** Opens [items] as the queue, starting at [startIndex], as `KitePlayer.openQueue` does. */
    public suspend fun openQueue(items: List<MediaItem>, startIndex: Int = 0) {
        val crossing = items.map(::crossing)
        sentItems.clear()
        crossing.forEachIndexed { i, item -> sentItems[item] = items[i] }
        call(Command.OpenQueue(crossing, startIndex))
    }

    /**
     * Starts or resumes playback. Call it from a user gesture's own handler: the browser starts the
     * page's audio device only there, and this starts it before it tells the worker.
     */
    public fun play() {
        checkOpen()
        audio?.resume()
        send(Control.Play)
    }

    /** Pauses playback. */
    public fun pause() {
        send(Control.Pause)
    }

    /** Seeks to [to] and returns once the worker has landed there, as `KitePlayer.seek` does. */
    public suspend fun seek(to: Duration, mode: SeekMode = SeekMode.Precise) {
        call(Command.Seek(to, mode))
    }

    /** Asks for a seek and returns at once, for a seek bar being dragged. */
    public fun requestSeek(to: Duration, mode: SeekMode = SeekMode.KeyframeThenRefine) {
        send(Control.RequestSeek(to, mode))
    }

    /** Stops playback and closes what was open. The player stays usable. */
    public suspend fun stop() {
        call(Command.Stop)
    }

    /** Opens the next queue item, keeping the play or pause intent. */
    public suspend fun next() {
        call(Command.Next)
    }

    /** Opens the previous queue item, keeping the play or pause intent. */
    public suspend fun previous() {
        call(Command.Previous)
    }

    /** Inserts [items] at [index], or at the end when [index] is null. */
    public suspend fun addToQueue(items: List<MediaItem>, index: Int? = null) {
        val crossing = items.map(::crossing)
        crossing.forEachIndexed { i, item -> sentItems[item] = items[i] }
        call(Command.AddToQueue(crossing, index))
    }

    /** Inserts one item. See the list overload. */
    public suspend fun addToQueue(item: MediaItem, index: Int? = null) {
        addToQueue(listOf(item), index)
    }

    /** Removes the queue item at [index]. */
    public suspend fun removeFromQueue(index: Int) {
        call(Command.RemoveFromQueue(index))
    }

    /** Moves the queue item at [from] so that it sits at [to]. */
    public suspend fun moveInQueue(from: Int, to: Int) {
        call(Command.MoveInQueue(from, to))
    }

    /** Removes every queue item except the one playing. */
    public suspend fun clearQueue() {
        call(Command.ClearQueue)
    }

    /** Steps a paused player by exactly one decoded frame. */
    public suspend fun stepFrame(direction: StepDirection = StepDirection.Forward) {
        call(Command.StepFrame(direction))
    }

    /**
     * The chapter whose span holds [position], or null: at or after its start and before its end,
     * and the last such chapter when spans overlap, the rule `KitePlayer.chapterAt` follows.
     */
    public fun chapterAt(position: Duration): Chapter? =
        state.value.chapters.lastOrNull { chapter -> chapter.start <= position && chapter.end.let { it == null || position < it } }

    /** Seeks to the start of chapter [index]. */
    public suspend fun seekToChapter(index: Int) {
        call(Command.SeekToChapter(index))
    }

    /** Seeks to the start of the next chapter. The worker reads the position, so it is exact. */
    public suspend fun nextChapter() {
        call(Command.NextChapter)
    }

    /** Seeks to the start of this chapter, or the one before within its first three seconds. */
    public suspend fun previousChapter() {
        call(Command.PreviousChapter)
    }

    /** Selects a track, or deselects the kind with a null [track], and says what happened. */
    public suspend fun selectTrack(kind: TrackKind, track: TrackId?): TrackChange =
        ask<Answer.Change>(Command.SelectTrack(kind, track)).change

    /** Shows a second subtitle track at the top of the picture, or clears it with null. */
    public suspend fun selectSecondarySubtitle(track: TrackId?): TrackChange =
        ask<Answer.Change>(Command.SelectSecondarySubtitle(track)).change

    /** Plays the variant at [index] of the tracks' variants, or lets the player choose with null. */
    public suspend fun selectVariant(index: Int?) {
        call(Command.SelectVariant(index))
    }

    /** Plays the channel numbered [number] of the tracks' programmes, or lets the player choose with null. */
    public suspend fun selectProgram(number: Int?) {
        call(Command.SelectProgram(number))
    }

    /**
     * Loads a subtitle file, selects it, and returns its id once it is showing. [source] needs an
     * address: one with its own reader is refused with [PlaybackError.ConfigurationInvalid].
     */
    public suspend fun addExternalSubtitle(source: SubtitleSource): TrackId {
        crossingRefusal(source)?.let { throw it }
        return ask<Answer.Track>(Command.AddExternalSubtitle(source.copy(uri = pageAddress(source.uri)))).id
    }

    /**
     * Reads an external subtitle track's file again in [encoding], or decided from its bytes for
     * null, as `KitePlayer.reloadExternalSubtitle` does, and returns once the new reading shows.
     */
    public suspend fun reloadExternalSubtitle(track: TrackId, encoding: String? = null) {
        call(Command.ReloadExternalSubtitle(track, encoding))
    }

    /** The worker player's diagnostics dump. */
    public suspend fun diagnosticsDump(): String = ask<Answer.Text>(Command.DiagnosticsDump).text

    /** The worker player's support bundle. */
    public suspend fun supportBundle(): String = ask<Answer.Text>(Command.SupportBundle).text

    /** The worker player's last warnings, oldest first. */
    public suspend fun warningHistory(): List<TimedWarning> = ask<Answer.Warnings>(Command.WarningHistory).warnings

    /** Sets the playback rate. */
    public fun setSpeed(value: Double): Unit = send(Control.SetSpeed(value))

    /** Chooses whether [setSpeed] keeps pitch. */
    public fun setPreservePitch(value: Boolean): Unit = send(Control.SetPreservePitch(value))

    /** Chooses which keyframe a [SeekMode.Keyframe] seek lands on; see [KitePlayer.setKeyframeChoice]. */
    public fun setKeyframeChoice(choice: KeyframeChoice): Unit = send(Control.SetKeyframeChoice(choice))

    /** Sets the volume. */
    public fun setVolume(value: Float): Unit = send(Control.SetVolume(value))

    /** Lowers the sound by [level] without touching the volume. */
    public fun setDuckLevel(level: Float): Unit = send(Control.SetDuckLevel(level))

    /** Sets the stereo balance. */
    public fun setBalance(value: Float): Unit = send(Control.SetBalance(value))

    /** Sets what the two front speakers play (#462). */
    public fun setStereoMode(mode: StereoMode): Unit = send(Control.SetStereoMode(mode))

    /** Turns the night mode on or off (#442). */
    public fun setNightMode(on: Boolean): Unit = send(Control.SetNightMode(on))

    /** Raises or lowers the dialogue in a downmix, in decibels (#442). */
    public fun setDialogueLevel(db: Float): Unit = send(Control.SetDialogueLevel(db))

    /** Moves the pitch by semitones without changing the speed (#465). */
    public fun setPitch(semitones: Double): Unit = send(Control.SetPitch(semitones))

    /** Shortens the silent stretches of a podcast or an audiobook (#429). */
    public fun setSkipSilence(on: Boolean): Unit = send(Control.SetSkipSilence(on))

    /** Silences the sound without losing the volume. */
    public fun setMuted(value: Boolean): Unit = send(Control.SetMuted(value))

    /** Parks or resumes video decoding in place. */
    public fun setVideoEnabled(enabled: Boolean): Unit = send(Control.SetVideoEnabled(enabled))

    /** Sets what happens at the end of the media. */
    public fun setLoop(mode: LoopMode): Unit = send(Control.SetLoop(mode))

    /** Plays the queue in a shuffled order, or in the order it was given. */
    public fun setShuffle(enabled: Boolean, seed: Long? = null): Unit = send(Control.SetShuffle(enabled, seed))

    /** Arms or clears the A-B loop. */
    public fun setAbLoop(a: Duration?, b: Duration? = null): Unit = send(Control.SetAbLoop(a, b))

    /** Sets how the picture occupies the canvas. */
    public fun setVideoScale(mode: VideoScale): Unit = send(Control.SetVideoScale(mode))

    /** Sets the live picture controls. */
    public fun setVideoAdjustments(value: VideoAdjustments): Unit = send(Control.SetVideoAdjustments(value))

    /** Sets how much work the renderer spends on the picture. */
    public fun setRenderQuality(value: RenderQuality): Unit = send(Control.SetRenderQuality(value))

    /** Sets the framing controls. */
    public fun setVideoTransform(value: VideoTransform): Unit = send(Control.SetVideoTransform(value))

    /** Sets how HDR video reaches the screen. */
    public fun setHdrPolicy(value: HdrPolicy): Unit = send(Control.SetHdrPolicy(value))

    /** See `KitePlayer.setFlashGuard`. The worker's canvas has no guard yet, so it changes nothing drawn. */
    public fun setFlashGuard(mode: FlashGuard): Unit = send(Control.SetFlashGuard(mode))

    /** Shifts subtitle timing. Positive shows cues later. */
    public fun setSubtitleDelay(value: Duration): Unit = send(Control.SetSubtitleDelay(value))

    /** Scales subtitle text over the authored size. */
    public fun setSubtitleScale(value: Float): Unit = send(Control.SetSubtitleScale(value))

    /** Overrides the authored subtitle style, or clears the override with null. */
    public fun setSubtitleStyle(override: SubtitleStyleOverride?): Unit = send(Control.SetSubtitleStyle(override))

    /** Moves the subtitles up the screen. */
    public fun setSubtitlePosition(value: Float): Unit = send(Control.SetSubtitlePosition(value))

    /** Draws only the forced pictures of a Blu-ray or DVD subtitle track, or every picture again. */
    public fun setForcedPicturesOnly(value: Boolean): Unit = send(Control.SetForcedPicturesOnly(value))

    /** Keeps subtitles inside the safe area of the output. */
    public fun setSubtitleSafeArea(value: SubtitleSafeArea): Unit = send(Control.SetSubtitleSafeArea(value))

    /** Delays the sound against the picture. */
    public fun setAudioDelay(value: Duration): Unit = send(Control.SetAudioDelay(value))

    /** Stops playback later, fading the sound down first. Null cancels an armed timer. */
    public fun setSleepTimer(timer: SleepTimer?, fade: Duration = KitePlayer.DEFAULT_SLEEP_FADE): Unit =
        send(Control.SetSleepTimer(timer, fade))

    /** Sets the ten-band equaliser. */
    public fun setEqualizer(settings: EqualizerSettings): Unit = send(Control.SetEqualizer(settings))

    /** Sets the positions to announce with [PlayerEvent.MarkerReached]. */
    public fun setMarkers(markers: List<Marker>): Unit = send(Control.SetMarkers(markers))

    /**
     * Picture in picture for the canvas this player draws on, or null when it was started without
     * one or this browser has neither feature `KitePlayerPictureInPicture` uses. The canvas belongs
     * to the worker, and both features still carry its frames: the document window takes the page's
     * canvas element, and the video element's window plays a live capture of it. Its play and pause
     * buttons play and pause this player. Call `start` from the viewer's click, as there.
     */
    public fun pictureInPictureOrNull(): KitePlayerPictureInPicture? {
        checkOpen()
        val canvas = canvas ?: return null
        return KitePlayerPictureInPicture.createOrNull(
            canvas = canvas,
            // The window may outlive the player, and a closed player refuses every call.
            setViewport = { width, height, scale -> if (!closed) setViewport(width, height, scale) },
            play = { if (!closed) play() },
            pause = { if (!closed) pause() },
        )
    }

    /**
     * Sizes the canvas's drawing buffer to [width] by [height] CSS pixels at [scale] device pixels
     * each, as `VideoRenderer.setViewport` does. The canvas belongs to the worker, so the page sets
     * its size through here rather than on the element.
     */
    public fun setViewport(width: Int, height: Int, scale: Float) {
        send(Control.SetViewport(width, height, scale))
    }

    /**
     * Asks the worker to close the player and end, and returns at once. The worker ends when the
     * player has closed. [closeAndAwait] waits for that.
     *
     * A worker that is inside a read does not see the request until the read returns, and a live
     * stream that stopped growing never returns it (#568). The page ends a worker that has not
     * answered within three seconds, and every command still waiting on it fails.
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
        post(PageMessage.Close(id))
        afterMillis(CLOSE_GRACE_MILLIS) { endUnanswered(id) }
    }

    /** Ends a worker that did not answer the close [id]. */
    private fun endUnanswered(id: Int) {
        val closing = pending.remove(id) ?: return
        val error = PlaybackError.Internal("the player closed while its worker did not answer")
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(PlaybackException(error)) }
        finish()
        closing.complete(null)
    }

    /** [close], returning once the worker has closed the player and ended. */
    public suspend fun closeAndAwait() {
        close()
        closeId?.let { pending[it] }?.await()
    }

    private fun checkOpen() {
        check(!closed) { "the player is closed" }
    }

    /** [media] as it crosses: with its addresses made whole here, where they mean what the page meant. */
    private fun crossing(media: MediaItem): MediaItem {
        crossingRefusal(media)?.let { throw it }
        return media.copy(
            uri = pageAddress(media.uri),
            externalSubtitles = media.externalSubtitles.map { it.copy(uri = pageAddress(it.uri)) },
        )
    }

    private fun own(item: MediaItem): MediaItem = sentItems[item] ?: item

    /** Sends [command] with a fresh id, and waits for its one reply. */
    private suspend fun call(command: Command): Answer? {
        checkOpen()
        dead?.let { throw PlaybackException(it) }
        val id = nextId++
        val reply = CompletableDeferred<Answer?>()
        pending[id] = reply
        post(PageMessage.Call(id, command))
        return reply.await()
    }

    /** [call], for a command whose reply carries an answer of the kind [A]. */
    private suspend inline fun <reified A : Answer> ask(command: Command): A =
        call(command) as? A ?: throw PlaybackException(
            PlaybackError.Internal("the worker answered ${command.member} without what it returns"),
        )

    private fun send(control: Control) {
        checkOpen()
        post(PageMessage.Send(control))
    }

    private fun post(message: PageMessage) {
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
                val failure = message.failure
                if (failure == null) reply.complete(message.answer) else reply.completeExceptionally(failure.toException())
                if (message.id == closeId) finish()
            }
            is WorkerMessage.State -> {
                stateFlow.value = message.snapshot.let { snapshot ->
                    snapshot.copy(media = snapshot.media?.let(::own), queue = snapshot.queue.map(::own))
                }
                holdDisplay(message.snapshot)
            }
            is WorkerMessage.Progressed -> progressFlow.value = message.progress
            is WorkerMessage.Stats -> statsFlow.value = message.stats
            is WorkerMessage.Event -> eventFlow.tryEmit(
                when (val event = message.event) {
                    is PlayerEvent.Opened -> event.copy(media = own(event.media))
                    else -> event
                },
            )
            is WorkerMessage.Audio -> if (message.resume) audio?.resume() else audio?.suspend()
        }
    }

    /**
     * The worker died, or its script could not load. Every command waiting on it fails, and the
     * player reports [PlaybackStatus.Failed], rather than a player that stops answering. A close
     * that was waiting is done instead: the player it asked to close is gone.
     */
    private fun died(detail: String) {
        if (dead != null) return
        val error = PlaybackError.Internal("the player's worker stopped: $detail")
        dead = error
        holdDisplay(null)
        started?.completeExceptionally(PlaybackException(error))
        val closing = closeId?.let { pending.remove(it) }
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(PlaybackException(error)) }
        if (closed) {
            finish()
            closing?.complete(null)
            return
        }
        stateFlow.update { it.copy(status = PlaybackStatus.Failed, error = error) }
        eventFlow.tryEmit(PlayerEvent.Failed(error))
        workerTerminate(worker)
    }

    /** Ends the worker and the page's audio once the close is answered, or the worker is gone. */
    private fun finish() {
        holdDisplay(null)
        workerTerminate(worker)
        audio?.close()
        closeId?.let { pending.remove(it) }?.complete(null)
    }

    public companion object {
        /** Keeps the original startup entry point with display wake enabled. */
        public suspend fun start(
            canvas: JsAny?,
            workerUrl: String = "./kiteplayer-web-worker.mjs",
            codecUrl: String = "./kite.mjs",
            libassUrl: String? = "./kiteass.mjs",
        ): KitePlayerWorker = start(canvas, workerUrl, codecUrl, libassUrl, true)

        /**
         * Starts a worker player, hands it [canvas] and the page's audio, and returns once it can
         * play.
         *
         * @param canvas the `HTMLCanvasElement` to draw on, or null for sound only. Its drawing
         *        passes to the worker for good, so the page cannot draw on it afterwards.
         * @param workerUrl the worker binary, `kiteplayer-web-worker.mjs`, as the page serves it.
         * @param codecUrl the codec module, `kite.mjs`. A relative address is read against the
         *        page's, as [workerUrl] is.
         * @param libassUrl the libass module, `kiteass.mjs`, which draws ASS subtitles as their
         *        authors styled them. A relative address is read against the page's. The worker
         *        starts loading it at once and does not wait for it: an ASS track that opens first is
         *        kept until it lands. Without the module, ASS draws with the built-in styling. Null
         *        loads nothing now, and the first ASS track then looks beside the worker binary.
         * @param keepDisplayAwake whether the page's screen stays awake while the worker plays a
         *        picture on [canvas] (#238), which a canvas does not get from the browser by itself.
         * @throws PlaybackException with [PlaybackError.Internal] when the worker cannot load, or
         *         cannot load the codec module.
         */
        public suspend fun start(
            canvas: JsAny?,
            workerUrl: String = "./kiteplayer-web-worker.mjs",
            codecUrl: String = "./kite.mjs",
            libassUrl: String? = "./kiteass.mjs",
            keepDisplayAwake: Boolean = true,
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
                val created = KitePlayerWorker(worker, audio, canvas, keepDisplayAwake)
                val ready = CompletableDeferred<Unit>()
                created.started = ready
                player = created
                // Whole here, where a relative address means what the page meant: the worker would
                // read it against its own address.
                val init = PageMessage.Init(
                    codecUrl = pageAddress(codecUrl),
                    sampleRate = audio?.sampleRate ?: 0,
                    channels = audio?.channels ?: 0,
                    latencySeconds = audio?.outputLatencySeconds,
                    libassUrl = libassUrl?.let(::pageAddress),
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

/** How long a close waits for the worker's answer before the page ends the worker. */
private const val CLOSE_GRACE_MILLIS = 3_000

@JsFun("(millis, run) => { setTimeout(run, millis); }")
private external fun afterMillis(millis: Int, run: () -> Unit)
