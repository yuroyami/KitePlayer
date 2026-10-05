// onTimeout is the select clause that makes an actor's wait cancellation free. Its alternative, a
// timeout wrapped around a receive, can consume a message and then be cancelled, which loses a command
// and suspends its caller for ever.
@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.AudioPlayback
import io.github.yuroyami.kiteplayer.AudioClockSnapshot
import io.github.yuroyami.kiteplayer.AudioContent
import io.github.yuroyami.kiteplayer.chapterHolding
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MasterClock
import io.github.yuroyami.kiteplayer.MatchingAudioSubtitles
import io.github.yuroyami.kiteplayer.MediaInspection
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MediaProgram
import io.github.yuroyami.kiteplayer.Marker
import io.github.yuroyami.kiteplayer.SubtitleConfig
import io.github.yuroyami.kiteplayer.SubtitleSource
import io.github.yuroyami.kiteplayer.HearingImpairedNotes
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackStats
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.QueueItemFailure
import io.github.yuroyami.kiteplayer.TimedWarning
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.EqualizerSettings
import io.github.yuroyami.kiteplayer.ReplayGainMode
import io.github.yuroyami.kiteplayer.SleepTimer
import io.github.yuroyami.kiteplayer.AudioTap
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.StepDirection
import io.github.yuroyami.kiteplayer.FrameDropPolicy
import io.github.yuroyami.kiteplayer.StreamVariant
import io.github.yuroyami.kiteplayer.SyncMode
import io.github.yuroyami.kiteplayer.TrackChange
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
import io.github.yuroyami.kiteplayer.ShownSlot
import io.github.yuroyami.kiteplayer.VideoPlayback
import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.sign
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.RecordingCapable
import io.github.yuroyami.kiteplayer.KeyframeChoice
import io.github.yuroyami.kiteplayer.KiteTrace
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.subtitle.CueSelector
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.channels.SendChannel
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
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The engine's session: one actor owning all playback state, and five workers doing the work.
 *
 * ### Why an actor
 *
 * Every piece of playback state lives here and is touched by one coroutine on one dispatcher: the
 * status, the epoch, the track selection, the published snapshot, and the decision about what happens
 * next. Accepted state-changing commands are messages: suspending calls carry one reply each, while
 * fire-and-forget calls discard or omit theirs. Terminal close is different by design: every close route
 * shares one result owned independently of any caller. After the actor returns, one independent finalizer
 * receives its immutable terminal outcome, closes the owned dispatchers, and becomes the sole writer of
 * the final snapshot and result. Those ownership periods never overlap. There is no lock to forget and no
 * field that two threads can disagree about. The workers own exactly what a single thread must own, a
 * demuxer cursor or a decoder context, and they communicate only through the queues and this actor's
 * messages.
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
     * How long teardown is allowed to run before it reports a compromised runtime.
     *
     * Production uses [CLOSE_DEADLINE]. A direct core test supplies zero to make the failure path
     * deterministic; the facade never exposes this override.
     */
    private val closeDeadline: Duration = CLOSE_DEADLINE,
    /**
     * The job this session's coroutines hang under.
     *
     * Null makes the session's lifetime its own, which is what a player whose `close` is the only end it
     * has wants. A caller that already has a lifetime, a test scope or an application scope, passes it
     * here so that cancelling that lifetime takes the session with it rather than leaving five workers
     * running.
     */
    parent: Job? = null,
    /**
     * Whether [statusHistory] and [illegalTransitions] are kept. Tests turn it on; a player does
     * not, because it lives as long as its application and both lists would grow at every pause
     * and every seek for all that time (#481).
     */
    private val recordTransitions: Boolean = false,
) : AutoCloseable {

    private val clock = output.clock

    private val scope = CoroutineScope(
        dispatchers.session + SupervisorJob(parent) + CoroutineName("kiteplayer-session"),
    )

    private val commands = Channel<CoreCommand>(Channel.UNLIMITED)
    private val outcomes = Channel<WorkerOutcome>(Channel.UNLIMITED)

    /** Commands taken off the channel but not yet executed, so nothing is lost to a preemption check. */
    private val heldCommands = ArrayDeque<CoreCommand>()

    /**
     * The reply of the request that built the session that is open or being opened, or null after a
     * stop. A cancelled request's stop names its reply, and only stops while it is still this (#410).
     * The queue's own advance builds with a reply nobody holds, so no earlier request owns the next item.
     */
    private var sessionOwner: Any? = null
    private val heldOutcomes = ArrayDeque<WorkerOutcome>()

    private val snapshotState = MutableStateFlow(PlayerSnapshot())
    private val progressState = MutableStateFlow(Progress())
    private val statsState = MutableStateFlow(PlaybackStats())

    /**
     * The cues showing right now.
     *
     * Its own flow rather than a field on the snapshot, because it changes on every cue edge, which
     * on a dense track is several times a second: putting it in the snapshot would make every
     * consumer of any other field recompose at that rate.
     */
    private val cuesState =
        MutableStateFlow<List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>>(emptyList())
    private val eventSink = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)

    /** The loudest volume this player accepts, from its configuration. See `AudioConfig.volumeCeiling`. */
    val volumeCeiling: Float get() = config.audio.volumeCeiling

    val subtitleCues: StateFlow<List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>>
        get() = cuesState.asStateFlow()

    val snapshots: StateFlow<PlayerSnapshot> get() = snapshotState.asStateFlow()
    val progress: StateFlow<Progress> get() = progressState.asStateFlow()
    val stats: StateFlow<PlaybackStats> get() = statsState.asStateFlow()
    val events: SharedFlow<PlayerEvent> get() = eventSink.asSharedFlow()

    /** The queues of the lossless collectors, replaced whole so [emitEvent] reads them without a lock. */
    private val eventTaps = atomic(emptyList<SendChannel<PlayerEvent>>())

    /**
     * Every event, with none dropped: each collector gets a queue of its own with no limit, fed
     * beside [events] rather than from it, so a collector of [events] that falls behind cannot cost
     * this one an event (#414). The queue is registered before the first suspension, so a collector
     * started undispatched has every event from then on.
     */
    val losslessEvents: Flow<PlayerEvent> = flow {
        val tap = Channel<PlayerEvent>(Channel.UNLIMITED)
        eventTaps.update { it + tap }
        try {
            for (event in tap) emit(event)
        } finally {
            eventTaps.update { taps -> taps.filterNot { it === tap } }
            tap.cancel()
        }
    }

    // Actor-confined state until the actor returns. The close finalizer then owns status, lastError and
    // the one terminal snapshot exclusively; every other field is immutable to it.
    private var status: PlaybackStatus = PlaybackStatus.Idle
    private var media: MediaItem? = null

    /** Told once per open: the same disagreement does not become a warning per seek. */
    private var divergencesReported: Boolean = false

    /**
     * The queue: the items and the cursor. Empty and -1 outside queue playback.
     *
     * The items are the actor's own and published in every snapshot, so they sit in a list nobody
     * can change, this actor included: every edit builds a new one. The list a caller passed used
     * to be kept as it was, and an edit to it, or through a snapshot from Java, changed the queue
     * under the session (#409).
     */
    private var queueItems: List<MediaItem> = emptyList()
        set(value) {
            field = if (value is ReadOnlyList) value else ReadOnlyList(value.toList())
        }
    private var queueIndex: Int = -1

    /** Positions into [queueItems] that failed to open and were skipped, until each opens (#487). */
    private var failedQueueIndices: Set<Int> = emptySet()

    /**
     * Shuffle as an order OVER the queue rather than a reorder OF it.
     *
     * [queueOrder] holds positions into [queueItems] in play order, and everything that walks the
     * queue walks it: next, previous, and the advance at the end of an item. With shuffle off it
     * is the plain 0, 1, 2. The items themselves never move, so the list an application shows is
     * always the list someone built, and turning shuffle off needs nothing put back.
     */
    private var shuffleEnabled = false

    /** Positions to announce on crossing, sorted. Player-level: they outlive the item. */
    private var markers: List<Marker> = emptyList()

    /** The published position at the last marker pass, and the seek epoch it was read in. */
    private var markerCursorUs: Long = NO_POSITION
    private var markerCursorEpoch: Generation? = null
    private var shuffleRandom: Random = Random.Default
    private var queueOrder: List<Int> = emptyList()

    /**
     * The order of the next lap, drawn once when the queue first looks past the end of this one
     * with [io.github.yuroyami.kiteplayer.QueueConfig.reshuffleEachLap] on, so the preload and the
     * advance agree on what follows, and taken up when the queue moves forward from [lapEndIndex]
     * to its first item (#488). Any other move, an edit or a new order drops it.
     */
    private var nextLapOrder: List<Int>? = null
    private var lapEndIndex: Int = -1

    /** The chapter the last ChapterChanged named, as an index; MIN_VALUE forces the first emit. */
    private var lastChapterIndex: Int = Int.MIN_VALUE

    /** One parsed external subtitle file: a synthetic track and its ready cue table. */
    private class ExternalSubtitleTrack(
        val id: TrackId,
        val info: TrackInfo,
        val cues: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
        /** The whole text of an ASS or SSA file, for the typesetter. Null for other formats. */
        val script: String? = null,
        /** Where the file came from and how it was asked to be read, for a reload (#515). */
        val source: SubtitleSource,
        /** How many times this track was read again, so the typesetter knows a reload from a reselection. */
        val revision: Int = 0,
    )

    /** The media item's parsed external subtitle files, in declaration order. */
    private var externalSubtitleTracks: List<ExternalSubtitleTrack> = emptyList()

    /** The external track currently timing cues, or null when none is. */
    private var selectedExternalSubtitle: TrackId? = null
    private var selectedExternalSubtitle2: TrackId? = null

    /** An external selection waiting for handleTrackChanges to finish its container rebuild. */
    private var pendingExternalSubtitle: TrackId? = null

    private fun isExternalSubtitle(track: TrackId?): Boolean =
        track != null && externalSubtitleTracks.any { it.id == track }

    /**
     * Parses the media item's external subtitle files: each becomes a selectable
     * synthetic subtitle track whose cues run through the SAME timing path container cues use.
     * A file that cannot be read or parsed warns typed and is skipped; the open never fails
     * over a subtitle.
     */
    /**
     * Reads the declared subtitle files, before any session exists.
     *
     * Split from [adoptExternalSubtitles] so an open can know whether a file flagged
     * [SubtitleSource.selectImmediately] will really load BEFORE it decides whether to select the
     * container's own subtitle stream. Deciding first and finding out afterwards is how a flagged
     * file that turned out to be unreadable left the viewer with no subtitles at all, which is
     * worse than the defect it was fixing. Nothing here touches the session.
     */
    private suspend fun parseExternalSubtitles(
        item: MediaItem,
        report: (PlaybackWarning) -> Unit = ::warn,
    ): List<ExternalSubtitleTrack> =
        item.externalSubtitles.mapIndexedNotNull { index, sourceFile ->
            // TrackId's own convention: external ids are negative, printed external1, external2...
            val id = TrackId(-(index + 1))
            when (val parsed = parseExternalSubtitle(sourceFile, id, item, report)) {
                is ExternalSubtitleParse.Loaded -> parsed.track
                is ExternalSubtitleParse.Failed -> {
                    report(PlaybackWarning.SubtitleSourceUnreadable(sourceFile.uri, parsed.reason))
                    null
                }
            }
        }

    /**
     * [parseExternalSubtitles] for an open, while the actor keeps reading its mailbox (#412).
     *
     * A stop or a close cancels the read and preempts the open, as it preempts the backend open. The
     * read runs in the player's scope rather than under this call, so a reader that ignores the
     * cancel cannot hold the actor, and a reader it makes anyway is closed by the read itself.
     *
     * @throws OpenPreempted when a stop or a close came first.
     */
    private suspend fun readOpenSubtitles(item: MediaItem): List<ExternalSubtitleTrack> {
        if (item.externalSubtitles.isEmpty()) return emptyList()
        val reading = scope.async { parseExternalSubtitles(item) }
        while (!reading.isCompleted) {
            if (preempted()) {
                reading.cancel()
                throw OpenPreempted()
            }
            withTimeoutOrNull(WORKER_POLL) { reading.join() }
        }
        return reading.await()
    }

    /** Merges what [parseExternalSubtitles] read into the session's track table. */
    private fun adoptExternalSubtitles(item: MediaItem, parsed: List<ExternalSubtitleTrack>) {
        // Every DECLARED file mints an id, loaded or not: the count of loaded tracks
        // used to seed addExternalSubtitle's next id, which collided with a declared track as
        // soon as one earlier declaration had failed to load.
        externalSubtitleIdsMinted = item.externalSubtitles.size
        externalSubtitleTracks = parsed
        if (parsed.isNotEmpty()) {
            tracks = tracks.copy(all = tracks.all + parsed.map { it.info })
        }
    }

    private sealed interface SubtitleBytes {
        class Read(val bytes: ByteArray) : SubtitleBytes
        class Refused(val reason: String) : SubtitleBytes
    }

    /**
     * The bytes of one external subtitle file, from whichever door it has.
     *
     * The caller's own reader first, then the network resolver for an http or https address, then
     * the local path. The network road carries the [parent] item's headers only to the item's own
     * scheme, host and port, where a subtitle beside a signed URL is almost always served.
     */
    private suspend fun readSubtitleBytes(
        source: SubtitleSource,
        parent: MediaItem?,
    ): SubtitleBytes {
        // The stall limit starts before the reader exists, so a factory or a resolver that never
        // answers is bounded as a silent read is (#412).
        val stallLimit = config.buffer.stallTimeout
        val factory = source.io
        if (factory != null) {
            val reader = when (val opened = openSubtitleReader(stallLimit) { factory.open() }) {
                is SubtitleReader.Opened -> opened.reader ?: return SubtitleBytes.Refused("its reader opened nothing")
                SubtitleReader.TimedOut -> return SubtitleBytes.Refused("its reader did not open within $stallLimit")
                is SubtitleReader.Failed -> return SubtitleBytes.Refused("its reader failed${causeDetail(opened.cause)}")
            }
            return readOrRefuse(reader, source.uri) { "its reader failed${causeDetail(it)}" }
        }
        if (source.uri.startsWith("http://", true) || source.uri.startsWith("https://", true)) {
            val headers = if (parent != null && sameHttpOrigin(source.uri, parent.uri)) parent.headers else emptyMap()
            val opened = openSubtitleReader(stallLimit) {
                resolveMediaIo(MediaItem(source.uri, headers = headers), config.network)
            }
            val reader = when (opened) {
                is SubtitleReader.Opened -> opened.reader ?: return SubtitleBytes.Refused(
                    "nothing here can fetch an address; add the network module or give the source its own reader",
                )
                SubtitleReader.TimedOut -> return SubtitleBytes.Refused("the address did not answer within $stallLimit")
                is SubtitleReader.Failed ->
                    return SubtitleBytes.Refused("the address could not be reached${causeDetail(opened.cause)}")
            }
            return readOrRefuse(reader, source.uri) { "the address could not be read${causeDetail(it)}" }
        }
        return when (val file = readExternalFile(source.uri, MAX_SUBTITLE_BYTES)) {
            is ExternalFile.Read -> SubtitleBytes.Read(file.bytes)
            ExternalFile.TooLarge ->
                SubtitleBytes.Refused("it holds more than $MAX_SUBTITLE_BYTES bytes, and a subtitle file that large is not one")
            ExternalFile.Unreadable -> SubtitleBytes.Refused("the file could not be read")
        }
    }

    private sealed interface SubtitleReader {
        class Opened(val reader: MediaIo?) : SubtitleReader
        object TimedOut : SubtitleReader
        class Failed(val cause: Throwable) : SubtitleReader
    }

    /**
     * A subtitle file's reader from [open], within [limit] (#412).
     *
     * A reader that [open] hands back after the limit has passed, or after the task was cancelled,
     * has no one else to close it, so it is closed here, and a reader handed on is closed by
     * [readWholly] alone: either way, exactly once. A cancellation is the task's own end and is
     * thrown on, never read as a failed reader.
     */
    private suspend fun openSubtitleReader(limit: Duration, open: suspend () -> MediaIo?): SubtitleReader {
        var made: MediaIo? = null
        try {
            val opened = withTimeoutOrNull(limit) {
                made = open()
                true
            }
            if (opened == true) return SubtitleReader.Opened(made)
            made?.let { runCatching { it.close() } }
            return SubtitleReader.TimedOut
        } catch (cancellation: CancellationException) {
            made?.let { runCatching { it.close() } }
            throw cancellation
        } catch (failure: Throwable) {
            made?.let { runCatching { it.close() } }
            return SubtitleReader.Failed(failure)
        }
    }

    /** [readWholly], with a failure read as a refusal that [reason] words, and a cancellation thrown on. */
    private suspend fun readOrRefuse(reader: MediaIo, uri: String, reason: (Throwable) -> String): SubtitleBytes = try {
        readWholly(reader, uri)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        SubtitleBytes.Refused(reason(failure))
    }

    /**
     * Reads a whole subtitle file, and refuses one that is not a subtitle file.
     *
     * Subtitles are small. The cap is what stops a wrong address, or a server answering with a
     * film, from being pulled entirely into memory before anything notices it is not text.
     */
    private suspend fun readWholly(reader: MediaIo, uri: String): SubtitleBytes = reader.use { io ->
        val declared = io.size
        if (declared != null && declared > MAX_SUBTITLE_BYTES) {
            return@use SubtitleBytes.Refused(
                "it declares $declared bytes, and a subtitle file over $MAX_SUBTITLE_BYTES is not one",
            )
        }
        val collected = mutableListOf<ByteArray>()
        var total = 0
        val chunk = ByteArray(SUBTITLE_READ_CHUNK)
        // The open waits for this file on the actor, so a reader that stops answering would hold
        // the open for ever. The stall timeout bounds each read, and the file is then skipped.
        val stallLimit = config.buffer.stallTimeout
        // A 0 means "nothing yet, more may come", as MediaIo.read says. Only -1 ends the file, and
        // a run of zeros shares the same stall bound (#215).
        var waitingSinceNanos: Long? = null
        while (true) {
            val read = withTimeoutOrNull(stallLimit) { io.read(chunk, 0, chunk.size) }
                ?: return@use SubtitleBytes.Refused("no bytes arrived for $stallLimit")
            if (read < 0) break
            if (read == 0) {
                val now = clock.nanos()
                val since = waitingSinceNanos ?: now.also { waitingSinceNanos = it }
                if ((now - since).nanoseconds >= stallLimit) {
                    return@use SubtitleBytes.Refused("no bytes arrived for $stallLimit")
                }
                delay(SUBTITLE_EMPTY_READ_RETRY)
                continue
            }
            waitingSinceNanos = null
            total += read
            if (total > MAX_SUBTITLE_BYTES) {
                return@use SubtitleBytes.Refused(
                    "it is over $MAX_SUBTITLE_BYTES bytes, which no subtitle file is",
                )
            }
            collected += chunk.copyOf(read)
        }
        if (total == 0) return@use SubtitleBytes.Refused("it is empty")
        val bytes = ByteArray(total)
        var at = 0
        collected.forEach { part ->
            part.copyInto(bytes, at)
            at += part.size
        }
        SubtitleBytes.Read(bytes)
    }

    private sealed interface ExternalSubtitleParse {
        class Loaded(val track: ExternalSubtitleTrack) : ExternalSubtitleParse
        class Failed(val reason: String) : ExternalSubtitleParse
    }

    /** One external subtitle file to one synthetic track, or the sentence saying why not. */
    private suspend fun parseExternalSubtitle(
        sourceFile: SubtitleSource,
        id: TrackId,
        parent: MediaItem?,
        report: (PlaybackWarning) -> Unit = ::warn,
    ): ExternalSubtitleParse {
        val parser = backend.subtitleFileParser()
            ?: return ExternalSubtitleParse.Failed(
                "this backend supplies no subtitle file parser, so external files cannot load",
            )
        val bytes = when (val read = readSubtitleBytes(sourceFile, parent)) {
            is SubtitleBytes.Read -> read.bytes
            is SubtitleBytes.Refused -> return ExternalSubtitleParse.Failed(read.reason)
        }
        // The encoding is decided from the bytes, not assumed. A file that needed a guess says so,
        // because a viewer looking at mojibake can act on "I read this as windows-1252" and cannot
        // act on silence. The East Asian tables are the parser's, because they live above the core.
        // A caller's language wins; without one, the file's name may say it (#514). An encoding the
        // caller named is used as it is, and one the player prefers stands in for the guess (#515).
        val hints = subtitleNameHints(sourceFile.uri)
        val language = sourceFile.language ?: hints.language
        val named = sourceFile.encoding
        val decoded = if (named != null) {
            decodeSubtitleBytesAs(bytes, named, eastAsian = parser::decode)
                ?: return ExternalSubtitleParse.Failed(
                    "it was to be read as $named, and this backend's subtitle parser has no table for that encoding",
                )
        } else {
            decodeSubtitleBytes(bytes, language, config.subtitles.fallbackEncoding, eastAsian = parser::decode)
        }
        if (!decoded.confident) {
            report(
                PlaybackWarning.SubtitleCharsetGuessed(
                    uri = sourceFile.uri,
                    charset = decoded.charset,
                    detected = decoded.unsupportedGuess,
                ),
            )
        }
        val trimmed = decoded.text.removePrefix("﻿")
        val isVtt = trimmed.startsWith("WEBVTT") || sourceFile.uri.endsWith(".vtt", ignoreCase = true)
        // The same self-announcement the backend's parser routes on: labelling every
        // non-VTT file SubRip told a track list that an ASS script was something it is not.
        val isAss = trimmed.trimStart(' ', '\r', '\n').startsWith("[Script Info]", ignoreCase = true)
        val parsed = runCatching { parser.parse(trimmed, isVtt) }.getOrElse { failure ->
            return ExternalSubtitleParse.Failed(
                "the external subtitle file failed to parse: ${redactUri(sourceFile.uri)}${causeDetail(failure)}",
            )
        }
        // The notes of hearing-impaired subtitles go as the file is read, but never an ASS script's (#493).
        val cues = if (isAss) parsed else hideHearingImpairedNotes(parsed, config.subtitles.hearingImpairedNotes)
        if (cues.isEmpty()) {
            return ExternalSubtitleParse.Failed(
                "the external subtitle file parsed to no cues: ${redactUri(sourceFile.uri)}",
            )
        }
        return ExternalSubtitleParse.Loaded(
            ExternalSubtitleTrack(
                id = id,
                info = TrackInfo(
                    id = id,
                    kind = TrackKind.Subtitle,
                    codec = when {
                        isVtt -> "external/webvtt"
                        isAss -> "external/ass"
                        else -> "external/subrip"
                    },
                    language = language,
                    title = sourceFile.title ?: sourceFile.uri.substringAfterLast('/'),
                    isForced = hints.forced,
                    isAccessibility = hints.hearingImpaired,
                ),
                cues = cues.sortedBy { cue -> cue.startMicros },
                script = if (isAss) trimmed else null,
                source = sourceFile,
            ),
        )
    }

    /**
     * Loads one subtitle file DURING playback, appends it as a selectable external track, and
     * selects it, because a viewer who just picked a file wants to see it, not to find it in a
     * menu. Unlike the open path, a file that cannot load fails the call typed and loudly: this
     * is a direct answer to a direct request, not a best-effort side dish of an open.
     */
    private fun addExternalSubtitle(command: CoreCommand.AddExternalSubtitle) {
        val active = session
        if (active == null) {
            command.reply.completeExceptionally(
                IllegalStateException("addExternalSubtitle needs an open media item"),
            )
            return
        }
        externalSubtitleIdsMinted++
        val id = TrackId(-externalSubtitleIdsMinted)
        // The reading and the parsing run as a task this request owns, so the actor keeps reading its
        // mailbox: a reader that is slow to open or to answer used to hold every stop and close behind
        // it (#412). The answer comes back to the actor as a command, and only the actor changes the
        // track table. A stop or a close cancels the task, and so does the caller leaving.
        val parent = media
        val acquisition = SubtitleAcquisition(id, command.reply, active)
        acquisition.job = scope.launch(start = CoroutineStart.LAZY) {
            val parsed = try {
                parseExternalSubtitle(command.source, id, parent)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                ExternalSubtitleParse.Failed("the external subtitle file could not be read${causeDetail(failure)}")
            }
            commands.trySend(CoreCommand.ExternalSubtitleRead { adoptExternalSubtitle(acquisition, parsed) })
        }
        subtitleAcquisitions += acquisition
        command.reply.invokeOnCompletion { cause -> if (cause is CancellationException) acquisition.job?.cancel() }
        acquisition.job?.start()
    }

    /**
     * One [addExternalSubtitle] or [reloadExternalSubtitle] whose file is still being read, owned by
     * the request that asked. [reply] answers the track's id for an add and nothing for a reload.
     */
    private class SubtitleAcquisition<T>(
        val id: TrackId,
        val reply: CompletableDeferred<T>,
        /** The session the file was asked for. A file that comes back to another one is not added. */
        val session: OpenSession,
        /** True when the track is already there and this reads its file again. */
        val reloading: Boolean = false,
    ) {
        var job: Job? = null
    }

    /** The external subtitle reads in flight. Actor-confined. */
    private val subtitleAcquisitions = mutableListOf<SubtitleAcquisition<*>>()

    /** Cancels every external subtitle read in flight and answers its caller with [failure] (#412). */
    private fun cancelSubtitleAcquisitions(failure: () -> Throwable) {
        if (subtitleAcquisitions.isEmpty()) return
        val cancelled = subtitleAcquisitions.toList()
        subtitleAcquisitions.clear()
        for (acquisition in cancelled) {
            acquisition.job?.cancel()
            acquisition.reply.completeExceptionally(failure())
        }
    }

    /** Adds the track a finished [SubtitleAcquisition] read, on the actor, if it is still wanted. */
    private suspend fun adoptExternalSubtitle(acquisition: SubtitleAcquisition<TrackId>, parsed: ExternalSubtitleParse) {
        // Gone means a stop or a close cancelled it and already answered its caller.
        if (!subtitleAcquisitions.remove(acquisition)) return
        // A caller that left wants nothing added.
        if (acquisition.reply.isCompleted) return
        val active = session
        if (active == null || active !== acquisition.session) {
            acquisition.reply.completeExceptionally(
                IllegalStateException("the media changed before the subtitle file finished loading"),
            )
            return
        }
        val id = acquisition.id
        when (parsed) {
            is ExternalSubtitleParse.Failed -> acquisition.reply.completeExceptionally(
                IllegalArgumentException(parsed.reason),
            )
            is ExternalSubtitleParse.Loaded -> {
                externalSubtitleTracks = externalSubtitleTracks + parsed.track
                tracks = tracks.copy(all = tracks.all + parsed.track.info)
                subtitleChosenByPlayer = false
                if (active.subtitleStream != null) {
                    // A container stream is timing cues: route through the same rebuild the
                    // ordinary selection path takes, so one selection owner survives. The caller
                    // is deliberately NOT answered here. It asked for a subtitle to be SHOWING,
                    // and handing it an id while the rebuild that makes that true has not run,
                    // and can still fail the whole player, is the false success the audit named.
                    pendingExternalSubtitle = id
                    val selection = CompletableDeferred<TrackChange>()
                    queueSelection(TrackKind.Subtitle, id, selection)
                    awaitSubtitleAdd(id, selection, acquisition.reply)
                } else {
                    applyExternalSubtitle(id)
                    acquisition.reply.complete(id)
                }
                publishSnapshot()
            }
        }
    }

    /**
     * Reads an external track's file again, in the encoding the command names or decided from its
     * bytes when it names none (#515), as a task the request owns, as an add is (#412).
     *
     * The track keeps its id and its place, so an application's menu and selection stay valid. A
     * later reload of the same track replaces one still reading, which is answered as replaced.
     */
    private fun reloadExternalSubtitle(command: CoreCommand.ReloadExternalSubtitle) {
        val active = session
        if (active == null) {
            command.reply.completeExceptionally(IllegalStateException("reloadExternalSubtitle needs an open media item"))
            return
        }
        val loaded = externalSubtitleTracks.firstOrNull { it.id == command.track }
        if (loaded == null) {
            command.reply.completeExceptionally(
                IllegalArgumentException("${command.track} is not an external subtitle track of the open media"),
            )
            return
        }
        subtitleAcquisitions.filter { it.reloading && it.id == command.track }.forEach { earlier ->
            subtitleAcquisitions.remove(earlier)
            earlier.job?.cancel()
            earlier.reply.completeExceptionally(IllegalStateException("a later reload of ${command.track} replaced this one"))
        }
        val source = loaded.source.copy(encoding = command.encoding)
        val parent = media
        val acquisition = SubtitleAcquisition(command.track, command.reply, active, reloading = true)
        acquisition.job = scope.launch(start = CoroutineStart.LAZY) {
            val parsed = try {
                parseExternalSubtitle(source, command.track, parent)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                ExternalSubtitleParse.Failed("the external subtitle file could not be read${causeDetail(failure)}")
            }
            commands.trySend(CoreCommand.ExternalSubtitleRead { adoptReloadedSubtitle(acquisition, parsed) })
        }
        subtitleAcquisitions += acquisition
        command.reply.invokeOnCompletion { cause -> if (cause is CancellationException) acquisition.job?.cancel() }
        acquisition.job?.start()
    }

    /**
     * Puts a track's new reading where the old one was, on the actor (#515).
     *
     * A track showing as the primary subtitle swaps its cue table in place, as an external selection
     * does, and its typesetter takes the new script. One showing as the secondary subtitle swaps that
     * slot's table. Neither moves playback. A read that failed leaves the track as it was.
     */
    private suspend fun adoptReloadedSubtitle(acquisition: SubtitleAcquisition<Unit>, parsed: ExternalSubtitleParse) {
        if (!subtitleAcquisitions.remove(acquisition)) return
        if (acquisition.reply.isCompleted) return
        val active = session
        if (active == null || active !== acquisition.session) {
            acquisition.reply.completeExceptionally(
                IllegalStateException("the media changed before the subtitle file finished loading again"),
            )
            return
        }
        val id = acquisition.id
        val at = externalSubtitleTracks.indexOfFirst { it.id == id }
        if (at < 0) {
            acquisition.reply.completeExceptionally(
                IllegalStateException("$id was taken out before its file finished loading again"),
            )
            return
        }
        val read = when (parsed) {
            is ExternalSubtitleParse.Failed -> {
                acquisition.reply.completeExceptionally(IllegalArgumentException(parsed.reason))
                return
            }
            is ExternalSubtitleParse.Loaded -> parsed.track
        }
        val old = externalSubtitleTracks[at]
        val track = ExternalSubtitleTrack(
            id = id,
            info = read.info,
            cues = read.cues,
            script = read.script,
            source = read.source,
            revision = old.revision + 1,
        )
        externalSubtitleTracks = externalSubtitleTracks.toMutableList().also { it[at] = track }
        tracks = tracks.copy(all = tracks.all.map { if (it.id == id) track.info else it })
        if (selectedExternalSubtitle == id) applyExternalSubtitle(id)
        if (selectedExternalSubtitle2 == id) {
            active.subtitle2Cues = track.cues.toMutableList()
            active.publishedCueKey = null
        }
        publishSnapshot()
        acquisition.reply.complete(Unit)
    }

    /**
     * Answers an [addExternalSubtitle] only once the rebuild its selection triggered has landed.
     *
     * Launched on the session dispatcher, which is the actor's own lane, so the rollback below
     * touches actor state under exactly the confinement every handler runs in. A selection that did
     * not apply takes the appended track back out again: a row in the track table that nothing can
     * ever show is worse than a call that failed and said so.
     */
    private fun awaitSubtitleAdd(
        id: TrackId,
        selection: CompletableDeferred<TrackChange>,
        reply: CompletableDeferred<TrackId>,
    ) {
        scope.launch {
            val outcome = try {
                selection.await()
            } catch (cancellation: CancellationException) {
                reply.completeExceptionally(cancellation)
                throw cancellation
            } catch (failure: Throwable) {
                withdrawExternalSubtitle(id)
                reply.completeExceptionally(failure)
                return@launch
            }
            if (outcome is TrackChange.Applied) {
                reply.complete(id)
                return@launch
            }
            withdrawExternalSubtitle(id)
            val why = when (outcome) {
                is TrackChange.Superseded -> "a later track selection replaced it"
                is TrackChange.Discarded -> outcome.reason
                is TrackChange.Applied -> ""
            }
            reply.completeExceptionally(
                IllegalStateException("the subtitle file loaded but its selection did not apply: $why"),
            )
        }
    }

    /** Takes an external subtitle track back out after its selection failed to apply. */
    private suspend fun withdrawExternalSubtitle(id: TrackId) {
        externalSubtitleTracks = externalSubtitleTracks.filterNot { it.id == id }
        tracks = tracks.copy(all = tracks.all.filterNot { it.id == id })
        if (selectedExternalSubtitle == id) {
            selectedExternalSubtitle = null
            session?.subtitleCues?.clear()
            tracks = tracks.withSelection(TrackKind.Subtitle, null)
            refreshTypesetting()
        }
        if (pendingExternalSubtitle == id) pendingExternalSubtitle = null
        publishSnapshot()
    }

    /**
     * Puts back the subtitle state that a rebuild does not carry (#212).
     *
     * A rebuild replaces the track table with the container's own and starts a session with an
     * empty secondary slot. This re-adds the external rows, re-applies the external primary
     * selection, and selects [secondary] again with a fresh decoder. Running it twice is harmless,
     * which matters because a decoder recovery inside a track change runs it once for each.
     */
    private suspend fun restoreSubtitleState(secondary: TrackId?) {
        val missing = externalSubtitleTracks.filter { external -> tracks.all.none { it.id == external.id } }
        if (missing.isNotEmpty()) tracks = tracks.copy(all = tracks.all + missing.map { it.info })
        val primary = pendingExternalSubtitle ?: selectedExternalSubtitle
        pendingExternalSubtitle = null
        if (primary != null && isExternalSubtitle(primary)) applyExternalSubtitle(primary)
        if (secondary == null || tracks.selectedSecondarySubtitle == secondary) return
        // The command's own path, so the restored slot gets exactly what a caller's selection gets.
        // It answers before it returns on every path that does not throw.
        val reply = CompletableDeferred<TrackChange>()
        applySecondarySubtitle(CoreCommand.SelectSecondarySubtitle(secondary, reply))
        val outcome = if (reply.isCompleted) runCatching { reply.await() } else null
        val change = outcome?.getOrNull()
        if (change !is TrackChange.Applied) {
            selectedExternalSubtitle2 = null
            val reason = (change as? TrackChange.Discarded)?.reason
                ?: outcome?.exceptionOrNull()?.message
                ?: "the selection was not answered"
            warn(PlaybackWarning.TrackDeselected(secondary, "the secondary subtitle could not follow the rebuild: $reason"))
        }
    }

    /** Swaps the timed cue table in place: no container reopen, one publish. */
    private suspend fun applyExternalSubtitle(target: TrackId?) {
        val active = session ?: return
        selectedExternalSubtitle = target
        active.subtitleCues = target
            ?.let { id -> externalSubtitleTracks.firstOrNull { it.id == id } }
            ?.cues
            ?.toMutableList()
            ?: mutableListOf()
        tracks = tracks.withSelection(TrackKind.Subtitle, target)
        refreshTypesetting()
        publishSnapshot()
    }
    private var session: OpenSession? = null
    private var tracks: Tracks = Tracks.Empty
    private var lastError: PlaybackError? = null
    private var playRequested = false

    /**
     * True while a queue move opens the next item with play carried over. An open clears
     * [playRequested] until it lands, and this keeps the intent published meanwhile (#226).
     */
    private var openCarriesPlay = false

    /** [PlayerSnapshot.playRequested]: playing, buffering, or opening with play carried over. */
    private fun publishedPlayIntent(): Boolean = when (status) {
        PlaybackStatus.Playing, PlaybackStatus.Buffering -> true
        PlaybackStatus.Opening -> openCarriesPlay
        else -> false
    }
    private var loop: LoopMode = LoopMode.Off

    /** Once per media: handleLoop refusing an unseekable repeat runs on every Ended pass. */
    private var loopRefusalWarned = false

    /** Ids ever minted for external subtitle tracks this media, failed loads included. */
    private var externalSubtitleIdsMinted = 0

    /**
     * The armed A-B loop. A player property like [speed]: it survives seeks and reopen,
     * because the caller armed the loop, not the media. With only A armed the loop wraps at the
     * end of the media; with both armed the crossing check in [handlePlaybackTime] owns B.
     */
    private var abLoopA: Duration? = null
    private var abLoopB: Duration? = null
    private var speed: Double = 1.0

    /*
     * Following an external clock (#91). The trim multiplies the caller's speed while a small
     * difference closes; it is 1 whenever nothing is being followed. All actor-confined.
     */
    private var externalClock: io.github.yuroyami.kiteplayer.ExternalClock? = null
    private var externalTrim: Double = 1.0
    private var externalAskedAtNanos: Long = NO_POSITION
    private var externalLastAnswerUs: Long = NO_POSITION
    private var externalSilentSinceNanos: Long = NO_POSITION
    private var externalSilentWarned: Boolean = false
    private var externalSeekAtNanos: Long = NO_POSITION
    private var externalSeekInFlight: Boolean = false
    private var externalSeekLatencyNanos: Long = 0L

    /*
     * Holding the delay behind a live sender (#395). The trim multiplies the caller's speed while the
     * player catches up with a source that pushes in real time; it is 1 whenever nothing is being
     * caught up. All actor-confined.
     */
    private var liveTrim: Double = 1.0
    private var liveCheckedAtNanos: Long = NO_POSITION

    /** The speed the pipelines run at: the caller's, times the external clock's and the live trims. */
    private val effectiveSpeed: Double get() = pipelineSpeed(speed)

    /**
     * [requested] times the external clock's and the live trims, held inside the tempo stage's range.
     *
     * A trim corrects a drift of a fraction of a percent, so at either end of the legal range it
     * saturates rather than asking the pipeline for a speed it refuses, which failed the session at
     * 4x or 0.25x as soon as the clock pulled outwards (#413). A requested speed outside the range is
     * left for the pipeline to refuse, as before.
     */
    private fun pipelineSpeed(requested: Double): Double {
        val trimmed = requested * externalTrim * liveTrim
        if (requested < TempoStage.MIN_SPEED || requested > TempoStage.MAX_SPEED) return trimmed
        return trimmed.coerceIn(TempoStage.MIN_SPEED, TempoStage.MAX_SPEED)
    }

    /** Whether speed keeps pitch, seeded from config. A live change applies at once, like speed. */
    private var preservePitch: Boolean = config.audio.preservePitch
    private var volume: Float = 1.0f

    /** The duck multiplier the session guards set, kept for every audio path this core builds. */
    private var duckLevel: Float = 1f
    private var muted: Boolean = false
    private var balance: Float = 0f
    private var equalizer: EqualizerSettings = config.audio.equalizer
    private var videoEnabled: Boolean = config.videoEnabled

    /** The armed sleep timer and its fade length. */
    private var sleepTimer: SleepTimer? = null
    private var sleepFade: Duration = Duration.ZERO

    /**
     * What is left of an [SleepTimer.After], and the instant it was last counted down from, or
     * [NO_POSITION] while playback is not advancing. A remaining time rather than a deadline, so a
     * pause does not run the timer down (#216).
     */
    private var sleepRemainingNanos: Long = 0L
    private var sleepCountedAtNanos: Long = NO_POSITION
    private var videoScale: VideoScale = VideoScale.Fit
    private var videoAdjustments: VideoAdjustments = VideoAdjustments.Identity
    private var renderQuality: io.github.yuroyami.kiteplayer.RenderQuality = config.renderQuality
    private var hdrPolicy: io.github.yuroyami.kiteplayer.HdrPolicy = config.hdrPolicy

    /** What the renderer last said the screen shows of this open's dynamic range. Reset with the session. */
    private var videoDynamicRange: io.github.yuroyami.kiteplayer.VideoDynamicRange =
        io.github.yuroyami.kiteplayer.VideoDynamicRange.Standard
    private var videoTransform: VideoTransform = VideoTransform.Identity

    /** Runtime subtitle timing shift, seeded from config. Positive shows cues later. */
    private var subtitleDelay: Duration = config.subtitles.delay

    /** Runtime subtitle size, seeded from config, applied at the next rasterisation. */
    private var subtitleScale: Float = config.subtitles.fontScale

    /** True once the installed typesetter failed to start or threw: this player stays on the Kotlin tier. */
    private var typesetterRefused: Boolean = false

    /**
     * True once `AudioConfig.resampler` refused: this player stays on the engine's own sinc. Set on
     * the audio feeder, read by the actor when it builds the next audio path.
     */
    private val resamplerRefused = atomic(false)

    /** The viewer's style override, seeded from config, applied at the next rasterisation. */
    private var subtitleStyle: io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride? = config.subtitles.style

    /**
     * Where the implicit bottom stack anchors, as a fraction of the viewport height (mpv's
     * sub-pos over 100). 1.0 is the ordinary bottom edge; explicitly positioned cues never move.
     */
    private var subtitlePosition: Float = 1f

    /**
     * The part of the output the Kotlin tier lays text out in, as insets that are fractions of the
     * output. Typeset tracks keep their author's placement. Actor only.
     */
    private var subtitleSafeArea: io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea =
        io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea.None

    /**
     * Runtime audio timing shift. Positive means the sound reaches the ear late (a Bluetooth
     * stack, a receiver), so the master clock the video chases is read that much AHEAD and every
     * frame is presented earlier by the same amount. The audio samples themselves are never
     * touched, which is what makes the setting cheap and instant.
     */
    private var audioDelay: Duration = Duration.ZERO
    private var closed = false
    private var terminated = false

    /** The closed flag as the non-suspending commands see it, from whatever thread calls them. */
    private val closedNow = atomic(false)

    /** One parentless terminal result shared by every close caller. */
    private val terminalCloseResult = CompletableDeferred<Unit>(parent = null)

    /** Completed the moment a close is first asked for, for helpers that go away with the player. */
    private val closeAsked = CompletableDeferred<Unit>(parent = null)

    /** The actor's final handoff to the independent dispatcher finalizer. */
    private val terminalCloseOutcome = atomic<TerminalCloseOutcome?>(null)

    private var requestedEpoch: Generation = Generation.Initial
    private var seekPhase: SeekPhase = SeekPhase.Idle
    private var pendingSeek: SeekRequest? = null
    private val pendingSeekReplies = mutableListOf<CompletableDeferred<SeekResult>>()
    private var seekHeldSinceNanos: Long = 0
    private var lastSeekAtNanos: Long = 0
    private var framesShownAtLastSeek: Long = 0
    /**
     * The desired track selection: at most one request per kind, each with its caller waiting.
     *
     * A map, and not the single pending command it used to be, for two separate reasons. A caller
     * that changed the audio track and then the subtitle track before the first rebuild ran lost
     * the audio change entirely AND was told it had applied; one rebuild now carries every kind
     * that has been asked for, and only a second request for the SAME kind displaces the first,
     * which is told so.
     */
    private val pendingSelections = mutableMapOf<TrackKind, SelectionRequest>()

    /** A variant choice waiting for the rebuild that reopens the item on it, or null (#376). */
    private var pendingVariant: VariantRequest? = null

    private class VariantRequest(val index: Int?, val reply: CompletableDeferred<Unit>)

    /** A programme choice waiting for the rebuild that reopens the item on it, or null (#505). */
    private var pendingProgram: ProgramRequest? = null

    private class ProgramRequest(val number: Int?, val reply: CompletableDeferred<Unit>)

    /** True while a variant or programme change waits for its rebuild. */
    private val reopenPending: Boolean get() = pendingVariant != null || pendingProgram != null

    /** True when the item's variant is the player's own step down, which it may lower again. */
    private var variantChosenByPlayer = false

    /**
     * True while the primary subtitle is the one the open chose by its rules, which read the audio,
     * so an audio change chooses it again (#506). A selection by the viewer or the application, or a
     * file added or flagged to be shown, makes the subtitle theirs, and it stays through every
     * audio change.
     */
    private var subtitleChosenByPlayer = false

    /** True when a caller's last sound choice for this item was none. Actor only. */
    private var soundOffByViewer = false

    /** True when a caller's last picture choice for this item was none (#527). Actor only. */
    private var pictureOffByViewer = false

    /** When playback began to wait for data while playing, or [NO_POSITION]. */
    private var starvedSinceNanos: Long = NO_POSITION

    /** The player tries no higher variant before this instant, or [NO_POSITION] for no wait (#376). */
    private var stepUpNotBeforeNanos: Long = NO_POSITION

    /** The wait after a step down before a step up. It doubles when a step up was followed by one. */
    private var stepUpWait: Duration = VARIANT_STEP_UP_WAIT

    /** True when the player's last variant change was a step up. */
    private var lastStepWasUp = false

    /** One caller's track selection, waiting for the rebuild that will honour it. */
    private class SelectionRequest(
        val kind: TrackKind,
        val track: TrackId?,
        val reply: CompletableDeferred<TrackChange>,
        /** True for the player's own choice, which no caller asked for and nobody awaits (#506). */
        val automatic: Boolean = false,
    )

    private var pendingVideoRecovery: VideoRecovery? = null
    private var videoRecoveryAttempted: Boolean = false
    private var forceBackendSoftwareForMedia: Boolean = false
    private var nextSessionToken: Long = 1L

    private var demuxUnderrunSeen = false
    private var rebuffers = 0L

    /**
     * The counters of every session this player has finished with.
     *
     * [PlaybackStats] documents its frame figures as monotonic totals, and they were read straight
     * off the live session, which is a NEW object after every track switch, decoder recovery,
     * queue advance and loop. A viewer who changed the audio track watched every total in an
     * overlay fall back to zero. The published figure is now this plus whatever the live session
     * has reached, so it only ever grows, and the per-session gauges beside it (queue depths,
     * drift, frames per second) stay per-session because that is what a gauge is.
     */
    private var retiredDecodedVideo = 0L
    private var retiredSubmitted = 0L
    private var retiredHeadless = 0L
    private var retiredDroppedLate = 0L
    private var retiredDroppedDecode = 0L
    private var retiredRefused = 0L
    private var retiredRepeated = 0L
    private var retiredUnderruns = 0L
    private var retiredLimited = 0L
    private var stillImageShownSinceNanos: Long = 0
    private var stillImageFinished = false
    private var firstFrameSeen = false
    private var openedAtNanos: Long = 0
    private var lastProgressAtNanos: Long = 0
    private var lastStatsAtNanos: Long = 0
    private var lastStatsDecoded: Long = 0

    /* Rising-edge state for the two counter-backed warnings: warned when the
     * counter MOVED this stats interval, so the history records onsets rather than flooding. */
    private var lastStatsUnderruns = 0L
    private var lastStatsIoBytes = 0L

    /** Bytes read by sessions that have already closed, so a reopen does not reset the total. */
    private var retiredIoBytes = 0L

    private var lastStatsDroppedLate = 0L

    /** The deadline this pass may sleep until. Handlers lower it; nothing raises it. */
    private var wakeAtNanos: Long = 0

    /** Read from any thread, so it is published rather than computed on demand. */
    private val publishedPositionMicros = atomic(0L)

    /** Independent of the video epoch: an in-place audio switch retires analysis too. */
    private var audioGeneration = Generation.Initial
    private val publishedAudioClock = atomic(AudioClockSnapshot.unavailable(audioGeneration))

    /**
     * The newest requested seek target, masking [publishedPositionMicros] until the seek machine
     * drains. Written at the public entry points (an absolute target needs no session state) and
     * again at acceptance with the merged request's resolution; cleared by [handlePlaybackTime] on
     * the first pass with nothing queued, and by every teardown that zeroes the position. Without
     * it, every poll between a request and its landing reads the old advancing clock, which a
     * seek bar renders as the thumb snapping back before it jumps to the destination. The landing
     * still writes only [publishedPositionMicros], so a stale landing can never overwrite the mask
     * of a newer request.
     */
    private val maskedSeekTargetMicros = atomic(NO_SEEK_MASK)

    // Observable-for-tests counters. Everything here is written by the actor only.
    var loopPasses: Long = 0
        private set
    var seekFlushCycles: Long = 0
        private set
    /** Subtitle operation counters: monotonic, actor-owned, and intentionally internal. */
    var subtitlePacketAttempts: Long = 0
        private set
    var subtitleMaxPacketAttemptsPerPass: Int = 0
        private set
    var subtitleCueAppendBatches: Long = 0
        private set
    var subtitleCueMergeBatches: Long = 0
        private set
    var subtitlePruneScans: Long = 0
        private set
    val endOfStream: EndOfStreamState = EndOfStreamState()

    /** Where the seek machine is, for the test that drives it. */
    val phase: SeekPhase get() = seekPhase

    /** The schedule's current display interval; read by tests, written at attach and on VsyncChanged. */
    val videoScheduleVsyncNanos: Long? get() = session?.video?.vsyncIntervalNanos

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
    /** Every status the player has had, from its first Idle, when [recordTransitions] is on, and empty otherwise. */
    val statusHistory: List<PlaybackStatus> get() = recordedStatuses

    /** Every transition the status machine forbids, as "from to to", when [recordTransitions] is on. */
    val illegalTransitions: List<String> get() = recordedIllegal

    private val recordedStatuses = if (recordTransitions) mutableListOf(PlaybackStatus.Idle) else mutableListOf()
    private val recordedIllegal = mutableListOf<String>()

    /** Notes the move from [from] to [to] for the tests that read it. A player keeps nothing. */
    private fun recordTransition(from: PlaybackStatus, to: PlaybackStatus) {
        if (!recordTransitions) return
        if (!StatusMachine.isLegal(from, to)) recordedIllegal += "$from to $to"
        recordedStatuses += to
    }

    /** Called with each handler's name as it runs, so a test can record the real order. */
    var onHandlerRun: ((String) -> Unit)? = null

    private class Handler(val name: String, val run: suspend () -> Unit)

    /**
     * The pass, in the one order it ever runs in.
     *
     * Order is data because it is a contract.
     */
    private val handlers: List<Handler> = listOf(
        Handler("drainCommands") { drainCommands() },
        Handler("handleLateStreams") { handleLateStreams() },
        Handler("handleTrackChanges") { handleTrackChanges() },
        Handler("handleAudioFill") { handleAudioFill() },
        Handler("handleQueueHandoff") { handleQueueHandoff() },
        Handler("handleVideoWrite") { handleVideoWrite() },
        Handler("handlePlaybackRestart") { handlePlaybackRestart() },
        Handler("handlePlaybackTime") { handlePlaybackTime() },
        Handler("handleBuffering") { handleBuffering() },
        Handler("handleSubtitles") { handleSubtitles() },
        Handler("handleEof") { handleEof() },
        Handler("handleLoop") { handleLoop() },
        Handler("handleExternalClock") { handleExternalClock() },
        Handler("handleLiveDelay") { handleLiveDelay() },
        Handler("handleSleepTimer") { handleSleepTimer() },
        Handler("handleQueueAdvance") { handleQueueAdvance() },
        Handler("handleQueuedSeek") { handleQueuedSeek() },
        Handler("publishSnapshot") { publishSnapshotIfDirty() },
        Handler("awaitWork") { awaitWork() },
    )

    /** The declared order, for the test that asserts it against the design. */
    val handlerOrder: List<String> = handlers.map { it.name }

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

    suspend fun openQueue(items: List<MediaItem>, startIndex: Int) {
        val reply = CompletableDeferred<Unit>()
        // The command copies the list as it is made, before anything can suspend (#409).
        send(CoreCommand.OpenQueue(items, startIndex, reply))
        awaitReply(reply, stopOnCancellation = true)
    }

    /**
     * Draws whatever subtitles were showing, at the captured frame's own size.
     *
     * Null when nothing was showing, when this build has no platform rasterizer, when the
     * frame's size is not a size, or when the rasterizer failed. A screenshot without its subtitles
     * is half a screenshot, and a screenshot with the text laid out for a screen of a different
     * shape is worse than either. The limits of the on-screen overlay hold here too.
     */
    private suspend fun rasterizeOverlayFor(
        captured: io.github.yuroyami.kiteplayer.CapturedFrame,
    ): io.github.yuroyami.kiteplayer.spi.SubtitleOverlay? {
        val rasterizer = output.subtitleRasterizer ?: return null
        val cues = subtitleCues.value
        if (cues.isEmpty()) return null
        // What the screen showed of the picture, so the text sits where it did once the caller crops.
        val shown = captured.size.cropped(captured.crop)
        val width = shown.displayWidth
        val height = shown.height
        if (width <= 0 || height <= 0) return null
        val images = withContext(dispatchers.raster) {
            rasterizer.rasterizeWithinLimits(
                io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea.None,
                applyOverride(cues, subtitleStyle), width, height, subtitleScale, subtitlePosition,
                ::warnUndrawnSubtitles,
            )
        }
        if (images.isNullOrEmpty()) return null
        return io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
            images = images,
            viewportWidth = width,
            viewportHeight = height,
            contentHash = captured.pts.micros,
        )
    }

    /** Decodes one audio track without playing it, with playback's reader rules. See [io.github.yuroyami.kiteplayer.KitePlayer.scanAudio]. */
    suspend fun scanAudio(media: MediaItem, track: io.github.yuroyami.kiteplayer.TrackId?,
        range: io.github.yuroyami.kiteplayer.AudioScanRange?,
        sink: io.github.yuroyami.kiteplayer.AudioScanSink): io.github.yuroyami.kiteplayer.AudioScanResult {
        // The backend playback itself opens through, so a scan decodes exactly what playback would.
        val io = resolveMediaIo(media, config.network)
        val item = if (io == null) media else media.copy(io = { io })
        return try {
            scanMediaAudio(backend, item, track, config.audio.preferredLanguages, range, sink)
        } catch (failure: Throwable) {
            // A reader the backend never took over is closed here; close tolerates a second call.
            if (io != null) runCatching { io.close() }
            throw failure
        }
    }

    /** Reads a file's own facts without opening playback. See [io.github.yuroyami.kiteplayer.KitePlayer.inspect]. */
    suspend fun inspect(media: MediaItem): MediaInspection {
        // The backend and the reader open would use, so an item that plays can be inspected (#202).
        val io = resolveMediaIo(media, config.network)
        val item = if (io == null) media else media.copy(io = { io })
        return try {
            inspectMedia(backend, item)
        } catch (failure: Throwable) {
            // A reader the backend never took over is closed here; close tolerates a second call.
            if (io != null) runCatching { io.close() }
            throw failure
        }
    }

    suspend fun queueNext() {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.QueueNext(reply))
        awaitReply(reply, stopOnCancellation = true)
    }

    suspend fun queuePrevious() {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.QueuePrevious(reply))
        awaitReply(reply, stopOnCancellation = true)
    }

    /**
     * The four queue edits.
     *
     * Only a removal of the item that is playing owns the session, because only it opens
     * something; the other three rearrange a list and cancel on their own.
     */
    suspend fun addToQueue(items: List<MediaItem>, index: Int? = null) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.EditQueue(QueueEdit.Add(items.toList(), index), reply))
        awaitReply(reply)
    }

    suspend fun removeFromQueue(index: Int) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.EditQueue(QueueEdit.Remove(index), reply))
        awaitReply(reply, stopOnCancellation = true)
    }

    suspend fun moveInQueue(from: Int, to: Int) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.EditQueue(QueueEdit.Move(from, to), reply))
        awaitReply(reply)
    }

    suspend fun clearQueue() {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.EditQueue(QueueEdit.Clear, reply))
        awaitReply(reply)
    }

    /**
     * Cancellable on its own, like every request that does not own the session.
     *
     * The step has already been accepted, and a backward step is an ordinary seek; abandoning the
     * wait abandons the answer, not the position and not the player.
     */
    suspend fun stepFrame(direction: StepDirection = StepDirection.Forward) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.StepFrame(direction, reply))
        awaitReply(reply)
    }

    suspend fun startRecording(path: String) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.StartRecording(path, reply))
        awaitReply(reply)
    }

    suspend fun stopRecording() {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.StopRecording(reply))
        awaitReply(reply)
    }

    suspend fun captureFrame(withSubtitles: Boolean = false): io.github.yuroyami.kiteplayer.CapturedFrame {
        val reply = CompletableDeferred<io.github.yuroyami.kiteplayer.CapturedFrame>()
        send(CoreCommand.CaptureFrame(reply))
        try {
            val captured = reply.await()
            if (!withSubtitles) return captured
            // Drawn AFTER the frame is in hand, because the layout depends on the frame's own
            // size, which nothing knows until the frame arrives. The raster lane owns every
            // typesetter call, so this goes through the same door the on-screen overlay does.
            return captured.withOverlay(rasterizeOverlayFor(captured))
        } catch (cancellation: CancellationException) {
            // A cancelled capture withdraws ITS OWN arm and nothing else. Posting Stop here, the
            // way the session-owning commands do, meant abandoning a screenshot killed playback
            // A newer capture that already replaced this arm is left alone.
            if (!closedNow.value) commands.trySend(CoreCommand.WithdrawCapture(reply))
            throw cancellation
        }
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

    /**
     * Which keyframe a plain keyframe seek lands on (#496). Read when a request is made, so each
     * request keeps the setting it was made under however long it waits; it holds no session state.
     */
    private val keyframeChoice = atomic(config.keyframeChoice)

    /** Sets [keyframeChoice] for the seek requests made from now on. */
    fun setKeyframeChoice(choice: KeyframeChoice) {
        keyframeChoice.value = choice
    }

    /** The [KeyframeChoice] the next seek request takes. */
    val currentKeyframeChoice: KeyframeChoice get() = keyframeChoice.value

    /** Seeks and returns what happened to this request: it landed, or a later request replaced it. */
    suspend fun seek(to: Pts, mode: SeekMode): SeekResult {
        val reply = CompletableDeferred<SeekResult>()
        maskedSeekTargetMicros.value = maskFor(to)
        send(CoreCommand.Seek(SeekRequest(SeekTarget.Absolute(to), mode, keyframe = keyframeChoice.value), reply))
        return awaitReply(reply)
    }

    /** Fire and forget, coalescing by contract. What a seek bar drag calls sixty times a second. */
    fun seekLater(to: Pts, mode: SeekMode) {
        checkOpenFor("requestSeek")
        // The mask is set from this very call, not from the actor's next pass: a fire-and-forget
        // caller polls position() in the gap before the command is drained, and an absolute target
        // needs no session state to name it. A request the drain drops withdraws the mask there.
        maskedSeekTargetMicros.value = maskFor(to)
        commands.trySend(CoreCommand.SeekLater(SeekRequest(SeekTarget.Absolute(to), mode, keyframe = keyframeChoice.value)))
    }

    /**
     * What the mask may answer for [to]: the request, but never a time the media does not have.
     *
     * The mask exists so a caller polling position() through a queued seek reads the timeline it
     * asked for rather than the one it is leaving. That is only true while the request is
     * reachable: a target past the end resolves to the end, so answering the raw request reports a
     * position the media never has, and a seek bar drawn from it sits past its own maximum
     * (owner report 2026-08-23, a shared playlist seeking a short file to a long file's position).
     */
    private fun maskFor(to: Pts): Long {
        // An estimated length is no end to cut at (#422).
        if (snapshotState.value.durationIsEstimate) return to.micros
        val durationUs = snapshotState.value.duration?.inWholeMicroseconds ?: return to.micros
        return to.micros.coerceAtMost(durationUs)
    }

    /**
     * Seeks by an offset from where playback is. What an arrow key produces.
     *
     * Relative because that is what the merge rules are for: holding the key down must move by the total
     * of the presses, not by the last one, and that only works if the request keeps its shape until the
     * moment it is resolved against a position.
     */
    fun seekByLater(offset: Duration, mode: SeekMode) {
        checkOpenFor("requestSeek")
        commands.trySend(CoreCommand.SeekLater(SeekRequest(SeekTarget.Relative(offset), mode, keyframe = keyframeChoice.value)))
    }

    /** Seeks to a fraction of the duration. What dragging a seek bar produces. */
    fun seekToFractionLater(fraction: Double, mode: SeekMode) {
        require(fraction.isFinite() && fraction >= 0.0 && fraction <= 1.0) {
            "a seek bar position must be between 0 and 1, was $fraction"
        }
        checkOpenFor("requestSeek")
        commands.trySend(CoreCommand.SeekLater(SeekRequest(SeekTarget.Factor(fraction), mode, keyframe = keyframeChoice.value)))
    }

    suspend fun stop() {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.Stop(reply))
        awaitReply(reply)
    }

    suspend fun selectTrack(kind: TrackKind, track: TrackId?): TrackChange {
        val reply = CompletableDeferred<TrackChange>()
        send(CoreCommand.SelectTrack(kind, track, reply))
        return awaitReply(reply)
    }

    suspend fun selectVariant(index: Int?) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SelectVariant(index, reply))
        awaitReply(reply)
    }

    suspend fun selectProgram(number: Int?) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SelectProgram(number, reply))
        awaitReply(reply)
    }

    suspend fun selectSecondarySubtitle(track: TrackId?): TrackChange {
        val reply = CompletableDeferred<TrackChange>()
        send(CoreCommand.SelectSecondarySubtitle(track, reply))
        return awaitReply(reply)
    }

    suspend fun attachRenderer(renderer: VideoRenderer) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.AttachRenderer(renderer, reply))
        awaitReply(reply)
    }

    /** Returns only once no submission to the renderer being detached is outstanding. */
    suspend fun detachRenderer(expected: VideoRenderer? = null) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.DetachRenderer(expected, reply))
        awaitReply(reply)
    }

    suspend fun setSpeed(value: Double) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SetSpeed(value, reply))
        awaitReply(reply)
    }

    suspend fun setExternalClock(clock: io.github.yuroyami.kiteplayer.ExternalClock?) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SetExternalClock(clock, reply))
        awaitReply(reply)
    }

    suspend fun addExternalSubtitle(source: SubtitleSource): TrackId {
        val reply = CompletableDeferred<TrackId>()
        send(CoreCommand.AddExternalSubtitle(source, reply))
        return try {
            awaitReply(reply)
        } catch (cancellation: CancellationException) {
            // The caller left, so the read it asked for is cancelled too, and nothing is added (#412).
            reply.cancel(cancellation)
            throw cancellation
        }
    }

    suspend fun reloadExternalSubtitle(track: TrackId, encoding: String?) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.ReloadExternalSubtitle(track, encoding, reply))
        try {
            awaitReply(reply)
        } catch (cancellation: CancellationException) {
            // As for an add: the caller left, so the read stops and the track stays as it was.
            reply.cancel(cancellation)
            throw cancellation
        }
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

    suspend fun setShuffle(enabled: Boolean, seed: Long? = null) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SetShuffle(enabled, seed, reply))
        awaitReply(reply)
    }

    /** Turns shuffle on with [order] as the play order, as a memento saved it. */
    suspend fun restoreQueueOrder(order: List<Int>) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.RestoreQueueOrder(order.toList(), reply))
        awaitReply(reply)
    }

    suspend fun setMarkers(markers: List<Marker>) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.SetMarkers(markers.toList(), reply))
        awaitReply(reply)
    }

    /** The position as of the last pass, which is never more than the wake floor old; while a seek
     * request is in flight, the newest requested target, which is the timeline the caller asked for. */
    fun position(): Duration {
        val masked = maskedSeekTargetMicros.value
        return (if (masked != NO_SEEK_MASK) masked else publishedPositionMicros.value).microseconds
    }

    /** One atomic mapping read, projected to the host instant of this call. */
    fun audioClock(): AudioClockSnapshot {
        val snapshot = publishedAudioClock.value
        return if (snapshot.isValid) snapshot.at(clock.nanos()) else snapshot
    }

    /** Terminal and idempotent. Atomically requests the shared close and returns without awaiting it. */
    override fun close() {
        requestClose()
    }

    /**
     * Terminal and idempotent, awaited through the one result shared with [close].
     *
     * @throws PlaybackException with [PlaybackError.RuntimeCompromised] when teardown did not finish
     *         inside its deadline, the Close command could not be queued, the actor terminated before
     *         handing off its outcome, an owned dispatcher did not close, or the independent finalizer
     *         failed. The non-cancellable worker ownership join cannot be cut short by that deadline; a
     *         wedged native call can therefore outlive it and require process termination.
     */
    suspend fun closeAndAwait() {
        requestClose()
        val reportedFailure = try {
            terminalCloseResult.await()
            null
        } catch (cancellation: CancellationException) {
            // This waiter goes away; the parentless result and actor-owned teardown do not.
            throw cancellation
        } catch (failure: Throwable) {
            failure
        }
        // Join on both non-cancelled outcomes. The result is settled only after terminal cleanup, but
        // actor completion is the ownership proof that no tail of the loop remains.
        actor.join()
        reportedFailure?.let { throw it }
    }

    /** Returns once a close has been asked for, at once when it already was. Never closes anything. */
    suspend fun awaitCloseRequest() {
        closeAsked.await()
    }

    private fun requestClose() {
        if (!closedNow.compareAndSet(expect = false, update = true)) return
        closeAsked.complete(Unit)
        if (!commands.trySend(CoreCommand.Close(terminalCloseResult)).isSuccess) {
            terminalCloseOutcome.compareAndSet(
                expect = null,
                update = TerminalCloseOutcome(
                    reply = terminalCloseResult,
                    failure = compromisedClose("the terminal Close command could not be queued"),
                ),
            )
            // Make the completion hook perform the same independent dispatcher finalization as every
            // other abort. The result is never settled early on the caller's thread.
            actor.cancel()
        }
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
        checkOpenFor(command.name)
        // The lifecycle check and the send are two steps; a close landing between them used to
        // drop the command silently. The failed send now completes the reply
        // exceptionally, so even a fire-and-forget caller that chooses to await learns the truth.
        if (!commands.trySend(command).isSuccess) {
            command.fail(closedCommand(command.name))
        }
    }

    private suspend fun send(command: CoreCommand) {
        if (closedNow.value) {
            command.fail(closedCommand(command.name))
            return
        }
        if (!commands.trySend(command).isSuccess) {
            command.fail(closedCommand(command.name))
        }
    }

    private fun checkOpenFor(command: String) {
        if (closedNow.value) throw closedCommand(command)
    }

    private fun closedCommand(command: String): IllegalStateException =
        IllegalStateException("the player is closed, so $command cannot run")

    /**
     * Awaits one reply.
     *
     * [stopOnCancellation] belongs to the commands that OWN the session, which is open, openQueue
     * and the two queue jumps: cancelling one of those mid-flight can leave a half-built graph, so
     * the actor is told to stop. Every other request is cancellable on its own, and posting a
     * global Stop for one of them is how abandoning a screenshot used to kill playback.
     */
    private suspend fun <T> awaitReply(reply: CompletableDeferred<T>, stopOnCancellation: Boolean = false): T {
        try {
            return reply.await()
        } catch (cancellation: CancellationException) {
            // The caller went away. Nothing half built may be left behind, and cancellation is never
            // reported as a playback failure.
            if (stopOnCancellation && !closedNow.value) {
                // Scoped to this request: the actor stops only a session this reply built (#410).
                commands.trySend(CoreCommand.Stop(CompletableDeferred(), owner = reply))
            }
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
                if (closedNow.value) settleOutstandingForClose()
                // Parent-scope cancellation is every bit as terminal as Close. The actor still owns
                // the installed graph here, so it must release that graph before its completion hook
                // takes over terminal publication and dispatcher shutdown.
                withContext(NonCancellable) { teardownSession() }
                throw cancellation
            } catch (failure: Throwable) {
                if (closedNow.value) {
                    // Once close is linearized, ordinary recovery would keep the actor alive and leave
                    // the shared terminal result pending. Reject every outstanding command, then let the
                    // actor completion hook report the compromised close.
                    settleOutstandingForClose()
                    throw failure
                }
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
        return heldCommands.any { (it is CoreCommand.Stop && stopApplies(it)) || it is CoreCommand.Close }
    }

    /**
     * Whether [stop] is for the session that is open or being built: a plain stop always is, and a
     * cancelled request's stop only while the session is still the one that request built.
     */
    private fun stopApplies(stop: CoreCommand.Stop): Boolean = stop.owner == null || stop.owner === sessionOwner

    /**
     * True when a NEWER seek is waiting in the mailbox (owner report 2026-08-26).
     *
     * The running seek asks this from its long waits for the same reason it asks [preempted]:
     * finishing a landing nobody wants any more is wall-clock the newest request pays for. A
     * scrub is many requests in quick succession, and digesting each one fully made the player
     * run seconds behind the finger on slow hardware, on both phones. mpv answers a new seek by
     * abandoning the one in flight; this is that rule. Drains like [preempted], so every command
     * taken off the channel is held for the next [drainCommands] in order.
     */
    private fun seekSuperseded(): Boolean {
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: break
            heldCommands.addLast(command)
        }
        return heldCommands.any { it is CoreCommand.Seek || it is CoreCommand.SeekLater }
    }

    /**
     * Captures work that arrived while an inline actor handler was running.
     *
     * Subtitle decoding is deliberately actor-confined, but confinement is not permission to
     * monopolise the actor. Taking one item from each mailbox preserves their normal drain order
     * in the held queues and lets the subtitle handler return at the next packet/output boundary.
     */
    private fun actorWorkWaiting(): Boolean {
        if (heldOutcomes.isNotEmpty() || heldCommands.isNotEmpty()) return true
        outcomes.tryReceive().getOrNull()?.let(heldOutcomes::addLast)
        commands.tryReceive().getOrNull()?.let(heldCommands::addLast)
        return heldOutcomes.isNotEmpty() || heldCommands.isNotEmpty()
    }

    private suspend fun drainCommands() {
        while (true) {
            val outcome = heldOutcomes.removeFirstOrNull() ?: outcomes.tryReceive().getOrNull() ?: break
            snapshotDirty = true
            handleWorkerOutcome(outcome)
            if (terminated) return
        }
        while (true) {
            val command = heldCommands.removeFirstOrNull() ?: commands.tryReceive().getOrNull() ?: break
            snapshotDirty = true
            try {
                execute(command)
            } catch (failure: Throwable) {
                // The command is no longer in a queue for close settlement to find. Preserve the
                // exactly-once reply contract before the loop turns the same failure into terminal close.
                if (command !is CoreCommand.Close) {
                    val replyFailure = when (failure) {
                        is CancellationException, is PlaybackException -> failure
                        else -> PlaybackException(
                            PlaybackError.Internal("the ${command.name} command failed", failure),
                        )
                    }
                    command.fail(replyFailure)
                }
                throw failure
            }
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
            is CoreCommand.StartRecording -> when {
                session == null -> IllegalStateException("startRecording needs an open media item")
                session?.source !is RecordingCapable -> UnsupportedOperationException(
                    "this backend cannot record: its source does not implement RecordingCapable",
                )
                else -> null
            }
            is CoreCommand.SelectVariant -> when {
                session == null -> IllegalStateException("selectVariant needs an open media item")
                session?.source?.seekable != true -> UnsupportedOperationException(
                    "this source is not seekable, so a variant change cannot reopen and seek back to where playback was",
                )
                command.index != null && tracks.variants.none { it.index == command.index } ->
                    IllegalArgumentException("the media has no variant ${command.index}")
                else -> null
            }
            is CoreCommand.SelectProgram -> when {
                session == null -> IllegalStateException("selectProgram needs an open media item")
                command.number != null && tracks.programs.none { it.number == command.number } ->
                    IllegalArgumentException("the media has no programme ${command.number}")
                // A live sender is opened again and joined where it is now. Anything else that
                // cannot seek may not be readable twice, as a pipe is not.
                session?.source?.let { it.seekable || it.realTime } != true -> UnsupportedOperationException(
                    "this source can neither seek nor be joined live, so a programme change cannot reopen it; " +
                        "open the item again with DemuxPolicy.program instead",
                )
                else -> null
            }
            is CoreCommand.SelectTrack -> when {
                command.kind == TrackKind.Subtitle && command.track != null &&
                    command.track == tracks.selectedSecondarySubtitle ->
                    IllegalArgumentException(
                        "subtitle track ${command.track} is already the secondary; one track cannot fill both slots",
                    )
                session == null && pendingVideoRecovery == null ->
                    IllegalStateException("selectTrack needs an open media item")
                // The sound's rule below, the other way round: a picture with no sound beside it
                // carries the clock, and a reopen without it failed the player for want of a stream.
                command.kind == TrackKind.Video && command.track == null && when {
                    session != null -> session?.videoStream != null && session?.audioLane == null
                    pendingVideoRecovery != null -> pendingVideoRecovery?.audio == StreamChoice.None
                    else -> false
                } ->
                    UnsupportedOperationException(
                        "the picture is the only timeline-carrying stream, so disabling it would leave no playable output",
                    )
                session != null && session?.source?.seekable != true && command.kind == TrackKind.Video &&
                    !pictureChangesInPlace(command.track) ->
                    UnsupportedOperationException(
                        "this source is not seekable, so a video-track switch cannot rebuild and seek " +
                            "back to where playback was; audio and subtitle tracks switch from live caches",
                    )
                command.kind == TrackKind.Audio && command.track == null && when {
                    session != null -> session?.videoStream == null
                    pendingVideoRecovery != null -> pendingVideoRecovery?.video == StreamChoice.None
                    else -> false
                } ->
                    UnsupportedOperationException(
                        "audio is the only timeline-carrying stream, so disabling it would leave no playable output",
                    )
                command.kind == TrackKind.Subtitle &&
                    command.track != null &&
                    !isExternalSubtitle(command.track) &&
                    session?.backendSession?.subtitleDecoders.isNullOrEmpty() &&
                    pendingVideoRecovery?.subtitleSelectionAvailable != true -> UnsupportedOperationException(
                    "this backend decodes no subtitle format, so a subtitle track cannot be selected",
                )
                // Canonicalized against the active session's own track set BEFORE any mutation:
                // an index of the wrong kind, or one this media does not have, used to silently
                // deselect or rebuild the wrong path.
                command.track != null && tracks.all.none { it.id == command.track && it.kind == command.kind } ->
                    IllegalArgumentException(
                        "${command.track} is not a ${command.kind} track of the current media; " +
                            "pass an id from tracks.all whose kind matches",
                    )
                else -> null
            }
            // The same status rule as Open: replacing what plays needs an explicit stop (#256).
            is CoreCommand.OpenQueue -> when {
                command.items.isEmpty() -> IllegalArgumentException("openQueue needs at least one item")
                command.startIndex !in command.items.indices -> IllegalArgumentException(
                    "startIndex ${command.startIndex} is outside the queue of ${command.items.size}",
                )
                status != PlaybackStatus.Idle && status != PlaybackStatus.Ended && status != PlaybackStatus.Failed ->
                    IllegalStateException(
                        "openQueue is legal from Idle, Ended and Failed; the player is $status, so call stop() first",
                    )
                else -> null
            }
            is CoreCommand.EditQueue -> queueEditRejection(command.edit)
            is CoreCommand.SetSpeed -> when {
                !command.value.isFinite() || command.value <= 0.0 ->
                    IllegalArgumentException("speed must be finite and positive, was ${command.value}")
                else -> null
            }
            is CoreCommand.SetVolume -> when {
                !command.value.isFinite() || command.value < 0f || command.value > volumeCeiling ->
                    IllegalArgumentException("volume must be between 0 and $volumeCeiling, was ${command.value}")
                else -> null
            }
            is CoreCommand.SetBalance -> when {
                !command.value.isFinite() || command.value < -1f || command.value > 1f ->
                    IllegalArgumentException("balance must be between -1 and 1, was ${command.value}")
                else -> null
            }
            is CoreCommand.SetSleepTimer -> when {
                command.fade < Duration.ZERO ->
                    IllegalArgumentException("a sleep-timer fade must not be negative, was ${command.fade}")
                command.timer is SleepTimer.After && command.timer.duration <= Duration.ZERO ->
                    IllegalArgumentException("a sleep timer must be set in the future, was ${command.timer.duration}")
                else -> null
            }
            else -> null
        }
    }

    /** True when a subtitle change swaps cue tables in place, needing no container reopen. */
    private fun inPlaceExternalSubtitleChange(command: CoreCommand.SelectTrack): Boolean =
        command.kind == TrackKind.Subtitle &&
            session?.subtitleStream == null &&
            (isExternalSubtitle(command.track) || (command.track == null && selectedExternalSubtitle != null))

    private fun seekRejection(): Throwable? = when {
        pendingVideoRecovery != null -> null
        session == null -> IllegalStateException("seek needs an open media item")
        session?.source?.seekable != true -> UnsupportedOperationException(
            "this source is not seekable, so there is no position to move the cursor to",
        )
        else -> null
    }

    private suspend fun execute(command: CoreCommand) {
        rejectionFor(command)?.let {
            // The caller's thread set the mask before this refusal. With no session, no pass will
            // clear it, and position() would answer the refused target (#255).
            if (command is CoreCommand.Seek) clearSeekMaskUnlessPending()
            command.fail(it)
            return
        }
        if (pendingNext != null && dropsPreload(command)) dropPending(null)
        when (command) {
            is CoreCommand.Open -> {
                // A plain open is single-media by contract: whatever queue existed is replaced.
                queueItems = emptyList()
                queueIndex = -1
                failedQueueIndices = emptySet()
                rebuildQueueOrder()
                runOpen(command)
            }
            is CoreCommand.OpenQueue -> {
                queueItems = command.items
                queueIndex = command.startIndex
                failedQueueIndices = emptySet()
                rebuildQueueOrder()
                openQueueItem(command.reply, step = 1)
            }
            is CoreCommand.QueueNext -> jumpQueue(neighbourInOrder(1), command.reply, "next")
            is CoreCommand.QueuePrevious -> jumpQueue(neighbourInOrder(-1), command.reply, "previous")
            is CoreCommand.EditQueue -> applyQueueEdit(command.edit, command.reply)
            is CoreCommand.SetShuffle -> {
                shuffleEnabled = command.enabled
                // A named seed restarts the sequence, so the same seed on the same queue gives the
                // same order every time; without one, the platform default carries on.
                if (command.enabled) {
                    shuffleRandom = command.seed?.let { Random(it) } ?: Random.Default
                }
                rebuildQueueOrder()
                publishSnapshot()
                command.reply.complete(Unit)
            }
            is CoreCommand.RestoreQueueOrder -> {
                // A saved shuffle continues where it was, instead of a fresh draw (#214). An order
                // that is not a permutation of this queue falls back to one.
                shuffleEnabled = true
                nextLapOrder = null
                if (command.order.sorted() == queueItems.indices.toList()) {
                    queueOrder = command.order
                } else {
                    rebuildQueueOrder()
                }
                publishSnapshot()
                command.reply.complete(Unit)
            }
            is CoreCommand.SetMarkers -> {
                markers = command.markers.sortedBy { it.position }
                publishSnapshot()
                command.reply.complete(Unit)
            }
            is CoreCommand.StepFrame -> when (command.direction) {
                StepDirection.Forward -> stepOneFrame(command.reply)
                StepDirection.Backward -> stepOneFrameBack(command.reply)
            }
            is CoreCommand.StartRecording -> {
                val recorder = session?.source as RecordingCapable
                try {
                    recorder.startRecording(command.path)
                    command.reply.complete(Unit)
                } catch (refusal: Exception) {
                    // A second start or a file that cannot be created. Playback goes on either way.
                    command.reply.completeExceptionally(refusal)
                }
            }
            is CoreCommand.StopRecording -> {
                session?.let { endRecording(it, reason = null) }
                command.reply.complete(Unit)
            }
            is CoreCommand.CaptureFrame -> requestCapture(command.reply)
            is CoreCommand.WithdrawCapture ->
                session?.video?.captureRequest?.compareAndSet(command.request, null)
            is CoreCommand.Play -> {
                // Idempotent in its own state, and queued rather than refused while opening or seeking:
                // the restart handler applies it as soon as the pipeline can honour it.
                playRequested = true
                // mpv's law (owner report 2026-08-17): play at the end IS a restart.
                // The intent flag was already true after a natural end, so pressing play changed
                // nothing and the player sat in Ended for ever. An unseekable source keeps
                // today's honest no-op: there is no way back to the beginning.
                if (status == PlaybackStatus.Ended && session?.source?.seekable == true) {
                    restartFrom(Pts.Zero)
                }
                command.reply.complete(Unit)
            }
            is CoreCommand.Pause -> {
                playRequested = false
                applyPause()
                command.reply.complete(Unit)
            }
            is CoreCommand.Seek -> queueSeek(command.request, command.reply)
            is CoreCommand.SeekLater -> if (
                session?.source?.seekable == true || pendingVideoRecovery != null
            ) {
                queueSeek(command.request, null)
            } else {
                // Dropped: the mask it set on the caller's thread goes with it (#255).
                clearSeekMaskUnlessPending()
            }
            is CoreCommand.Stop -> {
                // A cancelled request's stop that finds another request's session leaves it alone.
                if (stopApplies(command)) runStop()
                command.reply.complete(Unit)
            }
            is CoreCommand.Close -> runClose(command.reply)
            is CoreCommand.SelectSecondarySubtitle -> applySecondarySubtitle(command)
            is CoreCommand.SelectVariant -> {
                // The caller's choice, so the player does not lower it by itself.
                variantChosenByPlayer = false
                val asked = media?.demux?.variant
                if (command.index == asked && (command.index == null || command.index == tracks.selectedVariant)) {
                    command.reply.complete(Unit)
                } else {
                    // A later choice replaces one still waiting, which is told so by an error.
                    pendingVariant?.reply?.completeExceptionally(
                        IllegalStateException("a later selectVariant replaced this one before it applied"),
                    )
                    pendingVariant = VariantRequest(command.index, command.reply)
                }
            }
            is CoreCommand.SelectProgram -> {
                val asked = media?.demux?.program
                if (command.number == asked && (command.number == null || command.number == tracks.selectedProgram)) {
                    command.reply.complete(Unit)
                } else {
                    pendingProgram?.reply?.completeExceptionally(
                        IllegalStateException("a later selectProgram replaced this one before it applied"),
                    )
                    pendingProgram = ProgramRequest(command.number, command.reply)
                }
            }
            is CoreCommand.SelectTrack -> {
                // Commands apply in the order they were sent: a subtitle choice made after an add
                // whose file is still loading replaces the add's own selection, as it did when the
                // file was read inline, so the add is refused and adds no row (#412).
                if (command.kind == TrackKind.Subtitle) {
                    cancelSubtitleAcquisitions {
                        IllegalStateException("a later track selection replaced it before the subtitle file finished loading")
                    }
                    subtitleChosenByPlayer = false
                }
                // A sound that appears after the open never overrides a viewer who turned it off (#509).
                if (command.kind == TrackKind.Audio) soundOffByViewer = command.track == null
                // Nor does a picture override one who turned the picture off (#527).
                if (command.kind == TrackKind.Video) pictureOffByViewer = command.track == null
                traceUntilReplied(command.reply, "track", "switch") {
                    mapOf("kind" to command.kind.name, "track" to (command.track?.value?.toString() ?: "none"))
                }
                val externalTarget = command.track?.takeIf { isExternalSubtitle(it) }
                val externalActive = selectedExternalSubtitle != null
                if (command.kind == TrackKind.Subtitle &&
                    (externalTarget != null || (command.track == null && externalActive))
                ) {
                    if (session?.subtitleStream != null) {
                        // A container stream is timing cues: the ordinary rebuild deselects it,
                        // and the external table applies once the new graph stands.
                        pendingExternalSubtitle = externalTarget
                        queueSelection(command.kind, command.track, command.reply)
                    } else {
                        // No container stream involved: the swap is a cue-table replacement, in
                        // place, with no reopen and no seek.
                        applyExternalSubtitle(externalTarget)
                        command.reply.complete(TrackChange.Applied(command.kind, externalTarget))
                    }
                } else {
                    // A container selection while an external track times cues clears it: one
                    // subtitle selection exists, whoever owns it.
                    if (command.kind == TrackKind.Subtitle && externalActive) {
                        selectedExternalSubtitle = null
                        session?.subtitleCues?.clear()
                    }
                    // Applied by its own handler, so one pass never reopens the graph twice.
                    queueSelection(command.kind, command.track, command.reply)
                }
            }
            is CoreCommand.AttachRenderer -> {
                val previous = pendingRenderer
                val refusal = attachRefusal(command.renderer)
                if (refusal != null) {
                    // The working renderer stays. Warned as well as thrown, for the same reason as below.
                    warn(PlaybackWarning.CommandRefused("attachRenderer", refusal.message))
                    command.reply.completeExceptionally(PlaybackException(refusal))
                } else if (setRenderer(command.renderer)) {
                    command.reply.complete(Unit)
                    rendererSwapFollowUp(previous, command.renderer)
                } else {
                    // Warned as well as thrown: the facade's fire-and-forget form
                    // discards the reply, and a refused attach with no trace is a permanently
                    // black surface nothing explains.
                    val reason = "the video scheduler did not quiesce within $QUIESCE_DEADLINE"
                    warn(PlaybackWarning.CommandRefused("attachRenderer", reason))
                    command.reply.completeExceptionally(
                        IllegalStateException("renderer attach aborted: $reason"),
                    )
                }
            }
            is CoreCommand.DetachRenderer -> {
                if (command.expected != null && pendingRenderer !== command.expected) {
                    // Stale: something newer is attached, so nothing of the caller's is left to undo.
                    command.reply.complete(Unit)
                } else if (setRenderer(null)) {
                    command.reply.complete(Unit)
                } else {
                    val reason = "the video scheduler did not quiesce within $QUIESCE_DEADLINE"
                    warn(PlaybackWarning.CommandRefused("detachRenderer", reason))
                    command.reply.completeExceptionally(
                        IllegalStateException("renderer detach aborted: $reason"),
                    )
                }
            }
            is CoreCommand.AttachAudioTap -> {
                audioTaps.update { taps -> if (taps.any { it === command.tap }) taps else taps + command.tap }
                command.reply.complete(Unit)
            }
            is CoreCommand.DetachAudioTap -> {
                audioTaps.update { taps -> taps.filter { it !== command.tap } }
                command.reply.complete(Unit)
            }
            is CoreCommand.SetSpeed -> {
                val active = session
                // A live change needs no seek: the audio feeder applies the rate to the next buffer
                // it converts, the audio already in the ring plays out at the rate it was made at,
                // and the audio clock dates the boundary where the device reaches it. The video
                // schedule paces at the rate the clock reports, so it changes at the same moment.
                // Nothing stops, nothing is flushed, and an unseekable source changes speed too.
                val failure = runCatching {
                    active?.audio?.speed = pipelineSpeed(command.value)
                    active?.video?.speed = pipelineSpeed(command.value)
                }.exceptionOrNull()
                if (failure != null) {
                    command.reply.completeExceptionally(failure)
                } else {
                    speed = command.value
                    command.reply.complete(Unit)
                }
            }
            is CoreCommand.SetVolume -> {
                volume = command.value
                session?.audio?.volume = command.value
                command.reply.complete(Unit)
            }
            is CoreCommand.SetBalance -> {
                balance = command.value
                session?.audio?.balance = command.value
                command.reply.complete(Unit)
            }
            is CoreCommand.SetSleepTimer -> {
                sleepTimer = command.timer
                sleepFade = command.fade
                sleepRemainingNanos = (command.timer as? SleepTimer.After)?.duration?.inWholeNanoseconds ?: 0L
                sleepCountedAtNanos = if (status.isActive) clock.nanos() else NO_POSITION
                // Any change to the timer starts from full level. Cancelling has to undo a fade
                // that already started, and so does replacing: a later timer with more time left
                // than its fade never enters the fade branch, so the old multiplier would stay on
                // the ring and the listener hears a quarter of the volume with no way to see why.
                // A replacement already inside its own fade is brought back down on the next pass.
                session?.audio?.setFadeLevel(1f)
                command.reply.complete(Unit)
            }
            is CoreCommand.SetEqualizer -> {
                equalizer = command.settings
                session?.audio?.equalizer = command.settings
                command.reply.complete(Unit)
            }
            is CoreCommand.SetVideoEnabled -> {
                val changed = videoEnabled != command.value
                videoEnabled = command.value
                session?.videoParked?.value = !command.value
                /* Coming back, the decoder needs a keyframe: it has been fed nothing for a while
                 * and the next packet is mid group-of-pictures. A seekable source gets a precise
                 * seek to where playback already is, which flushes the lane and lands on a
                 * keyframe, so the picture returns at the right frame rather than at the next one
                 * the container happens to key. An unseekable source waits for that keyframe. */
                if (changed && command.value) {
                    session?.videoWaitingForKeyframe?.value = true
                    if (session?.source?.seekable == true && status.isActive) {
                        queueSeek(
                            SeekRequest(SeekTarget.Absolute(currentPosition()), SeekMode.Precise),
                            CompletableDeferred(),
                        )
                    }
                }
                command.reply.complete(Unit)
            }
            is CoreCommand.SetMuted -> {
                muted = command.value
                session?.audio?.muted = command.value
                command.reply.complete(Unit)
            }
            is CoreCommand.SetDuckLevel -> {
                duckLevel = command.level
                session?.audio?.setDuckLevel(command.level)
                command.reply.complete(Unit)
            }
            is CoreCommand.SetVideoScale -> {
                videoScale = command.mode
                // Whichever renderer is live learns immediately; the pending one learns so the
                // session that adopts it starts right; setRenderer re-tells any future one.
                session?.renderer?.setScaleMode(command.mode)
                if (session == null) pendingRenderer?.setScaleMode(command.mode)
                command.reply.complete(Unit)
            }
            is CoreCommand.SetVideoAdjustments -> {
                videoAdjustments = command.value
                // The same delivery law as the scale mode, because it is the same kind of value:
                // the engine's, honoured by whichever renderer is or becomes attached.
                session?.renderer?.setAdjustments(command.value)
                if (session == null) pendingRenderer?.setAdjustments(command.value)
                command.reply.complete(Unit)
            }
            is CoreCommand.SetRenderQuality -> {
                renderQuality = command.value
                session?.renderer?.setRenderQuality(command.value)
                if (session == null) pendingRenderer?.setRenderQuality(command.value)
                command.reply.complete(Unit)
            }
            is CoreCommand.SetExternalClock -> {
                externalClock = command.value
                resetExternalFollowing()
                command.reply.complete(Unit)
            }
            is CoreCommand.SetHdrPolicy -> {
                hdrPolicy = command.value
                session?.renderer?.setHdrPolicy(command.value)
                if (session == null) pendingRenderer?.setHdrPolicy(command.value)
                command.reply.complete(Unit)
            }
            // The same rule as the tone map warning: only after this open's first frame, so a report
            // about the previous item's last frames does not count for this one.
            is CoreCommand.ReportDynamicRange -> {
                if (firstFrameSeen) videoDynamicRange = command.value
                command.reply.complete(Unit)
            }
            is CoreCommand.SetVideoTransform -> {
                videoTransform = command.value
                session?.renderer?.setTransform(command.value)
                if (session == null) pendingRenderer?.setTransform(command.value)
                command.reply.complete(Unit)
            }
            is CoreCommand.SetSubtitleDelay -> {
                subtitleDelay = command.value
                // Retimed on the very next pass: dropping the published key forces the selector
                // to answer again and the overlay to republish at the shifted timing.
                session?.publishedCueKey = null
                command.reply.complete(Unit)
            }
            is CoreCommand.SetSubtitleScale -> {
                subtitleScale = command.value
                session?.publishedCueKey = null
                command.reply.complete(Unit)
            }
            is CoreCommand.SetSubtitleStyle -> {
                subtitleStyle = command.value
                // Re-rasterised on the very next pass, the same key-drop as a scale change.
                session?.publishedCueKey = null
                command.reply.complete(Unit)
            }
            is CoreCommand.SetSubtitlePosition -> {
                subtitlePosition = command.value
                // Re-rasterised on the very next pass, the same key-drop as a scale change.
                session?.publishedCueKey = null
                command.reply.complete(Unit)
            }
            is CoreCommand.SetSubtitleSafeArea -> {
                subtitleSafeArea = command.value
                // Re-rasterised on the very next pass, the same key-drop as a scale change.
                session?.publishedCueKey = null
                command.reply.complete(Unit)
            }
            is CoreCommand.SetAudioDelay -> {
                audioDelay = command.value
                // Nothing else to touch: the video schedule reads the biased master on its next
                // tick and SyncLaw walks the picture over within a frame or two, smoothly.
                command.reply.complete(Unit)
            }
            is CoreCommand.AddExternalSubtitle -> addExternalSubtitle(command)
            is CoreCommand.ReloadExternalSubtitle -> reloadExternalSubtitle(command)
            is CoreCommand.ExternalSubtitleRead -> command.adopt()
            is CoreCommand.StreamsChanged -> command.adopt()
            is CoreCommand.SetLoop -> {
                loop = command.mode
                command.reply.complete(Unit)
            }
            is CoreCommand.SetPreservePitch -> {
                // Live, like a speed change: the tempo stage settles onto an exact input frame and
                // carries on with the other law from there, with no seek and no seam.
                preservePitch = command.value
                session?.audio?.preservePitch = command.value
                command.reply.complete(Unit)
            }
            is CoreCommand.SetAbLoop -> {
                val active = session
                // The jump back is an ordinary precise seek, and an unseekable source has no way
                // to make one: the same refusal, for the same reason, as a live speed change.
                // Nobody awaits this reply, so the refusal is published as a warning too (#217).
                if (command.a != null && active != null && !active.source.seekable) {
                    val reason = "the A-B loop jumps back by precise seek, and this source is not seekable"
                    warn(PlaybackWarning.CommandRefused("setAbLoop", reason))
                    command.reply.completeExceptionally(UnsupportedOperationException(reason))
                } else {
                    abLoopA = command.a
                    abLoopB = command.b
                    command.reply.complete(Unit)
                }
            }
        }
    }

    /**
     * Attaches or detaches, with the fence detach promises. Returns whether the swap happened.
     *
     * The scheduler is parked before the delegate changes and released afterwards, so when this returns
     * true there is no submission to the old renderer outstanding anywhere. When the scheduler cannot
     * be parked within the deadline the swap is REFUSED: replacing the delegate under a scheduler that
     * may still be submitting to it is the use-after-free the fence exists to prevent, so the old
     * renderer stays attached and the caller gets an explicit failure.
     */
    private suspend fun setRenderer(renderer: VideoRenderer?): Boolean {
        // The scale mode, picture controls and framing survive renderer swaps: all belong to the
        // player, so whichever renderer arrives is told the ruling values before its first frame.
        renderer?.setScaleMode(videoScale)
        renderer?.setAdjustments(videoAdjustments)
        renderer?.setRenderQuality(renderQuality)
        renderer?.setHdrPolicy(hdrPolicy)
        renderer?.setTransform(videoTransform)
        val session = this.session
        if (session == null) {
            pendingRenderer = renderer
            watchRendererEvents(renderer)
            return true
        }
        val scheduler = session.videoScheduler
        if (scheduler != null) {
            if (!scheduler.quiesce(QUIESCE_DEADLINE)) {
                scheduler.release(requestedEpoch)
                return false
            }
            session.renderer.delegate = renderer
            scheduler.release(requestedEpoch)
        } else {
            session.renderer.delegate = renderer
        }
        // The display this renderer draws into, handed to the schedule while it is attached.
        session.video?.vsyncIntervalNanos = renderer?.vsyncIntervalNanos()
        pendingRenderer = renderer
        watchRendererEvents(renderer)
        return true
    }

    /**
     * What a successful renderer replacement still owes the picture. A decoder coupled to the
     * replaced renderer decodes into that renderer's dead surface, so the video path is rebuilt
     * against the new one through the ordinary track-change rebuild (same pass, position kept,
     * play state kept). A renderer that offers decoders the running path never asked takes over
     * through the same rebuild (#384). An uncoupled swap while not playing repaints once by
     * precise seek, because a parked scheduler presents nothing on its own.
     */
    private fun rendererSwapFollowUp(previous: VideoRenderer?, attached: VideoRenderer) {
        val active = session ?: return
        if (previous === attached) return
        val stream = active.videoStream ?: return
        val coupledElsewhere = active.videoDecoderOrigin == VideoDecoderOrigin.Renderer &&
            active.coupledRenderer !== attached
        if (coupledElsewhere) {
            // A waiting video selection already carries the rebuild; queueing another for the
            // same kind would supersede the caller's.
            if (TrackKind.Video in pendingSelections) return
            if (!active.source.seekable) {
                warn(
                    PlaybackWarning.CommandRefused(
                        "attachRenderer",
                        "the active video decoder is coupled to the replaced renderer and this " +
                            "source cannot seek, so the picture cannot follow the new renderer " +
                            "until the media is reopened",
                    ),
                )
                return
            }
            queueSelection(TrackKind.Video, TrackId(stream.index), CompletableDeferred())
            return
        }
        if (lateRendererCanDecode(active, stream, attached)) {
            if (TrackKind.Video in pendingSelections) return
            queueSelection(TrackKind.Video, TrackId(stream.index), CompletableDeferred())
            return
        }
        if (!playRequested && pendingSeek == null) {
            queueSeek(SeekRequest(SeekTarget.Absolute(currentPosition()), SeekMode.Precise), null)
        }
    }

    /**
     * True when [attached] offers decoders that the running video path never asked, and the policy
     * would try them. An open with no renderer, or with one that offered none, decodes on the
     * backend, which on Android copies every frame where the renderer's MediaCodec would write
     * straight to its surface. Only a rebuild lets a renderer that arrives later take over. A
     * session asks once, so a later renderer, or a path swap, does not rebuild it again.
     */
    private fun lateRendererCanDecode(active: OpenSession, stream: PlayerStreamInfo, attached: VideoRenderer): Boolean {
        if (active.videoDecoderOrigin != VideoDecoderOrigin.Backend || active.videoDecoderDeferred) return false
        if (active.rendererDecodersAsked || stream.isCoverArt) return false
        if (attached.videoDecoderFactories().isEmpty()) return false
        val selection = if (forceBackendSoftwareForMedia) {
            VideoDecoderSelection.BackendSoftwareOnly
        } else {
            VideoDecoderSelection.Configured
        }
        return rendererDecodes(selection, config.hardwareDecode, active.source.seekable)
    }

    /** A renderer attached before anything was open, kept for the session that follows. */
    private var pendingRenderer: VideoRenderer? = null

    /**
     * The attached audio taps. The actor adds and removes them and the feed worker drops one that
     * throws, so every change replaces the whole list and the worker reads it once per block.
     */
    private val audioTaps = atomic<List<AudioTap>>(emptyList())

    /** Hands one block to every attached tap and detaches any tap that throws. Runs on the feed worker. */
    private fun deliverToTaps(generation: Generation, pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
        val taps = audioTaps.value
        if (taps.isEmpty()) return
        for (tap in taps) {
            try {
                tap.onAudio(generation, pts, interleaved, frames, format)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                dropTap(tap, failure)
            }
        }
    }

    /** Tells every attached tap that what it holds is stale. Called wherever the engine flushes the ring. */
    private fun tapsDiscontinuous(active: OpenSession? = session) {
        audioGeneration = audioGeneration.next()
        active?.audioGeneration?.value = audioGeneration.value
        publishedAudioClock.value = AudioClockSnapshot.unavailable(audioGeneration, clock.nanos())
        for (tap in audioTaps.value) {
            try {
                tap.onDiscontinuity(audioGeneration)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                dropTap(tap, failure)
            }
        }
    }

    private fun dropTap(tap: AudioTap, failure: Throwable) {
        audioTaps.update { taps -> taps.filter { it !== tap } }
        warn(PlaybackWarning.AudioTapFailed(failure.message ?: failure::class.simpleName ?: "an exception with no message"))
    }

    /**
     * The renderer's event feed, finally collected: surface loss and hard
     * failure become typed warnings, so they reach the event flow, the bounded history and the
     * dump instead of being visible only as a frozen picture. One collector per attached
     * renderer; replacing or detaching cancels it, and the core's own scope ends it at close.
     */
    private var rendererEventsJob: kotlinx.coroutines.Job? = null

    /** Latches [PlaybackWarning.HdrToneMapped] to once per open. Reset with the session. */
    private var toneMapWarned: Boolean = false

    /**
     * Hands what a renderer says the screen shows to the actor, which owns the snapshot. A
     * renderer repeats its report while it lasts, so only a change costs a command.
     */
    private fun reportDynamicRange(range: io.github.yuroyami.kiteplayer.VideoDynamicRange) {
        if (range == videoDynamicRange) return
        commands.trySend(CoreCommand.ReportDynamicRange(range, CompletableDeferred()))
    }

    /** The renderer's colour limits already warned about this open, by detail. Reset with the session. */
    private val colorLimitsWarned = mutableSetOf<String>()

    /**
     * The video stream currently being fed to the renderer, or -1.
     *
     * Atomic because the renderer's event collector reads it and the actor writes it: a renderer
     * knows nothing about streams, so the engine is the one that can name the stream a tone map
     * happened on, and `session` itself is actor-confined and must not be read from that lane.
     */
    private val renderedStreamIndex = atomic(-1)

    private fun watchRendererEvents(renderer: VideoRenderer?) {
        rendererEventsJob?.cancel()
        rendererEventsJob = renderer?.let { attached ->
            scope.launch {
                attached.events.collect { event ->
                    when (event) {
                        is RendererEvent.SurfaceLost ->
                            warn(PlaybackWarning.NoRenderSurface(event.detail))
                        // A hard renderer failure used to be a warning and nothing else, so the
                        // schedule went on handing frames to a renderer that had already said it
                        // could not draw: the sound played and the picture stayed black for the
                        // rest of the session with nothing to be done about it.
                        // Detaching is the recovery this engine can actually perform. Playback
                        // continues headless, which is a state it already supports completely, the
                        // frames are counted as headless instead of refused, and the application
                        // is free to attach a working renderer whenever it has one.
                        is RendererEvent.Failed -> {
                            warn(
                                PlaybackWarning.RendererFailed(
                                    "${event.detail}; the renderer was detached and playback " +
                                        "continues without a picture until another is attached",
                                ),
                            )
                            // Identity-checked: a failure event that outlives its replacement
                            // must not remove the healthy renderer attached after it.
                            commands.trySend(CoreCommand.DetachRenderer(attached, CompletableDeferred()))
                        }
                        // Once per open, not once per frame: a renderer repeats this while it
                        // tone maps, and must not flood the warning feed for it. The latch is reset
                        // when a session opens, and the open's first frame must have come out, so
                        // an announcement about the previous item's last frames is not taken for
                        // this one; the renderer repeats it a second later.
                        is RendererEvent.ToneMapEngaged -> {
                            reportDynamicRange(io.github.yuroyami.kiteplayer.VideoDynamicRange.ToneMapped)
                            if (!toneMapWarned && firstFrameSeen) {
                                toneMapWarned = true
                                warn(
                                    PlaybackWarning.HdrToneMapped(
                                        transfer = event.transfer,
                                        // A renderer is handed frames, not streams, so most of
                                        // them answer -1 and the engine names the one it feeds.
                                        streamIndex = event.streamIndex.takeIf { it >= 0 }
                                            ?: renderedStreamIndex.value,
                                    ),
                                )
                            }
                        }
                        is RendererEvent.HdrShown -> reportDynamicRange(io.github.yuroyami.kiteplayer.VideoDynamicRange.High)
                        is RendererEvent.FramePresented -> if (config.frameEvents) {
                            val target = session?.video?.targetFor(event.pts.micros)
                            emitEvent(
                                PlayerEvent.FramePresented(
                                    pts = event.pts,
                                    atNanos = event.atNanos,
                                    latency = target?.let { (event.atNanos - it).nanoseconds } ?: Duration.ZERO,
                                    exact = event.exact,
                                ),
                            )
                        }
                        // Once per detail per open, for the same reason as the tone map above.
                        is RendererEvent.ColorApproximated ->
                            if (colorLimitsWarned.add(event.detail)) {
                                warn(PlaybackWarning.ColorApproximated(event.detail))
                            }
                        is RendererEvent.SurfaceAvailable -> Unit
                        is RendererEvent.VsyncChanged ->
                            // A display change reaches the running schedule without a reopen: a
                            // window dragged to another monitor, a phone dropping to 60 Hz.
                            session?.video?.vsyncIntervalNanos = event.intervalNanos
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Open.
    // ---------------------------------------------------------------------------------------------

    /**
     * Where the item asks to start, in microseconds, or null when the open starts where the
     * container does. An unhonourable request (unseekable source, position past the end) is
     * warned typed here rather than ignored silently or failed loudly: the media still plays,
     * from its own start, and the caller is told why.
     */
    private fun startPositionTargetUs(media: MediaItem, built: OpenSession): Long? {
        val requested = media.startPosition ?: return null
        if (requested <= Duration.ZERO) return null
        if (!built.source.seekable) {
            warn(PlaybackWarning.StartPositionIgnored(requested, "this source is not seekable"))
            return null
        }
        val durationUs = built.source.seekCeiling?.micros
        if (durationUs != null && requested.inWholeMicroseconds >= durationUs) {
            warn(PlaybackWarning.StartPositionIgnored(requested, "past the end of the media"))
            return null
        }
        return requested.inWholeMicroseconds
    }

    /**
     * What every open resets for a new item, in the order [runOpen] has always reset it, ending in
     * Opening. [epoch] is the epoch the new session's queues and decoders carry: the first one for
     * a fresh open, and the player's own for a preload that was aligned to it (#306).
     */
    private fun resetForOpen(item: MediaItem, epoch: Generation) {
        media = item
        variantChosenByPlayer = false
        subtitleChosenByPlayer = false
        soundOffByViewer = false
        pictureOffByViewer = false
        starvedSinceNanos = NO_POSITION
        stepUpNotBeforeNanos = NO_POSITION
        stepUpWait = VARIANT_STEP_UP_WAIT
        lastStepWasUp = false
        lastChapterIndex = Int.MIN_VALUE
        markerCursorUs = NO_POSITION
        markerCursorEpoch = null
        externalSubtitleTracks = emptyList()
        selectedExternalSubtitle = null
        selectedExternalSubtitle2 = null
        pendingExternalSubtitle = null
        // An open ends paused by contract, whatever was asked for before it. A play issued while this one
        // is still running arrives after this line and is honoured, which is what queueing it means.
        playRequested = false
        loopRefusalWarned = false
        toneMapWarned = false
        videoDynamicRange = io.github.yuroyami.kiteplayer.VideoDynamicRange.Standard
        colorLimitsWarned.clear()
        pendingVideoRecovery = null
        videoRecoveryAttempted = false
        forceBackendSoftwareForMedia = false
        seekPhase = SeekPhase.Idle
        // A pending request aims at the PREVIOUS timeline. Left in place, the
        // handler pass that follows this open would run it against the fresh media: a bar drag
        // on the finished episode became a jump into the next one. Its callers are answered
        // Superseded, exactly as runStop answers them, and the hold state dies with it so the
        // frame barrier never compares the new session against the old session's counters.
        pendingSeek = null
        seekHeldSinceNanos = 0
        lastSeekAtNanos = 0
        framesShownAtLastSeek = 0
        resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
        // The epoch exists to invalidate work still in flight inside ONE session, and the session it
        // referred to has just been torn down. Carrying its number into the replacement is what broke
        // every open after a seek: the queues and workers took the carried epoch while the new
        // decoders and schedule started at the initial one, so handOver dropped every frame decoded,
        // both startup deadlines expired on no picture, and playback landed in Ended at position zero
        // until some later seek happened to realign it (owner report 2026-08-22).
        requestedEpoch = epoch
        // For the same reason as the pending seek above: a waiting selection names a track id from
        // the PREVIOUS media's table, and running it against the new file would rebuild the wrong
        // stream or silently change nothing and report success.
        discardPendingSelections("a new media item was opened before the selection could be applied")
        maskedSeekTargetMicros.value = NO_SEEK_MASK
        publishedPositionMicros.value = 0L
        progressState.value = Progress(position = Duration.ZERO, bufferedAhead = Duration.ZERO)
        firstFrameSeen = false
        divergencesReported = false
        undrawnSubtitlesLimited.value = false
        endOfStream.reset()
        demuxUnderrunSeen = false
        stillImageFinished = false
        stillImageShownSinceNanos = 0
        openedAtNanos = clock.nanos()
        setStatus(PlaybackStatus.Opening)
    }

    /**
     * [absorb] is asked about a failure before the player fails on it. True means its caller moves
     * on: the session is torn down and nothing is published, and the reply is left for the caller.
     */
    private suspend fun runOpen(command: CoreCommand.Open, absorb: ((PlaybackError) -> Boolean)? = null) {
        traceUntilReplied(command.reply, "session", "open") { mapOf("uri" to redactUri(command.media.uri)) }
        sessionOwner = command.reply
        // Open is legal from Ended, and Ended keeps its session alive so the viewer can seek back.
        // That session must be fully torn down and awaited BEFORE the new one is installed:
        // overwriting the field would strand its source, workers, decoders, sink and queues live
        // but unreachable.
        if (session != null) teardownSession()
        resetForOpen(command.media, Generation.Initial)
        try {
            // The subtitle files are read FIRST, because whether one of them loads decides whether
            // the container's own subtitle stream should be selected at all. A file flagged
            // selectImmediately wins over the container's default, and the flag used to be honoured
            // only when no container subtitle happened to be active, which made an unconditional
            // promise conditional on the file. Choosing here rather than
            // afterwards means no rebuild and no moment where both selections exist.
            val parsedExternals = readOpenSubtitles(command.media)
            val immediateExternal = parsedExternals.firstOrNull { track ->
                command.media.externalSubtitles
                    .getOrNull(-track.id.value - 1)?.selectImmediately == true
            }
            val subtitleChoice = if (immediateExternal != null) StreamChoice.None else StreamChoice.Auto
            var built = buildSession(
                command.media, StreamChoice.Auto, StreamChoice.Auto, subtitleChoice,
                externalSubtitles = parsedExternals.map { it.info },
            )
            // An external file in a preferred language, picked over the container's own (#514).
            val preferredExternal = built.preferredExternalSubtitle?.let { id -> parsedExternals.firstOrNull { it.id == id } }
            session = built
            // Parked from the first packet when the player was configured that way, so an
            // audio-only application never decodes a frame it is going to throw away.
            built.videoParked.value = !videoEnabled
            // The item's start position, first half: the SOURCE is moved before the
            // workers start, while nothing reads it, so the initial fill decodes from the
            // keyframe at or before the target and nothing from the beginning of the media is
            // decoded, presented or heard. The exact landing is the second half below, made
            // cheap by this half: the refine walks forward within one group of pictures.
            val startTargetUs = startPositionTargetUs(command.media, built)
            // A loop armed before this open survives it, but cannot jump back on this source.
            if (abLoopA != null && !built.source.seekable) {
                warn(
                    PlaybackWarning.CommandRefused(
                        "setAbLoop",
                        "the armed A-B loop jumps back by precise seek, and this source is not seekable, " +
                            "so the loop does not run on this item",
                    ),
                )
            }
            if (startTargetUs != null) {
                withContext(dispatchers.demux) { built.source.seekToKeyframe(Pts(startTargetUs)) }
                publishedPositionMicros.value = startTargetUs
            }
            startWorkers(built)
            var recoveredAndPresented = false
            try {
                when (awaitInitialFill(built)) {
                    FillOutcome.WorkerFinished -> {
                        val observed = recoverObservedVideoFailure(built, Pts(startTargetUs ?: 0L))
                        if (observed == null) throw workerOutcomeException(built, "before the initial fill completed")
                        val recovered = observed.result ?: run {
                            command.reply.completeExceptionally(preemptedByTeardown("open"))
                            return
                        }
                        built = recovered.session
                        recoveredAndPresented = true
                    }
                    // A slow source is not a dead one: the session is real, so Opened stands, but the
                    // caller is told the pipeline was not primed instead of being left to infer it.
                    FillOutcome.TimedOut -> warn(
                        PlaybackWarning.StartupIncomplete("no stream reached readiness within $OPEN_FILL_DEADLINE"),
                    )
                    FillOutcome.Ready -> Unit
                    // A stop or a close is already on the channel. Everything below this point
                    // publishes Paused, announces Opened and completes the caller successfully,
                    // and the very next command then tears all of it down: the caller was told an
                    // open succeeded and was left with an Idle player.
                    FillOutcome.Preempted -> {
                        teardownSession()
                        command.reply.completeExceptionally(preemptedByTeardown("open"))
                        return
                    }
                }
                if (!recoveredAndPresented) reportFirstFrame(built, "open")
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                val observed = recoverObservedVideoFailure(built, Pts(startTargetUs ?: 0L)) ?: throw failure
                val recovered = observed.result ?: run {
                    command.reply.completeExceptionally(preemptedByTeardown("open"))
                    return
                }
                built = recovered.session
            }
            // presentFirstFrame itself stops on a preemption, so the check is repeated here: a stop
            // that arrived while the first frame was being pushed out must not be overtaken by the
            // success below either.
            if (preempted()) {
                teardownSession()
                command.reply.completeExceptionally(preemptedByTeardown("open"))
                return
            }
            // External subtitle files: read before the session was built, merged into the
            // container's table now that one exists, so a flagged one starts timing at once.
            adoptExternalSubtitles(command.media, parsedExternals)
            // Unconditional: when this is non-null the container's subtitle stream was left
            // unselected above, so there is never a competing selection to defer to.
            (immediateExternal ?: preferredExternal)?.let { applyExternalSubtitle(it.id) }
            subtitleChosenByPlayer = immediateExternal == null
            refreshTypesetting()
            // The start position's second half: the exact landing, as an ordinary precise seek
            // through the ordinary machine, so the masked position report, generation fencing
            // and pause preservation all hold without a special case.
            if (startTargetUs != null) {
                queueSeek(SeekRequest(SeekTarget.Absolute(Pts(startTargetUs)), SeekMode.Precise), null)
            }
            setStatus(PlaybackStatus.Paused)
            emitEvent(PlayerEvent.Opened(command.media, tracks))
            command.reply.complete(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (preempted: OpenPreempted) {
            // The stop or the close is next in the mailbox, and it finds nothing half built.
            teardownSession()
            command.reply.completeExceptionally(preemptedByTeardown("open"))
        } catch (failure: Throwable) {
            val error = classify(failure, command.media)
            teardownSession()
            if (absorb?.invoke(error) == true) return
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
    /**
     * How far an open got before it threw. Read only by [classify], written only below, and safe
     * as a plain field because every write and the read all happen on the session actor.
     */
    private enum class OpenStage {
        Source,
        Decoders,
        Output,
        Assembly,
        ;

        fun describe(): String = when (this) {
            Source -> "opening the source"
            Decoders -> "creating its decoders"
            Output -> "building its audio output"
            Assembly -> "assembling the session"
        }
    }

    private var openStage: OpenStage = OpenStage.Source

    private suspend fun buildSession(
        item: MediaItem,
        videoChoice: StreamChoice,
        audioChoice: StreamChoice,
        subtitleChoice: StreamChoice,
        videoSelection: VideoDecoderSelection = if (forceBackendSoftwareForMedia) {
            VideoDecoderSelection.BackendSoftwareOnly
        } else {
            VideoDecoderSelection.Configured
        },
        /** Set for the gapless preload, which must not touch the player while another item plays. */
        pending: PendingBuild? = null,
        /** The item's external subtitle tracks, which an open weighs against the container's own (#514). */
        externalSubtitles: List<TrackInfo> = emptyList(),
        /**
         * Where a preloaded pass of an A-B loop starts (#467). The source is moved to the keyframe
         * at or before it before anything is read, inside the build so its rollback covers the move.
         */
        startUs: Long = 0L,
    ): OpenSession {
        val report: (PlaybackWarning) -> Unit = pending?.report ?: ::warn
        fun stage(next: OpenStage) {
            if (pending == null) openStage = next
        }
        fun deselected(base: String): String = when {
            pending == null -> deselectionDetail(base)
            pending.failures.isEmpty() -> base
            else -> "$base (${pending.failures.joinToString("; ")})"
        }
        // The network resolver and the byte cache, both at the one place every open passes. The resolver
        // answers only for an item with a URI and no reader of its own; the cache wraps every
        // reader-fed open. On an open FAILURE a resolver-produced reader is the engine's to
        // close (the item's own reader stays the caller's, matching the backend's contract).
        stage(OpenStage.Source)
        // One reader per session, made HERE and owned here. The item carries a factory rather than
        // a live reader precisely because this line runs again for every rebuild: a track switch, a
        // decoder recovery, a loop and a queue returning to the same item all come back through it,
        // and the reader the previous session was given has been closed since.
        val suppliedIo = resolveReader(item, preemptible = pending == null)
        // A reader that recovers from a dropped connection says so through the player's warnings.
        suppliedIo?.setWarningSink { warning -> report(warning) }
        // Every byte the reader delivers is progress for the stall timeout, so a slow reader that
        // still delivers is never taken for a stalled one.
        val stallWatch = StallWatch(clock)
        val watchedIo = suppliedIo?.let { ProgressReportingMediaIo(it, stallWatch) }
        val cachingIo = if (watchedIo != null && config.network.ioCache.enabled) {
            CachingMediaIo(watchedIo, config.network.ioCache)
        } else {
            null
        }
        // What the backend will read through: the cache when there is one, the raw reader
        // otherwise. Handed over as a factory that answers with this one reader, because that is
        // what the item's own field is and the backend must not have two shapes to handle.
        val sessionIo = cachingIo ?: watchedIo
        val effectiveItem = if (sessionIo == null) item else item.copy(io = { sessionIo })
        val backendSession = try {
            // Only an open that reads through the engine's reader shows its progress, so only such
            // an open can stall. The backend's own protocols carry their own timeouts instead.
            val stallLimit = if (sessionIo != null) config.buffer.stallTimeout else Duration.INFINITE
            openBackendSession(effectiveItem, stallWatch, stallLimit, preemptible = pending == null)
        } catch (failure: Throwable) {
            // Every reader on this path is the engine's, whoever supplied the factory, so an open
            // that never produced a session closes it here. The backend's own unwind may have got
            // there first, which is why MediaIo.close is documented to tolerate a second call.
            if (sessionIo != null) runCatching { sessionIo.close() }
            throw failure
        }
        // The reverse-order construction ledger. Every resource acquired below
        // registers its undo the moment it exists; any failure runs the ledger newest-first under
        // NonCancellable, so nothing half built survives. Ownership transfers to OpenSession only
        // at the successful return.
        val rollback = mutableListOf<suspend () -> Unit>()
        rollback += { withContext(dispatchers.demux) { backendSession.close() } }
        try {
            // Backend degradations (hardware fallback, colour approximation) flow into the same
            // warning stream everything else uses, instead of a backend-private default.
            stage(OpenStage.Decoders)
            // Through warn(), not straight onto the flow: a backend degradation that went only to
            // the event flow was absent from the bounded history and therefore from every support
            // bundle, which is the one place a warning that happened before anyone collected can
            // still be read.
            backendSession.setWarningSink { warning -> report(warning) }
            val source = backendSession.source
            // In a multiplex every automatic choice comes from one channel, so one channel's
            // picture never plays with another's sound (#505).
            val program = chooseProgram(source.programs, source.streams, item.demux.program)
            val candidates = programCandidates(source.streams, source.programs, program)
            var builtTracks = source.toTracks().copy(selectedProgram = program?.number)
            if (pending == null) tracks = builtTracks

            val videoCandidate =
                resolveStreamChoice(videoChoice, source.streams, TrackKind.Video, report) {
                    candidates.firstOrNull { it.kind == TrackKind.Video && !it.isCoverArt }
                        // A file whose only picture is its cover art still has a picture worth showing, and the
                        // still-image rule is what keeps it from carrying the timeline.
                        ?: candidates.firstOrNull { it.kind == TrackKind.Video }
                }
            val audioCandidate =
                resolveStreamChoice(audioChoice, source.streams, TrackKind.Audio, report) {
                    pickAudio(candidates)
                }
            var preferredExternal: TrackId? = null
            val subtitleCandidate =
                resolveStreamChoice(subtitleChoice, source.streams, TrackKind.Subtitle, report) {
                    val container = pickSubtitle(candidates, audioCandidate)
                    // An external file that matches the preferences better leaves the container's unselected,
                    // and the open selects the file once its track exists (#514).
                    val external = preferredExternalSubtitle(container, externalSubtitles, audioCandidate, config.subtitles)
                    if (external != null) {
                        preferredExternal = external.id
                        null
                    } else {
                        container
                    }
                }

            var videoStream = videoCandidate
            var audioStream = audioCandidate
            var subtitleStream = subtitleCandidate
            // The old path would decode this stream with the renderer's own decoder, which needs
            // the surface the current item holds, so a preload leaves that decoder for the swap.
            // Cover art decodes on the backend's decoders either way.
            val deferVideoDecoder = pending != null && videoStream != null && !videoStream.isCoverArt &&
                pendingRenderer?.videoDecoderFactories().orEmpty().isNotEmpty() &&
                rendererDecodes(videoSelection, config.hardwareDecode, source.seekable)
            val selectedVideoDecoder = if (deferVideoDecoder) null else videoStream?.let {
                createVideoDecoder(
                    session = backendSession,
                    stream = it,
                    sourceSeekable = source.seekable,
                    selection = videoSelection,
                    pending = pending,
                )
            }
            val videoDecoder = selectedVideoDecoder?.decoder
            if (videoDecoder != null) {
                rollback += { withContext(dispatchers.videoDecode) { videoDecoder.close() } }
            }
            if (videoStream != null && videoDecoder == null && !deferVideoDecoder) {
                report(
                    PlaybackWarning.TrackDeselected(
                        TrackId(videoStream.index),
                        deselected("no decoder accepted this video stream"),
                    ),
                )
                videoStream = null
            }
            val audioDecoder = audioStream?.let { createAudioDecoder(backendSession, it, pending) }
            if (audioDecoder != null) {
                rollback += { withContext(dispatchers.audioDecode) { audioDecoder.close() } }
            }
            if (audioStream != null && audioDecoder == null) {
                report(
                    PlaybackWarning.TrackDeselected(
                        TrackId(audioStream.index),
                        deselected("no decoder accepted this audio stream"),
                    ),
                )
                audioStream = null
            }
            // A subtitle decoder that cannot open costs its track and not the file, as on a
            // switch: the web build of the media library has no subtitle decoders at all.
            var subtitleRefusal = "no decoder accepted this subtitle stream"
            val subtitleDecoder = subtitleStream?.let { stream ->
                try {
                    backendSession.subtitleDecoders.firstNotNullOfOrNull { factory -> factory.create(stream) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    subtitleRefusal = "the subtitle decoder could not be created${causeDetail(failure)}"
                    null
                }
            }
            if (subtitleDecoder != null) {
                rollback += { subtitleDecoder.close() }
            }
            if (subtitleStream != null && subtitleDecoder == null) {
                report(PlaybackWarning.TrackDeselected(TrackId(subtitleStream.index), subtitleRefusal))
                subtitleStream = null
            }
            if (videoStream == null && audioStream == null) {
                throw PlaybackException(
                    if (source.streams.isEmpty()) {
                        PlaybackError.NotMedia(item.uri, "the container declares no audio or video stream")
                    } else {
                        PlaybackError.NoPlayableStream(builtTracks.all)
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
            // Seed the schedule with the display the renderer already knows, before first frame.
            videoPlayback?.vsyncIntervalNanos = pendingRenderer?.vsyncIntervalNanos()
            if (videoPlayback != null) {
                rollback += { videoPlayback.close() }
                // Pre-start, so it applies immediately; the scheduler for this playback does not
                // exist yet, which is what makes the immediate path of the setter safe.
                videoPlayback.speed = effectiveSpeed
            }

            stage(OpenStage.Output)
            // Decided once per item, from the picture that plays: cover art is not a film (#446).
            val audioContent = item.audioContent.resolve(hasPicture = videoStream != null && !videoStream.isCoverArt)
            var sink: AudioSink? = null
            var audioPlayback: AudioPlayback? = null
            var negotiated: AudioFormat? = null
            // A preload opens no device: at the handoff it takes over the current item's.
            if (pending == null && audioStream != null && audioDecoder != null) {
                val createdSink = output.audioSink.create()
                sink = createdSink
                createdSink.setContent(audioContent)
                // Generalized from AudioPlayback's
                // construction onward the playback owns the sink and its close covers both (it is
                // idempotent). The window between the sink's creation and that construction is one
                // non-suspending line, and the playback entry below covers everything after it,
                // including a throw from AudioPlayback.open itself, which used to leak the sink.
                val createdPlayback = newAudioPlayback(createdSink)
                audioPlayback = createdPlayback
                rollback += { createdPlayback.close() }
                // Before open: open() captures the wanted rate as the fresh path's epoch, so a
                // player already at 2x opens its next file at 2x rather than at 1x until a seek.
                createdPlayback.speed = effectiveSpeed
                createdPlayback.preservePitch = preservePitch
                negotiated = createdPlayback.open(audioDecoder.outputFormat)
                createdPlayback.volume = volume
                createdPlayback.setDuckLevel(duckLevel)
                createdPlayback.muted = muted
                createdPlayback.replayGain = replayGainFor(audioStream, source.metadata)
                createdPlayback.balance = balance
                createdPlayback.equalizer = equalizer
                emitEvent(PlayerEvent.AudioFormatChanged(negotiated.sampleRate, negotiated.channels))
            }
            stage(OpenStage.Assembly)

            // The demux frontier can run seconds ahead of the presentation clock. A
            // stream enabled only at switch time would therefore begin at that frontier, not at
            // the picture the viewer is looking at. Keep bounded compressed caches for every
            // alternate audio/subtitle stream and decode only the selected lanes.
            val cachedAudioStreams = source.streams.filter { it.kind == TrackKind.Audio }
            val cachedSubtitleStreams = source.streams.filter { it.kind == TrackKind.Subtitle }
            val readStreams = buildSet {
                videoStream?.index?.let(::add)
                cachedAudioStreams.forEach { add(it.index) }
                cachedSubtitleStreams.forEach { add(it.index) }
            }
            withContext(dispatchers.demux) {
                source.selectStreams(readStreams)
                if (startUs > 0L) source.seekToKeyframe(Pts(startUs))
            }

            builtTracks = builtTracks
                .withSelection(TrackKind.Video, videoStream?.let { TrackId(it.index) })
                .withSelection(TrackKind.Audio, audioStream?.let { TrackId(it.index) })
                .withSelection(TrackKind.Subtitle, subtitleStream?.let { TrackId(it.index) })
            if (pending == null) tracks = builtTracks else pending.tracks = builtTracks
            videoStream?.visibleVideoSize?.let { size ->
                val event = PlayerEvent.VideoSizeChanged(size)
                if (pending == null) emitEvent(event) else pending.events += event
            }

            val softLimitUs = config.buffer.softTarget.inWholeMicroseconds
            // A queue starts at the initial generation, and a rebuild starts at whatever epoch the
            // player has reached. Aligning them here is what stops the demuxer's very first packet
            // from being rejected as stale, which would leave the new session with nothing. A rebuild
            // realigns the rest of itself afterwards: the recovery reopen and the track change both
            // flush their decoders into the epoch before the workers start.
            val videoQueue = videoStream?.let {
                PacketQueue(it.index, softLimitUs).also { queue -> queue.flushTo(requestedEpoch) }
            }
            val audioQueues = cachedAudioStreams.associate { stream ->
                stream.index to PacketQueue(stream.index, softLimitUs).also { queue ->
                    queue.flushTo(requestedEpoch)
                }
            }
            val subtitleQueues = cachedSubtitleStreams.associate { stream ->
                stream.index to PacketQueue(stream.index, softLimitUs).also { queue ->
                    queue.flushTo(requestedEpoch)
                }
            }
            val audioQueue = audioStream?.let { audioQueues[it.index] }
            val subtitleQueue = subtitleStream?.let { subtitleQueues[it.index] }
            val audioLane = if (audioStream != null && audioDecoder != null && audioQueue != null) {
                AudioLane(audioStream, audioDecoder, audioQueue)
            } else {
                null
            }
            return OpenSession(
                readStreams = readStreams,
                preferredExternalSubtitle = preferredExternal,
                audioContent = audioContent,
                token = pending?.token ?: nextSessionToken++,
                backendSession = backendSession,
                source = source,
                videoStream = videoStream,
                videoDecoder = if (videoStream == null) null else videoDecoder,
                videoDecoderOrigin = if (videoStream == null) null else selectedVideoDecoder?.origin,
                coupledRenderer = if (videoStream != null && selectedVideoDecoder?.origin == VideoDecoderOrigin.Renderer) {
                    pendingRenderer
                } else {
                    null
                },
                videoQueue = videoQueue,
                audioLane = audioLane,
                audioQueues = audioQueues,
                subtitleStream = subtitleStream,
                subtitleDecoder = if (subtitleStream == null) null else subtitleDecoder,
                subtitleQueue = subtitleQueue,
                subtitleQueues = subtitleQueues,
                video = videoPlayback,
                audio = audioPlayback,
                sink = sink,
                renderer = renderer,
                negotiatedFormat = negotiated,
                cachingIo = cachingIo,
                relatedTraffic = watchedIo?.related,
                stallWatch = stallWatch,
                networkIo = suppliedIo,
            ).also { built ->
                // What the device was opened for, which a gapless handoff compares the next item against.
                built.deviceRequest = if (audioPlayback != null) audioDecoder?.outputFormat else null
                built.videoDecoderDeferred = deferVideoDecoder && videoStream != null
                built.rendererDecodersAsked = videoStream != null && selectedVideoDecoder?.rendererAsked == true
            }
        } catch (failure: Throwable) {
            // Newest-first, under NonCancellable: a cancelled open must still release everything
            // it acquired, and each undo is isolated so one refusal cannot leak the rest.
            withContext(NonCancellable) {
                for (undo in rollback.asReversed()) {
                    runCatching { undo() }
                }
            }
            throw failure
        }
    }

    private enum class VideoDecoderSelection { Configured, BackendSoftwareOnly }

    private enum class VideoDecoderOrigin { Renderer, Backend }

    private data class SelectedVideoDecoder(
        val decoder: VideoDecoder,
        val origin: VideoDecoderOrigin,
        /** Whether an attached renderer's own factories were tried before this one was chosen. */
        val rendererAsked: Boolean,
    )

    /**
     * Why each decoder candidate refused the last stream it was offered, for the deselection
     * warning. Actor-confined, overwritten per create attempt. runCatching+getOrNull here used to
     * swallow cancellation and every diagnostic.
     */
    private var decoderCandidateFailures: List<String> = emptyList()

    private suspend fun createVideoDecoder(
        session: BackendSession,
        stream: PlayerStreamInfo,
        sourceSeekable: Boolean,
        selection: VideoDecoderSelection,
        pending: PendingBuild? = null,
    ): SelectedVideoDecoder? {
        if (stream.kind != TrackKind.Video) return null
        val failures = pending?.failures ?: mutableListOf<String>().also { decoderCandidateFailures = it }
        failures.clear()
        val report = pending?.report ?: ::warn

        val policy = when (selection) {
            VideoDecoderSelection.Configured -> config.hardwareDecode
            VideoDecoderSelection.BackendSoftwareOnly -> HwdecPolicy.Off
        }
        // A preload never takes a renderer's own decoder: that decoder needs the renderer's
        // surface, which the current item holds until the swap. buildSession refuses a preload
        // that would need one.
        val rendererEligible = pending == null && rendererDecodes(selection, policy, sourceSeekable)
        val rendererFactories = if (rendererEligible) pendingRenderer?.videoDecoderFactories().orEmpty() else emptyList()
        val rendererAsked = rendererFactories.isNotEmpty()
        for (factory in rendererFactories) {
            val decoder = tryCreateVideoDecoder(factory, stream, policy, failures)
            if (decoder != null && !passOverUnshowable(factory, decoder, failures)) {
                return SelectedVideoDecoder(decoder, VideoDecoderOrigin.Renderer, rendererAsked)
            }
            warnAboutRefusedHardwareCandidate(factory, stream, policy, report)
        }

        for (factory in session.videoDecoders) {
            val decoder = tryCreateVideoDecoder(factory, stream, policy, failures) ?: run {
                warnAboutRefusedHardwareCandidate(factory, stream, policy, report)
                continue
            }
            if (passOverUnshowable(factory, decoder, failures)) continue
            if (selection == VideoDecoderSelection.BackendSoftwareOnly && decoder.hardware != HwdecStatus.Software) {
                val reported = decoder.hardware
                try {
                    // This candidate is already owned but has not reached buildSession's rollback
                    // ledger. Cancellation must not strand it in that gap.
                    withContext(NonCancellable + dispatchers.videoDecode) { decoder.close() }
                } catch (failure: Throwable) {
                    failures += "${factory.name}: ignored Off and reported $reported; close failed: " +
                        (failure.message ?: failure::class.simpleName)
                    continue
                }
                failures += "${factory.name}: ignored Off and reported $reported"
                continue
            }
            return SelectedVideoDecoder(decoder, VideoDecoderOrigin.Backend, rendererAsked)
        }
        return null
    }

    /**
     * Closes [decoder] and answers true when it declares frames the attached renderer cannot show,
     * so the next candidate is tried before anything plays (#102). With no renderer attached, or a
     * decoder that cannot say, nothing is checked and the first frame stays the backstop.
     */
    private suspend fun passOverUnshowable(
        factory: VideoDecoderFactory,
        decoder: VideoDecoder,
        failures: MutableList<String>,
    ): Boolean {
        val renderer = pendingRenderer ?: return false
        val shape = decoder.output ?: return false
        if (renderer.accepts(shape)) return false
        try {
            // Owned but not yet in the build's rollback ledger, as for an ignored Off above.
            withContext(NonCancellable + dispatchers.videoDecode) { decoder.close() }
        } catch (failure: Throwable) {
            failures += "${factory.name}: makes $shape frames, and closing it failed: " +
                (failure.message ?: failure::class.simpleName)
            return true
        }
        failures += "${factory.name}: makes $shape frames, which the attached renderer cannot show"
        return true
    }

    /**
     * Why [renderer] cannot replace the attached one, or null when it can. Only a running decoder
     * that declares its frames is checked. A decoder coupled to the renderer being replaced is
     * left to the rebuild that follows the swap, because its frames change with the renderer.
     */
    private fun attachRefusal(renderer: VideoRenderer): PlaybackError.RendererIncompatible? {
        val active = session ?: return null
        val decoder = active.videoDecoder ?: return null
        if (active.videoDecoderOrigin == VideoDecoderOrigin.Renderer && active.coupledRenderer !== renderer) return null
        val shape = decoder.output ?: return null
        if (renderer.accepts(shape)) return null
        return PlaybackError.RendererIncompatible(renderer::class.simpleName ?: "renderer", shape)
    }

    private suspend fun tryCreateVideoDecoder(
        factory: VideoDecoderFactory,
        stream: PlayerStreamInfo,
        policy: HwdecPolicy,
        failures: MutableList<String>,
    ): VideoDecoder? = acquireAcrossContext(
        context = dispatchers.videoDecode,
        acquire = {
            try {
                factory.create(stream, policy, config.deinterlace)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                failures += "${factory.name}: ${failure.message ?: failure::class.simpleName}"
                null
            }
        },
        closeAbandoned = { it.close() },
    )

    /** True when the configured policy would try the attached renderer's own decoders first. */
    private fun rendererDecodes(selection: VideoDecoderSelection, policy: HwdecPolicy, sourceSeekable: Boolean): Boolean =
        selection == VideoDecoderSelection.Configured && when (policy) {
            HwdecPolicy.Auto -> sourceSeekable
            HwdecPolicy.Require -> true
            HwdecPolicy.Off, is HwdecPolicy.Prefer -> false
        }

    private fun warnAboutRefusedHardwareCandidate(
        factory: VideoDecoderFactory,
        stream: PlayerStreamInfo,
        policy: HwdecPolicy,
        report: (PlaybackWarning) -> Unit = ::warn,
    ) {
        // Named as a hardware problem only when hardware was actually asked for. A factory refusing a
        // stream it cannot decode has nothing to do with hardware, and the caller already learns about
        // that from the TrackDeselected warning and the failed open, each carrying the real reason.
        val askedForHardware = policy is HwdecPolicy.Require || policy is HwdecPolicy.Prefer
        if (askedForHardware) {
            report(PlaybackWarning.HardwareDecodeUnavailable(stream.codec, "${factory.name} refused the stream"))
        }
    }

    private suspend fun createAudioDecoder(
        session: BackendSession,
        stream: PlayerStreamInfo,
        pending: PendingBuild? = null,
    ): AudioDecoder? {
        if (stream.kind != TrackKind.Audio) return null
        val failures = pending?.failures ?: mutableListOf<String>().also { decoderCandidateFailures = it }
        failures.clear()
        for (factory in session.audioDecoders) {
            val decoder = acquireAcrossContext(
                context = dispatchers.audioDecode,
                acquire = {
                    try {
                        factory.create(stream)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        failures += "${factory.name}: ${failure.message ?: failure::class.simpleName}"
                        null
                    }
                },
                closeAbandoned = { it.close() },
            )
            if (decoder != null) return decoder
        }
        return null
    }

    /**
     * Transfers an acquired resource across a dispatcher boundary without a prompt-cancellation gap.
     *
     * [withContext] may finish [acquire] and then discard its result when the caller is cancelled
     * before resumption. The worker therefore records local ownership before returning. Ownership is
     * cleared only after the actor has received the value; otherwise cleanup runs on the resource's
     * own context under [NonCancellable].
     */
    private suspend fun <T : Any> acquireAcrossContext(
        context: CoroutineContext,
        acquire: suspend () -> T?,
        closeAbandoned: suspend (T) -> Unit,
    ): T? {
        var locallyOwned: T? = null
        var primaryFailure: Throwable? = null
        try {
            val acquired = withContext(context) {
                acquire().also { locallyOwned = it }
            }
            locallyOwned = null
            return acquired
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val abandoned = locallyOwned
            if (abandoned != null) {
                try {
                    withContext(context + NonCancellable) { closeAbandoned(abandoned) }
                } catch (closeFailure: Throwable) {
                    val primary = primaryFailure
                    if (primary != null) primary.addSuppressed(closeFailure) else throw closeFailure
                }
            }
        }
    }

    /**
     * A stop or a close cancelled a backend open before it returned. Each caller of [buildSession]
     * answers it the way it answers any other preemption.
     */
    private class OpenPreempted : Exception("a stop or a close preempted the backend open")

    /**
     * [resolveMediaIo] as a job that this actor can cancel, the way [openBackendSession] runs the
     * backend open (#398).
     *
     * The resolve is the item's own reader factory, a configured resolver or the automatic network
     * provider, and the network reader asks the server for its first bytes before it returns. Run
     * inline, a server that took the connection and stayed silent held every stop and close behind
     * it until the reader's read timeout, ten seconds by default, so a cancelled open stayed Opening
     * that long. The actor now reads its mailbox while it waits, and a stop or a close cancels the
     * resolve. A reader the resolve made anyway is closed here.
     *
     * The resolve stays on this actor's own dispatcher, where it always ran, and a gapless preload,
     * which must not answer the player's mailbox, still resolves inline.
     *
     * @throws OpenPreempted when a stop or a close cancelled the resolve.
     */
    private suspend fun resolveReader(item: MediaItem, preemptible: Boolean): MediaIo? {
        if (!preemptible) return resolveMediaIo(item, config.network)
        var outcome: Result<MediaIo?>? = null
        var abandoned = false
        try {
            supervisorScope {
                val resolving = launch {
                    outcome = try {
                        Result.success(resolveMediaIo(item, config.network))
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    }
                }
                while (!resolving.isCompleted) {
                    if (!abandoned && preempted()) {
                        abandoned = true
                        resolving.cancel()
                    }
                    // Woken by the resolve's end, or by the poll that reads the mailbox again.
                    withTimeoutOrNull(WORKER_POLL) { resolving.join() }
                }
            }
        } finally {
            val made = outcome?.getOrNull()
            if (made != null && (abandoned || !currentCoroutineContext().isActive)) runCatching { made.close() }
        }
        if (abandoned) throw OpenPreempted()
        val result = outcome ?: error("the reader's resolve ended without an answer")
        val failure = result.exceptionOrNull() ?: return result.getOrThrow()
        // As in openBackendSession: a cancellation this actor did not ask for is a failed resolve,
        // not the actor's own cancellation.
        if (failure is CancellationException) throw IllegalStateException("the reader's resolve was cancelled", failure)
        throw failure
    }

    /**
     * Opens the backend session on the demux lane, as a job that this actor can cancel.
     *
     * The open used to run inline, so a stop or a close waited behind an open that hung. The actor
     * now reads its mailbox while it waits. A stop or a close cancels the open, and so does
     * [stallLimit] when the open waits that long for the engine's reader without a byte. A backend
     * whose open blocks a thread has to let that cancellation reach its read, as the FFmpeg
     * backend's blocking bridge does.
     *
     * After a cancel the actor still waits for the open to end. The demux lane and the reader belong
     * to the open until it returns, and a close under a read is the one thing a blocking bridge must
     * never meet. A session that the open built anyway is closed here.
     *
     * @throws OpenPreempted when a stop or a close cancelled the open.
     * @throws PlaybackException with [PlaybackError.SourceStalled] when the open stalled.
     */
    private suspend fun openBackendSession(
        item: MediaItem,
        watch: StallWatch,
        stallLimit: Duration,
        preemptible: Boolean = true,
    ): BackendSession {
        var outcome: Result<BackendSession>? = null
        var abandoned: Throwable? = null
        try {
            supervisorScope {
                watch.begin()
                val opening = launch(dispatchers.demux) {
                    outcome = try {
                        Result.success(backend.open(item))
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    }
                }
                try {
                    while (!opening.isCompleted) {
                        if (abandoned == null) {
                            val stalledFor = watch.stalledFor() ?: Duration.ZERO
                            abandoned = when {
                                preemptible && preempted() -> OpenPreempted()
                                stalledFor >= stallLimit ->
                                    PlaybackException(PlaybackError.SourceStalled(item.uri, stalledFor))
                                else -> null
                            }
                            if (abandoned != null) opening.cancel()
                        }
                        // Woken by the open's end, or by the poll that reads the mailbox again.
                        withTimeoutOrNull(WORKER_POLL) { opening.join() }
                    }
                } finally {
                    watch.end()
                }
            }
        } finally {
            val built = outcome?.getOrNull()
            if (built != null && (abandoned != null || !currentCoroutineContext().isActive)) {
                withContext(dispatchers.demux + NonCancellable) { runCatching { built.close() } }
            }
        }
        abandoned?.let { throw it }
        val result = outcome ?: error("the backend open ended without an answer")
        val failure = result.exceptionOrNull() ?: return result.getOrThrow()
        // A cancellation that this actor did not ask for is a failed open. Rethrown as it is, the
        // loop would take it for the actor's own cancellation and stop.
        if (failure is CancellationException) throw IllegalStateException("the backend open was cancelled", failure)
        throw failure
    }

    /** The deselection detail: the plain sentence plus whatever each candidate actually said. */
    private fun deselectionDetail(base: String): String =
        if (decoderCandidateFailures.isEmpty()) base
        else "$base (${decoderCandidateFailures.joinToString("; ")})"

    private fun choiceFor(change: CoreCommand.SelectTrack, kind: TrackKind, current: Int?): StreamChoice = when {
        change.kind == kind -> change.track?.let { StreamChoice.At(it.value) } ?: StreamChoice.None
        current != null -> StreamChoice.At(current)
        else -> StreamChoice.None
    }

    private fun StreamChoice.selectedIndex(): Int? = (this as? StreamChoice.At)?.index

    /**
     * The automatic subtitle choice, per SubtitleConfig and the container's dispositions:
     * an accessibility track in a preferred language wins, then any preferred-language track
     * (default-flagged first), then, when the audio is not in a preferred language and the
     * config allows it, a forced track. No preference means no automatic subtitles.
     */
    private fun pickSubtitle(
        streams: List<PlayerStreamInfo>,
        audio: PlayerStreamInfo?,
    ): PlayerStreamInfo? = pickSubtitleStream(streams, audio, config.subtitles)

    private fun pickAudio(streams: List<PlayerStreamInfo>): PlayerStreamInfo? =
        pickAudioStream(
            streams,
            config.audio.preferredLanguages,
            outputChannels = if (config.audio.matchOutputChannels) {
                runCatching { output.audioSink.outputChannelCount() }.getOrNull()?.takeIf { it > 0 }
            } else {
                null
            },
        )

    /**
     * Fills the queues with the device stopped, until every selected stream is at least Ready.
     *
     * Paused, because a device started with nothing to play underruns at once and clicks. Bounded, and
     * preemptible: a stop or a close arriving during a slow network open is answered rather than waited
     * out.
     */
    private suspend fun awaitInitialFill(session: OpenSession): FillOutcome {
        val deadline = clock.nanos() + OPEN_FILL_DEADLINE.inWholeNanoseconds
        while (clock.nanos() < deadline) {
            if (preempted()) return FillOutcome.Preempted
            updateStreamStatuses(session)
            if (session.anyWorkerFinished()) return FillOutcome.WorkerFinished
            if (everySelectedStreamReady(session)) return FillOutcome.Ready
            delay(WORKER_POLL)
        }
        return FillOutcome.TimedOut
    }

    /** True when no frame can arrive any more: every stream ended, every decoder drained, nothing held. */
    private fun atEndOfStream(session: OpenSession): Boolean =
        session.selectedQueues().isNotEmpty() &&
            session.selectedQueues().all { it.isEndOfStream && it.count == 0 } &&
            session.decodersDrained() &&
            session.videoInFlight.value == 0 &&
            (session.video?.queuedFrames ?: 0) == 0

    /** What the initial fill actually established, so open never mistakes a timeout for readiness. */
    private enum class FillOutcome { Ready, Preempted, WorkerFinished, TimedOut }

    /**
     * What the one-frame push achieved, as five separate facts rather than one boolean.
     *
     * They were one number before, `submitted + headless`, and that number was wrong in both
     * directions: a renderer that refused every frame could never satisfy it, so an open burned
     * its whole ten second budget and then reported success anyway, while a frame released with no
     * renderer attached satisfied it instantly. Each outcome now has a name, and
     * the two that mean "the viewer is looking at nothing new" say so.
     */
    private enum class FirstFrame {
        /** A renderer accepted the frame. The strongest signal this engine has for "on screen". */
        Submitted,

        /** Nothing is attached, so the frame was paced and released with nowhere to draw it. */
        Headless,

        /** A renderer was attached and refused it. The surface still shows whatever it showed. */
        Refused,

        /** Nothing left the schedule before the deadline, or a stop cut the wait short. */
        None,

        /** No video track, so there is no first frame and nothing to report. */
        NoVideo,
    }

    /**
     * Says where the container's declaration and the decoder disagree, once per open.
     *
     * Read after the first frames rather than at open, because the comparison only exists once a
     * decoder has produced something: before that the container is the only voice and there is
     * nothing to disagree with.
     *
     * Not a failure. The file plays and the decoded value is the one in force. It is worth saying
     * because this is exactly what a viewer meets as a wrong-sized picture, or as an audio device
     * opened for a rate nothing feeds, with no other clue as to why.
     */
    private fun reportContainerDivergences(session: OpenSession) {
        if (divergencesReported) return
        val divergences = session.source.streamDivergences
        if (divergences.isEmpty()) return
        divergencesReported = true
        divergences.forEach { divergence ->
            warn(
                PlaybackWarning.ContainerDeclarationDiverged(
                    streamIndex = divergence.streamIndex,
                    field = divergence.field,
                    declared = divergence.declared,
                    decoded = divergence.decoded,
                ),
            )
        }
    }

    /**
     * Presents one frame with the clock stopped, so opening ends on a picture rather than on nothing.
     *
     * The scheduler worker does the presenting, because a renderer is documented to be called from it.
     * The actor asks for exactly one frame and waits for it to go out.
     */
    private suspend fun presentFirstFrame(
        session: OpenSession,
        budget: Duration = OPEN_FILL_DEADLINE,
    ): FirstFrame {
        val video = session.video ?: return FirstFrame.NoVideo
        // A parked lane decodes nothing, so no frame can come; the picture returns with the lane.
        if (session.videoParked.value) return FirstFrame.NoVideo
        // Against a baseline and not against zero. A seek ends with this too, and by then frames have
        // already gone out for the position the viewer left, so counting from zero would report the old
        // picture as the new one and present nothing at all.
        // Three baselines, because the difference between them IS the answer. The attachable
        // renderer is the only thing that knows whether a real renderer took the frame or whether
        // there was nothing attached to take it: with no delegate it accepts on the schedule's
        // behalf, so the schedule's own submitted count cannot tell those two apart. The refusal
        // is the schedule's to report, because that is where the renderer's "no" comes back.
        val submittedBefore = session.renderer.submittedFrames
        val headlessBefore = session.renderer.headlessFrames
        val refusedBefore = video.refusedFrames
        session.schedulerMode.value = SCHEDULER_ONE_FRAME
        session.schedulerNudge.trySend(Unit)
        val deadline = clock.nanos() + budget.inWholeNanoseconds
        var outcome = FirstFrame.None
        while (clock.nanos() < deadline) {
            session.firstWorkerOutcome.value?.cause?.let { throw it }
            outcome = when {
                session.renderer.submittedFrames > submittedBefore -> FirstFrame.Submitted
                session.renderer.headlessFrames > headlessBefore -> FirstFrame.Headless
                video.refusedFrames > refusedBefore -> FirstFrame.Refused
                else -> FirstFrame.None
            }
            if (outcome != FirstFrame.None) break
            if (preempted()) break
            // A pipeline that has reached the end of every selected stream, drained its decoders
            // and holds no frame cannot produce one, so waiting out the rest of the budget buys
            // nothing and costs the caller seconds of a frozen player. This is what a seek to the
            // very end looks like from here, and the whole budget used to be spent on it before
            // the session could even be told it had ended (owner report 2026-08-23).
            if (atEndOfStream(session)) break
            // Woken by the schedule's own release ping; the poll bounds the conditions above.
            withTimeoutOrNull(WORKER_POLL) { session.landingArrived.receive() }
        }
        session.schedulerMode.value = SCHEDULER_IDLE
        return outcome
    }

    /**
     * The same push, with the two silent outcomes said out loud.
     *
     * A headless release is deliberately NOT warned: with no renderer attached there is no picture
     * to be wrong about, `PlaybackStats.headlessFrames` already counts it, and the facade documents
     * that detaching costs the picture and nothing else. The other two are warned because in both
     * of them a renderer exists and the viewer is still looking at the old surface.
     */
    private suspend fun reportFirstFrame(session: OpenSession, what: String): FirstFrame {
        val outcome = presentFirstFrame(session)
        reportContainerDivergences(session)
        when (outcome) {
            FirstFrame.Submitted, FirstFrame.Headless, FirstFrame.NoVideo -> Unit
            FirstFrame.Refused -> warn(
                PlaybackWarning.StartupIncomplete(
                    "the renderer refused the first frame of the $what, so the surface still shows " +
                        "whatever it showed before",
                ),
            )
            FirstFrame.None -> warn(
                PlaybackWarning.StartupIncomplete(
                    "no frame left the schedule within $OPEN_FILL_DEADLINE, so the $what finished " +
                        "on no picture",
                ),
            )
        }
        return outcome
    }

    /**
     * The refusal a preempted open or track change completes with.
     *
     * Not a `CancellationException`: the caller's own coroutine was never cancelled, and handing
     * one back makes structured concurrency treat another part of the application calling stop as
     * this caller's own cancellation. A stop arriving mid-open is a fact about the order the calls
     * were made in, so it reads as one.
     */
    private fun preemptedByTeardown(what: String): IllegalStateException = IllegalStateException(
        "$what was preempted by stop() or close(), so the session it was building was torn down",
    )

    // ---------------------------------------------------------------------------------------------
    // The handlers.
    // ---------------------------------------------------------------------------------------------

    /**
     * Installs one caller's selection, displacing only a request for the SAME kind.
     *
     * The displaced caller is told `Superseded` and named the request that beat it. Completing it
     * normally, which is what happened before, meant two callers who asked for two different audio
     * tracks were both told they had won.
     */
    /**
     * The subtitle transaction. Every container subtitle queue is already routed, so the
     * actor can swap only the decoder/cue selector while video, audio and demux continue.
     */
    private suspend fun inPlaceContainerSubtitleChange(session: OpenSession): Boolean {
        val request = pendingSelections[TrackKind.Subtitle] ?: return false
        val targetExternal = request.track?.takeIf(::isExternalSubtitle)
        val targetStream = request.track
            ?.takeUnless(::isExternalSubtitle)
            ?.let { id -> session.source.streams.firstOrNull { it.index == id.value && it.kind == TrackKind.Subtitle } }

        if (targetExternal == null && targetStream?.index == session.subtitleStream?.index &&
            selectedExternalSubtitle == null
        ) {
            pendingSelections.remove(TrackKind.Subtitle)
            request.reply.complete(TrackChange.Applied(TrackKind.Subtitle, request.track))
            return true
        }

        var preparedDecoder: io.github.yuroyami.kiteplayer.spi.SubtitleDecoder? = null
        if (targetStream != null) {
            preparedDecoder = try {
                session.backendSession.subtitleDecoders.firstNotNullOfOrNull { it.create(targetStream) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                discardSelection(
                    TrackKind.Subtitle,
                    "the target subtitle decoder could not be created${causeDetail(failure)}",
                )
                return true
            }
            if (preparedDecoder == null) {
                discardSelection(TrackKind.Subtitle, "no decoder accepted subtitle stream ${targetStream.index}")
                return true
            }
            try {
                preparedDecoder.flush(requestedEpoch)
            } catch (cancellation: CancellationException) {
                preparedDecoder.close()
                throw cancellation
            } catch (failure: Throwable) {
                runCatching { preparedDecoder.close() }
                discardSelection(
                    TrackKind.Subtitle,
                    "the target subtitle decoder could not align to the live epoch${causeDetail(failure)}",
                )
                return true
            }
        }

        session.pendingSubtitlePacket?.close()
        session.pendingSubtitlePacket = null
        session.subtitleDecoderMayHaveOutput = false
        session.subtitleDrained = false
        session.subtitleDrainRefusals = 0
        session.lastSubtitlePruneCutoffUs = Long.MIN_VALUE
        withdrawSubtitleOverlay(session)

        val retiredDecoder = session.subtitleDecoder
        session.subtitleStream = targetStream
        session.subtitleDecoder = preparedDecoder
        session.subtitleQueue = targetStream?.let { session.subtitleQueues[it.index] }
        session.subtitleQueue?.dropBefore(
            currentPosition().micros - CUE_PRUNE_BEHIND_MICROS,
            assumedDurationUs = CUE_PRUNE_BEHIND_MICROS,
        )
        session.subtitleCues = when {
            targetStream != null -> session.subtitleCueCaches.getValue(targetStream.index)
            targetExternal != null -> externalSubtitleTracks.firstOrNull { it.id == targetExternal }
                ?.cues?.toMutableList() ?: mutableListOf()
            else -> mutableListOf()
        }
        selectedExternalSubtitle = targetExternal
        pendingExternalSubtitle = null
        refreshTypesetting()
        tracks = tracks.withSelection(TrackKind.Subtitle, request.track)
        publishSnapshot()
        pendingSelections.remove(TrackKind.Subtitle)
        request.reply.complete(TrackChange.Applied(TrackKind.Subtitle, request.track))
        if (request.automatic) emitEvent(PlayerEvent.TrackChosenByPlayer(TrackKind.Subtitle, request.track))

        if (retiredDecoder != null && retiredDecoder !== preparedDecoder) {
            runCatching { retiredDecoder.close() }.exceptionOrNull()?.let { failure ->
                warn(PlaybackWarning.ResourcesNotReleased("retired subtitle decoder: ${failure.message}"))
            }
        }
        return true
    }

    /**
     * Chooses the primary subtitle again for the audio [session] now plays, by the open's own rules,
     * while the open's choice still stands (#506). Those rules read the audio: a forced track goes
     * with the audio in its language, so a viewer who switches to the dub gets the signs track made
     * for it, and switching back brings the full track back. Media3 runs its text choice again the
     * same way. A subtitle the viewer or the application chose stays, and so does a secondary.
     */
    private suspend fun chooseSubtitleForAudio(session: OpenSession) {
        if (!subtitleChosenByPlayer || TrackKind.Subtitle in pendingSelections) return
        val source = session.source
        val program = source.programs.firstOrNull { it.number == tracks.selectedProgram }
        val container = pickSubtitle(programCandidates(source.streams, source.programs, program), session.audioStream)
        val target = preferredExternalSubtitle(container, externalSubtitleTracks.map { it.info }, session.audioStream, config.subtitles)?.id
            ?: container?.let { TrackId(it.index) }
        if (target == tracks.selectedSubtitle) return
        // One track cannot fill both slots, and the secondary is the viewer's.
        if (target != null && target == tracks.selectedSecondarySubtitle) return
        pendingSelections[TrackKind.Subtitle] =
            SelectionRequest(TrackKind.Subtitle, target, CompletableDeferred(), automatic = true)
        inPlaceContainerSubtitleChange(session)
    }

    /**
     * Takes in what [target]'s demux lane saw change after the open (#509): a cache for each sound
     * and subtitle stream the lane now reads in [reading], the source's streams and programmes as
     * [listed] and [programs] give them, and an event naming the tracks that appeared. What plays
     * changes later, in [handleLateStreams], once a new stream has reached the position. A preloaded
     * item keeps its tracks and its event for the swap, as everything else of its open does.
     */
    private fun adoptLayout(
        target: OpenSession,
        listed: List<PlayerStreamInfo>?,
        programs: List<MediaProgram>?,
        reading: List<Int>,
    ) {
        val preloaded = pendingNext?.takeIf { it.prepared?.session === target }
        if (target !== session && preloaded == null) return
        // The lane offers under its own epoch, which a seek moves only while the lane is parked.
        val epoch = target.demuxWorker?.epoch ?: requestedEpoch
        val softLimitUs = config.buffer.softTarget.inWholeMicroseconds
        for (index in reading) {
            val stream = listed?.firstOrNull { it.index == index } ?: continue
            target.addQueue(stream.kind, PacketQueue(index, softLimitUs).also { it.flushTo(epoch) })
        }
        target.layoutChanged = true
        val before = preloaded?.build?.tracks ?: tracks
        val after = before.withLayout(listed, programs)
        val added = after.all.filter { row -> before.find(row.id) == null }
        if (programs != null || added.any { it.kind == TrackKind.Subtitle }) target.subtitlesToChoose = true
        if (preloaded != null) {
            preloaded.build.tracks = after
            if (added.isNotEmpty()) preloaded.build.events += PlayerEvent.TracksAdded(added)
            return
        }
        tracks = after
        if (added.isNotEmpty()) emitEvent(PlayerEvent.TracksAdded(added))
        publishSnapshot()
    }

    /**
     * Plays a sound, a picture or subtitles that appeared after the open (#509, #527), by the open's
     * own rules and from the tracks of the channel that plays. A sound is chosen when none plays, or
     * when the one that plays ran out while another carries on, as when a channel moves its sound to
     * a new stream at a programme boundary; the sound that ran out lends its language first, so a
     * viewer who chose it keeps that language. A picture is chosen the same way, in
     * [choosePicture]. The subtitles are chosen again while the open's choice of them stands. A
     * caller who turned the sound or the picture off, or chose subtitles, keeps that. mpv chooses a
     * stream that surfaces after the open the same way, when nothing of its kind plays.
     *
     * Only a source that announced a change gets here, so media whose streams never change plays
     * exactly as before. A sound is chosen once its cache covers the position, because it starts
     * where the position is, and the in-place change later in this pass makes the switch.
     */
    private suspend fun handleLateStreams() {
        val session = session ?: return
        if (!session.layoutChanged || session.preloading.value) return
        if (seekPhase.isRunning || pendingSeek != null || pendingVideoRecovery != null || reopenPending) return
        if (session.subtitlesToChoose && TrackKind.Subtitle !in pendingSelections) {
            session.subtitlesToChoose = false
            chooseSubtitleForAudio(session)
        }
        val at = currentPosition().micros
        choosePicture(session, at)
        if (TrackKind.Audio in pendingSelections || soundOffByViewer) return
        val lane = session.audioLane
        if (lane != null && !ranOut(session, lane.queue, at)) return
        val program = tracks.programs.firstOrNull { it.number == tracks.selectedProgram }
        val live = programCandidates(session.source.streams, tracks.programs, program).filter { stream ->
            stream.kind == TrackKind.Audio &&
                stream.index != lane?.stream?.index &&
                stream.index !in session.soundsTried &&
                session.audioQueues[stream.index]?.let { audioCacheRefusal(it, at) == null } == true
        }
        val target = pickAudioStream(
            live,
            listOfNotNull(lane?.stream?.language) + config.audio.preferredLanguages,
            outputChannels = if (config.audio.matchOutputChannels) {
                runCatching { output.audioSink.outputChannelCount() }.getOrNull()?.takeIf { it > 0 }
            } else {
                null
            },
        ) ?: return
        // Tried once: a sound no decoder takes must not be asked for again on every pass.
        session.soundsTried += target.index
        pendingSelections[TrackKind.Audio] =
            SelectionRequest(TrackKind.Audio, TrackId(target.index), CompletableDeferred(), automatic = true)
    }

    /**
     * Whether the sound that [queue] carries has run out at [atUs]: its decoder has taken every packet,
     * every buffer it decoded is in the output, the output has played all of it, and the reads have
     * gone [SOUND_RAN_OUT_US] past the end of its newest packet, so it is not a packet late in the
     * interleaving. Waiting for the output to empty lets the old sound play to its last sample, so a
     * switch to the stream that carries on cuts nothing and the clock does not jump. After a seek
     * nothing has been read yet, so the position stands for the end.
     */
    private fun ranOut(session: OpenSession, queue: PacketQueue, atUs: Long): Boolean {
        if (queue.count > 0 || session.audioInFlight.value > 0) return false
        if ((session.audio?.buffered ?: Duration.ZERO) > Duration.ZERO) return false
        val frontier = session.allPacketQueues.maxOfOrNull { it.newestEndUs ?: Long.MIN_VALUE } ?: return false
        if (frontier == Long.MIN_VALUE) return false
        return frontier - (queue.newestEndUs ?: atUs) >= SOUND_RAN_OUT_US
    }

    /**
     * Plays a picture that appeared after the open (#527), by the open's own rule, the first of the
     * playing channel's pictures that is not cover art: when none plays, as for a radio service that
     * adds a slideshow or a channel joined during a break with no picture, or when the one that plays
     * ran out while another carries on, as when a channel moves its picture to a new stream at a
     * programme boundary. A picture is chosen once its cache holds a keyframe and reaches the
     * position, and plays in place from there.
     */
    private suspend fun choosePicture(session: OpenSession, atUs: Long) {
        if (TrackKind.Video in pendingSelections || pictureOffByViewer) return
        val playing = session.videoStream
        if (playing != null && !pictureRanOut(session, atUs)) return
        val program = tracks.programs.firstOrNull { it.number == tracks.selectedProgram }
        val target = programCandidates(session.source.streams, tracks.programs, program).firstOrNull { stream ->
            stream.kind == TrackKind.Video && !stream.isCoverArt &&
                stream.index != playing?.index &&
                stream.index !in session.picturesRefused &&
                session.pictureQueues[stream.index]?.let { pictureCacheRefusal(it, atUs) == null } == true
        } ?: return
        val refusal = playPictureInPlace(session, target, byViewer = false)
        if (refusal != null) {
            session.picturesRefused += target.index
            warn(PlaybackWarning.TrackDeselected(TrackId(target.index), refusal))
            return
        }
        emitEvent(PlayerEvent.TrackChosenByPlayer(TrackKind.Video, TrackId(target.index)))
    }

    /**
     * Whether the picture that plays has run out at [atUs], as [ranOut] says for a sound: its decoder
     * has taken every packet, the schedule has let every frame go, and the reads have gone
     * [SOUND_RAN_OUT_US] past the end of its newest packet. The few frames a decoder holds back to put
     * pictures in order go with it, as they do at a seek, because the picture after it starts where
     * they would have shown.
     */
    private fun pictureRanOut(session: OpenSession, atUs: Long): Boolean {
        val queue = session.videoQueue ?: return false
        if (queue.count > 0 || session.videoInFlight.value > 0) return false
        if ((session.video?.queuedFrames ?: 0) > 0) return false
        val frontier = session.allPacketQueues.maxOfOrNull { it.newestEndUs ?: Long.MIN_VALUE } ?: return false
        if (frontier == Long.MIN_VALUE) return false
        return frontier - (queue.newestEndUs ?: atUs) >= SOUND_RAN_OUT_US
    }

    /**
     * Why the cache [queue] of a picture cannot take over at [atUs], or null when it can (#527): it
     * needs a keyframe to start from, and packets that reach the position, so it carries on.
     */
    private fun pictureCacheRefusal(queue: PacketQueue, atUs: Long): String? {
        if (!queue.holdsKeyframe) return "picture stream ${queue.streamIndex} has no keyframe to start from yet"
        val last = queue.lastTimestampUs
            ?: return "picture stream ${queue.streamIndex} has no provable timestamp coverage"
        if (last < atUs) return "picture stream ${queue.streamIndex} ends ${atUs - last}us before the current position"
        return null
    }

    /**
     * A viewer's choice of a picture the demux lane reads into a cache, made in place (#527): a reopen
     * would not list a picture that appeared after the open, and a source that cannot seek could not
     * come back to the position. Turning the picture off happens in place too while a sound carries
     * the clock (#529), and while none plays it changes nothing that plays and only keeps off a
     * picture that appears later. The picture that plays now, or one with no cache, is left to the
     * rebuild, which is what a renderer that needs a new decoder asks for.
     */
    private suspend fun inPlacePictureChange(session: OpenSession) {
        val request = pendingSelections[TrackKind.Video] ?: return
        if (request.track == null) {
            if (session.videoStream != null && session.audioLane == null) return
            pendingSelections.remove(TrackKind.Video)
            val refusal = if (session.videoStream == null) null else pictureOffInPlace(session)
            request.reply.complete(
                if (refusal == null) TrackChange.Applied(TrackKind.Video, null) else TrackChange.Discarded(refusal),
            )
            return
        }
        val index = request.track?.value ?: return
        val queue = session.pictureQueues[index] ?: return
        if (index == session.videoStream?.index) return
        pendingSelections.remove(TrackKind.Video)
        val stream = session.source.streams.firstOrNull { it.index == index && it.kind == TrackKind.Video }
        val refusal = if (stream == null) {
            "no picture stream has index $index"
        } else {
            pictureCacheRefusal(queue, currentPosition().micros)
                ?: playPictureInPlace(session, stream, byViewer = !request.automatic)
        }
        request.reply.complete(
            if (refusal == null) TrackChange.Applied(TrackKind.Video, request.track) else TrackChange.Discarded(refusal),
        )
        if (refusal == null && request.automatic) {
            emitEvent(PlayerEvent.TrackChosenByPlayer(TrackKind.Video, request.track))
        }
    }

    /**
     * Whether choosing [track] as the picture needs no rebuild (#527, #529), so a source that cannot
     * seek can take it: a picture read into a cache that does not play now, or no picture, while none
     * plays or while a sound carries the clock.
     */
    private fun pictureChangesInPlace(track: TrackId?): Boolean {
        val open = session ?: return false
        val playing = open.videoStream?.index
        return if (track == null) {
            playing == null || open.audioLane != null
        } else {
            track.value != playing && track.value in open.pictureQueues
        }
    }

    /**
     * Takes the picture that plays off, in place (#529): the video lanes stop and end, the frames
     * waiting to be shown and the decoder go, and the sound and the subtitles play on untouched. The
     * picture's queue stays a cache the demux lane goes on filling, so choosing the picture again
     * plays it in place from its keyframe at the position, and the lanes start again then. The
     * renderer keeps the last picture it was given, as it does when a reopen leaves no picture.
     *
     * @return null when the picture is off, or why it is not.
     */
    private suspend fun pictureOffInPlace(session: OpenSession): String? {
        val decodeWorker = session.videoDecodeWorker
        val scheduler = session.videoScheduler
        decodeWorker?.requestQuiesce()
        scheduler?.requestQuiesce()
        val decodeParked = decodeWorker?.awaitQuiesced(QUIESCE_DEADLINE) ?: true
        val scheduleParked = scheduler?.awaitQuiesced(QUIESCE_DEADLINE) ?: true
        if (!decodeParked || !scheduleParked) {
            decodeWorker?.release(decodeWorker.epoch)
            scheduler?.release(scheduler.epoch)
            return "the video lanes did not stop within $QUIESCE_DEADLINE"
        }
        val playback = session.video
        val retired = session.videoDecoder
        // The frames go before their decoder, because a hardware frame holds one of its slots.
        playback?.flush(requestedEpoch)
        session.removePicture()
        session.videoDecoder = null
        session.videoDecoderOrigin = null
        session.coupledRenderer = null
        session.videoDecodeWorker = null
        session.videoScheduler = null
        session.pictureSwitchDiscardBeforeUs.value = Long.MIN_VALUE
        session.lastVideoPtsUs.value = NO_POSITION
        session.shownSlot.value = null
        if (retired != null) {
            withContext(NonCancellable + dispatchers.videoDecode) { runCatching { retired.close() } }
                .exceptionOrNull()?.let { failure ->
                    warn(PlaybackWarning.ResourcesNotReleased("retired video decoder: ${failure.message}"))
                }
        }
        playback?.close()
        // Released into nothing: each lane finds the session no longer runs it, and ends.
        decodeWorker?.release(decodeWorker.epoch)
        scheduler?.release(scheduler.epoch)
        // A viewer's choice stands for the whole item, so a seek brings back no picture.
        session.pictureTimeline.clear()
        tracks = tracks.withSelection(TrackKind.Video, null)
        publishSnapshot()
        return null
    }

    /**
     * Plays [stream] as the session's picture from its cache, in place (#527): no reopen and no seek,
     * so the sound and the subtitles go on untouched. A session with no picture gets the lanes an
     * open would have built for one. A session with a picture keeps its schedule, and changes its
     * decoder and its queue with both video lanes parked. The picture starts at the position, and a
     * seek back to before it plays the picture that played there; a [byViewer] choice stands for the
     * whole item instead.
     *
     * @return null when the picture plays, or why it does not.
     */
    private suspend fun playPictureInPlace(session: OpenSession, stream: PlayerStreamInfo, byViewer: Boolean): String? {
        val queue = session.pictureQueues[stream.index]
            ?: return "the demux lane does not read picture stream ${stream.index}"
        val before = session.videoStream?.index
        val startUs = currentPosition().micros
        val decodeWorker = session.videoDecodeWorker
        val scheduler = session.videoScheduler
        decodeWorker?.requestQuiesce()
        scheduler?.requestQuiesce()
        try {
            val decodeParked = decodeWorker?.awaitQuiesced(QUIESCE_DEADLINE) ?: true
            val scheduleParked = scheduler?.awaitQuiesced(QUIESCE_DEADLINE) ?: true
            if (!decodeParked || !scheduleParked) return "the video lanes did not stop within $QUIESCE_DEADLINE"
            swapPicture(session, stream, queue, startUs)?.let { return it }
        } finally {
            decodeWorker?.release(decodeWorker.epoch)
            scheduler?.release(scheduler.epoch)
        }
        if (decodeWorker == null) {
            val worker = Worker(VIDEO_DECODE_WORKER)
            session.videoDecodeWorker = worker
            worker.release(requestedEpoch)
            session.jobs += launchWorker(session, worker, dispatchers.videoDecode) { runVideoDecode(session, worker) }
        }
        if (scheduler == null) startVideoSchedule(session)
        val timeline = session.pictureTimeline
        if (byViewer || before == null) {
            timeline.clear()
            timeline += PictureSpan(Long.MIN_VALUE, stream.index)
        } else {
            if (timeline.isEmpty()) timeline += PictureSpan(Long.MIN_VALUE, before)
            recordPicture(timeline, startUs, stream.index)
        }
        pictureChanged(session, stream)
        return null
    }

    /**
     * Records in [timeline] that the picture at [index] plays from [fromUs] on, in order. A span that
     * names the picture of the span before it says nothing new and goes, so a move found again after
     * a seek back leaves one span, wherever in the move's stretch of reads it was found.
     */
    private fun recordPicture(timeline: MutableList<PictureSpan>, fromUs: Long, index: Int) {
        val at = timeline.indexOfFirst { it.fromUs > fromUs }.let { if (it < 0) timeline.size else it }
        timeline.add(at, PictureSpan(fromUs, index))
        var span = 1
        while (span < timeline.size) {
            if (timeline[span].index == timeline[span - 1].index) timeline.removeAt(span) else span++
        }
    }

    /**
     * Brings back the picture that played at [targetUs] before a seek there (#527), with every lane
     * parked by the seek, which then flushes and realigns all of it. A channel that moved its picture
     * to a new stream carries nothing of the new one before the move, so a seek back to before it
     * plays the old one again, and a seek forward past the move, or playing on, plays the new one.
     */
    private suspend fun restorePictureForSeek(session: OpenSession, targetUs: Long) {
        val timeline = session.pictureTimeline
        if (timeline.size < 2) return
        val wanted = timeline.lastOrNull { it.fromUs <= targetUs }?.index ?: return
        if (wanted == session.videoStream?.index) return
        val queue = session.pictureQueues[wanted] ?: return
        val stream = session.source.streams.firstOrNull { it.index == wanted && it.kind == TrackKind.Video } ?: return
        val refusal = swapPicture(session, stream, queue, startUs = null)
        if (refusal != null) {
            warn(PlaybackWarning.TrackDeselected(TrackId(wanted), refusal))
            return
        }
        pictureChanged(session, stream)
        emitEvent(PlayerEvent.TrackChosenByPlayer(TrackKind.Video, TrackId(wanted)))
    }

    /** What a picture taken in place tells the caller: the selection and the picture's size. */
    private fun pictureChanged(session: OpenSession, stream: PlayerStreamInfo) {
        tracks = tracks.withSelection(TrackKind.Video, TrackId(stream.index))
        stream.visibleVideoSize?.let { emitEvent(PlayerEvent.VideoSizeChanged(it)) }
        publishSnapshot()
    }

    /**
     * Makes [stream] the picture, read from [queue], with the video lanes parked or not started
     * (#527). The decoder is made before the old one goes, so a picture no decoder takes leaves the
     * old one playing. The cache starts at the keyframe before the position, and the frames before
     * [startUs] are decoded and not shown, as a precise seek does; null leaves that to a seek.
     *
     * @return null when the picture is in place, or why it is not.
     */
    private suspend fun swapPicture(
        session: OpenSession,
        stream: PlayerStreamInfo,
        queue: PacketQueue,
        startUs: Long?,
    ): String? {
        val selected = createVideoDecoder(
            session = session.backendSession,
            stream = stream,
            sourceSeekable = session.source.seekable,
            selection = if (forceBackendSoftwareForMedia) {
                VideoDecoderSelection.BackendSoftwareOnly
            } else {
                VideoDecoderSelection.Configured
            },
        ) ?: return deselectionDetail("no decoder accepted this video stream")
        val decoder = selected.decoder
        try {
            withContext(dispatchers.videoDecode) { decoder.flush(requestedEpoch) }
        } catch (failure: Throwable) {
            withContext(NonCancellable + dispatchers.videoDecode) { runCatching { decoder.close() } }
            if (failure is CancellationException) throw failure
            return "the video decoder could not align to the live epoch${causeDetail(failure)}"
        }
        val playback = session.video ?: VideoPlayback(
            renderer = session.renderer,
            clock = clock,
            containerFrameRate = stream.frameRate,
            timestampsMayJump = session.source.timestampsMayJump,
            queueCapacity = config.buffer.videoFrameQueue,
            dropPolicy = config.frameDrop,
        ).also { created ->
            created.vsyncIntervalNanos = pendingRenderer?.vsyncIntervalNanos()
            created.speed = effectiveSpeed
        }
        // The lanes are parked, so the frames of the picture before can go, and the schedule starts
        // over from the first frame of this one.
        playback.flush(requestedEpoch)
        val retired = session.videoDecoder
        session.installPicture(stream, queue, playback)
        session.videoDecoder = decoder
        session.videoDecoderOrigin = selected.origin
        session.coupledRenderer = if (selected.origin == VideoDecoderOrigin.Renderer) pendingRenderer else null
        session.rendererDecodersAsked = selected.rendererAsked
        // The cache may start inside a group of pictures, which decodes to nothing usable.
        session.videoWaitingForKeyframe.value = true
        session.pictureSwitchDiscardBeforeUs.value = startUs ?: Long.MIN_VALUE
        if (retired != null) {
            withContext(NonCancellable + dispatchers.videoDecode) { runCatching { retired.close() } }
                .exceptionOrNull()?.let { failure ->
                    warn(PlaybackWarning.ResourcesNotReleased("retired video decoder: ${failure.message}"))
                }
        }
        return null
    }

    /**
     * Starts the sound lanes of a session that opened with no sound, when one appears after the
     * open (#509). A session that opened with one has them already, idle or not.
     */
    private fun startSoundLanes(session: OpenSession) {
        val decode = Worker(AUDIO_DECODE_WORKER)
        session.audioDecodeWorker = decode
        decode.release(requestedEpoch)
        session.jobs += launchWorker(session, decode, dispatchers.audioDecode) { runAudioDecode(session, decode) }
        if (session.audioFeedWorker == null) launchFeeder(session)
    }

    /**
     * The secondary slot. In place, like every subtitle change: the demuxer already routes
     * every subtitle stream to its own live queue, so a second stream needs a decoder and a cue
     * table, never a reopen. The spec predated that and said reopen; the tree is better.
     */
    private suspend fun applySecondarySubtitle(command: CoreCommand.SelectSecondarySubtitle) {
        val session = this.session
        if (session == null) {
            command.reply.completeExceptionally(IllegalStateException("selectSecondarySubtitle needs an open media item"))
            return
        }
        val track = command.track
        if (track != null && track == tracks.selectedSubtitle) {
            command.reply.completeExceptionally(
                IllegalArgumentException("subtitle track $track is already the primary; one track cannot fill both slots"),
            )
            return
        }
        val targetExternal = track?.takeIf(::isExternalSubtitle)
        val targetStream = track
            ?.takeUnless(::isExternalSubtitle)
            ?.let { id -> session.source.streams.firstOrNull { it.index == id.value && it.kind == TrackKind.Subtitle } }
        if (track != null && targetExternal == null && targetStream == null) {
            command.reply.completeExceptionally(
                IllegalArgumentException("no subtitle stream has index ${track.value}"),
            )
            return
        }
        var preparedDecoder: io.github.yuroyami.kiteplayer.spi.SubtitleDecoder? = null
        if (targetStream != null) {
            preparedDecoder = try {
                session.backendSession.subtitleDecoders.firstNotNullOfOrNull { it.create(targetStream) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                command.reply.complete(
                    TrackChange.Discarded("the secondary subtitle decoder could not be created${causeDetail(failure)}"),
                )
                return
            }
            if (preparedDecoder == null) {
                command.reply.complete(
                    TrackChange.Discarded("no decoder accepted subtitle stream ${targetStream.index}"),
                )
                return
            }
            try {
                preparedDecoder.flush(requestedEpoch)
            } catch (cancellation: CancellationException) {
                preparedDecoder.close()
                throw cancellation
            } catch (failure: Throwable) {
                runCatching { preparedDecoder.close() }
                command.reply.complete(
                    TrackChange.Discarded("the secondary subtitle decoder could not align to the live epoch${causeDetail(failure)}"),
                )
                return
            }
        }

        session.pendingSubtitle2Packet?.close()
        session.pendingSubtitle2Packet = null
        session.subtitle2DecoderMayHaveOutput = false
        session.subtitle2Drained = false
        session.subtitle2DrainRefusals = 0
        session.lastSubtitle2PruneCutoffUs = Long.MIN_VALUE
        val retired = session.subtitle2Decoder
        session.subtitle2Stream = targetStream
        session.subtitle2Decoder = preparedDecoder
        session.subtitle2Queue = targetStream?.let { session.subtitleQueues[it.index] }
        session.subtitle2Queue?.dropBefore(
            currentPosition().micros - CUE_PRUNE_BEHIND_MICROS,
            assumedDurationUs = CUE_PRUNE_BEHIND_MICROS,
        )
        session.subtitle2Cues = when {
            targetStream != null -> session.subtitleCueCaches.getValue(targetStream.index)
            targetExternal != null -> externalSubtitleTracks.firstOrNull { it.id == targetExternal }
                ?.cues?.toMutableList() ?: mutableListOf()
            else -> mutableListOf()
        }
        selectedExternalSubtitle2 = targetExternal
        // Force the combined set to republish on the very next pass, gone cues included.
        session.publishedCueKey = null
        tracks = tracks.copy(selectedSecondarySubtitle = track)
        publishSnapshot()
        command.reply.complete(TrackChange.Applied(TrackKind.Subtitle, track))

        if (retired != null && retired !== preparedDecoder) {
            runCatching { retired.close() }.exceptionOrNull()?.let { failure ->
                warn(PlaybackWarning.ResourcesNotReleased("retired secondary subtitle decoder: ${failure.message}"))
            }
        }
    }

    private suspend fun withdrawSubtitleOverlay(session: OpenSession) {
        val rasterPublished = retireRasterJob(session)
        // The typeset lane's images are withdrawn with the rest: orphan every request first, then
        // wait for a render in flight, so nothing lands after the clear below.
        val typesetPublished = session.typeset?.let { lane ->
            lane.epoch.incrementAndGet()
            lane.job?.let { job -> job.cancel(); runCatching { job.join() } }
            lane.published.getAndSet(false)
        } ?: false
        if (session.publishedCueKey != null || typesetPublished || rasterPublished) {
            session.renderer.setOverlay(
                SubtitleOverlay(
                    images = emptyList(),
                    viewportWidth = session.publishedCanvas?.first ?: DEFAULT_SUBTITLE_CANVAS_WIDTH,
                    viewportHeight = session.publishedCanvas?.second ?: DEFAULT_SUBTITLE_CANVAS_HEIGHT,
                    contentHash = session.overlayGeneration.incrementAndGet(),
                ),
            )
        }
        session.publishedCueKey = null
        session.publishedCanvas = null
        // The drawn overlay and the published set are withdrawn together. Leaving the flow holding
        // the last cues would tell an application that text is on screen after it has been taken
        // off, which is the same defect the overlay withdrawal itself exists to prevent.
        cuesState.value = emptyList()
    }

    private fun discardSelection(kind: TrackKind, reason: String) {
        val request = pendingSelections.remove(kind) ?: return
        val track = request.track
        // The player's own choice was never a command, so its refusal names the track it gave up
        // on, as an open does for a stream nothing decodes.
        warn(
            if (request.automatic && track != null) {
                PlaybackWarning.TrackDeselected(track, reason)
            } else {
                PlaybackWarning.CommandRefused("selectTrack", reason)
            },
        )
        request.reply.complete(TrackChange.Discarded(reason))
    }

    private fun queueSelection(kind: TrackKind, track: TrackId?, reply: CompletableDeferred<TrackChange>) {
        pendingSelections.put(kind, SelectionRequest(kind, track, reply))
            ?.reply?.complete(TrackChange.Superseded(kind, track))
    }

    /** Ends every waiting selection without applying it: a stop, a close, or no media left. */
    private fun discardPendingSelections(reason: String) {
        val discarded = pendingSelections.values.toList()
        pendingSelections.clear()
        discarded.forEach { it.reply.complete(TrackChange.Discarded(reason)) }
        pendingVariant?.reply?.completeExceptionally(IllegalStateException("selectVariant did not apply: $reason"))
        pendingVariant = null
        pendingProgram?.reply?.completeExceptionally(IllegalStateException("selectProgram did not apply: $reason"))
        pendingProgram = null
    }

    /**
     * The choice for one kind: what a waiting request asked for, or what is selected now.
     *
     * Null inside a request means "none" and not "choose for me": a caller that asks for no audio
     * must get no audio, and the automatic choice is what an open does rather than what a change
     * to one track does.
     */
    private fun choiceFor(
        requested: List<SelectionRequest>,
        kind: TrackKind,
        current: Int?,
    ): StreamChoice {
        val request = requested.firstOrNull { it.kind == kind }
            ?: return current?.let { StreamChoice.At(it) } ?: StreamChoice.None
        return request.track?.let { StreamChoice.At(it.value) } ?: StreamChoice.None
    }

    private class PreparedAudioPath(
        val playback: AudioPlayback,
        val sink: AudioSink,
        val negotiated: AudioFormat,
        /** The format the device was opened for. */
        val request: AudioFormat,
    )

    /**
     * The ReplayGain to apply to [stream], from the container's tags and the configured mode.
     *
     * Returns 1 when the feature is off or nothing usable was found, which is also what an
     * unclamped measurement of zero decibels returns; `PlayerSnapshot.appliedReplayGainDb` is what
     * tells those apart. The clamp holds the peak at full scale, whatever volume boost the consumer
     * allowed: the volume stage owns boosting, and it folds loud passages where this could not.
     *
     * [containerTags] is a PARAMETER rather than a read of `session`, and that is not a style
     * choice: both callers run while the session is still being assembled, so the field is null
     * there and every container tag was silently missed. The tests caught it because a cancelling
     * preamp came out at twice the level instead of the same one.
     */
    private fun replayGainFor(stream: PlayerStreamInfo?, containerTags: Map<String, String>): Float {
        val mode = config.audio.replayGain
        if (mode == ReplayGainMode.Off) return 1f
        val tags = parseReplayGain(
            container = containerTags,
            stream = stream?.metadata ?: emptyMap(),
        )
        return replayGainLinear(
            tags = tags,
            mode = mode,
            preampDb = config.audio.replayGainPreampDb,
            fallbackDb = config.audio.replayGainFallbackDb,
        )
    }

    /** Every audio path is built here, so the open path and a track switch cannot disagree. */
    private fun newAudioPlayback(sink: AudioSink): AudioPlayback = AudioPlayback(
        sink,
        clock,
        onWarning = { warning ->
            // A refusal is reported once for the player, and the factory is not asked again.
            if (warning !is PlaybackWarning.ResamplerUnavailable || resamplerRefused.compareAndSet(false, true)) {
                warn(warning)
            }
        },
        downmix = config.audio.downmix,
        resampler = config.audio.resampler.takeUnless { resamplerRefused.value },
        upmix = config.audio.upmix,
    )

    /** Builds a dormant device path; ownership transfers only when the lane transaction commits. */
    private suspend fun prepareAudioPath(decoder: AudioDecoder, stream: PlayerStreamInfo?): PreparedAudioPath {
        val createdSink = output.audioSink.create()
        createdSink.setContent(session?.audioContent ?: AudioContent.Music)
        var createdPlayback: AudioPlayback? = null
        try {
            val playback = newAudioPlayback(createdSink)
            createdPlayback = playback
            playback.speed = effectiveSpeed
            playback.preservePitch = preservePitch
            val negotiated = playback.open(decoder.outputFormat)
            playback.volume = volume
            playback.setDuckLevel(duckLevel)
            playback.muted = muted
            playback.replayGain = replayGainFor(stream, session?.source?.metadata ?: emptyMap())
            playback.balance = balance
            playback.equalizer = equalizer
            playback.flush(requestedEpoch)
            return PreparedAudioPath(playback, createdSink, negotiated, decoder.outputFormat)
        } catch (cancellation: CancellationException) {
            if (createdPlayback != null) createdPlayback.close() else createdSink.close()
            throw cancellation
        } catch (failure: Throwable) {
            if (createdPlayback != null) createdPlayback.close() else createdSink.close()
            throw failure
        }
    }

    private fun audioCacheRefusal(queue: PacketQueue, atUs: Long): String? {
        val first = queue.firstTimestampUs
            ?: return if (queue.isEndOfStream) {
                "audio stream ${queue.streamIndex} has no cached packet at the current position"
            } else {
                "audio stream ${queue.streamIndex} has not reached the current position cache yet"
            }
        val last = queue.lastTimestampUs
            ?: return "audio stream ${queue.streamIndex} has no provable timestamp coverage"
        if (first > atUs + AUDIO_SWITCH_MAX_START_GAP_US) {
            return "audio stream ${queue.streamIndex} starts ${first - atUs}us after the current position; " +
                "the live-cache latency bound is ${AUDIO_SWITCH_MAX_START_GAP_US}us"
        }
        val wantedAhead = minOf(
            config.buffer.readyDuration.inWholeMicroseconds,
            config.buffer.softTarget.inWholeMicroseconds,
        )
        if (!queue.isEndOfStream && last < atUs + wantedAhead) {
            return "audio stream ${queue.streamIndex} has only ${last - atUs}us cached ahead; " +
                "${wantedAhead}us is required for an uninterrupted live switch"
        }
        return null
    }

    private suspend fun closePreparedAudioDecoder(decoder: AudioDecoder?) {
        if (decoder == null) return
        withContext(NonCancellable + dispatchers.audioDecode) { runCatching { decoder.close() } }
    }

    private fun drainDecodedAudio(session: OpenSession) {
        while (true) {
            val buffer = session.decodedAudio.tryReceive().getOrNull() ?: break
            buffer.close()
            session.audioInFlight.decrementAndGet()
        }
        check(session.audioInFlight.value == 0) {
            "audio workers parked with ${session.audioInFlight.value} decoded buffers still owned"
        }
    }

    private fun resetAudioAfterTrackChange(session: OpenSession, selected: Boolean) {
        session.firstAudio.clear()
        session.audioEosRequested.value = false
        session.audioTailFlushed.value = false
        endOfStream.audioDecoderDrained = !selected
        endOfStream.tailRequestedNanos = 0
        endOfStream.tailAbandoned = false
        endOfStream.draining = false
        endOfStream.drainStartedNanos = 0
        endOfStream.sinkDrained = false
        endOfStream.drainFailed = false
        endOfStream.keepOpen = false
        session.audioStatus = if (selected) StreamStatus.Syncing else StreamStatus.Ready
        demuxUnderrunSeen = false
    }

    /**
     * The audio transaction. Only audio decode/feed park; demux continues filling every
     * alternate cache and video continues decoding/scheduling on the unchanged epoch.
     */
    private suspend fun inPlaceAudioChange(session: OpenSession): Boolean {
        val request = pendingSelections[TrackKind.Audio] ?: return false
        val currentLane = session.audioLane
        val targetStream = request.track?.let { id ->
            session.source.streams.firstOrNull { it.index == id.value && it.kind == TrackKind.Audio }
        }

        if (targetStream?.index == currentLane?.stream?.index || targetStream == null && currentLane == null) {
            pendingSelections.remove(TrackKind.Audio)
            request.reply.complete(TrackChange.Applied(TrackKind.Audio, request.track))
            return true
        }

        val preflightAt = currentPosition()

        val targetQueue = targetStream?.let { session.audioQueues[it.index] }
        if (targetStream != null && targetQueue == null) {
            discardSelection(TrackKind.Audio, "audio stream ${targetStream.index} has no live packet cache")
            return true
        }
        if (targetQueue != null) {
            audioCacheRefusal(targetQueue, preflightAt.micros)?.let { reason ->
                discardSelection(TrackKind.Audio, reason)
                return true
            }
        }

        var preparedDecoder: AudioDecoder? = null
        var preparedPath: PreparedAudioPath? = null
        try {
            if (targetStream != null) {
                preparedDecoder = createAudioDecoder(session.backendSession, targetStream)
                if (preparedDecoder == null) {
                    discardSelection(TrackKind.Audio, "no decoder accepted audio stream ${targetStream.index}")
                    return true
                }
                withContext(dispatchers.audioDecode) { preparedDecoder.flush(requestedEpoch) }
                if (session.audio == null) preparedPath = prepareAudioPath(preparedDecoder, targetStream)
            }
        } catch (cancellation: CancellationException) {
            preparedPath?.playback?.close()
            closePreparedAudioDecoder(preparedDecoder)
            throw cancellation
        } catch (failure: Throwable) {
            preparedPath?.playback?.close()
            closePreparedAudioDecoder(preparedDecoder)
            discardSelection(
                TrackKind.Audio,
                "the target audio path could not be prepared${causeDetail(failure)}",
            )
            return true
        }

        val audioWorkers = listOfNotNull(session.audioDecodeWorker, session.audioFeedWorker)
        var parked = true
        for (worker in audioWorkers) {
            if (!worker.quiesce(QUIESCE_DEADLINE)) {
                parked = false
                break
            }
        }
        if (!parked) {
            audioWorkers.forEach { it.release(requestedEpoch) }
            preparedPath?.playback?.close()
            closePreparedAudioDecoder(preparedDecoder)
            discardSelection(
                TrackKind.Audio,
                "audio workers did not reach a safe switch boundary within $QUIESCE_DEADLINE",
            )
            return true
        }

        // Decoder construction and cooperative parking can take real wall time while audio A and
        // video continue. The transaction's boundary is therefore NOW, with both audio workers
        // parked, not the optimistic position sampled during preflight. Revalidate because the
        // demux lane may also have pruned the inactive cache while preparation was in flight.
        session.audio?.anchorClock()
        val commitAt = currentPosition()
        if (targetQueue != null) {
            audioCacheRefusal(targetQueue, commitAt.micros)?.let { reason ->
                audioWorkers.forEach { it.release(requestedEpoch) }
                preparedPath?.playback?.close()
                closePreparedAudioDecoder(preparedDecoder)
                discardSelection(TrackKind.Audio, reason)
                return true
            }
        }
        publishedPositionMicros.value = commitAt.micros

        val playback = session.audio ?: preparedPath?.playback
        try {
            drainDecodedAudio(session)
            playback?.flush(requestedEpoch)
            tapsDiscontinuous()
        } catch (cancellation: CancellationException) {
            audioWorkers.forEach { it.release(requestedEpoch) }
            preparedPath?.playback?.close()
            closePreparedAudioDecoder(preparedDecoder)
            throw cancellation
        } catch (failure: Throwable) {
            audioWorkers.forEach { it.release(requestedEpoch) }
            preparedPath?.playback?.close()
            closePreparedAudioDecoder(preparedDecoder)
            session.audioDeviceNeedsStart = playRequested && currentLane != null
            discardSelection(TrackKind.Audio, "the audio ring refused the live swap${causeDetail(failure)}")
            return true
        }

        if (preparedPath != null) {
            session.audio = preparedPath.playback
            session.sink = preparedPath.sink
            session.negotiatedFormat = preparedPath.negotiated
            session.deviceRequest = preparedPath.request
        } else if (targetStream != null) {
            // A reused path still carries the previous stream's ReplayGain and peak clamp (#288).
            // Set here, after the ring flush succeeded, so a refused switch keeps the old gain.
            session.audio?.replayGain = replayGainFor(targetStream, session.source.metadata)
        }
        targetQueue?.dropBefore(
            commitAt.micros - audioSwitchHistoryUs(),
            assumedDurationUs = AUDIO_PRUNE_ASSUMED_PACKET_DURATION_US,
            untimedEndsByNext = true,
        )
        session.audioSwitchDiscardBeforeUs.value = if (targetStream == null) Long.MIN_VALUE else commitAt.micros
        val targetLane = if (targetStream != null && preparedDecoder != null && targetQueue != null) {
            AudioLane(targetStream, preparedDecoder, targetQueue)
        } else {
            null
        }
        session.installAudioLane(targetLane)
        resetAudioAfterTrackChange(session, targetLane != null)

        val retiredDecoder = currentLane?.decoder
        if (retiredDecoder != null && retiredDecoder !== preparedDecoder) {
            withContext(dispatchers.audioDecode) {
                runCatching { retiredDecoder.close() }.exceptionOrNull()?.let { failure ->
                    warn(PlaybackWarning.ResourcesNotReleased("retired audio decoder: ${failure.message}"))
                }
            }
        }
        audioWorkers.forEach { it.release(requestedEpoch) }
        // A session that opened with no sound has no sound lanes until one appears (#509).
        if (targetLane != null && session.audioDecodeWorker == null) startSoundLanes(session)
        if (preparedPath != null) {
            emitEvent(
                PlayerEvent.AudioFormatChanged(
                    preparedPath.negotiated.sampleRate,
                    preparedPath.negotiated.channels,
                ),
            )
            startAudioEventCollector(session)
        }
        session.audioDeviceNeedsStart = playRequested && targetLane != null
        tracks = tracks.withSelection(TrackKind.Audio, request.track)
        // Before the reply, so a caller that awaited the audio reads the subtitle that goes with it,
        // and while the request is still pending, so a close that cancels this answers it.
        chooseSubtitleForAudio(session)
        publishSnapshot()
        pendingSelections.remove(TrackKind.Audio)
        request.reply.complete(TrackChange.Applied(TrackKind.Audio, request.track))
        if (request.automatic) emitEvent(PlayerEvent.TrackChosenByPlayer(TrackKind.Audio, request.track))
        return true
    }

    /** Audio/subtitle are live transactions; only video selection rebuilds the session. */
    private suspend fun handleTrackChanges() {
        // A renderer failure has already torn the old graph down. The recovery reopen folds these
        // choices into its one replacement graph so nothing is lost and no second open happens.
        if (pendingVideoRecovery != null) return
        if (pendingSelections.isEmpty() && !reopenPending) return
        // A picture the demux lane reads into a cache plays in place, as a sound does (#527).
        if (!reopenPending) session?.let { inPlacePictureChange(it) }
        // Owner report 2026-08-26: a subtitle-only change must not ride the full
        // reopen below, which was built for video and audio switches and visibly interrupts
        // playback. Container subtitle tracks get the same in-place treatment external subtitles
        // always had. A refusal (demux would not park, stale id, external target) discards the
        // pending entry instead: the rebuild below runs for the kinds this branch does not take,
        // which is video, and for an audio or subtitle change it never reached.
        if (TrackKind.Video !in pendingSelections && !reopenPending) {
            val active = session
            if (active != null) {
                if (TrackKind.Subtitle in pendingSelections) inPlaceContainerSubtitleChange(active)
                if (TrackKind.Audio in pendingSelections) inPlaceAudioChange(active)
                if (pendingSelections.isEmpty()) return
            }
        }
        // Taken and cleared together: everything asked for so far rides ONE rebuild, and a request
        // arriving during it belongs to the next one.
        val requested = pendingSelections.values.toList()
        pendingSelections.clear()
        // A variant or programme change reopens the item on the chosen one, through the same
        // rebuild.
        val variantRequest = pendingVariant
        pendingVariant = null
        val programRequest = pendingProgram
        pendingProgram = null
        val reopening = variantRequest != null || programRequest != null
        val current = session
        val item = media?.let { open ->
            var demux = open.demux
            if (variantRequest != null) demux = demux.copy(variant = variantRequest.index)
            if (programRequest != null) demux = demux.copy(program = programRequest.number)
            if (demux == open.demux) open else open.copy(demux = demux)
        }
        if (current == null || item == null) {
            requested.forEach {
                it.reply.complete(
                    TrackChange.Discarded("no media is open, so the selection had nothing to apply to"),
                )
            }
            variantRequest?.reply?.completeExceptionally(IllegalStateException("no media is open"))
            programRequest?.reply?.completeExceptionally(IllegalStateException("no media is open"))
            return
        }
        media = item
        val at = currentPosition()
        val wasPlaying = playRequested
        val secondaryBefore = tracks.selectedSecondarySubtitle
        val audioBefore = current.audioStream?.index
        // A live sender cannot be sought back, so a programme change joins it where it is now.
        val seekBack = current.source.seekable
        // Another variant numbers its streams its own way, and another programme has tracks of its
        // own, so what was not asked for is chosen again.
        fun keptOrChosen(kind: TrackKind, index: Int?): StreamChoice = when {
            !reopening || requested.any { it.kind == kind } -> choiceFor(requested, kind, index)
            index == null -> StreamChoice.None
            else -> StreamChoice.Auto
        }
        val video = keptOrChosen(TrackKind.Video, current.videoStream?.index)
        val audio = keptOrChosen(TrackKind.Audio, current.audioStream?.index)
        // An external subtitle target means NO container stream: the rebuild deselects
        // whatever container track was timing cues, and the external table applies afterwards.
        val subtitleRequest = requested.firstOrNull { it.kind == TrackKind.Subtitle }
        val subtitle = if (subtitleRequest != null && isExternalSubtitle(subtitleRequest.track)) {
            StreamChoice.None
        } else {
            keptOrChosen(TrackKind.Subtitle, current.subtitleStream?.index)
        }
        try {
            teardownSession()
            requestedEpoch = requestedEpoch.next()
            var rebuilt = buildSession(item, video, audio, subtitle)
            session = rebuilt
            // Fresh decoders stamp Generation.Initial, and the reposition seek below that would
            // align them runs only above zero. At zero every frame was dropped as stale (#277).
            flushDecoders(rebuilt, requestedEpoch)
            clearBuffers(rebuilt, requestedEpoch)
            rebuilt.videoParked.value = !videoEnabled
            startWorkers(rebuilt)
            var recoveredAndPositioned = false
            when (awaitInitialFill(rebuilt)) {
                FillOutcome.WorkerFinished -> {
                    val observed = recoverObservedVideoFailure(rebuilt, at)
                    if (observed == null) throw workerOutcomeException(rebuilt, "before the track change could refill")
                    val recovered = observed.result ?: run {
                        requested.forEach {
                            it.reply.complete(TrackChange.Discarded(PREEMPTED_SELECTION))
                        }
                        // Taken out of its slot above, so nothing later can answer it (#525).
                        variantRequest?.reply?.completeExceptionally(IllegalStateException(PREEMPTED_SELECTION))
                        programRequest?.reply?.completeExceptionally(IllegalStateException(PREEMPTED_SELECTION))
                        return
                    }
                    rebuilt = recovered.session
                    recoveredAndPositioned = true
                }
                FillOutcome.TimedOut -> warn(
                    PlaybackWarning.StartupIncomplete("no stream reached readiness within $OPEN_FILL_DEADLINE after the track change"),
                )
                FillOutcome.Ready -> Unit
                // The same lie an open used to tell: a stop is already queued, so
                // publishing a status and reporting the selection applied would be undone by the
                // very next command.
                FillOutcome.Preempted -> {
                    teardownSession()
                    requested.forEach { it.reply.complete(TrackChange.Discarded(PREEMPTED_SELECTION)) }
                    variantRequest?.reply?.completeExceptionally(IllegalStateException(PREEMPTED_SELECTION))
                    programRequest?.reply?.completeExceptionally(IllegalStateException(PREEMPTED_SELECTION))
                    return
                }
            }
            // A user seek that was already queued outranks the reposition. A successful decoder
            // recovery has already performed the internal precise reposition and must not queue it twice.
            // A paused player repositions at zero too: nothing else hands the rebuilt path a picture,
            // so a renderer that arrived before the first play stayed black (#384).
            if (seekBack && !recoveredAndPositioned && pendingSeek == null && (at > Pts.Zero || !wasPlaying)) {
                pendingSeek = SeekRequest(SeekTarget.Absolute(at), SeekMode.Precise)
            }
            playRequested = wasPlaying
            // The rebuild replaced the track table and started an empty secondary slot. The
            // external rows and both subtitle selections go back on top of it.
            restoreSubtitleState(secondaryBefore)
            refreshTypesetting()
            // An audio change that rode the rebuild, beside a video change or on another variant,
            // takes the subtitle that goes with it as the in-place one does (#506).
            if (reopening || rebuilt.audioStream?.index != audioBefore) chooseSubtitleForAudio(rebuilt)
            setStatus(if (wasPlaying) PlaybackStatus.Buffering else PlaybackStatus.Paused)
            // Published before the replies, as the in-place audio and subtitle changes do. The
            // status write above publishes only when the status moves, and a paused player's
            // stays Paused, so a caller on another thread read the old selection on return.
            publishSnapshot()
            requested.forEach { it.reply.complete(TrackChange.Applied(it.kind, it.track)) }
            variantRequest?.reply?.complete(Unit)
            programRequest?.reply?.complete(Unit)
        } catch (cancellation: CancellationException) {
            variantRequest?.reply?.completeExceptionally(
                if (closedNow.value) IllegalStateException("the player was closed before selectVariant could finish") else cancellation,
            )
            programRequest?.reply?.completeExceptionally(
                if (closedNow.value) IllegalStateException("the player was closed before selectProgram could finish") else cancellation,
            )
            requested.forEach {
                it.reply.completeExceptionally(
                    if (closedNow.value) {
                        IllegalStateException("the player was closed before selectTrack could finish")
                    } else {
                        cancellation
                    },
                )
            }
            throw cancellation
        } catch (preempted: OpenPreempted) {
            teardownSession()
            requested.forEach { it.reply.complete(TrackChange.Discarded(PREEMPTED_SELECTION)) }
            variantRequest?.reply?.completeExceptionally(IllegalStateException(PREEMPTED_SELECTION))
            programRequest?.reply?.completeExceptionally(IllegalStateException(PREEMPTED_SELECTION))
        } catch (failure: Throwable) {
            val error = classify(failure, item)
            teardownSession()
            fail(error)
            requested.forEach { it.reply.completeExceptionally(PlaybackException(error)) }
            variantRequest?.reply?.completeExceptionally(PlaybackException(error))
            programRequest?.reply?.completeExceptionally(PlaybackException(error))
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
            emitEvent(
                PlayerEvent.FirstFrameRendered((clock.nanos() - openedAtNanos).nanoseconds),
            )
        }
        if (video.queuedFrames > 0 && session.schedulerMode.value == SCHEDULER_RUNNING) {
            wakeIn(FRAME_WAKE)
        }
    }

    private data class VideoRecovery(
        val item: MediaItem,
        val video: StreamChoice,
        val audio: StreamChoice,
        val subtitle: StreamChoice,
        val position: Pts,
        val duration: Pts?,
        val codec: String,
        val subtitleSelectionAvailable: Boolean,
        val failure: VideoDecoderRuntimeFailure,
    )

    private data class VideoRecoveryResult(
        val session: OpenSession,
        val landedAt: Pts,
        val epoch: Generation,
    )

    private fun videoRecoveryFor(
        active: OpenSession,
        failure: Throwable,
        position: Pts = currentPosition(),
    ): VideoRecovery? {
        val decoderFailure = failure as? VideoDecoderRuntimeFailure ?: return null
        val decoder = active.videoDecoder ?: return null
        val hardware = decoder.hardware
        when (hardware) {
            is HwdecStatus.HardwareZeroCopy, is HwdecStatus.HardwareWithDownload -> Unit
            HwdecStatus.Software -> return null
        }
        val policyAllowsRecovery = when (config.hardwareDecode) {
            HwdecPolicy.Auto -> true
            HwdecPolicy.Off, HwdecPolicy.Require, is HwdecPolicy.Prefer -> false
        }
        if (!policyAllowsRecovery || videoRecoveryAttempted) return null
        // Origin-agnostic on purpose: a renderer-coupled MediaCodec session and a backend hwaccel
        // (VideoToolbox invalidated the moment iOS backgrounds the app) die the same way, and both
        // recover the same way, by reopening the seekable source with backend software at the
        // current position. Gating this on the renderer origin turned every backend hardware
        // failure into a dead player.
        if (!active.source.seekable) return null
        val item = media ?: return null
        val stream = active.videoStream ?: return null
        return VideoRecovery(
            item = item,
            video = StreamChoice.At(stream.index),
            audio = active.audioStream?.let { StreamChoice.At(it.index) } ?: StreamChoice.None,
            subtitle = active.subtitleStream?.let { StreamChoice.At(it.index) } ?: StreamChoice.None,
            position = position,
            duration = active.source.seekCeiling,
            codec = stream.codec,
            subtitleSelectionAvailable = active.backendSession.subtitleDecoders.isNotEmpty(),
            failure = decoderFailure,
        )
    }

    /** Completes a recovery deferred by the worker-outcome handler so queued user seeks win. */
    private suspend fun handlePendingVideoRecovery(recovery: VideoRecovery) {
        // Taken and cleared together, like the ordinary rebuild: the recovery reopen IS the one
        // replacement graph, so every waiting selection rides it and is answered by it.
        val requested = pendingSelections.values.toList()
        pendingSelections.clear()
        val requestedRecovery = if (requested.isEmpty()) {
            recovery
        } else {
            recovery.copy(
                video = choiceFor(requested, TrackKind.Video, recovery.video.selectedIndex()),
                audio = choiceFor(requested, TrackKind.Audio, recovery.audio.selectedIndex()),
                subtitle = choiceFor(requested, TrackKind.Subtitle, recovery.subtitle.selectedIndex()),
            )
        }
        val userSeek = pendingSeek
        val target = userSeek?.resolve(requestedRecovery.position, requestedRecovery.duration)
            ?: requestedRecovery.position
        if (userSeek != null) {
            pendingSeek = null
            seekHeldSinceNanos = 0L
            seekPhase = SeekPhase.Idle
        }
        try {
            val result = reopenWithBackendSoftware(
                recovery = requestedRecovery,
                requestedTarget = target,
                mode = userSeek?.mode ?: SeekMode.Precise,
            ) ?: run {
                // A stop or a close preempted the reopen, so the graph these selections asked for
                // never came to exist. Saying so beats leaving the callers to infer it.
                requested.forEach { it.reply.complete(TrackChange.Discarded(PREEMPTED_SELECTION)) }
                return
            }
            // An audio change that rode the recovery takes the subtitle that goes with it (#506).
            if (result.session.audioStream?.index != recovery.audio.selectedIndex()) chooseSubtitleForAudio(result.session)
            publishSnapshot()
            requested.forEach { it.reply.complete(TrackChange.Applied(it.kind, it.track)) }
            if (userSeek != null) {
                emitEvent(PlayerEvent.SeekCompleted(result.epoch, result.landedAt.asDuration))
                resolveSeekReplies(SeekResult.Applied(result.landedAt))
            }
            setStatus(if (playRequested) PlaybackStatus.Buffering else PlaybackStatus.Paused)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val error = softwareRecoveryFailure(requestedRecovery, failure)
            teardownSession()
            resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
            requested.forEach { it.reply.completeExceptionally(PlaybackException(error)) }
            fail(error)
        }
    }

    /**
     * Reopens the media rather than replaying packets into another decoder.
     *
     * The failed session has already been torn down. The replacement is backend-only and software-only;
     * its decoders and queues are put directly into the new generation before any worker can touch them.
     */
    private suspend fun reopenWithBackendSoftware(
        recovery: VideoRecovery,
        requestedTarget: Pts,
        mode: SeekMode = SeekMode.Precise,
    ): VideoRecoveryResult? {
        if (preempted()) return null
        endOfStream.reset()
        demuxUnderrunSeen = false
        stillImageFinished = false
        stillImageShownSinceNanos = 0
        requestedEpoch = requestedEpoch.next()
        val epoch = requestedEpoch
        val durationUs = recovery.duration?.micros ?: Long.MAX_VALUE
        val target = Pts(requestedTarget.micros.coerceIn(0L, durationUs.coerceAtLeast(0L)))
        // Read before buildSession replaces the table it lives in.
        val secondaryBefore = tracks.selectedSecondarySubtitle
        val rebuilt = try {
            buildSession(
                item = recovery.item,
                videoChoice = recovery.video,
                audioChoice = recovery.audio,
                subtitleChoice = recovery.subtitle,
                videoSelection = VideoDecoderSelection.BackendSoftwareOnly,
            )
        } catch (preempted: OpenPreempted) {
            return null
        }
        session = rebuilt
        verifyRecoveredTracks(recovery, rebuilt)
        if (preempted()) {
            teardownSession()
            return null
        }

        var attempt = 0
        var landed: Pts?
        while (true) {
            if (attempt > 0) {
                rebuilt.schedulerMode.value = SCHEDULER_IDLE
                stopAudioDevice(rebuilt)
                if (!quiesceWorkers(rebuilt)) {
                    error("the software recovery workers did not quiesce for precise preroll")
                }
            }
            val backoff = SeekTiming.OVERSHOOT_BACKOFF_US[attempt]
            val aim = Pts((target.micros - backoff).coerceAtLeast(0L))
            flushDecoders(rebuilt, epoch)
            clearBuffers(rebuilt, epoch)
            withContext(dispatchers.demux) { rebuilt.source.seekToKeyframe(aim) }
            rebuilt.discardBeforeUs.value = when (mode) {
                SeekMode.Keyframe -> Long.MIN_VALUE
                else -> target.micros
            }
            rebuilt.firstVideo.clear()
            rebuilt.firstDecodedVideo.clear()
            rebuilt.firstAudio.clear()
            if (attempt == 0) startWorkers(rebuilt) else releaseWorkers(rebuilt, epoch)
            landed = awaitLanding(rebuilt, epoch)
            rebuilt.firstWorkerOutcome.value?.cause?.let { throw it }
            if (preempted()) {
                teardownSession()
                return null
            }
            val decoded = rebuilt.firstDecodedVideo.of(epoch) ?: rebuilt.firstAudio.of(epoch)
            val overshot = decoded != null &&
                decoded.micros > target.micros + SeekTiming.PRECISE_TOLERANCE_US
            val laddered = attempt < SeekTiming.OVERSHOOT_BACKOFF_US.lastIndex && aim.micros > 0L
            if (!overshot || !laddered) break
            attempt++
        }

        rebuilt.discardBeforeUs.value = Long.MIN_VALUE
        seekPhase = SeekPhase.Idle
        val endOfStreamLanding = rebuilt.selectedQueues().all { it.isEndOfStream } && rebuilt.decodersDrained()
        if (landed == null && !endOfStreamLanding) {
            error("the software recovery produced no frame at $target within $SEEK_DEADLINE")
        }
        val applied = landed ?: target
        publishedPositionMicros.value = applied.micros
        reportFirstFrame(rebuilt, "decoder recovery")
        rebuilt.firstWorkerOutcome.value?.cause?.let { throw it }
        if (preempted()) {
            teardownSession()
            return null
        }
        // The same subtitle state an ordinary rebuild puts back: external rows, both selections,
        // and the typesetter for the primary track.
        restoreSubtitleState(secondaryBefore)
        refreshTypesetting()
        warn(
            PlaybackWarning.HardwareDecodeUnavailable(
                recovery.codec,
                "hardware video ${recovery.failure.operation} failed${causeDetail(recovery.failure.cause ?: recovery.failure)}; " +
                    "reopened the seekable source with backend software at ${applied.micros} us",
            ),
        )
        return VideoRecoveryResult(rebuilt, applied, epoch)
    }

    private fun verifyRecoveredTracks(recovery: VideoRecovery, rebuilt: OpenSession) {
        fun restored(choice: StreamChoice, index: Int?): Boolean = when (choice) {
            StreamChoice.Auto -> true
            StreamChoice.None -> index == null
            is StreamChoice.At -> choice.index == index
        }
        check(restored(recovery.video, rebuilt.videoStream?.index)) { "the software decoder refused the selected video track" }
        check(restored(recovery.audio, rebuilt.audioStream?.index)) { "the recovered session lost the selected audio track" }
        check(restored(recovery.subtitle, rebuilt.subtitleStream?.index)) { "the recovered session lost the selected subtitle track" }
        if (recovery.video != StreamChoice.None) {
            check(rebuilt.videoDecoderOrigin == VideoDecoderOrigin.Backend) {
                "software recovery selected a renderer-coupled decoder"
            }
            check(rebuilt.videoDecoder?.hardware == HwdecStatus.Software) {
                "the backend ignored HwdecPolicy.Off and did not return a software decoder"
            }
        }
    }

    private fun softwareRecoveryFailure(recovery: VideoRecovery, failure: Throwable): PlaybackError.DecoderFailed =
        PlaybackError.DecoderFailed(
            codec = recovery.codec,
            detail = "hardware video ${recovery.failure.operation} failed" +
                causeDetail(recovery.failure.cause ?: recovery.failure) +
                "; reopening with backend software failed${causeDetail(failure)}",
            cause = failure,
        )

    private data class ObservedVideoRecovery(val result: VideoRecoveryResult?)

    /** Routes a decoder crash noticed by an in-progress open/seek before the actor drains outcomes. */
    private suspend fun recoverObservedVideoFailure(
        active: OpenSession,
        target: Pts,
        mode: SeekMode = SeekMode.Precise,
    ): ObservedVideoRecovery? {
        val outcome = active.firstWorkerOutcome.value ?: return null
        if (outcome.sessionToken != active.token || outcome.name != VIDEO_DECODE_WORKER) return null
        val recovery = videoRecoveryFor(active, outcome.cause ?: return null, target) ?: return null
        videoRecoveryAttempted = true
        forceBackendSoftwareForMedia = true
        setStatus(PlaybackStatus.Buffering)
        teardownSession()
        return try {
            ObservedVideoRecovery(reopenWithBackendSoftware(recovery, target, mode))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            teardownSession()
            throw PlaybackException(softwareRecoveryFailure(recovery, failure))
        }
    }

    /** Routes a decoder call made by the actor itself, such as flush during a seek. */
    private suspend fun recoverDirectVideoFailure(
        active: OpenSession,
        failure: VideoDecoderRuntimeFailure,
        target: Pts,
        mode: SeekMode = SeekMode.Precise,
    ): ObservedVideoRecovery? {
        val recovery = videoRecoveryFor(active, failure, target) ?: return null
        videoRecoveryAttempted = true
        forceBackendSoftwareForMedia = true
        setStatus(PlaybackStatus.Buffering)
        teardownSession()
        return try {
            ObservedVideoRecovery(reopenWithBackendSoftware(recovery, target, mode))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (recoveryFailure: Throwable) {
            teardownSession()
            throw PlaybackException(softwareRecoveryFailure(recovery, recoveryFailure))
        }
    }

    private fun workerOutcomeException(session: OpenSession, context: String): PlaybackException {
        val outcome = session.firstWorkerOutcome.value
        val cause = outcome?.cause
        val error = when (outcome?.name) {
            VIDEO_DECODE_WORKER -> PlaybackError.DecoderFailed(
                session.videoStream?.codec ?: "video",
                cause?.message ?: "the video decoder stopped $context",
                cause,
            )
            AUDIO_DECODE_WORKER -> PlaybackError.DecoderFailed(
                session.audioStream?.codec ?: "audio",
                cause?.message ?: "the audio decoder stopped $context",
                cause,
            )
            DEMUX_WORKER -> (cause as? PlaybackException)?.error ?: PlaybackError.SourceUnavailable(
                media?.uri ?: "",
                cause ?: IllegalStateException("the demuxer stopped $context"),
                "the demuxer stopped $context${cause?.let(::causeDetail).orEmpty()}",
            )
            AUDIO_FEED_WORKER, AUDIO_DEVICE -> audioFeedError(session, cause) { "a pipeline worker stopped $context" }
            else -> PlaybackError.Internal("a pipeline worker stopped $context", cause)
        }
        return PlaybackException(error)
    }

    /**
     * What a dead audio feed reports. The audio pipeline's refusals are already typed, and they
     * get the stream's codec here, where it is known. Anything else is a bug here.
     */
    private fun audioFeedError(session: OpenSession, cause: Throwable?, detail: () -> String): PlaybackError =
        when (val error = (cause as? PlaybackException)?.error) {
            is PlaybackError.DecoderFailed -> error.copy(codec = session.audioStream?.codec ?: error.codec)
            null -> PlaybackError.Internal(detail(), cause)
            else -> error
        }

    /**
     * The start rendezvous: playback begins only when every selected stream can supply it.
     *
     * Level triggered, so a play() that arrived during an open or a seek is honoured here as soon as the
     * pipeline is ready, and a rebuffer leaves through the same door it came in by.
     */
    private suspend fun handlePlaybackRestart() {
        pendingVideoRecovery?.let { recovery ->
            pendingVideoRecovery = null
            handlePendingVideoRecovery(recovery)
            return
        }
        val session = session ?: return
        if (seekPhase.isRunning) return
        if (status == PlaybackStatus.Ended || status == PlaybackStatus.Failed) return
        if (!playRequested) return
        // After frame steps the picture can be ahead of the paused sound. Started as it stands, the
        // sound would restart where playback paused and the picture would stand still until the
        // sound caught up, so play first seeks to the frame on screen. The seek clears the flag.
        val picture = session.video?.shownPts()
        if (session.pictureHoldsPosition && picture != null && session.source.seekable &&
            soundLagsPicture(session, picture)
        ) {
            if (pendingSeek == null) queueSeek(SeekRequest(SeekTarget.Absolute(picture), SeekMode.Precise), null)
            return
        }
        // A live audio swap intentionally leaves status and video scheduling untouched. Start the
        // emptied device only after the new lane has submitted data, avoiding a burst of synthetic
        // underruns while the picture continues on its own clock.
        var startedAudioThisPass = false
        if (session.audioDeviceNeedsStart) {
            val audio = session.audio
            if (session.audioLane == null) {
                session.audioDeviceNeedsStart = false
            } else if (audio != null && audio.buffered > Duration.ZERO) {
                audio.play()
                startedAudioThisPass = true
                session.audioDeviceNeedsStart = false
            } else {
                wakeIn(WORKER_POLL)
                return
            }
        }
        if (!everySelectedStreamReady(session)) {
            // Owner report 2026-08-25, reversing the older "never declare Buffering
            // here" rule): the wait this branch takes is real, so the status must say so. A play
            // against a pipeline that cannot supply it yet IS Buffering by that state's own
            // definition, and leaving Paused standing made every tap after a seek look dropped on
            // a slow source: the restart honoured the play seconds later and nothing ever said a
            // wait was happening. Entered only from Paused with playRequested true, so the
            // paused-seek rule in runSeek and the open's own states are untouched, and
            // handleBuffering still owns the during-playback entry and its rebuffer count.
            if (status == PlaybackStatus.Paused) setStatus(PlaybackStatus.Buffering)
            wakeIn(WORKER_POLL)
            return
        }
        if (status != PlaybackStatus.Playing) {
            // The device path intentionally survives audio disable/enable cycles, but a dormant
            // path is not a selected output. Restarting it here would resume callbacks against an
            // empty ring every time play() followed an audio-off pause.
            if (!startedAudioThisPass && session.audioLane != null) session.audio?.play()
            setStatus(PlaybackStatus.Playing)
        }
        // The clocks run again, so they carry the position from here.
        session.pictureHoldsPosition = false
        session.schedulerMode.value = SCHEDULER_RUNNING
    }

    /**
     * True when the sound would restart more than the sync law tolerates before [picture].
     *
     * A sound clock with no reading counts as lagging. That is what a seek leaves until the device
     * plays, and a backward step is a seek, so the sound would restart at that step's target and
     * not at a picture that forward steps moved on from there.
     */
    private fun soundLagsPicture(session: OpenSession, picture: Pts): Boolean {
        if (session.audioLane == null) return false
        val sound = session.audio?.position() ?: return true
        return picture.micros - sound.micros > SyncLaw.SYNC_THRESHOLD_MAX_US
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

    /** Withdraws the seek mask, unless an accepted request still owns it. */
    private fun clearSeekMaskUnlessPending() {
        if (pendingSeek == null) maskedSeekTargetMicros.value = NO_SEEK_MASK
    }

    private fun handlePlaybackTime() {
        val session = session ?: return
        // The mask lives exactly as long as a request is queued or held. drainCommands runs before
        // this handler, so a newly accepted request re-arms the mask before this pass can clear it,
        // and the clock resumes publishing only once the machine has actually drained.
        if (pendingSeek == null) {
            maskedSeekTargetMicros.value = NO_SEEK_MASK
            publishedPositionMicros.value = currentPosition().micros
        }
        // Chapter crossings: compared on the published reading, so a seek and ordinary
        // playback announce a boundary the same way. Media with no table emits nothing.
        val chapters = session.source.chapters
        if (chapters.isNotEmpty()) {
            val positionUs = publishedPositionMicros.value
            // The same shared reading the facade uses, so an event and a query can never disagree
            // about which chapter is playing. A position in a gap belongs to no chapter, which is
            // reported as one: null.
            val current = chapters.chapterHolding(positionUs)
            val index = current?.let { chapters.indexOf(it) } ?: -1
            if (index != lastChapterIndex) {
                lastChapterIndex = index
                emitEvent(PlayerEvent.ChapterChanged(current))
            }
        }
        // Markers: announced on CROSSING while playing. The cursor is the published reading of the
        // previous pass; a seek moves it to the landing without announcing anything, which is what
        // lets a backward seek or a loop re-arm the markers behind the new position.
        if (markers.isNotEmpty() && pendingSeek == null) {
            val positionUs = publishedPositionMicros.value
            if (markerCursorEpoch != requestedEpoch || markerCursorUs == NO_POSITION) {
                markerCursorEpoch = requestedEpoch
                markerCursorUs = positionUs
            } else if (status == PlaybackStatus.Playing && positionUs > markerCursorUs) {
                for (marker in markers) {
                    val markerUs = marker.position.inWholeMicroseconds
                    if (markerUs > markerCursorUs && markerUs <= positionUs) {
                        emitEvent(PlayerEvent.MarkerReached(marker))
                    }
                }
                markerCursorUs = positionUs
            }
            if (status == PlaybackStatus.Playing) {
                // Wake when the next marker lands rather than a whole pass later, the way the A-B
                // loop does: media distance over rate is wall distance.
                markers.firstOrNull { it.position.inWholeMicroseconds > positionUs }?.let { next ->
                    wakeIn(((next.position.inWholeMicroseconds - positionUs) / wakeRate()).toLong().microseconds)
                }
            }
        }
        if (session.isStillImage && session.framesOut(session.video) > 0) {
            if (stillImageShownSinceNanos == 0L) stillImageShownSinceNanos = clock.nanos()
            val shownFor = (clock.nanos() - stillImageShownSinceNanos).nanoseconds
            if (shownFor >= STILL_IMAGE_DURATION) stillImageFinished = true
            else wakeIn(STILL_IMAGE_DURATION - shownFor)
        }
        // The A-B loop's B crossing: compared on the published reading like the chapters,
        // so a wrap is impossible while a seek is in flight and the pass after one starts clean.
        // Playing only: a paused player may be seeked past B and inspected there. The wrap is an
        // ordinary precise seek, so an unseekable source cannot wrap; arming refused the live
        // case, and a loop armed before such an open never fires and was warned at the open.
        // A next pass that follows B in the ring keeps the position short of B, so this wraps
        // only a turn that no pass follows (#467).
        val loopA = abLoopA
        val loopB = abLoopB
        if (loopA != null && loopB != null && pendingSeek == null &&
            status == PlaybackStatus.Playing && session.source.seekable
        ) {
            val positionUs = publishedPositionMicros.value
            val bUs = loopB.inWholeMicroseconds
            if (positionUs >= bUs) {
                queueSeek(
                    SeekRequest(SeekTarget.Absolute(Pts(loopA.inWholeMicroseconds)), SeekMode.Precise),
                    null,
                )
            } else {
                // Wake when B lands rather than a whole pass later. Media distance over rate is
                // wall distance, the same division the schedule itself makes.
                wakeIn(((bUs - positionUs) / wakeRate()).toLong().microseconds)
            }
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
        if (endStalledSession(session)) return
        stepDownWhenStarved(session)
        stepDownWhenLinkTooSlow(session)
        stepUpWhenFast(session)
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

    /**
     * Steps an HLS stream down to the next variant with a lower bitrate when playback has waited for
     * data for [VARIANT_STEP_DOWN_AFTER] while playing, long before the stall timeout would end it
     * (#376). Only while the player chooses the variant itself: a variant the caller selected stays.
     * [stepUpWhenFast] waits after this before it tries a higher variant again.
     */
    private fun stepDownWhenStarved(session: OpenSession) {
        val starving = playRequested && status == PlaybackStatus.Buffering && firstFrameSeen &&
            pendingSeek == null && !reopenPending
        if (!starving) {
            starvedSinceNanos = NO_POSITION
            return
        }
        val now = clock.nanos()
        if (starvedSinceNanos == NO_POSITION) starvedSinceNanos = now
        val waited = (now - starvedSinceNanos).nanoseconds
        if (waited < VARIANT_STEP_DOWN_AFTER) {
            wakeIn(VARIANT_STEP_DOWN_AFTER - waited)
            return
        }
        starvedSinceNanos = NO_POSITION
        val item = media ?: return
        if (item.demux.variant != null && !variantChosenByPlayer) return
        if (!session.source.seekable) return
        val current = tracks.variants.firstOrNull { it.index == tracks.selectedVariant } ?: return
        val lower = tracks.variants.filter { it.bitrate < current.bitrate }.maxByOrNull { it.bitrate } ?: return
        lowerVariant(current, lower, "playback waited ${waited.inWholeMilliseconds} ms for data at ${current.bitrate} bits per second")
    }

    /**
     * Steps an HLS stream down when the link cannot carry the variant that plays, before the
     * buffer runs dry (#376). A link a little too slow for its variant makes short pauses that
     * never last the [VARIANT_STEP_DOWN_AFTER] of [stepDownWhenStarved], so this reads the link
     * itself: when it reads less media per second than the playback speed uses, over at least
     * [VARIANT_STEP_DOWN_SAMPLE] of media, the stream moves to the highest lower variant that the
     * link carries with [VARIANT_FIT_HEADROOM] to spare, or else to the lowest.
     */
    private fun stepDownWhenLinkTooSlow(session: OpenSession) {
        if (!playRequested || !firstFrameSeen) return
        if (status != PlaybackStatus.Playing && status != PlaybackStatus.Buffering) return
        if (pendingSeek != null || reopenPending) return
        val item = media ?: return
        if (item.demux.variant != null && !variantChosenByPlayer) return
        if (!session.source.seekable) return
        val current = tracks.variants.firstOrNull { it.index == tracks.selectedVariant } ?: return
        val read = session.readRate.mediaPerReadSecond(VARIANT_STEP_DOWN_SAMPLE.inWholeMicroseconds) ?: return
        if (read >= speed) return
        // The bits per second the link delivers, divided by the speed, which plays that much faster.
        val linkBits = read * current.bitrate / speed
        val lower = tracks.variants.filter { it.bitrate < current.bitrate }
        if (lower.isEmpty()) return
        val target = lower.filter { it.bitrate * VARIANT_FIT_HEADROOM <= linkBits }.maxByOrNull { it.bitrate }
            ?: lower.minBy { it.bitrate }
        lowerVariant(
            current,
            target,
            "the link carried about ${linkBits.toLong()} bits per second of playback, too little for ${current.bitrate}",
        )
    }

    /** Moves the stream to [target], a lower variant, and makes the next step up wait. */
    private fun lowerVariant(current: StreamVariant, target: StreamVariant, detail: String) {
        variantChosenByPlayer = true
        // A step up that ended in this step down was too early, so the next one waits twice as long.
        if (lastStepWasUp) stepUpWait = (stepUpWait * 2).coerceAtMost(VARIANT_STEP_UP_WAIT_MAX)
        lastStepWasUp = false
        stepUpNotBeforeNanos = clock.nanos() + stepUpWait.inWholeNanoseconds
        warn(PlaybackWarning.VariantLowered(current.index, target.index, detail))
        // Nobody waits on this reply: the variant change is the player's own.
        pendingVariant = VariantRequest(target.index, CompletableDeferred())
    }

    /**
     * Steps an HLS stream up to the next variant with a higher bitrate when the link carries it with
     * room to spare (#376). The link's rate is what the reader measured, `MediaIo.networkBitsPerSecond`,
     * divided by the playback speed. It must reach [VARIANT_STEP_UP_HEADROOM] times the higher
     * bitrate while every selected queue holds its soft target. [ReadRate] cannot answer this: a
     * reader that fetches ahead serves its reads at once, however slow the link.
     *
     * Only while the player chooses the variant itself, and never past the item's
     * `DemuxPolicy.maxBitrate` or `maxVideoHeight`. Each change opens the stream again, so the
     * picture holds for a moment: after a step down this waits [VARIANT_STEP_UP_WAIT], and twice as
     * long each time a step up was followed by another step down.
     */
    private fun stepUpWhenFast(session: OpenSession) {
        if (!playRequested || status != PlaybackStatus.Playing || !firstFrameSeen) return
        if (pendingSeek != null || reopenPending) return
        val item = media ?: return
        if (item.demux.variant != null && !variantChosenByPlayer) return
        if (!session.source.seekable) return
        if (stepUpNotBeforeNanos != NO_POSITION && clock.nanos() < stepUpNotBeforeNanos) return
        val current = tracks.variants.firstOrNull { it.index == tracks.selectedVariant } ?: return
        val higher = tracks.variants
            .filter { it.bitrate > current.bitrate && withinCaps(it, item.demux) }
            .minByOrNull { it.bitrate } ?: return
        if (!wellBuffered(session)) return
        val linkBits = session.networkIo?.networkBitsPerSecond() ?: return
        if (linkBits / speed < higher.bitrate * VARIANT_STEP_UP_HEADROOM) return
        variantChosenByPlayer = true
        lastStepWasUp = true
        // Nobody waits on this reply either.
        pendingVariant = VariantRequest(higher.index, CompletableDeferred())
    }

    /** True when [variant] stays within the caps the item set on the player's own choice. */
    private fun withinCaps(variant: StreamVariant, demux: DemuxPolicy): Boolean {
        val maxBitrate = demux.maxBitrate
        val maxHeight = demux.maxVideoHeight
        val height = variant.height
        return (maxBitrate == null || variant.bitrate <= maxBitrate) && (maxHeight == null || height == null || height <= maxHeight)
    }

    /**
     * Ends the session when the demux lane has waited `BufferPolicy.stallTimeout` for the source
     * without a packet or a byte, and reports [PlaybackError.SourceStalled].
     *
     * The interrupt comes first, because it is what ends the read; the teardown then finds a lane
     * that can stop. A source that cannot interrupt keeps the old wait, as a container seek on such
     * a source does: a teardown around a read that never returns would hang the actor instead.
     *
     * @return true when the session was ended.
     */
    private suspend fun endStalledSession(session: OpenSession): Boolean {
        val limit = config.buffer.stallTimeout
        if (limit.isInfinite() || session.stallInterruptRefused) return false
        val stalledFor = session.stallWatch.stalledFor() ?: return false
        if (stalledFor < limit) {
            wakeIn(limit - stalledFor)
            return false
        }
        if (!runCatching { session.source.interrupt() }.getOrDefault(false)) {
            session.stallInterruptRefused = true
            return false
        }
        val error = PlaybackError.SourceStalled(media?.uri ?: "", stalledFor)
        teardownSession()
        fail(error)
        resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
        return true
    }

    /**
     * Cue timing. Decode work is actor-confined but explicitly budgeted: a dense ASS stream
     * cannot keep the pass inside this handler while pause, play, seek or a worker failure waits in
     * a mailbox. A hit budget reschedules immediately; a mailbox arrival returns at the next
     * decoder boundary and is drained at the start of the next pass.
     *
     * Subtitles follow the measured rule of publishing on changes, never per frame: cues
     * change about once a second. The raster cost therefore sits on cue edges, and the renderer
     * skips re-uploading an unchanged overlay by contentHash.
     */
    private suspend fun handleSubtitles() {
        val session = this.session ?: return
        val decoder = session.subtitleDecoder
        val queue = session.subtitleQueue

        // The drain half needs a container stream; the timing half below does not: an external
        // cue table times and publishes through the same selector with no decoder at all.
        if (decoder == null || queue == null) {
            if (actorWorkWaiting()) {
                wakeIn(Duration.ZERO)
                return
            }
            if (session.subtitleCues.isEmpty() && session.publishedCueKey.isNullOrEmpty()) return
            timeAndPublishCues(session)
            return
        }

        // A command can arrive after this pass's drainCommands handler returned. Do no subtitle
        // work at all when it is already waiting; the following pass starts with that command.
        if (actorWorkWaiting()) {
            wakeIn(Duration.ZERO)
            return
        }

        var packetAttempts = 0
        var receiveBatches = 0
        var cuesInserted = false
        var interrupted = false

        /** Drains output left by this or the previous pass, within the same explicit budget. */
        suspend fun drainDecoderOutput() {
            while (
                session.subtitleDecoderMayHaveOutput &&
                receiveBatches < SUBTITLE_RECEIVE_BATCHES_PER_PASS
            ) {
                val decoded = decoder.receive()
                if (decoded.isEmpty()) {
                    session.subtitleDecoderMayHaveOutput = false
                    return
                }
                receiveBatches++
                cuesInserted = true
                insertCues(session.subtitleCues, withoutNotes(decoded, session.subtitleStream))
                if (actorWorkWaiting()) {
                    interrupted = true
                    return
                }
            }
        }

        // A receive budget can end a pass after an accepted packet. Its remaining output must be
        // drained before another packet is sent, preserving the decoder's back-pressure contract.
        drainDecoderOutput()

        // Text decode is parsing; it runs inline. The send contract is the decoder SPI's: false
        // means full and the caller must drain before retrying the SAME packet. A packet the
        // decoder temporarily refuses is RETAINED for the next pass, exactly like the audio and
        // video paths retain theirs: closing it on refusal silently dropped the cue.
        while (
            !interrupted &&
            !session.subtitleDecoderMayHaveOutput &&
            packetAttempts < SUBTITLE_PACKETS_PER_PASS
        ) {
            val packet = session.pendingSubtitlePacket ?: queue.poll() ?: break
            session.pendingSubtitlePacket = null
            val accepted = try {
                decoder.send(packet)
            } catch (failure: Throwable) {
                packet.close()
                throw failure
            }
            packetAttempts++
            subtitlePacketAttempts++
            session.subtitleDecoderMayHaveOutput = true
            if (accepted) {
                // The typesetter reads the event's own bytes; the decoder above read them for the
                // cue table and the cue flow. Same packet, two readers, one copy each.
                session.typeset?.let { lane -> stageTypesetEvent(lane, packet) }
                packet.close()
            } else {
                session.pendingSubtitlePacket = packet
            }

            // A command posted from another thread while send() parsed this packet wins the actor
            // before even its output drain. That output remains marked and is the first work of the
            // following subtitle pass.
            if (actorWorkWaiting()) {
                interrupted = true
                break
            }

            drainDecoderOutput()
            if (!accepted) {
                // A full decoder with output is retried immediately after that output drains. One
                // that exposed no output gets the ordinary bounded poll instead of a busy loop.
                wakeIn(if (session.subtitleDecoderMayHaveOutput) Duration.ZERO else WORKER_POLL)
                break
            }
        }

        // The queue has run dry at the end of the stream, so the decoder is told with the null
        // packet the SPI defines: a decoder may hold its last cue until then, and an empty queue
        // cannot say it holds none (#480).
        if (!interrupted && !session.subtitleDrained && !session.subtitleDecoderMayHaveOutput &&
            session.pendingSubtitlePacket == null && queue.ranDry()
        ) {
            val accepted = decoder.send(null)
            session.subtitleDecoderMayHaveOutput = true
            if (accepted || ++session.subtitleDrainRefusals >= SUBTITLE_DRAIN_REFUSALS) session.subtitleDrained = true
            drainDecoderOutput()
            if (!session.subtitleDrained) wakeIn(if (session.subtitleDecoderMayHaveOutput) Duration.ZERO else WORKER_POLL)
        }

        subtitleMaxPacketAttemptsPerPass = maxOf(subtitleMaxPacketAttemptsPerPass, packetAttempts)

        // Cover arrivals between the last decoder boundary and the bookkeeping below.
        if (!interrupted && actorWorkWaiting()) interrupted = true

        // Pruning used to scan the whole cue table once per decoded packet. Once per actor pass,
        // and at most once per second of media progress inside pruneCueHistory, is the same memory
        // policy without making a 48-packet/s subtitle stream perform 48 table scans per second.
        if (cuesInserted && !interrupted) pruneCueHistory(session)

        val backlog = session.subtitleDecoderMayHaveOutput ||
            session.pendingSubtitlePacket != null ||
            queue.count > 0
        if (interrupted || backlog && packetAttempts >= SUBTITLE_PACKETS_PER_PASS ||
            session.subtitleDecoderMayHaveOutput && receiveBatches >= SUBTITLE_RECEIVE_BATCHES_PER_PASS
        ) {
            wakeIn(Duration.ZERO)
        }

        // The secondary lane's decode, the same budgeted shape, after the primary's so the
        // primary always wins a contended pass.
        if (!interrupted) interrupted = driveSecondarySubtitleDecode(session)

        // The command or failure already captured above should not pay a cue-table scan or a raster
        // launch. The next pass handles it first, then comes back here to publish the resulting state.
        if (!interrupted) timeAndPublishCues(session)
    }

    /**
     * The secondary lane's half of [handleSubtitles]: the same budgets, the same retained
     * packet, the same back-pressure contract, against the lane-2 fields. Returns true when a
     * waiting command interrupted the pass.
     */
    private suspend fun driveSecondarySubtitleDecode(session: OpenSession): Boolean {
        val decoder = session.subtitle2Decoder ?: return false
        val queue = session.subtitle2Queue ?: return false
        var packetAttempts = 0
        var receiveBatches = 0
        var cuesInserted = false
        var interrupted = false

        suspend fun drainDecoderOutput() {
            while (session.subtitle2DecoderMayHaveOutput && receiveBatches < SUBTITLE_RECEIVE_BATCHES_PER_PASS) {
                val decoded = decoder.receive()
                if (decoded.isEmpty()) {
                    session.subtitle2DecoderMayHaveOutput = false
                    return
                }
                receiveBatches++
                cuesInserted = true
                insertCues(session.subtitle2Cues, withoutNotes(decoded, session.subtitle2Stream))
                if (actorWorkWaiting()) {
                    interrupted = true
                    return
                }
            }
        }

        drainDecoderOutput()
        while (!interrupted && !session.subtitle2DecoderMayHaveOutput && packetAttempts < SUBTITLE_PACKETS_PER_PASS) {
            val packet = session.pendingSubtitle2Packet ?: queue.poll() ?: break
            session.pendingSubtitle2Packet = null
            val accepted = try {
                decoder.send(packet)
            } catch (failure: Throwable) {
                packet.close()
                throw failure
            }
            packetAttempts++
            session.subtitle2DecoderMayHaveOutput = true
            if (accepted) packet.close() else session.pendingSubtitle2Packet = packet
            if (actorWorkWaiting()) {
                interrupted = true
                break
            }
            drainDecoderOutput()
            if (!accepted) {
                wakeIn(if (session.subtitle2DecoderMayHaveOutput) Duration.ZERO else WORKER_POLL)
                break
            }
        }
        // The secondary lane's end-of-stream drain, as the primary's (#480).
        if (!interrupted && !session.subtitle2Drained && !session.subtitle2DecoderMayHaveOutput &&
            session.pendingSubtitle2Packet == null && queue.ranDry()
        ) {
            val accepted = decoder.send(null)
            session.subtitle2DecoderMayHaveOutput = true
            if (accepted || ++session.subtitle2DrainRefusals >= SUBTITLE_DRAIN_REFUSALS) session.subtitle2Drained = true
            drainDecoderOutput()
            if (!session.subtitle2Drained) wakeIn(if (session.subtitle2DecoderMayHaveOutput) Duration.ZERO else WORKER_POLL)
        }
        if (!interrupted && actorWorkWaiting()) interrupted = true
        if (cuesInserted && !interrupted) pruneSecondaryCueHistory(session)
        val backlog = session.subtitle2DecoderMayHaveOutput ||
            session.pendingSubtitle2Packet != null ||
            queue.count > 0
        if (interrupted || backlog && packetAttempts >= SUBTITLE_PACKETS_PER_PASS ||
            session.subtitle2DecoderMayHaveOutput && receiveBatches >= SUBTITLE_RECEIVE_BATCHES_PER_PASS
        ) {
            wakeIn(Duration.ZERO)
        }
        return interrupted
    }

    /** The lane-2 pruning cursor, the same policy as [pruneCueHistory]. */
    private fun pruneSecondaryCueHistory(session: OpenSession) {
        if (session.subtitle2Decoder == null) return
        val cutoff = currentPosition().micros - subtitleDelay.inWholeMicroseconds - CUE_PRUNE_BEHIND_MICROS
        if (cutoff <= 0) return
        if (
            session.lastSubtitle2PruneCutoffUs != Long.MIN_VALUE &&
            cutoff - session.lastSubtitle2PruneCutoffUs < CUE_PRUNE_STEP_MICROS
        ) return
        session.lastSubtitle2PruneCutoffUs = cutoff
        session.subtitle2Cues.removeAll { it.endMicros < cutoff }
    }

    /**
     * How much longer the last cue still has to run, measured once when every other lane is done.
     *
     * Both lanes, because a second subtitle track can outlast the first. Zero for every file whose
     * subtitles end before its pictures do, which is almost all of them, so this costs one pass
     * over a small list at the very end of a session and nothing at all during playback.
     */
    private fun subtitleTailOverrun(session: OpenSession): Duration {
        val positionUs = currentPosition().micros - subtitleDelay.inWholeMicroseconds
        val primary = session.subtitleCues.maxOfOrNull { it.endMicros }
        val secondary = session.subtitle2Cues.maxOfOrNull { it.endMicros }
        val latestEnd = maxOf(primary ?: Long.MIN_VALUE, secondary ?: Long.MIN_VALUE)
        if (latestEnd == Long.MIN_VALUE) return Duration.ZERO
        val remainingUs = latestEnd - positionUs
        return if (remainingUs <= 0) Duration.ZERO else remainingUs.microseconds
    }

    /** Whether a selected subtitle lane is at the end of its stream with its drain not yet taken or given (#480). */
    private fun subtitleDrainOwed(session: OpenSession): Boolean {
        val primary = session.subtitleDecoder != null && session.subtitleQueue?.ranDry() == true &&
            (!session.subtitleDrained || session.subtitleDecoderMayHaveOutput)
        val secondary = session.subtitle2Decoder != null && session.subtitle2Queue?.ranDry() == true &&
            (!session.subtitle2Drained || session.subtitle2DecoderMayHaveOutput)
        return primary || secondary
    }

    /** Whether this queue has nothing left and never will: its stream ended and it is empty. */
    private fun PacketQueue.ranDry(): Boolean = count == 0 && isEndOfStream

    /** The timing half of handleSubtitles, shared by container and external cue tables. */
    private suspend fun timeAndPublishCues(session: OpenSession) {
        val positionUs = currentPosition().micros - subtitleDelay.inWholeMicroseconds
        // The index is a derived cache over the very same list; it extends on an append and
        // rebuilds after a prune, a merge or a clear. Syncing here rather than at every mutation
        // site keeps the cue table's own code unchanged and costs one size compare per pass.
        session.cueIndex.syncTo(session.subtitleCues)
        val primaryActive = session.cueIndex.activeAt(positionUs)
        // The secondary lane rides the same clock and the same delay, forced to the top of the
        // picture on the way out so the two tracks can never sit on each other.
        session.cue2Index.syncTo(session.subtitle2Cues)
        val secondaryActive = if (session.subtitle2Cues.isEmpty()) {
            emptyList()
        } else {
            session.cue2Index.activeAt(positionUs).map { cue ->
                when (cue) {
                    is io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text -> cue.copy(
                        layout = cue.layout.copy(
                            alignment = io.github.yuroyami.kiteplayer.subtitle.CueAlignment.TopCenter,
                            positionX = null,
                            positionY = null,
                        ),
                    )
                    is io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Bitmap -> cue
                }
            }
        }
        val active = if (secondaryActive.isEmpty()) primaryActive else primaryActive + secondaryActive
        // A typesetter that threw on its lane is abandoned here, on the actor, and the Kotlin tier
        // takes the track over on this very pass.
        var lane = session.typeset
        val laneFailure = lane?.failed?.value
        if (lane != null && laneFailure != null) {
            abandonTypesetting(session, lane, laneFailure)
            lane = null
        }
        // The cues themselves are the identity, not their timestamps: two different texts or
        // styles over the same interval are different overlays, and a (start, end) key republished
        // nothing for them. Structural equality on the data classes is exact.
        // The canvas is now the surface, so a rotation or a resize changes what the text should
        // have been rasterised onto. Cheap to ask, and asked only here, where cue timing already runs.
        val canvasNow = session.renderer.outputSize?.let { it.width to it.height }
        if (active != session.publishedCueKey || canvasNow != session.publishedCanvas) {
            session.publishedCueKey = active.toList()
            session.publishedCanvas = canvasNow
            // Published from the same branch that decides the overlay, so what an application reads
            // and what the renderer draws can never be two different sets.
            cuesState.value = active
            if (lane == null) publishOverlay(session, active)
        }
        lane?.let { live ->
            // The typesetter owns the primary track's text. Bitmap cues and the secondary lane
            // are still the rasterizer's, drawn beside the typeset images by the same job.
            val others = primaryActive.filterIsInstance<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Bitmap>() +
                secondaryActive
            driveTypesetting(session, live, positionUs, others)
        }

        // Sleep exactly to the next cue edge instead of polling for it, whichever lane's comes
        // first.
        listOfNotNull(
            session.cueIndex.nextChangeAfter(positionUs),
            session.cue2Index.nextChangeAfter(positionUs),
        ).minOrNull()?.let { nextUs ->
            val untilNext = (nextUs - positionUs).microseconds
            if (untilNext > Duration.ZERO) wakeIn(minOf(untilNext, WORKER_POLL))
        }
    }

    /** [decoded] without the notes of hearing-impaired subtitles, unless [stream] is ASS, whose text is often signs (#493). */
    private fun withoutNotes(
        decoded: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
        stream: PlayerStreamInfo?,
    ): List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue> {
        val mode = config.subtitles.hearingImpairedNotes
        if (mode == HearingImpairedNotes.Keep || stream?.codec?.lowercase() in ASS_CODECS) return decoded
        return hideHearingImpairedNotes(decoded, mode)
    }

    private fun insertCues(
        cues: MutableList<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
        decoded: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
    ) {
        if (decoded.isEmpty()) return
        var decodedIsSorted = true
        for (index in 1 until decoded.size) {
            if (decoded[index - 1].startMicros > decoded[index].startMicros) {
                decodedIsSorted = false
                break
            }
        }
        val incoming = if (decodedIsSorted) {
            decoded
        } else {
            decoded.sortedBy { it.startMicros }
        }
        if (cues.isEmpty() || cues.last().startMicros <= incoming.first().startMicros) {
            // Only the cues sharing the latest start can still be open, so the closing pass starts
            // at that group rather than at the beginning of a table that can hold thousands.
            val openFrom = io.github.yuroyami.kiteplayer.subtitle.lastStartGroup(cues)
            cues.addAll(incoming)
            io.github.yuroyami.kiteplayer.subtitle.closeOpenCues(cues, openFrom)
            subtitleCueAppendBatches++
            return
        }

        // Decoders normally emit timestamp order, so this is deliberately the cold path. A linear
        // stable merge retains correctness for reordered packets without re-sorting the entire cue
        // history on every ordinary packet.
        val merged = ArrayList<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>(cues.size + incoming.size)
        var existingIndex = 0
        var incomingIndex = 0
        while (existingIndex < cues.size && incomingIndex < incoming.size) {
            if (cues[existingIndex].startMicros <= incoming[incomingIndex].startMicros) {
                merged += cues[existingIndex++]
            } else {
                merged += incoming[incomingIndex++]
            }
        }
        while (existingIndex < cues.size) merged += cues[existingIndex++]
        while (incomingIndex < incoming.size) merged += incoming[incomingIndex++]
        cues.clear()
        cues.addAll(merged)
        // The cold path: a cue merged into the middle may be open, so the pass covers the table.
        io.github.yuroyami.kiteplayer.subtitle.closeOpenCues(cues)
        subtitleCueMergeBatches++
    }

    /**
     * The pruning cursor. Container cues far behind the position are dropped: a backward
     * seek flushes and re-decodes them, so keeping the whole history only grew a list forever.
     * External cue tables (no decoder) are NEVER pruned; nothing re-supplies them.
     */
    private fun pruneCueHistory(session: OpenSession) {
        if (session.subtitleDecoder == null) return
        val cutoff = currentPosition().micros - subtitleDelay.inWholeMicroseconds - CUE_PRUNE_BEHIND_MICROS
        if (cutoff <= 0) return
        if (
            session.lastSubtitlePruneCutoffUs != Long.MIN_VALUE &&
            cutoff - session.lastSubtitlePruneCutoffUs < CUE_PRUNE_STEP_MICROS
        ) return
        session.lastSubtitlePruneCutoffUs = cutoff
        subtitlePruneScans++
        session.subtitleCues.removeAll { it.endMicros < cutoff }
    }

    /**
     * Rasterises [active] for the renderer's output, inside the safe area, and hands the overlay to
     * the renderer. With no platform rasterizer the timing still ran; only the drawing is absent,
     * and the OutputBackend KDoc says exactly that.
     */
    private suspend fun publishOverlay(
        session: OpenSession,
        active: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
    ) {
        val rasterizer = output.subtitleRasterizer ?: return
        // The canvas is the SURFACE, not the video. Text drawn at the video's size and stretched to
        // the surface is resampled before it is ever seen, which on a phone showing an 800p film is
        // 40 pixel glyphs blown up by 1.4: the soft, ragged lettering the owner reported on
        // 2026-08-23. Rasterising at the size the renderer draws at makes that scale exactly 1, and
        // the apparent size does not move, because every size in the rasteriser is a fraction of the
        // canvas height. A renderer that cannot say falls back to the picture, upright.
        val (width, height) = subtitleCanvas(session)
        // Read here, on the actor, because the raster lane below must not read actor state.
        val safeArea = subtitleSafeArea
        val generation = session.overlayGeneration.incrementAndGet()
        if (active.isEmpty()) {
            // A clear costs no rasterisation; publish it inline so text vanishes on time. The
            // generation already moved, so the wait covers only a raster already past its check.
            session.rasterJob?.let { job -> job.cancel(); runCatching { job.join() } }
            session.rasterJob = null
            session.renderer.setOverlay(
                SubtitleOverlay(emptyList(), width, height, contentHash = generation),
            )
            return
        }
        // Rasterisation runs on its own serial lane, never on the actor. Only the
        // NEWEST publication may land: a slow raster of superseded text checks the generation
        // after drawing and drops itself. The job rides a SINGLE slot rather than the session's
        // job list: one Job per cue edge appended for a whole film grew that list
        // by thousands of completed coroutines teardown then had to walk. The superseded raster
        // is cancelled outright, and teardown joins the one live slot.
        val cues = active.toList()
        session.rasterJob?.cancel()
        session.rasterJob = scope.launch(dispatchers.raster) {
            // A rasterizer that failed publishes the empty overlay, so the text it replaced goes.
            val images = rasterizer.rasterizeWithinLimits(
                safeArea, applyOverride(cues, subtitleStyle), width, height, subtitleScale, subtitlePosition,
                ::warnUndrawnSubtitles,
            ).orEmpty()
            if (session.overlayGeneration.value != generation) return@launch
            showOverlayFromRasterLane(
                session,
                SubtitleOverlay(
                    images = images,
                    viewportWidth = width,
                    viewportHeight = height,
                    contentHash = generation,
                ),
            )
        }
    }

    /**
     * Hands [overlay] to the renderer from the raster lane. That lane's jobs have no one to fail
     * to, so a renderer that throws costs the subtitles and a warning, not the player.
     */
    private suspend fun showOverlayFromRasterLane(session: OpenSession, overlay: SubtitleOverlay) {
        try {
            session.renderer.setOverlay(overlay)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            warnUndrawnSubtitles(UndrawnSubtitles.Failed, "the renderer refused the subtitle overlay: $failure")
        }
    }

    /**
     * Warns once per opened item that subtitles went past a limit, and once per player that
     * drawing them failed. Called from the raster lane.
     */
    private fun warnUndrawnSubtitles(cause: UndrawnSubtitles, detail: String) {
        val warned = when (cause) {
            UndrawnSubtitles.Limited -> undrawnSubtitlesLimited
            UndrawnSubtitles.Failed -> undrawnSubtitlesFailed
        }
        if (warned.compareAndSet(expect = false, update = true)) warn(PlaybackWarning.SubtitlesNotDrawn(detail))
    }

    /** Set once [warnUndrawnSubtitles] warned for that cause. Each open clears the limit one. */
    private val undrawnSubtitlesLimited = atomic(false)
    private val undrawnSubtitlesFailed = atomic(false)

    /**
     * Orphans the Kotlin tier's raster job and waits for it, the way the typeset lane is retired, so
     * no raster publication lands after the clear that follows (#223). The generation moves first:
     * a job before its check drops itself, and a job past it finishes publishing before the join
     * returns. Cancelling alone cannot stop that second job, because no renderer's publish suspends
     * where a cancellation could reach it.
     *
     * @return true when a raster job existed, so its text may be on screen.
     */
    private suspend fun retireRasterJob(session: OpenSession): Boolean {
        val job = session.rasterJob ?: return false
        session.overlayGeneration.incrementAndGet()
        job.cancel()
        runCatching { job.join() }
        session.rasterJob = null
        return true
    }

    /**
     * Starts, keeps or stops the typesetting lane to match the selected subtitle track.
     *
     * Idempotent, and called from every path that changes the selection: open, the in-place
     * container switch, an external file coming or going, and a rebuild. The lane is keyed on the
     * track, so a call that changes nothing costs one string compare.
     *
     * Events already decoded before a lane exists are not replayed into it: the raw bytes are gone
     * once the packet is closed. Every caller runs before the first drain of the track it selects,
     * because a switch installs its decoder and the queue's retained history in the same pass.
     */
    private suspend fun refreshTypesetting() {
        val session = session ?: return
        val stream = session.subtitleStream
        val external = selectedExternalSubtitle?.let { id -> externalSubtitleTracks.firstOrNull { it.id == id } }
        val target: Pair<String, TypesetOp>? = when {
            !config.subtitles.typesetting || typesetterRefused -> null
            external != null -> external.script?.let { script ->
                "external:${external.id.value}:${external.revision}" to TypesetOp.Document(script.encodeToByteArray())
            }
            stream != null && isTypesetCodec(stream.codec) ->
                "stream:${stream.index}" to TypesetOp.Header(stream.codecExtradata ?: ByteArray(0))
            else -> null
        }
        if (target == null) {
            stopTypesetting(session)
            return
        }
        val (key, opener) = target
        if (session.typeset?.key == key) return
        stopTypesetting(session)
        val provider = io.github.yuroyami.kiteplayer.spi.SubtitleTypesetters.select() ?: return
        val typesetter = try {
            provider.create()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            refuseTypesetting(provider.id, failure.message ?: failure::class.simpleName ?: "failed to start")
            return
        }
        if (typesetter == null) {
            refuseTypesetting(provider.id, "no engine for this platform")
            return
        }
        val lane = TypesetLane(key, provider.id, typesetter)
        // Fonts first, so the header's styles resolve against them: the application's, then the
        // container's own attachments, then the track.
        config.subtitles.fonts.forEach { font -> lane.stage(TypesetOp.Font(font.name, font.data)) }
        session.source.attachments.filter { it.isFont }.forEach { attachment ->
            lane.stage(TypesetOp.Font(attachment.fileName, attachment.data))
        }
        lane.stage(opener)
        session.typeset = lane
        // Whatever the Kotlin tier had on the glass belongs to the old owner of this track.
        session.publishedCueKey = null
        snapshotDirty = true
    }

    /** Once per player: a provider that cannot start is not asked again, and the viewer is told why. */
    private fun refuseTypesetting(provider: String, detail: String) {
        typesetterRefused = true
        warn(PlaybackWarning.TypesetterUnavailable(provider, detail))
    }

    /** The lane threw on its own thread: fall back to the Kotlin tier for the rest of this player. */
    private suspend fun abandonTypesetting(session: OpenSession, lane: TypesetLane, failure: Throwable) {
        refuseTypesetting(lane.providerId, failure.message ?: failure::class.simpleName ?: "render failed")
        stopTypesetting(session)
    }

    /**
     * Ends the lane: orphans its requests, waits for a render in flight, closes the engine on the
     * lane it always ran on, and clears what it had drawn. The next pass republishes through the
     * Kotlin tier, which is what the cleared published key asks for.
     */
    private suspend fun stopTypesetting(session: OpenSession) {
        val lane = session.typeset ?: return
        session.typeset = null
        lane.epoch.incrementAndGet()
        lane.job?.let { job -> job.cancel(); runCatching { job.join() } }
        withContext(dispatchers.raster) { runCatching { lane.close() } }
        if (lane.published.getAndSet(false)) {
            val frame = lane.lastRequestFrame
            session.renderer.setOverlay(
                SubtitleOverlay(
                    images = emptyList(),
                    viewportWidth = frame?.width ?: DEFAULT_SUBTITLE_CANVAS_WIDTH,
                    viewportHeight = frame?.height ?: DEFAULT_SUBTITLE_CANVAS_HEIGHT,
                    contentHash = session.overlayGeneration.incrementAndGet(),
                ),
            )
        }
        session.publishedCueKey = null
        session.publishedCanvas = null
        snapshotDirty = true
    }

    /** One container event for the lane: its bytes and its timing in the typesetter's milliseconds. */
    private fun stageTypesetEvent(lane: TypesetLane, packet: io.github.yuroyami.kiteplayer.spi.PlayerPacket) {
        val startUs = packet.pts?.micros ?: return
        val durationUs = packet.duration?.micros?.takeIf { it > 0 } ?: TYPESET_DEFAULT_HOLD_MICROS
        lane.stage(TypesetOp.Event(packet.copyBytes(), startUs / 1000, durationUs / 1000))
    }

    /**
     * The size subtitles are laid out for, rule 2 of docs/subtitle-placement.md: the renderer's
     * output when it reports one, else the picture's display size. The picture is turned upright
     * when the stream carries a quarter turn, because the renderer shows it that way.
     */
    private fun subtitleCanvas(session: OpenSession): Pair<Int, Int> {
        session.renderer.outputSize?.let { output ->
            if (output.width > 0 && output.height > 0) return output.width to output.height
        }
        val size = session.videoStream?.visibleVideoSize
        val width = size?.displayWidth?.takeIf { it > 0 }
        val height = size?.height?.takeIf { it > 0 }
        if (width == null || height == null) return DEFAULT_SUBTITLE_CANVAS_WIDTH to DEFAULT_SUBTITLE_CANVAS_HEIGHT
        return if (isQuarterTurn(session.videoStream?.rotationDegrees)) height to width else width to height
    }

    /** The geometry the typesetter draws into, from the same canvas rule [publishOverlay] uses. */
    private fun typesetFrame(session: OpenSession): io.github.yuroyami.kiteplayer.spi.TypesetFrame {
        val (width, height) = subtitleCanvas(session)
        val size = session.videoStream?.visibleVideoSize
        var videoWidth = size?.displayWidth?.takeIf { it > 0 } ?: width
        var videoHeight = size?.height?.takeIf { it > 0 } ?: height
        // The renderer turns a sideways recording upright, so the fitted picture is the turned one.
        if (size != null && isQuarterTurn(session.videoStream?.rotationDegrees)) {
            val turned = videoWidth
            videoWidth = videoHeight
            videoHeight = turned
        }
        val margins = fittedMargins(width, height, videoWidth, videoHeight, videoScale)
        return io.github.yuroyami.kiteplayer.spi.TypesetFrame(
            width = width,
            height = height,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            marginTop = margins[0],
            marginBottom = margins[1],
            marginLeft = margins[2],
            marginRight = margins[3],
            fontScale = subtitleScale,
            linePosition = subtitlePosition,
        )
    }

    /** One video frame of media time, bounded so a broken frame rate cannot spin or stall the lane. */
    private fun typesetFrameIntervalUs(session: OpenSession): Long {
        val rate = session.videoStream?.frameRate?.takeIf { it.isFinite() && it >= 1.0 } ?: return 40_000L
        return (1_000_000.0 / rate).toLong().coerceIn(TYPESET_MIN_INTERVAL_MICROS, WORKER_POLL.inWholeMicroseconds)
    }

    /**
     * The per-pass half of typesetting: decides whether this pass asks for a render, and keeps the
     * actor waking at frame cadence while the picture moves.
     *
     * Renders are asked for when the engine has staged input, when the geometry or the other cues
     * changed, and otherwise once per video frame while playing. Paused, a position change alone
     * (a seek) asks once. The typesetter answers "unchanged" for a static line, so the cadence
     * costs one cheap call per frame and publishes nothing.
     */
    private fun driveTypesetting(
        session: OpenSession,
        lane: TypesetLane,
        positionUs: Long,
        others: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
    ) {
        val frame = typesetFrame(session)
        val timeMillis = positionUs.coerceAtLeast(0L) / 1000
        val playing = status == PlaybackStatus.Playing
        val intervalUs = typesetFrameIntervalUs(session)
        val first = lane.lastRequestMillis == Long.MIN_VALUE
        val moved = timeMillis != lane.lastRequestMillis
        val frameElapsed = first || timeMillis < lane.lastRequestMillis ||
            (timeMillis - lane.lastRequestMillis) * 1000 >= intervalUs
        val wanted = lane.hasStaged() || frame != lane.lastRequestFrame || others != lane.lastRequestOthers ||
            subtitleSafeArea != lane.lastRequestSafeArea || (moved && (frameElapsed || !playing))
        if (wanted) {
            lane.lastRequestMillis = timeMillis
            lane.lastRequestFrame = frame
            lane.lastRequestOthers = others
            lane.lastRequestSafeArea = subtitleSafeArea
            requestTypesetRender(
                session,
                lane,
                TypesetRequest(timeMillis, frame, others, subtitleStyle, subtitleSafeArea, lane.epoch.value),
            )
        }
        if (playing) wakeIn(intervalUs.microseconds)
    }

    /** Posts the newest request and makes sure exactly one render job owns the lane. */
    private fun requestTypesetRender(session: OpenSession, lane: TypesetLane, request: TypesetRequest) {
        if (lane.failed.value != null) return
        lane.requested.value = request
        if (lane.running.compareAndSet(expect = false, update = true)) {
            lane.job = scope.launch(dispatchers.raster) { typesetLoop(session, lane) }
        }
    }

    /**
     * The render job. It drains coalesced requests until none is left, then hands the lane back.
     *
     * The handover is the delicate part: a request posted after this job saw an empty slot but
     * before it released the lane would otherwise wait for a render nobody schedules. Releasing
     * first and then re-checking, with the same CAS the actor uses to launch, closes that window
     * from both sides: either this job reclaims the lane and renders, or the actor's CAS won and a
     * new job does.
     */
    private suspend fun typesetLoop(session: OpenSession, lane: TypesetLane) {
        while (true) {
            val request = lane.requested.getAndSet(null)
            if (request == null) {
                lane.running.value = false
                if (lane.requested.value == null || !lane.running.compareAndSet(expect = false, update = true)) return
                continue
            }
            if (request.epoch != lane.epoch.value) continue
            val images = try {
                lane.renderNow(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                lane.failed.value = failure
                lane.running.value = false
                return
            }
            if (
                images == null && request.otherCues == lane.publishedOthers &&
                request.otherSafeArea == lane.publishedSafeArea
            ) continue
            if (images != null) lane.lastImages = images
            lane.publishedOthers = request.otherCues
            lane.publishedSafeArea = request.otherSafeArea
            val frame = request.frame
            val rasterized = if (request.otherCues.isEmpty()) {
                emptyList()
            } else {
                // The typeset images keep the author's placement; the cues beside them keep to the
                // safe area, rule 3 of docs/subtitle-placement.md.
                output.subtitleRasterizer?.rasterizeWithinLimits(
                    request.otherSafeArea,
                    applyOverride(request.otherCues, request.otherStyle),
                    frame.width,
                    frame.height,
                    frame.fontScale,
                    frame.linePosition,
                    ::warnUndrawnSubtitles,
                ).orEmpty()
            }
            // Re-checked after the render, because a withdrawal may have happened during it.
            if (request.epoch != lane.epoch.value) continue
            lane.published.value = true
            showOverlayFromRasterLane(
                session,
                SubtitleOverlay(
                    images = lane.lastImages + rasterized,
                    viewportWidth = frame.width,
                    viewportHeight = frame.height,
                    contentHash = session.overlayGeneration.incrementAndGet(),
                ),
            )
        }
    }

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

        // A preload owns this item's end: the next item's sound follows its last sample, and a
        // fallback hands the end back to the lines below. See handleQueueHandoff.
        if (pendingNext?.coldOnly == false) {
            wakeIn(WORKER_POLL)
            return
        }
        // A queued seek moves the position before this pass ends. Declaring the end first let the
        // queue advance and drop the seek it had been asked for.
        if (pendingSeek != null) return

        // Told as soon as the audio side is finished, which is what the sink's own contract asks for and
        // is earlier than the moment every condition below is met: the video frame queue holds frames
        // ahead of the screen and empties last, and the ring runs dry while it drains. The silence in
        // between is the end of the media, not a failure to keep up, and counting it as underruns makes
        // the counter useless for spotting the real thing. Measured on a ten second clip: two underruns
        // reported without this line, none with it. Idempotent, and undone by a flush.
        val audioQueue = session.audioQueue
        if (endOfStream.audioDecoderDrained && audioQueue != null &&
            audioQueue.isEndOfStream && audioQueue.count == 0 &&
            // AND nothing still between the decoder and the device. Without this the ring was told
            // the stream had ended while up to five decoded buffers were still on their way into
            // it, so the marker meant "demux finished", not "no more audio".
            session.audioInFlight.value == 0
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
        if ((session.video?.queuedFrames ?: 0) > 0 && !stillImageFinished) return

        // The audio lane's own end, which the four conditions above cannot see. A drained decoder
        // and an empty packet queue say demuxing and decoding finished; the decoded samples then
        // travel through a handoff channel, a conversion and the DSP stages before any device hears
        // them. Ending here used to cut all of that off, which is silent media loss and is worst
        // exactly where it is most audible: short clips, and any non-1x speed.
        if (session.audioLane != null && !endOfStream.tailAbandoned) {
            // Bounded like the sink drain below it and for the same reason: a feeder that
            // cannot place the tail must not park the player one poll short of Ended for ever. The
            // deadline starts at the first of these two waits, so a stalled handoff and a stalled
            // flush share one budget rather than each getting a fresh one.
            if (endOfStream.tailRequestedNanos == 0L) endOfStream.tailRequestedNanos = clock.nanos()
            val tailPending = session.audioInFlight.value > 0 || !session.audioTailFlushed.value
            val tailTimedOut =
                clock.nanos() - endOfStream.tailRequestedNanos >= DRAIN_DEADLINE.inWholeNanoseconds
            if (tailPending && tailTimedOut) {
                endOfStream.tailAbandoned = true
                warn(
                    PlaybackWarning.AudioDrainIncomplete(
                        "the decoded audio still in flight did not reach the device within " +
                            "$DRAIN_DEADLINE, so the end of the media was declared without it",
                    ),
                )
            } else if (session.audioInFlight.value > 0) {
                wakeIn(WORKER_POLL)
                return
            } else if (!session.audioTailFlushed.value) {
                // Everything decoded has been handed over. What is left is what the tempo stage is
                // holding, and only the feeder may push that out, so ask and wait for its answer.
                session.audioEosRequested.value = true
                wakeIn(WORKER_POLL)
                return
            }
        }

        if (!endOfStream.draining) {
            endOfStream.draining = true
            endOfStream.drainStartedNanos = clock.nanos()
            // Said as soon as the decoder is done, not when the ring empties: the silence between those
            // two moments is the end of the media and must not be counted as a failure to keep up.
            session.audio?.endOfStream()
        }

        if (!endOfStream.sinkDrained) {
            val audio = session.audio.takeIf { session.audioLane != null }
            // Bounded: a device that stopped pulling freezes the ring's fill, and
            // an unconditional wait here parked the player one poll before Ended for ever. The
            // grace is the buffered tail itself plus the same deadline the drain call gets; past
            // it, the drain below runs and completes as failed rather than being polled again.
            val drainGraceNanos = audio?.buffered?.inWholeNanoseconds?.plus(DRAIN_DEADLINE.inWholeNanoseconds)
            if (audio != null && audio.buffered > Duration.ZERO && !endOfStream.drainFailed &&
                clock.nanos() - endOfStream.drainStartedNanos < (drainGraceNanos ?: 0L)
            ) {
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

        // The subtitle lane's own end. Every other lane has said "no more data"; this one finishes
        // when its last cue stops being shown, which is later than the last frame whenever a
        // closing line outlives the picture it belongs to. Ending here is how the last line of
        // dialogue in a film used to vanish a moment early.
        if (!endOfStream.subtitleTailDone) {
            // A lane whose decoder is still owed its drain, or still has output to give, may hold
            // the last cue, which the tail below has to count (#480).
            if (endOfStream.subtitleTailUntilNanos == 0L && subtitleDrainOwed(session)) {
                wakeIn(Duration.ZERO)
                return
            }
            if (endOfStream.subtitleTailUntilNanos == 0L) {
                val overrun = subtitleTailOverrun(session)
                endOfStream.subtitleTailUntilNanos = if (overrun <= Duration.ZERO) {
                    -1L
                } else {
                    clock.nanos() + minOf(overrun, SUBTITLE_TAIL_MAX).inWholeNanoseconds
                }
            }
            if (endOfStream.subtitleTailUntilNanos > 0 && clock.nanos() < endOfStream.subtitleTailUntilNanos) {
                wakeIn(WORKER_POLL)
                return
            }
            endOfStream.subtitleTailDone = true
        }

        session.schedulerMode.value = SCHEDULER_IDLE
        // The timeline stops where the media does. A clock left running reads on from wall time, so a
        // player sitting on its last frame would report a position further past the duration the longer
        // it was left there. The final device anchor is consumed first, exactly as a pause does it, so a
        // late callback cannot re-anchor a clock that is already frozen.
        session.audio?.anchorClock()
        session.audio?.pause()
        session.furthestPositionUs = maxOf(session.furthestPositionUs, currentPosition().micros)
        emitEvent(PlayerEvent.Ended)
        setStatus(PlaybackStatus.Ended)
    }

    private fun handleLoop() {
        if (status != PlaybackStatus.Ended) return
        // "Finish this one and stop" outranks every repeat. handleQueueAdvance, later in the same
        // pass, sees Ended, disarms the timer and withdraws play, and a stopped end is not repeated
        // on the passes after it (#216).
        if (sleepTimer == SleepTimer.EndOfItem || !playRequested) return
        // The armed A-B loop owns the end of the media: with no B, or a B past the end,
        // the wrap point IS the end, and the jump back to A restarts playback like a repeat,
        // regardless of LoopMode. An A at or past the duration would land straight back on the
        // end and restart every pass for ever, so such an A is treated as unarmed rather than
        // spun on; an unseekable source cannot make the jump at all.
        val loopA = abLoopA
        // The item just ended, so how far it played is its real length, whatever it declared.
        val durationUs = session?.let { endedLengthUs(it) }
        if (loopA != null && session?.source?.seekable == true &&
            durationUs != null && loopA.inWholeMicroseconds < durationUs
        ) {
            restartFrom(Pts(loopA.inWholeMicroseconds))
            return
        }
        // One media item repeating is a seek to zero and nothing else. LoopMode.All with a queue
        // of one or none means the same thing: the whole queue IS the current item. This is the
        // old path: a repeat normally never ends the item, because its next pass was preloaded
        // and follows it in the ring (#467).
        val repeatsCurrent = loop == LoopMode.One || (loop == LoopMode.All && queueItems.size <= 1)
        if (!repeatsCurrent) return
        // The same guard the A-B branch above has: the repeat is a precise seek,
        // and this was the one seek path that never asked. Seeking an unseekable source killed
        // the session with an Internal error; staying Ended with a typed warning is the truth.
        if (session?.source?.seekable != true) {
            if (!loopRefusalWarned) {
                loopRefusalWarned = true
                warn(
                    PlaybackWarning.CommandRefused(
                        "setLoop",
                        "the repeat seeks back to the start, and this source is not seekable",
                    ),
                )
            }
            return
        }
        restartFrom(Pts.Zero)
    }

    /** The Ended-to-Buffering turnover both loop kinds share: reset EOF, keep intent, seek to [target]. */
    private fun restartFrom(target: Pts) {
        endOfStream.reset()
        stillImageFinished = false
        stillImageShownSinceNanos = 0
        playRequested = true
        setStatus(PlaybackStatus.Buffering)
        pendingSeek = SeekRequest(SeekTarget.Absolute(target), SeekMode.Precise)
    }

    /**
     * The queue's own advance. At Ended with a queue behind it, the next item opens and
     * playback continues; LoopMode.All wraps past the last item. Runs after handleLoop, which
     * owns the repeat-current cases, and the Ended-to-Opening transition makes re-entry
     * impossible: by the time this pass ends the status has left Ended.
     */
    /**
     * Follows the external clock, one question at a time (#91). See [io.github.yuroyami.kiteplayer.ExternalClock]
     * for what each answer does. Only while playing, with no seek in hand: a pause, a seek or a
     * reopen starts the following again from a trim of 1.
     */
    private fun handleExternalClock() {
        val external = externalClock
        val active = session
        if (active == null || status != PlaybackStatus.Playing || pendingSeek != null) {
            if (externalTrim != 1.0) applyExternalTrim(1.0)
            externalAskedAtNanos = NO_POSITION
            return
        }
        val now = clock.nanos()
        if (externalSeekInFlight) {
            // The first pass that plays again after the seek: what the seek cost, which the next
            // seek adds to its target so that it lands where the clock will be.
            externalSeekInFlight = false
            externalSeekLatencyNanos = (now - externalSeekAtNanos).coerceIn(0L, EXTERNAL_SEEK_LATENCY_CAP_NANOS)
        }
        if (external == null) {
            if (config.syncMode == SyncMode.ExternalMaster) {
                noteExternalSilence(now, "no external clock is set, although the sync mode is ExternalMaster")
            }
            return
        }
        val askedAt = externalAskedAtNanos
        if (askedAt != NO_POSITION && now - askedAt < EXTERNAL_ASK_INTERVAL_NANOS) {
            wakeIn((EXTERNAL_ASK_INTERVAL_NANOS - (now - askedAt)).nanoseconds)
            return
        }
        externalAskedAtNanos = now
        wakeIn(EXTERNAL_ASK_INTERVAL_NANOS.nanoseconds)
        val answer = try {
            external.positionAt(now)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            null
        }
        val answerUs = answer?.inWholeMicroseconds
        // Only an answer that moved since the last question is followed. A clock that stopped
        // would otherwise pull playback back with a seek before anything could tell it stopped.
        val moved = answerUs != null && externalLastAnswerUs != NO_POSITION &&
            abs(answerUs - externalLastAnswerUs) > EXTERNAL_MOVED_US
        externalLastAnswerUs = answerUs ?: NO_POSITION
        if (answerUs == null || !moved) {
            // No answer to follow, so the player runs on its own audio clock. A stopped clock is
            // never read as a pause: play and pause belong to the caller's commands.
            if (externalTrim != 1.0) applyExternalTrim(1.0)
            noteExternalSilence(
                now,
                if (answerUs == null) "the external clock gave no answer" else "the external clock stopped moving",
            )
            return
        }
        externalSilentSinceNanos = NO_POSITION
        externalSilentWarned = false

        val errorUs = answerUs - currentPosition().micros
        val maxTrim = config.externalClock.maxTrim
        when {
            abs(errorUs) >= EXTERNAL_SEEK_US && active.source.seekable && active.source.seekCeiling?.let { answerUs <= it.micros } != false -> {
                val askedRecently = externalSeekAtNanos != NO_POSITION && now - externalSeekAtNanos < EXTERNAL_SEEK_COOLDOWN_NANOS
                if (askedRecently) {
                    // A second jump this soon is likely the last seek's own delay: lean on the trim.
                    applyExternalTrim(1.0 + sign(errorUs.toDouble()) * maxTrim)
                } else {
                    externalSeekAtNanos = now
                    externalSeekInFlight = true
                    applyExternalTrim(1.0)
                    val targetUs = (answerUs + externalSeekLatencyNanos / 1_000L).coerceAtLeast(0L)
                    queueSeek(SeekRequest(SeekTarget.Absolute(Pts(targetUs)), SeekMode.Precise), null)
                }
            }
            abs(errorUs) < EXTERNAL_DEADBAND_US -> applyExternalTrim(1.0)
            else -> {
                // Close the difference over about a second, within the cap, in steps of 0.05
                // percent so the tempo stage is not retuned on every question.
                val wanted = (errorUs / 1_000_000.0).coerceIn(-maxTrim, maxTrim)
                applyExternalTrim(1.0 + round(wanted / EXTERNAL_TRIM_STEP) * EXTERNAL_TRIM_STEP)
            }
        }
    }

    /** Warns once that nothing is followed, after the silence has lasted [EXTERNAL_SILENT_NANOS]. */
    private fun noteExternalSilence(now: Long, detail: String) {
        if (externalSilentSinceNanos == NO_POSITION) externalSilentSinceNanos = now
        if (!externalSilentWarned && now - externalSilentSinceNanos >= EXTERNAL_SILENT_NANOS) {
            externalSilentWarned = true
            warn(PlaybackWarning.ExternalClockSilent(detail))
        }
    }

    /** Sets the trim and hands the new effective speed to both pipelines. */
    private fun applyExternalTrim(trim: Double) {
        if (trim == externalTrim) return
        externalTrim = trim
        session?.audio?.speed = effectiveSpeed
        session?.video?.speed = effectiveSpeed
    }

    /**
     * Holds the delay behind a sender that pushes in real time (#395). What has arrived and not been
     * heard is that delay, less the network's own transit. A stall that the network later makes good
     * grows it by as long as the player waited, because the sender went on sending and its media
     * arrives late but whole. While the delay is more than [LIVE_CATCH_UP_ABOVE_US] over the buffer
     * policy's ready duration, the player plays [LIVE_CATCH_UP_SPEED] times faster through the tempo
     * stage, which keeps the pitch, until it is back within [LIVE_CATCH_UP_UNTIL_US] of it. A
     * second of excess clears in ten seconds, and the ready duration that every resume waits for is
     * the delay the player keeps.
     *
     * Only while playing at the caller's speed of 1, with no seek in hand and no external clock,
     * which owns the speed when there is one, and only while speed keeps the pitch: played as on a
     * turntable, catching up would raise every voice by a tenth. A pause, a stall or a new item
     * starts from a trim of 1. A delay below zero, or above twice the read-ahead budget, is a jump in the timestamps and not
     * a delay, so it is not followed.
     */
    private fun handleLiveDelay() {
        val active = session
        if (active == null || !active.source.realTime || status != PlaybackStatus.Playing || pendingSeek != null ||
            externalClock != null || speed != 1.0 || !preservePitch
        ) {
            if (liveTrim != 1.0) applyLiveTrim(1.0)
            liveCheckedAtNanos = NO_POSITION
            return
        }
        val now = clock.nanos()
        val checkedAt = liveCheckedAtNanos
        if (checkedAt != NO_POSITION && now - checkedAt < LIVE_DELAY_CHECK_NANOS) {
            wakeIn((LIVE_DELAY_CHECK_NANOS - (now - checkedAt)).nanoseconds)
            return
        }
        liveCheckedAtNanos = now
        wakeIn(LIVE_DELAY_CHECK_NANOS.nanoseconds)
        val newestUs = active.readRate.newestUs() ?: return
        val delayUs = newestUs - currentPosition().micros
        val readyUs = config.buffer.readyDuration.inWholeMicroseconds
        when {
            delayUs < 0 || delayUs > 2 * config.buffer.totalDuration.inWholeMicroseconds -> applyLiveTrim(1.0)
            delayUs > readyUs + LIVE_CATCH_UP_ABOVE_US -> applyLiveTrim(LIVE_CATCH_UP_SPEED)
            delayUs <= readyUs + LIVE_CATCH_UP_UNTIL_US -> applyLiveTrim(1.0)
        }
    }

    /** Sets the live trim and hands the new effective speed to both pipelines. */
    private fun applyLiveTrim(trim: Double) {
        if (trim == liveTrim) return
        liveTrim = trim
        session?.audio?.speed = effectiveSpeed
        session?.video?.speed = effectiveSpeed
    }

    /** A new clock, or none: the following starts from the beginning. */
    private fun resetExternalFollowing() {
        applyExternalTrim(1.0)
        externalAskedAtNanos = NO_POSITION
        externalLastAnswerUs = NO_POSITION
        externalSilentSinceNanos = NO_POSITION
        externalSilentWarned = false
        externalSeekAtNanos = NO_POSITION
        externalSeekInFlight = false
    }

    /**
     * The sleep timer, one pass at a time.
     *
     * Runs only while playback is advancing, so a paused player does not sleep through its own
     * timer and a timer set during a pause waits for play. The fade is computed from how much of
     * it is left rather than stepped, so a pass the actor was late for cannot leave the level
     * stranded, and a fade longer than the time remaining simply starts already part way down.
     */
    private suspend fun handleSleepTimer() {
        val timer = sleepTimer ?: return
        val audio = session?.audio
        if (!status.isActive) {
            sleepCountedAtNanos = NO_POSITION
            return
        }
        if (timer is SleepTimer.After) {
            val now = clock.nanos()
            if (sleepCountedAtNanos != NO_POSITION) sleepRemainingNanos -= now - sleepCountedAtNanos
            sleepCountedAtNanos = now
        }

        val remaining: Duration = when (timer) {
            is SleepTimer.After -> sleepRemainingNanos.nanoseconds
            is SleepTimer.At -> timer.position - currentPosition().asDuration
            // Handled by the end-of-stream path, which knows when an item is genuinely over.
            SleepTimer.EndOfItem -> return
        }

        if (remaining <= Duration.ZERO) {
            fireSleepTimer()
            return
        }
        if (sleepFade > Duration.ZERO && remaining < sleepFade) {
            val level = (remaining / sleepFade).toFloat().coerceIn(0f, 1f)
            audio?.setFadeLevel(level)
        }
    }

    /** Pauses, disarms, and gives the level back so the next play is not silent. */
    private suspend fun fireSleepTimer() {
        sleepTimer = null
        playRequested = false
        applyPause()
        // The level goes back BEFORE anything else can play: a timer that left the fade in place
        // would make the next play silent, which is the bug every hand-written sleep timer has.
        session?.audio?.setFadeLevel(1f)
        snapshotDirty = true
    }

    private suspend fun handleQueueAdvance() {
        if (status != PlaybackStatus.Ended) return
        // An end-of-item timer stops here rather than letting the queue move on, which is what
        // "finish this one and stop" means.
        if (sleepTimer == SleepTimer.EndOfItem) {
            sleepTimer = null
            playRequested = false
            session?.audio?.setFadeLevel(1f)
            snapshotDirty = true
            return
        }
        if (loop == LoopMode.One) return
        if (queueItems.size <= 1) return
        val next = neighbourInOrder(1) ?: return
        val from = queueIndex
        queueIndex = next
        queueMoved(from)
        openCarriesPlay = true
        try {
            openQueueItem(CompletableDeferred(), step = 1)
        } finally {
            openCarriesPlay = false
        }
        // An open ends paused by contract; a queue that was playing keeps playing through it.
        playRequested = true
    }

    // ---------------------------------------------------------------------------------------------
    // The gapless queue: preload, handoff and swap. See docs/gapless-queue.md.
    // ---------------------------------------------------------------------------------------------

    /**
     * The next queue item, open in the background so that its sound follows the last sample of
     * the current item on the same device. Actor only.
     */
    private var pendingNext: PendingNext? = null

    /** The current session and next position whose handoff fell back, so the pair is not tried again. */
    private var gaplessRefused: Pair<Long, Int>? = null

    /** Cancelled preload builds that may still be releasing what they opened. Close waits for them. */
    private val retiringBuilds = mutableListOf<Job>()

    private class PendingNext(
        val index: Int,
        val item: MediaItem,
        /** The token of the session this item follows. */
        val follows: Long,
        val build: PendingBuild,
        val job: Deferred<Result<PreparedNext>>,
        /**
         * True when this is the next pass of the current item, under a repeat, rather than the next
         * queue item (#467). [index] is then the current item's own queue position, -1 outside queue
         * playback.
         */
        val repeat: Boolean = false,
        /** Where the pass starts: A for an A-B loop, else zero. */
        val startUs: Long = 0L,
        /**
         * Where the current pass stops for this one, and where this one stops in its turn: B for
         * an A-B loop whose B is inside the item, or null when the pass follows the item's end.
         */
        val wrapUs: Long? = null,
    ) {
        /** Set once the build has finished; the item's workers then run. */
        var prepared: PreparedNext? = null

        /** True once the item's feeder writes into the current item's ring. */
        var handedOff: Boolean = false

        /**
         * True when this item cannot take the current item's ring, so it follows the old way: the
         * current item ends and stops its device, and this item opens from the preload with a
         * device of its own (#306).
         */
        var coldOnly: Boolean = false
    }

    private class PreparedNext(
        val session: OpenSession,
        val externals: List<ExternalSubtitleTrack>,
    )

    /**
     * Where one turn of an A-B loop stops (#467). The actor gives it at the start of the turn. The
     * feeder stops at it only while nothing it wrote in this epoch reaches past [us], so the ring
     * never holds a sample from past B ahead of the next pass: an end given too late is passed by,
     * and the B crossing goes back by the seek. Stopped at, the feeder writes up to [us] exactly,
     * keeps the rest of that buffer, and holds it, unwritten, until the next pass takes the ring or
     * the end is lifted, when it carries on from [us] with nothing lost. A new end is a new object,
     * so the feeder tells a lifted end from one given again for the same B.
     */
    private class PassEnd(val us: Long) {
        /** True once every sample before [us] is written and the feeder holds the rest. */
        val reached = atomic(false)
    }

    /**
     * Selects the external file a prepared [item] opens with, as an open does: one flagged to show
     * at once, or else one the build preferred over the container's own (#514). The subtitle is the
     * player's own choice unless a file asked to be shown (#506).
     */
    private suspend fun applyPreparedSubtitle(item: MediaItem, prepared: PreparedNext) {
        val immediate = prepared.externals
            .firstOrNull { track -> item.externalSubtitles.getOrNull(-track.id.value - 1)?.selectImmediately == true }
            ?.id
        (immediate ?: prepared.session.preferredExternalSubtitle)?.let { applyExternalSubtitle(it) }
        subtitleChosenByPlayer = immediate == null
    }

    /**
     * What a background build for the preload does differently from an open: it writes no player
     * state, holds its warnings and events for the swap, opens no audio device, and never takes a
     * renderer's own video decoder. See [buildSession].
     */
    private inner class PendingBuild(val token: Long) {
        val warnings = HeldWarnings()
        val report: (PlaybackWarning) -> Unit = warnings::deliver

        /** Why each decoder candidate refused, for this build's deselection warnings. */
        val failures = mutableListOf<String>()

        /** Events an open would emit, emitted at the swap instead. */
        val events = mutableListOf<PlayerEvent>()
        var tracks: Tracks = Tracks.Empty
    }

    /** Warnings of a preloaded item, held until it becomes the current one. Safe from any thread. */
    private inner class HeldWarnings {
        /** Null once released: later warnings then pass straight through. */
        private val held = atomic<List<PlaybackWarning>?>(emptyList())
        private val discarded = atomic(false)

        fun deliver(warning: PlaybackWarning) {
            while (true) {
                if (discarded.value) return
                val current = held.value
                if (current == null) {
                    warn(warning)
                    return
                }
                if (held.compareAndSet(current, current + warning)) return
            }
        }

        /** Delivers what is held, and everything after it directly. */
        fun release() {
            held.getAndSet(null)?.forEach(::warn)
        }

        /** The item was dropped, so nothing it says reaches the caller. */
        fun discard() {
            discarded.value = true
        }
    }

    /**
     * The gapless handoff, one pass at a time. It starts the preload when the current item nears
     * its end, starts the preloaded item's workers when its build finishes, gives it the ring when
     * the current item's last sample is written, and swaps the items when the device plays the
     * next item's first sample.
     */
    private suspend fun handleQueueHandoff() {
        session?.let { resumeAfterTakeBack(it) }
        val next = pendingNext
        if (next != null) session?.let { stepHandoff(it, next) }
        // Straight after the step, so that a pass it dropped lets the sound held for it go on at
        // once, and the item a swap made current gets its own turn and preload (#467).
        val active = session ?: return
        if (pendingNext != null) return
        settleTurn(active)
        maybeStartPreload(active)
    }

    /** One step of the handoff to [next]: adopt it, give it the ring, or swap the items. */
    private suspend fun stepHandoff(active: OpenSession, next: PendingNext) {
        // Defensive: every path that replaces the session drops the preload first.
        if (next.follows != active.token) {
            dropPending(null)
            return
        }
        val prepared = next.prepared ?: adoptPreload(next, active) ?: return
        // The old path owns the end of the current item; handleQueueAdvance opens this one.
        if (next.coldOnly) return
        if (active.audioLane == null) {
            stepSilentHandoff(active, next, prepared)
            return
        }
        if (!next.handedOff) {
            if (!currentAudioFinished(active)) {
                // With every packet decoded, the last sample is at most a ring depth and a few
                // buffers away, and the next item's feeder must start well before the ring runs dry.
                val allDecoded = active.audioQueue?.let { it.isEndOfStream && it.count == 0 } == true
                wakeIn(if (allDecoded) HANDOFF_POLL else WORKER_POLL)
                return
            }
            if (!pendingPrimed(prepared.session)) {
                // Waited for only while the ring still covers the wait.
                if (ringRunsDry(active)) {
                    dropPending("the next item was not ready when the current one ran out of sound")
                } else {
                    wakeIn(HANDOFF_POLL)
                }
                return
            }
            if (!handOffRing(active, next, prepared)) return
        }
        if (active.audio?.joinCrossed == true) {
            if (next.repeat) swapToRepeat(next, prepared) else swapToNext(next, prepared)
        } else {
            wakeIn(HANDOFF_POLL)
        }
    }

    /**
     * The handoff between two silent items (#524). There is no ring to give, so the picture times
     * the join: once the current item has shown its last picture, or its last before B, the next
     * one becomes current, and its first picture takes the slot after that last one, one frame
     * period on, as the pictures of one item follow each other. Only while playing, because a
     * paused schedule has no slot that ends.
     */
    private suspend fun stepSilentHandoff(active: OpenSession, next: PendingNext, prepared: PreparedNext) {
        if (active.videoParked.value) {
            dropPending("the current item has no selected audio track, and its picture was turned off")
            return
        }
        val runsOutAt = pictureRunsOutAt(active)
        if (runsOutAt == null) {
            val near = active.passEnd.value?.reached?.value == true || active.videoQueue?.isEndOfStream == true
            wakeIn(if (near) HANDOFF_POLL else WORKER_POLL)
            return
        }
        if (status != PlaybackStatus.Playing) return
        if (!pendingPrimed(prepared.session)) {
            if (clock.nanos() >= runsOutAt) {
                dropPending("the next item was not ready when the current one ran out of pictures")
            } else {
                wakeIn(HANDOFF_POLL)
            }
            return
        }
        // Before the swap starts its schedule, which reads it on its first step.
        prepared.session.video?.startAt(runsOutAt)
        if (next.repeat) swapToRepeat(next, prepared) else swapToNext(next, prepared)
    }

    /**
     * When the slot of a silent item's last picture ends, once every picture of its pass is shown,
     * or null while one is still to come (#524). The last is the last before B for a pass that stops
     * there, which the video lane marks by holding the first picture at B, or the last of the item.
     * The slot counts only when the scheduler published it for the picture on screen, so a slot of
     * the picture before is never read as the last one's.
     */
    private fun pictureRunsOutAt(active: OpenSession): Long? {
        val video = active.video ?: return null
        if (video.queuedFrames > 0) return null
        val atB = active.passEnd.value?.reached?.value == true
        val queue = active.videoQueue ?: return null
        val atEnd = queue.isEndOfStream && queue.count == 0 && (active.videoDecoder?.isDrained ?: true) &&
            active.videoInFlight.value == 0
        if (!atB && !atEnd) return null
        val slot = active.shownSlot.value ?: return null
        if (slot.ptsUs != video.shownPts()?.micros) return null
        return slot.untilNanos
    }

    /**
     * Seeks to where the sound was once the ring was taken back from an A-B loop's next pass
     * (#467), which cleared the sound before B along with it. A seek that the drop made way for
     * owns the position already.
     */
    private fun resumeAfterTakeBack(active: OpenSession) {
        val resumeUs = active.resumeAfterTakeBackUs
        if (resumeUs == NO_POSITION) return
        active.resumeAfterTakeBackUs = NO_POSITION
        if (pendingSeek != null || seekPhase.isRunning) return
        queueSeek(SeekRequest(SeekTarget.Absolute(Pts(resumeUs)), SeekMode.Precise), null)
    }

    /**
     * The B that a turn of the armed A-B loop stops at for its next pass to follow (#467), or null
     * when no pass can follow it: no B inside the item, or no pass the preload may open for it.
     */
    private fun loopEndFor(active: OpenSession): Long? =
        loopWrapUs(active)?.takeIf { passMayFollow(active, queueIndex) }

    /**
     * The B of the armed A-B loop when it is inside [active], where the loop's next pass follows
     * the turn that plays, or null when it follows the end of the item or no loop is armed.
     */
    private fun loopWrapUs(active: OpenSession): Long? {
        if (abLoopA == null) return null
        val bUs = abLoopB?.inWholeMicroseconds ?: return null
        val durationUs = active.source.duration?.micros ?: return null
        return bUs.takeIf { it < durationUs }
    }

    /**
     * Whether the preload may open what follows [active], the queue item at [index] or the item's
     * own next pass, as far as nothing but a setting or a fallback changes while the item plays.
     * The turns of an A-B loop ask it too, so that none waits at B for a pass that never opens.
     */
    private fun passMayFollow(active: OpenSession, index: Int): Boolean {
        val policy = config.queue
        if (!policy.gapless || policy.preloadNext <= Duration.ZERO) return false
        if (sleepTimer == SleepTimer.EndOfItem) return false
        if (gaplessRefused == (active.token to index)) return false
        if (active.source.duration == null) return false
        return active.source.seekable && !active.isStillImage
    }

    /**
     * Starts a turn of the A-B loop from [fromUs] (#467). Its sound stops at B for the next pass,
     * unless the turn starts too near B for a pass to open in time: less than [MIN_PASS_LEAD]
     * before it, or less than the whole section when that is shorter. Such a turn plays on past B
     * to the seek back to A, as every turn did before passes, and the turns after it start at A.
     * Given before the feeder writes a sample of the turn, so a section shorter than the ring
     * stops at B too.
     */
    private fun startTurn(active: OpenSession, fromUs: Long) {
        val bUs = loopEndFor(active)
        active.turnDecided = true
        active.turnEndUs = bUs
        val aUs = abLoopA?.inWholeMicroseconds ?: 0L
        val late = bUs != null && bUs - fromUs < minOf(MIN_PASS_LEAD.inWholeMicroseconds, bUs - aUs)
        active.passEnd.value = if (bUs != null && !late) PassEnd(bUs) else null
    }

    /**
     * Starts the turn again from where the sound is when what follows B changed: the loop armed,
     * moved or cleared, or no pass possible any more, which lifts the end, so the feeder goes on
     * past B with the sound it held. Not while a seek is due: the seek starts a turn of its own,
     * and until it lands nothing past B may be written or shown.
     */
    private fun settleTurn(active: OpenSession) {
        if (pendingSeek != null || seekPhase.isRunning) return
        if (active.turnDecided && loopEndFor(active) == active.turnEndUs) return
        startTurn(active, publishedPositionMicros.value)
    }

    /**
     * Starts the preload once the current item is within `QueueConfig.preloadNext` of its end: of
     * the next queue item, or under a repeat of the current item's next pass (#467). An armed A-B
     * loop owns the end as [handleLoop] has it: its next pass starts at A, and follows B when B is
     * inside the item, or else the item's end.
     */
    private fun maybeStartPreload(active: OpenSession) {
        if (status != PlaybackStatus.Playing && status != PlaybackStatus.Paused) return
        if (pendingSeek != null || seekPhase.isRunning || pendingVideoRecovery != null) return
        if (pendingSelections.isNotEmpty()) return
        val loopA = abLoopA?.inWholeMicroseconds
        // The cases handleLoop repeats: the whole queue is the current item under LoopMode.All.
        val repeat = loopA != null || loop == LoopMode.One || (loop == LoopMode.All && queueItems.size <= 1)
        if (!repeat && queueItems.size <= 1) return
        val index = if (repeat) queueIndex else neighbourInOrder(1) ?: return
        if (!passMayFollow(active, index)) return
        val durationUs = active.source.duration?.micros ?: return
        val wrapUs = loopWrapUs(active)
        val leadUs = config.queue.preloadNext.inWholeMicroseconds
        if (wrapUs != null) {
            if (!passBeforeBDue(active, wrapUs, leadUs)) return
        } else if (!passAtEndDue(active, durationUs, leadUs)) {
            return
        }
        // An A at or past the end would start the pass on the end itself, which handleLoop treats
        // as an A that is not armed.
        if (wrapUs == null && loopA != null && loopA >= durationUs) return
        val item = if (repeat) media ?: return else queueItems[index]
        val refusal = when {
            // A silent item's join is timed by its picture (#524), so it needs one.
            active.audioLane == null && (active.video == null || active.videoParked.value) ->
                "the current item has no selected audio track and no picture"
            active.audioLane != null && active.audio == null -> "the current item has no audio device open"
            // A repeat starts again from zero, as the seek it replaces does.
            !repeat && (item.startPosition ?: Duration.ZERO) > Duration.ZERO -> "the next item has a start position"
            else -> null
        }
        if (refusal != null) {
            gaplessRefused = active.token to index
            warn(PlaybackWarning.GaplessFallback(index, refusal))
            return
        }
        val build = PendingBuild(nextSessionToken++)
        if (repeat) {
            val startUs = loopA ?: 0L
            val job = startRepeatBuild(active, item, build, startUs, wrapUs)
            pendingNext = PendingNext(index, item, active.token, build, job, repeat = true, startUs = startUs, wrapUs = wrapUs)
            wakeIn(WORKER_POLL)
            return
        }
        // On the session lane, so it reads the player where the actor does. It writes nothing of
        // the player's: see PendingBuild.
        val job = scope.async(dispatchers.session) {
            try {
                val externals = parseExternalSubtitles(item, build.report)
                val immediate = externals.any { track ->
                    item.externalSubtitles.getOrNull(-track.id.value - 1)?.selectImmediately == true
                }
                val built = buildSession(
                    item = item,
                    videoChoice = StreamChoice.Auto,
                    audioChoice = StreamChoice.Auto,
                    subtitleChoice = if (immediate) StreamChoice.None else StreamChoice.Auto,
                    videoSelection = VideoDecoderSelection.Configured,
                    pending = build,
                    externalSubtitles = externals.map { it.info },
                )
                Result.success(PreparedNext(built, externals))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }
        pendingNext = PendingNext(index, item, active.token, build, job)
        wakeIn(WORKER_POLL)
    }

    /**
     * Whether the next pass of an A-B loop whose B is inside the item should start opening now
     * (#467): once the turn that plays has an end for it to follow, which [startTurn] gave it,
     * and is within the lead of B. A turn with no end opens none, because the seek back to A that
     * ends it drops every pass.
     */
    private fun passBeforeBDue(active: OpenSession, wrapUs: Long, leadUs: Long): Boolean {
        if (active.passEnd.value == null) return false
        val leftUs = wrapUs - publishedPositionMicros.value
        if (leftUs > leadUs) {
            // Woken when the lead begins rather than a whole pass later. Media distance over rate
            // is wall distance.
            if (status == PlaybackStatus.Playing) wakeIn(((leftUs - leadUs) / wakeRate()).toLong().microseconds)
            return false
        }
        return true
    }

    /** Whether the next item, or the next pass, should start opening before the current item's end. */
    private fun passAtEndDue(active: OpenSession, durationUs: Long, leadUs: Long): Boolean {
        // Too late: the current item's sound is all in the ring, or its last picture is on
        // screen, and the old path is closer.
        if (currentAudioFinished(active)) return false
        if (active.audioLane == null && pictureRunsOutAt(active) != null) return false
        val leftUs = if (active.source.durationIsEstimate) {
            // An estimated length cannot time the lead, so the demuxer reaching the end of the input
            // does: what is left is then the queues and the ring, a few seconds at most (#422).
            if (!demuxReachedEnd(active)) {
                if (status == PlaybackStatus.Playing) wakeIn(WORKER_POLL)
                return false
            }
            0L
        } else {
            durationUs - publishedPositionMicros.value
        }
        if (leftUs > leadUs) {
            // Woken when the lead begins rather than a whole pass later. Media distance over rate
            // is wall distance.
            if (status == PlaybackStatus.Playing) wakeIn(((leftUs - leadUs) / wakeRate()).toLong().microseconds)
            return false
        }
        return true
    }

    /**
     * The background build of the current item's next pass (#467). It opens the streams that play
     * now, with the decoder selection the item has come to, and reads no external subtitle file:
     * the player keeps the ones it read, and the swap puts them back on the new pass. A pass of an
     * A-B loop starts at A as a precise seek lands there, from the keyframe before it with the
     * lanes dropping what comes before A, and stops at [wrapUs] in its turn.
     */
    private fun startRepeatBuild(
        active: OpenSession,
        item: MediaItem,
        build: PendingBuild,
        startUs: Long,
        wrapUs: Long?,
    ): Deferred<Result<PreparedNext>> {
        val videoChoice = active.videoStream?.let { StreamChoice.At(it.index) } ?: StreamChoice.None
        val audioChoice = active.audioStream?.let { StreamChoice.At(it.index) } ?: StreamChoice.None
        // An external file timing the cues means no container stream, as in a rebuild.
        val subtitleChoice = active.subtitleStream
            ?.takeIf { selectedExternalSubtitle == null }
            ?.let { StreamChoice.At(it.index) }
            ?: StreamChoice.None
        val videoSelection = if (forceBackendSoftwareForMedia) {
            VideoDecoderSelection.BackendSoftwareOnly
        } else {
            VideoDecoderSelection.Configured
        }
        return scope.async(dispatchers.session) {
            try {
                val built = buildSession(
                    item = item,
                    videoChoice = videoChoice,
                    audioChoice = audioChoice,
                    subtitleChoice = subtitleChoice,
                    videoSelection = videoSelection,
                    pending = build,
                    startUs = startUs,
                )
                // Left in place for the whole pass, where it drops nothing: a video decoder made at
                // the swap still has the frames between the keyframe and A to throw away.
                if (startUs > 0L) built.discardBeforeUs.value = startUs
                // The pass's own turn, given before its feeder starts, so a section shorter than the
                // ring stops at B too. It starts at A, so it is never too near B.
                wrapUs?.let {
                    built.turnDecided = true
                    built.turnEndUs = it
                    built.passEnd.value = PassEnd(it)
                }
                Result.success(PreparedNext(built, emptyList()))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }
    }

    /**
     * Takes the finished build, answers what only the opened item can answer, aligns the item to
     * the epoch the player is at, and starts its demux and decode workers. Null while the build
     * still runs, and when the preload was dropped.
     */
    private suspend fun adoptPreload(next: PendingNext, active: OpenSession): PreparedNext? {
        if (!next.job.isCompleted) {
            if (active.audioLane == null) {
                val runsOutAt = pictureRunsOutAt(active)
                if (runsOutAt != null && status == PlaybackStatus.Playing && clock.nanos() >= runsOutAt) {
                    dropPending("the next item was still opening when the current one ran out of pictures")
                } else {
                    wakeIn(WORKER_POLL)
                }
            } else if (currentAudioFinished(active) && ringRunsDry(active)) {
                dropPending("the next item was still opening when the current one ran out of sound")
            } else {
                wakeIn(WORKER_POLL)
            }
            return null
        }
        val prepared = next.job.await().getOrElse { failure ->
            dropPending("the next item did not open${causeDetail(failure)}")
            return null
        }
        // Set first, so that every drop from here on releases the opened item.
        next.prepared = prepared
        val incoming = prepared.session
        handoffRefusal(active, incoming)?.let { refusal ->
            // A repeat's old path seeks back rather than opening anything, so the pass goes.
            if (next.repeat) {
                dropPending(refusal)
                return null
            }
            // The item is fine and only the ring does not fit it, so the old path plays it from
            // here rather than opening it again (#306).
            gaplessRefused = active.token to next.index
            warn(PlaybackWarning.GaplessFallback(next.index, refusal))
            next.coldOnly = true
        }
        // Aligned to the epoch the player is at, as a rebuild is: fresh decoders stamp the first
        // epoch, and the workers would drop everything they produced.
        incoming.videoParked.value = !videoEnabled
        try {
            flushDecoders(incoming, requestedEpoch)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            dropPending("the next item's decoders did not start${causeDetail(failure)}")
            return null
        }
        incoming.video?.flush(requestedEpoch)
        incoming.subtitleDecoder?.flush(requestedEpoch)
        startPendingWorkers(incoming)
        snapshotDirty = true
        return prepared
    }

    /** Why the preloaded item cannot take the current item's ring, or null when it can. */
    private fun handoffRefusal(active: OpenSession, incoming: OpenSession): String? {
        // Two silent items join by their pictures, and one silent item cannot join one with sound,
        // whose ring has nothing before it to follow or nothing after it to time the swap (#524).
        if (active.audioLane == null) {
            return if (incoming.audioLane == null) null else "the current item has no selected audio track"
        }
        val format = incoming.audioDecoder?.outputFormat ?: return "the next item has no selected audio track"
        val device = active.deviceRequest ?: return "the current item has no audio device open"
        if (format.sampleRate != device.sampleRate || format.channels != device.channels) {
            return "the next item has ${format.sampleRate} Hz and ${format.channels} channels, and the " +
                "device was opened for ${device.sampleRate} Hz and ${device.channels} channels"
        }
        return null
    }

    /**
     * The demux and decode workers of a preloaded item, which fill its queues. Its feeder starts at
     * the handoff and its video schedule at the swap.
     */
    private fun startPendingWorkers(incoming: OpenSession) {
        val epoch = requestedEpoch
        incoming.preloading.value = true
        if (incoming.videoQueue != null && incoming.videoDecoder != null && incoming.video != null) {
            incoming.videoDecodeWorker = Worker(VIDEO_DECODE_WORKER)
        }
        if (incoming.audioQueues.isNotEmpty()) incoming.audioDecodeWorker = Worker(AUDIO_DECODE_WORKER)
        incoming.demuxWorker = Worker(DEMUX_WORKER)
        incoming.workers.forEach { it.release(epoch) }
        incoming.demuxWorker?.let { worker ->
            incoming.jobs += launchWorker(incoming, worker, dispatchers.demux) { runDemux(incoming, worker) }
        }
        incoming.videoDecodeWorker?.let { worker ->
            incoming.jobs += launchWorker(incoming, worker, dispatchers.videoDecode) { runVideoDecode(incoming, worker) }
        }
        incoming.audioDecodeWorker?.let { worker ->
            incoming.jobs += launchWorker(incoming, worker, dispatchers.audioDecode) { runAudioDecode(incoming, worker) }
        }
    }

    /** True when the preloaded item can start at once: its queues are ready and decoded sound waits. */
    private fun pendingPrimed(incoming: OpenSession): Boolean {
        val readyUs = config.buffer.readyDuration.inWholeMicroseconds
        if (!incoming.selectedQueues().all { it.isReady(readyUs, config.buffer.readyPackets) }) return false
        val video = incoming.video
        val videoReady = video == null || video.queuedFrames > 0 ||
            incoming.videoQueue?.isEndOfStream == true || incoming.videoParked.value ||
            incoming.videoDecoderDeferred
        val audioReady = incoming.audioLane == null || incoming.audioInFlight.value > 0 ||
            incoming.audioQueue?.isEndOfStream == true
        return videoReady && audioReady
    }

    /** True once every decoded sample of the current item is in the ring. */
    /**
     * Moves [active]'s furthest position on, and republishes the snapshot when its length is an
     * estimate that playback has now passed by a second or more, so the seek bar and the lock screen
     * follow what really plays without a new snapshot every tick (#422).
     */
    private fun noteFurthestPosition(active: OpenSession) {
        if (seekPhase.isRunning || pendingSeek != null) return
        val here = currentPosition().micros
        if (here <= active.furthestPositionUs) return
        active.furthestPositionUs = here
        if (!active.source.durationIsEstimate) return
        val shownUs = snapshotState.value.duration?.inWholeMicroseconds ?: return
        if (here - shownUs >= DURATION_FOLLOW_STEP_US) publishSnapshot()
    }

    /** True once the demuxer has read to the end of the input for every selected stream it feeds. */
    private fun demuxReachedEnd(active: OpenSession): Boolean {
        val queues = listOfNotNull(active.audioQueue, active.videoQueue)
        return queues.isNotEmpty() && queues.all { it.isEndOfStream }
    }

    /**
     * The length a snapshot reports for [active] (#422). A stated length as it is. An estimate until
     * playback passes it, then the furthest position played, and once the item ends, the position it
     * ended at, which is the real length however wrong the estimate was.
     */
    private fun publishedDuration(active: OpenSession): Duration? {
        val stated = active.source.duration ?: return null
        if (!active.source.durationIsEstimate) return stated.asDuration
        if (status == PlaybackStatus.Ended) return active.furthestPositionUs.coerceAtLeast(0L).microseconds
        return maxOf(stated.micros, active.furthestPositionUs).microseconds
    }

    /** Whether [publishedDuration] is still FFmpeg's guess rather than the length really played. */
    private fun durationStillEstimated(active: OpenSession): Boolean {
        if (!active.source.durationIsEstimate || active.source.duration == null) return false
        if (status == PlaybackStatus.Ended) return false
        return active.furthestPositionUs <= (active.source.duration?.micros ?: 0L)
    }

    /** How long [active] really is once it has ended: its stated length, or how far it played (#422). */
    private fun endedLengthUs(active: OpenSession): Long? =
        if (active.source.durationIsEstimate) active.furthestPositionUs.takeIf { it > 0L } else active.source.duration?.micros

    private fun currentAudioFinished(active: OpenSession): Boolean {
        // An A-B loop's pass is finished at B, with the sound after it held back (#467).
        if (active.passEnd.value?.reached?.value == true) return true
        val queue = active.audioQueue ?: return false
        return (active.audioDecoder?.isDrained ?: true) && queue.isEndOfStream && queue.count == 0 &&
            active.audioInFlight.value == 0
    }

    /** True when the ring holds too little of the current item to wait for the next one any longer. */
    private fun ringRunsDry(active: OpenSession): Boolean =
        (active.audio?.buffered ?: Duration.ZERO) <= HANDOFF_MARGIN

    /**
     * Gives the ring to the preloaded item: the current item's feeder parks, and the next item's
     * feeder starts right after the last sample in the ring. The device keeps running.
     *
     * @return false when the current feeder did not park; the preload was dropped then.
     */
    private suspend fun handOffRing(active: OpenSession, next: PendingNext, prepared: PreparedNext): Boolean {
        val audio = active.audio ?: run {
            dropPending("the current item has no audio device open")
            return false
        }
        val incoming = prepared.session
        // The ring has one producer, so the current feeder stops before the next one starts.
        val feeder = active.audioFeedWorker
        if (feeder != null && !feeder.quiesce(QUIESCE_DEADLINE)) {
            feeder.release(requestedEpoch)
            dropPending("the current item's audio feeder did not stop within $QUIESCE_DEADLINE")
            return false
        }
        // ReplayGain is applied on the way in, so the next item's own value holds from its first sample.
        audio.replayGain = replayGainFor(incoming.audioStream, incoming.source.metadata)
        audio.beginJoin()
        active.ownsAudio = false
        incoming.audio = audio
        incoming.sink = active.sink
        incoming.negotiatedFormat = active.negotiatedFormat
        incoming.deviceRequest = active.deviceRequest
        // The taps have every block of the current item, since blocks reach them as they are
        // written. What follows comes from somewhere else, so they hear that first, and the next
        // item's blocks arrive under a generation of their own.
        tapsDiscontinuous(active = incoming)
        launchFeeder(incoming)
        next.handedOff = true
        return true
    }

    /** Starts a preloaded item's audio feeder, which the preload left for the handoff or the open. */
    private fun launchFeeder(incoming: OpenSession) {
        val worker = Worker(AUDIO_FEED_WORKER)
        incoming.audioFeedWorker = worker
        worker.release(requestedEpoch)
        incoming.jobs += launchWorker(incoming, worker, dispatchers.audioFeed) { runAudioFeed(incoming, worker) }
    }

    /**
     * The preload of queue position [index] when its workers run and no handoff has started, or
     * null. A repeat's next pass is never one, because it carries none of the item's external
     * subtitle files, so `next` opens the item afresh.
     */
    private fun primedFor(index: Int?): PendingNext? =
        pendingNext?.takeIf { index != null && it.index == index && it.prepared != null && !it.handedOff && !it.repeat }

    /**
     * Opens a primed preload as the current item without opening it again (#306). The item before
     * it closes as an open closes it, and the preload gets an audio device of its own, opened for
     * its format. Like [runOpen], it ends paused on the item's first frame.
     */
    private suspend fun runOpenPrepared(
        next: PendingNext,
        reply: CompletableDeferred<Unit>,
        absorb: ((PlaybackError) -> Boolean)? = null,
    ) {
        traceUntilReplied(reply, "session", "open") { mapOf("uri" to redactUri(next.item.uri)) }
        sessionOwner = reply
        val prepared = next.prepared ?: error("runOpenPrepared needs a primed preload")
        val incoming = prepared.session
        // Adopted, not dropped: the teardown below must leave it alone.
        pendingNext = null
        if (session != null) teardownSession()
        // The preload's queues and decoders carry the epoch the player is at, so the open keeps it.
        resetForOpen(next.item, requestedEpoch)
        try {
            session = incoming
            incoming.preloading.value = false
            incoming.videoParked.value = !videoEnabled
            tracks = next.build.tracks
            openStage = OpenStage.Output
            openAudioPathFor(incoming)
            openStage = OpenStage.Assembly
            tapsDiscontinuous(active = incoming)
            startAudioEventCollector(incoming)
            if (incoming.audioQueues.isNotEmpty()) launchFeeder(incoming)
            if (incoming.videoDecoderDeferred) startDeferredVideo(incoming)
            startVideoSchedule(incoming, SCHEDULER_IDLE)
            next.build.warnings.release()
            next.build.events.forEach(::emitEvent)
            reportFirstFrame(incoming, "open")
            if (preempted()) {
                teardownSession()
                reply.completeExceptionally(preemptedByTeardown("open"))
                return
            }
            adoptExternalSubtitles(next.item, prepared.externals)
            applyPreparedSubtitle(next.item, prepared)
            refreshTypesetting()
            setStatus(PlaybackStatus.Paused)
            emitEvent(PlayerEvent.Opened(next.item, tracks))
            reply.complete(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val error = classify(failure, next.item)
            teardownSession()
            if (absorb?.invoke(error) == true) return
            fail(error)
            reply.completeExceptionally(PlaybackException(error))
        }
    }

    /** Opens an audio device for the selected audio lane of [target], as an open's output stage does. */
    private suspend fun openAudioPathFor(target: OpenSession) {
        val lane = target.audioLane ?: return
        val createdSink = output.audioSink.create()
        createdSink.setContent(target.audioContent)
        val playback = newAudioPlayback(createdSink)
        try {
            // Before open, which captures the wanted rate as the fresh path's epoch.
            playback.speed = effectiveSpeed
            playback.preservePitch = preservePitch
            val negotiated = playback.open(lane.decoder.outputFormat)
            playback.volume = volume
            playback.setDuckLevel(duckLevel)
            playback.muted = muted
            playback.replayGain = replayGainFor(lane.stream, target.source.metadata)
            playback.balance = balance
            playback.equalizer = equalizer
            target.audio = playback
            target.sink = createdSink
            target.negotiatedFormat = negotiated
            target.deviceRequest = lane.decoder.outputFormat
            target.ownsAudio = true
            emitEvent(PlayerEvent.AudioFormatChanged(negotiated.sampleRate, negotiated.channels))
        } catch (failure: Throwable) {
            // The playback owns the sink it was given, so its close covers both.
            playback.close()
            throw failure
        }
    }

    /**
     * Makes the preloaded item the current one, once the device has played its first sample. The
     * old item's lanes, decoders and source close, and the device and the ring carry on. The
     * status and the play intent do not change.
     */
    private suspend fun swapToNext(next: PendingNext, prepared: PreparedNext) {
        val incoming = prepared.session
        pendingNext = null
        emitEvent(PlayerEvent.Ended)
        detachSession(forHandoff = true)?.let { releaseSession(it) }
        // What an open resets for a new item, except the play intent, the status and the epoch,
        // which carry on with the device.
        media = next.item
        val from = queueIndex
        queueIndex = next.index
        queueMoved(from)
        lastChapterIndex = Int.MIN_VALUE
        markerCursorUs = NO_POSITION
        markerCursorEpoch = null
        externalSubtitleTracks = emptyList()
        selectedExternalSubtitle = null
        selectedExternalSubtitle2 = null
        pendingExternalSubtitle = null
        loopRefusalWarned = false
        toneMapWarned = false
        videoDynamicRange = io.github.yuroyami.kiteplayer.VideoDynamicRange.Standard
        colorLimitsWarned.clear()
        videoRecoveryAttempted = false
        forceBackendSoftwareForMedia = false
        firstFrameSeen = false
        divergencesReported = false
        undrawnSubtitlesLimited.value = false
        endOfStream.reset()
        demuxUnderrunSeen = false
        stillImageFinished = false
        stillImageShownSinceNanos = 0
        openedAtNanos = clock.nanos()
        tracks = next.build.tracks
        session = incoming
        incoming.preloading.value = false
        incoming.videoParked.value = !videoEnabled
        incoming.audio?.commitJoin()
        // A silent item reads no position until its first picture is shown, and starts where its
        // pass starts (#524).
        publishedPositionMicros.value = if (incoming.audioLane == null) next.startUs else currentPosition().micros
        progressState.value = Progress(position = publishedPositionMicros.value.microseconds, bufferedAhead = Duration.ZERO)
        next.build.warnings.release()
        next.build.events.forEach(::emitEvent)
        adoptExternalSubtitles(next.item, prepared.externals)
        applyPreparedSubtitle(next.item, prepared)
        refreshTypesetting()
        startAudioEventCollector(incoming)
        if (incoming.videoDecoderDeferred) startDeferredVideo(incoming)
        // The preload built its schedule at the speed of that moment; the speed may have changed
        // since, and the schedule has not started, so the current one applies at once.
        incoming.video?.speed = effectiveSpeed
        startVideoSchedule(incoming)
        reportContainerDivergences(incoming)
        emitEvent(PlayerEvent.Opened(next.item, tracks))
        snapshotDirty = true
    }

    /**
     * Makes the next pass of the current item the current session, once the device has played its
     * first sample (#467). As [swapToNext], except that the item stays: its queue position, its
     * external subtitle files, both subtitle selections and everything reported once per item carry
     * on, and only [PlayerEvent.Ended] fires, as at each turn of a repeat that seeks back. A turn of
     * an A-B loop at a B inside the item fires nothing, as the seek back to A fired nothing.
     */
    private suspend fun swapToRepeat(next: PendingNext, prepared: PreparedNext) {
        val incoming = prepared.session
        pendingNext = null
        if (next.wrapUs == null) emitEvent(PlayerEvent.Ended)
        // Read before the new pass's table replaces the one they live in.
        val secondaryBefore = tracks.selectedSecondarySubtitle
        // The real length of an item whose length was a guess is how far it played, which a new
        // pass would otherwise forget until it got there again (#422).
        session?.let { ending ->
            incoming.furthestPositionUs = maxOf(incoming.furthestPositionUs, ending.furthestPositionUs)
            // A recording ends where the media jumps back, as it did at the seek a repeat used to be.
            if (ending.recordingEnd != null) {
                ending.recordingEnd = if (next.wrapUs != null || next.startUs > 0L) {
                    "the A-B loop went back to A"
                } else {
                    "the item repeated from its start"
                }
            }
        }
        detachSession(forHandoff = true)?.let { releaseSession(it) }
        // The markers behind the start are armed again, as the seek back to it armed them.
        markerCursorUs = NO_POSITION
        markerCursorEpoch = null
        endOfStream.reset()
        demuxUnderrunSeen = false
        tracks = next.build.tracks
        session = incoming
        incoming.preloading.value = false
        incoming.videoParked.value = !videoEnabled
        incoming.audio?.commitJoin()
        // A silent item reads no position until its first picture is shown, and starts where its
        // pass starts (#524).
        publishedPositionMicros.value = if (incoming.audioLane == null) next.startUs else currentPosition().micros
        progressState.value = Progress(position = publishedPositionMicros.value.microseconds, bufferedAhead = Duration.ZERO)
        // The pass's own warnings are news; its events, the picture size, are what the item said.
        next.build.warnings.release()
        restoreSubtitleState(secondaryBefore)
        refreshTypesetting()
        startAudioEventCollector(incoming)
        if (incoming.videoDecoderDeferred) startDeferredVideo(incoming)
        incoming.video?.speed = effectiveSpeed
        startVideoSchedule(incoming)
        snapshotDirty = true
    }

    /**
     * Creates the video decoder that a preload left for the swap, now that the item before it has
     * closed its own and freed the renderer's surface, and starts the video decode lane. A stream
     * that no decoder accepts is dropped through the ordinary track change, as an open drops it.
     */
    private suspend fun startDeferredVideo(incoming: OpenSession) {
        incoming.videoDecoderDeferred = false
        val stream = incoming.videoStream ?: return
        val selected = createVideoDecoder(
            session = incoming.backendSession,
            stream = stream,
            sourceSeekable = incoming.source.seekable,
            selection = VideoDecoderSelection.Configured,
        )
        val decoder = selected?.decoder
        val aligned = decoder != null && try {
            withContext(dispatchers.videoDecode) { decoder.flush(requestedEpoch) }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            decoderCandidateFailures = listOf("${stream.codec}: the flush failed${causeDetail(failure)}")
            withContext(NonCancellable + dispatchers.videoDecode) { runCatching { decoder.close() } }
            false
        }
        if (selected == null || !aligned) {
            warn(
                PlaybackWarning.TrackDeselected(
                    TrackId(stream.index),
                    deselectionDetail("no decoder accepted this video stream"),
                ),
            )
            queueSelection(TrackKind.Video, null, CompletableDeferred())
            return
        }
        incoming.videoDecoder = selected.decoder
        incoming.videoDecoderOrigin = selected.origin
        incoming.coupledRenderer = if (selected.origin == VideoDecoderOrigin.Renderer) pendingRenderer else null
        incoming.rendererDecodersAsked = selected.rendererAsked
        val worker = Worker(VIDEO_DECODE_WORKER)
        incoming.videoDecodeWorker = worker
        worker.release(requestedEpoch)
        incoming.jobs += launchWorker(incoming, worker, dispatchers.videoDecode) { runVideoDecode(incoming, worker) }
    }

    /**
     * The preloaded item's video schedule. At the swap a playing player runs it and a paused one
     * gets one frame; an open passes [mode] idle and presents its first frame itself.
     */
    private fun startVideoSchedule(
        incoming: OpenSession,
        mode: Int = if (status == PlaybackStatus.Playing) SCHEDULER_RUNNING else SCHEDULER_ONE_FRAME,
    ) {
        if (incoming.videoQueue == null || incoming.videoDecoder == null || incoming.video == null) return
        val worker = Worker(VIDEO_SCHEDULE_WORKER)
        incoming.videoScheduler = worker
        incoming.schedulerMode.value = mode
        worker.release(requestedEpoch)
        incoming.jobs += launchWorker(incoming, worker, dispatchers.videoSchedule) { runVideoSchedule(incoming, worker) }
    }

    /**
     * Drops the preload and releases everything it opened. [reason] is a fallback reason, which is
     * warned, or null when the caller's own action dropped it. A handoff under way is taken back
     * first. After a fallback the same pair of items is not tried again.
     */
    private suspend fun dropPending(reason: String?) {
        val next = pendingNext ?: return
        pendingNext = null
        snapshotDirty = true
        next.build.warnings.discard()
        val active = session
        if (reason != null) {
            if (active != null) gaplessRefused = active.token to next.index
            warn(PlaybackWarning.GaplessFallback(next.index, reason))
        }
        val prepared = next.prepared
        when {
            // A build the player's own cancellation stopped released what it opened, and awaiting
            // it would throw into the teardown that is running now.
            prepared == null && next.job.isCompleted -> if (!next.job.isCancelled) {
                next.job.await().getOrNull()?.session?.let { releaseSession(it) }
            }
            prepared == null -> {
                // Still opening: its own rollback releases what it opened.
                next.job.cancel()
                retiringBuilds.removeAll { it.isCompleted }
                retiringBuilds += next.job
            }
            next.handedOff && active != null -> takeRingBack(active, prepared.session, midItem = next.wrapUs != null)
            else -> releaseSession(prepared.session)
        }
    }

    /**
     * Takes the ring back from a next item whose feeder already writes into it. That feeder parks,
     * the device stops and the ring is cleared, which loses at most one ring depth of the current
     * item's end, and the next item is released. The current item then sits at its end with
     * nothing left to drain, unless the next pass was an A-B loop's and the current one stopped at
     * a B [midItem] the item: the pass then carries on from where the sound was heard, by a seek
     * that brings back what the clearing lost, and its end stands until that seek lands, so
     * nothing from past B is heard or shown before it (#467).
     */
    private suspend fun takeRingBack(active: OpenSession, incoming: OpenSession, midItem: Boolean = false) {
        val audio = active.audio
        val parked = incoming.audioFeedWorker?.quiesce(QUIESCE_DEADLINE) ?: true
        // Both producers are parked, so the flush may clear the ring.
        if (parked) audio?.flush(requestedEpoch)
        incoming.audio = null
        incoming.sink = null
        releaseSession(incoming)
        // Its feeder is joined by now, whatever the park answered.
        if (!parked) audio?.flush(requestedEpoch)
        active.ownsAudio = true
        if (midItem) {
            active.resumeAfterTakeBackUs = publishedPositionMicros.value
        } else {
            active.audioTailFlushed.value = true
            endOfStream.draining = true
            endOfStream.sinkDrained = true
        }
        // The taps heard the next item's first blocks, which will never play.
        tapsDiscontinuous(active = active)
        active.audioFeedWorker?.release(requestedEpoch)
    }

    /** The actions that change what follows the current item, or where it plays. See docs/gapless-queue.md. */
    private fun dropsPreload(command: CoreCommand): Boolean = when (command) {
        is CoreCommand.QueueNext -> primedFor(neighbourInOrder(1)) == null
        is CoreCommand.Open, is CoreCommand.OpenQueue, is CoreCommand.QueuePrevious,
        is CoreCommand.EditQueue, is CoreCommand.SetShuffle, is CoreCommand.RestoreQueueOrder,
        is CoreCommand.Seek, is CoreCommand.SeekLater, is CoreCommand.Close,
        is CoreCommand.SelectTrack, is CoreCommand.SelectSecondarySubtitle,
        is CoreCommand.AttachRenderer, is CoreCommand.DetachRenderer, is CoreCommand.StepFrame,
        is CoreCommand.SetSleepTimer, is CoreCommand.SetAbLoop,
        -> true
        is CoreCommand.Stop -> stopApplies(command)
        is CoreCommand.SetLoop -> command.mode != loop
        is CoreCommand.SetSpeed -> command.value != speed
        is CoreCommand.SetPreservePitch -> command.value != preservePitch
        is CoreCommand.SetVideoEnabled -> command.value != videoEnabled
        else -> false
    }

    /**
     * Steps a PAUSED player forward by exactly one decoded frame.
     *
     * This used to be a precise seek to the current position plus one NOMINAL frame period taken
     * from the container's declared rate. Every assumption in that sentence fails on real media:
     * variable frame rate has no nominal period, B-frames and repeated or non-monotonic timestamps
     * make "one period later" land on the wrong frame, and a container whose declared rate is
     * simply wrong skips or repeats. It also treated a superseded seek as a successful step, and it
     * refused to work at all on a source that could not seek.
     *
     * What it does now is what stepping is: the decoder has already filled the frame queue ahead of
     * the paused picture, so the schedule is asked to release exactly one of them. The frame that
     * comes out is the next frame of the media, whatever its timestamp, on any source, seekable or
     * not, and no decoding is repeated to get it.
     */
    private suspend fun stepOneFrame(reply: CompletableDeferred<Unit>) {
        val active = session
        when {
            active == null ->
                reply.completeExceptionally(IllegalStateException("stepFrame needs an open media item"))
            playRequested ->
                reply.completeExceptionally(IllegalStateException("stepFrame steps a PAUSED player; pause first"))
            active.videoStream == null || active.video == null ->
                reply.completeExceptionally(UnsupportedOperationException("stepFrame needs a selected video track"))
            else -> {
                when (presentFirstFrame(active, STEP_DEADLINE)) {
                    FirstFrame.Submitted, FirstFrame.Headless, FirstFrame.Refused -> {
                        // The picture IS the position while paused, so the step publishes the
                        // timestamp of the frame it just put on screen. Not the video clock: that
                        // reads a little past the frame, and the scheduler records it only after
                        // this pass can already have seen the frame go out.
                        active.pictureHoldsPosition = true
                        active.video?.shownPts()?.let { publishedPositionMicros.value = it.micros }
                        publishSnapshot()
                        reply.complete(Unit)
                    }
                    FirstFrame.None -> reply.completeExceptionally(
                        IllegalStateException(
                            "no frame reached the screen within $STEP_DEADLINE; the media may have ended",
                        ),
                    )
                    FirstFrame.NoVideo -> reply.completeExceptionally(
                        UnsupportedOperationException("stepFrame needs a selected video track"),
                    )
                }
            }
        }
    }

    /**
     * Steps a PAUSED player back to the last frame before the one on screen.
     *
     * The decoder has thrown that frame away, so this is a precise seek with the other landing: the
     * container seek aims just under the picture, and the video lane keeps the newest frame below it
     * until the picture's own frame arrives. The step answers when that seek does. A landing that is
     * not before the picture means the picture is the first frame, and the step says so.
     *
     * A seek still waiting to run is the timeline the caller asked for, so the step goes back from
     * that seek's target instead of from the picture the seek is about to replace.
     */
    private fun stepOneFrameBack(reply: CompletableDeferred<Unit>) {
        val active = session
        val waitingTargetUs = maskedSeekTargetMicros.value.takeIf { pendingSeek != null && it != NO_SEEK_MASK }
        val onScreenUs = waitingTargetUs ?: active?.video?.shownPts()?.micros ?: NO_POSITION
        when {
            active == null ->
                reply.completeExceptionally(IllegalStateException("stepFrame needs an open media item"))
            playRequested ->
                reply.completeExceptionally(IllegalStateException("stepFrame steps a PAUSED player; pause first"))
            active.videoStream == null || active.video == null ->
                reply.completeExceptionally(UnsupportedOperationException("stepFrame needs a selected video track"))
            !active.source.seekable ->
                reply.completeExceptionally(
                    UnsupportedOperationException(
                        "a backward step seeks to the keyframe before the picture, and this source is not seekable",
                    ),
                )
            onScreenUs == NO_POSITION ->
                reply.completeExceptionally(IllegalStateException("no frame is on screen yet, so there is nothing to step back from"))
            else -> {
                val landing = CompletableDeferred<SeekResult>()
                queueSeek(
                    SeekRequest(SeekTarget.Absolute(Pts(onScreenUs)), SeekMode.Precise, SeekLanding.Before),
                    landing,
                )
                // Every seek reply completes exactly once, so this answers the step exactly once too.
                landing.invokeOnCompletion { cause ->
                    if (cause != null) {
                        reply.completeExceptionally(cause)
                        return@invokeOnCompletion
                    }
                    when (val result = landing.getCompleted()) {
                        is SeekResult.Applied -> if (result.landedAt.micros < onScreenUs) {
                            reply.complete(Unit)
                        } else {
                            reply.completeExceptionally(
                                IllegalStateException(
                                    "no frame comes before the one at ${onScreenUs}us; it is the first frame of the media",
                                ),
                            )
                        }
                        // Like seek(): a request that a newer one replaced is not a failure.
                        is SeekResult.Superseded -> reply.complete(Unit)
                        is SeekResult.Rejected -> reply.completeExceptionally(IllegalStateException(result.reason))
                    }
                }
            }
        }
    }

    /**
     * Arms the schedule's one-shot capture. Playing, the very next presented frame
     * fulfils it; paused, a precise seek to the current position pushes one frame through the
     * same gate, so the copy is always taken at the presentation boundary, before ownership
     * moves to the renderer.
     */
    private fun requestCapture(reply: CompletableDeferred<io.github.yuroyami.kiteplayer.CapturedFrame>) {
        val active = session
        val video = active?.video
        when {
            active == null || video == null ->
                reply.completeExceptionally(IllegalStateException("captureFrame needs an open media item with video"))
            active.videoStream == null ->
                reply.completeExceptionally(UnsupportedOperationException("captureFrame needs a selected video track"))
            // Neither branch below can present a frame here: a precise seek to the end lands past
            // the last frame, and a playing schedule has nothing left to present (#197).
            status == PlaybackStatus.Ended ->
                reply.completeExceptionally(
                    IllegalStateException("the media has ended, so no frame is left to capture; seek first"),
                )
            !playRequested && !active.source.seekable ->
                reply.completeExceptionally(
                    UnsupportedOperationException(
                        "a paused capture re-presents its frame by precise seek, and this source is not seekable",
                    ),
                )
            else -> {
                video.captureRequest.getAndSet(reply)?.completeExceptionally(
                    IllegalStateException("superseded by a newer captureFrame"),
                )
                if (!playRequested) {
                    queueSeek(SeekRequest(SeekTarget.Absolute(currentPosition()), SeekMode.Precise), null)
                }
            }
        }
    }

    /**
     * The queue index one step away in PLAY order, or null when there is no such item.
     *
     * Play order and list order are the same thing until shuffle is on, so this is the only place
     * that has to know which is which.
     */
    private fun neighbourInOrder(delta: Int): Int? {
        if (queueOrder.isEmpty()) return null
        val at = queueOrder.indexOf(queueIndex)
        if (at < 0) return null
        val target = at + delta
        return when {
            target in queueOrder.indices -> queueOrder[target]
            loop == LoopMode.All && delta == 1 && shuffleEnabled && config.queue.reshuffleEachLap && queueOrder.size > 1 ->
                nextLap().first()
            loop == LoopMode.All ->
                queueOrder[((target % queueOrder.size) + queueOrder.size) % queueOrder.size]
            else -> null
        }
    }

    /** The next lap's order, drawn now if it was not yet: never starting with the item ending this lap. */
    private fun nextLap(): List<Int> {
        nextLapOrder?.takeIf { lapEndIndex == queueIndex }?.let { return it }
        val drawn = queueItems.indices.shuffled(shuffleRandom).toMutableList()
        if (drawn.size > 1 && drawn[0] == queueIndex) {
            val swap = shuffleRandom.nextInt(1, drawn.size)
            drawn[0] = drawn[swap].also { drawn[swap] = drawn[0] }
        }
        lapEndIndex = queueIndex
        nextLapOrder = drawn
        return drawn
    }

    /**
     * Called when the queue moved from [from] to [queueIndex]: a move forward off the end of the
     * lap onto the first item of the drawn next lap takes that lap up, and any other drops it.
     */
    private fun queueMoved(from: Int) {
        val lap = nextLapOrder ?: return
        nextLapOrder = null
        if (from == lapEndIndex && queueIndex == lap.first() && lap.size == queueItems.size) queueOrder = lap
    }

    /**
     * Opens the queue item at [queueIndex], from its preload when one is primed. With
     * [QueueItemFailure.Skip], an item that cannot be opened is reported and passed over, [step]
     * places along the play order, until one opens; the player fails with the last error when the
     * order runs out or when every item in the queue has failed in a row (#487).
     */
    private suspend fun openQueueItem(reply: CompletableDeferred<Unit>, step: Int) {
        val skip = config.queue.onItemFailure == QueueItemFailure.Skip
        var failedInARow = 0
        while (true) {
            val target = queueIndex
            var failure: PlaybackError? = null
            val absorb: ((PlaybackError) -> Boolean)? = if (skip) { error -> failure = error; true } else null
            val primed = primedFor(target)
            if (primed != null) {
                runOpenPrepared(primed, reply, absorb)
            } else {
                runOpen(CoreCommand.Open(queueItems[target], reply), absorb)
            }
            val error = failure
            if (error == null) {
                if (target in failedQueueIndices && session != null) {
                    failedQueueIndices = failedQueueIndices - target
                    publishSnapshot()
                }
                return
            }
            failedQueueIndices = failedQueueIndices + target
            failedInARow++
            warn(PlaybackWarning.QueueItemSkipped(target, queueItems[target].uri, error))
            val following = if (failedInARow < queueItems.size) neighbourInOrder(step) else null
            if (following == null) {
                fail(error)
                reply.completeExceptionally(PlaybackException(error))
                return
            }
            val from = queueIndex
            queueIndex = following
            queueMoved(from)
        }
    }

    /** Explicit queue movement, refused typed when there is nowhere to go. */
    private suspend fun jumpQueue(target: Int?, reply: CompletableDeferred<Unit>, direction: String) {
        if (queueItems.isEmpty()) {
            reply.completeExceptionally(IllegalStateException("no queue is open; openQueue first"))
            return
        }
        if (target == null) {
            // The position quoted is the one in PLAY order, which is the order the person is
            // hearing; quoting the list position under shuffle would name a different item.
            val playPosition = queueOrder.indexOf(queueIndex) + 1
            reply.completeExceptionally(
                IllegalStateException(
                    "the queue has no $direction item from $playPosition of ${queueItems.size}; " +
                        "LoopMode.All is what makes the ends meet",
                ),
            )
            return
        }
        val wasPlaying = playRequested
        val from = queueIndex
        queueIndex = target
        queueMoved(from)
        openCarriesPlay = wasPlaying
        try {
            openQueueItem(reply, step = if (direction == "previous") -1 else 1)
        } finally {
            openCarriesPlay = false
        }
        playRequested = wasPlaying
    }

    /**
     * The queue as an edit sees it.
     *
     * A plain [open] is a queue of one that has not been written down, so an edit counts it as
     * one. Refusing an edit there would make an application call `openQueue` with the item it is
     * already playing just to add a second one, which reopens what is on screen for nothing.
     */
    /**
     * Builds the play order from nothing.
     *
     * Shuffled, the item already playing comes first, because a shuffle that started somewhere
     * else would interrupt what is on screen to obey a setting.
     */
    private fun rebuildQueueOrder() {
        nextLapOrder = null
        queueOrder = when {
            queueItems.isEmpty() -> emptyList()
            !shuffleEnabled || queueIndex !in queueItems.indices -> queueItems.indices.toList()
            else -> listOf(queueIndex) +
                queueItems.indices.filter { it != queueIndex }.shuffled(shuffleRandom)
        }
    }

    /**
     * Carries the play order across an edit that renumbers the list.
     *
     * Rebuilding instead would reshuffle what the listener has not heard yet, so adding one track
     * would change the order of every track after it.
     */
    private fun remapQueueOrder(renumber: (Int) -> Int) {
        nextLapOrder = null
        queueOrder = queueOrder.map(renumber)
        failedQueueIndices = failedQueueIndices.map(renumber).toSet()
    }

    private fun editableQueueSize(): Int =
        if (queueItems.isNotEmpty()) queueItems.size else if (media != null) 1 else 0

    private fun queueEditRejection(edit: QueueEdit): Throwable? {
        val size = editableQueueSize()
        if (size == 0) {
            return IllegalStateException("${edit.name} needs an open item or queue; open or openQueue first")
        }
        fun outside(index: Int, limit: Int): Throwable? =
            if (index in 0..limit) null else IllegalArgumentException(
                "index $index is outside the queue of $size",
            )
        return when (edit) {
            is QueueEdit.Add -> when {
                edit.items.isEmpty() -> IllegalArgumentException("addToQueue needs at least one item")
                // One past the end is legal, and only here: that position IS an append.
                else -> outside(edit.index ?: size, size)
            }
            is QueueEdit.Remove -> outside(edit.index, size - 1)
            is QueueEdit.Move -> outside(edit.from, size - 1) ?: outside(edit.to, size - 1)
            QueueEdit.Clear -> null
        }
    }

    /**
     * Applies one edit.
     *
     * The law all four follow: the item that was playing keeps playing, and the cursor goes
     * wherever that item went. Removing the playing item is the only edit that opens anything,
     * because that item is gone and something has to take its place.
     */
    private suspend fun applyQueueEdit(edit: QueueEdit, reply: CompletableDeferred<Unit>) {
        // A lap drawn before the edit names positions the edit may move (#488).
        nextLapOrder = null
        // The unwritten queue of one becomes a written one on its first edit.
        if (queueItems.isEmpty()) {
            queueItems = listOfNotNull(media)
            queueIndex = 0
            rebuildQueueOrder()
        }
        when (edit) {
            is QueueEdit.Add -> {
                val at = edit.index ?: queueItems.size
                queueItems = queueItems.toMutableList().apply { addAll(at, edit.items) }
                if (at <= queueIndex) queueIndex += edit.items.size
                remapQueueOrder { if (it >= at) it + edit.items.size else it }
                queueOrder = queueOrder.toMutableList().apply {
                    // Shuffled, each new item lands at a random position AFTER the one playing:
                    // last every time would make an add predictable, and anywhere at all would let
                    // it jump ahead of tracks that were already queued to play.
                    val after = indexOf(queueIndex) + 1
                    (at until at + edit.items.size).forEach { added ->
                        add(if (shuffleEnabled) shuffleRandom.nextInt(after, size + 1) else added, added)
                    }
                }
                publishSnapshot()
                reply.complete(Unit)
            }
            is QueueEdit.Move -> {
                queueItems = queueItems.toMutableList().apply { add(edit.to, removeAt(edit.from)) }
                remapQueueOrder { position ->
                    when {
                        position == edit.from -> edit.to
                        edit.from < position && position <= edit.to -> position - 1
                        edit.to <= position && position < edit.from -> position + 1
                        else -> position
                    }
                }
                queueIndex = if (queueIndex == edit.from) {
                    edit.to
                } else {
                    // Where the playing item ends up: the removal pulls it back when it sat
                    // behind the moved one, and the insert pushes it along when the moved one
                    // lands at or in front of it.
                    val afterRemoval = if (edit.from < queueIndex) queueIndex - 1 else queueIndex
                    if (edit.to <= afterRemoval) afterRemoval + 1 else afterRemoval
                }
                publishSnapshot()
                reply.complete(Unit)
            }
            QueueEdit.Clear -> {
                queueItems = listOf(queueItems[queueIndex])
                failedQueueIndices = if (queueIndex in failedQueueIndices) setOf(0) else emptySet()
                queueIndex = 0
                queueOrder = listOf(0)
                publishSnapshot()
                reply.complete(Unit)
            }
            is QueueEdit.Remove -> removeQueueItem(edit.index, reply)
        }
    }

    private suspend fun removeQueueItem(index: Int, reply: CompletableDeferred<Unit>) {
        val removedPlaying = index == queueIndex
        queueItems = queueItems.toMutableList().apply { removeAt(index) }
        queueOrder = queueOrder.filter { it != index }.map { if (it > index) it - 1 else it }
        failedQueueIndices = failedQueueIndices.filter { it != index }.map { if (it > index) it - 1 else it }.toSet()
        if (!removedPlaying) {
            if (index < queueIndex) queueIndex -= 1
            publishSnapshot()
            reply.complete(Unit)
            return
        }
        // What followed it takes its place. LoopMode.All is what makes the ends meet, here as
        // everywhere else in the queue.
        val next = when {
            index < queueItems.size -> index
            loop == LoopMode.All && queueItems.isNotEmpty() -> 0
            else -> null
        }
        if (next == null) {
            // Nothing followed it, so the player stops rather than holding a picture of media the
            // queue no longer contains. The cursor rests on the last item still there, so
            // previous() has somewhere to go; an emptied queue puts it back to -1, which is the
            // same expression.
            queueIndex = queueItems.size - 1
            runStop()
            reply.complete(Unit)
            return
        }
        val wasPlaying = playRequested
        queueIndex = next
        openQueueItem(reply, step = 1)
        playRequested = wasPlaying
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
            // handlePlaybackTime cannot clear the mask with no session, so the request that
            // cannot run clears it here; the published zero is the honest sessionless answer.
            maskedSeekTargetMicros.value = NO_SEEK_MASK
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
        val activeBeforeSeek = session
        val requestedTarget = activeBeforeSeek?.let {
            request.resolve(currentPosition(), it.source.duration, it.source.seekCeiling)
        }
        try {
            runSeek(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val failed = session
            if (failed != null && requestedTarget != null) {
                try {
                    val observed = if (failure is VideoDecoderRuntimeFailure) {
                        recoverDirectVideoFailure(failed, failure, requestedTarget, request.mode)
                    } else {
                        recoverObservedVideoFailure(failed, requestedTarget, request.mode)
                    }
                    if (observed != null) {
                        val recovered = observed.result ?: return
                        emitEvent(
                            PlayerEvent.SeekCompleted(recovered.epoch, recovered.landedAt.asDuration),
                        )
                        resolveSeekReplies(SeekResult.Applied(recovered.landedAt))
                        setStatus(if (playRequested) PlaybackStatus.Buffering else PlaybackStatus.Paused)
                        return
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (recoveryFailure: Throwable) {
                    val error = (recoveryFailure as? PlaybackException)?.error
                        ?: PlaybackError.Internal("the decoder recovery during seek failed", recoveryFailure)
                    seekPhase = SeekPhase.Idle
                    resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
                    teardownSession()
                    fail(error)
                    return
                }
            }
            // A source that throws mid-seek leaves the pipeline flushed and the cursor nowhere useful, so
            // the session is torn down rather than left in a position nothing knows.
            val error = PlaybackError.Internal("the seek failed", failure)
            seekPhase = SeekPhase.Idle
            resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
            teardownSession()
            fail(error)
        }
    }

    private fun shouldHold(request: SeekRequest, nowNanos: Long): Boolean {
        val session = session ?: return false
        val sinceLastSeekUs = (nowNanos - lastSeekAtNanos) / 1_000
        if (lastSeekAtNanos != 0L && sinceLastSeekUs < SeekTiming.COALESCE_WINDOW_US) {
            // Released, so that a refusing renderer does not freeze scrubbing: the previous seek
            // did produce its frame, the output simply would not draw it.
            val shown = session.framesReleased(session.video)
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

        // The accepted request IS the timeline now. Refreshing the mask with the merged request's
        // resolution covers the targets the entry points cannot name (relative and factor need a
        // position and a duration). Resolving against the masked position rather than the cursor
        // makes a relative request issued during a held seek stack on the timeline the user
        // already asked for, exactly like the merge rules do.
        val active = session
        val accepted = pendingSeek
        if (active != null && accepted != null) {
            val basis = maskedSeekTargetMicros.value.takeIf { it != NO_SEEK_MASK }
                ?: publishedPositionMicros.value
            maskedSeekTargetMicros.value =
                accepted.resolve(Pts(basis), active.source.duration, active.source.seekCeiling).micros
        }
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
        // A seek takes the ring back from a next item that already writes into it, before the
        // flush below clears it. Commands drop the preload earlier; this is for the engine's own seeks.
        dropPending(null)
        val session = session ?: return
        val tracing = KiteTrace.enabled
        var phaseBegin = if (tracing) clock.nanos() else 0L
        val origin = currentPosition()
        val target = request.resolve(origin, session.source.duration, session.source.seekCeiling)
        val landsBefore = request.landing == SeekLanding.Before
        // Only a plain keyframe seek takes the choice (#496). The precise modes decode forward from
        // the keyframe before the target, and a backward landing needs the frame before the one on
        // screen, so both keep the keyframe before.
        val keyframe = when {
            request.mode != SeekMode.Keyframe || landsBefore -> KeyframeChoice.Before
            request.keyframe == KeyframeChoice.InSeekDirection ->
                if (target > origin) KeyframeChoice.After else KeyframeChoice.Before
            else -> request.keyframe
        }
        session.pictureHoldsPosition = false

        // 1
        val previousEpoch = requestedEpoch
        requestedEpoch = requestedEpoch.next()
        val epoch = requestedEpoch
        seekPhase = SeekPhase.Flushing
        // Status follows intent, not machinery. Buffering means "the user asked for playback and
        // the engine cannot supply it", so a paused seek must not visit it: every state mirror
        // read the old unconditional Buffering as a momentary unpause. A seek that starts at
        // Ended keeps Ended until the landing below proves the position moved.
        if (playRequested && status != PlaybackStatus.Ended) setStatus(PlaybackStatus.Buffering)
        endOfStream.reset()
        stillImageFinished = false
        stillImageShownSinceNanos = 0

        // 2
        session.schedulerMode.value = SCHEDULER_IDLE
        // A playing device fades out before it stops, so the seek does not cut the wave (#486).
        stopAudioDevice(session)
        val unparked = unparkedWorkers(session)
        if (unparked.isNotEmpty()) {
            // Quiescence is the precondition of every mutation below. Without it, flushing a
            // decoder or clearing a queue mutates state a still-running worker may be using, so
            // the seek aborts as a transaction instead of continuing on best effort. The workers
            // are released so playback continues at the old position, and every waiting caller
            // gets an explicit rejection rather than a fabricated success.
            val workers = if (unparked.size == 1) "the ${unparked[0]} worker" else "the ${unparked.joinToString()} workers"
            val reason = "$workers did not park within $QUIESCE_DEADLINE; the seek was aborted"
            warn(PlaybackWarning.CommandRefused("seek", reason))
            seekPhase = SeekPhase.Idle
            session.discardBeforeUs.value = Long.MIN_VALUE
            session.landsBeforeTarget.value = false
            // Nothing was flushed to the new epoch, so the queues and decoders still carry the old
            // one. Released into the new epoch, every packet and frame was refused for ever (#200).
            requestedEpoch = previousEpoch
            releaseWorkers(session, previousEpoch)
            resolveSeekReplies(SeekResult.Rejected(reason))
            if (!playRequested && status != PlaybackStatus.Ended) setStatus(PlaybackStatus.Paused)
            return
        }
        // After the quiesce, so an aborted seek keeps the recording, and before the container seek,
        // so no packet from the new position reaches the file.
        endRecording(session, reason = "a seek moved the playback position")
        // Every lane is parked, and the flush below aligns whatever picture plays (#527).
        restorePictureForSeek(session, target.micros)

        var attempt = 0
        var landed: Pts? = null
        // KeyframeThenRefine runs this loop in two phases: the first
        // lands and PRESENTS the keyframe at or before the target, which is the immediate
        // picture a seek-bar drag wants, and the second is an ordinary precise landing on the
        // exact frame. Every other mode has exactly one phase.
        var refining = false
        while (true) {
            val phaseMode = when {
                request.mode != SeekMode.KeyframeThenRefine -> request.mode
                refining -> SeekMode.Precise
                else -> SeekMode.Keyframe
            }
            // 3 is implicit and is the point of step 2: the scheduler is parked, so no frame of the old
            // epoch can reach the renderer from here on.
            val backoff = SeekTiming.OVERSHOOT_BACKOFF_US[attempt]
            // A backward landing aims one microsecond under the frame on screen, so the container
            // seek resolves to the keyframe before it even when that frame is itself a keyframe.
            val under = if (landsBefore) 1L else 0L
            val aim = Pts((target.micros - backoff - under).coerceAtLeast(0L))

            // 4
            flushDecoders(session, epoch)
            // 5
            clearBuffers(session, epoch)
            // The container seek is a blocking native call, and a
            // wedged one used to hold the actor for ever. It runs as a child on the demux lane;
            // when the deadline passes and the source can interrupt, the call is aborted and the
            // seek fails typed. A source that cannot interrupt keeps the old unbounded wait,
            // which is no worse than every engine before this one.
            val seekCall = scope.async(dispatchers.demux) {
                runCatching { session.source.seekToKeyframe(aim, keyframe) }
            }
            val prompt = withTimeoutOrNull(SEEK_NATIVE_DEADLINE) { seekCall.await() }
            val seekOutcome = when {
                prompt != null -> prompt
                !session.source.interrupt() -> seekCall.await()
                else -> withTimeoutOrNull(SEEK_INTERRUPT_GRACE) { seekCall.await() }
                    ?: Result.failure(
                        IllegalStateException(
                            "the interrupted container seek did not return within $SEEK_INTERRUPT_GRACE",
                        ),
                    )
            }
            seekOutcome.exceptionOrNull()?.let { failure ->
                // The source is poisoned mid-seek; nothing on it can be trusted again. Fail the
                // session typed instead of freezing the player, and answer every waiting caller.
                val reason = "the container seek did not complete: ${failure.message}"
                seekPhase = SeekPhase.Idle
                session.discardBeforeUs.value = Long.MIN_VALUE
                session.landsBeforeTarget.value = false
                val error = PlaybackError.SourceUnavailable(media?.uri ?: "", failure, reason)
                // Fail first, answer last: a caller resumed by the rejection must observe the
                // final Failed state, not a session halfway through its teardown.
                teardownSession()
                fail(error)
                resolveSeekReplies(SeekResult.Rejected(reason))
                return
            }

            // 7
            seekPhase = if (phaseMode == SeekMode.Keyframe) SeekPhase.Filling else SeekPhase.Discarding
            // The exact target, not target minus tolerance: the public promise is "the first
            // frame at or after the target", and a 5 ms allowance under it showed pre-target
            // pictures and audio the promise says cannot appear. The tolerance
            // still exists where it belongs, in the overshoot judgment below.
            session.discardBeforeUs.value = when (phaseMode) {
                SeekMode.Keyframe -> Long.MIN_VALUE
                else -> target.micros
            }
            session.landsBeforeTarget.value = landsBefore && phaseMode != SeekMode.Keyframe
            session.firstVideo.clear()
            session.firstDecodedVideo.clear()
            session.firstAudio.clear()
            // A ping from the flushed epoch must not satisfy this landing's first wait.
            do {
                val stale = session.landingArrived.tryReceive()
            } while (stale.isSuccess)
            // Before the feeder writes a sample of the new position (#467).
            startTurn(session, target.micros)
            releaseWorkers(session, epoch)
            landed = awaitLanding(session, epoch)

            // A keyframe seek is documented to land at or before the target, and a container without an
            // index resolves it by byte position and can land after it. The first frame the decoder
            // produced is the evidence, so that is what is judged here.
            val decoded = session.firstDecodedVideo.of(epoch) ?: session.firstAudio.of(epoch)
            // A backward landing has overshot as soon as the first frame is not before the
            // target, because then there is no earlier frame to show. A keyframe chosen after the
            // target or nearest to it lands past the target on purpose, so it has no overshoot.
            val overshot = decoded != null && keyframe == KeyframeChoice.Before && if (landsBefore) {
                decoded.micros >= target.micros
            } else {
                decoded.micros > target.micros + SeekTiming.PRECISE_TOLERANCE_US
            }
            val laddered = attempt < SeekTiming.OVERSHOOT_BACKOFF_US.lastIndex && aim.micros > 0L
            if (!overshot || !laddered || preempted() || seekSuperseded()) {
                val keyframeShort = landed != null && landed.micros < target.micros
                // No refine when a newer request waits; the keyframe that already
                // presented is this seek's whole answer, and the newer target takes the pipeline.
                if (request.mode == SeekMode.KeyframeThenRefine && !refining && keyframeShort &&
                    !preempted() && !seekSuperseded()
                ) {
                    // The immediate picture: the keyframe presents NOW, before the refine pass
                    // pays its decode-forward. The mask keeps reporting the exact target
                    // throughout, so no observer mistakes the keyframe for the answer.
                    presentFirstFrame(session)
                    if (tracing) {
                        val now = clock.nanos()
                        KiteTrace.span("seek", "keyframe", phaseBegin, now, seekTraceArgs(target, landed))
                        phaseBegin = now
                    }
                    refining = true
                    attempt = 0
                    session.schedulerMode.value = SCHEDULER_IDLE
                    // The refine repeats the flush-clear-seek pass, so it needs the same
                    // quiescence; a refusal keeps the keyframe landing as the honest result.
                    if (!quiesceWorkers(session)) {
                        releaseWorkers(session, epoch)
                        break
                    }
                    continue
                }
                break
            }
            attempt++
            session.schedulerMode.value = SCHEDULER_IDLE
            // Same precondition as step 2: another flush pass may only run against parked
            // workers. If they cannot be parked, this attempt's landing stands as the result,
            // and the workers are released again so none stays parked behind the break.
            if (!quiesceWorkers(session)) {
                releaseWorkers(session, epoch)
                break
            }
        }

        // 8
        if (tracing) {
            val phase = when {
                refining -> "refine"
                request.mode == SeekMode.Precise -> "precise"
                else -> "keyframe"
            }
            KiteTrace.span("seek", phase, phaseBegin, clock.nanos(), seekTraceArgs(target, landed))
        }
        seekPhase = SeekPhase.Idle
        session.discardBeforeUs.value = Long.MIN_VALUE
        session.landsBeforeTarget.value = false
        lastSeekAtNanos = clock.nanos()
        framesShownAtLastSeek = session.framesReleased(session.video)
        // No landing frame is a real answer, not a formality to paper over. It is legitimate in
        // exactly two shapes: the seek ran off the end of the stream (nothing left to decode), or
        // a later request preempted this one. Anything else means the pipeline produced nothing
        // within the deadline, and reporting SeekCompleted there was the fabricated success the
        // audit named. The caller gets a rejection and the completion event is not emitted.
        val endOfStreamLanding = session.selectedQueues().all { it.isEndOfStream } && session.decodersDrained()
        // A landing abandoned for a newer request is superseded, not rejected. The
        // newer seek runs on the next pass and its own landing is the position the caller wants.
        if (landed == null && seekSuperseded() && !preempted()) {
            resolveSeekReplies(SeekResult.Superseded(epoch))
            return
        }
        // A backward step cut short by stop or close has no picture to report, and the target it
        // would report instead is the frame it was stepping away from.
        if (landed == null && landsBefore && preempted()) {
            resolveSeekReplies(SeekResult.Rejected("stop() or close() arrived before the backward step landed"))
            return
        }
        if (landed == null && !endOfStreamLanding && !preempted()) {
            resolveSeekReplies(
                SeekResult.Rejected("the pipeline produced no frame for the seek target within $SEEK_DEADLINE"),
            )
            if (!playRequested && status != PlaybackStatus.Ended) setStatus(PlaybackStatus.Paused)
            return
        }
        publishedPositionMicros.value = (landed ?: target).micros
        // A backward step lands on a frame the clocks never reached, so the picture holds the
        // position there as it does after a forward step.
        session.pictureHoldsPosition = landsBefore && landed != null
        // The landing is the position now. Left set, the mask answers the target until the next
        // pass, and a caller reads the position as soon as the reply below completes.
        if (pendingSeek == null) maskedSeekTargetMicros.value = NO_SEEK_MASK
        // A landing that ran off the end of the stream has no frame to show by definition, so it
        // takes the silent form: warning there would fire on every seek to the end of a file.
        if (landed != null) reportFirstFrame(session, "seek") else presentFirstFrame(session)
        emitEvent(PlayerEvent.SeekCompleted(epoch, (landed ?: target).asDuration))
        resolveSeekReplies(SeekResult.Applied(landed ?: target))
        // An applied seek is the one legal exit from Ended besides open and stop: the position
        // moved, so "playback reached the end" is no longer true. A landing that itself ran off
        // the end keeps Ended out of the play route, and the failure returns above keep Ended
        // untouched because a seek that moved nothing proved nothing.
        if (!playRequested) {
            setStatus(PlaybackStatus.Paused)
        } else if (status == PlaybackStatus.Ended && landed != null) {
            setStatus(PlaybackStatus.Buffering)
        }
    }

    private suspend fun quiesceWorkers(session: OpenSession): Boolean = unparkedWorkers(session).isEmpty()

    /** Parks every worker and returns the names of those that missed the deadline. */
    private suspend fun unparkedWorkers(session: OpenSession): List<String> {
        // Every flag first, then every acknowledgement: the workers park in parallel, so the whole
        // pipeline costs one worker's residual nap rather than the sum of all of them.
        val workers = session.workers
        for (worker in workers) worker.requestQuiesce()
        val unparked = workers.filterNot { worker -> worker.awaitQuiesced(QUIESCE_DEADLINE) }.map { it.name }
        seekFlushCycles++
        return unparked
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
            withContext(dispatchers.videoDecode) {
                try {
                    decoder.flush(epoch)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    throw VideoDecoderRuntimeFailure("flush", failure)
                }
            }
        }
        session.audioDecoder?.let { decoder ->
            withContext(dispatchers.audioDecode) { decoder.flush(epoch) }
        }
    }

    private suspend fun clearBuffers(session: OpenSession, epoch: Generation) {
        session.allPacketQueues.forEach { it.flushTo(epoch) }
        session.lastSwitchCachePrunePositionUs = Long.MIN_VALUE
        // A retained subtitle packet belongs to the flushed position and is owned here alone.
        session.pendingSubtitlePacket?.close()
        session.pendingSubtitlePacket = null
        session.subtitleDecoderMayHaveOutput = false
        session.subtitleDrained = false
        session.subtitleDrainRefusals = 0
        session.lastSubtitlePruneCutoffUs = Long.MIN_VALUE
        // The pure selector makes seek reconstruction trivial: clear, re-decode from the landing
        // point, and the next pass's activeAt IS the rebuilt state, in either direction.
        session.subtitleCueCaches.values.forEach { it.clear() }
        session.subtitleCues = session.subtitleStream
            ?.let { stream -> session.subtitleCueCaches.getValue(stream.index) }
            ?: mutableListOf()
        // External cues are position-independent facts about the file beside the media: a flush
        // that empties the table must put them back, or every seek silences them.
        selectedExternalSubtitle
            ?.let { id -> externalSubtitleTracks.firstOrNull { it.id == id } }
            ?.cues
            ?.let { session.subtitleCues.addAll(it) }
        session.publishedCueKey = null
        session.subtitleDecoder?.flush(epoch)
        // The typesetter drops its events too and re-reads them as the demuxer redelivers.
        session.typeset?.let { lane ->
            lane.stage(TypesetOp.Clear)
            lane.lastRequestMillis = Long.MIN_VALUE
        }
        // The secondary lane's mirror of everything above.
        session.pendingSubtitle2Packet?.close()
        session.pendingSubtitle2Packet = null
        session.subtitle2DecoderMayHaveOutput = false
        session.subtitle2Drained = false
        session.subtitle2DrainRefusals = 0
        session.lastSubtitle2PruneCutoffUs = Long.MIN_VALUE
        session.subtitle2Cues = when {
            session.subtitle2Stream != null ->
                session.subtitleCueCaches.getValue(session.subtitle2Stream!!.index)
            selectedExternalSubtitle2 != null ->
                externalSubtitleTracks.firstOrNull { it.id == selectedExternalSubtitle2 }
                    ?.cues?.toMutableList() ?: mutableListOf()
            else -> mutableListOf()
        }
        session.subtitle2Decoder?.flush(epoch)
        while (true) {
            val buffer = session.decodedAudio.tryReceive().getOrNull() ?: break
            buffer.close()
            session.audioInFlight.decrementAndGet()
        }
        // A seek away from the end makes the stream un-ended, so the token and the feeder's answer
        // both go back. Left set, the next arrival at the end would read a stale "already flushed"
        // and skip the tail it was supposed to push.
        session.audioEosRequested.value = false
        session.audioTailFlushed.value = false
        session.audioSwitchDiscardBeforeUs.value = Long.MIN_VALUE
        session.pictureSwitchDiscardBeforeUs.value = Long.MIN_VALUE
        // A seek owns the position, so a take-back's resume gives way to it (#467).
        session.resumeAfterTakeBackUs = NO_POSITION
        endOfStream.tailRequestedNanos = 0
        endOfStream.tailAbandoned = false
        session.video?.flush(epoch)
        // The scheduler is the only writer of this reading, and it is parked, so clearing it here is the
        // same discipline as flushing a decoder on its owning worker. It has to be cleared: a position
        // measured at the place the viewer just left must not be reported as the place they arrived at.
        session.lastVideoPtsUs.value = NO_POSITION
        // Stops the device again, harmlessly, and then clears the ring: the callback is already out, and
        // the ring's own contract requires exactly that before its counters are written.
        session.audio?.flush(epoch)
        tapsDiscontinuous()
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
        // A backward landing is a picture before the target, and the sound only starts at the
        // target, so the sound can never answer one, however long the decoder takes.
        val pictureOnly = session.landsBeforeTarget.value
        fun answer(video: Pts?, audio: Pts?): Pts? = if (pictureOnly) video else video ?: audio
        while (clock.nanos() < deadline) {
            session.firstWorkerOutcome.value?.cause?.let { throw it }
            val video = session.firstVideo.of(epoch)
            val audio = session.firstAudio.of(epoch)
            if (video != null) return video
            // Video is the better answer when there is video, because it is the only side the precise
            // discard filters, so its first frame is the position that was asked for. Waiting for ever on
            // it is not: a stream whose pictures stopped arriving still has a position, and the sound
            // knows it.
            if (!pictureOnly && audio != null && (session.videoStream == null || clock.nanos() > videoGrace)) {
                return audio
            }
            if (session.selectedQueues().all { it.isEndOfStream } && session.decodersDrained()) return answer(video, audio)
            if (preempted()) return answer(video, audio)
            // A newer request ends this wait too; whatever landed is the answer.
            if (seekSuperseded()) return answer(video, audio)
            // Woken by the worker that records the landing; the poll stays only as the bound for
            // every condition above that has no ping of its own.
            withTimeoutOrNull(WORKER_POLL) { session.landingArrived.receive() }
        }
        return answer(session.firstVideo.of(epoch), session.firstAudio.of(epoch))
    }

    // ---------------------------------------------------------------------------------------------
    // Stop, close and failure.
    // ---------------------------------------------------------------------------------------------

    private suspend fun runStop() {
        sessionOwner = null
        cancelSubtitleAcquisitions { IllegalStateException("stop() ended the subtitle file's load before it finished") }
        playRequested = false
        pendingVideoRecovery = null
        pendingSeek = null
        discardPendingSelections("stop() tore the session down before the selection could be applied")
        resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
        // The caller's own end, so it warns nothing. The teardown below would warn.
        session?.let { endRecording(it, reason = null) }
        teardownSession()
        media = null
        tracks = Tracks.Empty
        externalSubtitleTracks = emptyList()
        selectedExternalSubtitle = null
        pendingExternalSubtitle = null
        endOfStream.reset()
        seekPhase = SeekPhase.Idle
        // Idle publishes Idle's numbers: a position and progress left over from the stopped
        // session would describe media that no longer exists.
        maskedSeekTargetMicros.value = NO_SEEK_MASK
        publishedPositionMicros.value = 0L
        progressState.value = Progress(position = Duration.ZERO, bufferedAhead = Duration.ZERO)
        setStatus(PlaybackStatus.Idle)
        publishSnapshot()
        // The stats too, and off the interval: the totals stay (they belong to the player, and the
        // stopped session was retired into them), while every gauge falls to its empty value
        // because there is no session to measure. Waiting for the next interval left a stopped
        // player reporting the queue depths of media it no longer holds.
        publishProgressAndStats(force = true)
    }

    private suspend fun runClose(reply: CompletableDeferred<Unit>) {
        if (closed) {
            return
        }
        closed = true
        playRequested = false
        pendingVideoRecovery = null
        pendingSeek = null
        cancelSubtitleAcquisitions { IllegalStateException("close() ended the subtitle file's load before it finished") }
        // Nothing here may block before the release below starts, because the close deadline is
        // the release's: finishing a recording, releasing the preload and waiting for preload
        // builds still unwinding all run inside it. Each used to run on the actor first, outside
        // any deadline, and the builds each had a deadline of their own (#473).
        val preload = takePreloadForClose()
        // The sessions come off the actor FIRST, so that everything below is about a graph nothing
        // else can reach, and the release can then run somewhere this coroutine is able to stop
        // waiting for. The close is the caller's own end of a recording, so it warns nothing.
        val detached = detachSession(quietRecordingEnd = true)
        val release = ClosingGraph(
            // The current item first: after a handoff the next item owns the audio device and the
            // ring the current one's clock reads, so the next item must be released after it.
            sessions = listOfNotNull(detached, preload.session),
            preloadBuild = preload.finishedBuild,
            // A build still unwinding runs on the dispatchers the finalizer closes.
            unwinding = retiringBuilds.toList() + listOfNotNull(preload.runningBuild),
        )
        retiringBuilds.clear()
        // A zero budget is used by tests to force the compromised-runtime result. It must not prevent
        // teardown from starting: a missed deadline changes the report, never resource ownership.
        val outcome = when {
            // First, and before the "nothing to release" case: a zero budget is the test override
            // that forces the compromised report deterministically, and it must do that whether or
            // not a session was open.
            closeDeadline <= Duration.ZERO -> {
                release.sessions.forEach { releaseReporting(it) }
                ReleaseOutcome.Overran
            }
            release.isEmpty -> ReleaseOutcome.Released
            else -> awaitRelease(release)
        }
        settleOutstandingForClose()
        val failure = when (outcome) {
            ReleaseOutcome.Released -> null
            ReleaseOutcome.Overran -> PlaybackException(
                PlaybackError.RuntimeCompromised(
                    if (teardownWedged.value) {
                        "teardown did not finish within $closeDeadline and is STILL RUNNING, so the " +
                            "playback threads were left alive rather than closed under it; a native " +
                            "call that has wedged cannot be killed from inside the process, so a caller " +
                            "that needs those threads back has to terminate the process"
                    } else {
                        "teardown did not finish within $closeDeadline, so a worker may still hold resources"
                    },
                ),
            )
            // A release that ended by throwing finished, and finishing is not releasing (#472).
            is ReleaseOutcome.Failed -> PlaybackException(
                PlaybackError.RuntimeCompromised(
                    "the session release stopped part way${causeDetail(outcome.cause)}, so a resource " +
                        "it had not reached may still be held",
                ),
            )
        }
        terminalCloseOutcome.compareAndSet(
            expect = null,
            update = TerminalCloseOutcome(reply = reply, failure = failure),
        )
        terminated = true
    }

    /**
     * Releases [graph] on a lifetime of its own and waits at most [closeDeadline] for it. That is
     * the close's one deadline: nothing before this call blocks, so a recording whose file is
     * still being written, a preload or a build still unwinding cannot hold a close past it (#473).
     *
     * The deadline used to wrap [teardownSession] directly, whose whole body is `NonCancellable`,
     * so it could never fire: a native close that wedged kept `closeAndAwait` suspended for ever
     * and the documented compromised-runtime report was unreachable. The release
     * is not abandoned, because abandoning it would leak the graph outright; what changes is that
     * the actor stops WAITING for it and reports the truth.
     *
     * @return how the release ended: inside the deadline cleanly, inside it by throwing, or not
     *         inside it at all.
     */
    private suspend fun awaitRelease(graph: ClosingGraph): ReleaseOutcome {
        var thrown: Throwable? = null
        // Parentless, so cancelling anything cannot abandon the graph half released, and on the
        // release lane, which is the one lane the actor is not standing on.
        val release = GlobalScope.launch(
            context = dispatchers.release + CoroutineName("kiteplayer-session-release"),
        ) {
            // Every session is released even when an earlier one threw; the first throw is the report.
            val built = graph.preloadBuild?.let { build -> runCatching { build.await() }.getOrNull()?.getOrNull()?.session }
            for (session in graph.sessions + listOfNotNull(built)) {
                releaseReporting(session)?.let { if (thrown == null) thrown = it }
            }
            for (build in graph.unwinding) runCatching { build.join() }
        }
        if (withTimeoutOrNull(closeDeadline) { release.join() } != null) {
            return thrown?.let { ReleaseOutcome.Failed(it) } ?: ReleaseOutcome.Released
        }
        // Still running. The dispatchers it is standing on must NOT be closed under it, so the
        // finalizer is told to leave them alone: leaked threads are recoverable by ending the
        // process, and closing a dispatcher a wedged native call is running on is not.
        teardownWedged.value = true
        return ReleaseOutcome.Overran
    }

    /**
     * Releases [detached] and returns what it threw, after warning about it. The release job has
     * no parent to report to, so a throw out of it would be an unhandled failure. warn() is
     * fence-locked and safe from any thread.
     */
    private suspend fun releaseReporting(detached: OpenSession): Throwable? = try {
        releaseSession(detached)
        null
    } catch (thrown: Throwable) {
        warn(PlaybackWarning.ResourcesNotReleased("the session release failed${causeDetail(thrown)}"))
        thrown
    }

    /**
     * Everything a close releases under its one deadline: the sessions it detached, the preload's
     * finished build whose session nobody adopted, and the cancelled builds still unwinding.
     */
    private class ClosingGraph(
        val sessions: List<OpenSession>,
        val preloadBuild: Deferred<Result<PreparedNext>>?,
        val unwinding: List<Job>,
    ) {
        val isEmpty: Boolean get() = sessions.isEmpty() && preloadBuild == null && unwinding.isEmpty()
    }

    /** What [takePreloadForClose] took: a session to release, a finished build to read one from, or a build to wait for. */
    private class ClosingPreload(
        val session: OpenSession? = null,
        val finishedBuild: Deferred<Result<PreparedNext>>? = null,
        val runningBuild: Job? = null,
    )

    /**
     * Takes the preload off the actor for a close, without releasing anything, so the close's
     * release does that under its deadline (#473). A handoff needs no taking back: the current
     * item no longer owns the audio, so it is released first and the next item, which owns it,
     * after it.
     */
    private fun takePreloadForClose(): ClosingPreload {
        val next = pendingNext ?: return ClosingPreload()
        pendingNext = null
        snapshotDirty = true
        next.build.warnings.discard()
        val prepared = next.prepared
        return when {
            prepared != null -> ClosingPreload(session = prepared.session)
            // A build the player's own cancellation stopped released what it opened, and awaiting
            // it would throw into the teardown.
            next.job.isCompleted -> ClosingPreload(finishedBuild = next.job.takeUnless { it.isCancelled })
            // Still opening: its own rollback releases what it opened, and the close waits for it.
            else -> {
                next.job.cancel()
                ClosingPreload(runningBuild = next.job)
            }
        }
    }

    /** How the release of a detached session ended, which decides what close reports. */
    private sealed interface ReleaseOutcome {
        /** It returned inside the deadline. A step that refused was warned about and the rest ran. */
        data object Released : ReleaseOutcome

        /** It threw inside the deadline, so the steps after the throw never ran. */
        class Failed(val cause: Throwable) : ReleaseOutcome

        /** It was still running at the deadline. */
        data object Overran : ReleaseOutcome
    }

    /** Set when a release outlived its deadline, so the finalizer leaves its dispatchers alone. */
    private val teardownWedged = atomic(false)

    /** Completes every command that close prevents from running, once, from the actor thread. */
    private fun settleOutstandingForClose() {
        pendingVideoRecovery = null
        discardPendingSelections("the player was closed before the selection could be applied")
        pendingSeek = null
        resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
        commands.close()
        while (true) {
            val pending = heldCommands.removeFirstOrNull() ?: commands.tryReceive().getOrNull() ?: break
            if (pending !is CoreCommand.Close) {
                pending.fail(IllegalStateException("the player was closed before ${pending.name} could run"))
            }
        }
    }

    private fun compromisedClose(detail: String): PlaybackException =
        PlaybackException(PlaybackError.RuntimeCompromised(detail))

    /**
     * Releases the actor's owned dispatchers only after its coroutine has returned.
     *
     * [GlobalScope] is deliberate here: this one-shot finalizer must have no caller, actor or worker job
     * as its parent, and [Dispatchers.Default] is not one of the six dispatchers it is about to close.
     * The returned deferred retains any scheduling failure so the completion hook can turn it into the
     * same typed terminal result instead of reporting an uncaught coroutine failure.
     */
    private fun launchCloseFinalizer(actorCause: Throwable?) {
        val outcome = terminalCloseOutcome.value
        if (outcome == null) {
            // An actor that dies before Close still leaves a terminal object. Transfer ownership here,
            // reject future commands immediately, and resolve everything the dead actor left behind.
            closedNow.compareAndSet(expect = false, update = true)
            settleOutstandingForClose()
        }
        var reportedFailure = when {
            outcome != null -> outcome.failure
            actorCause != null -> compromisedClose(
                "the session actor failed before terminal close settled${causeDetail(actorCause)}",
            )
            else -> compromisedClose("the session actor completed before terminal close settled")
        }
        val reply = outcome?.reply ?: terminalCloseResult
        val finalizer = GlobalScope.async(
            context = Dispatchers.Default + CoroutineName("kiteplayer-close-finalizer"),
        ) {
            try {
                // Never under a release that is still running: those threads ARE the dispatchers,
                // and closing one out from under a wedged native call turns a leak into a crash
                // The compromised report already names the leak.
                if (closeDispatchers && !teardownWedged.value) dispatchers.close()
            } catch (failure: Throwable) {
                reportedFailure = compromisedClose(
                    "the owned playback dispatchers did not close${causeDetail(failure)}",
                )
            }
            val terminalFailure = reportedFailure
            publishTerminalCloseState(terminalFailure)
            if (terminalFailure != null) {
                reply.completeExceptionally(terminalFailure)
            } else {
                reply.complete(Unit)
            }
        }
        finalizer.invokeOnCompletion { cause ->
            if (cause != null) {
                val failure = compromisedClose(
                    "the independent close finalizer failed${causeDetail(cause)}",
                )
                runCatching { publishTerminalCloseState(failure) }
                reply.completeExceptionally(failure)
            }
        }
    }

    /** Publishes the one terminal state after actor ownership ended and dispatcher shutdown resolved. */
    private fun publishTerminalCloseState(failure: PlaybackException?) {
        val error = failure?.error
        if (error != null) {
            lastError = error
            emitEvent(PlayerEvent.Failed(error))
        }
        if (status != PlaybackStatus.Idle) {
            recordTransition(status, PlaybackStatus.Idle)
            status = PlaybackStatus.Idle
        }
        // Terminal close leaves nothing of the closed media behind: a snapshot still naming the
        // media, its tracks or its position would describe a session that no longer exists.
        media = null
        tracks = Tracks.Empty
        maskedSeekTargetMicros.value = NO_SEEK_MASK
        publishedPositionMicros.value = 0L
        progressState.value = Progress(position = Duration.ZERO, bufferedAhead = Duration.ZERO)
        // The totals survive the close because they belong to the player and every session was
        // retired into them; every gauge is at its empty value because nothing is measurable any
        // more. Built by hand rather than through publishProgressAndStats for the reason below:
        // nothing here may consult the clock.
        statsState.value = PlaybackStats(
            decodedVideoFrames = retiredDecodedVideo,
            submittedFrames = retiredSubmitted,
            headlessFrames = retiredHeadless,
            droppedFramesLate = retiredDroppedLate,
            droppedFramesDecode = retiredDroppedDecode,
            refusedFrames = retiredRefused,
            repeatedFrames = retiredRepeated,
            audioUnderruns = retiredUnderruns,
            audioLimitedFrames = retiredLimited,
            droppedEvents = droppedEvents.value,
            rebuffers = rebuffers,
            syncMode = config.syncMode,
        )
        // Do not consult the clock or any worker after their dispatchers have closed. This is the same
        // state projection publishSnapshot would make with no live session, written once by the new owner.
        snapshotState.value = PlayerSnapshot(
            status = status,
            media = media,
            tracks = tracks,
            speed = speed,
            volume = volume,
            muted = muted,
            loop = loop,
            videoScale = videoScale,
            videoAdjustments = videoAdjustments,
            renderQuality = renderQuality,
            hdrPolicy = hdrPolicy,
            videoDynamicRange = videoDynamicRange,
            videoTransform = videoTransform,
            subtitleDelay = subtitleDelay,
            subtitleScale = subtitleScale,
            subtitleStyle = subtitleStyle,
            subtitlePosition = subtitlePosition,
            subtitleTypesetter = session?.typeset?.providerId,
            audioDelay = audioDelay,
            abLoopA = abLoopA,
            abLoopB = abLoopB,
            preservePitch = preservePitch,
            error = lastError,
            generation = requestedEpoch,
            queue = queueItems,
            queueIndex = queueIndex,
            shuffle = shuffleEnabled,
            queueOrder = queueOrder,
            failedQueueItems = failedQueueIndices,
            markers = markers,
            playRequested = publishedPlayIntent(),
        )
    }

    private fun causeDetail(cause: Throwable): String =
        cause.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()

    /**
     * Tears the session down in the reverse of the order it was built in.
     *
     * Workers first, so nothing is using a decoder when it closes, and each decoder on the dispatcher
     * that owned it. Everything is attempted even when something throws, because one backend refusing to
     * close is not a reason to leak the rest.
     */
    /**
     * Takes the session off the actor and folds its counters into the player's totals.
     *
     * Split from [releaseSession] for two reasons. Terminal close has to be able to BOUND its wait
     * for the release half, and it can only do that once the session is unreachable, which is this
     * line. And the counters must be retired at exactly this moment, because after
     * it nothing can read them again and their totals would otherwise fall back to the next
     * session's zero.
     */
    private fun detachSession(forHandoff: Boolean = false, quietRecordingEnd: Boolean = false): OpenSession? {
        val detached = session ?: return null
        session = null
        // Every path that retires a session comes through here: the next queue item, a video track
        // switch, a failure. None of them is an end the caller asked for, except a close. The
        // recording itself ends in the release: finishing its file waits for the demux worker's
        // write and then writes the trailer, and nothing that can block may run on the actor
        // before a close starts counting its deadline (#473).
        if (quietRecordingEnd) detached.recordingEnd = null
        // An armed capture waits for a frame that this session will never present (#197).
        detached.video?.captureRequest?.getAndSet(null)?.completeExceptionally(
            IllegalStateException("the media was closed before captureFrame got a frame"),
        )
        // The retiring worker keeps its old identity even if its final callback is still running.
        // A gapless swap marks the discontinuity for the incoming item itself.
        if (!forHandoff) tapsDiscontinuous(active = null)
        // No session, no cues. A stop or a close that left the last line published would have an
        // application drawing subtitles over nothing.
        cuesState.value = emptyList()
        retireCounters(detached)
        return detached
    }

    private suspend fun teardownSession() {
        dropPending(null)
        releaseSession(detachSession() ?: return)
    }

    /**
     * Ends the recording that [session]'s source makes, if one runs. A null [reason] is an end the
     * caller asked for and warns nothing. A file that cannot be finished warns in every case.
     */
    private fun endRecording(session: OpenSession, reason: String?) {
        val recorder = session.source as? RecordingCapable ?: return
        val path = recorder.recordingPath ?: return
        val failure = try {
            recorder.stopRecording()
            null
        } catch (failure: Exception) {
            failure
        }
        val why = failure?.let { "the file could not be finished: ${it.message ?: it::class.simpleName}" }
            ?: reason
            ?: return
        warn(PlaybackWarning.RecordingStopped(path, why))
    }

    /**
     * Traces the time from now until [reply] completes, when a trace sink is installed. [args] is
     * built only then.
     */
    private inline fun traceUntilReplied(
        reply: CompletableDeferred<*>,
        category: String,
        name: String,
        args: () -> Map<String, String>,
    ) {
        if (!KiteTrace.enabled) return
        val begin = clock.nanos()
        val fixed = args()
        reply.invokeOnCompletion { cause ->
            KiteTrace.span(category, name, begin, clock.nanos(), fixed + ("outcome" to if (cause == null) "done" else "failed"))
        }
    }

    private fun seekTraceArgs(target: Pts, landed: Pts?): Map<String, String> =
        mapOf("target" to target.micros.toString(), "landed" to (landed?.micros?.toString() ?: "none"))

    /** Folds one finished session's counters into the player's totals. Once per session, exactly. */
    private fun retireCounters(session: OpenSession) {
        retiredDecodedVideo += session.decodedVideoFrames.value
        retiredSubmitted += session.renderer.submittedFrames
        retiredHeadless += session.renderer.headlessFrames
        retiredDroppedLate += session.video?.droppedFrames ?: 0
        retiredDroppedDecode += session.droppedVideoBeforeDecode.value
        retiredRefused += session.video?.refusedFrames ?: 0
        retiredRepeated += session.video?.repeatedFrames ?: 0
        if (session.ownsAudio) retiredUnderruns += session.audio?.underruns ?: 0
        if (session.ownsAudio) retiredLimited += session.audio?.limitedFrames ?: 0
        retiredIoBytes += ioBytesOf(session)
    }

    /** The bytes a session read over its reader and over every reader opened for its media. */
    private fun ioBytesOf(session: OpenSession?): Long =
        (session?.cachingIo?.upstreamBytesRead?.value ?: 0L) + (session?.relatedTraffic?.bytes?.value ?: 0L)

    /**
     * Stops the session's audio device, fading the sound out first where the device would cut it
     * (#486). A session with a device and no audio path yet has nothing playing to fade.
     */
    private suspend fun stopAudioDevice(session: OpenSession) {
        val audio = session.audio
        if (audio != null) audio.stopDevice() else session.sink?.stop()
    }

    private suspend fun releaseSession(session: OpenSession) {
        // Once detached, this is the only remaining owner of the graph. Cancellation and the close
        // reporting budget may no longer skip any release below, otherwise the detached decoder or
        // backend session becomes unreachable. A wedged native close may therefore outlive the budget;
        // that is preferable to returning while a worker can still touch freed native state.
        withContext(NonCancellable) {
            session.schedulerMode.value = SCHEDULER_IDLE
            // Every step still runs even when an earlier one failed, which is why each is wrapped.
            // What changed is that the failures are COLLECTED rather than dropped: a decoder or a
            // device that refused to close used to leave no trace anywhere.
            val releaseFailures = mutableListOf<String>()
            suspend fun release(what: String, block: suspend () -> Unit) {
                try {
                    block()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    releaseFailures += "$what: ${failure.message ?: failure::class.simpleName}"
                }
            }
            // Owner report 2026-08-26: the platform renderer is SHARED across
            // rebuilds while the "did I publish" key is per-session, so an overlay outlived the
            // session that published it: after a track change, the fresh session's empty key said
            // "nothing to clear" and the old text stayed on the glass for ever, which read as
            // "disable subtitles does nothing". A dying session therefore withdraws its own cues.
            // The withdrawal goes through the ledger like every close below it: a renderer that
            // throws while it withdraws, one that lost its target for instance, used to skip every
            // release after it, so the workers, decoders, queues and source of the session were
            // never closed and close still reported success (#472).
            session.typeset?.let { lane ->
                lane.epoch.incrementAndGet()
                lane.job?.let { job -> job.cancel(); runCatching { job.join() } }
            }
            // A raster job that could not be retired may have published, so it counts as published.
            var rasterPublished = true
            release("subtitle raster") { rasterPublished = retireRasterJob(session) }
            if (session.publishedCueKey != null || session.typeset?.published?.value == true || rasterPublished) {
                release("subtitle overlay withdrawal") {
                    session.renderer.setOverlay(
                        SubtitleOverlay(
                            images = emptyList(),
                            viewportWidth = session.publishedCanvas?.first ?: DEFAULT_SUBTITLE_CANVAS_WIDTH,
                            viewportHeight = session.publishedCanvas?.second ?: DEFAULT_SUBTITLE_CANVAS_HEIGHT,
                            contentHash = session.overlayGeneration.incrementAndGet(),
                        ),
                    )
                }
            }
            if (session.ownsAudio) release("audio device stop") { stopAudioDevice(session) }
            // A lane blocked inside an uncancellable native read would
            // make the quiesce below burn its whole deadline and the joins after it wait for
            // ever. The session is ending and the source is about to close, so aborting whatever
            // it is doing costs nothing. Best effort: a source that cannot interrupt returns
            // false and the old ordering stands.
            runCatching { session.source.interrupt() }
            session.workers.forEach { worker -> runCatching { worker.quiesce(QUIESCE_DEADLINE) } }
            session.jobs.forEach { it.cancel() }
            session.jobs.forEach { runCatching { it.join() } }
            // The demux worker is joined, so no packet of the recording is mid-write, and the file
            // is finished here, inside whatever deadline the release runs under (#473).
            release("recording") { endRecording(session, session.recordingEnd) }
            session.typeset?.let { lane ->
                session.typeset = null
                release("subtitle typesetter") { withContext(dispatchers.raster) { lane.close() } }
            }
            // Direct hardware frames retain codec output slots. Release the playback queue while the
            // codec owner is still alive; closing MediaCodec first invalidates queued frame handles.
            release("video output") { session.video?.close() }
            session.videoDecoder?.let { decoder ->
                release("video decoder") { withContext(dispatchers.videoDecode) { decoder.close() } }
            }
            session.audioDecoder?.let { decoder ->
                release("audio decoder") { withContext(dispatchers.audioDecode) { decoder.close() } }
            }
            while (true) {
                val buffer = session.decodedAudio.tryReceive().getOrNull() ?: break
                runCatching { buffer.close() }
                session.audioInFlight.decrementAndGet()
            }
            release("video queue") { session.videoQueue?.close() }
            // The pictures the demux lane reads beside the one that plays hold packets too (#527).
            session.pictureQueues.values.forEach { queue ->
                if (queue !== session.videoQueue) release("picture cache ${queue.streamIndex}") { queue.close() }
            }
            session.audioQueues.values.forEach { queue ->
                release("audio queue ${queue.streamIndex}") { queue.close() }
            }
            // Subtitles are resources like the other two paths: the decoder holds backend state and
            // the queue holds owned packets, and skipping them here leaked both.
            release("subtitle decoder") { session.subtitleDecoder?.close() }
            release("secondary subtitle decoder") { session.subtitle2Decoder?.close() }
            session.subtitleQueues.values.forEach { queue ->
                release("subtitle queue ${queue.streamIndex}") { queue.close() }
            }
            // A packet a decoder refused is held off its queue for the next pass, so closing the
            // queues misses it. The secondary track holds one too, which used to be left open (#482).
            runCatching { session.pendingSubtitlePacket?.close() }
            session.pendingSubtitlePacket = null
            runCatching { session.pendingSubtitle2Packet?.close() }
            session.pendingSubtitle2Packet = null
            // Closes the sink too: the audio path owns the device it was given. A gapless handoff
            // gave both to the next item, which closes them.
            if (session.ownsAudio) release("audio playback") { session.audio?.close() }
            release("backend session") { session.backendSession.close() }
            if (releaseFailures.isNotEmpty()) {
                warn(PlaybackWarning.ResourcesNotReleased(releaseFailures.joinToString("; ")))
            }
        }
    }

    private fun fail(error: PlaybackError) {
        lastError = error
        emitEvent(PlayerEvent.Failed(error))
        setStatus(PlaybackStatus.Failed)
    }

    /**
     * Turns a failure from an open into a typed error, using the stage the open had reached.
     *
     * Everything that was not already typed used to become "source unavailable", whatever had
     * actually broken: a refusing audio device, a renderer, or a plain bug in assembly all told the
     * caller the file could not be read. That invites a pointless retry and hides the subsystem
     * that failed. Only a failure while the source itself was being opened is a
     * source failure now; the rest name their stage and keep their cause.
     */
    private fun classify(failure: Throwable, item: MediaItem): PlaybackError = when {
        failure is PlaybackException -> failure.error
        openStage == OpenStage.Source ->
            PlaybackError.SourceUnavailable(item.uri, failure, failure.message)
        // The subsystem is named in the detail and the original throwable survives, which a
        // support bundle needs.
        openStage == OpenStage.Output -> PlaybackError.Internal(
            "the audio output could not be built for ${redactUri(item.uri)}: ${failure.message?.let(::redactUrisIn)}",
            failure,
        )
        else -> PlaybackError.Internal(
            "the playback session failed while ${openStage.describe()} for ${redactUri(item.uri)}: " +
                "${failure.message?.let(::redactUrisIn)}",
            failure,
        )
    }

    private suspend fun handleWorkerOutcome(outcome: WorkerOutcome) {
        val cause = outcome.cause ?: return
        if (cause is CancellationException) return
        if (pendingNext?.prepared?.session?.token == outcome.sessionToken) {
            dropPending("the ${outcome.name} worker of the next item failed${causeDetail(cause)}")
            return
        }
        val session = session ?: return
        if (outcome.sessionToken != session.token) return
        val recovery = if (outcome.name == VIDEO_DECODE_WORKER) videoRecoveryFor(session, cause) else null
        if (recovery != null) {
            videoRecoveryAttempted = true
            forceBackendSoftwareForMedia = true
            setStatus(PlaybackStatus.Buffering)
            // Fences and closes every queued direct frame before the replacement session exists.
            teardownSession()
            pendingVideoRecovery = recovery
            return
        }
        val error = when {
            outcome.name == DEMUX_WORKER -> (cause as? PlaybackException)?.error ?: PlaybackError.SourceUnavailable(
                media?.uri ?: "", cause, "the demuxer failed: ${cause.message}",
            )
            outcome.name == VIDEO_DECODE_WORKER -> (cause as? PlaybackException)?.error
                ?: PlaybackError.DecoderFailed(
                    session.videoStream?.codec ?: "video", cause.message ?: cause.toString(), cause,
                )
            outcome.name == AUDIO_DECODE_WORKER -> (cause as? PlaybackException)?.error
                ?: PlaybackError.DecoderFailed(
                    session.audioStream?.codec ?: "audio", cause.message ?: cause.toString(), cause,
                )
            outcome.name == AUDIO_FEED_WORKER || outcome.name == AUDIO_DEVICE ->
                audioFeedError(session, cause) { "the ${outcome.name} worker failed" }
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
        recordTransition(status, next)
        status = next
        // A new attempt replaces the old failure rather than leaving two truths on the snapshot.
        if (next == PlaybackStatus.Opening) lastError = null
        // Published here and not only at the end of the pass. A handler that then spends a second inside
        // a slow backend would otherwise leave every observer reading the status the player has left.
        publishSnapshot()
    }

    /**
     * The dirty flag: the per-pass handler used to allocate a full snapshot EVERY pass,
     * quiet or not. Snapshot content only moves through commands, worker outcomes, status
     * transitions and the explicit publication sites, and every one of those marks or calls
     * directly; a quiet pass allocates nothing. Progress and stats keep their own intervals
     * below and publish regardless, because position moves without commands.
     */
    private var snapshotDirty = true

    private fun publishSnapshotIfDirty() {
        if (snapshotDirty) publishSnapshot()
        // The progress and stats intervals live inside publishSnapshot; run them even on a
        // quiet pass without paying the snapshot allocation.
        else publishProgressAndStats()
    }

    private fun publishSnapshot() {
        snapshotDirty = false
        val session = session
        // Published from the actor for the renderer's event lane to read; see renderedStreamIndex.
        renderedStreamIndex.value = session?.videoStream?.index ?: -1
        val now = clock.nanos()
        snapshotState.value = PlayerSnapshot(
            status = status,
            media = media,
            duration = session?.let { publishedDuration(it) },
            durationIsEstimate = session?.let { durationStillEstimated(it) } ?: false,
            seekable = session?.source?.seekable ?: false,
            videoSize = session?.videoStream?.visibleVideoSize,
            tracks = tracks,
            chapters = session?.source?.chapters ?: emptyList(),
            metadata = session?.source?.metadata ?: emptyMap(),
            speed = speed,
            volume = volume,
            /* Read from the live sink every publish rather than cached at open: it becomes null
             * when the device closes, and an effect attached to a closed session is inert. The
             * teardown projection above leaves it at its null default for the same reason. */
            audioSessionId = session?.audio?.platformSessionId,
            balance = balance,
            videoEnabled = videoEnabled,
            equalizer = equalizer,
            sleepTimer = sleepTimer,
            /* Reported as APPLIED, in dB, so a gain the peak clamp reduced shows the reduced
             * figure. Null when nothing is applied at all, which a plain 1.0 could not say: a file
             * measured as needing no change publishes 0 dB, not nothing. */
            appliedReplayGainDb = session?.audio?.replayGain
                ?.takeIf { config.audio.replayGain != ReplayGainMode.Off }
                ?.let { (20.0 * log10(it.toDouble())).toFloat() },
            muted = muted,
            loop = loop,
            videoScale = videoScale,
            videoAdjustments = videoAdjustments,
            renderQuality = renderQuality,
            hdrPolicy = hdrPolicy,
            videoDynamicRange = videoDynamicRange,
            videoTransform = videoTransform,
            subtitleDelay = subtitleDelay,
            subtitleScale = subtitleScale,
            subtitleStyle = subtitleStyle,
            subtitlePosition = subtitlePosition,
            subtitleTypesetter = session?.typeset?.providerId,
            audioDelay = audioDelay,
            abLoopA = abLoopA,
            abLoopB = abLoopB,
            preservePitch = preservePitch,
            error = lastError,
            generation = requestedEpoch,
            queue = queueItems,
            queueIndex = queueIndex,
            shuffle = shuffleEnabled,
            queueOrder = queueOrder,
            failedQueueItems = failedQueueIndices,
            markers = markers,
            playRequested = publishedPlayIntent(),
            preloadedIndex = pendingNext?.takeIf { it.prepared != null && it.index >= 0 }?.index,
        )
        publishProgressAndStats()
    }

    /** Publishes position, host time, effective rate and identity as one atomic audio mapping. */
    private fun publishAudioClock(session: OpenSession?, now: Long) {
        val audio = session?.audio?.takeIf { session.audioLane != null }
        if (audio == null || pendingSeek != null || seekPhase.isRunning ||
            (status != PlaybackStatus.Playing && status != PlaybackStatus.Paused)
        ) {
            publishedAudioClock.value = AudioClockSnapshot.unavailable(audioGeneration, now)
            return
        }
        val reading = audio.clockSnapshot()
        publishedAudioClock.value = AudioClockSnapshot(
            position = reading.pts,
            hostTimeNanos = reading.hostTimeNanos,
            rate = if (reading.paused) 0.0 else reading.speed,
            generation = Generation(session.audioGeneration.value),
            quality = audio.latencyQuality,
        )
    }

    /**
     * The interval-gated halves, shared by dirty and quiet passes.
     *
     * [force] publishes regardless of the intervals, which is what a stop needs: the session is
     * gone, and leaving the last playing session's queue depths and drift on the flow describes
     * media that is no longer open. The audible mapping is refreshed on every pass.
     */
    private fun publishProgressAndStats(force: Boolean = false) {
        val session = session
        val now = clock.nanos()
        publishAudioClock(session, now)
        if (force || (now - lastProgressAtNanos).nanoseconds >= config.progressInterval) {
            lastProgressAtNanos = now
            if (session != null) noteFurthestPosition(session)
            progressState.value = Progress(
                // The masked read, deliberately: the progress flow feeds the same seek bars that
                // poll position(), and the two must never disagree about which timeline is current.
                position = position(),
                bufferedAhead = bufferedAhead(session),
                bufferedRanges = bufferedRanges(session),
            )
        }
        if (force || (now - lastStatsAtNanos).nanoseconds >= config.statsInterval) {
            val elapsed = (now - lastStatsAtNanos).nanoseconds
            lastStatsAtNanos = now
            val decoded = retiredDecodedVideo + (session?.decodedVideoFrames?.value ?: 0)
            // The total is monotonic by construction now that retired sessions are folded in, so
            // the difference cannot go negative. The coercion stays as a floor rather than as the
            // fix it used to be: a negative frames-per-second against a monotonic-total contract
            // is the kind of thing worth being defended against twice.
            val delta = (decoded - lastStatsDecoded).coerceAtLeast(0L)
            val fps = if (elapsed > Duration.ZERO) {
                delta * 1_000.0 / elapsed.inWholeMilliseconds.coerceAtLeast(1)
            } else {
                0.0
            }
            lastStatsDecoded = decoded
            // The audit found these two documented warnings wired to nothing. Both read
            // the player-level total now, so the rising edge is a real onset rather than the
            // silent re-baselining a per-session counter produced at every reopen.
            val underrunsNow = retiredUnderruns + (session?.audio?.underruns ?: 0)
            if (underrunsNow > lastStatsUnderruns) {
                warn(PlaybackWarning.AudioUnderrun(underrunsNow))
                // Seen by this pass, not at the moment the device ran dry: the device thread runs
                // no managed code, so the instant is as late as one stats interval.
                if (KiteTrace.enabled) {
                    KiteTrace.instant("audio", "underrun", clock.nanos(), mapOf("total" to underrunsNow.toString()))
                }
            }
            lastStatsUnderruns = underrunsNow
            val droppedLateNow = retiredDroppedLate + (session?.video?.droppedFrames ?: 0)
            val droppedDelta = (droppedLateNow - lastStatsDroppedLate).coerceAtLeast(0)
            if (droppedDelta >= FRAME_DROP_WARN_PER_INTERVAL) {
                warn(PlaybackWarning.FrameDropping(droppedDelta.toInt()))
            }
            lastStatsDroppedLate = droppedLateNow

            // Bytes over the wire. The rate is a difference between two samples divided by the
            // interval the sampler actually runs on, so a late tick reports a lower rate rather
            // than a spike, which is the honest way round for a figure used to explain a rebuffer.
            val ioBytesNow = retiredIoBytes + ioBytesOf(session)
            val ioDelta = (ioBytesNow - lastStatsIoBytes).coerceAtLeast(0)
            lastStatsIoBytes = ioBytesNow
            val ioPerSecond = if (config.statsInterval > Duration.ZERO) {
                ioDelta * 1000L / config.statsInterval.inWholeMilliseconds.coerceAtLeast(1)
            } else {
                0L
            }

            statsState.value = PlaybackStats(
                ioBytesTotal = ioBytesNow,
                ioBytesPerSecond = ioPerSecond,
                decodedVideoFrames = decoded,
                submittedFrames = retiredSubmitted + (session?.renderer?.submittedFrames ?: 0),
                headlessFrames = retiredHeadless + (session?.renderer?.headlessFrames ?: 0),
                droppedFramesLate = droppedLateNow,
                droppedFramesDecode = retiredDroppedDecode +
                    (session?.droppedVideoBeforeDecode?.value ?: 0),
                refusedFrames = retiredRefused + (session?.video?.refusedFrames ?: 0),
                repeatedFrames = retiredRepeated + (session?.video?.repeatedFrames ?: 0),
                audioUnderruns = underrunsNow,
                audioLimitedFrames = retiredLimited + (session?.audio?.limitedFrames ?: 0),
                droppedEvents = droppedEvents.value,
                rebuffers = rebuffers,
                avDrift = (session?.driftUs?.value ?: 0L).microseconds,
                videoDecodeFps = fps,
                videoQueueDepth = session?.video?.buffered ?: Duration.ZERO,
                audioQueueDepth = session?.audio?.buffered ?: Duration.ZERO,
                audioLatency = (session?.audio?.latencyNanos ?: 0L).nanoseconds,
                audioLatencyQuality = session?.audio?.latencyQuality ?: LatencyQuality.Unreliable,
                hardwareDecode = session?.videoDecoder?.hardware ?: HwdecStatus.Software,
                decodeTimeP50 = (session?.decodeTimes?.p(0.5) ?: 0L).nanoseconds,
                decodeTimeP95 = (session?.decodeTimes?.p(0.95) ?: 0L).nanoseconds,
                presentLatenessP95 = (session?.video?.presentLatenessP95Nanos() ?: 0L).nanoseconds,
                containerBitrate = session?.source?.containerBitrateBps,
                syncMode = config.syncMode,
                masterClock = masterClockKind(session),
            )
        }
    }

    /**
     * The byte cache window as a time range, byte-to-time mapped PROPORTIONALLY (byte fraction
     * times duration). Exact for constant bitrate, approximate for variable, honest about both
     * in the Progress KDoc; empty whenever size or duration is unknown, no cache is running, or
     * the media is read from other addresses than the cached one.
     */
    private fun bufferedRanges(session: OpenSession?): List<ClosedRange<Duration>> {
        val cache = session?.cachingIo ?: return emptyList()
        // Media read in parts from other addresses, such as an HLS stream: the cached reader holds
        // the playlist, whose bytes say nothing about the timeline.
        if ((session.relatedTraffic?.opens?.value ?: 0) > 0) return emptyList()
        val sizeBytes = cache.size ?: return emptyList()
        if (sizeBytes <= 0L) return emptyList()
        val durationUs = session.source.duration?.micros ?: return emptyList()
        if (durationUs <= 0L) return emptyList()
        val window = cache.window.value
        val start = window.start
        val end = window.end.coerceAtMost(sizeBytes)
        if (end <= start) return emptyList()
        fun toTime(byte: Long): Duration = (byte.toDouble() / sizeBytes * durationUs).toLong().microseconds
        return listOf(toTime(start)..toTime(end))
    }

    private fun bufferedAhead(session: OpenSession?): Duration {
        if (session == null) return Duration.ZERO
        val queues = session.selectedQueues()
        if (queues.isEmpty()) return Duration.ZERO
        return queues.minOf { it.bufferedUs }.microseconds
    }

    private fun masterClockKind(session: OpenSession?): MasterClock = when {
        session == null -> MasterClock.None
        session.audioLane != null && config.syncMode != SyncMode.VideoMaster -> MasterClock.Audio
        session.video != null -> MasterClock.Video
        else -> MasterClock.None
    }

    /**
     * Every event leaves through here, and a loss is counted instead of ignored.
     *
     * `tryEmit` answers false when the buffer is full, which is what a collector slower than the
     * session produces. Every call site used to discard that answer, so a lost `SeekCompleted` or
     * `Ended` was indistinguishable from one that was never emitted. The count is published as
     * [PlaybackStats.droppedEvents], a monotonic total a consumer can diff, and it is in the
     * diagnostics dump.
     *
     * The other way an event reaches nobody is not a defect and is not detectable: this flow
     * replays nothing, so one emitted while nobody is collecting is delivered to nobody and
     * `tryEmit` still says true. That is the documented split between state and occurrences, and
     * it is why every warning is ALSO written to the bounded history, which a late reader can read.
     */
    private fun emitEvent(event: PlayerEvent) {
        // An unlimited queue refuses only once its collector has gone, and then it is being removed.
        for (tap in eventTaps.value) tap.trySend(event)
        if (!eventSink.tryEmit(event)) droppedEvents.incrementAndGet()
    }

    /** Events the buffer could not take. Published as a stat and printed in the dump. */
    private val droppedEvents = atomic(0L)

    private fun warn(warning: PlaybackWarning) {
        // The bounded history first: the event feed replays nothing to a late collector,
        // and a bug report is exactly a late collector, so the record cannot live only there.
        kotlinx.atomicfu.locks.synchronized(warningFence) {
            warningLog.addLast(TimedWarning(clock.nanos(), warning))
            while (warningLog.size > WARNING_HISTORY_LIMIT) warningLog.removeFirst()
        }
        io.github.yuroyami.kiteplayer.KiteLog.log("kiteplayer", warning.message, warning.fields)
        emitEvent(PlayerEvent.Warning(warning))
    }

    /** Records a warning the facade decided on, in the same history and event feed. */
    fun reportWarning(warning: PlaybackWarning) = warn(warning)

    /** Warnings this core emitted, oldest first, capped at [WARNING_HISTORY_LIMIT]. */
    fun warningHistory(): List<TimedWarning> =
        kotlinx.atomicfu.locks.synchronized(warningFence) { warningLog.toList() }

    private val warningFence = kotlinx.atomicfu.locks.SynchronizedObject()
    private val warningLog = ArrayDeque<TimedWarning>()

    /**
     * Everything a bug report needs, in one string: the resolved
     * configuration, the backends by name, the tracks and selections, the three published
     * snapshots, the KD artifacts attached to the session, and the warning history. Reads only
     * published state, so it is safe from any thread at any moment, including after failure,
     * which is when it is usually called.
     */
    fun diagnosticsDump(redactPaths: Boolean = false): String = buildString {
        fun path(uri: String): String = if (redactPaths) redactUri(uri) else uri
        // Free text quotes the URI it failed on, so redacting only the path lines left the
        // token in the bundle one line further down.
        fun text(value: String): String = if (redactPaths) redactUrisIn(value) else value
        val snapshot = snapshotState.value
        val liveStats = statsState.value
        val liveProgress = progressState.value
        appendLine("KitePlayer diagnostics")
        appendLine("status      ${snapshot.status}")
        appendLine("media       ${snapshot.media?.uri?.let(::path) ?: "none"}")
        snapshot.media?.openOptions?.takeIf { it.isNotEmpty() }?.let { options ->
            // Values are withheld under redaction and keys are not: `headers` routinely carries an
            // Authorization line, and a bundle still has to show WHICH options were set.
            appendLine(
                if (redactPaths) {
                    "openOptions ${options.keys.sorted()} (values withheld; " +
                        "unconsumed keys warn typed at open)"
                } else {
                    "openOptions $options (unconsumed keys warn typed at open)"
                },
            )
        }
        if (snapshot.queue.isNotEmpty()) {
            appendLine(
                "queue       ${snapshot.queueIndex + 1} of ${snapshot.queue.size}: " +
                    snapshot.queue.joinToString { path(it.uri) },
            )
        }
        appendLine("duration    ${snapshot.duration ?: "unknown"}")
        appendLine("seekable    ${snapshot.seekable}")
        appendLine("position    ${liveProgress.position} (buffered ahead ${liveProgress.bufferedAhead})")
        appendLine("error       ${snapshot.error?.message?.let(::text) ?: "none"}")
        appendLine()
        appendLine("config")
        appendLine("  backend           ${config.backends.backend?.describeForDiagnostics() ?: "none"}")
        appendLine("  output            ${config.backends.output?.let { it::class.simpleName } ?: "none"}")
        appendLine("  hardwareDecode    ${config.hardwareDecode}")
        appendLine("  frameDrop         ${config.frameDrop}")
        appendLine("  syncMode          ${config.syncMode}")
        appendLine("  buffer            ready=${config.buffer.readyDuration}/${config.buffer.readyPackets}p " +
            "soft=${config.buffer.softTarget} frames=${config.buffer.videoFrameQueue}")
        appendLine("  intervals         progress=${config.progressInterval} stats=${config.statsInterval}")
        appendLine("  speed=${snapshot.speed} volume=${snapshot.volume} muted=${snapshot.muted} loop=${snapshot.loop}")
        appendLine()
        appendLine("tracks")
        snapshot.tracks.all.forEach { track ->
            val selected = track.id == snapshot.tracks.selectedVideo ||
                track.id == snapshot.tracks.selectedAudio ||
                track.id == snapshot.tracks.selectedSubtitle
            appendLine("  ${if (selected) "*" else " "} ${track.id} ${track.kind} ${track.codec}" +
                (track.language?.let { " lang=$it" } ?: ""))
        }
        appendLine()
        appendLine("stats")
        appendLine("  decoded=${liveStats.decodedVideoFrames} submitted=${liveStats.submittedFrames} " +
            "headless=${liveStats.headlessFrames} droppedLate=${liveStats.droppedFramesLate} " +
            "refused=${liveStats.refusedFrames} repeated=${liveStats.repeatedFrames}")
        appendLine("  underruns=${liveStats.audioUnderruns} limited=${liveStats.audioLimitedFrames} " +
            "rebuffers=${liveStats.rebuffers} avDrift=${liveStats.avDrift} master=${liveStats.masterClock} hwdec=${liveStats.hardwareDecode}")
        // Anything but zero means this session's event feed is not a complete record, which a bug
        // report that reasons from the events needs to know before it reasons.
        appendLine("  eventsDropped=${liveStats.droppedEvents} (a full buffer: the collector was slower " +
            "than the session)")
        // Actor liveness: passes stop counting when the loop is stuck in a long step.
        appendLine("  actor passes=$loopPasses $debugState")
        // What subtitles were actually drawn at. A canvas smaller than the surface means the text
        // is being stretched, which is the difference between crisp lettering and soft lettering.
        appendLine("  subtitles: surface=" + (session?.renderer?.outputSize?.let { "${it.width}x${it.height}" } ?: "unreported") +
            " canvas=" + (session?.publishedCanvas?.let { "${it.first}x${it.second}" } ?: "video-sized"))
        appendLine()
        appendLine("kd artifacts")
        // The filter the media item actually carries. This line used to say "none attached"
        // unconditionally, so a support bundle from a session running a filter graph denied that
        // the graph existed, which is the one fact such a bundle is collected to establish
        // Typed filter plans are still roadmap work; a raw graph string is what
        // can be attached today, and it is what is reported.
        @OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)
        val attachedFilter = snapshot.media?.videoFilter
        @OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)
        val attachedAudioFilter = snapshot.media?.audioFilter
        appendLine(
            when {
                attachedFilter == null && attachedAudioFilter == null -> "  filters: none attached"
                attachedAudioFilter == null -> "  filters: video graph attached: ${text(attachedFilter!!)}"
                attachedFilter == null -> "  filters: audio graph attached: ${text(attachedAudioFilter)}"
                else -> "  filters: video graph attached: ${text(attachedFilter)}; " +
                    "audio graph attached: ${text(attachedAudioFilter)}"
            },
        )
        appendLine()
        appendLine("warnings (${warningHistory().size} kept, cap $WARNING_HISTORY_LIMIT)")
        warningHistory().forEach { entry ->
            appendLine("  [${entry.atNanos}] ${text(entry.warning.message)}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State reads shared by the handlers.
    // ---------------------------------------------------------------------------------------------

    private fun currentPosition(): Pts {
        val session = session ?: return Pts(publishedPositionMicros.value)
        // Paused after a frame step, the clocks still read where playback stopped.
        if (session.pictureHoldsPosition) session.video?.shownPts()?.let { return it }
        // The same selector scheduling uses decides whose reading IS the position: under
        // VideoMaster the picture carries the timeline, and preferring audio here anyway made
        // position, relative seeks and subtitles follow a clock scheduling ignores.
        // The other side is still the fallback, because a master that has produced no reading yet
        // beats a stale published number.
        val audioReading = session.audio?.takeIf { session.audioLane != null }?.position()
        val videoReading = session.lastVideoPtsUs.value.takeIf { it != NO_POSITION }?.let(::Pts)
        val position = when (masterClockKind(session)) {
            MasterClock.Video -> videoReading ?: audioReading
            else -> audioReading ?: videoReading
        }
        return position ?: Pts(publishedPositionMicros.value)
    }

    private fun everySelectedStreamReady(session: OpenSession): Boolean {
        val readyUs = config.buffer.readyDuration.inWholeMicroseconds
        if (!session.selectedQueues().all { it.isReady(readyUs, config.buffer.readyPackets) }) return false
        // Decoded output too: a queue full of compressed packets proves nothing about a decoder
        // that is producing nothing, and declaring readiness on packets alone started playback
        // into an underrun or a blank first frame. A stream that already ended is
        // exempt, because no more output can ever arrive for it.
        val video = session.video
        val videoReady = video == null ||
            session.videoParked.value ||
            video.queuedFrames > 0 ||
            session.videoQueue?.isEndOfStream == true
        val audio = session.audio
        val audioReady = session.audioLane == null ||
            audio?.buffered?.let { it > Duration.ZERO } == true ||
            session.audioQueue?.isEndOfStream == true
        return videoReady && audioReady
    }

    private fun demuxerRanShort(session: OpenSession): Boolean =
        session.selectedQueues().any { it.count == 0 && !it.isEndOfStream }

    private fun wellBuffered(session: OpenSession): Boolean =
        session.selectedQueues().all { it.isWellBuffered }

    private fun outputStarved(session: OpenSession): Boolean {
        val video = session.video
        return when {
            session.audioLane != null -> (session.audio?.buffered ?: Duration.ZERO) <= Duration.ZERO
            video != null -> video.queuedFrames == 0
            else -> false
        }
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
        tapsDiscontinuous(session)
        val epoch = requestedEpoch
        if (session.videoQueue != null && session.videoDecoder != null && session.video != null) {
            session.videoDecodeWorker = Worker(VIDEO_DECODE_WORKER)
            session.videoScheduler = Worker(VIDEO_SCHEDULE_WORKER)
        }
        // Keep the lanes alive even when audio starts deselected. They idle on a null routing
        // snapshot and can be released onto a newly installed lane without creating a session.
        if (session.audioQueues.isNotEmpty()) {
            session.audioDecodeWorker = Worker(AUDIO_DECODE_WORKER)
            session.audioFeedWorker = Worker(AUDIO_FEED_WORKER)
        }
        session.demuxWorker = Worker(DEMUX_WORKER)
        session.workers.forEach { it.release(epoch) }

        session.demuxWorker?.let { worker ->
            session.jobs += launchWorker(session, worker, dispatchers.demux) { runDemux(session, worker) }
        }
        session.videoDecodeWorker?.let { worker ->
            session.jobs += launchWorker(session, worker, dispatchers.videoDecode) { runVideoDecode(session, worker) }
        }
        session.audioDecodeWorker?.let { worker ->
            session.jobs += launchWorker(session, worker, dispatchers.audioDecode) { runAudioDecode(session, worker) }
        }
        session.audioFeedWorker?.let { worker ->
            session.jobs += launchWorker(session, worker, dispatchers.audioFeed) { runAudioFeed(session, worker) }
        }
        session.videoScheduler?.let { worker ->
            session.jobs += launchWorker(session, worker, dispatchers.videoSchedule) { runVideoSchedule(session, worker) }
        }
        // The sink's device events finally reach a listener. warn() is fence-locked,
        // so collecting on the session lane is safe from wherever the sink emits.
        startAudioEventCollector(session)
    }

    private fun startAudioEventCollector(session: OpenSession) {
        if (session.audioEventJob != null) return
        val audio = session.audio ?: return
        val job = scope.launch(dispatchers.session) {
            audio.events.collect { event ->
                when (event) {
                    is io.github.yuroyami.kiteplayer.spi.AudioSinkEvent.DeviceLost ->
                        warn(PlaybackWarning.AudioDeviceChanged("device lost: " + event.detail))
                    is io.github.yuroyami.kiteplayer.spi.AudioSinkEvent.DeviceChanged ->
                        warn(PlaybackWarning.AudioDeviceChanged(event.detail))
                    // The sink was already reporting these and the engine threw
                    // them away. An underrun warns once per session; a format request is a
                    // device condition the caller must hear about even though the engine cannot
                    // renegotiate yet.
                    is io.github.yuroyami.kiteplayer.spi.AudioSinkEvent.Underrun -> {
                        if (!session.warnedAboutDeviceUnderrun) {
                            session.warnedAboutDeviceUnderrun = true
                            warn(PlaybackWarning.AudioDeviceUnderrun(event.detail))
                        }
                    }
                    is io.github.yuroyami.kiteplayer.spi.AudioSinkEvent.FormatChangeRequested ->
                        warn(
                            PlaybackWarning.AudioDeviceChanged(
                                "the device requested a format change: " + event.detail,
                            ),
                        )
                    // The actor stops the session, the same way it handles a dead worker. This lane
                    // may not touch the session itself.
                    is io.github.yuroyami.kiteplayer.spi.AudioSinkEvent.Failed -> {
                        val outcome = WorkerOutcome(session.token, AUDIO_DEVICE, PlaybackException(event.error))
                        session.firstWorkerOutcome.compareAndSet(null, outcome)
                        outcomes.trySend(outcome)
                    }
                }
            }
        }
        session.audioEventJob = job
        session.jobs += job
    }

    /**
     * Launches one worker so that every way it can end arrives on one channel.
     *
     * A crash becomes a message the actor handles rather than a coroutine that vanishes, which is the
     * difference between a typed failure and a player that hangs with no explanation.
     */
    private fun launchWorker(
        session: OpenSession,
        worker: Worker,
        context: CoroutineContext,
        body: suspend () -> Unit,
    ): Job =
        scope.launch(context) {
            try {
                body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                val outcome = WorkerOutcome(session.token, worker.name, failure)
                session.firstWorkerOutcome.compareAndSet(null, outcome)
                outcomes.trySend(outcome)
            } finally {
                worker.markFinished()
            }
        }

    /** Reads packets and hands them to the per-stream queues, stalling when the total is over budget. */
    private suspend fun runDemux(session: OpenSession, worker: Worker) {
        val late = LateStreams(session.readStreams)
        try {
            demuxLoop(session, worker, late)
        } finally {
            late.close()
        }
    }

    private suspend fun demuxLoop(session: OpenSession, worker: Worker, late: LateStreams) {
        var epoch = worker.epoch
        var restarts = worker.releases
        var ended = false
        val queueOf = { index: Int ->
            session.audioQueues[index] ?: session.subtitleQueues[index] ?: session.pictureQueues[index]
        }
        while (true) {
            worker.checkpoint()
            pruneInactiveSwitchCaches(session)
            // Every restart voids what this loop remembers, and a restart is not the same thing as an
            // epoch change: one seek can flush and restart the pipeline several times under one epoch.
            if (worker.releases != restarts) {
                restarts = worker.releases
                epoch = worker.epoch
                ended = false
                // A seek moved the reads, so the rate starts over from where they land.
                session.readRate.restart()
                late.dropHeld()
            }
            if (!late.idle) late.deliver(queueOf, epoch, ended)
            if (ended) {
                worker.nap(WORKER_POLL)
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
                    // No queue at all is a parked video-only item; the poll is the only wake-up then.
                    val drained = session.selectedQueues().firstOrNull()
                    worker.napUntil(WORKER_POLL) { drained?.awaitDrain() ?: kotlinx.coroutines.awaitCancellation() }
                }
                continue
            }
            // Marked for the stall timeout: the actor measures how long this read waits.
            session.stallWatch.begin()
            val readStartedNanos = clock.nanos()
            val packet = try {
                session.source.readPacket()
            } finally {
                session.stallWatch.end()
            }
            // The timeline is measured on one stream, the video when there is one.
            val measured = session.videoStream?.index ?: session.audioStream?.index
            session.readRate.read(
                clock.nanos() - readStartedNanos,
                packet?.takeIf { it.streamIndex == measured }?.pts?.micros,
            )
            if (packet == null) {
                // Held packets go ahead of the end, which a queue keeps after what it holds.
                if (!late.idle) late.deliver(queueOf, epoch, ended = true)
                session.allPacketQueues.forEach { it.signalEndOfStream(epoch) }
                ended = true
                continue
            }
            val listed = packet.newStreams
            val programs = packet.newPrograms
            if (listed != null || programs != null) announceLayout(session, late, listed, programs)
            when (packet.streamIndex) {
                session.videoStream?.index -> session.videoQueue?.offer(packet, epoch) ?: packet.close()
                else -> {
                    // A stream asked for since the open keeps its order behind what it already holds.
                    if (late.waits(packet.streamIndex)) {
                        late.hold(packet)
                    } else {
                        val queue = queueOf(packet.streamIndex)
                        if (queue == null) packet.close() else queue.offer(packet, epoch)
                    }
                }
            }
        }
    }

    /**
     * The demux lane's answer to a packet that says the source's streams or programmes changed
     * (#509). Every stream the lane does not read yet is asked for now, before the next read, because
     * the source skips a stream nobody asked for. The actor then makes their caches and updates the
     * tracks. A picture is read into a cache like a sound, so that one the player turns to shows at
     * the position rather than from wherever the reads had got to by then (#527).
     */
    private fun announceLayout(
        session: OpenSession,
        late: LateStreams,
        listed: List<PlayerStreamInfo>?,
        programs: List<MediaProgram>?,
    ) {
        val fresh = listed.orEmpty()
            .filter { it.index !in late.selection && !(it.kind == TrackKind.Video && it.isCoverArt) }
            .map { it.index }
        var reading = emptyList<Int>()
        if (fresh.isNotEmpty()) {
            try {
                session.source.selectStreams(late.selection + fresh)
                late.added(fresh)
                reading = fresh
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                fresh.forEach { index ->
                    warn(PlaybackWarning.TrackDeselected(TrackId(index), "the source would not read it${causeDetail(failure)}"))
                }
            }
        }
        commands.trySend(CoreCommand.StreamsChanged { adoptLayout(session, listed, programs, reading) })
    }

    /** Keeps alternate-track history near presentation time without making it backpressure video. */
    private fun pruneInactiveSwitchCaches(session: OpenSession) {
        // The published position is the playing item's, not this one's.
        if (session.preloading.value) return
        val positionUs = publishedPositionMicros.value
        val previous = session.lastSwitchCachePrunePositionUs
        if (previous != Long.MIN_VALUE && positionUs - previous < SWITCH_CACHE_PRUNE_STEP_US) return
        session.lastSwitchCachePrunePositionUs = positionUs

        val audioCutoff = positionUs - audioSwitchHistoryUs()
        val activeAudio = session.audioQueue
        session.audioQueues.values.forEach { queue ->
            if (queue !== activeAudio) {
                queue.dropBefore(audioCutoff, assumedDurationUs = AUDIO_PRUNE_ASSUMED_PACKET_DURATION_US, untimedEndsByNext = true)
            }
        }
        val subtitleCutoff = positionUs - CUE_PRUNE_BEHIND_MICROS
        val activeSubtitle = session.subtitleQueue
        session.subtitleQueues.values.forEach { queue ->
            if (queue !== activeSubtitle) {
                queue.dropBefore(subtitleCutoff, assumedDurationUs = CUE_PRUNE_BEHIND_MICROS)
            }
        }
        // A picture keeps only from the keyframe a switch at the position would start on (#527).
        val activePicture = session.videoQueue
        session.pictureQueues.values.forEach { queue ->
            if (queue !== activePicture) queue.dropBeforeKeyframe(positionUs)
        }
    }

    private fun audioSwitchHistoryUs(): Long = minOf(
        config.buffer.totalDuration.inWholeMicroseconds / 2,
        maxOf(config.buffer.readyDuration.inWholeMicroseconds, AUDIO_SWITCH_MIN_HISTORY_US),
    )

    /**
     * The read-ahead bound, across every stream at once.
     *
     * A per-stream limit that stalls the producer deadlocks on a badly interleaved file, so the decision
     * belongs where every stream is visible.
     */
    private fun overBudget(session: OpenSession): Boolean {
        val queues = session.selectedQueues()
        if (queues.isEmpty()) return false
        // Every alternate cache counts toward the BYTE cap: retained packets are real memory. Only
        // playback-carrying queues count toward duration, otherwise retained history looks like
        // forward read-ahead and can deadlock the demuxer.
        val bytes = session.allPacketQueues.sumOf { it.bytesBuffered }
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
        // Relief exists for the deadlock, not for routine budget pressure. While every selected
        // queue is ready, waiting for a drain is the correct answer, and cutting anything would
        // eat the switch caches of every healthily paused session sitting at its duration cap.
        // A selected queue held UNDER readiness by the budget is the deadlock: playback cannot
        // start, so nothing will ever drain, so the budget can never free itself.
        val readyUs = config.buffer.readyDuration.inWholeMicroseconds
        if (queues.none { !it.isReady(readyUs, config.buffer.readyPackets) }) return false
        // An inactive switch cache is sacrificed first. It counts against the same byte cap, and a
        // lane whose packets carry no timestamps cannot be pruned by position, so it can hold the
        // whole cap on its own. Cutting it cannot gap what is on screen; the only
        // cost is a later track switch refusing for insufficient coverage, and that refusal is
        // typed and told, so this needs no warning of its own.
        val inactiveHoarder = session.allPacketQueues
            .filter { queue -> queues.none { it === queue } }
            .maxByOrNull { it.bytesBuffered }
        // A picture cache goes whole: a cut in the middle of its run would leave every picture after
        // the cut built on ones that are gone, and the reads refill it from the next keyframe (#527).
        val keepBytes = when {
            inactiveHoarder == null -> 0L
            session.pictureQueues.values.any { it === inactiveHoarder } -> 0L
            else -> inactiveHoarder.bytesBuffered / 2
        }
        if (inactiveHoarder != null && inactiveHoarder.count > 0 && inactiveHoarder.dropFromTail(keepBytes) > 0) {
            return true
        }
        // Cutting the media being played needs true starvation, not mere unreadiness.
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
        var queue = session.videoQueue ?: return
        var decoder = session.videoDecoder ?: return
        val video = session.video ?: return
        var epoch = worker.epoch
        var restarts = worker.releases
        var ending = false
        // Set when a late packet was thrown away and cleared by the next keyframe; see skipToKeyframe.
        var skippingToKeyframe = false
        // What the decoder was last told to skip; see skipNonReferenceBeforeTarget. A flush leaves
        // the decoder's setting as it was, so this carries across one too.
        var skippingNonReference = false
        val held = HeldLanding()
        try {
            while (true) {
                // Every park ends in a restart, which drops the held frame, so it goes before the
                // park, while its decoder is alive: a picture that changes or goes off closes that
                // decoder while this lane is parked (#527, #529).
                worker.checkpoint { held.drop(session) }
                if (worker.releases != restarts) {
                    restarts = worker.releases
                    epoch = worker.epoch
                    // The flush cleared the decoder, so its drain signal has to be sent again.
                    ending = false
                    // A flush re-anchors on a keyframe by construction, so an unfinished skip from
                    // the old epoch must not eat the first packets of the new one.
                    skippingToKeyframe = false
                    // A frame held for a backward landing belongs to the timeline the flush ended.
                    held.drop(session)
                    // A picture turned off ended this lane while it was parked, and one turned on
                    // again later gets a lane of its own (#529).
                    if (session.videoDecodeWorker !== worker) return
                    // A picture that gave way to another changed the queue and the decoder while this
                    // lane was parked (#527). A new decoder skips nothing until it is told to.
                    queue = session.videoQueue ?: return
                    val current = session.videoDecoder ?: return
                    if (current !== decoder) skippingNonReference = false
                    decoder = current
                }
                if (drainFrames(session, worker, decoder, video, epoch, held)) continue
                if (queue.isEndOfStream && queue.count == 0) {
                    if (!ending) {
                        // The drain signal travels in band, as a null packet, exactly as libavcodec expects.
                        // The decoder contract lets send refuse while its output side is full, so the signal
                        // is only marked delivered when accepted; a refusal loops back through drainFrames
                        // and retries, otherwise the decoder never drains and end-of-file hangs forever.
                        if (videoDecoderSend(decoder, null)) ending = true
                        continue
                    }
                    // The stream ran out before any frame reached a backward target, so the frame held
                    // for it is the last one there is before it.
                    if (held.isHolding && decoder.isDrained) {
                        handOverHeld(session, worker, video, epoch, held)
                        continue
                    }
                    // The last frames still leave the schedule, and each may owe its decoder a release.
                    worker.napUntil(WORKER_POLL) { video.awaitDeparture() }
                    continue
                }
                val packet = queue.poll()
                if (packet == null) {
                    // Nothing taken, so nothing can be lost: the wait is bounded and the poll above is what
                    // actually takes a packet.
                    worker.napUntil(WORKER_POLL) { awaitPacketOrDeparture(queue, video) }
                    continue
                }
                if (session.videoParked.value) {
                    // Discarded before the decoder, and counted as nothing: this is a decision, not a
                    // shortfall. The container keeps being read, so audio and subtitles are untouched
                    // and nothing has to be reopened when the lane comes back.
                    packet.close()
                    continue
                }
                if (session.videoWaitingForKeyframe.value) {
                    // Just un-parked. The decoder has been starved and the next packet is mid
                    // group-of-pictures, which decodes to nothing usable; wait for a frame it can
                    // start from.
                    if (!packet.isKeyframe) {
                        packet.close()
                        continue
                    }
                    session.videoWaitingForKeyframe.value = false
                }
                if (skipToKeyframe(session, packet, skippingToKeyframe)) {
                    skippingToKeyframe = true
                    session.droppedVideoBeforeDecode.incrementAndGet()
                    packet.close()
                    continue
                }
                skippingToKeyframe = false
                val skipNonReference = skipNonReferenceBeforeTarget(
                    packetPtsUs = packet.pts?.micros,
                    packetDurationUs = packet.duration?.micros,
                    discardBeforeUs = session.discardBeforeUs.value,
                    landsBeforeTarget = session.landsBeforeTarget.value,
                )
                if (skipNonReference != skippingNonReference) {
                    try {
                        videoDecoderSkip(decoder, skipNonReference)
                    } catch (failure: Throwable) {
                        packet.close()
                        throw failure
                    }
                    skippingNonReference = skipNonReference
                }
                try {
                    while (!videoDecoderSend(decoder, packet)) {
                        // False means the decoder did NOT take this packet. A synchronous codec usually
                        // has output immediately, but Android's asynchronous internals can transiently
                        // expose neither an input slot nor an output frame. Retry in bounded steps so
                        // that ordinary readiness is not fatal and a seek can still park this worker.
                        val frame = timedVideoReceive(session, decoder)
                        if (frame != null) {
                            session.decodedVideoFrames.incrementAndGet()
                            session.videoInFlight.incrementAndGet()
                            if (!handOver(session, worker, video, frame, epoch, held)) break
                        } else {
                            if (worker.quiesceRequested) break
                            worker.napUntil(handoverRetry(session)) { video.awaitDeparture() }
                        }
                    }
                } finally {
                    packet.close()
                }
            }
        } finally {
            // Closed on the worker that owns it, whether the session ends or the lane is torn down.
            held.drop(session)
        }
    }

    /**
     * Waits for the next video packet or for a frame to leave the schedule, whichever comes first.
     * A frame that leaves may owe its decoder a release, and only a call from this worker makes it.
     */
    private suspend fun awaitPacketOrDeparture(queue: PacketQueue, video: VideoPlayback) =
        kotlinx.coroutines.coroutineScope {
            val packet = async { queue.awaitData() }
            val departure = async { video.awaitDeparture() }
            select<Unit> {
                packet.onAwait { }
                departure.onAwait { }
            }
            packet.cancel()
            departure.cancel()
        }

    /** The pre-decode drop rule, with this session's numbers. See [skipVideoPacketBeforeDecode]. */
    private fun skipToKeyframe(
        session: OpenSession,
        packet: io.github.yuroyami.kiteplayer.spi.PlayerPacket,
        skipping: Boolean,
    ): Boolean = !session.preloading.value && skipVideoPacketBeforeDecode(
        policy = config.frameDrop,
        isKeyframe = packet.isKeyframe,
        packetPtsUs = packet.pts?.micros,
        positionUs = publishedPositionMicros.value,
        discardBeforeUs = session.discardBeforeUs.value,
        alreadySkipping = skipping,
        lateThresholdUs = LATE_BEFORE_DECODE_US,
    )

    /** Pulls whatever the decoder has ready. True when something came out. */
    private suspend fun drainFrames(
        session: OpenSession,
        worker: Worker,
        decoder: VideoDecoder,
        video: VideoPlayback,
        epoch: Generation,
        held: HeldLanding,
    ): Boolean {
        val frame = timedVideoReceive(session, decoder) ?: return false
        session.decodedVideoFrames.incrementAndGet()
        session.videoInFlight.incrementAndGet()
        handOver(session, worker, video, frame, epoch, held)
        return true
    }

    private suspend fun videoDecoderSend(
        decoder: VideoDecoder,
        packet: io.github.yuroyami.kiteplayer.spi.PlayerPacket?,
    ): Boolean = try {
        decoder.send(packet)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        throw VideoDecoderRuntimeFailure("send", failure)
    }

    private fun videoDecoderSkip(decoder: VideoDecoder, skip: Boolean) = try {
        decoder.skipNonReferenceFrames(skip)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        throw VideoDecoderRuntimeFailure("skip", failure)
    }

    /** [videoDecoderReceive], timed: a frame that came out is one sample of decode time. */
    private suspend fun timedVideoReceive(session: OpenSession, decoder: VideoDecoder): VideoFrame? {
        val startedNanos = clock.nanos()
        val frame = videoDecoderReceive(decoder)
        if (frame != null) {
            noteUnfittedCrop(session, frame)
            val endedNanos = clock.nanos()
            session.decodeTimes.add(endedNanos - startedNanos)
            if (KiteTrace.perFrame) {
                KiteTrace.span("video", "decode", startedNanos, endedNanos, mapOf("pts" to frame.pts.micros.toString()))
            }
        }
        return frame
    }

    /**
     * Warns once per session when the video stream's crop leaves nothing of a decoded picture.
     * Every decoder drops such a crop rather than hand it on, and only here are the stream's crop
     * and the frame's size side by side, so this one place speaks for every decoder (#497).
     */
    private fun noteUnfittedCrop(session: OpenSession, frame: VideoFrame) {
        val stream = session.videoStream ?: return
        val crop = stream.crop?.takeUnless { it.isEmpty } ?: return
        val size = frame.size
        if (crop.fits(size.width, size.height) || !session.cropIgnoredWarned.compareAndSet(false, true)) return
        warn(
            PlaybackWarning.CropIgnored(
                stream.index,
                "top ${crop.top}, bottom ${crop.bottom}, left ${crop.left} and right ${crop.right} " +
                    "leave nothing of a ${size.width}x${size.height} picture",
            ),
        )
    }

    private suspend fun videoDecoderReceive(decoder: VideoDecoder): VideoFrame? = try {
        decoder.receive()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        throw VideoDecoderRuntimeFailure("receive", failure)
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
        held: HeldLanding,
    ): Boolean {
        var ownsFrame = true
        try {
            if (frame.generation != epoch) return true
            // Recorded before the discard, because this is where the seek actually landed and that is what
            // the overshoot ladder has to judge. A precise seek that landed correctly still throws away up to
            // a whole group of pictures, so judging the landing by the first frame that survived the discard
            // would call every correct precise seek an overshoot.
            session.firstDecodedVideo.record(epoch, frame.pts)
            session.landingArrived.trySend(Unit)
            if (frame.pts.micros < session.discardBeforeUs.value) {
                // A backward step lands on the last frame before its target, so the newest frame
                // below it is kept in place of the one before instead of being thrown away.
                if (session.landsBeforeTarget.value) {
                    held.replace(frame, session)
                    ownsFrame = false
                }
                return true
            }
            // A picture taken in place starts at the position, from the keyframe before it (#527).
            val switchDiscard = session.pictureSwitchDiscardBeforeUs.value
            if (switchDiscard != Long.MIN_VALUE) {
                if (frame.pts.micros < switchDiscard) return true
                session.pictureSwitchDiscardBeforeUs.compareAndSet(switchDiscard, Long.MIN_VALUE)
            }
            // A picture at or after the A-B loop's end belongs to the pass after B, and waits here
            // while the end stands, so the pass that plays never shows it (#467).
            if (!awaitPassEnd(session, worker, frame)) return false
            // The first frame at or after a backward target: the held frame is the landing and goes
            // out first, and this one waits behind it at the head of the queue for a forward step.
            if (!handOverHeld(session, worker, video, epoch, held)) return false
            if (offerToSchedule(session, worker, video, frame, epoch)) {
                ownsFrame = false
                return true
            }
            return false
        } finally {
            if (ownsFrame) frame.close()
            session.videoInFlight.decrementAndGet()
        }
    }

    /**
     * Waits while [frame] is at or after this pass's end (#467). The end is read before the park
     * request, because the actor lifts it before it asks for one, so a lifted end lets the frame
     * through. False when quiescence came first; the caller then closes the frame.
     */
    private suspend fun awaitPassEnd(session: OpenSession, worker: Worker, frame: VideoFrame): Boolean {
        while (true) {
            val end = session.passEnd.value ?: return true
            if (frame.pts.micros < end.us) return true
            // A silent pass has no feeder to say it reached B, and the first picture at B says
            // that every picture before it is with the schedule (#524).
            if (session.audioLane == null) end.reached.value = true
            if (worker.quiesceRequested) return false
            worker.nap(HANDOFF_POLL)
        }
    }

    /** Hands the frame a backward landing holds to the schedule. False when quiescence came first. */
    private suspend fun handOverHeld(
        session: OpenSession,
        worker: Worker,
        video: VideoPlayback,
        epoch: Generation,
        held: HeldLanding,
    ): Boolean {
        val landing = held.take() ?: return true
        var ownsLanding = true
        try {
            if (!offerToSchedule(session, worker, video, landing, epoch)) return false
            ownsLanding = false
            return true
        } finally {
            if (ownsLanding) landing.close()
            // Held frames stay counted in flight, so end of stream cannot be declared under one.
            session.videoInFlight.decrementAndGet()
        }
    }

    /**
     * Records [frame] as the landing when it is the epoch's first, then offers it to the schedule
     * until it is taken. False when quiescence was asked for first, and the caller still owns the
     * frame then.
     */
    private suspend fun offerToSchedule(
        session: OpenSession,
        worker: Worker,
        video: VideoPlayback,
        frame: VideoFrame,
        epoch: Generation,
    ): Boolean {
        session.firstVideo.record(epoch, frame.pts)
        session.landingArrived.trySend(Unit)
        while (true) {
            // The offer itself is atomic. Until it succeeds the caller still owns the frame, so
            // cancellation during the bounded retry closes it in the caller's finally instead of
            // orphaning a hardware output slot.
            if (video.trySubmit(frame)) return true
            if (worker.quiesceRequested) return false
            // Woken by the frame that frees the slot, so the release that frame queued is served
            // on the next decoder call instead of at the end of a poll (#139). Every path that
            // takes a frame out signals that departure, so the bound is the ordinary poll: a
            // shorter one only woke the worker to find the queue still full (#245).
            worker.napUntil(WORKER_POLL) { video.awaitDeparture() }
        }
    }

    /**
     * The bound on the decoder's retry when it takes no packet and gives no frame. An asynchronous
     * decoder signals nothing when it becomes ready, so a playing schedule polls it often. A paused
     * schedule takes no frame, so nothing frees the decoder's output, and the ordinary poll is
     * enough: a departure or a quiesce request still ends the wait at once (#245).
     */
    private fun handoverRetry(session: OpenSession): Duration =
        if (session.schedulerMode.value == SCHEDULER_IDLE) WORKER_POLL else HANDOVER_RETRY

    /**
     * The frame a backward landing holds: the newest one decoded below the target so far.
     *
     * Only the video decode worker touches it, and every frame it has not handed over is its own,
     * so this frame is closed or handed over on that worker and nowhere else. It stays counted in
     * the session's frames in flight while it is held.
     */
    private class HeldLanding {
        private var frame: VideoFrame? = null

        val isHolding: Boolean get() = frame != null

        /** Keeps [next] in place of the frame held so far, which is closed. */
        fun replace(next: VideoFrame, session: OpenSession) {
            drop(session)
            session.videoInFlight.incrementAndGet()
            frame = next
        }

        /** Gives the held frame up to the caller, which hands it over or closes it and uncounts it. */
        fun take(): VideoFrame? = frame.also { frame = null }

        fun drop(session: OpenSession) {
            frame?.let {
                it.close()
                session.videoInFlight.decrementAndGet()
            }
            frame = null
        }
    }

    private suspend fun runAudioDecode(session: OpenSession, worker: Worker) {
        var epoch = worker.epoch
        var restarts = -1
        var lane: AudioLane? = null
        var ending = false
        while (true) {
            worker.checkpoint()
            if (worker.releases != restarts) {
                restarts = worker.releases
                epoch = worker.epoch
                lane = session.audioLane
                ending = false
            }
            val active = lane
            if (active == null) {
                worker.nap(WORKER_POLL)
                continue
            }
            val queue = active.queue
            val decoder = active.decoder
            if (passBuffer(session, worker, decoder, epoch)) continue
            if (queue.isEndOfStream && queue.count == 0) {
                if (!ending) {
                    // Same rule as the video worker: the drain signal counts only when accepted.
                    if (decoder.send(null)) ending = true
                    continue
                }
                worker.nap(WORKER_POLL)
                continue
            }
            val packet = queue.poll()
            if (packet == null) {
                worker.napUntil(WORKER_POLL) { queue.awaitData() }
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
        var ownsBuffer = true
        try {
            if (buffer.generation != epoch) return true
            // Audio is trimmed to the target too, and for the same reason video is. A precise seek that
            // starts its sound at the keyframe plays up to a whole group of pictures of audio from before the
            // position that was asked for, which is the one part of a seek a listener hears immediately.
            // Whole buffers only: the one that straddles the target is kept, which is at most one buffer of
            // imprecision against a sample-exact trim that needs a filter this build does not have.
            val bufferEndUs = buffer.pts.micros + buffer.format.durationOf(buffer.frameCount).micros
            val discardBefore = maxOf(
                session.discardBeforeUs.value,
                session.audioSwitchDiscardBeforeUs.value,
            )
            if (bufferEndUs <= discardBefore) return true
            // Counted BEFORE the offer, not inside it: the feeder lowers this the moment it is done
            // with a buffer, and a count raised after a successful handoff could be lowered before
            // it was ever raised. An abandoned offer puts it back below.
            session.audioInFlight.incrementAndGet()
            while (true) {
                if (worker.quiesceRequested) {
                    session.audioInFlight.decrementAndGet()
                    return false
                }
                // A select rather than a cancelled send, for the same reason the actor uses one: a send
                // cancelled at the wrong instant can leave a buffer neither queued nor owned by anyone.
                val sent = select<Boolean> {
                    session.decodedAudio.onSend(buffer) { true }
                    // The quiesce request ends the wait like a timeout does: the caller loops and
                    // reads its own flags, which is what a bare timeout made it wait to do.
                    worker.onWake { false }
                    onTimeout(WORKER_POLL) { false }
                }
                if (sent) {
                    ownsBuffer = false
                    return true
                }
            }
        } finally {
            if (ownsBuffer) buffer.close()
        }
    }

    /**
     * The picture [epoch] starts on, for a sound that may start after it (#526), or null when the
     * session shows no moving picture, as with none, one turned off, cover art or a sparse stream,
     * whose sound carries the timeline, when it is a preload, which joins the ring where the item
     * before it ends, or when its picture has not come within [LANDING_GRACE]. The decoder that
     * records it never waits on this worker, and a quiesce ends the wait at once.
     */
    private suspend fun pictureStartUs(session: OpenSession, worker: Worker, epoch: Generation): Long? {
        val picture = session.videoStream
        if (session.video == null || picture == null || picture.isCoverArt || picture.isSparse) return null
        if (session.videoParked.value || session.preloading.value) return null
        val deadline = clock.nanos() + LANDING_GRACE.inWholeNanoseconds
        while (true) {
            session.firstVideo.of(epoch)?.let { return it.micros }
            if (worker.quiesceRequested || clock.nanos() >= deadline) return null
            worker.nap(HANDOFF_POLL)
        }
    }

    /**
     * Writes silence from [fromUs] to [untilUs] ahead of a sound that starts after where playing
     * starts (#526), so the clock runs from the picture rather than jumping to the first sample, and
     * the picture before the sound shows. mpv and VLC play such a file the same way. Written in
     * slices through the same door as decoded sound, so the conversion and the clock see one
     * stream, and the taps hear the silence too. False when a quiesce abandoned it part way.
     */
    private suspend fun feedSilence(
        session: OpenSession,
        worker: Worker,
        audio: AudioPlayback,
        fromUs: Long,
        untilUs: Long,
        format: AudioFormat,
        epoch: Generation,
    ): Boolean {
        val totalFrames = (untilUs - fromUs) * format.sampleRate / 1_000_000L
        if (totalFrames <= 0) return true
        val slice = (format.sampleRate / 10).coerceAtLeast(1)
        val silence = FloatArray(slice * format.channels)
        session.firstAudio.record(epoch, Pts(fromUs))
        session.landingArrived.trySend(Unit)
        var written = 0L
        while (written < totalFrames) {
            if (worker.quiesceRequested) return false
            val frames = minOf(slice.toLong(), totalFrames - written).toInt()
            val pts = Pts(fromUs + written * 1_000_000L / format.sampleRate)
            deliverToTaps(Generation(session.audioGeneration.value), pts, silence, frames, format)
            audio.submitDecoded(pts, silence, frames, format, { worker.quiesceRequested }, worker::nap)
            written += frames
        }
        return !worker.quiesceRequested
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
        val interleaver = Interleaver()
        var epoch = worker.epoch
        var restarts = worker.releases
        // How far this epoch's sound is written, which decides whether an A-B loop's end came in
        // time to be taken (#467).
        var fedUntilUs = Long.MIN_VALUE
        // The buffer that reaches past a taken pass end, kept unwritten from the end on.
        var held: AudioBuffer? = null
        var heldAt: PassEnd? = null
        try {
            while (true) {
                // Every park comes before the ring is cleared, by a seek or a track change, or before
                // the next pass follows the end, so the kept buffer is never written after one. It
                // goes now, as the decoded buffers waiting in the channel go.
                if (worker.quiesceRequested) {
                    held?.let { kept ->
                        held = null
                        heldAt = null
                        kept.close()
                        session.audioInFlight.decrementAndGet()
                    }
                }
                worker.checkpoint()
                if (worker.releases != restarts) {
                    restarts = worker.releases
                    if (worker.epoch != epoch) fedUntilUs = Long.MIN_VALUE
                    epoch = worker.epoch
                }
                val waiting = held
                var resumeFromUs = Long.MIN_VALUE
                val buffer = if (waiting != null) {
                    val end = heldAt ?: error("a held buffer has its end")
                    // Kept while the end stands: the next pass takes the ring from here, or the
                    // end is lifted and this pass carries on from it. A quiesce ends the nap, and
                    // the buffer goes at the top of the loop.
                    if (session.passEnd.value === end) {
                        worker.nap(HANDOFF_POLL)
                        continue
                    }
                    held = null
                    heldAt = null
                    resumeFromUs = end.us
                    waiting
                } else {
                    select<AudioBuffer?> {
                        session.decodedAudio.onReceive { it }
                        worker.onWake { null }
                        onTimeout(WORKER_POLL) { null }
                    }
                }
                if (buffer == null) {
                    // Nothing waiting. If the session has said the stream is over and the handoff is
                    // provably empty, push the DSP tail into the ring and answer. This worker owns the
                    // pipeline, so it is the only place that may.
                    val audio = session.audio
                    if (session.audioLane != null && audio != null && session.audioEosRequested.value &&
                        !session.audioTailFlushed.value &&
                        session.audioInFlight.value == 0
                    ) {
                        audio.finishDecoded({ worker.quiesceRequested }, worker::nap)
                        // Only after the tail is in the ring, so the terminal state cannot read this
                        // as done while a quiesce abandoned the submit half way.
                        if (!worker.quiesceRequested) session.audioTailFlushed.value = true
                    }
                    continue
                }
                var keep = false
                try {
                    if (buffer.generation != epoch) continue
                    val audio = session.audio ?: continue
                    // Read before the landing below is signalled. The actor ends the seek and clears the
                    // boundary as soon as it sees the landing, and a trim that read it afterwards kept
                    // the samples before the target (#292).
                    val switchDiscard = session.audioSwitchDiscardBeforeUs.value
                    // A buffer kept at a pass end that was lifted resumes where its written half ended.
                    val discardBefore = maxOf(session.discardBeforeUs.value, switchDiscard, resumeFromUs)
                    // Where the sound must start when it starts later than this buffer says: at the
                    // switch point after an in-place change, or at the picture the epoch starts on,
                    // so the clock starts with the picture (#526).
                    if (resumeFromUs == Long.MIN_VALUE && (switchDiscard != Long.MIN_VALUE || fedUntilUs == Long.MIN_VALUE)) {
                        val from = if (switchDiscard != Long.MIN_VALUE) switchDiscard else pictureStartUs(session, worker, epoch)
                        if (worker.quiesceRequested) continue
                        if (from != null && buffer.pts.micros > from) {
                            if (!feedSilence(session, worker, audio, from, buffer.pts.micros, buffer.format, epoch)) continue
                            fedUntilUs = buffer.pts.micros
                        }
                    }
                    var interleaved = interleaver.interleave(buffer)
                    var pts = buffer.pts
                    var frames = buffer.frameCount
                    // Sample-exact trim of the one buffer that straddles the seek target. The decode
                    // side drops whole buffers that END before the target; this slices the leading
                    // pre-target samples off the survivor, so a precise seek starts its sound AT the
                    // target instead of up to one buffer early. Runs at most once per
                    // seek, so the one copyOfRange is off any steady-state path.
                    if (discardBefore != Long.MIN_VALUE && pts.micros < discardBefore && buffer.format.sampleRate > 0) {
                        val skipFrames = ((discardBefore - pts.micros) * buffer.format.sampleRate / 1_000_000L)
                            .coerceIn(0L, frames.toLong()).toInt()
                        if (skipFrames > 0) {
                            val channels = buffer.format.channels
                            interleaved = interleaved.copyOfRange(skipFrames * channels, frames * channels)
                            pts = Pts(pts.micros + buffer.format.durationOf(skipFrames).micros)
                            frames -= skipFrames
                        }
                    }
                    // The A-B loop's end, the mirror of the trim above (#467): the samples before it
                    // are written, and the buffer is kept from it on, so the next pass's first
                    // sample follows the last one before B.
                    val end = session.passEnd.value?.takeIf { fedUntilUs <= it.us }
                    if (end != null && buffer.format.sampleRate > 0) {
                        val before = ((end.us - pts.micros) * buffer.format.sampleRate / 1_000_000L)
                            .coerceIn(0L, frames.toLong()).toInt()
                        if (before < frames) {
                            keep = true
                            held = buffer
                            heldAt = end
                            if (before > 0) interleaved = interleaved.copyOfRange(0, before * buffer.format.channels)
                            frames = before
                        }
                    }
                    if (frames == 0) {
                        if (keep) end?.reached?.value = true
                        continue
                    }
                    // The landing is the first sample that will be heard, so it is recorded after the trim.
                    session.firstAudio.record(epoch, pts)
                    session.landingArrived.trySend(Unit)
                    // The trimmed block goes to the taps first, then to the device.
                    deliverToTaps(Generation(session.audioGeneration.value), pts, interleaved, frames, buffer.format)
                    // One call, no external timeout, no retry. The old shape cancelled submitDecoded
                    // mid-buffer on a deadline and called it again with the same input, which replayed
                    // samples the ring had already accepted and ran the stateful conversion twice
                    // The abort callback bounds the wait instead: while the ring is full
                    // the submit polls it, and a quiesce request abandons the unaccepted remainder,
                    // which the seek's flush was about to discard anyway. The wait on a full ring is
                    // the worker's nap, so the same request also ends that wait at once.
                    audio.submitDecoded(pts, interleaved, frames, buffer.format, { worker.quiesceRequested }, worker::nap)
                    fedUntilUs = maxOf(fedUntilUs, pts.micros + buffer.format.durationOf(frames).micros)
                    // Only once the samples before the end are in the ring, because the handoff that
                    // this answer starts parks this worker, and a park abandons what is unwritten.
                    if (keep) end?.reached?.value = true
                    if (switchDiscard != Long.MIN_VALUE) {
                        session.audioSwitchDiscardBeforeUs.compareAndSet(switchDiscard, Long.MIN_VALUE)
                    }
                } finally {
                    if (!keep) {
                        buffer.close()
                        // Lowered only here, after the buffer is finished with on every path including the
                        // epoch skip above, so the count covers the conversion and not just the queue.
                        session.audioInFlight.decrementAndGet()
                    }
                }
            }
        } finally {
            held?.let { kept ->
                kept.close()
                session.audioInFlight.decrementAndGet()
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
            // A picture turned off ended this lane while it was parked (#529).
            if (session.videoScheduler !== worker) return
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
                    val master = masterReading(session)
                    val wait = video.tick(master?.pts, master?.speed)
                    recordVideoClock(session, video)
                    // The pacing wait yields to a park request like every other idle wait: while
                    // playing, this sleep IS the schedule, so a seek that did not interrupt it paid
                    // most of a frame period before the pipeline could be parked.
                    if (wait > Duration.ZERO) worker.nap(minOf(wait, WORKER_POLL).atLeastOneTick())
                }
                SCHEDULER_ONE_FRAME -> {
                    video.resumeSchedule()
                    // Released and not shown: a renderer that refuses still consumed the frame, so
                    // a gate counting successes alone would tick the whole queue away one frame at
                    // a time looking for a success that is never coming.
                    val before = session.framesReleased(video)
                    val wait = video.presentNext(masterPosition(session))
                    recordVideoClock(session, video)
                    if (session.framesReleased(video) > before) {
                        session.schedulerMode.compareAndSet(SCHEDULER_ONE_FRAME, SCHEDULER_IDLE)
                        // The actor's presentFirstFrame is watching these counters; wake it.
                        session.landingArrived.trySend(Unit)
                    } else if (wait > Duration.ZERO) {
                        worker.nap(minOf(wait, WORKER_POLL).atLeastOneTick())
                    }
                }
                else -> {
                    video.pauseSchedule()
                    // A slot published before the pause ends at a time the pause has moved on.
                    session.shownSlot.value = null
                    select<Unit> {
                        session.schedulerNudge.onReceive { }
                        worker.onWake { }
                        onTimeout(WORKER_POLL) { }
                    }
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
        // The audio-delay bias: reading the master AHEAD by the delay presents every frame that
        // much earlier, which is exactly what a sound that arrives late at the ear needs.
        session.audioLane == null -> null
        else -> session.audio?.position()?.let { Pts(it.micros + audioDelay.inWholeMicroseconds) }
    }

    /**
     * The audio clock in one reading, for the schedule: its position, biased like [masterPosition],
     * and the rate the listener hears. Null when video is the master or nothing is audible yet.
     */
    private fun masterReading(session: OpenSession): ClockSnapshot? {
        if (config.syncMode == SyncMode.VideoMaster || session.audioLane == null) return null
        val reading = session.audio?.clockSnapshot() ?: return null
        val pts = reading.pts ?: return null
        return reading.copy(pts = Pts(pts.micros + audioDelay.inWholeMicroseconds))
    }

    /**
     * The rate that turns a media distance into a wake-up delay. After a speed change the device
     * still plays the old rate for the ring's depth, so the faster of the two is used: waking early
     * costs one pass, waking late misses the moment.
     */
    private fun wakeRate(): Double {
        val audible = publishedAudioClock.value.rate
        return if (audible > 0.0) maxOf(speed, audible) else speed
    }

    /** Publishes what the scheduler alone may read, so the actor never touches the video clock. */
    private fun recordVideoClock(session: OpenSession, video: VideoPlayback) {
        session.lastVideoPtsUs.value = video.position()?.micros ?: NO_POSITION
        session.shownSlot.value = video.shownSlot()
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
    /** One audio selection, published as a unit so workers never observe a mixed trio. */
    private class AudioLane(
        val stream: PlayerStreamInfo,
        val decoder: AudioDecoder,
        val queue: PacketQueue,
    )

    private class AudioRouting(
        val lane: AudioLane?,
        val selectedQueues: List<PacketQueue>,
    )

    /** A session's per-stream caches, with every packet-owning queue listed once. */
    private class QueueTable(
        val video: PacketQueue?,
        val audio: Map<Int, PacketQueue>,
        val subtitle: Map<Int, PacketQueue>,
        /**
         * One cache for each picture the demux lane reads besides the one the open chose: each that
         * appeared after the open, and the open's own once another took its place (#527). The
         * picture that plays may be one of them, and is then [video] too.
         */
        val pictures: Map<Int, PacketQueue> = emptyMap(),
    ) {
        val all: List<PacketQueue> =
            listOfNotNull(video) + audio.values + subtitle.values + pictures.values.filter { it !== video }
    }

    /** The picture stream at [index] played from [fromUs] on (#527). */
    private class PictureSpan(val fromUs: Long, val index: Int)

    /**
     * The picture a session plays: its stream, its packet queue and its schedule, replaced together
     * and only by the actor, when a picture appears after the open or the one that played gives way
     * to another (#527). Null throughout is a session with no picture.
     */
    private class PictureLane(
        val stream: PlayerStreamInfo?,
        val queue: PacketQueue?,
        val playback: VideoPlayback?,
    )

    private class OpenSession(
        val token: Long,
        val backendSession: BackendSession,
        val source: PlayerMediaSource,
        videoStream: PlayerStreamInfo?,
        /**
         * Set before the video decode worker starts: at build, at the swap for a preload, or with
         * the worker parked when a picture appears after the open (#527).
         */
        var videoDecoder: VideoDecoder?,
        var videoDecoderOrigin: VideoDecoderOrigin?,
        /** The renderer whose factory made [videoDecoder], null for a backend decoder. */
        var coupledRenderer: VideoRenderer?,
        videoQueue: PacketQueue?,
        audioLane: AudioLane?,
        /** One epoch-aligned compressed cache for every audio stream in the container. */
        audioQueues: Map<Int, PacketQueue>,
        // The subtitle trio is mutable for exactly one writer: the actor. Demux never reads it;
        // it routes through the per-track maps below, so a live swap needs no video or demux
        // interruption.
        var subtitleStream: PlayerStreamInfo?,
        var subtitleDecoder: io.github.yuroyami.kiteplayer.spi.SubtitleDecoder?,
        var subtitleQueue: PacketQueue?,
        /** One epoch-aligned compressed cache for every container subtitle stream. */
        subtitleQueues: Map<Int, PacketQueue>,
        video: VideoPlayback?,
        /** Actor-owned; created lazily when a session initially opened with audio deselected. */
        var audio: AudioPlayback?,
        var sink: AudioSink?,
        val renderer: AttachableRenderer,
        var negotiatedFormat: AudioFormat?,
        /** Non-null when this open reads through the byte cache; progress reads its window. */
        val cachingIo: CachingMediaIo? = null,
        /** What the readers opened for the media's other addresses did, such as HLS segments. */
        val relatedTraffic: RelatedTraffic? = null,
        /** How long the demux lane has waited for the source; see `BufferPolicy.stallTimeout`. */
        val stallWatch: StallWatch,
        /** The reader the item's address resolved to, before the engine's own layers, for its network rate. */
        val networkIo: MediaIo? = null,
        /**
         * The external subtitle track the build chose over the container's own by language, which
         * the open selects once the track exists, or null (#514).
         */
        val preferredExternalSubtitle: TrackId? = null,
        /** What this item's sound is, resolved at the build, for every device it opens (#446). */
        val audioContent: AudioContent = AudioContent.Music,
        /**
         * The streams the open asked the source for. The demux lane adds to its own copy of this as
         * streams appear after the open (#509), because it is the lane that talks to the source.
         */
        val readStreams: Set<Int> = emptySet(),
    ) {
        /**
         * True once the source answered that it cannot interrupt a stalled read. The session then
         * keeps waiting, because a teardown around a read that never returns would hang. Actor only.
         */
        var stallInterruptRefused: Boolean = false

        /**
         * False once a gapless handoff gave [audio] and [sink] to the next item: this session's
         * release then leaves the device and the ring alone. Actor only.
         */
        var ownsAudio: Boolean = true

        /** The format the audio device was opened for, which a gapless handoff compares against. Actor only. */
        var deviceRequest: AudioFormat? = null

        /**
         * True while a preloaded item waits for its video decoder, which the renderer's own
         * factory makes at the swap, once the item before it has freed the surface. Actor only.
         */
        var videoDecoderDeferred: Boolean = false

        /**
         * True when the video decoder of this session was chosen with an attached renderer's own
         * factories in the running. A renderer that arrives later rebuilds the path only while this
         * is false, so a session asks a renderer once (#384). Actor only.
         */
        var rendererDecodersAsked: Boolean = false

        /** How fast the source delivers media while the demuxer reads, for the variant step down (#376). */
        val readRate: ReadRate = ReadRate()

        /**
         * True while this session is the next queue item, open in the background. Its workers then
         * judge nothing by the published position, which belongs to the item still playing.
         */
        val preloading = atomic(false)

        private val pictureLane = atomic(PictureLane(videoStream, videoQueue, video))

        /** The picture that plays, or null. */
        val videoStream: PlayerStreamInfo? get() = pictureLane.value.stream

        /** The picture's packets. */
        val videoQueue: PacketQueue? get() = pictureLane.value.queue

        /** The picture's schedule, which hands its frames to [renderer]. */
        val video: VideoPlayback? get() = pictureLane.value.playback

        private val audioRouting = atomic(
            AudioRouting(audioLane, listOfNotNull(videoQueue, audioLane?.queue)),
        )

        val audioLane: AudioLane? get() = audioRouting.value.lane
        val audioStream: PlayerStreamInfo? get() = audioLane?.stream
        val audioDecoder: AudioDecoder? get() = audioLane?.decoder
        val audioQueue: PacketQueue? get() = audioLane?.queue

        fun installAudioLane(lane: AudioLane?) {
            audioRouting.value = AudioRouting(lane, listOfNotNull(videoQueue, lane?.queue))
        }

        /**
         * Makes [stream] the picture, read from its cache [queue] and shown through [playback]
         * (#527). Every table changes at once, so the demux lane routes the stream's next packet to
         * the same queue it filled before. The picture that played stays a cache of its own, because
         * the lane goes on reading it: a seek back can find it again. Actor only, with the video
         * lanes parked or not started.
         */
        fun installPicture(stream: PlayerStreamInfo, queue: PacketQueue, playback: VideoPlayback) {
            replacePicture(PictureLane(stream, queue, playback))
        }

        /**
         * Takes the picture off (#529). The queue it read stays a cache the demux lane goes on
         * filling, so choosing the picture again plays in place. Actor only, with the video lanes
         * parked.
         */
        fun removePicture() {
            replacePicture(PictureLane(null, null, null))
        }

        private fun replacePicture(next: PictureLane) {
            val before = pictureLane.value
            pictureLane.value = next
            val table = queueTable.value
            val left = before.stream?.index
            val pictures = if (left != null && before.queue != null && left !in table.pictures) {
                table.pictures + (left to before.queue)
            } else {
                table.pictures
            }
            queueTable.value = QueueTable(next.queue, table.audio, table.subtitle, pictures)
            audioRouting.value = AudioRouting(audioLane, listOfNotNull(next.queue, audioLane?.queue))
        }

        /**
         * The per-stream caches, replaced whole and only by the actor when a stream appears after
         * the open (#509), so the demux lane reads one table or the next and never half of one.
         */
        private val queueTable = atomic(QueueTable(videoQueue, audioQueues, subtitleQueues))

        val audioQueues: Map<Int, PacketQueue> get() = queueTable.value.audio

        val subtitleQueues: Map<Int, PacketQueue> get() = queueTable.value.subtitle

        /** The picture caches, by stream; see [QueueTable.pictures]. */
        val pictureQueues: Map<Int, PacketQueue> get() = queueTable.value.pictures

        /** Every packet-owning queue, each exactly once, for byte accounting/flush/teardown. */
        val allPacketQueues: List<PacketQueue> get() = queueTable.value.all

        /** Adds the cache of a stream that appeared after the open. Actor only. */
        fun addQueue(kind: TrackKind, queue: PacketQueue) {
            val table = queueTable.value
            val index = queue.streamIndex
            queueTable.value = when (kind) {
                TrackKind.Audio -> QueueTable(table.video, table.audio + (index to queue), table.subtitle, table.pictures)
                TrackKind.Subtitle -> {
                    subtitleCueCaches[index] = mutableListOf()
                    QueueTable(table.video, table.audio, table.subtitle + (index to queue), table.pictures)
                }
                TrackKind.Video -> QueueTable(table.video, table.audio, table.subtitle, table.pictures + (index to queue))
            }
        }

        /**
         * True once the source announced a stream or a programme change after the open (#509). Only
         * then does the engine look for a sound or subtitles to choose again, so media whose streams
         * never change plays exactly as it did. Actor only.
         */
        var layoutChanged: Boolean = false

        /** True when subtitles appeared or the programmes changed, until the choice runs again. Actor only. */
        var subtitlesToChoose: Boolean = false

        /** The sounds the player chose on its own after the open, each tried once. Actor only. */
        val soundsTried: MutableSet<Int> = HashSet()

        /**
         * The pictures that could not take over in place, each refused once (#527), so one no decoder
         * takes is not asked for again on every pass. Actor only.
         */
        val picturesRefused: MutableSet<Int> = HashSet()

        /**
         * Which picture played from where, once the player moved the picture in place (#527), so a
         * seek plays the picture that played at its target. Empty until then. Actor only.
         */
        val pictureTimeline: MutableList<PictureSpan> = ArrayList()

        /** Between the audio decoder and the feeder. Small, because the ring is the real buffer. */
        val decodedAudio: Channel<AudioBuffer> = Channel(capacity = 4)

        /** Written before worker start or with the audio workers parked. Read once per tap block. */
        val audioGeneration = atomic(0L)

        /**
         * Pinged when a worker records a first timestamp for a new epoch, and when the schedule
         * releases the frame a one-frame request asked for, so the actor's waits wake when the
         * thing happens instead of at their next 50 ms sample. Conflated: one token is enough,
         * every waiter re-reads its own conditions.
         */
        val landingArrived: Channel<Unit> =
            Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /**
         * Pinged by the actor when it changes [schedulerMode] and needs the schedule to act on it
         * now, so an idle scheduler wakes instead of sleeping out the rest of its 50 ms nap.
         */
        val schedulerNudge: Channel<Unit> =
            Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /**
         * Decoded audio that has left the decoder and has not yet reached the device.
         *
         * Counts what is queued in [decodedAudio] AND the one buffer the feeder is converting, so
         * a reading of zero means the handoff really is empty rather than momentarily so. The end
         * of stream reads it: the packet queue emptying says only that demuxing finished, and up
         * to five buffers of real audio can still be in here when it does.
         *
         * Raised by the decoder before it offers a buffer and lowered by the feeder once the
         * buffer is done with, in that order, so the count is an upper bound and never negative.
         */
        val audioInFlight = atomic(0)

        /**
         * Video frames the decoder has produced that have not reached the frame queue yet.
         *
         * The audio twin above exists because a drained decoder and an empty packet queue say
         * "demuxing and decoding finished", not "nothing is still on its way". The
         * video lane has the same gap between [videoDecoderReceive] returning a frame and
         * [handOver] submitting it, and end-of-stream questions asked in that gap would answer
         * that no frame can arrive while one is in a worker's hand.
         */
        val videoInFlight = atomic(0)

        /**
         * The end-of-stream token for the audio lane, set by the session and read by the feeder.
         *
         * The feeder answers by flushing the DSP tail and setting [audioTailFlushed]. Both are
         * cleared by a flush, because a seek away from the end makes the stream un-ended.
         */
        val audioEosRequested = atomic(false)

        /** Set by the feeder once the DSP tail is in the ring. The terminal state waits for it. */
        val audioTailFlushed = atomic(false)

        /**
         * Where this turn of the item stops, so that the next pass of an A-B loop follows its last
         * sample before B in the ring (#467), or null. Given and lifted by the actor; the feeder
         * and the video lane hold everything at or after it. See [PassEnd].
         */
        val passEnd = atomic<PassEnd?>(null)

        /**
         * Whether this turn of an A-B loop was given its end, or none, and the B it was given for.
         * Actor-owned, but for a pass, whose build gives them before it is handed over.
         */
        var turnDecided: Boolean = false
        var turnEndUs: Long? = null

        /**
         * Where to seek once the ring was taken back from an A-B loop's next pass, which loses the
         * sound the ring held before B, or [NO_POSITION]. Actor-owned; a seek clears it.
         */
        var resumeAfterTakeBackUs: Long = NO_POSITION

        val decodedVideoFrames = atomic(0L)

        /** Wall time each decoded frame took on the receive side; sorted only at the stats tick. */
        val decodeTimes = Percentiles(240)

        /** Set once [PlaybackWarning.CropIgnored] has been said for this session's video stream. */
        val cropIgnoredWarned = atomic(false)

        /** Packets thrown away before the decoder ever saw them. See FrameDropPolicy.LateAndDecode. */
        val droppedVideoBeforeDecode = atomic(0L)
        val lastVideoPtsUs = atomic(NO_POSITION)
        /**
         * The frame on screen and the end of its slot, as the scheduler last published them, or
         * null while the schedule is idle. A silent item's next pass starts at that end (#524).
         */
        val shownSlot = atomic<ShownSlot?>(null)
        val driftUs = atomic(0L)
        val discardBeforeUs = atomic(Long.MIN_VALUE)
        /**
         * True while a backward step lands: the video lane keeps the newest frame below
         * [discardBeforeUs] instead of closing it, and shows that frame when the first one at or
         * after the target arrives. Set and cleared by the seek machine with [discardBeforeUs].
         */
        val landsBeforeTarget = atomic(false)
        /**
         * True while the video lane is parked: packets are discarded before the decoder.
         *
         * Not a drop. The drop counters mean the engine could not keep up, and reporting a
         * deliberate park through them would turn every backgrounded application into a
         * performance bug report.
         */
        val videoParked = atomic(false)
        /** Set when the lane un-parks: packets are discarded until a keyframe the decoder can start from. */
        val videoWaitingForKeyframe = atomic(false)
        /** Audio-only precise boundary for a lane swap; video remains on the current epoch. */
        val audioSwitchDiscardBeforeUs = atomic(Long.MIN_VALUE)

        /**
         * Where a picture taken in place starts (#527). Its cache starts at the keyframe before the
         * position, so the frames before this are decoded and not shown, as a precise seek does.
         * Set by the actor with the video lanes parked, and cleared by the decode lane at the first
         * frame past it, or by a seek.
         */
        val pictureSwitchDiscardBeforeUs = atomic(Long.MIN_VALUE)
        val schedulerMode = atomic(SCHEDULER_IDLE)
        val firstVideo = FirstTimestamp()

        /** Before the precise discard, so the overshoot ladder judges the landing and not the filter. */
        val firstDecodedVideo = FirstTimestamp()
        val firstAudio = FirstTimestamp()
        val firstWorkerOutcome = atomic<WorkerOutcome?>(null)

        var demuxWorker: Worker? = null
        var videoDecodeWorker: Worker? = null
        var audioDecodeWorker: Worker? = null
        var audioFeedWorker: Worker? = null
        var videoScheduler: Worker? = null
        val jobs: MutableList<Job> = mutableListOf()

        /** The sole device-event collector, including a lazily-created audio path. */
        var audioEventJob: Job? = null

        /** A live swap refills the empty ring before restarting the device, without buffering video. */
        var audioDeviceNeedsStart: Boolean = false

        /**
         * True from a frame step until playback resumes or another seek starts. A step moves the
         * picture and leaves both clocks where they stopped, so the frame on screen is the position.
         */
        var pictureHoldsPosition: Boolean = false

        /** Demux-lane-only cadence cursor for inactive compressed-cache pruning. */
        var lastSwitchCachePrunePositionUs: Long = Long.MIN_VALUE

        /** The one in-flight subtitle rasterisation; a newer cue edge cancels and replaces it. */
        var rasterJob: Job? = null

        /** Said once: the condition lasts as long as the file does. */
        var warnedAboutInterleaving: Boolean = false
        var warnedAboutDeviceUnderrun: Boolean = false

        /**
         * The furthest position this session has played or landed at, which is how long the media
         * has proved to be. Read when its length is only an estimate (#422). Actor-confined.
         */
        var furthestPositionUs: Long = 0L

        /** Per-container-track cue history. Only the actor mutates these start-sorted tables. */
        val subtitleCueCaches: MutableMap<Int, MutableList<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>> =
            subtitleQueues.keys.associateWith { mutableListOf<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>() }
                .toMutableMap()

        /** The cue store currently timed by the selector; external tracks use a detached table. */
        /** The lookup cache over [subtitleCues]. Derived, so nothing has to keep it in step by hand. */
        val cueIndex: io.github.yuroyami.kiteplayer.subtitle.CueIndex =
            io.github.yuroyami.kiteplayer.subtitle.CueIndex()

        var subtitleCues: MutableList<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue> =
            subtitleStream?.let { subtitleCueCaches.getValue(it.index) } ?: mutableListOf()

        // The secondary subtitle lane: the same shape as the primary fields above, driven by
        // its own budgeted pass and timed by its own index; its cues are forced to the top before
        // rasterising. One slot per direction, exactly mpv's secondary-sid.
        var subtitle2Stream: PlayerStreamInfo? = null
        var subtitle2Decoder: io.github.yuroyami.kiteplayer.spi.SubtitleDecoder? = null
        var subtitle2Queue: PacketQueue? = null
        var pendingSubtitle2Packet: io.github.yuroyami.kiteplayer.spi.PlayerPacket? = null
        var subtitle2DecoderMayHaveOutput: Boolean = false
        var subtitle2Drained: Boolean = false
        var subtitle2DrainRefusals: Int = 0
        var subtitle2Cues: MutableList<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue> = mutableListOf()
        var lastSubtitle2PruneCutoffUs: Long = Long.MIN_VALUE
        val cue2Index: io.github.yuroyami.kiteplayer.subtitle.CueIndex =
            io.github.yuroyami.kiteplayer.subtitle.CueIndex()

        /**
         * A subtitle packet the decoder refused because its output side was full, retained so the
         * next pass retries it instead of losing the cue. Session thread only; closed on flush
         * and teardown like any other queued packet.
         */
        var pendingSubtitlePacket: io.github.yuroyami.kiteplayer.spi.PlayerPacket? = null

        /** True when receive() must be drained before another subtitle packet may be sent. */
        var subtitleDecoderMayHaveOutput: Boolean = false

        /** Last cutoff that paid a full cue-table prune scan; reset with every seek flush. */
        var lastSubtitlePruneCutoffUs: Long = Long.MIN_VALUE

        /** What the last published overlay showed, so an unchanged set publishes nothing. */
        var publishedCueKey: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>? = null

        /**
         * Whether the primary lane's decoder has taken the null packet that tells it the stream has
         * ended, and how often it refused it (#480). A decoder may hold its last cue until then.
         * Reset by every flush and every decoder change, because a drained decoder takes packets
         * again only after a flush. The secondary lane keeps its own pair.
         */
        var subtitleDrained: Boolean = false
        var subtitleDrainRefusals: Int = 0

        /**
         * Why a recording this session's source still makes ends when the session is released,
         * warned as [PlaybackWarning.RecordingStopped], or null when the caller ended it and nothing
         * is warned. The release finishes the file, after the workers are joined (#473).
         */
        var recordingEnd: String? = "the player closed the media being recorded"

        /** The surface size the published overlay was rasterised for, so a resize can redraw it. */
        var publishedCanvas: Pair<Int, Int>? = null

        /**
         * Monotonic overlay identity. A hash of the cue content is collision-prone; a counter
         * bumped on every real change can never claim two different overlays are the same.
         */
        /** Bumped on the actor, read by the raster lane's stale-work guard. */
        val overlayGeneration = atomic(0L)

        /** The typesetting lane drawing the selected ASS track, or null when the Kotlin tier does. */
        var typeset: TypesetLane? = null

        var videoStatus: StreamStatus = StreamStatus.Syncing
        var audioStatus: StreamStatus = StreamStatus.Syncing

        val workers: List<Worker>
            get() = listOfNotNull(demuxWorker, videoDecodeWorker, audioDecodeWorker, audioFeedWorker, videoScheduler)

        /**
         * One atomic snapshot: video plus only the audio lane that currently carries playback.
         *
         * A parked video lane is left out. Its packets are thrown away as they arrive, so its queue
         * is always empty, and counting it made the open wait 10 s for a picture and the
         * interleaving relief cut the audio as if video were starved (#374).
         */
        fun selectedQueues(): List<PacketQueue> {
            val queues = audioRouting.value.selectedQueues
            val parked = videoQueue?.takeIf { videoParked.value } ?: return queues
            return queues.filter { it !== parked }
        }

        fun decodersDrained(): Boolean =
            (videoDecoder?.isDrained ?: true) && (audioDecoder?.isDrained ?: true)

        fun anyWorkerFinished(): Boolean = workers.any { it.isFinished }

        /** Frames that reached a screen, or would have if one were attached. */
        fun framesOut(video: VideoPlayback?): Long {
            if (video == null) return 0
            return video.submittedFrames + video.headlessFrames
        }

        /**
         * Frames the schedule let go of, refusals included.
         *
         * What "one frame went out" must mean for any wait: a renderer that refuses everything
         * still consumed the frame, and a wait that only counted successes never ended.
         */
        fun framesReleased(video: VideoPlayback?): Long = video?.releasedFrames ?: 0

        val isStillImage: Boolean
            get() = videoStream.let { it != null && audioStream == null && (it.isCoverArt || it.isSparse) }
    }

    private companion object {
        /** How far behind the position container cues survive before pruning. */
        const val CUE_PRUNE_BEHIND_MICROS = 30_000_000L

        /** Alternate packet caches follow playback at this cadence, never the demux frontier. */
        const val SWITCH_CACHE_PRUNE_STEP_US = 250_000L

        /** Enough compressed preroll for decoder priming and a current-position audio swap. */
        const val AUDIO_SWITCH_MIN_HISTORY_US = 1_000_000L

        /**
         * How long a container seek may block before the engine aborts it through the source's
         * interrupt seam. Generous on purpose: an unindexed network scan can be slow
         * and legitimate, and a wedge is minutes, not seconds.
         */
        val SEEK_NATIVE_DEADLINE = 10.seconds

        /** How long an interrupted seek is given to actually return before the session fails. */
        val SEEK_INTERRUPT_GRACE = 2.seconds

        /**
         * Stand-in end for an audio packet whose duration FFmpeg left at zero. A compressed audio
         * packet spans tens of milliseconds, so one second over-retains by an order of magnitude
         * and still keeps a duration-less lane trimmable.
         */
        const val AUDIO_PRUNE_ASSUMED_PACKET_DURATION_US = 1_000_000L

        /** A target beginning farther ahead would violate the user-visible switch latency bound. */
        const val AUDIO_SWITCH_MAX_START_GAP_US = 250_000L

        /**
         * How far the reads must have gone past the end of a sound's last packet before that sound
         * counts as run out (#509). Interleaving in a transport stream is well inside it.
         */
        const val SOUND_RAN_OUT_US = 1_000_000L

        /** Inline subtitle work yields the actor at these hard operation ceilings. */
        const val SUBTITLE_PACKETS_PER_PASS = 32
        const val SUBTITLE_RECEIVE_BATCHES_PER_PASS = 32

        /**
         * How often a subtitle decoder may refuse the end-of-stream drain before it counts as
         * drained, so a decoder that refuses it for ever cannot hold the end of the media (#480).
         */
        const val SUBTITLE_DRAIN_REFUSALS = 8

        /** Pruning is a memory bound, not a cue-edge operation; one scan per media second suffices. */
        const val CUE_PRUNE_STEP_MICROS = 1_000_000L

        /** No seek in flight: [maskedSeekTargetMicros] defers to the published clock. */
        const val NO_SEEK_MASK: Long = Long.MIN_VALUE

        /** Said once, by every path where a stop or a close ends a selection before it applies. */
        const val PREEMPTED_SELECTION: String =
            "stop() or close() tore the session down before the selection could be applied"

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

        /**
         * The most the end of a session waits for a cue that outlives the last frame.
         *
         * A real closing caption runs a second or two past the pictures. A cue whose end is
         * further out than this is a malformed file, and holding a finished player open on its
         * word would be worse than cutting the line short.
         */
        val SUBTITLE_TAIL_MAX: Duration = 10.seconds

        /**
         * The most an external subtitle file may be.
         *
         * Sixteen mebibytes. The largest real subtitle files are a few hundred kilobytes, so this
         * is not a limit anybody meets; it is what stops a wrong address, or a server answering
         * with a film, from being pulled whole into memory before anything notices.
         */
        const val MAX_SUBTITLE_BYTES: Int = 16 * 1024 * 1024

        /** Read in blocks rather than one call, because a reader may answer short. */
        const val SUBTITLE_READ_CHUNK: Int = 64 * 1024

        /** How long a subtitle read waits after a reader answers "nothing yet" before it asks again. */
        val SUBTITLE_EMPTY_READ_RETRY: Duration = 10.milliseconds

        /**
         * How far behind the clock a packet has to be before FrameDropPolicy.LateAndDecode throws
         * it away undecoded.
         *
         * Half a second, which is many frames rather than one. The schedule already drops a frame
         * that is late by a fraction of its own duration, and that costs one picture; this costs
         * everything up to the next keyframe, so it only fires when the decoder is losing the race
         * rather than merely wobbling.
         */
        const val LATE_BEFORE_DECODE_US: Long = 500_000L

        /** Overlay canvas for subtitles on audio-only media, where no video size exists. */
        /** An ASS event whose container declares no duration holds five seconds, libass' own habit. */
        const val TYPESET_DEFAULT_HOLD_MICROS: Long = 5_000_000L

        /** No render cadence faster than 100 frames a second, whatever the container claims. */
        const val TYPESET_MIN_INTERVAL_MICROS: Long = 10_000L

        const val DEFAULT_SUBTITLE_CANVAS_WIDTH: Int = 1280
        const val DEFAULT_SUBTITLE_CANVAS_HEIGHT: Int = 720

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

        /**
         * How long a single frame step waits for its frame.
         *
         * Far shorter than an open's budget, because the frame it wants has already been decoded
         * and is sitting in the queue: anything that takes longer than this means the queue is
         * empty, which at the end of the media is the honest answer rather than something to wait
         * ten seconds for.
         */
        val STEP_DEADLINE: Duration = 1.seconds

        /** How long a seek prefers the video side's answer before it accepts the audio side's. */
        val LANDING_GRACE: Duration = 500.milliseconds

        /** How long the device is given to play out what it holds. */
        val DRAIN_DEADLINE: Duration = 5.seconds

        /** How often the gapless handoff looks again while the ring carries the join. */
        val HANDOFF_POLL: Duration = 10.milliseconds

        /** The least of the current item the ring must hold while the handoff waits for the next one. */
        val HANDOFF_MARGIN: Duration = 40.milliseconds

        /**
         * The least time before B that a turn of an A-B loop needs for its next pass to open,
         * unless the section is shorter (#467). A turn that starts nearer B goes back by the seek.
         */
        val MIN_PASS_LEAD: Duration = 1.seconds


        /** How far playback runs past an estimated length before the snapshot's length follows it (#422). */
        const val DURATION_FOLLOW_STEP_US: Long = 1_000_000L

        /** Late drops in one stats interval that make dropping worth SAYING, not just counting. */
        const val FRAME_DROP_WARN_PER_INTERVAL: Long = 5L

        /** How long teardown may take before close reports a compromised runtime. */
        val CLOSE_DEADLINE: Duration = 10.seconds

        /** Wake soon enough that a frame due in the next period is not missed. */
        val FRAME_WAKE: Duration = 5.milliseconds

        val COALESCE_WINDOW: Duration = SeekTiming.COALESCE_WINDOW_US.microseconds

        const val NO_POSITION: Long = Long.MIN_VALUE

        const val SCHEDULER_IDLE = 0
        const val SCHEDULER_ONE_FRAME = 1
        const val SCHEDULER_RUNNING = 2

        /** Warnings kept for the dump: enough for a session's story, bounded by contract. */
        const val WARNING_HISTORY_LIMIT = 64


        const val DEMUX_WORKER = "demux"
        const val VIDEO_DECODE_WORKER = "video decode"
        const val AUDIO_DECODE_WORKER = "audio decode"
        const val AUDIO_FEED_WORKER = "audio feed"
        const val VIDEO_SCHEDULE_WORKER = "video schedule"

        /** Not a worker: the sink reporting that it cannot play again, through the same channel. */
        const val AUDIO_DEVICE = "audio device"
    }
    // ---------------------------------------------------------------------------------------------
    // The actor, started LAST.
    //
    // Kotlin runs property initializers in declaration order, and this one starts a coroutine on
    // another thread that reads fields declared thousands of lines above. When it sat near the top
    // of the class the actor could run before the constructor had initialised the rest, and on a
    // three-core CI runner it did: the first pass touched a still-null atomic, the actor died inside
    // <init>, and its completion hook reported a compromised close before open was ever called.
    // Nothing below this declaration may start work on another thread.
    // ---------------------------------------------------------------------------------------------

    private val actor: Job = scope.async { runLoop() }.also { job ->
        job.invokeOnCompletion { cause ->
            // This hook runs only after the actor body has returned. Closing its dispatcher from inside
            // runClose would make the native WorkerDispatcher wait on its own termination forever.
            launchCloseFinalizer(cause)
        }
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

/** How long playback may wait for data before an HLS stream steps down a variant (#376). */
private val VARIANT_STEP_DOWN_AFTER: Duration = 4.seconds

/** How much media the read rate must cover before a step down trusts it (#376). */
private val VARIANT_STEP_DOWN_SAMPLE: Duration = 6.seconds

/** How far the link must exceed a lower variant's bitrate for a step down to choose it. */
private const val VARIANT_FIT_HEADROOM: Double = 1.2

/** How long a step up waits after a step down (#376). Doubled after each step up that did not last. */
private val VARIANT_STEP_UP_WAIT: Duration = 30.seconds

/** The longest wait between a step down and the next step up. */
private val VARIANT_STEP_UP_WAIT_MAX: Duration = 5.minutes

/** How far the link must exceed the higher bitrate: 1.5 leaves a third of it spare. */
private const val VARIANT_STEP_UP_HEADROOM: Double = 1.5

// Following an external clock (#91). The thresholds are the starting values of the design.
private const val EXTERNAL_ASK_INTERVAL_NANOS: Long = 50_000_000L
private const val EXTERNAL_DEADBAND_US: Long = 2_000L
private const val EXTERNAL_SEEK_US: Long = 150_000L
private const val EXTERNAL_SEEK_COOLDOWN_NANOS: Long = 1_000_000_000L
private const val EXTERNAL_SEEK_LATENCY_CAP_NANOS: Long = 2_000_000_000L
private const val EXTERNAL_MOVED_US: Long = 1_000L
private const val EXTERNAL_SILENT_NANOS: Long = 2_000_000_000L
private const val EXTERNAL_TRIM_STEP: Double = 0.0005

// Holding the delay behind a live sender (#395).
/** How much faster than the caller's speed the player catches up: a second of delay in ten. */
private const val LIVE_CATCH_UP_SPEED: Double = 1.1

/** How far past the ready duration the delay may grow before the player catches up. */
private const val LIVE_CATCH_UP_ABOVE_US: Long = 500_000L

/** How close to the ready duration the catching up brings the delay. */
private const val LIVE_CATCH_UP_UNTIL_US: Long = 100_000L

/** How often the delay is looked at: often enough that catching up overshoots by 25 ms at most. */
private const val LIVE_DELAY_CHECK_NANOS: Long = 250_000_000L

/**
 * Which stream of a kind a session should use.
 *
 * Three cases and not two, because a null track id from a caller means "none" and the absence of a request
 * means "choose the default". Collapsing those two into one null is how a player ends up unable to turn its
 * own audio off.
 */
/**
 * The stream a choice names, and a warning when the media no longer has it.
 *
 * One function rather than three copies of the same `when`, because the three copies is how the
 * hole stayed open: [StreamChoice.None] and an [StreamChoice.At] naming a stream that is gone both
 * answered null, sitting one line apart, and neither said anything. A caller who switched
 * subtitles off and a rebuild whose subtitle stream vanished looked identical from every angle.
 *
 * The missing index is a WARNING and not a refusal. Nothing reaches here that a caller invented:
 * `selectTrack` is validated against the live track set before it can become a choice, so an
 * [StreamChoice.At] is either that validated id or an index the engine read off the session it is
 * rebuilding. When it goes missing the caller made no mistake and the media changed underneath.
 *
 * @param auto what to pick when the choice is [StreamChoice.Auto]. Its own empty answer stays
 *   silent: media with no stream of a kind is ordinary, and warning about it would fire on most
 *   files.
 */
internal fun resolveStreamChoice(
    choice: StreamChoice,
    streams: List<PlayerStreamInfo>,
    kind: TrackKind,
    warn: (PlaybackWarning) -> Unit,
    auto: () -> PlayerStreamInfo?,
): PlayerStreamInfo? = when (choice) {
    StreamChoice.None -> null
    StreamChoice.Auto -> auto()
    is StreamChoice.At -> streams.firstOrNull { it.index == choice.index }
        ?: run {
            warn(
                PlaybackWarning.TrackDeselected(
                    TrackId(choice.index),
                    "this media has no $kind stream at index ${choice.index} any more, " +
                        "so the selection carried into this open was dropped",
                ),
            )
            null
        }
}

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

    /** When the audio lane was first asked to push its DSP tail out, so that wait is bounded too. */
    var tailRequestedNanos: Long = 0

    /** The tail wait was bounded out rather than answered, so the tail is gone and it was said. */
    var tailAbandoned: Boolean = false

    /** The decoders are done and the device is playing out what it already holds. */
    var draining: Boolean = false

    /** When [draining] flipped, so the wait for the ring to empty is bounded. */
    var drainStartedNanos: Long = 0

    /** The device has finished, or its drain was bounded out. */
    var sinkDrained: Boolean = false

    /** The drain was bounded out rather than finishing, which is a device that went away. */
    var drainFailed: Boolean = false

    /** The last frame stays on the screen, so a finished file looks finished rather than black. */
    var keepOpen: Boolean = false

    /** When the last cue stops being due, worked out once. Zero before that, and bounded. */
    var subtitleTailUntilNanos: Long = 0

    /** The subtitle lane has had its time, so the end is not held for it again. */
    var subtitleTailDone: Boolean = false

    fun reset() {
        demuxerEnded = false
        audioDecoderDrained = false
        videoDecoderDrained = false
        draining = false
        drainStartedNanos = 0
        tailRequestedNanos = 0
        tailAbandoned = false
        sinkDrained = false
        drainFailed = false
        keepOpen = false
        subtitleTailUntilNanos = 0
        subtitleTailDone = false
    }
}

/** What happened to one seek request. */
internal sealed interface SeekResult {
    /** The seek ran and this is where it landed. */
    data class Applied(val landedAt: Pts) : SeekResult

    /** A later request absorbed this one before it ran. Not a failure: the position moved anyway. */
    data class Superseded(val by: Generation) : SeekResult

    /**
     * The seek was aborted before mutating anything, because its precondition could not be
     * established: a worker did not reach a quiescent boundary within the deadline. Flushing
     * decoders and clearing queues under a live worker is the memory-safety fault the audit
     * named, so the transaction refuses instead of proceeding on best effort.
     */
    data class Rejected(val reason: String) : SeekResult
}

/** Marks the only worker failures safe for renderer-hardware recovery. */
private class VideoDecoderRuntimeFailure(
    val operation: String,
    cause: Throwable,
) : RuntimeException("video decoder $operation failed: ${cause.message ?: cause::class.simpleName}", cause)

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
internal class WorkerOutcome(val sessionToken: Long, val name: String, val cause: Throwable?)

/**
 * Interleaves a decoded buffer into what the ring wants, into one array reused across buffers.
 *
 * The array grows to the largest buffer seen and is reused after that. The conversion stage behind
 * the ring copies what it is given, so handing the same array over again is safe. The buffer
 * writes the layout itself through [AudioBuffer.copyInterleaved], which the FFmpeg backend decodes
 * straight into; a planar scratch here meant every sample was copied twice more.
 */
internal class Interleaver {
    private var interleaved = FloatArray(0)

    fun interleave(buffer: AudioBuffer): FloatArray {
        val values = buffer.frameCount * buffer.format.channels
        if (interleaved.size < values) interleaved = FloatArray(values)
        buffer.copyInterleaved(interleaved)
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

/** The actor's immutable terminal handoff, read only by its completion finalizer. */
private data class TerminalCloseOutcome(
    val reply: CompletableDeferred<Unit>,
    val failure: PlaybackException?,
)

/**
 * Every message the actor accepts.
 *
 * Ordinary awaited commands carry one reply each, fire-and-forget commands omit or discard theirs, and
 * the sole Close command carries the terminal result shared by every close route.
 */
internal sealed class CoreCommand(val name: String, private val deferred: CompletableDeferred<*>) {

    fun fail(cause: Throwable) {
        deferred.completeExceptionally(cause)
    }

    class Open(val media: MediaItem, val reply: CompletableDeferred<Unit>) : CoreCommand("open", reply)

    class OpenQueue(
        items: List<MediaItem>,
        val startIndex: Int,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("openQueue", reply) {
        /** A copy taken as the command is made, so the caller's list is its own again at once (#409). */
        val items: List<MediaItem> = items.toList()
    }

    class QueueNext(val reply: CompletableDeferred<Unit>) : CoreCommand("queueNext", reply)
    class QueuePrevious(val reply: CompletableDeferred<Unit>) : CoreCommand("queuePrevious", reply)

    class EditQueue(val edit: QueueEdit, val reply: CompletableDeferred<Unit>) :
        CoreCommand(edit.name, reply)

    class SetShuffle(
        val enabled: Boolean,
        val seed: Long?,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("setShuffle", reply)

    class RestoreQueueOrder(val order: List<Int>, val reply: CompletableDeferred<Unit>) :
        CoreCommand("restoreQueueOrder", reply)

    class SetMarkers(val markers: List<Marker>, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setMarkers", reply)

    class StepFrame(
        val direction: StepDirection,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("stepFrame", reply)

    class CaptureFrame(
        val reply: CompletableDeferred<io.github.yuroyami.kiteplayer.CapturedFrame>,
    ) : CoreCommand("captureFrame", reply)

    /**
     * Withdraws one abandoned [CaptureFrame] arm.
     *
     * Fire and forget, and identity-matched: a capture whose caller went away must clear its own
     * request and leave a newer one that already replaced it armed.
     */
    class WithdrawCapture(
        val request: CompletableDeferred<io.github.yuroyami.kiteplayer.CapturedFrame>,
    ) : CoreCommand("withdrawCapture", CompletableDeferred(Unit))
    class StartRecording(val path: String, val reply: CompletableDeferred<Unit>) : CoreCommand("startRecording", reply)
    class StopRecording(val reply: CompletableDeferred<Unit>) : CoreCommand("stopRecording", reply)
    class Play(val reply: CompletableDeferred<Unit>) : CoreCommand("play", reply)
    class Pause(val reply: CompletableDeferred<Unit>) : CoreCommand("pause", reply)
    class Seek(val request: SeekRequest, val reply: CompletableDeferred<SeekResult>) : CoreCommand("seek", reply)
    /**
     * Stops whatever is open, or, with an [owner], only the session the request that [owner] names
     * built. A cancelled open posts the second kind, so a cancel that arrives late cannot stop a
     * newer item that another call opened in between (#410).
     */
    class Stop(val reply: CompletableDeferred<Unit>, val owner: Any? = null) : CoreCommand("stop", reply)
    class Close(val reply: CompletableDeferred<Unit>) : CoreCommand("close", reply)
    class SetSpeed(val value: Double, val reply: CompletableDeferred<Unit>) : CoreCommand("setSpeed", reply)
    class SetVolume(val value: Float, val reply: CompletableDeferred<Unit>) : CoreCommand("setVolume", reply)
    class SetBalance(val value: Float, val reply: CompletableDeferred<Unit>) : CoreCommand("setBalance", reply)
    class SetVideoEnabled(val value: Boolean, val reply: CompletableDeferred<Unit>) : CoreCommand("setVideoEnabled", reply)
    class SetEqualizer(val settings: EqualizerSettings, val reply: CompletableDeferred<Unit>) : CoreCommand("setEqualizer", reply)
    class SetSleepTimer(
        val timer: SleepTimer?,
        val fade: Duration,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("setSleepTimer", reply)
    class SetMuted(val value: Boolean, val reply: CompletableDeferred<Unit>) : CoreCommand("setMuted", reply)
    class SetDuckLevel(val level: Float, val reply: CompletableDeferred<Unit>) : CoreCommand("setDuckLevel", reply)
    class SetLoop(val mode: LoopMode, val reply: CompletableDeferred<Unit>) : CoreCommand("setLoop", reply)
    class SetAbLoop(val a: Duration?, val b: Duration?, val reply: CompletableDeferred<Unit>) : CoreCommand("setAbLoop", reply)
    class SetPreservePitch(val value: Boolean, val reply: CompletableDeferred<Unit>) : CoreCommand("setPreservePitch", reply)
    class SetVideoScale(val mode: VideoScale, val reply: CompletableDeferred<Unit>) : CoreCommand("setVideoScale", reply)
    class SetRenderQuality(val value: io.github.yuroyami.kiteplayer.RenderQuality, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setRenderQuality", reply)
    class SetHdrPolicy(val value: io.github.yuroyami.kiteplayer.HdrPolicy, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setHdrPolicy", reply)
    class SetExternalClock(val value: io.github.yuroyami.kiteplayer.ExternalClock?, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setExternalClock", reply)
    class ReportDynamicRange(val value: io.github.yuroyami.kiteplayer.VideoDynamicRange, val reply: CompletableDeferred<Unit>) :
        CoreCommand("reportDynamicRange", reply)
    class SetVideoAdjustments(val value: VideoAdjustments, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setVideoAdjustments", reply)
    class SetVideoTransform(val value: VideoTransform, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setVideoTransform", reply)
    class SetSubtitleDelay(val value: Duration, val reply: CompletableDeferred<Unit>) : CoreCommand("setSubtitleDelay", reply)
    class SetSubtitleScale(val value: Float, val reply: CompletableDeferred<Unit>) : CoreCommand("setSubtitleScale", reply)
    class SetSubtitleStyle(
        val value: io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride?,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("setSubtitleStyle", reply)
    class SetSubtitlePosition(val value: Float, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setSubtitlePosition", reply)
    class SetSubtitleSafeArea(
        val value: io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("setSubtitleSafeArea", reply)
    class SetAudioDelay(val value: Duration, val reply: CompletableDeferred<Unit>) : CoreCommand("setAudioDelay", reply)
    class AddExternalSubtitle(val source: SubtitleSource, val reply: CompletableDeferred<TrackId>) :
        CoreCommand("addExternalSubtitle", reply)
    class ReloadExternalSubtitle(val track: TrackId, val encoding: String?, val reply: CompletableDeferred<Unit>) :
        CoreCommand("reloadExternalSubtitle", reply)

    /**
     * The answer of an [AddExternalSubtitle]'s or a [ReloadExternalSubtitle]'s read, back on the
     * actor, where [adopt] adds the track or swaps in its new reading (#412, #515). Posted by the
     * read's own task, never by a caller, so its reply is a completed placeholder; the caller's reply
     * is the one the read carries.
     */
    class ExternalSubtitleRead(val adopt: suspend () -> Unit) :
        CoreCommand("addExternalSubtitle", CompletableDeferred(Unit))

    /**
     * The demux lane saw the source's streams or programmes change after the open (#509). Sent by
     * the lane, never by a caller, so its reply is a completed placeholder; [adopt] runs on the actor.
     */
    class StreamsChanged(val adopt: suspend () -> Unit) :
        CoreCommand("streamsChanged", CompletableDeferred(Unit))

    class SelectSecondarySubtitle(
        val track: TrackId?,
        val reply: CompletableDeferred<TrackChange>,
    ) : CoreCommand("selectSecondarySubtitle", reply)

    class SelectTrack(
        val kind: TrackKind,
        val track: TrackId?,
        val reply: CompletableDeferred<TrackChange>,
    ) : CoreCommand("selectTrack", reply)

    class SelectVariant(
        val index: Int?,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("selectVariant", reply)

    class SelectProgram(
        val number: Int?,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("selectProgram", reply)

    class AttachRenderer(
        val renderer: VideoRenderer,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("attachRenderer", reply)

    class DetachRenderer(
        val expected: VideoRenderer?,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("detachRenderer", reply)

    class AttachAudioTap(val tap: AudioTap, val reply: CompletableDeferred<Unit>) : CoreCommand("attachAudioTap", reply)

    class DetachAudioTap(val tap: AudioTap, val reply: CompletableDeferred<Unit>) : CoreCommand("detachAudioTap", reply)

    /** Fire and forget by contract, so its reply is complete before it is sent. */
    class SeekLater(val request: SeekRequest) : CoreCommand("seekLater", CompletableDeferred(Unit))
}

/** The tracks a source declares, as the player's own value type. */
/**
 * Reads what an open would publish, and plays nothing.
 *
 * Opening a backend session builds no decoder: the session carries decoder FACTORIES, which are
 * lists rather than devices, so opening and closing one straight away is exactly a probe and costs
 * a container read. That is why this needs nothing new from a backend.
 */
internal suspend fun inspectMedia(backend: MediaBackend, media: MediaItem): MediaInspection =
    // A backend open may block its thread, so it never runs on the caller's (#202).
    withContext(blockingWorkDispatcher) {
        val session = try {
            backend.open(media)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: PlaybackException) {
            throw failure
        } catch (failure: Throwable) {
            // Typed as open types a failure while the source opens, never the backend's own type.
            throw PlaybackException(PlaybackError.SourceUnavailable(media.uri, failure, failure.message))
        }
        readInspection(session)
    }

private fun readInspection(session: BackendSession): MediaInspection {
    try {
        val source = session.source
        return MediaInspection(
            duration = source.duration?.let { it.micros.microseconds },
            tracks = source.toTracks(),
            metadata = source.metadata,
            chapters = source.chapters,
            seekable = source.seekable,
            containerBitrateBps = source.containerBitrateBps,
        )
    } finally {
        session.close()
    }
}

/**
 * These tracks with the source's streams and programmes as they stand after the open (#509), when
 * [listed] and [programs] give them. The rows of external files stay, after the container's, and so
 * does every selection.
 */
private fun Tracks.withLayout(listed: List<PlayerStreamInfo>?, programs: List<MediaProgram>?): Tracks = copy(
    all = if (listed == null) all else listed.toTracks().all + all.filter { it.id.value < 0 },
    programs = programs ?: this.programs,
)

/** The track table of this source, with its variants and programmes. */
private fun io.github.yuroyami.kiteplayer.spi.PlayerMediaSource.toTracks(): Tracks =
    streams.toTracks().copy(variants = variants, selectedVariant = selectedVariant, programs = programs)

/**
 * The programme an open picks its tracks from (#505): the one [asked] names when the media has it,
 * otherwise the first whose tracks hold a picture that is not cover art, otherwise the first with
 * any track. Null when the media has no programme with a track.
 */
internal fun chooseProgram(programs: List<MediaProgram>, streams: List<PlayerStreamInfo>, asked: Int?): MediaProgram? {
    programs.firstOrNull { it.number == asked }?.let { return it }
    val pictures = streams.filter { it.kind == TrackKind.Video && !it.isCoverArt }.mapTo(HashSet()) { it.index }
    return programs.firstOrNull { program -> program.tracks.any { it.value in pictures } }
        ?: programs.firstOrNull { it.tracks.isNotEmpty() }
}

/**
 * The streams the automatic choices pick from (#505). With two or more [programs], those are the
 * streams of [program] and, for a kind it has none of, the streams that sit in no programme, which
 * is how FFmpeg lists a stream it found by its packets rather than in a programme table. A stream
 * that only another programme holds is never one, so a multiplex never plays one channel's picture
 * with another channel's sound. FFmpeg's own player keeps its sound to its picture's programme the
 * same way, through `av_find_best_stream`. With one programme or none, every stream is one, as
 * before.
 */
internal fun programCandidates(
    streams: List<PlayerStreamInfo>,
    programs: List<MediaProgram>,
    program: MediaProgram?,
): List<PlayerStreamInfo> {
    if (program == null || programs.size < 2) return streams
    val inside = program.tracks.mapTo(HashSet()) { it.value }
    val placed = programs.flatMapTo(HashSet()) { other -> other.tracks.map { it.value } }
    val kindsInside = streams.filter { it.index in inside }.mapTo(HashSet()) { it.kind }
    return streams.filter { it.index in inside || (it.kind !in kindsInside && it.index !in placed) }
}

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
            videoSize = stream.visibleVideoSize,
            frameRate = stream.frameRate,
            sampleRate = stream.sampleRate,
            channels = stream.channels,
            isCoverArt = stream.isCoverArt,
            metadata = stream.metadata,
            dolbyVision = stream.dolbyVision,
        )
    },
)

/**
 * The audio stream an open picks when nothing was chosen, shared with audio scans.
 *
 * Ordinary tracks outrank descriptive/accessibility ones at every tier: a described track is
 * opt-in listening, not the default face of the media. The sort is stable, so container order
 * still decides inside each rank, and an accessibility track remains reachable when it is the only
 * candidate. A language preference still outranks everything, ordinary-first within the language.
 */
internal fun pickAudioStream(
    streams: List<PlayerStreamInfo>,
    preferredLanguages: List<String>,
    outputChannels: Int? = null,
): PlayerStreamInfo? {
    val audio = streams.filter { it.kind == TrackKind.Audio }
    if (audio.isEmpty()) return null
    val chosen = pickAudioByLanguage(audio, preferredLanguages)
    return if (outputChannels == null) chosen else closerMixOf(chosen, audio, outputChannels)
}

/**
 * [chosen], or a track that is the same language and accessibility as it and whose channel count is
 * closer to [outputChannels] (#466). The first closest in container order wins, and [chosen] wins any
 * tie, so a file with one mix per language plays as before. A commentary never stands in for the
 * main mix, and a track that does not say how many channels it has is never one.
 */
private fun closerMixOf(chosen: PlayerStreamInfo, audio: List<PlayerStreamInfo>, outputChannels: Int): PlayerStreamInfo {
    val chosenChannels = chosen.channels ?: return chosen
    fun distance(channels: Int) = kotlin.math.abs(channels - outputChannels)
    fun isCommentary(stream: PlayerStreamInfo) =
        stream.isCommentary || stream.title?.contains("comment", ignoreCase = true) == true
    if (isCommentary(chosen)) return chosen
    val sameLanguage: (PlayerStreamInfo) -> Boolean = if (chosen.language.isNullOrBlank()) {
        { it.language.isNullOrBlank() }
    } else {
        { sameLanguage(it.language, chosen.language) }
    }
    return audio
        .filter { it !== chosen && it.isAccessibility == chosen.isAccessibility && !isCommentary(it) && sameLanguage(it) }
        .mapNotNull { stream -> stream.channels?.let { stream to distance(it) } }
        .filter { it.second < distance(chosenChannels) }
        .minByOrNull { it.second }
        ?.first ?: chosen
}

private fun pickAudioByLanguage(audio: List<PlayerStreamInfo>, preferredLanguages: List<String>): PlayerStreamInfo {
    val ranked = audio.sortedBy { it.isAccessibility }
    // Codes are compared as languages, not as spellings, so `ja` finds a `jpn` track (#435). The
    // first preference that any track matches decides; ordinary tracks still come first inside it,
    // and then the track whose script and region agree with the preference.
    val preferences = LanguagePreferences(preferredLanguages)
    if (!preferences.isEmpty) {
        ranked.mapNotNull { stream -> preferences.match(stream.language, stream.title)?.let { stream to it } }
            .sortedWith(compareBy({ it.second.preference }, { it.first.isAccessibility }, { -it.second.closeness }))
            .firstOrNull()?.let { return it.first }
    }
    return ranked.firstOrNull { it.isDefault && !it.isAccessibility }
        ?: ranked.firstOrNull { !it.isAccessibility }
        ?: ranked.firstOrNull { it.isDefault }
        ?: ranked.first()
}

/**
 * The subtitle stream an open picks when nothing was chosen, per [config] and the container's
 * dispositions: an accessibility track in a preferred language wins, then any preferred-language
 * track, closest to the preference and then default-flagged first; then, when the audio is not in a
 * preferred language and the config allows it, a forced track in a preferred language, and a forced
 * track in the audio's own language whatever the preferences. Then, when [SubtitleConfig.autoSelect]
 * asks for it, the plain default. Under audio in a preferred language,
 * [SubtitleConfig.withMatchingAudio] may leave only the forced tracks to choose from, or none.
 *
 * Languages are compared as languages, so `ja` finds a `jpn` track and `en` audio pairs with an
 * `eng` forced track, and one rule serves the preference, the audio and the forced pairing (#435).
 */
internal fun pickSubtitleStream(
    streams: List<PlayerStreamInfo>,
    audio: PlayerStreamInfo?,
    config: SubtitleConfig,
): PlayerStreamInfo? {
    val preferences = LanguagePreferences(config.preferredLanguages)
    val subtitles = streams.filter { it.kind == TrackKind.Subtitle }
        .choosableWith(audio, config, preferences) { it.isForced }
    if (subtitles.isEmpty()) return null
    fun best(candidates: List<PlayerStreamInfo>): PlayerStreamInfo? =
        candidates.mapNotNull { stream -> preferences.match(stream.language, stream.title)?.let { stream to it } }
            .sortedWith(compareBy({ it.second.preference }, { -it.second.closeness }, { !it.first.isDefault }))
            .firstOrNull()?.first
    if (!preferences.isEmpty) {
        best(subtitles.filter { it.isAccessibility })?.let { return it }
        best(subtitles)?.let { return it }
    }
    if (config.autoSelectForced) {
        val audioLanguage = audio?.language
        val audioPreferred = preferences.matches(audioLanguage)
        if (!preferences.isEmpty && !audioPreferred) {
            best(subtitles.filter { it.isForced })?.let { return it }
        }
        // A forced track is authored for the viewers of this audio: foreign lines inside
        // audio they otherwise understand. That pairing holds with no language preference
        // configured at all, so it must not hide behind preferredLanguages.
        if (audioLanguage != null) {
            subtitles.firstOrNull { it.isForced && sameLanguage(it.language, audioLanguage) }
                ?.let { return it }
        }
    }
    // The plain default: subtitled media shows its subtitles. Default-flagged first, and a
    // forced-only track never wins here, because forced tracks exist for foreign lines
    // inside otherwise-understood audio, not as the default face of the media.
    if (config.autoSelect) {
        subtitles.filter { !it.isForced }.sortedByDescending { it.isDefault }.firstOrNull()
            ?.let { return it }
    }
    return null
}

/**
 * The external subtitle track to select at open over [container], the container's own choice, or
 * null to keep that (#514). An external file wins when it matches the language preferences and the
 * container's choice matches a later one, matches the same one no more closely, or matches none: a
 * file the caller added for this item in the language the viewer prefers was added to be seen. With
 * no preferences, the container's choice stands.
 */
internal fun preferredExternalSubtitle(
    container: PlayerStreamInfo?,
    externals: List<TrackInfo>,
    audio: PlayerStreamInfo?,
    config: SubtitleConfig,
): TrackInfo? {
    val preferences = LanguagePreferences(config.preferredLanguages)
    if (preferences.isEmpty) return null
    val (track, match) = externals.choosableWith(audio, config, preferences) { it.isForced }.mapNotNull { track -> preferences.match(track.language, track.title)?.let { track to it } }
        .sortedWith(compareBy({ it.second.preference }, { -it.second.closeness }, { it.first.isForced }))
        .firstOrNull() ?: return null
    val ours = container?.let { preferences.match(it.language, it.title) } ?: return track
    return when {
        match.preference < ours.preference -> track
        match.preference == ours.preference && match.closeness >= ours.closeness -> track
        else -> null
    }
}

/**
 * The subtitle tracks the player may choose by itself under [audio] (#506). Audio in a preferred
 * language is audio the viewer understands, and under it [SubtitleConfig.withMatchingAudio] keeps
 * only the forced tracks, or none, as mpv's `subs-with-matching-audio` does. Under any other audio
 * every track stays.
 */
private inline fun <T> List<T>.choosableWith(
    audio: PlayerStreamInfo?,
    config: SubtitleConfig,
    preferences: LanguagePreferences,
    isForced: (T) -> Boolean,
): List<T> = when {
    config.withMatchingAudio == MatchingAudioSubtitles.All || !preferences.matches(audio?.language) -> this
    config.withMatchingAudio == MatchingAudioSubtitles.ForcedOnly -> filter(isForced)
    else -> emptyList()
}

/** Where a seek on this source is cut: its length, unless that length is only an estimate (#422). */
internal val PlayerMediaSource.seekCeiling: Pts? get() = if (durationIsEstimate) null else duration

/**
 * One edit to the open queue.
 *
 * They travel as a single command so that the items and the cursor into them can only be read
 * together: a snapshot taken between the two halves of an edit would name the wrong item.
 */
internal sealed interface QueueEdit {
    /** The public call this edit came from, used in refusals and diagnostics. */
    val name: String

    data class Add(val items: List<MediaItem>, val index: Int?) : QueueEdit {
        override val name: String get() = "addToQueue"
    }

    data class Remove(val index: Int) : QueueEdit {
        override val name: String get() = "removeFromQueue"
    }

    data class Move(val from: Int, val to: Int) : QueueEdit {
        override val name: String get() = "moveInQueue"
    }

    data object Clear : QueueEdit {
        override val name: String get() = "clearQueue"
    }
}

/**
 * A list that refuses every edit, also through the `java.util.List` a JVM caller sees, where a
 * plain read-only Kotlin list still has a working `remove`.
 */
private class ReadOnlyList<T>(private val items: List<T>) : AbstractList<T>() {
    override val size: Int get() = items.size

    override fun get(index: Int): T = items[index]
}
