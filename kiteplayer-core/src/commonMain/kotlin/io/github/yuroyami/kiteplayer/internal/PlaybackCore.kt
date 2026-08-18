// onTimeout is the select clause that makes an actor's wait cancellation free. Its alternative, a
// timeout wrapped around a receive, can consume a message and then be cancelled, which loses a command
// and suspends its caller for ever.
@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.AudioPlayback
import io.github.yuroyami.kiteplayer.chapterHolding
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MasterClock
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.SubtitleSource
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackStats
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.TimedWarning
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.SyncMode
import io.github.yuroyami.kiteplayer.TrackChange
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
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
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.subtitle.CueSelector
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

    // Actor-confined state until the actor returns. The close finalizer then owns status, lastError and
    // the one terminal snapshot exclusively; every other field is immutable to it.
    private var status: PlaybackStatus = PlaybackStatus.Idle
    private var media: MediaItem? = null

    /** The queue (S4.e): the items and the cursor. Empty and -1 outside queue playback. */
    private var queueItems: List<MediaItem> = emptyList()
    private var queueIndex: Int = -1

    /** The chapter the last ChapterChanged named, as an index; MIN_VALUE forces the first emit. */
    private var lastChapterIndex: Int = Int.MIN_VALUE

    /** One parsed external subtitle file (S4.e): a synthetic track and its ready cue table. */
    private class ExternalSubtitleTrack(
        val id: TrackId,
        val info: TrackInfo,
        val cues: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
    )

    /** The media item's parsed external subtitle files, in declaration order. */
    private var externalSubtitleTracks: List<ExternalSubtitleTrack> = emptyList()

    /** The external track currently timing cues, or null when none is. */
    private var selectedExternalSubtitle: TrackId? = null

    /** An external selection waiting for handleTrackChanges to finish its container rebuild. */
    private var pendingExternalSubtitle: TrackId? = null

    private fun isExternalSubtitle(track: TrackId?): Boolean =
        track != null && externalSubtitleTracks.any { it.id == track }

    /**
     * Parses the media item's external subtitle files (S4.e): each becomes a selectable
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
     * worse than the defect it was fixing (audit KP-P1-20). Nothing here touches the session.
     */
    private fun parseExternalSubtitles(item: MediaItem): List<ExternalSubtitleTrack> =
        item.externalSubtitles.mapIndexedNotNull { index, sourceFile ->
            // TrackId's own convention: external ids are negative, printed external1, external2...
            val id = TrackId(-(index + 1))
            when (val parsed = parseExternalSubtitle(sourceFile, id)) {
                is ExternalSubtitleParse.Loaded -> parsed.track
                is ExternalSubtitleParse.Failed -> {
                    warn(PlaybackWarning.TrackDeselected(id, parsed.reason))
                    null
                }
            }
        }

    /** Merges what [parseExternalSubtitles] read into the session's track table. */
    private fun adoptExternalSubtitles(item: MediaItem, parsed: List<ExternalSubtitleTrack>) {
        // Every DECLARED file mints an id, loaded or not (F-EXT1): the count of loaded tracks
        // used to seed addExternalSubtitle's next id, which collided with a declared track as
        // soon as one earlier declaration had failed to load.
        externalSubtitleIdsMinted = item.externalSubtitles.size
        externalSubtitleTracks = parsed
        if (parsed.isNotEmpty()) {
            tracks = tracks.copy(all = tracks.all + parsed.map { it.info })
        }
    }

    private sealed interface ExternalSubtitleParse {
        class Loaded(val track: ExternalSubtitleTrack) : ExternalSubtitleParse
        class Failed(val reason: String) : ExternalSubtitleParse
    }

    /** One external subtitle file to one synthetic track, or the sentence saying why not. */
    private fun parseExternalSubtitle(sourceFile: SubtitleSource, id: TrackId): ExternalSubtitleParse {
        if (sourceFile.io != null) {
            return ExternalSubtitleParse.Failed(
                "custom subtitle IO is not wired; external subtitles read local paths (17.8 owns the rest)",
            )
        }
        val parser = backend.subtitleFileParser()
            ?: return ExternalSubtitleParse.Failed(
                "this backend supplies no subtitle file parser, so external files cannot load",
            )
        val text = readExternalTextOrNull(sourceFile.uri)
            ?: return ExternalSubtitleParse.Failed(
                "the external subtitle file could not be read: ${sourceFile.uri}",
            )
        val trimmed = text.removePrefix("﻿")
        val isVtt = trimmed.startsWith("WEBVTT") || sourceFile.uri.endsWith(".vtt", ignoreCase = true)
        // The same self-announcement the backend's parser routes on (F-EXT2): labelling every
        // non-VTT file SubRip told a track list that an ASS script was something it is not.
        val isAss = trimmed.trimStart(' ', '\r', '\n').startsWith("[Script Info]", ignoreCase = true)
        val cues = runCatching { parser.parse(trimmed, isVtt) }.getOrElse { failure ->
            return ExternalSubtitleParse.Failed(
                "the external subtitle file failed to parse: ${sourceFile.uri}${causeDetail(failure)}",
            )
        }
        if (cues.isEmpty()) {
            return ExternalSubtitleParse.Failed(
                "the external subtitle file parsed to no cues: ${sourceFile.uri}",
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
                    language = sourceFile.language,
                    title = sourceFile.title ?: sourceFile.uri.substringAfterLast('/'),
                ),
                cues = cues.sortedBy { cue -> cue.startMicros },
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
        when (val parsed = parseExternalSubtitle(command.source, id)) {
            is ExternalSubtitleParse.Failed -> command.reply.completeExceptionally(
                IllegalArgumentException(parsed.reason),
            )
            is ExternalSubtitleParse.Loaded -> {
                externalSubtitleTracks = externalSubtitleTracks + parsed.track
                tracks = tracks.copy(all = tracks.all + parsed.track.info)
                if (active.subtitleStream != null) {
                    // A container stream is timing cues: route through the same rebuild the
                    // ordinary selection path takes, so one selection owner survives. The caller
                    // is deliberately NOT answered here. It asked for a subtitle to be SHOWING,
                    // and handing it an id while the rebuild that makes that true has not run,
                    // and can still fail the whole player, is the false success the audit named
                    // (KP-P1-02).
                    pendingExternalSubtitle = id
                    val selection = CompletableDeferred<TrackChange>()
                    queueSelection(TrackKind.Subtitle, id, selection)
                    awaitSubtitleAdd(id, selection, command.reply)
                } else {
                    applyExternalSubtitle(id)
                    command.reply.complete(id)
                }
            }
        }
    }

    /**
     * Answers an [addExternalSubtitle] only once the rebuild its selection triggered has landed.
     *
     * Launched on the session dispatcher, which is the actor's own thread, so the rollback below
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
    private fun withdrawExternalSubtitle(id: TrackId) {
        externalSubtitleTracks = externalSubtitleTracks.filterNot { it.id == id }
        tracks = tracks.copy(all = tracks.all.filterNot { it.id == id })
        if (selectedExternalSubtitle == id) {
            selectedExternalSubtitle = null
            session?.subtitleCues?.clear()
            tracks = tracks.withSelection(TrackKind.Subtitle, null)
        }
        if (pendingExternalSubtitle == id) pendingExternalSubtitle = null
        publishSnapshot()
    }

    /** Swaps the timed cue table in place (S4.e): no container reopen, one publish. */
    private fun applyExternalSubtitle(target: TrackId?) {
        val active = session ?: return
        selectedExternalSubtitle = target
        active.subtitleCues.clear()
        target
            ?.let { id -> externalSubtitleTracks.firstOrNull { it.id == id } }
            ?.cues
            ?.let(active.subtitleCues::addAll)
        tracks = tracks.withSelection(TrackKind.Subtitle, target)
        publishSnapshot()
    }
    private var session: OpenSession? = null
    private var tracks: Tracks = Tracks.Empty
    private var lastError: PlaybackError? = null
    private var playRequested = false
    private var loop: LoopMode = LoopMode.Off

    /** Once per media: handleLoop refusing an unseekable repeat runs on every Ended pass. */
    private var loopRefusalWarned = false

    /** Ids ever minted for external subtitle tracks this media, failed loads included (F-EXT1). */
    private var externalSubtitleIdsMinted = 0

    /**
     * The armed A-B loop (S4.g). A player property like [speed]: it survives seeks and reopen,
     * because the caller armed the loop, not the media. With only A armed the loop wraps at the
     * end of the media; with both armed the crossing check in [handlePlaybackTime] owns B.
     */
    private var abLoopA: Duration? = null
    private var abLoopB: Duration? = null
    private var speed: Double = 1.0

    /** Whether speed keeps pitch, seeded from config. A live change rides a precise seek like speed. */
    private var preservePitch: Boolean = config.audio.preservePitch
    private var volume: Float = 1.0f
    private var muted: Boolean = false
    private var videoScale: VideoScale = VideoScale.Fit
    private var videoAdjustments: VideoAdjustments = VideoAdjustments.Identity
    private var videoTransform: VideoTransform = VideoTransform.Identity

    /** Runtime subtitle timing shift, seeded from config. Positive shows cues later. */
    private var subtitleDelay: Duration = config.subtitles.delay

    /** Runtime subtitle size, seeded from config, applied at the next rasterisation. */
    private var subtitleScale: Float = config.subtitles.fontScale

    /**
     * Where the implicit bottom stack anchors, as a fraction of the viewport height (mpv's
     * sub-pos over 100). 1.0 is the ordinary bottom edge; explicitly positioned cues never move.
     */
    private var subtitlePosition: Float = 1f

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
     * which is told so (audit KP-P1-01).
     */
    private val pendingSelections = mutableMapOf<TrackKind, SelectionRequest>()

    /** One caller's track selection, waiting for the rebuild that will honour it. */
    private class SelectionRequest(
        val kind: TrackKind,
        val track: TrackId?,
        val reply: CompletableDeferred<TrackChange>,
    )

    private var pendingVideoRecovery: VideoRecovery? = null
    private var videoRecoveryAttempted: Boolean = false
    private var forceBackendSoftwareForMedia: Boolean = false
    private var nextSessionToken: Long = 1L

    private var demuxUnderrunSeen = false
    private var rebuffers = 0L

    /**
     * The counters of every session this player has finished with (audit KP-P1-21).
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
    private var retiredRefused = 0L
    private var retiredRepeated = 0L
    private var retiredUnderruns = 0L
    private var stillImageShownSinceNanos: Long = 0
    private var stillImageFinished = false
    private var firstFrameSeen = false
    private var openedAtNanos: Long = 0
    private var lastProgressAtNanos: Long = 0
    private var lastStatsAtNanos: Long = 0
    private var lastStatsDecoded: Long = 0

    /* Rising-edge state for the two counter-backed warnings (audit F-WRN1): warned when the
     * counter MOVED this stats interval, so the history records onsets rather than flooding. */
    private var lastStatsUnderruns = 0L
    private var lastStatsDroppedLate = 0L

    /** The deadline this pass may sleep until. Handlers lower it; nothing raises it. */
    private var wakeAtNanos: Long = 0

    /** Read from any thread, so it is published rather than computed on demand. */
    private val publishedPositionMicros = atomic(0L)

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
        Handler("handleQueueAdvance") { handleQueueAdvance() },
        Handler("handleQueuedSeek") { handleQueuedSeek() },
        Handler("publishSnapshot") { publishSnapshotIfDirty() },
        Handler("awaitWork") { awaitWork() },
    )

    /** The declared order, for the test that asserts it against the design. */
    val handlerOrder: List<String> = handlers.map { it.name }

    private val actor: Job = scope.async { runLoop() }.also { job ->
        job.invokeOnCompletion { cause ->
            // This hook runs only after the actor body has returned. Closing its dispatcher from inside
            // runClose would make the native WorkerDispatcher wait on its own termination forever.
            launchCloseFinalizer(cause)
        }
    }

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
        send(CoreCommand.OpenQueue(items, startIndex, reply))
        awaitReply(reply, stopOnCancellation = true)
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
     * Cancellable on its own, like every request that does not own the session (audit KP-P1-04).
     *
     * The step is an ordinary seek that has already been accepted; abandoning the wait abandons
     * the answer, not the position and not the player.
     */
    suspend fun stepFrame() {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.StepFrame(reply))
        awaitReply(reply)
    }

    suspend fun captureFrame(): io.github.yuroyami.kiteplayer.CapturedFrame {
        val reply = CompletableDeferred<io.github.yuroyami.kiteplayer.CapturedFrame>()
        send(CoreCommand.CaptureFrame(reply))
        try {
            return reply.await()
        } catch (cancellation: CancellationException) {
            // A cancelled capture withdraws ITS OWN arm and nothing else. Posting Stop here, the
            // way the session-owning commands do, meant abandoning a screenshot killed playback
            // (audit KP-P1-04). A newer capture that already replaced this arm is left alone.
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

    /** Seeks and returns what happened to this request: it landed, or a later request replaced it. */
    suspend fun seek(to: Pts, mode: SeekMode): SeekResult {
        val reply = CompletableDeferred<SeekResult>()
        maskedSeekTargetMicros.value = to.micros
        send(CoreCommand.Seek(SeekRequest(SeekTarget.Absolute(to), mode), reply))
        return awaitReply(reply)
    }

    /** Fire and forget, coalescing by contract. What a seek bar drag calls sixty times a second. */
    fun seekLater(to: Pts, mode: SeekMode) {
        checkOpenFor("seekLater")
        // The mask is set from this very call, not from the actor's next pass: a fire-and-forget
        // caller polls position() in the gap before the command is drained, and an absolute target
        // needs no session state to name it. A request the drain drops (unseekable source) is
        // cleared one pass later by handlePlaybackTime.
        maskedSeekTargetMicros.value = to.micros
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
        checkOpenFor("seekLater")
        commands.trySend(CoreCommand.SeekLater(SeekRequest(SeekTarget.Relative(offset), mode)))
    }

    /** Seeks to a fraction of the duration. What dragging a seek bar produces. */
    fun seekToFractionLater(fraction: Double, mode: SeekMode) {
        require(fraction.isFinite() && fraction >= 0.0 && fraction <= 1.0) {
            "a seek bar position must be between 0 and 1, was $fraction"
        }
        checkOpenFor("seekLater")
        commands.trySend(CoreCommand.SeekLater(SeekRequest(SeekTarget.Factor(fraction), mode)))
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

    suspend fun addExternalSubtitle(source: SubtitleSource): TrackId {
        val reply = CompletableDeferred<TrackId>()
        send(CoreCommand.AddExternalSubtitle(source, reply))
        return awaitReply(reply)
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

    /** The position as of the last pass, which is never more than the wake floor old; while a seek
     * request is in flight, the newest requested target, which is the timeline the caller asked for. */
    fun position(): Duration {
        val masked = maskedSeekTargetMicros.value
        return (if (masked != NO_SEEK_MASK) masked else publishedPositionMicros.value).microseconds
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

    private fun requestClose() {
        if (!closedNow.compareAndSet(expect = false, update = true)) return
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
        // drop the command silently (audit P1-22). The failed send now completes the reply
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
     * global Stop for one of them is how abandoning a screenshot used to kill playback (audit
     * KP-P1-04).
     */
    private suspend fun <T> awaitReply(reply: CompletableDeferred<T>, stopOnCancellation: Boolean = false): T {
        try {
            return reply.await()
        } catch (cancellation: CancellationException) {
            // The caller went away. Nothing half built may be left behind, and cancellation is never
            // reported as a playback failure.
            if (stopOnCancellation && !closedNow.value) {
                commands.trySend(CoreCommand.Stop(CompletableDeferred()))
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
        return heldCommands.any { it is CoreCommand.Stop || it is CoreCommand.Close }
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
            is CoreCommand.SelectTrack -> when {
                session == null && pendingVideoRecovery == null ->
                    IllegalStateException("selectTrack needs an open media item")
                session != null && session?.source?.seekable != true &&
                    !inPlaceExternalSubtitleChange(command) -> UnsupportedOperationException(
                    "this source is not seekable, so a track switch cannot reopen it and seek back to " +
                        "where playback was; see KPKMP-PAST.md digest 8.3",
                )
                command.kind == TrackKind.Subtitle &&
                    !isExternalSubtitle(command.track) &&
                    session?.backendSession?.subtitleDecoders.isNullOrEmpty() &&
                    pendingVideoRecovery?.subtitleSelectionAvailable != true -> UnsupportedOperationException(
                    "this backend decodes no subtitle format, so a subtitle track cannot be selected",
                )
                // Canonicalized against the active session's own track set BEFORE any mutation:
                // an index of the wrong kind, or one this media does not have, used to silently
                // deselect or rebuild the wrong path (audit P1-6).
                command.track != null && tracks.all.none { it.id == command.track && it.kind == command.kind } ->
                    IllegalArgumentException(
                        "${command.track} is not a ${command.kind} track of the current media; " +
                            "pass an id from tracks.all whose kind matches",
                    )
                else -> null
            }
            is CoreCommand.OpenQueue -> when {
                command.items.isEmpty() -> IllegalArgumentException("openQueue needs at least one item")
                command.startIndex !in command.items.indices -> IllegalArgumentException(
                    "startIndex ${command.startIndex} is outside the queue of ${command.items.size}",
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

    /** True when a subtitle change swaps cue tables in place, needing no container reopen (S4.e). */
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
            command.fail(it)
            return
        }
        when (command) {
            is CoreCommand.Open -> {
                // A plain open is single-media by contract: whatever queue existed is replaced.
                queueItems = emptyList()
                queueIndex = -1
                runOpen(command)
            }
            is CoreCommand.OpenQueue -> {
                queueItems = command.items
                queueIndex = command.startIndex
                runOpen(CoreCommand.Open(command.items[command.startIndex], command.reply))
            }
            is CoreCommand.QueueNext -> jumpQueue(queueIndex + 1, command.reply, "next")
            is CoreCommand.QueuePrevious -> jumpQueue(queueIndex - 1, command.reply, "previous")
            is CoreCommand.StepFrame -> stepOneFrame(command.reply)
            is CoreCommand.CaptureFrame -> requestCapture(command.reply)
            is CoreCommand.WithdrawCapture ->
                session?.video?.captureRequest?.compareAndSet(command.request, null)
            is CoreCommand.Play -> {
                // Idempotent in its own state, and queued rather than refused while opening or seeking:
                // the restart handler applies it as soon as the pipeline can honour it.
                playRequested = true
                // mpv's law (owner report 2026-08-17, F-PLAY1): play at the end IS a restart.
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
            ) queueSeek(command.request, null)
            is CoreCommand.Stop -> {
                runStop()
                command.reply.complete(Unit)
            }
            is CoreCommand.Close -> runClose(command.reply)
            is CoreCommand.SelectTrack -> {
                val externalTarget = command.track?.takeIf { isExternalSubtitle(it) }
                val externalActive = selectedExternalSubtitle != null
                if (command.kind == TrackKind.Subtitle &&
                    (externalTarget != null || (command.track == null && externalActive))
                ) {
                    if (session?.subtitleStream != null) {
                        // A container stream is timing cues: the ordinary rebuild deselects it,
                        // and the external table applies once the new graph stands (S4.e).
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
                if (setRenderer(command.renderer)) command.reply.complete(Unit)
                else {
                    // Warned as well as thrown (audit F-API1): the facade's fire-and-forget form
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
                if (setRenderer(null)) command.reply.complete(Unit)
                else {
                    val reason = "the video scheduler did not quiesce within $QUIESCE_DEADLINE"
                    warn(PlaybackWarning.CommandRefused("detachRenderer", reason))
                    command.reply.completeExceptionally(
                        IllegalStateException("renderer detach aborted: $reason"),
                    )
                }
            }
            is CoreCommand.SetSpeed -> {
                val active = session
                // The refusal is decided BEFORE any pipeline sees the value (audit F-SP1): the
                // old order wrote the rate into both pipelines and refused afterwards, so a
                // later flush promoted a rate the caller was told did not apply. A live change
                // rides a precise seek to the current position: the seek's own flush is the
                // epoch boundary both pipelines apply their new rate at. On an unseekable source
                // there is no such boundary to ride, and pretending the rate changed while every
                // queued sample kept the old one would be a lie.
                if (active != null && command.value != speed && !active.source.seekable) {
                    val reason = "a live speed change re-anchors by precise seek, and this source is not seekable"
                    warn(PlaybackWarning.CommandRefused("setSpeed", reason))
                    command.reply.completeExceptionally(UnsupportedOperationException(reason))
                } else {
                    val failure = runCatching {
                        active?.audio?.speed = command.value
                        active?.video?.speed = command.value
                    }.exceptionOrNull()
                    if (failure != null) {
                        command.reply.completeExceptionally(failure)
                    } else {
                        val changedLive = active != null && command.value != speed
                        speed = command.value
                        if (changedLive) {
                            queueSeek(
                                SeekRequest(SeekTarget.Absolute(currentPosition()), SeekMode.Precise),
                                null,
                            )
                        }
                        command.reply.complete(Unit)
                    }
                }
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
            is CoreCommand.SetSubtitlePosition -> {
                subtitlePosition = command.value
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
            is CoreCommand.SetLoop -> {
                loop = command.mode
                command.reply.complete(Unit)
            }
            is CoreCommand.SetPreservePitch -> {
                val active = session
                when {
                    command.value == preservePitch -> command.reply.complete(Unit)
                    // The same boundary law as SetSpeed, because it IS the same boundary: the
                    // mechanism can only change where the ring is empty, which is a flush, which
                    // a live change reaches by precise seek, which an unseekable source cannot make.
                    active?.audio != null && !active.source.seekable -> {
                        val reason = "a live pitch-law change re-anchors by precise seek, and this source is not seekable"
                        warn(PlaybackWarning.CommandRefused("setPreservePitch", reason))
                        command.reply.completeExceptionally(UnsupportedOperationException(reason))
                    }
                    else -> {
                        preservePitch = command.value
                        active?.audio?.preservePitch = command.value
                        if (active?.audio != null && speed != 1.0) {
                            // Audible only away from 1.0, so the rebuffer is only paid there. At
                            // 1.0 both mechanisms are the same bypass and the flush would buy
                            // nothing; the stored value rules the next epoch anyway.
                            queueSeek(
                                SeekRequest(SeekTarget.Absolute(currentPosition()), SeekMode.Precise),
                                null,
                            )
                        }
                        command.reply.complete(Unit)
                    }
                }
            }
            is CoreCommand.SetAbLoop -> {
                val active = session
                // The jump back is an ordinary precise seek, and an unseekable source has no way
                // to make one: the same refusal, for the same reason, as a live speed change.
                if (command.a != null && active != null && !active.source.seekable) {
                    command.reply.completeExceptionally(
                        UnsupportedOperationException(
                            "the A-B loop jumps back by precise seek, and this source is not seekable",
                        ),
                    )
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
        pendingRenderer = renderer
        watchRendererEvents(renderer)
        return true
    }

    /** A renderer attached before anything was open, kept for the session that follows. */
    private var pendingRenderer: VideoRenderer? = null

    /**
     * The renderer's event feed, finally collected (17.11 SOL-API5): surface loss and hard
     * failure become typed warnings, so they reach the event flow, the bounded history and the
     * dump instead of being visible only as a frozen picture. One collector per attached
     * renderer; replacing or detaching cancels it, and the core's own scope ends it at close.
     */
    private var rendererEventsJob: kotlinx.coroutines.Job? = null

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
                        // rest of the session with nothing to be done about it (audit 15.4.3).
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
                            commands.trySend(CoreCommand.DetachRenderer(CompletableDeferred()))
                        }
                        is RendererEvent.SurfaceAvailable, is RendererEvent.VsyncChanged -> Unit
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
        val durationUs = built.source.duration?.micros
        if (durationUs != null && requested.inWholeMicroseconds >= durationUs) {
            warn(PlaybackWarning.StartPositionIgnored(requested, "past the end of the media"))
            return null
        }
        return requested.inWholeMicroseconds
    }

    private suspend fun runOpen(command: CoreCommand.Open) {
        // Open is legal from Ended, and Ended keeps its session alive so the viewer can seek back.
        // That session must be fully torn down and awaited BEFORE the new one is installed:
        // overwriting the field would strand its source, workers, decoders, sink and queues live
        // but unreachable (audit P1-1).
        if (session != null) teardownSession()
        media = command.media
        lastChapterIndex = Int.MIN_VALUE
        externalSubtitleTracks = emptyList()
        selectedExternalSubtitle = null
        pendingExternalSubtitle = null
        // An open ends paused by contract, whatever was asked for before it. A play issued while this one
        // is still running arrives after this line and is honoured, which is what queueing it means.
        playRequested = false
        loopRefusalWarned = false
        pendingVideoRecovery = null
        videoRecoveryAttempted = false
        forceBackendSoftwareForMedia = false
        seekPhase = SeekPhase.Idle
        // A pending request aims at the PREVIOUS timeline (audit F-SEEK1). Left in place, the
        // handler pass that follows this open would run it against the fresh media: a bar drag
        // on the finished episode became a jump into the next one. Its callers are answered
        // Superseded, exactly as runStop answers them, and the hold state dies with it so the
        // frame barrier never compares the new session against the old session's counters.
        pendingSeek = null
        seekHeldSinceNanos = 0
        lastSeekAtNanos = 0
        framesShownAtLastSeek = 0
        resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
        // For the same reason as the pending seek above: a waiting selection names a track id from
        // the PREVIOUS media's table, and running it against the new file would rebuild the wrong
        // stream or silently change nothing and report success.
        discardPendingSelections("a new media item was opened before the selection could be applied")
        maskedSeekTargetMicros.value = NO_SEEK_MASK
        publishedPositionMicros.value = 0L
        progressState.value = Progress(position = Duration.ZERO, bufferedAhead = Duration.ZERO)
        firstFrameSeen = false
        endOfStream.reset()
        demuxUnderrunSeen = false
        stillImageFinished = false
        stillImageShownSinceNanos = 0
        openedAtNanos = clock.nanos()
        setStatus(PlaybackStatus.Opening)
        try {
            // The subtitle files are read FIRST, because whether one of them loads decides whether
            // the container's own subtitle stream should be selected at all. A file flagged
            // selectImmediately wins over the container's default, and the flag used to be honoured
            // only when no container subtitle happened to be active, which made an unconditional
            // promise conditional on the file (audit KP-P1-20). Choosing here rather than
            // afterwards means no rebuild and no moment where both selections exist.
            val parsedExternals = parseExternalSubtitles(command.media)
            val immediateExternal = parsedExternals.firstOrNull { track ->
                command.media.externalSubtitles
                    .getOrNull(-track.id.value - 1)?.selectImmediately == true
            }
            val subtitleChoice = if (immediateExternal != null) StreamChoice.None else StreamChoice.Auto
            var built = buildSession(command.media, StreamChoice.Auto, StreamChoice.Auto, subtitleChoice)
            session = built
            // The item's start position (SOL-API1), first half: the SOURCE is moved before the
            // workers start, while nothing reads it, so the initial fill decodes from the
            // keyframe at or before the target and nothing from the beginning of the media is
            // decoded, presented or heard. The exact landing is the second half below, made
            // cheap by this half: the refine walks forward within one group of pictures.
            val startTargetUs = startPositionTargetUs(command.media, built)
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
                    // open succeeded and was left with an Idle player (audit KP-P1-05).
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
            // success below either (audit KP-P1-05).
            if (preempted()) {
                teardownSession()
                command.reply.completeExceptionally(preemptedByTeardown("open"))
                return
            }
            // External subtitle files (S4.e): read before the session was built, merged into the
            // container's table now that one exists, so a flagged one starts timing at once.
            adoptExternalSubtitles(command.media, parsedExternals)
            // Unconditional: when this is non-null the container's subtitle stream was left
            // unselected above, so there is never a competing selection to defer to.
            immediateExternal?.let { applyExternalSubtitle(it.id) }
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
    ): OpenSession {
        // M1's resolver and M5's cache, both at the one place every open passes. The resolver
        // answers only for an item with a URI and no reader of its own; the cache wraps every
        // reader-fed open. On an open FAILURE a resolver-produced reader is the engine's to
        // close (the item's own reader stays the caller's, matching the backend's contract).
        openStage = OpenStage.Source
        // One reader per session, made HERE and owned here. The item carries a factory rather than
        // a live reader precisely because this line runs again for every rebuild: a track switch, a
        // decoder recovery, a loop and a queue returning to the same item all come back through it,
        // and the reader the previous session was given has been closed since (audit KP-P1-03).
        val suppliedIo = item.io?.invoke() ?: config.network.ioResolver?.resolve(item.uri)
        val cachingIo = if (suppliedIo != null && config.network.ioCache.enabled) {
            CachingMediaIo(suppliedIo, config.network.ioCache)
        } else {
            null
        }
        // What the backend will read through: the cache when there is one, the raw reader
        // otherwise. Handed over as a factory that answers with this one reader, because that is
        // what the item's own field is and the backend must not have two shapes to handle.
        val sessionIo = cachingIo ?: suppliedIo
        val effectiveItem = if (sessionIo == null) item else item.copy(io = { sessionIo })
        val backendSession = try {
            acquireAcrossContext(
                context = dispatchers.demux,
                acquire = { backend.open(effectiveItem) },
                closeAbandoned = { it.close() },
            ) ?: error("a media backend returned no session")
        } catch (failure: Throwable) {
            // Every reader on this path is the engine's, whoever supplied the factory, so an open
            // that never produced a session closes it here. The backend's own unwind may have got
            // there first, which is why MediaIo.close is documented to tolerate a second call.
            if (sessionIo != null) runCatching { sessionIo.close() }
            throw failure
        }
        // The reverse-order construction ledger (audit P1-2). Every resource acquired below
        // registers its undo the moment it exists; any failure runs the ledger newest-first under
        // NonCancellable, so nothing half built survives. Ownership transfers to OpenSession only
        // at the successful return.
        val rollback = mutableListOf<suspend () -> Unit>()
        rollback += { withContext(dispatchers.demux) { backendSession.close() } }
        try {
            // Backend degradations (hardware fallback, colour approximation) flow into the same
            // warning stream everything else uses, instead of a backend-private default (P1-21).
            openStage = OpenStage.Decoders
            // Through warn(), not straight onto the flow: a backend degradation that went only to
            // the event flow was absent from the bounded history and therefore from every support
            // bundle, which is the one place a warning that happened before anyone collected can
            // still be read.
            backendSession.setWarningSink { warning -> warn(warning) }
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
            val subtitleCandidate = when (subtitleChoice) {
                StreamChoice.None -> null
                is StreamChoice.At -> source.streams.firstOrNull { it.index == subtitleChoice.index }
                StreamChoice.Auto -> pickSubtitle(source.streams, audioCandidate)
            }

            var videoStream = videoCandidate
            var audioStream = audioCandidate
            var subtitleStream = subtitleCandidate
            val selectedVideoDecoder = videoStream?.let {
                createVideoDecoder(
                    session = backendSession,
                    stream = it,
                    sourceSeekable = source.seekable,
                    selection = videoSelection,
                )
            }
            val videoDecoder = selectedVideoDecoder?.decoder
            if (videoDecoder != null) {
                rollback += { withContext(dispatchers.videoDecode) { videoDecoder.close() } }
            }
            if (videoStream != null && videoDecoder == null) {
                warn(
                    PlaybackWarning.TrackDeselected(
                        TrackId(videoStream.index),
                        deselectionDetail("no decoder accepted this video stream"),
                    ),
                )
                videoStream = null
            }
            val audioDecoder = audioStream?.let { createAudioDecoder(backendSession, it) }
            if (audioDecoder != null) {
                rollback += { withContext(dispatchers.audioDecode) { audioDecoder.close() } }
            }
            if (audioStream != null && audioDecoder == null) {
                warn(
                    PlaybackWarning.TrackDeselected(
                        TrackId(audioStream.index),
                        deselectionDetail("no decoder accepted this audio stream"),
                    ),
                )
                audioStream = null
            }
            val subtitleDecoder = subtitleStream?.let { stream ->
                backendSession.subtitleDecoders.firstNotNullOfOrNull { factory -> factory.create(stream) }
            }
            if (subtitleDecoder != null) {
                rollback += { subtitleDecoder.close() }
            }
            if (subtitleStream != null && subtitleDecoder == null) {
                warn(
                    PlaybackWarning.TrackDeselected(
                        TrackId(subtitleStream.index),
                        "no decoder accepted this subtitle stream",
                    ),
                )
                subtitleStream = null
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
            if (videoPlayback != null) {
                rollback += { videoPlayback.close() }
                // Pre-start, so it applies immediately; the scheduler for this playback does not
                // exist yet, which is what makes the immediate path of the setter safe.
                videoPlayback.speed = speed
            }

            openStage = OpenStage.Output
            var sink: AudioSink? = null
            var audioPlayback: AudioPlayback? = null
            var negotiated: AudioFormat? = null
            if (audioStream != null && audioDecoder != null) {
                val createdSink = output.audioSink.create()
                sink = createdSink
                // Interlude item I-03, generalized by the ledger: from AudioPlayback's
                // construction onward the playback owns the sink and its close covers both (it is
                // idempotent). The window between the sink's creation and that construction is one
                // non-suspending line, and the playback entry below covers everything after it,
                // including a throw from AudioPlayback.open itself, which used to leak the sink.
                val createdPlayback = AudioPlayback(
                    createdSink,
                    clock,
                    onWarning = { warn(it) },
                    downmix = config.audio.downmix,
                )
                audioPlayback = createdPlayback
                rollback += { createdPlayback.close() }
                // Before open: open() captures the wanted rate as the fresh path's epoch, so a
                // player already at 2x opens its next file at 2x rather than at 1x until a seek.
                createdPlayback.speed = speed
                createdPlayback.preservePitch = preservePitch
                negotiated = createdPlayback.open(audioDecoder.outputFormat)
                createdPlayback.volume = volume
                createdPlayback.muted = muted
                emitEvent(PlayerEvent.AudioFormatChanged(negotiated.sampleRate, negotiated.channels))
            }
            openStage = OpenStage.Assembly

            withContext(dispatchers.demux) {
                source.selectStreams(
                    setOfNotNull(videoStream?.index, audioStream?.index, subtitleStream?.index),
                )
            }

            tracks = tracks
                .withSelection(TrackKind.Video, videoStream?.let { TrackId(it.index) })
                .withSelection(TrackKind.Audio, audioStream?.let { TrackId(it.index) })
                .withSelection(TrackKind.Subtitle, subtitleStream?.let { TrackId(it.index) })
            videoStream?.videoSize?.let { emitEvent(PlayerEvent.VideoSizeChanged(it)) }

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
            val subtitleQueue = subtitleStream?.let {
                PacketQueue(it.index, softLimitUs).also { queue -> queue.flushTo(requestedEpoch) }
            }
            return OpenSession(
                token = nextSessionToken++,
                backendSession = backendSession,
                source = source,
                videoStream = videoStream,
                audioStream = audioStream,
                videoDecoder = if (videoStream == null) null else videoDecoder,
                videoDecoderOrigin = if (videoStream == null) null else selectedVideoDecoder?.origin,
                audioDecoder = if (audioStream == null) null else audioDecoder,
                videoQueue = videoQueue,
                audioQueue = audioQueue,
                subtitleStream = subtitleStream,
                subtitleDecoder = if (subtitleStream == null) null else subtitleDecoder,
                subtitleQueue = subtitleQueue,
                video = videoPlayback,
                audio = audioPlayback,
                sink = sink,
                renderer = renderer,
                negotiatedFormat = negotiated,
                cachingIo = cachingIo,
            )
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
    )

    /**
     * Why each decoder candidate refused the last stream it was offered, for the deselection
     * warning. Actor-confined, overwritten per create attempt. runCatching+getOrNull here used to
     * swallow cancellation and every diagnostic (audit P1-20).
     */
    private var decoderCandidateFailures: List<String> = emptyList()

    private suspend fun createVideoDecoder(
        session: BackendSession,
        stream: PlayerStreamInfo,
        sourceSeekable: Boolean,
        selection: VideoDecoderSelection,
    ): SelectedVideoDecoder? {
        if (stream.kind != TrackKind.Video) return null
        val failures = mutableListOf<String>()
        decoderCandidateFailures = failures

        val policy = when (selection) {
            VideoDecoderSelection.Configured -> config.hardwareDecode
            VideoDecoderSelection.BackendSoftwareOnly -> HwdecPolicy.Off
        }
        val rendererEligible = selection == VideoDecoderSelection.Configured && when (policy) {
            HwdecPolicy.Auto -> sourceSeekable
            HwdecPolicy.Require -> true
            HwdecPolicy.Off, is HwdecPolicy.Prefer -> false
        }
        if (rendererEligible) {
            for (factory in pendingRenderer?.videoDecoderFactories().orEmpty()) {
                val decoder = tryCreateVideoDecoder(factory, stream, policy, failures)
                if (decoder != null) {
                    return SelectedVideoDecoder(decoder, VideoDecoderOrigin.Renderer)
                }
                warnAboutRefusedHardwareCandidate(factory, stream, policy)
            }
        }

        for (factory in session.videoDecoders) {
            val decoder = tryCreateVideoDecoder(factory, stream, policy, failures) ?: run {
                warnAboutRefusedHardwareCandidate(factory, stream, policy)
                continue
            }
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
            return SelectedVideoDecoder(decoder, VideoDecoderOrigin.Backend)
        }
        return null
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
                factory.create(stream, policy)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                failures += "${factory.name}: ${failure.message ?: failure::class.simpleName}"
                null
            }
        },
        closeAbandoned = { it.close() },
    )

    private fun warnAboutRefusedHardwareCandidate(
        factory: VideoDecoderFactory,
        stream: PlayerStreamInfo,
        policy: HwdecPolicy,
    ) {
        // Named as a hardware problem only when hardware was actually asked for. A factory refusing a
        // stream it cannot decode has nothing to do with hardware, and the caller already learns about
        // that from the TrackDeselected warning and the failed open, each carrying the real reason.
        val askedForHardware = policy is HwdecPolicy.Require || policy is HwdecPolicy.Prefer
        if (askedForHardware) {
            warn(PlaybackWarning.HardwareDecodeUnavailable(stream.codec, "${factory.name} refused the stream"))
        }
    }

    private suspend fun createAudioDecoder(session: BackendSession, stream: PlayerStreamInfo): AudioDecoder? {
        if (stream.kind != TrackKind.Audio) return null
        val failures = mutableListOf<String>()
        decoderCandidateFailures = failures
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

    /** Language preference first, then the container's default disposition, then the first audio track. */
    /**
     * The automatic subtitle choice, per SubtitleConfig and the container's dispositions:
     * an accessibility track in a preferred language wins, then any preferred-language track
     * (default-flagged first), then, when the audio is not in a preferred language and the
     * config allows it, a forced track. No preference means no automatic subtitles.
     */
    private fun pickSubtitle(
        streams: List<PlayerStreamInfo>,
        audio: PlayerStreamInfo?,
    ): PlayerStreamInfo? {
        val subtitles = streams.filter { it.kind == TrackKind.Subtitle }
        if (subtitles.isEmpty()) return null
        val preferred = config.subtitles.preferredLanguages.map { it.lowercase() }
        fun matches(stream: PlayerStreamInfo) = stream.language?.lowercase() in preferred
        if (preferred.isNotEmpty()) {
            subtitles.firstOrNull { matches(it) && it.isAccessibility }?.let { return it }
            subtitles.filter { matches(it) }.sortedByDescending { it.isDefault }.firstOrNull()?.let { return it }
        }
        if (config.subtitles.autoSelectForced) {
            val audioPreferred = audio?.language?.lowercase() in preferred
            if (preferred.isNotEmpty() && !audioPreferred) {
                subtitles.firstOrNull { it.isForced && matches(it) }?.let { return it }
            }
        }
        // The plain default: subtitled media shows its subtitles. Default-flagged first, and a
        // forced-only track never wins here, because forced tracks exist for foreign lines
        // inside otherwise-understood audio, not as the default face of the media.
        if (config.subtitles.autoSelect) {
            subtitles.filter { !it.isForced }.sortedByDescending { it.isDefault }.firstOrNull()
                ?.let { return it }
        }
        return null
    }

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

    /** What the initial fill actually established, so open never mistakes a timeout for readiness. */
    private enum class FillOutcome { Ready, Preempted, WorkerFinished, TimedOut }

    /**
     * What the one-frame push achieved, as five separate facts rather than one boolean.
     *
     * They were one number before, `submitted + headless`, and that number was wrong in both
     * directions: a renderer that refused every frame could never satisfy it, so an open burned
     * its whole ten second budget and then reported success anyway, while a frame released with no
     * renderer attached satisfied it instantly (audit KP-P1-06). Each outcome now has a name, and
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
            delay(WORKER_POLL)
        }
        session.schedulerMode.value = SCHEDULER_IDLE
        return outcome
    }

    /**
     * The same push, with the two silent outcomes said out loud (audit KP-P1-06).
     *
     * A headless release is deliberately NOT warned: with no renderer attached there is no picture
     * to be wrong about, `PlaybackStats.headlessFrames` already counts it, and the facade documents
     * that detaching costs the picture and nothing else. The other two are warned because in both
     * of them a renderer exists and the viewer is still looking at the old surface.
     */
    private suspend fun reportFirstFrame(session: OpenSession, what: String): FirstFrame {
        val outcome = presentFirstFrame(session)
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
     * The refusal a preempted open or track change completes with (audit KP-P1-05).
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
     * tracks were both told they had won (audit KP-P1-01).
     */
    private fun queueSelection(kind: TrackKind, track: TrackId?, reply: CompletableDeferred<TrackChange>) {
        pendingSelections.put(kind, SelectionRequest(kind, track, reply))
            ?.reply?.complete(TrackChange.Superseded(kind, track))
    }

    /** Ends every waiting selection without applying it: a stop, a close, or no media left. */
    private fun discardPendingSelections(reason: String) {
        val discarded = pendingSelections.values.toList()
        pendingSelections.clear()
        discarded.forEach { it.reply.complete(TrackChange.Discarded(reason)) }
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

    /** Applies the desired selection by reopening the source at the position playback is at. See digest 8.3. */
    private suspend fun handleTrackChanges() {
        // A renderer failure has already torn the old graph down. The recovery reopen folds these
        // choices into its one replacement graph so nothing is lost and no second open happens.
        if (pendingVideoRecovery != null) return
        if (pendingSelections.isEmpty()) return
        // Taken and cleared together: everything asked for so far rides ONE rebuild, and a request
        // arriving during it belongs to the next one.
        val requested = pendingSelections.values.toList()
        pendingSelections.clear()
        val current = session
        val item = media
        if (current == null || item == null) {
            requested.forEach {
                it.reply.complete(
                    TrackChange.Discarded("no media is open, so the selection had nothing to apply to"),
                )
            }
            return
        }
        val at = currentPosition()
        val wasPlaying = playRequested
        val video = choiceFor(requested, TrackKind.Video, current.videoStream?.index)
        val audio = choiceFor(requested, TrackKind.Audio, current.audioStream?.index)
        // An external subtitle target means NO container stream (S4.e): the rebuild deselects
        // whatever container track was timing cues, and the external table applies afterwards.
        val subtitleRequest = requested.firstOrNull { it.kind == TrackKind.Subtitle }
        val subtitle = if (subtitleRequest != null && isExternalSubtitle(subtitleRequest.track)) {
            StreamChoice.None
        } else {
            choiceFor(requested, TrackKind.Subtitle, current.subtitleStream?.index)
        }
        try {
            teardownSession()
            requestedEpoch = requestedEpoch.next()
            var rebuilt = buildSession(item, video, audio, subtitle)
            session = rebuilt
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
                        return
                    }
                    rebuilt = recovered.session
                    recoveredAndPositioned = true
                }
                FillOutcome.TimedOut -> warn(
                    PlaybackWarning.StartupIncomplete("no stream reached readiness within $OPEN_FILL_DEADLINE after the track change"),
                )
                FillOutcome.Ready -> Unit
                // The same lie an open used to tell (audit KP-P1-05): a stop is already queued, so
                // publishing a status and reporting the selection applied would be undone by the
                // very next command.
                FillOutcome.Preempted -> {
                    teardownSession()
                    requested.forEach { it.reply.complete(TrackChange.Discarded(PREEMPTED_SELECTION)) }
                    return
                }
            }
            // A user seek that was already queued outranks the reposition. A successful decoder
            // recovery has already performed the internal precise reposition and must not queue it twice.
            if (!recoveredAndPositioned && pendingSeek == null && at > Pts.Zero) {
                pendingSeek = SeekRequest(SeekTarget.Absolute(at), SeekMode.Precise)
            }
            playRequested = wasPlaying
            // The rebuild replaced the track table; the synthetic external rows and any waiting
            // external selection re-apply on top of it (S4.e).
            if (externalSubtitleTracks.isNotEmpty()) {
                tracks = tracks.copy(all = tracks.all + externalSubtitleTracks.map { it.info })
            }
            pendingExternalSubtitle?.let { waiting ->
                pendingExternalSubtitle = null
                applyExternalSubtitle(waiting)
            }
            setStatus(if (wasPlaying) PlaybackStatus.Buffering else PlaybackStatus.Paused)
            requested.forEach { it.reply.complete(TrackChange.Applied(it.kind, it.track)) }
        } catch (cancellation: CancellationException) {
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
        } catch (failure: Throwable) {
            val error = classify(failure, item)
            teardownSession()
            fail(error)
            requested.forEach { it.reply.completeExceptionally(PlaybackException(error)) }
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
            duration = active.source.duration,
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
        val rebuilt = buildSession(
            item = recovery.item,
            videoChoice = recovery.video,
            audioChoice = recovery.audio,
            subtitleChoice = recovery.subtitle,
            videoSelection = VideoDecoderSelection.BackendSoftwareOnly,
        )
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
                rebuilt.sink?.stop()
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
            DEMUX_WORKER -> PlaybackError.SourceUnavailable(
                media?.uri ?: "",
                cause ?: IllegalStateException("the demuxer stopped $context"),
                "the demuxer stopped $context${cause?.let(::causeDetail).orEmpty()}",
            )
            else -> PlaybackError.Internal("a pipeline worker stopped $context", cause)
        }
        return PlaybackException(error)
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
        // The mask lives exactly as long as a request is queued or held. drainCommands runs before
        // this handler, so a newly accepted request re-arms the mask before this pass can clear it,
        // and the clock resumes publishing only once the machine has actually drained.
        if (pendingSeek == null) {
            maskedSeekTargetMicros.value = NO_SEEK_MASK
            publishedPositionMicros.value = currentPosition().micros
        }
        // Chapter crossings (S4.e): compared on the published reading, so a seek and ordinary
        // playback announce a boundary the same way. Media with no table emits nothing.
        val chapters = session.source.chapters
        if (chapters.isNotEmpty()) {
            val positionUs = publishedPositionMicros.value
            // The same shared reading the facade uses, so an event and a query can never disagree
            // about which chapter is playing. A position in a gap belongs to no chapter, which is
            // reported as one: null (audit KP-P1-11).
            val current = chapters.chapterHolding(positionUs)
            val index = current?.let { chapters.indexOf(it) } ?: -1
            if (index != lastChapterIndex) {
                lastChapterIndex = index
                emitEvent(PlayerEvent.ChapterChanged(current))
            }
        }
        if (session.isStillImage && session.framesOut(session.video) > 0) {
            if (stillImageShownSinceNanos == 0L) stillImageShownSinceNanos = clock.nanos()
            val shownFor = (clock.nanos() - stillImageShownSinceNanos).nanoseconds
            if (shownFor >= STILL_IMAGE_DURATION) stillImageFinished = true
            else wakeIn(STILL_IMAGE_DURATION - shownFor)
        }
        // The A-B loop's B crossing (S4.g): compared on the published reading like the chapters,
        // so a wrap is impossible while a seek is in flight and the pass after one starts clean.
        // Playing only: a paused player may be seeked past B and inspected there. The wrap is an
        // ordinary precise seek, so an unseekable source cannot wrap; arming refused the live
        // case, and a loop armed before such an open simply never fires.
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
                wakeIn(((bUs - positionUs) / speed).toLong().microseconds)
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
     * Cue timing (S4.c). Three cheap steps per pass: drain decoded cues in, ask the pure
     * selector what is visible NOW, and publish an overlay only when that answer changed.
     *
     * Publishing on changes, never per frame, is 17.9's measured law applied to subtitles: cues
     * change about once a second. The raster cost therefore sits on cue edges, and the renderer
     * skips re-uploading an unchanged overlay by contentHash.
     */
    private suspend fun handleSubtitles() {
        val session = this.session ?: return
        val decoder = session.subtitleDecoder
        val queue = session.subtitleQueue

        // The drain half needs a container stream; the timing half below does not: an external
        // cue table (S4.e) times and publishes through the same selector with no decoder at all.
        if (decoder == null || queue == null) {
            if (session.subtitleCues.isEmpty() && session.publishedCueKey.isNullOrEmpty()) return
            timeAndPublishCues(session)
            return
        }

        // Text decode is parsing; it runs inline. The send contract is the decoder SPI's: false
        // means full and the caller must drain before retrying the SAME packet. A packet the
        // decoder temporarily refuses is RETAINED for the next pass, exactly like the audio and
        // video paths retain theirs: closing it on refusal silently dropped the cue (audit P1-8).
        while (true) {
            val packet = session.pendingSubtitlePacket ?: queue.poll() ?: break
            session.pendingSubtitlePacket = null
            var accepted = false
            try {
                while (true) {
                    if (decoder.send(packet)) {
                        accepted = true
                        break
                    }
                    val decoded = decoder.receive()
                    if (decoded.isEmpty()) break
                    insertCues(session, decoded)
                }
            } finally {
                if (accepted) packet.close()
            }
            if (!accepted) {
                session.pendingSubtitlePacket = packet
                wakeIn(WORKER_POLL)
                break
            }
            while (true) {
                val decoded = decoder.receive()
                if (decoded.isEmpty()) break
                insertCues(session, decoded)
            }
        }

        timeAndPublishCues(session)
    }

    /** The timing half of handleSubtitles, shared by container and external cue tables (S4.e). */
    private suspend fun timeAndPublishCues(session: OpenSession) {
        val positionUs = currentPosition().micros - subtitleDelay.inWholeMicroseconds
        val active = CueSelector.activeAt(session.subtitleCues, positionUs)
        // The cues themselves are the identity, not their timestamps: two different texts or
        // styles over the same interval are different overlays, and a (start, end) key republished
        // nothing for them (audit P1-14). Structural equality on the data classes is exact.
        if (active != session.publishedCueKey) {
            session.publishedCueKey = active.toList()
            publishOverlay(session, active)
        }

        // Sleep exactly to the next cue edge instead of polling for it.
        CueSelector.nextChangeAfter(session.subtitleCues, positionUs)?.let { nextUs ->
            val untilNext = (nextUs - positionUs).microseconds
            if (untilNext > Duration.ZERO) wakeIn(minOf(untilNext, WORKER_POLL))
        }
    }

    private fun insertCues(session: OpenSession, decoded: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>) {
        session.subtitleCues.addAll(decoded)
        session.subtitleCues.sortBy { it.startMicros }
        pruneCueHistory(session)
    }

    /**
     * SOL-P5's pruning cursor. Container cues far behind the position are dropped: a backward
     * seek flushes and re-decodes them, so keeping the whole history only grew a list forever.
     * External cue tables (no decoder) are NEVER pruned; nothing re-supplies them.
     */
    private fun pruneCueHistory(session: OpenSession) {
        if (session.subtitleDecoder == null) return
        val cutoff = currentPosition().micros - subtitleDelay.inWholeMicroseconds - CUE_PRUNE_BEHIND_MICROS
        if (cutoff <= 0) return
        session.subtitleCues.removeAll { it.endMicros < cutoff }
    }

    /**
     * Rasterises [active] at the video's own display size and hands the overlay to the renderer.
     * With no platform rasterizer the timing still ran; only the drawing is absent, and the
     * OutputBackend KDoc says exactly that.
     */
    private suspend fun publishOverlay(
        session: OpenSession,
        active: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
    ) {
        val rasterizer = output.subtitleRasterizer ?: return
        val size = session.videoStream?.videoSize
        val width = size?.displayWidth?.takeIf { it > 0 } ?: DEFAULT_SUBTITLE_CANVAS_WIDTH
        val height = size?.height?.takeIf { it > 0 } ?: DEFAULT_SUBTITLE_CANVAS_HEIGHT
        val generation = session.overlayGeneration.incrementAndGet()
        if (active.isEmpty()) {
            // A clear costs no rasterisation; publish it inline so text vanishes on time.
            session.renderer.setOverlay(
                SubtitleOverlay(emptyList(), width, height, contentHash = generation),
            )
            return
        }
        // SOL-P5: rasterisation runs on its own serial lane, never on the actor. Only the
        // NEWEST publication may land: a slow raster of superseded text checks the generation
        // after drawing and drops itself. The job rides a SINGLE slot rather than the session's
        // job list (audit F-JOB1): one Job per cue edge appended for a whole film grew that list
        // by thousands of completed coroutines teardown then had to walk. The superseded raster
        // is cancelled outright, and teardown joins the one live slot.
        val cues = active.toList()
        session.rasterJob?.cancel()
        session.rasterJob = scope.launch(dispatchers.raster) {
            val images = rasterizer.rasterize(cues, width, height, subtitleScale, subtitlePosition)
            if (session.overlayGeneration.value != generation) return@launch
            session.renderer.setOverlay(
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
            audioQueue.isEndOfStream && audioQueue.count == 0 &&
            // AND nothing still between the decoder and the device. Without this the ring was told
            // the stream had ended while up to five decoded buffers were still on their way into
            // it, so the marker meant "demux finished", not "no more audio" (audit P0-20).
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
        if (session.video != null && session.video.queuedFrames > 0 && !stillImageFinished) return

        // The audio lane's own end, which the four conditions above cannot see. A drained decoder
        // and an empty packet queue say demuxing and decoding finished; the decoded samples then
        // travel through a handoff channel, a conversion and the DSP stages before any device hears
        // them. Ending here used to cut all of that off, which is silent media loss and is worst
        // exactly where it is most audible: short clips, and any non-1x speed (audit P0-20).
        if (session.audio != null && !endOfStream.tailAbandoned) {
            // Bounded like the sink drain below it and for the same reason (F-EOS1): a feeder that
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
            val audio = session.audio
            // Bounded (audit F-EOS1): a device that stopped pulling freezes the ring's fill, and
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
        session.schedulerMode.value = SCHEDULER_IDLE
        // The timeline stops where the media does. A clock left running reads on from wall time, so a
        // player sitting on its last frame would report a position further past the duration the longer
        // it was left there. The final device anchor is consumed first, exactly as a pause does it, so a
        // late callback cannot re-anchor a clock that is already frozen.
        session.audio?.anchorClock()
        session.audio?.pause()
        emitEvent(PlayerEvent.Ended)
        setStatus(PlaybackStatus.Ended)
    }

    private fun handleLoop() {
        if (status != PlaybackStatus.Ended) return
        // The armed A-B loop owns the end of the media (S4.g): with no B, or a B past the end,
        // the wrap point IS the end, and the jump back to A restarts playback like a repeat,
        // regardless of LoopMode. An A at or past the duration would land straight back on the
        // end and restart every pass for ever, so such an A is treated as unarmed rather than
        // spun on; an unseekable source cannot make the jump at all.
        val loopA = abLoopA
        val durationUs = session?.source?.duration?.micros
        if (loopA != null && session?.source?.seekable == true &&
            durationUs != null && loopA.inWholeMicroseconds < durationUs
        ) {
            restartFrom(Pts(loopA.inWholeMicroseconds))
            return
        }
        // One media item repeating is a seek to zero and nothing else. LoopMode.All with a queue
        // of one or none means the same thing: the whole queue IS the current item (S4.e).
        val repeatsCurrent = loop == LoopMode.One || (loop == LoopMode.All && queueItems.size <= 1)
        if (!repeatsCurrent) return
        // The same guard the A-B branch above has (audit F-LOOP1): the repeat is a precise seek,
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
     * The queue's own advance (S4.e). At Ended with a queue behind it, the next item opens and
     * playback continues; LoopMode.All wraps past the last item. Runs after handleLoop, which
     * owns the repeat-current cases, and the Ended-to-Opening transition makes re-entry
     * impossible: by the time this pass ends the status has left Ended.
     */
    private suspend fun handleQueueAdvance() {
        if (status != PlaybackStatus.Ended) return
        if (loop == LoopMode.One) return
        if (queueItems.size <= 1) return
        val next = when {
            queueIndex + 1 < queueItems.size -> queueIndex + 1
            loop == LoopMode.All -> 0
            else -> return
        }
        queueIndex = next
        runOpen(CoreCommand.Open(queueItems[next], CompletableDeferred()))
        // An open ends paused by contract; a queue that was playing keeps playing through it.
        playRequested = true
    }

    /**
     * Steps a PAUSED player forward by exactly one decoded frame (S4.e, corrected by KP-P1-10).
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
                        // frame it just put on screen rather than waiting for a clock that is
                        // frozen to notice.
                        active.lastVideoPtsUs.value
                            .takeIf { it != NO_POSITION }
                            ?.let { publishedPositionMicros.value = it }
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
     * Arms the schedule's one-shot capture (S4.e). Playing, the very next presented frame
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

    /** Explicit queue movement, refused typed when there is nowhere to go (S4.e). */
    private suspend fun jumpQueue(target: Int, reply: CompletableDeferred<Unit>, direction: String) {
        if (queueItems.isEmpty()) {
            reply.completeExceptionally(IllegalStateException("no queue is open; openQueue first"))
            return
        }
        val resolved = when {
            target in queueItems.indices -> target
            loop == LoopMode.All -> ((target % queueItems.size) + queueItems.size) % queueItems.size
            else -> {
                reply.completeExceptionally(
                    IllegalStateException(
                        "the queue has no $direction item from ${queueIndex + 1} of ${queueItems.size}; " +
                            "LoopMode.All is what makes the ends meet",
                    ),
                )
                return
            }
        }
        val wasPlaying = playRequested
        queueIndex = resolved
        runOpen(CoreCommand.Open(queueItems[resolved], reply))
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
            request.resolve(currentPosition(), it.source.duration)
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
            // did produce its frame, the output simply would not draw it (audit KP-P1-06).
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
                accepted.resolve(Pts(basis), active.source.duration).micros
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
        val session = session ?: return
        val target = request.resolve(currentPosition(), session.source.duration)

        // 1
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
        session.sink?.stop()
        val quiescent = quiesceWorkers(session)
        if (!quiescent) {
            // Quiescence is the precondition of every mutation below. Without it, flushing a
            // decoder or clearing a queue mutates state a still-running worker may be using, so
            // the seek aborts as a transaction instead of continuing on best effort. The workers
            // are released so playback continues at the old position, and every waiting caller
            // gets an explicit rejection rather than a fabricated success.
            val reason = "a worker did not reach a quiescent boundary within $QUIESCE_DEADLINE; the seek was aborted"
            warn(PlaybackWarning.BadTimestamps(reason))
            seekPhase = SeekPhase.Idle
            session.discardBeforeUs.value = Long.MIN_VALUE
            releaseWorkers(session, epoch)
            resolveSeekReplies(SeekResult.Rejected(reason))
            if (!playRequested && status != PlaybackStatus.Ended) setStatus(PlaybackStatus.Paused)
            return
        }

        var attempt = 0
        var landed: Pts? = null
        // KeyframeThenRefine runs this loop in two phases (SOL-API3, closed here): the first
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
            val aim = Pts((target.micros - backoff).coerceAtLeast(0L))

            // 4
            flushDecoders(session, epoch)
            // 5
            clearBuffers(session, epoch)
            // 6
            withContext(dispatchers.demux) { session.source.seekToKeyframe(aim) }

            // 7
            seekPhase = if (phaseMode == SeekMode.Keyframe) SeekPhase.Filling else SeekPhase.Discarding
            // The exact target, not target minus tolerance: the public promise is "the first
            // frame at or after the target", and a 5 ms allowance under it showed pre-target
            // pictures and audio the promise says cannot appear (audit P1-10). The tolerance
            // still exists where it belongs, in the overshoot judgment below.
            session.discardBeforeUs.value = when (phaseMode) {
                SeekMode.Keyframe -> Long.MIN_VALUE
                else -> target.micros
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
            if (!overshot || !laddered || preempted()) {
                val keyframeShort = landed != null && landed.micros < target.micros
                if (request.mode == SeekMode.KeyframeThenRefine && !refining && keyframeShort && !preempted()) {
                    // The immediate picture: the keyframe presents NOW, before the refine pass
                    // pays its decode-forward. The mask keeps reporting the exact target
                    // throughout, so no observer mistakes the keyframe for the answer.
                    presentFirstFrame(session)
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
        seekPhase = SeekPhase.Idle
        session.discardBeforeUs.value = Long.MIN_VALUE
        lastSeekAtNanos = clock.nanos()
        framesShownAtLastSeek = session.framesReleased(session.video)
        // No landing frame is a real answer, not a formality to paper over. It is legitimate in
        // exactly two shapes: the seek ran off the end of the stream (nothing left to decode), or
        // a later request preempted this one. Anything else means the pipeline produced nothing
        // within the deadline, and reporting SeekCompleted there was the fabricated success the
        // audit called P1-4. The caller gets a rejection and the completion event is not emitted.
        val endOfStreamLanding = session.selectedQueues().all { it.isEndOfStream } && session.decodersDrained()
        if (landed == null && !endOfStreamLanding && !preempted()) {
            resolveSeekReplies(
                SeekResult.Rejected("the pipeline produced no frame for the seek target within $SEEK_DEADLINE"),
            )
            if (!playRequested && status != PlaybackStatus.Ended) setStatus(PlaybackStatus.Paused)
            return
        }
        publishedPositionMicros.value = (landed ?: target).micros
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
        session.videoQueue?.flushTo(epoch)
        session.audioQueue?.flushTo(epoch)
        session.subtitleQueue?.flushTo(epoch)
        // A retained subtitle packet belongs to the flushed position and is owned here alone.
        session.pendingSubtitlePacket?.close()
        session.pendingSubtitlePacket = null
        // The pure selector makes seek reconstruction trivial: clear, re-decode from the landing
        // point, and the next pass's activeAt IS the rebuilt state, in either direction.
        session.subtitleCues.clear()
        // External cues are position-independent facts about the file beside the media: a flush
        // that empties the table must put them back, or every seek silences them (S4.e).
        selectedExternalSubtitle
            ?.let { id -> externalSubtitleTracks.firstOrNull { it.id == id } }
            ?.cues
            ?.let(session.subtitleCues::addAll)
        session.publishedCueKey = null
        session.subtitleDecoder?.flush(epoch)
        while (true) {
            val buffer = session.decodedAudio.tryReceive().getOrNull() ?: break
            buffer.close()
            session.audioInFlight.decrementAndGet()
        }
        // A seek away from the end makes the stream un-ended, so the token and the feeder's answer
        // both go back. Left set, the next arrival at the end would read a stale "already flushed"
        // and skip the tail it was supposed to push (audit P0-20).
        session.audioEosRequested.value = false
        session.audioTailFlushed.value = false
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
            session.firstWorkerOutcome.value?.cause?.let { throw it }
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
        pendingVideoRecovery = null
        pendingSeek = null
        discardPendingSelections("stop() tore the session down before the selection could be applied")
        resolveSeekReplies(SeekResult.Superseded(requestedEpoch))
        teardownSession()
        media = null
        tracks = Tracks.Empty
        externalSubtitleTracks = emptyList()
        selectedExternalSubtitle = null
        pendingExternalSubtitle = null
        endOfStream.reset()
        seekPhase = SeekPhase.Idle
        // Idle publishes Idle's numbers: a position and progress left over from the stopped
        // session would describe media that no longer exists (audit P1-18).
        maskedSeekTargetMicros.value = NO_SEEK_MASK
        publishedPositionMicros.value = 0L
        progressState.value = Progress(position = Duration.ZERO, bufferedAhead = Duration.ZERO)
        setStatus(PlaybackStatus.Idle)
        publishSnapshot()
        // The stats too, and off the interval: the totals stay (they belong to the player, and the
        // stopped session was retired into them), while every gauge falls to its empty value
        // because there is no session to measure. Waiting for the next interval left a stopped
        // player reporting the queue depths of media it no longer holds (audit KP-P1-21).
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
        // The session comes off the actor FIRST, so that everything below is about a graph nothing
        // else can reach, and the release can then run somewhere this coroutine is able to stop
        // waiting for (audit KP-P1-07).
        val detached = detachSession()
        // A zero budget is used by tests to force the compromised-runtime result. It must not prevent
        // teardown from starting: a missed deadline changes the report, never resource ownership.
        val finished = when {
            // First, and before the "nothing to release" case: a zero budget is the test override
            // that forces the compromised report deterministically, and it must do that whether or
            // not a session was open.
            closeDeadline <= Duration.ZERO -> {
                if (detached != null) releaseSession(detached)
                false
            }
            detached == null -> true
            else -> awaitRelease(detached)
        }
        settleOutstandingForClose()
        val failure = if (!finished) {
            val error = PlaybackError.RuntimeCompromised(
                if (teardownWedged.value) {
                    "teardown did not finish within $closeDeadline and is STILL RUNNING, so the " +
                        "playback threads were left alive rather than closed under it; a native " +
                        "call that has wedged cannot be killed from inside the process, so a caller " +
                        "that needs those threads back has to terminate the process"
                } else {
                    "teardown did not finish within $closeDeadline, so a worker may still hold resources"
                },
            )
            PlaybackException(error)
        } else {
            null
        }
        terminalCloseOutcome.compareAndSet(
            expect = null,
            update = TerminalCloseOutcome(reply = reply, failure = failure),
        )
        terminated = true
    }

    /**
     * Releases [detached] on a lifetime of its own and waits at most [closeDeadline] for it.
     *
     * The deadline used to wrap [teardownSession] directly, whose whole body is `NonCancellable`,
     * so it could never fire: a native close that wedged kept `closeAndAwait` suspended for ever
     * and the documented compromised-runtime report was unreachable (audit KP-P1-07). The release
     * is not abandoned, because abandoning it would leak the graph outright; what changes is that
     * the actor stops WAITING for it and reports the truth.
     *
     * @return true when the release finished inside the deadline.
     */
    private suspend fun awaitRelease(detached: OpenSession): Boolean {
        // Parentless, so cancelling anything cannot abandon the graph half released, and on the
        // release lane, which is the one lane the actor is not standing on.
        val release = GlobalScope.launch(
            context = dispatchers.release + CoroutineName("kiteplayer-session-release"),
        ) {
            try {
                releaseSession(detached)
            } catch (thrown: Throwable) {
                // This job has no parent to report to, so a throw here would be an unhandled
                // failure. warn() is fence-locked and safe from any thread.
                warn(PlaybackWarning.ResourcesNotReleased("the session release failed${causeDetail(thrown)}"))
            }
        }
        if (withTimeoutOrNull(closeDeadline) { release.join() } != null) return true
        // Still running. The dispatchers it is standing on must NOT be closed under it, so the
        // finalizer is told to leave them alone: leaked threads are recoverable by ending the
        // process, and closing a dispatcher a wedged native call is running on is not.
        teardownWedged.value = true
        return false
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
                // (audit KP-P1-07). The compromised report already names the leak.
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
            if (!StatusMachine.isLegal(status, PlaybackStatus.Idle)) {
                illegalTransitions += "$status to ${PlaybackStatus.Idle}"
            }
            status = PlaybackStatus.Idle
            statusHistory += PlaybackStatus.Idle
        }
        // Terminal close leaves nothing of the closed media behind: a snapshot still naming the
        // media, its tracks or its position would describe a session that no longer exists
        // (audit P1-18).
        media = null
        tracks = Tracks.Empty
        maskedSeekTargetMicros.value = NO_SEEK_MASK
        publishedPositionMicros.value = 0L
        progressState.value = Progress(position = Duration.ZERO, bufferedAhead = Duration.ZERO)
        // The totals survive the close because they belong to the player and every session was
        // retired into them; every gauge is at its empty value because nothing is measurable any
        // more. Built by hand rather than through publishProgressAndStats for the reason below:
        // nothing here may consult the clock (audit KP-P1-21).
        statsState.value = PlaybackStats(
            decodedVideoFrames = retiredDecodedVideo,
            submittedFrames = retiredSubmitted,
            headlessFrames = retiredHeadless,
            droppedFramesLate = retiredDroppedLate,
            refusedFrames = retiredRefused,
            repeatedFrames = retiredRepeated,
            audioUnderruns = retiredUnderruns,
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
            videoTransform = videoTransform,
            subtitleDelay = subtitleDelay,
            subtitleScale = subtitleScale,
            subtitlePosition = subtitlePosition,
            audioDelay = audioDelay,
            abLoopA = abLoopA,
            abLoopB = abLoopB,
            preservePitch = preservePitch,
            error = lastError,
            generation = requestedEpoch,
            queue = queueItems,
            queueIndex = queueIndex,
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
     * line (audit KP-P1-07). And the counters must be retired at exactly this moment, because after
     * it nothing can read them again and their totals would otherwise fall back to the next
     * session's zero (audit KP-P1-21).
     */
    private fun detachSession(): OpenSession? {
        val detached = session ?: return null
        session = null
        retireCounters(detached)
        return detached
    }

    private suspend fun teardownSession() {
        releaseSession(detachSession() ?: return)
    }

    /** Folds one finished session's counters into the player's totals. Once per session, exactly. */
    private fun retireCounters(session: OpenSession) {
        retiredDecodedVideo += session.decodedVideoFrames.value
        retiredSubmitted += session.renderer.submittedFrames
        retiredHeadless += session.renderer.headlessFrames
        retiredDroppedLate += session.video?.droppedFrames ?: 0
        retiredRefused += session.video?.refusedFrames ?: 0
        retiredRepeated += session.video?.repeatedFrames ?: 0
        retiredUnderruns += session.audio?.underruns ?: 0
    }

    private suspend fun releaseSession(session: OpenSession) {
        // Once detached, this is the only remaining owner of the graph. Cancellation and the close
        // reporting budget may no longer skip any release below, otherwise the detached decoder or
        // backend session becomes unreachable. A wedged native close may therefore outlive the budget;
        // that is preferable to returning while a worker can still touch freed native state.
        withContext(NonCancellable) {
            session.schedulerMode.value = SCHEDULER_IDLE
            // Every close still runs even when an earlier one failed, which is why each is wrapped.
            // What changed is that the failures are COLLECTED rather than dropped: a decoder or a
            // device that refused to close used to leave no trace anywhere (audit KP-P1-08).
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
            release("audio device stop") { session.sink?.stop() }
            session.workers.forEach { worker -> runCatching { worker.quiesce(QUIESCE_DEADLINE) } }
            session.jobs.forEach { it.cancel() }
            session.rasterJob?.cancel()
            session.jobs.forEach { runCatching { it.join() } }
            session.rasterJob?.let { runCatching { it.join() } }
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
            release("audio queue") { session.audioQueue?.close() }
            // Subtitles are resources like the other two paths: the decoder holds backend state and
            // the queue holds owned packets, and skipping them here leaked both (audit P1-13).
            release("subtitle decoder") { session.subtitleDecoder?.close() }
            release("subtitle queue") { session.subtitleQueue?.close() }
            runCatching { session.pendingSubtitlePacket?.close() }
            session.pendingSubtitlePacket = null
            // Closes the sink too: the audio path owns the device it was given.
            release("audio playback") { session.audio?.close() }
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
     * that failed (audit KP-P1-19). Only a failure while the source itself was being opened is a
     * source failure now; the rest name their stage and keep their cause.
     */
    private fun classify(failure: Throwable, item: MediaItem): PlaybackError = when {
        failure is PlaybackException -> failure.error
        openStage == OpenStage.Source ->
            PlaybackError.SourceUnavailable(item.uri, failure, failure.message)
        // Internal rather than AudioDeviceUnavailable, which carries no cause: the subsystem is
        // named in the detail and the original throwable survives, which a support bundle needs.
        openStage == OpenStage.Output -> PlaybackError.Internal(
            "the audio output could not be built for ${item.uri}: ${failure.message}",
            failure,
        )
        else -> PlaybackError.Internal(
            "the playback session failed while ${openStage.describe()} for ${item.uri}: " +
                "${failure.message}",
            failure,
        )
    }

    private suspend fun handleWorkerOutcome(outcome: WorkerOutcome) {
        val cause = outcome.cause ?: return
        if (cause is CancellationException) return
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
            outcome.name == DEMUX_WORKER -> PlaybackError.SourceUnavailable(
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

    /**
     * SOL-P6's dirty flag: the per-pass handler used to allocate a full snapshot EVERY pass,
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
            videoScale = videoScale,
            videoAdjustments = videoAdjustments,
            videoTransform = videoTransform,
            subtitleDelay = subtitleDelay,
            subtitleScale = subtitleScale,
            subtitlePosition = subtitlePosition,
            audioDelay = audioDelay,
            abLoopA = abLoopA,
            abLoopB = abLoopB,
            preservePitch = preservePitch,
            error = lastError,
            generation = requestedEpoch,
            queue = queueItems,
            queueIndex = queueIndex,
        )
        publishProgressAndStats()
    }

    /**
     * The interval-gated halves, shared by dirty and quiet passes (SOL-P6).
     *
     * [force] publishes regardless of the intervals, which is what a stop needs: the session is
     * gone, and leaving the last playing session's queue depths and drift on the flow describes
     * media that is no longer open (audit KP-P1-21).
     */
    private fun publishProgressAndStats(force: Boolean = false) {
        val session = session
        val now = clock.nanos()
        if (force || (now - lastProgressAtNanos).nanoseconds >= config.progressInterval) {
            lastProgressAtNanos = now
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
            // is the kind of thing worth being defended against twice (audit P1-18, KP-P1-21).
            val delta = (decoded - lastStatsDecoded).coerceAtLeast(0L)
            val fps = if (elapsed > Duration.ZERO) {
                delta * 1_000.0 / elapsed.inWholeMilliseconds.coerceAtLeast(1)
            } else {
                0.0
            }
            lastStatsDecoded = decoded
            // F-WRN1: the audit found these two documented warnings wired to nothing. Both read
            // the player-level total now, so the rising edge is a real onset rather than the
            // silent re-baselining a per-session counter produced at every reopen.
            val underrunsNow = retiredUnderruns + (session?.audio?.underruns ?: 0)
            if (underrunsNow > lastStatsUnderruns) {
                warn(PlaybackWarning.AudioUnderrun(underrunsNow))
            }
            lastStatsUnderruns = underrunsNow
            val droppedLateNow = retiredDroppedLate + (session?.video?.droppedFrames ?: 0)
            val droppedDelta = (droppedLateNow - lastStatsDroppedLate).coerceAtLeast(0)
            if (droppedDelta >= FRAME_DROP_WARN_PER_INTERVAL) {
                warn(PlaybackWarning.FrameDropping(droppedDelta.toInt()))
            }
            lastStatsDroppedLate = droppedLateNow
            statsState.value = PlaybackStats(
                decodedVideoFrames = decoded,
                submittedFrames = retiredSubmitted + (session?.renderer?.submittedFrames ?: 0),
                headlessFrames = retiredHeadless + (session?.renderer?.headlessFrames ?: 0),
                droppedFramesLate = droppedLateNow,
                refusedFrames = retiredRefused + (session?.video?.refusedFrames ?: 0),
                repeatedFrames = retiredRepeated + (session?.video?.repeatedFrames ?: 0),
                audioUnderruns = underrunsNow,
                droppedEvents = droppedEvents.value,
                rebuffers = rebuffers,
                avDrift = (session?.driftUs?.value ?: 0L).microseconds,
                videoDecodeFps = fps,
                videoQueueDepth = session?.video?.buffered ?: Duration.ZERO,
                audioQueueDepth = session?.audio?.buffered ?: Duration.ZERO,
                audioLatencyQuality = session?.audio?.latencyQuality ?: LatencyQuality.Unreliable,
                hardwareDecode = session?.videoDecoder?.hardware ?: HwdecStatus.Software,
                syncMode = config.syncMode,
                masterClock = masterClockKind(session),
            )
        }
    }

    /**
     * The M5 cache window as a time range, byte-to-time mapped PROPORTIONALLY (byte fraction
     * times duration). Exact for constant bitrate, approximate for variable, honest about both
     * in the Progress KDoc; empty whenever size or duration is unknown or no cache is running.
     */
    private fun bufferedRanges(session: OpenSession?): List<ClosedRange<Duration>> {
        val cache = session?.cachingIo ?: return emptyList()
        val sizeBytes = cache.size ?: return emptyList()
        if (sizeBytes <= 0L) return emptyList()
        val durationUs = session.source.duration?.micros ?: return emptyList()
        if (durationUs <= 0L) return emptyList()
        val start = cache.windowStartByte.value
        val end = cache.windowEndByte.value.coerceAtMost(sizeBytes)
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
        session.audio != null && config.syncMode != SyncMode.VideoMaster -> MasterClock.Audio
        session.video != null -> MasterClock.Video
        else -> MasterClock.None
    }

    /**
     * Every event leaves through here, and a loss is counted instead of ignored (audit KP-P1-09).
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
        if (!eventSink.tryEmit(event)) droppedEvents.incrementAndGet()
    }

    /** Events the buffer could not take. Published as a stat and printed in the dump. */
    private val droppedEvents = atomic(0L)

    private fun warn(warning: PlaybackWarning) {
        // The bounded history first (S4.d): the event feed replays nothing to a late collector,
        // and a bug report is exactly a late collector, so the record cannot live only there.
        kotlinx.atomicfu.locks.synchronized(warningFence) {
            warningLog.addLast(TimedWarning(clock.nanos(), warning))
            while (warningLog.size > WARNING_HISTORY_LIMIT) warningLog.removeFirst()
        }
        io.github.yuroyami.kiteplayer.KiteLog.log("kiteplayer", warning.message)
        emitEvent(PlayerEvent.Warning(warning))
    }

    /** Warnings this core emitted, oldest first, capped at [WARNING_HISTORY_LIMIT]. */
    fun warningHistory(): List<TimedWarning> =
        kotlinx.atomicfu.locks.synchronized(warningFence) { warningLog.toList() }

    private val warningFence = kotlinx.atomicfu.locks.SynchronizedObject()
    private val warningLog = ArrayDeque<TimedWarning>()

    /**
     * Everything a bug report needs, in one string (S4.d, carrying KD-7): the resolved
     * configuration, the backends by name, the tracks and selections, the three published
     * snapshots, the KD artifacts attached to the session, and the warning history. Reads only
     * published state, so it is safe from any thread at any moment, including after failure,
     * which is when it is usually called.
     */
    fun diagnosticsDump(redactPaths: Boolean = false): String = buildString {
        fun path(uri: String): String = if (redactPaths) uri.substringAfterLast('/') else uri
        val snapshot = snapshotState.value
        val liveStats = statsState.value
        val liveProgress = progressState.value
        appendLine("KitePlayer diagnostics")
        appendLine("status      ${snapshot.status}")
        appendLine("media       ${snapshot.media?.uri?.let(::path) ?: "none"}")
        snapshot.media?.openOptions?.takeIf { it.isNotEmpty() }?.let { options ->
            appendLine("openOptions $options (unconsumed keys warn typed at open)")
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
        appendLine("error       ${snapshot.error?.message ?: "none"}")
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
        appendLine("  underruns=${liveStats.audioUnderruns} rebuffers=${liveStats.rebuffers} " +
            "avDrift=${liveStats.avDrift} master=${liveStats.masterClock} hwdec=${liveStats.hardwareDecode}")
        // Anything but zero means this session's event feed is not a complete record, which a bug
        // report that reasons from the events needs to know before it reasons (audit KP-P1-09).
        appendLine("  eventsDropped=${liveStats.droppedEvents} (a full buffer: the collector was slower " +
            "than the session)")
        appendLine()
        appendLine("kd artifacts")
        // The filter the media item actually carries. This line used to say "none attached"
        // unconditionally, so a support bundle from a session running a filter graph denied that
        // the graph existed, which is the one fact such a bundle is collected to establish
        // (audit KP-P1-18). Typed filter plans are still roadmap work; a raw graph string is what
        // can be attached today, and it is what is reported.
        val attachedFilter = snapshot.media?.videoFilter
        appendLine(
            if (attachedFilter == null) {
                "  filters: none attached"
            } else {
                "  filters: video graph attached: $attachedFilter"
            },
        )
        appendLine()
        appendLine("warnings (${warningHistory().size} kept, cap $WARNING_HISTORY_LIMIT)")
        warningHistory().forEach { entry ->
            appendLine("  [${entry.atNanos}] ${entry.warning.message}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State reads shared by the handlers.
    // ---------------------------------------------------------------------------------------------

    private fun currentPosition(): Pts {
        val session = session ?: return Pts(publishedPositionMicros.value)
        // The same selector scheduling uses decides whose reading IS the position: under
        // VideoMaster the picture carries the timeline, and preferring audio here anyway made
        // position, relative seeks and subtitles follow a clock scheduling ignores (audit P1-12).
        // The other side is still the fallback, because a master that has produced no reading yet
        // beats a stale published number.
        val audioReading = session.audio?.position()
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
        // into an underrun or a blank first frame (audit P1-9). A stream that already ended is
        // exempt, because no more output can ever arrive for it.
        val videoReady = session.video == null ||
            session.video.queuedFrames > 0 ||
            session.videoQueue?.isEndOfStream == true
        val audioReady = session.audio == null ||
            session.audio.buffered > Duration.ZERO ||
            session.audioQueue?.isEndOfStream == true
        return videoReady && audioReady
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
        // F-WRN1: the sink's device events finally reach a listener. warn() is fence-locked,
        // so collecting on the session lane is safe from wherever the sink emits.
        session.audio?.let { audio ->
            session.jobs += scope.launch(dispatchers.session) {
                audio.events.collect { event ->
                    when (event) {
                        is io.github.yuroyami.kiteplayer.spi.AudioSinkEvent.DeviceLost ->
                            warn(PlaybackWarning.AudioDeviceChanged("device lost: " + event.detail))
                        is io.github.yuroyami.kiteplayer.spi.AudioSinkEvent.DeviceChanged ->
                            warn(PlaybackWarning.AudioDeviceChanged(event.detail))
                        else -> Unit
                    }
                }
            }
        }
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
                session.subtitleQueue?.signalEndOfStream(epoch)
                ended = true
                continue
            }
            when (packet.streamIndex) {
                session.videoStream?.index -> session.videoQueue?.offer(packet, epoch) ?: packet.close()
                session.audioStream?.index -> session.audioQueue?.offer(packet, epoch) ?: packet.close()
                session.subtitleStream?.index -> session.subtitleQueue?.offer(packet, epoch) ?: packet.close()
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
        // The subtitle queue counts toward the BYTE cap: its packets are real memory, and leaving
        // them out made the backlog invisible to the budget (audit P1-13). It stays out of the
        // duration cap, because subtitle streams are sparse: two cues can sit minutes apart, so
        // their buffered duration says nothing about read-ahead and would trip the cap instantly.
        val bytes = queues.sumOf { it.bytesBuffered } + (session.subtitleQueue?.bytesBuffered ?: 0L)
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
                    // The decoder contract lets send refuse while its output side is full, so the signal
                    // is only marked delivered when accepted; a refusal loops back through drainFrames
                    // and retries, otherwise the decoder never drains and end-of-file hangs forever.
                    if (videoDecoderSend(decoder, null)) ending = true
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
                while (!videoDecoderSend(decoder, packet)) {
                    // False means the decoder did NOT take this packet. A synchronous codec usually
                    // has output immediately, but Android's asynchronous internals can transiently
                    // expose neither an input slot nor an output frame. Retry in bounded steps so
                    // that ordinary readiness is not fatal and a seek can still park this worker.
                    val frame = videoDecoderReceive(decoder)
                    if (frame != null) {
                        session.decodedVideoFrames.incrementAndGet()
                        if (!handOver(session, worker, video, frame, epoch)) break
                    } else {
                        if (worker.quiesceRequested) break
                        delay(HANDOVER_RETRY)
                    }
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
        val frame = videoDecoderReceive(decoder) ?: return false
        session.decodedVideoFrames.incrementAndGet()
        handOver(session, worker, video, frame, epoch)
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
    ): Boolean {
        var ownsFrame = true
        try {
            if (frame.generation != epoch) return true
            // Recorded before the discard, because this is where the seek actually landed and that is what
            // the overshoot ladder has to judge. A precise seek that landed correctly still throws away up to
            // a whole group of pictures, so judging the landing by the first frame that survived the discard
            // would call every correct precise seek an overshoot.
            session.firstDecodedVideo.record(epoch, frame.pts)
            if (frame.pts.micros < session.discardBeforeUs.value) return true
            session.firstVideo.record(epoch, frame.pts)
            while (true) {
                // The offer itself is atomic. Until it succeeds this function still owns the frame, so
                // cancellation during the bounded retry closes it in finally instead of orphaning a
                // hardware output slot.
                if (video.trySubmit(frame)) {
                    ownsFrame = false
                    return true
                }
                if (worker.quiesceRequested) return false
                delay(HANDOVER_RETRY)
            }
        } finally {
            if (ownsFrame) frame.close()
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
                    // Same rule as the video worker: the drain signal counts only when accepted.
                    if (decoder.send(null)) ending = true
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
        var ownsBuffer = true
        try {
            if (buffer.generation != epoch) return true
            // Audio is trimmed to the target too, and for the same reason video is. A precise seek that
            // starts its sound at the keyframe plays up to a whole group of pictures of audio from before the
            // position that was asked for, which is the one part of a seek a listener hears immediately.
            // Whole buffers only: the one that straddles the target is kept, which is at most one buffer of
            // imprecision against a sample-exact trim that needs a filter this build does not have.
            val bufferEndUs = buffer.pts.micros + buffer.format.durationOf(buffer.frameCount).micros
            if (bufferEndUs <= session.discardBeforeUs.value) return true
            // Counted BEFORE the offer, not inside it: the feeder lowers this the moment it is done
            // with a buffer, and a count raised after a successful handoff could be lowered before
            // it was ever raised. An abandoned offer puts it back below (audit P0-20).
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
            }
            if (buffer == null) {
                // Nothing waiting. If the session has said the stream is over and the handoff is
                // provably empty, push the DSP tail into the ring and answer. This worker owns the
                // pipeline, so it is the only place that may (audit P0-20).
                if (session.audioEosRequested.value &&
                    !session.audioTailFlushed.value &&
                    session.audioInFlight.value == 0
                ) {
                    audio.finishDecoded { worker.quiesceRequested }
                    // Only after the tail is in the ring, so the terminal state cannot read this
                    // as done while a quiesce abandoned the submit half way.
                    if (!worker.quiesceRequested) session.audioTailFlushed.value = true
                }
                continue
            }
            try {
                if (buffer.generation != epoch) continue
                session.firstAudio.record(epoch, buffer.pts)
                var interleaved = interleaver.interleave(buffer)
                var pts = buffer.pts
                var frames = buffer.frameCount
                // Sample-exact trim of the one buffer that straddles the seek target. The decode
                // side drops whole buffers that END before the target; this slices the leading
                // pre-target samples off the survivor, so a precise seek starts its sound AT the
                // target instead of up to one buffer early (audit P1-10). Runs at most once per
                // seek, so the one copyOfRange is off any steady-state path.
                val discardBefore = session.discardBeforeUs.value
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
                if (frames == 0) continue
                // One call, no external timeout, no retry. The old shape cancelled submitDecoded
                // mid-buffer on a deadline and called it again with the same input, which replayed
                // samples the ring had already accepted and ran the stateful conversion twice
                // (audit P1-3). The abort callback bounds the wait instead: while the ring is full
                // the submit polls it, and a quiesce request abandons the unaccepted remainder,
                // which the seek's flush was about to discard anyway.
                audio.submitDecoded(pts, interleaved, frames, buffer.format) { worker.quiesceRequested }
            } finally {
                buffer.close()
                // Lowered only here, after the buffer is finished with on every path including the
                // epoch skip above, so the count covers the conversion and not just the queue.
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
                    // Released and not shown: a renderer that refuses still consumed the frame, so
                    // a gate counting successes alone would tick the whole queue away one frame at
                    // a time looking for a success that is never coming (audit KP-P1-06).
                    val before = session.framesReleased(video)
                    val wait = video.tick(masterPosition(session))
                    recordVideoClock(session, video)
                    if (session.framesReleased(video) > before) {
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
        // The audio-delay bias: reading the master AHEAD by the delay presents every frame that
        // much earlier, which is exactly what a sound that arrives late at the ear needs.
        else -> session.audio?.position()?.let { Pts(it.micros + audioDelay.inWholeMicroseconds) }
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
        val token: Long,
        val backendSession: BackendSession,
        val source: PlayerMediaSource,
        val videoStream: PlayerStreamInfo?,
        val audioStream: PlayerStreamInfo?,
        val videoDecoder: VideoDecoder?,
        val videoDecoderOrigin: VideoDecoderOrigin?,
        val audioDecoder: AudioDecoder?,
        val videoQueue: PacketQueue?,
        val audioQueue: PacketQueue?,
        val subtitleStream: PlayerStreamInfo?,
        val subtitleDecoder: io.github.yuroyami.kiteplayer.spi.SubtitleDecoder?,
        val subtitleQueue: PacketQueue?,
        val video: VideoPlayback?,
        val audio: AudioPlayback?,
        val sink: AudioSink?,
        val renderer: AttachableRenderer,
        val negotiatedFormat: AudioFormat?,
        /** Non-null when this open reads through the M5 byte cache; progress reads its window. */
        val cachingIo: CachingMediaIo? = null,
    ) {
        /** Between the audio decoder and the feeder. Small, because the ring is the real buffer. */
        val decodedAudio: Channel<AudioBuffer> = Channel(capacity = 4)

        /**
         * Decoded audio that has left the decoder and has not yet reached the device.
         *
         * Counts what is queued in [decodedAudio] AND the one buffer the feeder is converting, so
         * a reading of zero means the handoff really is empty rather than momentarily so. The end
         * of stream reads it: the packet queue emptying says only that demuxing finished, and up
         * to five buffers of real audio can still be in here when it does (audit P0-20).
         *
         * Raised by the decoder before it offers a buffer and lowered by the feeder once the
         * buffer is done with, in that order, so the count is an upper bound and never negative.
         */
        val audioInFlight = atomic(0)

        /**
         * The end-of-stream token for the audio lane, set by the session and read by the feeder.
         *
         * The feeder answers by flushing the DSP tail and setting [audioTailFlushed]. Both are
         * cleared by a flush, because a seek away from the end makes the stream un-ended.
         */
        val audioEosRequested = atomic(false)

        /** Set by the feeder once the DSP tail is in the ring. The terminal state waits for it. */
        val audioTailFlushed = atomic(false)

        val decodedVideoFrames = atomic(0L)
        val lastVideoPtsUs = atomic(NO_POSITION)
        val driftUs = atomic(0L)
        val discardBeforeUs = atomic(Long.MIN_VALUE)
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

        /** The one in-flight subtitle rasterisation; a newer cue edge cancels and replaces it. */
        var rasterJob: Job? = null

        /** Said once: the condition lasts as long as the file does. */
        var warnedAboutInterleaving: Boolean = false

        /** The cue store, session thread only, kept start-sorted. Cleared on every flush. */
        val subtitleCues: MutableList<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue> = mutableListOf()

        /**
         * A subtitle packet the decoder refused because its output side was full, retained so the
         * next pass retries it instead of losing the cue. Session thread only; closed on flush
         * and teardown like any other queued packet.
         */
        var pendingSubtitlePacket: io.github.yuroyami.kiteplayer.spi.PlayerPacket? = null

        /** What the last published overlay showed, so an unchanged set publishes nothing. */
        var publishedCueKey: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>? = null

        /**
         * Monotonic overlay identity. A hash of the cue content is collision-prone; a counter
         * bumped on every real change can never claim two different overlays are the same.
         */
        /** SOL-P5: bumped on the actor, read by the raster lane's stale-work guard. */
        val overlayGeneration = atomic(0L)

        var videoStatus: StreamStatus = StreamStatus.Syncing
        var audioStatus: StreamStatus = StreamStatus.Syncing

        val workers: List<Worker>
            get() = listOfNotNull(demuxWorker, videoDecodeWorker, audioDecodeWorker, audioFeedWorker, videoScheduler)

        /** SOL-P6: cached, because the queues are constructor-fixed and the hot handlers ask
         *  every pass; the old listOfNotNull allocated a list per call. */
        private val selectedQueuesCached: List<PacketQueue> = listOfNotNull(videoQueue, audioQueue)
        fun selectedQueues(): List<PacketQueue> = selectedQueuesCached

        fun decodersDrained(): Boolean =
            (videoDecoder?.isDrained ?: true) && (audioDecoder?.isDrained ?: true)

        fun anyWorkerFinished(): Boolean = workers.any { it.isFinished }

        /** Frames that reached a screen, or would have if one were attached. */
        fun framesOut(video: VideoPlayback?): Long {
            if (video == null) return 0
            return video.submittedFrames + video.headlessFrames
        }

        /**
         * Frames the schedule let go of, refusals included (audit KP-P1-06).
         *
         * What "one frame went out" must mean for any wait: a renderer that refuses everything
         * still consumed the frame, and a wait that only counted successes never ended.
         */
        fun framesReleased(video: VideoPlayback?): Long = video?.releasedFrames ?: 0

        val isStillImage: Boolean
            get() = videoStream != null && audioStream == null && (videoStream.isCoverArt || videoStream.isSparse)
    }

    private companion object {
        /** SOL-P5: how far behind the position container cues survive before pruning. */
        const val CUE_PRUNE_BEHIND_MICROS = 30_000_000L

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

        /** Overlay canvas for subtitles on audio-only media, where no video size exists. */
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

        /** Warnings kept for the dump (S4.d): enough for a session's story, bounded by contract. */
        const val WARNING_HISTORY_LIMIT = 64


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

    /** When the audio lane was first asked to push its DSP tail out, so that wait is bounded too. */
    var tailRequestedNanos: Long = 0

    /** The tail wait was bounded out rather than answered, so the tail is gone and it was said. */
    var tailAbandoned: Boolean = false

    /** The decoders are done and the device is playing out what it already holds. */
    var draining: Boolean = false

    /** When [draining] flipped, so the wait for the ring to empty is bounded (F-EOS1). */
    var drainStartedNanos: Long = 0

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
        drainStartedNanos = 0
        tailRequestedNanos = 0
        tailAbandoned = false
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

    /**
     * The seek was aborted before mutating anything, because its precondition could not be
     * established: a worker did not reach a quiescent boundary within the deadline. Flushing
     * decoders and clearing queues under a live worker is the memory-safety fault the audit
     * called P0-7, so the transaction refuses instead of proceeding on best effort.
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
        val items: List<MediaItem>,
        val startIndex: Int,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("openQueue", reply)

    class QueueNext(val reply: CompletableDeferred<Unit>) : CoreCommand("queueNext", reply)
    class QueuePrevious(val reply: CompletableDeferred<Unit>) : CoreCommand("queuePrevious", reply)

    class StepFrame(val reply: CompletableDeferred<Unit>) : CoreCommand("stepFrame", reply)

    class CaptureFrame(
        val reply: CompletableDeferred<io.github.yuroyami.kiteplayer.CapturedFrame>,
    ) : CoreCommand("captureFrame", reply)

    /**
     * Withdraws one abandoned [CaptureFrame] arm (audit KP-P1-04).
     *
     * Fire and forget, and identity-matched: a capture whose caller went away must clear its own
     * request and leave a newer one that already replaced it armed.
     */
    class WithdrawCapture(
        val request: CompletableDeferred<io.github.yuroyami.kiteplayer.CapturedFrame>,
    ) : CoreCommand("withdrawCapture", CompletableDeferred(Unit))
    class Play(val reply: CompletableDeferred<Unit>) : CoreCommand("play", reply)
    class Pause(val reply: CompletableDeferred<Unit>) : CoreCommand("pause", reply)
    class Seek(val request: SeekRequest, val reply: CompletableDeferred<SeekResult>) : CoreCommand("seek", reply)
    class Stop(val reply: CompletableDeferred<Unit>) : CoreCommand("stop", reply)
    class Close(val reply: CompletableDeferred<Unit>) : CoreCommand("close", reply)
    class SetSpeed(val value: Double, val reply: CompletableDeferred<Unit>) : CoreCommand("setSpeed", reply)
    class SetVolume(val value: Float, val reply: CompletableDeferred<Unit>) : CoreCommand("setVolume", reply)
    class SetMuted(val value: Boolean, val reply: CompletableDeferred<Unit>) : CoreCommand("setMuted", reply)
    class SetLoop(val mode: LoopMode, val reply: CompletableDeferred<Unit>) : CoreCommand("setLoop", reply)
    class SetAbLoop(val a: Duration?, val b: Duration?, val reply: CompletableDeferred<Unit>) : CoreCommand("setAbLoop", reply)
    class SetPreservePitch(val value: Boolean, val reply: CompletableDeferred<Unit>) : CoreCommand("setPreservePitch", reply)
    class SetVideoScale(val mode: VideoScale, val reply: CompletableDeferred<Unit>) : CoreCommand("setVideoScale", reply)
    class SetVideoAdjustments(val value: VideoAdjustments, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setVideoAdjustments", reply)
    class SetVideoTransform(val value: VideoTransform, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setVideoTransform", reply)
    class SetSubtitleDelay(val value: Duration, val reply: CompletableDeferred<Unit>) : CoreCommand("setSubtitleDelay", reply)
    class SetSubtitleScale(val value: Float, val reply: CompletableDeferred<Unit>) : CoreCommand("setSubtitleScale", reply)
    class SetSubtitlePosition(val value: Float, val reply: CompletableDeferred<Unit>) :
        CoreCommand("setSubtitlePosition", reply)
    class SetAudioDelay(val value: Duration, val reply: CompletableDeferred<Unit>) : CoreCommand("setAudioDelay", reply)
    class AddExternalSubtitle(val source: SubtitleSource, val reply: CompletableDeferred<TrackId>) :
        CoreCommand("addExternalSubtitle", reply)

    class SelectTrack(
        val kind: TrackKind,
        val track: TrackId?,
        val reply: CompletableDeferred<TrackChange>,
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
