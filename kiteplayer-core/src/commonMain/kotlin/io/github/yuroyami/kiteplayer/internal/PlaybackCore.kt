// onTimeout is the select clause that makes an actor's wait cancellation free. Its alternative, a
// timeout wrapped around a receive, can consume a message and then be cancelled, which loses a command
// and suspends its caller for ever.
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.AudioPlayback
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MasterClock
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackStats
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.SyncMode
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
import io.github.yuroyami.kiteplayer.VideoPlayback
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The engine's session: one actor owning all playback state, and five workers doing the work.
 *
 * ### Why an actor
 *
 * Every piece of playback state lives here and is touched by one coroutine on one dispatcher: the
 * status, the epoch, the track selection, the published snapshot, and the decision about what happens
 * next. Nothing outside reaches in. Every external interaction is a message with its own reply, so
 * there is no lock to forget and no field that two threads can disagree about. The workers own exactly
 * what a single thread must own, a demuxer cursor or a decoder context, and they communicate only
 * through the queues and this actor's messages.
 *
 * ### Why the loop is level triggered
 *
 * Each pass reads the state, decides, and may lower one shared wake-up deadline. No handler is a
 * transition hook, and no handler is the only chance to notice something. A condition that becomes true
 * at an inconvenient moment is simply noticed on the next pass, which is what makes it impossible for a
 * command sequence to wedge the player: there is no edge to miss. Handlers run in one fixed order, and
 * that order is data rather than a hand-written sequence of calls, so a test asserts it directly.
 *
 * ### Why quiescence and not just generations
 *
 * Every packet, frame and clock carries the epoch it belongs to, and anything stale is discarded at the
 * next hop. That is defence in depth and it is not enough on its own. A tag says that work is stale; it
 * does not say whether a worker is inside a decoder, a queue or a real-time device callback right now.
 * So a seek stops the sink, asks every worker to park at a boundary of its own choosing, waits for the
 * acknowledgements, and only then flushes decoders and clears buffers. See [runSeek].
 */
internal class PlaybackCore(
    private val config: PlayerConfig,
    private val backend: MediaBackend,
    private val output: OutputBackend,
    private val dispatchers: PlaybackDispatchers,
    /**
     * Whether tearing the session down also closes [dispatchers].
     *
     * True by default, because the usual owner is a player whose `close` is the last thing anyone calls
     * and whose worker threads must not outlive it. False when a caller shares one dispatcher set
     * between sessions, which a virtual-time test does.
     */
    private val closeDispatchers: Boolean = true,
    /**
     * The job this session's coroutines hang under.
     *
     * Null makes the session's lifetime its own, which is what a player whose `close` is the only end it
     * has wants. A caller that already has a lifetime, a test scope or an application scope, passes it
     * here so that cancelling that lifetime takes the session with it rather than leaving five workers
     * running.
     */
    parent: Job? = null,
) : AutoCloseable {

    private val clock = output.clock

    private val scope = CoroutineScope(
        dispatchers.session + SupervisorJob(parent) + CoroutineName("kiteplayer-session"),
    )

    private val commands = Channel<CoreCommand>(Channel.UNLIMITED)
    private val outcomes = Channel<WorkerOutcome>(Channel.UNLIMITED)

    /** Commands taken off the channel but not yet executed, so nothing is lost to a preemption check. */
    private val heldCommands = ArrayDeque<CoreCommand>()
    private val heldOutcomes = ArrayDeque<WorkerOutcome>()

    private val snapshotState = MutableStateFlow(PlayerSnapshot())
    private val progressState = MutableStateFlow(Progress())
    private val statsState = MutableStateFlow(PlaybackStats())
    private val eventSink = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)

    val snapshots: StateFlow<PlayerSnapshot> get() = snapshotState.asStateFlow()
    val progress: StateFlow<Progress> get() = progressState.asStateFlow()
    val stats: StateFlow<PlaybackStats> get() = statsState.asStateFlow()
    val events: SharedFlow<PlayerEvent> get() = eventSink.asSharedFlow()

    // Actor-confined state. Nothing below is read or written from any other coroutine.
    private var status: PlaybackStatus = PlaybackStatus.Idle
    private var media: MediaItem? = null
    private var session: OpenSession? = null
    private var tracks: Tracks = Tracks.Empty
    private var lastError: PlaybackError? = null
    private var playRequested = false
    private var loop: LoopMode = LoopMode.Off
    private var speed: Double = 1.0
    private var volume: Float = 1.0f
    private var muted: Boolean = false
    private var closed = false
    private var terminated = false

    /** The closed flag as the non-suspending commands see it, from whatever thread calls them. */
    private val closedNow = atomic(false)

    private var requestedEpoch: Generation = Generation.Initial
    private var seekPhase: SeekPhase = SeekPhase.Idle
    private var pendingSeek: SeekRequest? = null
    private val pendingSeekReplies = mutableListOf<CompletableDeferred<SeekResult>>()
    private var seekHeldSinceNanos: Long = 0
    private var lastSeekAtNanos: Long = 0
    private var framesShownAtLastSeek: Long = 0
    private var pendingTrackChange: CoreCommand.SelectTrack? = null

    private var demuxUnderrunSeen = false
    private var rebuffers = 0L
    private var stillImageShownSinceNanos: Long = 0
    private var stillImageFinished = false
    private var firstFrameSeen = false
    private var openedAtNanos: Long = 0
    private var lastProgressAtNanos: Long = 0
    private var lastStatsAtNanos: Long = 0
    private var lastStatsDecoded: Long = 0

    /** The deadline this pass may sleep until. Handlers lower it; nothing raises it. */
    private var wakeAtNanos: Long = 0

    /** Read from any thread, so it is published rather than computed on demand. */
    private val publishedPositionMicros = atomic(0L)

    // Observable-for-tests counters. Everything here is written by the actor only.
    var loopPasses: Long = 0
        private set
    var seekFlushCycles: Long = 0
        private set
    val endOfStream: EndOfStreamState = EndOfStreamState()

    /** Where the seek machine is, for the test that drives it. */
    val phase: SeekPhase get() = seekPhase

    /**
     * Everything a stuck session needs to explain itself, in one line.
     *
     * A player that will not move is the failure that costs the most to diagnose, because the interesting
     * state is spread over five workers. This is not a log line: the actor builds it on demand, so it is
     * always the truth of the pass that is running rather than something recorded earlier.
     */
    val debugState: String
        get() = buildString {
            append("status=").append(status)
            append(" phase=").append(seekPhase)
            append(" playRequested=").append(playRequested)
            append(" epoch=").append(requestedEpoch)
            append(" pendingSeek=").append(pendingSeek != null)
            append(" demuxUnderrun=").append(demuxUnderrunSeen)
            append(" eos=[demux=").append(endOfStream.demuxerEnded)
            append(" audio=").append(endOfStream.audioDecoderDrained)
            append(" video=").append(endOfStream.videoDecoderDrained)
            append(" draining=").append(endOfStream.draining)
            append(" sink=").append(endOfStream.sinkDrained)
            append(" drainFailed=").append(endOfStream.drainFailed)
            append("]")
            val open = session
            if (open == null) {
                append(" session=none")
            } else {
                append(" video=").append(open.videoStatus).append(" audio=").append(open.audioStatus)
                open.videoQueue?.let { append(" videoQueue=").append(it.count).append("/").append(it.bufferedUs) }
                open.audioQueue?.let { append(" audioQueue=").append(it.count).append("/").append(it.bufferedUs) }
                append(" videoEos=").append(open.videoQueue?.isEndOfStream)
                append(" audioEos=").append(open.audioQueue?.isEndOfStream)
                append(" frames=").append(open.video?.queuedFrames)
                append(" ring=").append(open.audio?.buffered)
                append(" scheduler=").append(open.schedulerMode.value)
                append(" parked=").append(open.workers.count { it.isParked })
                append("/").append(open.workers.size)
                append(" finished=").append(open.workers.count { it.isFinished })
            }
        }
    val statusHistory: MutableList<PlaybackStatus> = mutableListOf(PlaybackStatus.Idle)
    val illegalTransitions: MutableList<String> = mutableListOf()

    /** Called with each handler's name as it runs, so a test can record the real order. */
    var onHandlerRun: ((String) -> Unit)? = null

    private class Handler(val name: String, val run: suspend () -> Unit)

    /**
     * The pass, in the one order it ever runs in.
     *
     * Order is data because it is a contract. `handleSubtitles` has an empty body in this build and is
     * here anyway: leaving it out would let the order change silently when cue timing lands.
     */
    private val handlers: List<Handler> = listOf(
        Handler("drainCommands") { drainCommands() },
        Handler("handleTrackChanges") { handleTrackChanges() },
        Handler("handleAudioFill") { handleAudioFill() },
        Handler("handleVideoWrite") { handleVideoWrite() },
        Handler("handlePlaybackRestart") { handlePlaybackRestart() },
        Handler("handlePlaybackTime") { handlePlaybackTime() },
        Handler("handleBuffering") { handleBuffering() },
        Handler("handleSubtitles") { handleSubtitles() },
        Handler("handleEof") { handleEof() },
        Handler("handleLoop") { handleLoop() },
        Handler("handleQueuedSeek") { handleQueuedSeek() },
        Handler("publishSnapshot") { publishSnapshot() },
        Handler("awaitWork") { awaitWork() },
    )

    /** The declared order, for the test that asserts it against the design. */
    val handlerOrder: List<String> = handlers.map { it.name }

    private val actor: Job = scope.launch { runLoop() }

    // ---------------------------------------------------------------------------------------------
    // The commands, as the facade calls them.
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens [item] and returns once the first frame is ready and the player is paused on it.
     *
     * Cancelling the caller does not leave a half-open graph behind: the actor is told to stop, which
     * tears down whatever it had built and returns to Idle.
     */
    suspend fun open(item: MediaItem) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.Open(item, reply))
        awaitReply(reply, stopOnCancellation = true)
    }

    /**
     * Asks for playback. Idempotent, and queued rather than refused during an open or a seek.
     *
     * Not a suspending call, because there is nothing useful to wait for: what the caller wants is for
     * playback to start as soon as the pipeline can supply it, and the start rendezvous decides that. A
     * caller that wants to know watches the status.
     */
    fun play() {
        check(!closedNow.value) { "the player is closed, so play cannot run" }
        commands.trySend(CoreCommand.Play(CompletableDeferred()))
    }

    /**
     * Asks for a pause. Idempotent, and queued the same way.
     *
     * The ordering the design promises is internal: the clocks freeze only after the device is quiet and
     * its last anchor has been consumed, so a late callback cannot re-anchor a frozen clock.
     */
    fun pause() {
        check(!closedNow.value) { "the player is closed, so pause cannot run" }
        commands.trySend(CoreCommand.Pause(CompletableDeferred()))
    }

    /** Seeks and returns what happened to this request: it landed, or a later request replaced it. */
    suspend fun seek(to: Pts, mode: SeekMode): SeekResult {
        val reply = CompletableDeferred<SeekResult>()
        send(CoreCommand.Seek(SeekRequest(SeekTarget.Absolute(to), mode), reply))
        return awaitReply(reply)
    }

    /** Fire and forget, coalescing by contract. What a seek bar drag calls sixty times a second. */
    fun seekLater(to: Pts, mode: SeekMode) {
        commands.trySend(CoreCommand.SeekLater(SeekRequest(SeekTarget.Absolute(to), mode)))
    }

    /**
     * Seeks by an offset from where playback is. What an arrow key produces.
     *
     * Relative because that is what the merge rules are for: holding the key down must move by the total
     * of the presses, not by the last one, and that only works if the request keeps its shape until the
     * moment it is resolved against a position.
     */
    fun seekByLater(offset: Duration, mode: SeekMode) {
        commands.trySend(CoreCommand.SeekLater(SeekRequest(SeekTarget.Relative(offset), mode)))
    }

    /** Seeks to a fraction of the duration. What dragging a seek bar produces. */
    fun seekToFractionLater(fraction: Double, mode: SeekMode) {
        require(fraction.isFinite() && fraction >= 0.0 && fraction <= 1.0) {
            "a seek bar position must be between 0 and 1, was $fraction"
        }
        commands.trySend(CoreCommand.SeekLater(SeekRequest(SeekTarget.Factor(fraction), mode)))
    }

    suspend fun stop() {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.Stop(reply))
        awaitReply(reply)
    }

    suspend fun selectTrack(kind: TrackKind, track: TrackId?) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SelectTrack(kind, track, reply))
        awaitReply(reply)
    }

    suspend fun attachRenderer(renderer: VideoRenderer) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.AttachRenderer(renderer, reply))
        awaitReply(reply)
    }

    /** Returns only once no submission to the renderer being detached is outstanding. */
    suspend fun detachRenderer() {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.DetachRenderer(reply))
        awaitReply(reply)
    }

    suspend fun setSpeed(value: Double) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SetSpeed(value, reply))
        awaitReply(reply)
    }

    suspend fun setVolume(value: Float) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SetVolume(value, reply))
        awaitReply(reply)
    }

    suspend fun setMuted(value: Boolean) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SetMuted(value, reply))
        awaitReply(reply)
    }

    suspend fun setLoop(mode: LoopMode) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SetLoop(mode, reply))
        awaitReply(reply)
    }

    /** The position as of the last pass, which is never more than the wake floor old. */
    fun position(): Duration = publishedPositionMicros.value.microseconds

    /** Terminal and idempotent. Returns at once; the teardown itself is bounded. */
    override fun close() {
        commands.trySend(CoreCommand.Close(CompletableDeferred()))
    }

    /**
     * Terminal and idempotent, awaited.
     *
     * @throws PlaybackException with [PlaybackError.RuntimeCompromised] when teardown did not finish
     *         inside its deadline. A wedged native call cannot be killed from inside the process, so
     *         the honest answer is that the runtime is compromised rather than a successful close.
     */
    suspend fun closeAndAwait() {
        val reply = CompletableDeferred<Unit>()
        if (commands.trySend(CoreCommand.Close(reply)).isSuccess) reply.await() else reply.complete(Unit)
        actor.join()
    }

    /**
     * Enqueues a command whose completion the caller does not wait for.
     *
     * The facade's non-suspending setters need this. Ordering is what makes it safe: the command lands on
     * the same channel in the same order the calls were made, so a volume change followed by a mute is
     * applied in that order even though neither call waited. The reply is completed by the actor and
     * dropped, which is honest only because every one of these commands is validated by the caller before
     * it is posted; a rejection that only the actor could find would have nowhere to go.
     */
    fun post(command: CoreCommand) {
        check(!closedNow.value) { "the player is closed, so ${command.name} cannot run" }
        commands.trySend(command)
    }

    private suspend fun send(command: CoreCommand) {
        if (!commands.trySend(command).isSuccess) {
            command.fail(IllegalStateException("the player is closed"))
        }
    }

    private suspend fun <T> awaitReply(reply: CompletableDeferred<T>, stopOnCancellation: Boolean = false): T {
        try {
            return reply.await()
        } catch (cancellation: CancellationException) {
            // The caller went away. Nothing half built may be left behind, and cancellation is never
            // reported as a playback failure.
            if (stopOnCancellation) commands.trySend(CoreCommand.Stop(CompletableDeferred()))
            throw cancellation
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The loop.
    // ---------------------------------------------------------------------------------------------

    private suspend fun runLoop() {
        while (!terminated) {
            wakeAtNanos = clock.nanos() + WAKE_FLOOR.inWholeNanoseconds
            try {
                for (handler in handlers) {
                    onHandlerRun?.invoke(handler.name)
                    handler.run()
                    if (terminated) return
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // The actor must not die quietly: a loop that stops is a player that hangs with no
                // explanation, which is the one failure mode worse than a typed error.
                val error = PlaybackError.Internal("the session loop failed", failure)
                resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
                teardownSession()
                fail(error)
                publishSnapshot()
            }
            loopPasses++
        }
    }

    /** Handlers lower this and nothing raises it, which is what makes the pass order safe to reorder. */
    private fun wakeIn(duration: Duration) {
        val candidate = clock.nanos() + duration.inWholeNanoseconds.coerceAtLeast(0)
        if (candidate < wakeAtNanos) wakeAtNanos = candidate
    }

    /**
     * Sleeps until the deadline, or until a message arrives, whichever comes first.
     *
     * The timeout is a select clause and not a `withTimeout` around a receive, and that is not a matter
     * of taste. A receive cancelled by a timeout may already have taken an element, and losing a command
     * that way is a caller suspended for ever on a reply that will never come. A select chooses exactly
     * one clause and cancels nothing, so a message either arrives here or stays in its channel.
     */
    private suspend fun awaitWork() {
        if (heldCommands.isNotEmpty() || heldOutcomes.isNotEmpty()) return
        val waitNanos = wakeAtNanos - clock.nanos()
        if (waitNanos <= 0) return
        select<Unit> {
            commands.onReceive { heldCommands.addLast(it) }
            outcomes.onReceive { heldOutcomes.addLast(it) }
            onTimeout(waitNanos.nanoseconds.atLeastOneTick()) { }
        }
    }

    /**
     * True when a stop or a close is waiting.
     *
     * Both preempt whatever the actor is in the middle of, which is why the long steps inside an open
     * and a seek ask. Everything taken off the channel to answer the question is held, so the next
     * [drainCommands] still sees it, in order.
     */
    private fun preempted(): Boolean {
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: break
            heldCommands.addLast(command)
        }
        return heldCommands.any { it is CoreCommand.Stop || it is CoreCommand.Close }
    }

    private suspend fun drainCommands() {
        while (true) {
            val outcome = heldOutcomes.removeFirstOrNull() ?: outcomes.tryReceive().getOrNull() ?: break
            handleWorkerOutcome(outcome)
            if (terminated) return
        }
        while (true) {
            val command = heldCommands.removeFirstOrNull() ?: commands.tryReceive().getOrNull() ?: break
            execute(command)
            if (terminated) return
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Command legality, and the commands themselves.
    // ---------------------------------------------------------------------------------------------

    /**
     * The legality table.
     *
     * Every command has a documented rule about the states it is legal in, and a command that is not
     * legal is refused rather than queued forever or quietly ignored. A refusal is an
     * [IllegalStateException] or an [UnsupportedOperationException] naming the state and what to do
     * instead, because it is a mistake in the caller's sequence and not a playback failure: a
     * [PlaybackException] means the media or the device failed.
     */
    private fun rejectionFor(command: CoreCommand): Throwable? {
        if (closed && command !is CoreCommand.Close) {
            return IllegalStateException("the player is closed, so ${command.name} cannot run")
        }
        return when (command) {
            // Failed is in the legal set alongside Idle and Ended, one state wider than digest 8.1's
            // table. A failed open or a dead worker leaves no session and no running worker to replace,
            // so the stop() the table asks for would be pure ceremony between a failure and its retry.
            // Every state the table means by "any playing state" is still refused.
            is CoreCommand.Open -> when (status) {
                PlaybackStatus.Idle, PlaybackStatus.Ended, PlaybackStatus.Failed -> null
                else -> IllegalStateException(
                    "open is legal from Idle, Ended and Failed; the player is $status, so call stop() first",
                )
            }
            // Legal in every state a live player can be in, including Opening and Seeking, where they are
            // remembered and applied by the restart handler as soon as the pipeline can honour them.
            // Refusing them there would make a caller time its own play against an open it cannot see.
            is CoreCommand.Play, is CoreCommand.Pause -> null
            is CoreCommand.Seek -> seekRejection()
            is CoreCommand.SeekLater -> null
            is CoreCommand.SelectTrack -> when {
                session == null -> IllegalStateException("selectTrack needs an open media item")
                session?.source?.seekable != true -> UnsupportedOperationException(
                    "this source is not seekable, so a track switch cannot reopen it and seek back to " +
                        "where playback was; see KPKMP.md digest 8.3",
                )
                command.kind == TrackKind.Subtitle -> UnsupportedOperationException(
                    "no subtitle decoder exists in this build, so a subtitle track cannot be selected",
                )
                else -> null
            }
            is CoreCommand.SetLoop -> when (command.mode) {
                LoopMode.All -> IllegalArgumentException(
                    "LoopMode.All repeats a queue and there is no queue; see KPKMP.md section 11",
                )
                else -> null
            }
            is CoreCommand.SetSpeed -> when {
                !command.value.isFinite() || command.value <= 0.0 ->
                    IllegalArgumentException("speed must be finite and positive, was ${command.value}")
                else -> null
            }
            is CoreCommand.SetVolume -> when {
                !command.value.isFinite() || command.value < 0f || command.value > 1f ->
                    IllegalArgumentException("volume must be between 0 and 1, was ${command.value}")
                else -> null
            }
            else -> null
        }
    }

    private fun seekRejection(): Throwable? = when {
        session == null -> IllegalStateException("seek needs an open media item")
        session?.source?.seekable != true -> UnsupportedOperationException(
            "this source is not seekable, so there is no position to move the cursor to",
        )
        else -> null
    }

    private suspend fun execute(command: CoreCommand) {
        rejectionFor(command)?.let {
            command.fail(it)
            return
        }
        when (command) {
            is CoreCommand.Open -> runOpen(command)
            is CoreCommand.Play -> {
                // Idempotent in its own state, and queued rather than refused while opening or seeking:
                // the restart handler applies it as soon as the pipeline can honour it.
                playRequested = true
                command.reply.complete(Unit)
            }
            is CoreCommand.Pause -> {
                playRequested = false
                applyPause()
                command.reply.complete(Unit)
            }
            is CoreCommand.Seek -> queueSeek(command.request, command.reply)
            is CoreCommand.SeekLater -> if (session?.source?.seekable == true) queueSeek(command.request, null)
            is CoreCommand.Stop -> {
                runStop()
                command.reply.complete(Unit)
            }
            is CoreCommand.Close -> runClose(command.reply)
            is CoreCommand.SelectTrack -> {
                // Applied by its own handler, so one pass never reopens the graph twice.
                pendingTrackChange?.reply?.complete(Unit)
                pendingTrackChange = command
            }
            is CoreCommand.AttachRenderer -> {
                setRenderer(command.renderer)
                command.reply.complete(Unit)
            }
            is CoreCommand.DetachRenderer -> {
                setRenderer(null)
                command.reply.complete(Unit)
            }
            is CoreCommand.SetSpeed -> {
                speed = command.value
                val failure = runCatching { session?.audio?.speed = command.value }.exceptionOrNull()
                if (failure != null) command.reply.completeExceptionally(failure)
                else command.reply.complete(Unit)
            }
            is CoreCommand.SetVolume -> {
                volume = command.value
                session?.audio?.volume = command.value
                command.reply.complete(Unit)
            }
            is CoreCommand.SetMuted -> {
                muted = command.value
                session?.audio?.muted = command.value
                command.reply.complete(Unit)
            }
            is CoreCommand.SetLoop -> {
                loop = command.mode
                command.reply.complete(Unit)
            }
        }
    }

    /**
     * Attaches or detaches, with the fence detach promises.
     *
     * The scheduler is parked before the delegate changes and released afterwards, so when this returns
     * there is no submission to the old renderer outstanding anywhere.
     */
    private suspend fun setRenderer(renderer: VideoRenderer?) {
        val session = this.session
        if (session == null) {
            pendingRenderer = renderer
            return
        }
        val scheduler = session.videoScheduler
        if (scheduler != null) {
            scheduler.quiesce(QUIESCE_DEADLINE)
            session.renderer.delegate = renderer
            scheduler.release(requestedEpoch)
        } else {
            session.renderer.delegate = renderer
        }
        pendingRenderer = renderer
    }

    /** A renderer attached before anything was open, kept for the session that follows. */
    private var pendingRenderer: VideoRenderer? = null

    // ---------------------------------------------------------------------------------------------
    // Open.
    // ---------------------------------------------------------------------------------------------

    private suspend fun runOpen(command: CoreCommand.Open) {
        media = command.media
        // An open ends paused by contract, whatever was asked for before it. A play issued while this one
        // is still running arrives after this line and is honoured, which is what queueing it means.
        playRequested = false
        firstFrameSeen = false
        endOfStream.reset()
        demuxUnderrunSeen = false
        stillImageFinished = false
        stillImageShownSinceNanos = 0
        openedAtNanos = clock.nanos()
        setStatus(PlaybackStatus.Opening)
        try {
            val built = buildSession(command.media, StreamChoice.Auto, StreamChoice.Auto)
            session = built
            startWorkers(built)
            awaitInitialFill(built)
            presentFirstFrame(built)
            setStatus(PlaybackStatus.Paused)
            eventSink.tryEmit(PlayerEvent.Opened(command.media, tracks))
            command.reply.complete(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val error = classify(failure, command.media)
            teardownSession()
            fail(error)
            command.reply.completeExceptionally(PlaybackException(error))
        }
    }

    /**
     * The open sequence, in the order the design fixes.
     *
     * Open the backend session on the demux worker; choose the default tracks; create the decoders and
     * deselect a stream whose every candidate refuses; open the device and negotiate a format; then, and
     * only then, tell the source which streams to read. Opening fails only when nothing playable is left,
     * because a file with a video track this build cannot decode is still a file whose sound plays.
     */
    private suspend fun buildSession(
        item: MediaItem,
        videoChoice: StreamChoice,
        audioChoice: StreamChoice,
    ): OpenSession {
        val backendSession = withContext(dispatchers.demux) { backend.open(item) }
        try {
            val source = backendSession.source
            tracks = source.streams.toTracks()

            val videoCandidate = when (videoChoice) {
                StreamChoice.None -> null
                is StreamChoice.At -> source.streams.firstOrNull { it.index == videoChoice.index }
                StreamChoice.Auto -> source.streams.firstOrNull { it.kind == TrackKind.Video && !it.isCoverArt }
                    // A file whose only picture is its cover art still has a picture worth showing, and the
                    // still-image rule is what keeps it from carrying the timeline.
                    ?: source.streams.firstOrNull { it.kind == TrackKind.Video }
            }
            val audioCandidate = when (audioChoice) {
                StreamChoice.None -> null
                is StreamChoice.At -> source.streams.firstOrNull { it.index == audioChoice.index }
                StreamChoice.Auto -> pickAudio(source.streams)
            }

            var videoStream = videoCandidate
            var audioStream = audioCandidate
            val videoDecoder = videoStream?.let { createVideoDecoder(backendSession, it) }
            if (videoStream != null && videoDecoder == null) {
                warn(
                    PlaybackWarning.TrackDeselected(
                        TrackId(videoStream.index),
                        "no decoder accepted this video stream",
                    ),
                )
                videoStream = null
            }
            val audioDecoder = audioStream?.let { createAudioDecoder(backendSession, it) }
            if (audioStream != null && audioDecoder == null) {
                warn(
                    PlaybackWarning.TrackDeselected(
                        TrackId(audioStream.index),
                        "no decoder accepted this audio stream",
                    ),
                )
                audioStream = null
            }
            if (videoStream == null && audioStream == null) {
                throw PlaybackException(
                    if (source.streams.isEmpty()) {
                        PlaybackError.NotMedia(item.uri, "the container declares no audio or video stream")
                    } else {
                        PlaybackError.NoPlayableStream(tracks.all)
                    },
                )
            }

            val renderer = AttachableRenderer().also { it.delegate = pendingRenderer }
            val videoPlayback = videoStream?.let {
                VideoPlayback(
                    renderer = renderer,
                    clock = clock,
                    containerFrameRate = it.frameRate,
                    timestampsMayJump = source.timestampsMayJump,
                    queueCapacity = config.buffer.videoFrameQueue,
                    dropPolicy = config.frameDrop,
                )
            }

            var sink: AudioSink? = null
            var audioPlayback: AudioPlayback? = null
            var negotiated: AudioFormat? = null
            if (audioStream != null && audioDecoder != null) {
                sink = output.audioSink.create()
                audioPlayback = AudioPlayback(sink, clock, onWarning = { warn(it) })
                negotiated = audioPlayback.open(audioDecoder.outputFormat)
                audioPlayback.volume = volume
                audioPlayback.muted = muted
                eventSink.tryEmit(PlayerEvent.AudioFormatChanged(negotiated.sampleRate, negotiated.channels))
            }

            try {
                withContext(dispatchers.demux) {
                    source.selectStreams(setOfNotNull(videoStream?.index, audioStream?.index))
                }
            } catch (failure: Throwable) {
                // Interlude item I-03. The audio path above is already live: a created sink, an
                // opened AudioPlayback, and since B1.8 that pair owns a C sink, a C ring and an
                // initialised AudioUnit. The catch at the bottom of this function closes only the
                // backend session, and runClose's teardownSession returns immediately because
                // `this.session` is not assigned yet, so a throw from here used to leak all three
                // while `retainedResources()` reported zero. `selectStreams` is the reachable
                // thrower: it ends in a `check`, a `require` and `openPacketReader`.
                runCatching { audioPlayback?.close() }
                throw failure
            }

            tracks = tracks
                .withSelection(TrackKind.Video, videoStream?.let { TrackId(it.index) })
                .withSelection(TrackKind.Audio, audioStream?.let { TrackId(it.index) })
            videoStream?.videoSize?.let { eventSink.tryEmit(PlayerEvent.VideoSizeChanged(it)) }

            val softLimitUs = config.buffer.softTarget.inWholeMicroseconds
            // A queue starts at the initial generation, and a session built after a track change starts at
            // whatever epoch the player has reached. Aligning them here is what stops the demuxer's very
            // first packet from being rejected as stale, which would leave the new session with nothing.
            val videoQueue = videoStream?.let {
                PacketQueue(it.index, softLimitUs).also { queue -> queue.flushTo(requestedEpoch) }
            }
            val audioQueue = audioStream?.let {
                PacketQueue(it.index, softLimitUs).also { queue -> queue.flushTo(requestedEpoch) }
            }
            return OpenSession(
                backendSession = backendSession,
                source = source,
                videoStream = videoStream,
                audioStream = audioStream,
                videoDecoder = if (videoStream == null) null else videoDecoder,
                audioDecoder = if (audioStream == null) null else audioDecoder,
                videoQueue = videoQueue,
                audioQueue = audioQueue,
                video = videoPlayback,
                audio = audioPlayback,
                sink = sink,
                renderer = renderer,
                negotiatedFormat = negotiated,
            )
        } catch (failure: Throwable) {
            // The backend session is the one resource every failure path above owes back from
            // here; the audio path closes itself in the inner catch at its own creation site
            // (interlude item I-03), because this outer catch cannot know whether it was reached
            // before or after the device went live. Corrected at the interlude: the comment that
            // stood here claimed nothing half built survives, while the audio path did.
            runCatching { backendSession.close() }
            throw failure
        }
    }

    private suspend fun createVideoDecoder(session: BackendSession, stream: PlayerStreamInfo): VideoDecoder? {
        if (stream.kind != TrackKind.Video) return null
        for (factory in session.videoDecoders) {
            val decoder = withContext(dispatchers.videoDecode) {
                runCatching { factory.create(stream, config.hardwareDecode) }.getOrNull()
            }
            if (decoder != null) return decoder
            // Named as a hardware problem only when hardware was actually asked for. A factory refusing a
            // stream it cannot decode has nothing to do with hardware, and the caller already learns about
            // that from the TrackDeselected warning and the failed open, each carrying the real reason.
            // Warning here regardless would put a wrong sentence in every bug report about a missing codec.
            val askedForHardware = config.hardwareDecode is HwdecPolicy.Require ||
                config.hardwareDecode is HwdecPolicy.Prefer
            if (askedForHardware) {
                warn(PlaybackWarning.HardwareDecodeUnavailable(stream.codec, "${factory.name} refused the stream"))
            }
        }
        return null
    }

    private suspend fun createAudioDecoder(session: BackendSession, stream: PlayerStreamInfo): AudioDecoder? {
        if (stream.kind != TrackKind.Audio) return null
        for (factory in session.audioDecoders) {
            val decoder = withContext(dispatchers.audioDecode) {
                runCatching { factory.create(stream) }.getOrNull()
            }
            if (decoder != null) return decoder
        }
        return null
    }

    private fun choiceFor(change: CoreCommand.SelectTrack, kind: TrackKind, current: Int?): StreamChoice = when {
        change.kind == kind -> change.track?.let { StreamChoice.At(it.value) } ?: StreamChoice.None
        current != null -> StreamChoice.At(current)
        else -> StreamChoice.None
    }

    /** Language preference first, then the container's default disposition, then the first audio track. */
    private fun pickAudio(streams: List<PlayerStreamInfo>): PlayerStreamInfo? {
        val audio = streams.filter { it.kind == TrackKind.Audio }
        if (audio.isEmpty()) return null
        for (language in config.audio.preferredLanguages) {
            audio.firstOrNull { it.language?.startsWith(language, ignoreCase = true) == true }?.let { return it }
        }
        return audio.firstOrNull { it.isDefault } ?: audio.first()
    }

    /**
     * Fills the queues with the device stopped, until every selected stream is at least Ready.
     *
     * Paused, because a device started with nothing to play underruns at once and clicks. Bounded, and
     * preemptible: a stop or a close arriving during a slow network open is answered rather than waited
     * out.
     */
    private suspend fun awaitInitialFill(session: OpenSession) {
        val deadline = clock.nanos() + OPEN_FILL_DEADLINE.inWholeNanoseconds
        while (clock.nanos() < deadline) {
            if (preempted()) return
            updateStreamStatuses(session)
            if (everySelectedStreamReady(session)) return
            if (session.anyWorkerFinished()) return
            delay(WORKER_POLL)
        }
    }

    /**
     * Presents one frame with the clock stopped, so opening ends on a picture rather than on nothing.
     *
     * The scheduler worker does the presenting, because a renderer is documented to be called from it.
     * The actor asks for exactly one frame and waits for it to go out.
     */
    private suspend fun presentFirstFrame(session: OpenSession) {
        val video = session.video ?: return
        // Against a baseline and not against zero. A seek ends with this too, and by then frames have
        // already gone out for the position the viewer left, so counting from zero would report the old
        // picture as the new one and present nothing at all.
        val before = session.framesOut(video)
        session.schedulerMode.value = SCHEDULER_ONE_FRAME
        val deadline = clock.nanos() + OPEN_FILL_DEADLINE.inWholeNanoseconds
        while (clock.nanos() < deadline) {
            if (session.framesOut(video) > before) break
            if (preempted()) break
            delay(WORKER_POLL)
        }
        session.schedulerMode.value = SCHEDULER_IDLE
    }

    // ---------------------------------------------------------------------------------------------
    // The handlers.
    // ---------------------------------------------------------------------------------------------

    /** Applies a track change by reopening the source at the position playback is at. See digest 8.3. */
    private suspend fun handleTrackChanges() {
        val change = pendingTrackChange ?: return
        pendingTrackChange = null
        val current = session
        val item = media
        if (current == null || item == null) {
            change.reply.complete(Unit)
            return
        }
        val at = currentPosition()
        val wasPlaying = playRequested
        // Null means "none" and not "choose for me": a caller that asks for no audio must get no audio, and
        // the automatic choice is what an open does rather than what a change to one track does.
        val video = choiceFor(change, TrackKind.Video, current.videoStream?.index)
        val audio = choiceFor(change, TrackKind.Audio, current.audioStream?.index)
        try {
            teardownSession()
            requestedEpoch = requestedEpoch.next()
            val rebuilt = buildSession(item, video, audio)
            session = rebuilt
            startWorkers(rebuilt)
            if (at > Pts.Zero) {
                pendingSeek = SeekRequest(SeekTarget.Absolute(at), SeekMode.Precise)
            }
            awaitInitialFill(rebuilt)
            playRequested = wasPlaying
            setStatus(if (wasPlaying) PlaybackStatus.Buffering else PlaybackStatus.Paused)
            change.reply.complete(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val error = classify(failure, item)
            teardownSession()
            fail(error)
            change.reply.completeExceptionally(PlaybackException(error))
        }
    }

    /**
     * Anchors the audio clock once per pass, at one known point.
     *
     * Doing it here rather than wherever the first reader happens to be is what makes the pass's
     * decisions consistent: the position, the drift and the buffering rule all read the same anchoring.
     */
    private fun handleAudioFill() {
        val session = session ?: return
        session.audio?.anchorClock()
        updateStreamStatuses(session)
    }

    /** Keeps the scheduler in the mode the state asks for, and notices the first frame that went out. */
    private fun handleVideoWrite() {
        val session = session ?: return
        val video = session.video ?: return
        if (!firstFrameSeen && session.framesOut(video) > 0) {
            firstFrameSeen = true
            eventSink.tryEmit(
                PlayerEvent.FirstFrameRendered((clock.nanos() - openedAtNanos).nanoseconds),
            )
        }
        if (video.queuedFrames > 0 && session.schedulerMode.value == SCHEDULER_RUNNING) {
            wakeIn(FRAME_WAKE)
        }
    }

    /**
     * The start rendezvous: playback begins only when every selected stream can supply it.
     *
     * Level triggered, so a play() that arrived during an open or a seek is honoured here as soon as the
     * pipeline is ready, and a rebuffer leaves through the same door it came in by.
     */
    private suspend fun handlePlaybackRestart() {
        val session = session ?: return
        if (seekPhase.isRunning) return
        if (status == PlaybackStatus.Ended || status == PlaybackStatus.Failed) return
        if (!playRequested) return
        if (!everySelectedStreamReady(session)) {
            // Deliberately not a place that declares Buffering. A stream that is not ready is one signal,
            // and one signal is never enough: handleBuffering owns that decision and counts it, so there is
            // exactly one way into the state and exactly one rule behind it.
            wakeIn(WORKER_POLL)
            return
        }
        if (status != PlaybackStatus.Playing) {
            session.audio?.play()
            setStatus(PlaybackStatus.Playing)
        }
        session.schedulerMode.value = SCHEDULER_RUNNING
    }

    private suspend fun applyPause() {
        val session = session ?: return
        // Parking the scheduler is what freezes the picture. The frame timer is wall time, so a paused
        // interval leaves it far behind, and the schedule's own resync anchors it to now on the first
        // tick after the release rather than presenting a burst to catch up.
        session.schedulerMode.value = SCHEDULER_IDLE
        // The clock is frozen only after the device is quiet, and the device's final anchor is consumed
        // first, so a late callback cannot re-anchor a clock that is already frozen.
        session.audio?.anchorClock()
        session.audio?.pause()
        if (status == PlaybackStatus.Playing || status == PlaybackStatus.Buffering) {
            setStatus(PlaybackStatus.Paused)
        }
    }

    private fun handlePlaybackTime() {
        val session = session ?: return
        publishedPositionMicros.value = currentPosition().micros
        if (session.isStillImage && session.framesOut(session.video) > 0) {
            if (stillImageShownSinceNanos == 0L) stillImageShownSinceNanos = clock.nanos()
            val shownFor = (clock.nanos() - stillImageShownSinceNanos).nanoseconds
            if (shownFor >= STILL_IMAGE_DURATION) stillImageFinished = true
            else wakeIn(STILL_IMAGE_DURATION - shownFor)
        }
    }

    /**
     * Buffering needs two signals, never one.
     *
     * A momentarily empty queue is normal and says nothing on its own; so does an output that has just
     * been handed its last buffer. The player is buffering when the demuxer has actually run short AND
     * the output is starved right now. The demuxer's signal is sticky until the cache recovers past its
     * soft target, because a cache that refills to one packet and empties again is still not healthy.
     */
    private suspend fun handleBuffering() {
        val session = session ?: return
        if (demuxerRanShort(session)) demuxUnderrunSeen = true else if (wellBuffered(session)) demuxUnderrunSeen = false
        if (!playRequested || status != PlaybackStatus.Playing) return
        if (endOfStream.demuxerEnded) return
        if (!demuxUnderrunSeen || !outputStarved(session)) return
        rebuffers++
        setStatus(PlaybackStatus.Buffering)
        session.schedulerMode.value = SCHEDULER_IDLE
        session.audio?.pause()
        wakeIn(WORKER_POLL)
    }

    /** Cue timing is Horizon B. The handler exists so the pass order does not change when it lands. */
    private fun handleSubtitles() = Unit

    /**
     * End of stream, which is six conditions and not one flag.
     *
     * They are separate because each can be true without the others and each has its own recovery. The
     * demuxer can be done while decoders still hold frames. A decoder can be drained while the device
     * still has half a second of sound to play. The device's own drain can never finish, because the
     * device went away, and that has to complete as failed rather than be polled forever. And the last
     * frame stays on the screen either way, so a finished file looks finished instead of black.
     */
    private suspend fun handleEof() {
        val session = session ?: return
        if (status == PlaybackStatus.Failed || status == PlaybackStatus.Opening) return

        endOfStream.demuxerEnded = session.selectedQueues().all { it.isEndOfStream }
        endOfStream.audioDecoderDrained = session.audioDecoder?.isDrained ?: true
        endOfStream.videoDecoderDrained = session.videoDecoder?.isDrained ?: true

        // Told as soon as the audio side is finished, which is what the sink's own contract asks for and
        // is earlier than the moment every condition below is met: the video frame queue holds frames
        // ahead of the screen and empties last, and the ring runs dry while it drains. The silence in
        // between is the end of the media, not a failure to keep up, and counting it as underruns makes
        // the counter useless for spotting the real thing. Measured on a ten second clip: two underruns
        // reported without this line, none with it. Idempotent, and undone by a flush.
        val audioQueue = session.audioQueue
        if (endOfStream.audioDecoderDrained && audioQueue != null &&
            audioQueue.isEndOfStream && audioQueue.count == 0
        ) {
            session.audio?.endOfStream()
        }

        // A still image runs out of packets on its first frame, which is not a reason to end anything.
        // Album art is meant to be looked at, so the media lasts as long as the image is shown for.
        if (session.isStillImage && !stillImageFinished) {
            wakeIn(WORKER_POLL)
            return
        }

        if (!endOfStream.demuxerEnded) return
        if (!endOfStream.audioDecoderDrained || !endOfStream.videoDecoderDrained) return
        if (session.selectedQueues().any { it.count > 0 }) return
        if (session.video != null && session.video.queuedFrames > 0 && !stillImageFinished) return

        if (!endOfStream.draining) {
            endOfStream.draining = true
            // Said as soon as the decoder is done, not when the ring empties: the silence between those
            // two moments is the end of the media and must not be counted as a failure to keep up.
            session.audio?.endOfStream()
        }

        if (!endOfStream.sinkDrained) {
            val audio = session.audio
            if (audio != null && audio.buffered > Duration.ZERO && !endOfStream.drainFailed) {
                wakeIn(WORKER_POLL)
                return
            }
            if (audio != null) {
                val finished = withTimeoutOrNull(DRAIN_DEADLINE) { audio.drain() } != null
                if (!finished) {
                    endOfStream.drainFailed = true
                    warn(
                        PlaybackWarning.AudioDrainIncomplete(
                            "the device did not report its buffer empty within $DRAIN_DEADLINE, so the " +
                                "drain completed as failed rather than being polled for ever",
                        ),
                    )
                }
            }
            endOfStream.sinkDrained = true
        }

        // The picture stays. The renderer holds its own last image, so nothing here has to keep a frame
        // alive to make that true.
        endOfStream.keepOpen = true

        // Never while paused with a frame on screen: reaching the end of the buffers is not the end of
        // the media for a viewer who asked for a still picture.
        if (!playRequested) return
        if (status == PlaybackStatus.Ended) return
        session.schedulerMode.value = SCHEDULER_IDLE
        // The timeline stops where the media does. A clock left running reads on from wall time, so a
        // player sitting on its last frame would report a position further past the duration the longer
        // it was left there. The final device anchor is consumed first, exactly as a pause does it, so a
        // late callback cannot re-anchor a clock that is already frozen.
        session.audio?.anchorClock()
        session.audio?.pause()
        eventSink.tryEmit(PlayerEvent.Ended)
        setStatus(PlaybackStatus.Ended)
    }

    private fun handleLoop() {
        if (status != PlaybackStatus.Ended) return
        if (loop != LoopMode.One) return
        // One media item repeating is a seek to zero and nothing else, which is why the queue-repeating
        // mode is refused instead of pretended: there is no queue for it to move through.
        endOfStream.reset()
        stillImageFinished = false
        stillImageShownSinceNanos = 0
        playRequested = true
        setStatus(PlaybackStatus.Buffering)
        pendingSeek = SeekRequest(SeekTarget.Absolute(Pts.Zero), SeekMode.Precise)
    }

    /**
     * At most one seek per pass, and the two waiting rules.
     *
     * Inside the coalescing window a new request waits for a frame from the previous seek to have
     * reached the screen. Without that, holding an arrow key freezes the picture completely: every seek
     * is superseded before it can present anything. A precise request additionally waits for the
     * previous restart, because otherwise a seek past the end has its end-of-stream result overwritten
     * and playback never terminates. Both waits are bounded: a rule that can hold for ever is a wedge.
     */
    private suspend fun handleQueuedSeek() {
        val request = pendingSeek ?: run {
            seekHeldSinceNanos = 0
            return
        }
        if (session == null) {
            pendingSeek = null
            resolveSeekReplies(SeekResult.Applied(Pts.Zero))
            return
        }
        val now = clock.nanos()
        val heldFor = if (seekHeldSinceNanos == 0L) Duration.ZERO else (now - seekHeldSinceNanos).nanoseconds
        if (heldFor < COALESCE_WINDOW && shouldHold(request, now)) {
            if (seekHeldSinceNanos == 0L) seekHeldSinceNanos = now
            seekPhase = SeekPhase.Pending
            wakeIn(WORKER_POLL)
            return
        }
        seekHeldSinceNanos = 0
        seekPhase = SeekPhase.Idle
        pendingSeek = null
        try {
            runSeek(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // A source that throws mid-seek leaves the pipeline flushed and the cursor nowhere useful, so
            // the session is torn down rather than left in a position nothing knows.
            val error = PlaybackError.Internal("the seek failed", failure)
            resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
            teardownSession()
            fail(error)
        }
    }

    private fun shouldHold(request: SeekRequest, nowNanos: Long): Boolean {
        val session = session ?: return false
        val sinceLastSeekUs = (nowNanos - lastSeekAtNanos) / 1_000
        if (lastSeekAtNanos != 0L && sinceLastSeekUs < SeekTiming.COALESCE_WINDOW_US) {
            val shown = session.framesOut(session.video)
            if (session.video != null && shown <= framesShownAtLastSeek) return true
        }
        if (request.mode == SeekMode.Precise && status == PlaybackStatus.Buffering && playRequested) {
            return !everySelectedStreamReady(session)
        }
        return false
    }

    private fun queueSeek(request: SeekRequest, reply: CompletableDeferred<SeekResult>?) {
        if (pendingSeek != null) {
            // Everything waiting on the request this one absorbs is answered now, once, as superseded.
            // Leaving those callers to infer it from a position that is no longer theirs is how a
            // scrubbing interface ends up with seeks that never complete.
            val by = requestedEpoch.next()
            resolveSeekReplies(SeekResult.Superseded(by))
        }
        pendingSeek = pendingSeek?.merge(request) ?: request
        reply?.let { pendingSeekReplies += it }
    }

    private fun resolveSeekReplies(result: SeekResult) {
        pendingSeekReplies.forEach { it.complete(result) }
        pendingSeekReplies.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // The seek machine.
    // ---------------------------------------------------------------------------------------------

    /**
     * One seek, quiesce first. This order is the contract, not an implementation detail.
     *
     * 1. Coalesce, bump the requested epoch, publish Buffering.
     * 2. Stop the sink, so the device callback is provably out before anything it reads is touched, then
     *    ask every worker to quiesce and await the acknowledgements.
     * 3. Fence the renderer: with the scheduler parked, nothing can submit for the old epoch.
     * 4. Flush each decoder on its owning worker's dispatcher, with the new generation.
     * 5. Clear the packet queues, the frame queue and the audio ring, now that every consumer is quiet.
     * 6. Seek the source on its owner.
     * 7. Restart the workers under the acknowledged epoch and preroll, discarding frames before the
     *    target for the precise modes and backing off along the overshoot ladder when the first frame
     *    proves the seek landed late.
     * 8. Anchor from the first frame of the new epoch, present it, restore the play state, and complete
     *    every waiting caller exactly once.
     */
    private suspend fun runSeek(request: SeekRequest) {
        val session = session ?: return
        val target = request.resolve(currentPosition(), session.source.duration)

        // 1
        requestedEpoch = requestedEpoch.next()
        val epoch = requestedEpoch
        seekPhase = SeekPhase.Flushing
        if (status != PlaybackStatus.Ended) setStatus(PlaybackStatus.Buffering)
        endOfStream.reset()
        stillImageFinished = false
        stillImageShownSinceNanos = 0

        // 2
        session.schedulerMode.value = SCHEDULER_IDLE
        session.sink?.stop()
        val quiescent = quiesceWorkers(session)
        if (!quiescent) {
            warn(PlaybackWarning.BadTimestamps("a worker did not reach a quiescent boundary within $QUIESCE_DEADLINE"))
        }

        var attempt = 0
        var landed: Pts? = null
        while (true) {
            // 3 is implicit and is the point of step 2: the scheduler is parked, so no frame of the old
            // epoch can reach the renderer from here on.
            val backoff = SeekTiming.OVERSHOOT_BACKOFF_US[attempt]
            val aim = Pts((target.micros - backoff).coerceAtLeast(0L))

            // 4
            flushDecoders(session, epoch)
            // 5
            clearBuffers(session, epoch)
            // 6
            withContext(dispatchers.demux) { session.source.seekToKeyframe(aim) }

            // 7
            seekPhase = if (request.mode == SeekMode.Keyframe) SeekPhase.Filling else SeekPhase.Discarding
            session.discardBeforeUs.value = when (request.mode) {
                SeekMode.Keyframe -> Long.MIN_VALUE
                else -> target.micros - SeekTiming.PRECISE_TOLERANCE_US
            }
            session.firstVideo.clear()
            session.firstDecodedVideo.clear()
            session.firstAudio.clear()
            releaseWorkers(session, epoch)
            landed = awaitLanding(session, epoch)

            // A keyframe seek is documented to land at or before the target, and a container without an
            // index resolves it by byte position and can land after it. The first frame the decoder
            // produced is the evidence, so that is what is judged here.
            val decoded = session.firstDecodedVideo.of(epoch) ?: session.firstAudio.of(epoch)
            val overshot = decoded != null && decoded.micros > target.micros + SeekTiming.PRECISE_TOLERANCE_US
            val laddered = attempt < SeekTiming.OVERSHOOT_BACKOFF_US.lastIndex && aim.micros > 0L
            if (!overshot || !laddered || preempted()) break
            attempt++
            session.schedulerMode.value = SCHEDULER_IDLE
            quiesceWorkers(session)
        }

        // 8
        seekPhase = SeekPhase.Idle
        session.discardBeforeUs.value = Long.MIN_VALUE
        lastSeekAtNanos = clock.nanos()
        framesShownAtLastSeek = session.framesOut(session.video)
        publishedPositionMicros.value = (landed ?: target).micros
        presentFirstFrame(session)
        eventSink.tryEmit(PlayerEvent.SeekCompleted(epoch, (landed ?: target).asDuration))
        resolveSeekReplies(SeekResult.Applied(landed ?: target))
        if (!playRequested && status != PlaybackStatus.Ended) setStatus(PlaybackStatus.Paused)
    }

    private suspend fun quiesceWorkers(session: OpenSession): Boolean {
        var quiescent = true
        for (worker in session.workers) {
            if (!worker.quiesce(QUIESCE_DEADLINE)) quiescent = false
        }
        seekFlushCycles++
        return quiescent
    }

    /**
     * Flushes every decoder on the dispatcher that owns it.
     *
     * A decoding context belongs to one thread. The worker that owns it is parked, so nothing is using
     * it, and the flush runs on that worker's own dispatcher rather than on the actor's, which is what
     * keeps the confinement true.
     */
    private suspend fun flushDecoders(session: OpenSession, epoch: Generation) {
        session.videoDecoder?.let { decoder ->
            withContext(dispatchers.videoDecode) { decoder.flush(epoch) }
        }
        session.audioDecoder?.let { decoder ->
            withContext(dispatchers.audioDecode) { decoder.flush(epoch) }
        }
    }

    private suspend fun clearBuffers(session: OpenSession, epoch: Generation) {
        session.videoQueue?.flushTo(epoch)
        session.audioQueue?.flushTo(epoch)
        while (true) {
            val buffer = session.decodedAudio.tryReceive().getOrNull() ?: break
            buffer.close()
        }
        session.video?.flush(epoch)
        // The scheduler is the only writer of this reading, and it is parked, so clearing it here is the
        // same discipline as flushing a decoder on its owning worker. It has to be cleared: a position
        // measured at the place the viewer just left must not be reported as the place they arrived at.
        session.lastVideoPtsUs.value = NO_POSITION
        // Stops the device again, harmlessly, and then clears the ring: the callback is already out, and
        // the ring's own contract requires exactly that before its counters are written.
        session.audio?.flush(epoch)
    }

    private fun releaseWorkers(session: OpenSession, epoch: Generation) {
        session.workers.forEach { it.release(epoch) }
    }

    /**
     * Waits for the first timestamp of the new epoch, which is where the seek actually landed.
     *
     * libavformat does not report it, so it is discovered from the pipeline: the worker that delivers
     * the first frame or buffer of the epoch records its timestamp. Bounded, and preemptible.
     */
    private suspend fun awaitLanding(session: OpenSession, epoch: Generation): Pts? {
        val startedAt = clock.nanos()
        val deadline = startedAt + SEEK_DEADLINE.inWholeNanoseconds
        val videoGrace = startedAt + LANDING_GRACE.inWholeNanoseconds
        while (clock.nanos() < deadline) {
            val video = session.firstVideo.of(epoch)
            val audio = session.firstAudio.of(epoch)
            if (video != null) return video
            // Video is the better answer when there is video, because it is the only side the precise
            // discard filters, so its first frame is the position that was asked for. Waiting for ever on
            // it is not: a stream whose pictures stopped arriving still has a position, and the sound
            // knows it.
            if (audio != null && (session.videoStream == null || clock.nanos() > videoGrace)) return audio
            if (session.selectedQueues().all { it.isEndOfStream } && session.decodersDrained()) return video ?: audio
            if (preempted()) return video ?: audio
            delay(WORKER_POLL)
        }
        return session.firstVideo.of(epoch) ?: session.firstAudio.of(epoch)
    }

    // ---------------------------------------------------------------------------------------------
    // Stop, close and failure.
    // ---------------------------------------------------------------------------------------------

    private suspend fun runStop() {
        playRequested = false
        pendingSeek = null
        pendingTrackChange?.reply?.complete(Unit)
        pendingTrackChange = null
        resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
        teardownSession()
        media = null
        tracks = Tracks.Empty
        endOfStream.reset()
        seekPhase = SeekPhase.Idle
        setStatus(PlaybackStatus.Idle)
        publishSnapshot()
    }

    private suspend fun runClose(reply: CompletableDeferred<Unit>) {
        if (closed) {
            reply.complete(Unit)
            return
        }
        closed = true
        closedNow.value = true
        playRequested = false
        pendingSeek = null
        val finished = withTimeoutOrNull(CLOSE_DEADLINE) {
            teardownSession()
        } != null
        pendingTrackChange?.reply?.complete(Unit)
        pendingTrackChange = null
        resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
        // Every command that never ran is answered, so no caller is left suspended for ever.
        commands.close()
        while (true) {
            val pending = heldCommands.removeFirstOrNull() ?: commands.tryReceive().getOrNull() ?: break
            if (pending is CoreCommand.Close) pending.reply.complete(Unit)
            else pending.fail(IllegalStateException("the player was closed before ${pending.name} could run"))
        }
        setStatus(PlaybackStatus.Idle)
        if (!finished) {
            val error = PlaybackError.RuntimeCompromised(
                "teardown did not finish within $CLOSE_DEADLINE, so a worker may still hold resources",
            )
            lastError = error
            eventSink.tryEmit(PlayerEvent.Failed(error))
            reply.completeExceptionally(PlaybackException(error))
        } else {
            reply.complete(Unit)
        }
        publishSnapshot()
        terminated = true
        if (closeDispatchers) dispatchers.close()
    }

    /**
     * Tears the session down in the reverse of the order it was built in.
     *
     * Workers first, so nothing is using a decoder when it closes, and each decoder on the dispatcher
     * that owned it. Everything is attempted even when something throws, because one backend refusing to
     * close is not a reason to leak the rest.
     */
    private suspend fun teardownSession() {
        val session = session ?: return
        this.session = null
        session.schedulerMode.value = SCHEDULER_IDLE
        runCatching { session.sink?.stop() }
        session.workers.forEach { it.quiesce(QUIESCE_DEADLINE) }
        // The join is the ONLY thing standing between a cancelled feeder and a freed C ring, so it
        // runs under NonCancellable (interlude item I-02). Without it, the close budget arithmetic
        // makes the joins vanish exactly when they matter: five quiesce deadlines can consume the
        // whole CLOSE_DEADLINE, the withTimeoutOrNull above then cancels this block, and a plain
        // `runCatching { it.join() }` in a cancelled coroutine catches the join's own
        // CancellationException and returns WITHOUT waiting, so `session.audio?.close()` below,
        // which never suspends, frees the C ring while a cancelled feeder can still be executing a
        // whole buffer of ring writes on its own thread. That producer-side call on a freed ring
        // was measured as a heap-use-after-free under AddressSanitizer at the interlude review,
        // not argued.
        withContext(NonCancellable) {
            session.jobs.forEach { it.cancel() }
            session.jobs.forEach { runCatching { it.join() } }
        }
        session.videoDecoder?.let { decoder ->
            runCatching { withContext(dispatchers.videoDecode) { decoder.close() } }
        }
        session.audioDecoder?.let { decoder ->
            runCatching { withContext(dispatchers.audioDecode) { decoder.close() } }
        }
        while (true) {
            val buffer = session.decodedAudio.tryReceive().getOrNull() ?: break
            buffer.close()
        }
        session.videoQueue?.close()
        session.audioQueue?.close()
        runCatching { session.video?.close() }
        // Closes the sink too: the audio path owns the device it was given.
        runCatching { session.audio?.close() }
        runCatching { session.backendSession.close() }
    }

    private fun fail(error: PlaybackError) {
        lastError = error
        eventSink.tryEmit(PlayerEvent.Failed(error))
        setStatus(PlaybackStatus.Failed)
    }

    private fun classify(failure: Throwable, item: MediaItem): PlaybackError = when (failure) {
        is PlaybackException -> failure.error
        else -> PlaybackError.SourceUnavailable(item.uri, failure, failure.message)
    }

    private suspend fun handleWorkerOutcome(outcome: WorkerOutcome) {
        val cause = outcome.cause ?: return
        if (cause is CancellationException) return
        val session = session
        val error = when {
            outcome.name == DEMUX_WORKER -> PlaybackError.SourceUnavailable(
                media?.uri ?: "", cause, "the demuxer failed: ${cause.message}",
            )
            outcome.name == VIDEO_DECODE_WORKER -> PlaybackError.DecoderFailed(
                session?.videoStream?.codec ?: "video", cause.message ?: cause.toString(), cause,
            )
            outcome.name == AUDIO_DECODE_WORKER -> PlaybackError.DecoderFailed(
                session?.audioStream?.codec ?: "audio", cause.message ?: cause.toString(), cause,
            )
            else -> PlaybackError.Internal("the ${outcome.name} worker failed", cause)
        }
        // A dead worker is a handled failure and never a hang, which is why every worker reports here.
        teardownSession()
        fail(error)
        resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
    }

    // ---------------------------------------------------------------------------------------------
    // Publication.
    // ---------------------------------------------------------------------------------------------

    private fun setStatus(next: PlaybackStatus) {
        if (status == next) return
        if (!StatusMachine.isLegal(status, next)) illegalTransitions += "$status to $next"
        status = next
        statusHistory += next
        // A new attempt replaces the old failure rather than leaving two truths on the snapshot.
        if (next == PlaybackStatus.Opening) lastError = null
        // Published here and not only at the end of the pass. A handler that then spends a second inside
        // a slow backend would otherwise leave every observer reading the status the player has left.
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val session = session
        val now = clock.nanos()
        snapshotState.value = PlayerSnapshot(
            status = status,
            media = media,
            duration = session?.source?.duration?.asDuration,
            seekable = session?.source?.seekable ?: false,
            videoSize = session?.videoStream?.videoSize,
            tracks = tracks,
            chapters = session?.source?.chapters ?: emptyList(),
            speed = speed,
            volume = volume,
            muted = muted,
            loop = loop,
            error = lastError,
            generation = requestedEpoch,
        )
        if ((now - lastProgressAtNanos).nanoseconds >= config.progressInterval) {
            lastProgressAtNanos = now
            progressState.value = Progress(
                position = publishedPositionMicros.value.microseconds,
                bufferedAhead = bufferedAhead(session),
            )
        }
        if ((now - lastStatsAtNanos).nanoseconds >= config.statsInterval) {
            val elapsed = (now - lastStatsAtNanos).nanoseconds
            lastStatsAtNanos = now
            val decoded = session?.decodedVideoFrames?.value ?: 0
            val fps = if (elapsed > Duration.ZERO) {
                (decoded - lastStatsDecoded) * 1_000.0 / elapsed.inWholeMilliseconds.coerceAtLeast(1)
            } else {
                0.0
            }
            lastStatsDecoded = decoded
            statsState.value = PlaybackStats(
                decodedVideoFrames = decoded,
                submittedFrames = session?.renderer?.submittedFrames ?: 0,
                headlessFrames = session?.renderer?.headlessFrames ?: 0,
                droppedFramesLate = session?.video?.droppedFrames ?: 0,
                repeatedFrames = session?.video?.repeatedFrames ?: 0,
                audioUnderruns = session?.audio?.underruns ?: 0,
                rebuffers = rebuffers,
                avDrift = (session?.driftUs?.value ?: 0L).microseconds,
                videoDecodeFps = fps,
                videoQueueDepth = session?.video?.buffered ?: Duration.ZERO,
                audioQueueDepth = session?.audio?.buffered ?: Duration.ZERO,
                audioLatencyQuality = session?.audio?.latencyQuality ?: LatencyQuality.Unreliable,
                syncMode = config.syncMode,
                masterClock = masterClockKind(session),
            )
        }
    }

    private fun bufferedAhead(session: OpenSession?): Duration {
        if (session == null) return Duration.ZERO
        val queues = session.selectedQueues()
        if (queues.isEmpty()) return Duration.ZERO
        return queues.minOf { it.bufferedUs }.microseconds
    }

    private fun masterClockKind(session: OpenSession?): MasterClock = when {
        session == null -> MasterClock.None
        session.audio != null && config.syncMode != SyncMode.VideoMaster -> MasterClock.Audio
        session.video != null -> MasterClock.Video
        else -> MasterClock.None
    }

    private fun warn(warning: PlaybackWarning) {
        eventSink.tryEmit(PlayerEvent.Warning(warning))
    }

    // ---------------------------------------------------------------------------------------------
    // State reads shared by the handlers.
    // ---------------------------------------------------------------------------------------------

    private fun currentPosition(): Pts {
        val session = session ?: return Pts(publishedPositionMicros.value)
        session.audio?.position()?.let { return it }
        val video = session.lastVideoPtsUs.value
        if (video != NO_POSITION) return Pts(video)
        return Pts(publishedPositionMicros.value)
    }

    private fun everySelectedStreamReady(session: OpenSession): Boolean {
        val readyUs = config.buffer.readyDuration.inWholeMicroseconds
        return session.selectedQueues().all { it.isReady(readyUs, config.buffer.readyPackets) }
    }

    private fun demuxerRanShort(session: OpenSession): Boolean =
        session.selectedQueues().any { it.count == 0 && !it.isEndOfStream }

    private fun wellBuffered(session: OpenSession): Boolean =
        session.selectedQueues().all { it.isWellBuffered }

    private fun outputStarved(session: OpenSession): Boolean = when {
        session.audio != null -> session.audio.buffered <= Duration.ZERO
        session.video != null -> session.video.queuedFrames == 0
        else -> false
    }

    private fun updateStreamStatuses(session: OpenSession) {
        val readyUs = config.buffer.readyDuration.inWholeMicroseconds
        session.videoQueue?.let { queue ->
            session.videoStatus = streamStatusOf(
                queue.isReady(readyUs, config.buffer.readyPackets),
                session.videoDecoder?.isDrained == true && queue.count == 0,
            )
        }
        session.audioQueue?.let { queue ->
            session.audioStatus = streamStatusOf(
                queue.isReady(readyUs, config.buffer.readyPackets),
                session.audioDecoder?.isDrained == true && queue.count == 0,
            )
        }
    }

    private fun streamStatusOf(ready: Boolean, drained: Boolean): StreamStatus = when {
        drained -> if (endOfStream.sinkDrained) StreamStatus.Eof else StreamStatus.Draining
        !ready -> StreamStatus.Syncing
        status == PlaybackStatus.Playing -> StreamStatus.Playing
        else -> StreamStatus.Ready
    }

    // ---------------------------------------------------------------------------------------------
    // The workers.
    // ---------------------------------------------------------------------------------------------

    private fun startWorkers(session: OpenSession) {
        val epoch = requestedEpoch
        if (session.videoQueue != null && session.videoDecoder != null && session.video != null) {
            session.videoDecodeWorker = Worker(VIDEO_DECODE_WORKER)
            session.videoScheduler = Worker(VIDEO_SCHEDULE_WORKER)
        }
        if (session.audioQueue != null && session.audioDecoder != null && session.audio != null) {
            session.audioDecodeWorker = Worker(AUDIO_DECODE_WORKER)
            session.audioFeedWorker = Worker(AUDIO_FEED_WORKER)
        }
        session.demuxWorker = Worker(DEMUX_WORKER)
        session.workers.forEach { it.release(epoch) }

        session.demuxWorker?.let { worker ->
            session.jobs += launchWorker(worker, dispatchers.demux) { runDemux(session, worker) }
        }
        session.videoDecodeWorker?.let { worker ->
            session.jobs += launchWorker(worker, dispatchers.videoDecode) { runVideoDecode(session, worker) }
        }
        session.audioDecodeWorker?.let { worker ->
            session.jobs += launchWorker(worker, dispatchers.audioDecode) { runAudioDecode(session, worker) }
        }
        session.audioFeedWorker?.let { worker ->
            session.jobs += launchWorker(worker, dispatchers.audioFeed) { runAudioFeed(session, worker) }
        }
        session.videoScheduler?.let { worker ->
            session.jobs += launchWorker(worker, dispatchers.videoSchedule) { runVideoSchedule(session, worker) }
        }
    }

    /**
     * Launches one worker so that every way it can end arrives on one channel.
     *
     * A crash becomes a message the actor handles rather than a coroutine that vanishes, which is the
     * difference between a typed failure and a player that hangs with no explanation.
     */
    private fun launchWorker(worker: Worker, context: CoroutineContext, body: suspend () -> Unit): Job =
        scope.launch(context) {
            try {
                body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                outcomes.trySend(WorkerOutcome(worker.name, failure))
            } finally {
                worker.markFinished()
            }
        }

    /** Reads packets and hands them to the per-stream queues, stalling when the total is over budget. */
    private suspend fun runDemux(session: OpenSession, worker: Worker) {
        var epoch = worker.epoch
        var restarts = worker.releases
        var ended = false
        while (true) {
            worker.checkpoint()
            // Every restart voids what this loop remembers, and a restart is not the same thing as an
            // epoch change: one seek can flush and restart the pipeline several times under one epoch.
            if (worker.releases != restarts) {
                restarts = worker.releases
                epoch = worker.epoch
                ended = false
            }
            if (ended) {
                delay(WORKER_POLL)
                continue
            }
            if (overBudget(session)) {
                // Waiting for room is the normal answer. It is the wrong answer when the reason there is no
                // room is that one stream has read far ahead of another, because then the starved stream's
                // decoder is what everything is waiting for and more reading is the only way to reach it.
                if (!relieveInterleaving(session)) {
                    // Woken by whichever consumer takes something, rather than by a timer, so read-ahead
                    // resumes the moment there is room. Nothing is taken here, so the bounded wait can lose
                    // at most a wake-up.
                    withTimeoutOrNull(WORKER_POLL) { session.selectedQueues().first().awaitDrain() }
                }
                continue
            }
            val packet = session.source.readPacket()
            if (packet == null) {
                session.videoQueue?.signalEndOfStream(epoch)
                session.audioQueue?.signalEndOfStream(epoch)
                ended = true
                continue
            }
            when (packet.streamIndex) {
                session.videoStream?.index -> session.videoQueue?.offer(packet, epoch) ?: packet.close()
                session.audioStream?.index -> session.audioQueue?.offer(packet, epoch) ?: packet.close()
                else -> packet.close()
            }
        }
    }

    /**
     * The read-ahead bound, across every stream at once.
     *
     * A per-stream limit that stalls the producer deadlocks on a badly interleaved file, so the decision
     * belongs where every stream is visible.
     */
    private fun overBudget(session: OpenSession): Boolean {
        val queues = session.selectedQueues()
        if (queues.isEmpty()) return false
        val bytes = queues.sumOf { it.bytesBuffered }
        val longest = queues.maxOf { it.bufferedUs }
        return bytes >= config.buffer.totalBytes || longest >= config.buffer.totalDuration.inWholeMicroseconds
    }

    /**
     * Truncates a stream that has read far ahead of a starved one, and says so.
     *
     * This is the interleaving deadlock every player meets once. The read-ahead budget is reached by one
     * stream while another has nothing; that other stream's decoder starves, so its clock stops, so nothing
     * is consumed, so the budget is never freed. A gap in one stream beats a player that has stopped, so the
     * newest end of the hoarding queue is dropped to make room. Legal only at the tail of a run that has not
     * been decoded yet, which is exactly where this cuts.
     *
     * Called from the demux worker. Everything it touches is either immutable or guarded by the queue's own
     * lock, and the warning goes through a flow whose emission is thread safe.
     *
     * @return true when something was dropped, which means reading can continue at once.
     */
    private fun relieveInterleaving(session: OpenSession): Boolean {
        val queues = session.selectedQueues()
        if (queues.size < 2) return false
        val starved = queues.firstOrNull { it.count == 0 && !it.isEndOfStream } ?: return false
        val hoarding = queues.maxByOrNull { it.bytesBuffered } ?: return false
        if (hoarding === starved || hoarding.count == 0) return false

        // Half of what it holds, measured in its own bytes rather than against the byte cap, because the cap
        // that was reached may have been the duration one and a byte target would then drop nothing at all.
        val dropped = hoarding.dropFromTail(hoarding.bytesBuffered / 2)
        if (dropped == 0) return false
        // Once per session. The condition persists for as long as the file is badly interleaved, and a
        // warning per drop would bury everything else a caller is listening for.
        if (!session.warnedAboutInterleaving) {
            session.warnedAboutInterleaving = true
            warn(PlaybackWarning.PathologicalInterleaving(TrackId(starved.streamIndex), dropped))
        }
        return true
    }

    private suspend fun runVideoDecode(session: OpenSession, worker: Worker) {
        val queue = session.videoQueue ?: return
        val decoder = session.videoDecoder ?: return
        val video = session.video ?: return
        var epoch = worker.epoch
        var restarts = worker.releases
        var ending = false
        while (true) {
            worker.checkpoint()
            if (worker.releases != restarts) {
                restarts = worker.releases
                epoch = worker.epoch
                // The flush cleared the decoder, so its drain signal has to be sent again.
                ending = false
            }
            if (drainFrames(session, worker, decoder, video, epoch)) continue
            if (queue.isEndOfStream && queue.count == 0) {
                if (!ending) {
                    // The drain signal travels in band, as a null packet, exactly as libavcodec expects.
                    decoder.send(null)
                    ending = true
                    continue
                }
                delay(WORKER_POLL)
                continue
            }
            val packet = queue.poll()
            if (packet == null) {
                // Nothing taken, so nothing can be lost: the wait is bounded and the poll above is what
                // actually takes a packet.
                withTimeoutOrNull(WORKER_POLL) { queue.awaitData() }
                continue
            }
            try {
                while (!decoder.send(packet)) {
                    // False means the decoder is full and did NOT take the packet, so it is offered
                    // again after draining rather than discarded. A decoder that accepts nothing and
                    // produces nothing has no legal state to be in.
                    val frame = decoder.receive() ?: error(
                        "decoder refused a packet and produced nothing; this violates the codec contract",
                    )
                    if (!handOver(session, worker, video, frame, epoch)) break
                }
            } finally {
                packet.close()
            }
        }
    }

    /** Pulls whatever the decoder has ready. True when something came out. */
    private suspend fun drainFrames(
        session: OpenSession,
        worker: Worker,
        decoder: VideoDecoder,
        video: VideoPlayback,
        epoch: Generation,
    ): Boolean {
        val frame = decoder.receive() ?: return false
        session.decodedVideoFrames.incrementAndGet()
        handOver(session, worker, video, frame, epoch)
        return true
    }

    /**
     * Hands a frame to the schedule, giving it up when the actor asks for quiescence.
     *
     * The queue is deliberately small and the handover suspends while it is full, which is the
     * backpressure that stops the decoder from running ahead of the display and from holding a hardware
     * pool's every buffer. Waiting for ever is not an option though: a seek has to be able to park this
     * worker, so the wait is retried in bounded steps and abandoned when quiescence is asked for. A
     * frame abandoned that way belongs to an epoch that is about to be flushed anyway, and this function
     * is its only owner, so it closes it.
     */
    private suspend fun handOver(
        session: OpenSession,
        worker: Worker,
        video: VideoPlayback,
        frame: VideoFrame,
        epoch: Generation,
    ): Boolean {
        if (frame.generation != epoch) {
            frame.close()
            return true
        }
        // Recorded before the discard, because this is where the seek actually landed and that is what
        // the overshoot ladder has to judge. A precise seek that landed correctly still throws away up to
        // a whole group of pictures, so judging the landing by the first frame that survived the discard
        // would call every correct precise seek an overshoot.
        session.firstDecodedVideo.record(epoch, frame.pts)
        if (frame.pts.micros < session.discardBeforeUs.value) {
            // Precise seeking decodes forward from the keyframe and throws away what is before the
            // target.
            frame.close()
            return true
        }
        session.firstVideo.record(epoch, frame.pts)
        while (true) {
            // Nothing here can be cancelled halfway: the offer either takes the frame or leaves it, and the
            // wait between attempts holds nothing. That is what makes giving up safe, and giving up is what
            // lets this worker park for a seek instead of sitting inside a full queue.
            if (video.trySubmit(frame)) return true
            if (worker.quiesceRequested) {
                frame.close()
                return false
            }
            delay(HANDOVER_RETRY)
        }
    }

    private suspend fun runAudioDecode(session: OpenSession, worker: Worker) {
        val queue = session.audioQueue ?: return
        val decoder = session.audioDecoder ?: return
        var epoch = worker.epoch
        var restarts = worker.releases
        var ending = false
        while (true) {
            worker.checkpoint()
            if (worker.releases != restarts) {
                restarts = worker.releases
                epoch = worker.epoch
                ending = false
            }
            if (passBuffer(session, worker, decoder, epoch)) continue
            if (queue.isEndOfStream && queue.count == 0) {
                if (!ending) {
                    decoder.send(null)
                    ending = true
                    continue
                }
                delay(WORKER_POLL)
                continue
            }
            val packet = queue.poll()
            if (packet == null) {
                withTimeoutOrNull(WORKER_POLL) { queue.awaitData() }
                continue
            }
            try {
                while (!decoder.send(packet)) {
                    val buffer = decoder.receive() ?: error(
                        "decoder refused a packet and produced nothing; this violates the codec contract",
                    )
                    if (!offerBuffer(session, worker, buffer, epoch)) break
                }
            } finally {
                packet.close()
            }
        }
    }

    private suspend fun passBuffer(
        session: OpenSession,
        worker: Worker,
        decoder: AudioDecoder,
        epoch: Generation,
    ): Boolean {
        val buffer = decoder.receive() ?: return false
        offerBuffer(session, worker, buffer, epoch)
        return true
    }

    private suspend fun offerBuffer(
        session: OpenSession,
        worker: Worker,
        buffer: AudioBuffer,
        epoch: Generation,
    ): Boolean {
        if (buffer.generation != epoch) {
            buffer.close()
            return true
        }
        // Audio is trimmed to the target too, and for the same reason video is. A precise seek that
        // starts its sound at the keyframe plays up to a whole group of pictures of audio from before the
        // position that was asked for, which is the one part of a seek a listener hears immediately.
        // Whole buffers only: the one that straddles the target is kept, which is at most one buffer of
        // imprecision against a sample-exact trim that needs a filter this build does not have.
        val bufferEndUs = buffer.pts.micros + buffer.format.durationOf(buffer.frameCount).micros
        if (bufferEndUs <= session.discardBeforeUs.value) {
            buffer.close()
            return true
        }
        while (true) {
            if (worker.quiesceRequested) {
                buffer.close()
                return false
            }
            // A select rather than a cancelled send, for the same reason the actor uses one: a send
            // cancelled at the wrong instant can leave a buffer neither queued nor owned by anyone, which
            // is exactly the leak the ledger exists to catch.
            val sent = select<Boolean> {
                session.decodedAudio.onSend(buffer) { true }
                onTimeout(WORKER_POLL) { false }
            }
            if (sent) return true
        }
    }

    /**
     * Turns decoded buffers into what the device took, and hands them to the ring.
     *
     * The ring's single producer is this worker, which is why the conversion stage lives on it too. The
     * handover is bounded for the same reason the video one is: while the device is paused the ring
     * stays full, and a feeder that waited for ever inside it could never be parked for a seek. What is
     * abandoned is audio from an epoch the seek is about to flush.
     */
    private suspend fun runAudioFeed(session: OpenSession, worker: Worker) {
        val audio = session.audio ?: return
        val interleaver = Interleaver()
        var epoch = worker.epoch
        var restarts = worker.releases
        while (true) {
            worker.checkpoint()
            if (worker.releases != restarts) {
                restarts = worker.releases
                epoch = worker.epoch
            }
            val buffer = select<AudioBuffer?> {
                session.decodedAudio.onReceive { it }
                onTimeout(WORKER_POLL) { null }
            } ?: continue
            try {
                if (buffer.generation != epoch) continue
                session.firstAudio.record(epoch, buffer.pts)
                val interleaved = interleaver.interleave(buffer)
                while (true) {
                    if (worker.quiesceRequested) break
                    val done = withTimeoutOrNull(HANDOVER_DEADLINE) {
                        audio.submitDecoded(buffer.pts, interleaved, buffer.frameCount, buffer.format)
                    }
                    if (done != null) break
                }
            } finally {
                buffer.close()
            }
        }
    }

    /**
     * The presentation loop.
     *
     * It runs in one of three modes because the schedule is the picture: running is playback, one frame
     * is what an open and a seek end with, and idle is a pause. A paused schedule is a parked loop and
     * not a frame timer that keeps advancing, so nothing accumulates while the viewer waits.
     */
    private suspend fun runVideoSchedule(session: OpenSession, worker: Worker) {
        val video = session.video ?: return
        while (true) {
            worker.checkpoint()
            when (session.schedulerMode.value) {
                SCHEDULER_RUNNING -> {
                    // The pause and resume arithmetic of the design, applied here and not by the actor,
                    // because the video clock has one owner and this loop is it. Both calls are
                    // idempotent, so they cost a boolean read on every pass and act on the pass where the
                    // mode changed. Without the resume the interval the player spent paused counts as
                    // time already spent on the frame on screen, and every frame behind it is late the
                    // instant playback resumes: measured, one frame dropped and one repeated at the start
                    // of every file, because an open ends paused on its first frame.
                    video.resumeSchedule()
                    val wait = video.tick(masterPosition(session))
                    recordVideoClock(session, video)
                    if (wait > Duration.ZERO) delay(minOf(wait, WORKER_POLL).atLeastOneTick())
                }
                SCHEDULER_ONE_FRAME -> {
                    video.resumeSchedule()
                    val before = session.framesOut(video)
                    val wait = video.tick(masterPosition(session))
                    recordVideoClock(session, video)
                    if (session.framesOut(video) > before) {
                        session.schedulerMode.compareAndSet(SCHEDULER_ONE_FRAME, SCHEDULER_IDLE)
                    } else if (wait > Duration.ZERO) {
                        delay(minOf(wait, WORKER_POLL).atLeastOneTick())
                    }
                }
                else -> {
                    video.pauseSchedule()
                    delay(WORKER_POLL)
                }
            }
        }
    }

    /**
     * What the master clock reads, from the worker that needs it.
     *
     * Audio drives when there is audio, because the ear notices a discontinuity in sound immediately and
     * the eye rarely notices a duplicated frame. With no audio the schedule paces itself from its own
     * timestamps, which is what passing null means.
     */
    private fun masterPosition(session: OpenSession): Pts? = when {
        config.syncMode == SyncMode.VideoMaster -> null
        else -> session.audio?.position()
    }

    /** Publishes what the scheduler alone may read, so the actor never touches the video clock. */
    private fun recordVideoClock(session: OpenSession, video: VideoPlayback) {
        session.lastVideoPtsUs.value = video.position()?.micros ?: NO_POSITION
        session.driftUs.value = video.drift.inWholeMicroseconds
    }

    // ---------------------------------------------------------------------------------------------
    // The session's own state.
    // ---------------------------------------------------------------------------------------------

    /**
     * Everything one opened media item owns.
     *
     * Held as one object so teardown is one place, and so the workers get a single reference rather than
     * a handful of fields that could be swapped underneath them.
     */
    private class OpenSession(
        val backendSession: BackendSession,
        val source: PlayerMediaSource,
        val videoStream: PlayerStreamInfo?,
        val audioStream: PlayerStreamInfo?,
        val videoDecoder: VideoDecoder?,
        val audioDecoder: AudioDecoder?,
        val videoQueue: PacketQueue?,
        val audioQueue: PacketQueue?,
        val video: VideoPlayback?,
        val audio: AudioPlayback?,
        val sink: AudioSink?,
        val renderer: AttachableRenderer,
        val negotiatedFormat: AudioFormat?,
    ) {
        /** Between the audio decoder and the feeder. Small, because the ring is the real buffer. */
        val decodedAudio: Channel<AudioBuffer> = Channel(capacity = 4)

        val decodedVideoFrames = atomic(0L)
        val lastVideoPtsUs = atomic(NO_POSITION)
        val driftUs = atomic(0L)
        val discardBeforeUs = atomic(Long.MIN_VALUE)
        val schedulerMode = atomic(SCHEDULER_IDLE)
        val firstVideo = FirstTimestamp()

        /** Before the precise discard, so the overshoot ladder judges the landing and not the filter. */
        val firstDecodedVideo = FirstTimestamp()
        val firstAudio = FirstTimestamp()

        var demuxWorker: Worker? = null
        var videoDecodeWorker: Worker? = null
        var audioDecodeWorker: Worker? = null
        var audioFeedWorker: Worker? = null
        var videoScheduler: Worker? = null
        val jobs: MutableList<Job> = mutableListOf()

        /** Said once: the condition lasts as long as the file does. */
        var warnedAboutInterleaving: Boolean = false

        var videoStatus: StreamStatus = StreamStatus.Syncing
        var audioStatus: StreamStatus = StreamStatus.Syncing

        val workers: List<Worker>
            get() = listOfNotNull(demuxWorker, videoDecodeWorker, audioDecodeWorker, audioFeedWorker, videoScheduler)

        fun selectedQueues(): List<PacketQueue> = listOfNotNull(videoQueue, audioQueue)

        fun decodersDrained(): Boolean =
            (videoDecoder?.isDrained ?: true) && (audioDecoder?.isDrained ?: true)

        fun anyWorkerFinished(): Boolean = workers.any { it.isFinished }

        /** Frames that left the schedule one way or another, which is how a presentation is noticed. */
        fun framesOut(video: VideoPlayback?): Long {
            if (video == null) return 0
            return video.submittedFrames + video.headlessFrames
        }

        val isStillImage: Boolean
            get() = videoStream != null && audioStream == null && (videoStream.isCoverArt || videoStream.isSparse)
    }

    private companion object {
        /**
         * The longest the loop sleeps when nothing asks for earlier.
         *
         * Level triggering needs a floor: a condition that becomes true without anyone signalling, a
         * device that quietly recovered, a queue that filled from the other side, is noticed on the next
         * pass, and this is how long that can take. Short enough that nothing user visible waits on it,
         * long enough that an idle player is not a busy loop.
         */
        val WAKE_FLOOR: Duration = 50.milliseconds

        /** How long a still image or a piece of cover art stays on screen before it counts as finished. */
        val STILL_IMAGE_DURATION: Duration = 5.seconds

        /** The ceiling on any wait inside a worker, which is what makes quiescence bounded. */
        val WORKER_POLL: Duration = 50.milliseconds

        /** How long the actor waits for one worker to reach a boundary. */
        val QUIESCE_DEADLINE: Duration = 2.seconds

        /** How long a handover to a full ring is allowed to take before it is retried. */
        val HANDOVER_DEADLINE: Duration = 250.milliseconds

        /**
         * How long a full frame queue is left alone between attempts.
         *
         * Far below a frame period, so a slot that frees is used almost at once, and long enough that a
         * paused pipeline is not a busy loop on the decoder's thread.
         */
        val HANDOVER_RETRY: Duration = 5.milliseconds

        /** How long an open may spend filling before it gives up waiting and reports what it has. */
        val OPEN_FILL_DEADLINE: Duration = 10.seconds

        /** How long a seek may spend finding its landing. */
        val SEEK_DEADLINE: Duration = 10.seconds

        /** How long a seek prefers the video side's answer before it accepts the audio side's. */
        val LANDING_GRACE: Duration = 500.milliseconds

        /** How long the device is given to play out what it holds. */
        val DRAIN_DEADLINE: Duration = 5.seconds

        /** How long teardown may take before close reports a compromised runtime. */
        val CLOSE_DEADLINE: Duration = 10.seconds

        /** Wake soon enough that a frame due in the next period is not missed. */
        val FRAME_WAKE: Duration = 5.milliseconds

        val COALESCE_WINDOW: Duration = SeekTiming.COALESCE_WINDOW_US.microseconds

        const val NO_POSITION: Long = Long.MIN_VALUE

        const val SCHEDULER_IDLE = 0
        const val SCHEDULER_ONE_FRAME = 1
        const val SCHEDULER_RUNNING = 2

        const val DEMUX_WORKER = "demux"
        const val VIDEO_DECODE_WORKER = "video decode"
        const val AUDIO_DECODE_WORKER = "audio decode"
        const val AUDIO_FEED_WORKER = "audio feed"
        const val VIDEO_SCHEDULE_WORKER = "video schedule"
    }
}

/**
 * Rounds a wait up to something a dispatcher can actually wait for.
 *
 * A delay shorter than the scheduler's own resolution is not a shorter wait, it is a busy loop: the
 * call returns immediately, nothing has moved on, and the same too-short wait is computed again. One
 * millisecond is the resolution every dispatcher this engine runs on has, so that is the floor. The cost
 * is at most a millisecond of scheduling precision, which every rule here re-measures on its next pass
 * anyway.
 */
private fun Duration.atLeastOneTick(): Duration = if (this < DISPATCHER_TICK) DISPATCHER_TICK else this

private val DISPATCHER_TICK: Duration = 1.milliseconds

/**
 * Which stream of a kind a session should use.
 *
 * Three cases and not two, because a null track id from a caller means "none" and the absence of a request
 * means "choose the default". Collapsing those two into one null is how a player ends up unable to turn its
 * own audio off.
 */
internal sealed interface StreamChoice {
    /** Pick the default: the first non-cover-art video, and audio by language then disposition. */
    data object Auto : StreamChoice

    /** Use no stream of this kind. */
    data object None : StreamChoice

    data class At(val index: Int) : StreamChoice
}

/** Per stream, so the start rendezvous and the end of stream are decided per stream and not globally. */
internal enum class StreamStatus { Syncing, Ready, Playing, Draining, Eof }

/**
 * The six conditions end of stream is made of.
 *
 * Named separately because each is separately true, separately observable, and separately wrong when a
 * player gets it wrong: a file that stops a second early, one that never finishes, one that reports
 * underruns as it ends, or one that goes black on the last frame.
 */
internal class EndOfStreamState {
    /** The demuxer reached the end of the container. */
    var demuxerEnded: Boolean = false

    /** The audio decoder has reported the end of its stream. */
    var audioDecoderDrained: Boolean = false

    /** The video decoder has reported the end of its stream. */
    var videoDecoderDrained: Boolean = false

    /** The decoders are done and the device is playing out what it already holds. */
    var draining: Boolean = false

    /** The device has finished, or its drain was bounded out. */
    var sinkDrained: Boolean = false

    /** The drain was bounded out rather than finishing, which is a device that went away. */
    var drainFailed: Boolean = false

    /** The last frame stays on the screen, so a finished file looks finished rather than black. */
    var keepOpen: Boolean = false

    fun reset() {
        demuxerEnded = false
        audioDecoderDrained = false
        videoDecoderDrained = false
        draining = false
        sinkDrained = false
        drainFailed = false
        keepOpen = false
    }
}

/** What happened to one seek request. */
internal sealed interface SeekResult {
    /** The seek ran and this is where it landed. */
    data class Applied(val landedAt: Pts) : SeekResult

    /** A later request absorbed this one before it ran. Not a failure: the position moved anyway. */
    data class Superseded(val by: Generation) : SeekResult
}

/**
 * The first timestamp of an epoch, published by whichever worker delivered it.
 *
 * Two fields with one writer and one reader: the timestamp is stored before the epoch that validates it,
 * so a reader either sees an epoch it does not recognise or a timestamp that belongs to it.
 */
internal class FirstTimestamp {
    private val epochValue = atomic(UNSET)
    private val ptsUs = atomic(0L)

    fun record(epoch: Generation, pts: Pts) {
        if (epochValue.value == epoch.value) return
        ptsUs.value = pts.micros
        epochValue.value = epoch.value
    }

    fun of(epoch: Generation): Pts? = if (epochValue.value == epoch.value) Pts(ptsUs.value) else null

    fun clear() {
        epochValue.value = UNSET
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}

/** How a worker ended. Null means it simply stopped; anything else is a failure the actor turns typed. */
internal class WorkerOutcome(val name: String, val cause: Throwable?)

/**
 * Interleaves a decoded buffer into what the ring wants, without allocating per buffer.
 *
 * The arrays grow to the largest buffer seen and are reused after that. The conversion stage behind the
 * ring copies what it is given, so handing the same array over again is safe.
 */
private class Interleaver {
    private var planar = FloatArray(0)
    private var interleaved = FloatArray(0)

    fun interleave(buffer: AudioBuffer): FloatArray {
        val frames = buffer.frameCount
        val channels = buffer.format.channels
        if (planar.size < frames) planar = FloatArray(frames)
        if (interleaved.size < frames * channels) interleaved = FloatArray(frames * channels)
        for (channel in 0 until channels) {
            buffer.copyChannel(channel, planar)
            var frame = 0
            while (frame < frames) {
                interleaved[frame * channels + channel] = planar[frame]
                frame++
            }
        }
        return interleaved
    }
}

/**
 * Which status may follow which.
 *
 * The table is here rather than in a test because it is part of the engine's contract: a transition
 * outside it is a bug in the core, and the core records it instead of hiding it. Nothing throws on a
 * violation, because a wrong status is not worth crashing a player over, but the record makes the
 * simulation campaign able to fail on one.
 */
internal object StatusMachine {
    fun isLegal(from: PlaybackStatus, to: PlaybackStatus): Boolean = when (from) {
        PlaybackStatus.Idle -> to == PlaybackStatus.Opening
        PlaybackStatus.Opening -> to == PlaybackStatus.Paused || to == PlaybackStatus.Playing ||
            to == PlaybackStatus.Buffering || to == PlaybackStatus.Failed || to == PlaybackStatus.Idle
        PlaybackStatus.Buffering -> to != PlaybackStatus.Opening
        PlaybackStatus.Playing -> to != PlaybackStatus.Opening
        PlaybackStatus.Paused -> to != PlaybackStatus.Opening
        PlaybackStatus.Ended -> to == PlaybackStatus.Opening || to == PlaybackStatus.Buffering ||
            to == PlaybackStatus.Playing || to == PlaybackStatus.Paused || to == PlaybackStatus.Idle ||
            to == PlaybackStatus.Failed
        PlaybackStatus.Failed -> to == PlaybackStatus.Opening || to == PlaybackStatus.Idle
    }
}

/** Every message the actor accepts. Each carries its own reply and completes exactly once. */
internal sealed class CoreCommand(val name: String, private val deferred: CompletableDeferred<*>) {

    fun fail(cause: Throwable) {
        deferred.completeExceptionally(cause)
    }

    class Open(val media: MediaItem, val reply: CompletableDeferred<Unit>) : CoreCommand("open", reply)
    class Play(val reply: CompletableDeferred<Unit>) : CoreCommand("play", reply)
    class Pause(val reply: CompletableDeferred<Unit>) : CoreCommand("pause", reply)
    class Seek(val request: SeekRequest, val reply: CompletableDeferred<SeekResult>) : CoreCommand("seek", reply)
    class Stop(val reply: CompletableDeferred<Unit>) : CoreCommand("stop", reply)
    class Close(val reply: CompletableDeferred<Unit>) : CoreCommand("close", reply)
    class SetSpeed(val value: Double, val reply: CompletableDeferred<Unit>) : CoreCommand("setSpeed", reply)
    class SetVolume(val value: Float, val reply: CompletableDeferred<Unit>) : CoreCommand("setVolume", reply)
    class SetMuted(val value: Boolean, val reply: CompletableDeferred<Unit>) : CoreCommand("setMuted", reply)
    class SetLoop(val mode: LoopMode, val reply: CompletableDeferred<Unit>) : CoreCommand("setLoop", reply)

    class SelectTrack(
        val kind: TrackKind,
        val track: TrackId?,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("selectTrack", reply)

    class AttachRenderer(
        val renderer: VideoRenderer,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("attachRenderer", reply)

    class DetachRenderer(val reply: CompletableDeferred<Unit>) : CoreCommand("detachRenderer", reply)

    /** Fire and forget by contract, so its reply is complete before it is sent. */
    class SeekLater(val request: SeekRequest) : CoreCommand("seekLater", CompletableDeferred(Unit))
}

/** The tracks a source declares, as the player's own value type. */
private fun List<PlayerStreamInfo>.toTracks(): Tracks = Tracks(
    all = map { stream ->
        TrackInfo(
            id = TrackId(stream.index),
            kind = stream.kind,
            codec = stream.codec,
            language = stream.language,
            title = stream.title,
            isDefault = stream.isDefault,
            isForced = stream.isForced,
            isAccessibility = stream.isAccessibility,
            bitrate = stream.bitrate,
            videoSize = stream.videoSize,
            frameRate = stream.frameRate,
            sampleRate = stream.sampleRate,
            channels = stream.channels,
            isCoverArt = stream.isCoverArt,
        )
    },
)
