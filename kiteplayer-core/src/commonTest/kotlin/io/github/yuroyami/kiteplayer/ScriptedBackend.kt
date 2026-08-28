package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.AudioDecoderFactory
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.random.Random

/**
 * A whole media item written as a script, and a backend that plays it.
 *
 * This is the other half of what makes the engine testable. Time already enters through
 * [MonotonicClock], so a session can be driven through hours of media in a few virtual milliseconds;
 * what was missing was media that behaves exactly as a test says, including behaving badly. Everything
 * here is deterministic given a seed, allocates its own frames and packets through a [LeakLedger], and
 * carries the generation it belongs to in its sample values, so a test can prove that nothing from a
 * superseded epoch was ever heard rather than merely that nothing was presented.
 *
 * Nothing here is a mock in the usual sense: there are no expectations and no verification of calls.
 * The scripted pieces obey the same contracts a real backend obeys, and the assertions are about what
 * the engine did with them.
 */
internal data class ScriptedAudioTrack(
    val index: Int,
    val marker: Float,
    val language: String = "und",
    val title: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val isDefault: Boolean = false,
    /** False makes the scripted decoder factory refuse this specific track. */
    val decoderAccepted: Boolean = true,
    /** True marks the track as descriptive/accessibility audio (SALANKE S11). */
    val isAccessibility: Boolean = false,
    /** Optional early packet cutoff, used to model an alternate cache that stops growing. */
    val packetEndUs: Long? = null,
    /** Virtual preparation cost before this track's decoder is returned. */
    val decoderCreateDelayUs: Long = 0,
    /** Bytes per scripted packet, so one lane can dominate a byte budget the way a lossless track does. */
    val packetSizeBytes: Int = 1024,
    /** False models FFmpeg's routine zero duration: the packet carries a start and no duration. */
    val packetDurationKnown: Boolean = true,
) {
    fun format(defaultSampleRate: Int, defaultChannels: Int): AudioFormat = AudioFormat(
        sampleRate = sampleRate ?: defaultSampleRate,
        channels = channels ?: defaultChannels,
        sampleFormat = SampleFormat.F32,
    )

    init {
        require(packetEndUs == null || packetEndUs >= 0) { "packetEndUs must not be negative" }
        require(decoderCreateDelayUs >= 0) { "decoderCreateDelayUs must not be negative" }
    }
}

internal data class ScriptedSubtitleTrack(
    val index: Int,
    val cues: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
    val language: String = "und",
    val title: String? = null,
    val isDefault: Boolean = false,
    /** True marks the track as forced subtitles (SALANKE S02). */
    val isForced: Boolean = false,
    /** False makes the scripted decoder factory refuse this specific track. */
    val decoderAccepted: Boolean = true,
) {
    val cuesByStart: Map<Long, List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>> =
        cues.groupBy { it.startMicros }

    val packets: List<ScriptedSubtitlePacket> = cuesByStart
        .map { (startMicros, atStart) ->
            ScriptedSubtitlePacket(
                startMicros = startMicros,
                endMicros = atStart.maxOf { it.endMicros },
            )
        }
        .sortedBy { it.startMicros }
}

internal class MediaScript(
    val durationUs: Long = 4_000_000,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true,
    /** The chapter table the scripted container declares (S4.e). */
    val chapters: List<Chapter> = emptyList(),
    /** 40 ms, which is 25 frames a second. */
    val videoFrameDurationUs: Long = 40_000,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    /** Sample frames in one decoded audio buffer. 1024 is what AAC produces. */
    val audioBufferFrames: Int = 1024,
    /** How far apart the keyframes are. A seek can only land on one of these. */
    val keyframeIntervalUs: Long = 400_000,
    /** True makes the only video stream a still image, which must never carry the timeline. */
    val videoIsCoverArt: Boolean = false,
    val seekable: Boolean = true,
    /**
     * How far past the requested target a seek lands, before rounding down to a keyframe.
     *
     * Zero is what an indexed container does. A positive value is the container that resolves a seek by
     * byte position and overshoots, which is what the overshoot backoff ladder exists for.
     */
    val seekOvershootUs: Long = 0,
    /**
     * How long each packet read takes, in microseconds of the test's clock.
     *
     * Zero is a local file, which reads faster than playback consumes and therefore never starves anything.
     * A value above the media time one packet carries is a source slower than real time, which is what a
     * network stall looks like from the engine's side and the only way to make the demuxer run short.
     */
    val readDelayUs: Long = 0,
    /**
     * True emits every video packet before any audio packet, which is a container interleaved as badly as
     * one can be.
     *
     * Real files are interleaved within a fraction of a second. A file like this one is what the read-ahead
     * budget meets head on: the video queue reaches the cap while the audio queue is empty, the audio
     * decoder starves, its clock stops, nothing is consumed, and the budget is never freed. It exists here
     * because that deadlock has to be provably answered.
     */
    val badlyInterleaved: Boolean = false,
    /** Scripted subtitle cues. Non-empty adds a subtitle stream whose packets carry them. */
    val subtitleCues: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue> = emptyList(),
    val subtitleLanguage: String = "eng",
    /** Counts the scripted decoder's work without putting timing assumptions into a virtual-time test. */
    val subtitleProbe: ScriptedSubtitleProbe = ScriptedSubtitleProbe(),
    /** Extra container audio tracks. Explicit indices make identity assertions unambiguous. */
    val additionalAudioTracks: List<ScriptedAudioTrack> = emptyList(),
    /** Extra container subtitle tracks. Explicit indices make identity assertions unambiguous. */
    val additionalSubtitleTracks: List<ScriptedSubtitleTrack> = emptyList(),
) {
    val videoIndex: Int = 0
    val audioIndex: Int = if (hasVideo) 1 else 0
    val subtitleIndex: Int = (if (hasVideo) 1 else 0) + (if (hasAudio) 1 else 0)
    val audioBufferDurationUs: Long = audioBufferFrames.toLong() * 1_000_000L / sampleRate

    val audioTracks: List<ScriptedAudioTrack> = buildList {
        if (hasAudio) {
            add(
                ScriptedAudioTrack(
                    index = audioIndex,
                    marker = 1f,
                    language = "eng",
                    title = "scripted audio A",
                    sampleRate = sampleRate,
                    channels = channels,
                    isDefault = true,
                ),
            )
        }
        addAll(additionalAudioTracks)
    }

    val subtitleTracks: List<ScriptedSubtitleTrack> = buildList {
        if (subtitleCues.isNotEmpty()) {
            add(
                ScriptedSubtitleTrack(
                    index = subtitleIndex,
                    cues = subtitleCues,
                    language = subtitleLanguage,
                    title = "scripted subtitle A",
                    isDefault = true,
                ),
            )
        }
        addAll(additionalSubtitleTracks)
    }

    val hasSubtitles: Boolean get() = subtitleTracks.isNotEmpty()
    val hasAnyAudio: Boolean get() = audioTracks.isNotEmpty()

    /**
     * One container packet per subtitle timestamp, built once.
     *
     * The old script emitted one packet per cue and then made the decoder scan every cue for the
     * packet's timestamp. Besides turning a 70k-cue regression into billions of test-harness
     * comparisons, two cues with the same start produced both cues twice. A real subtitle packet
     * can decode to several cues, so grouping equal starts is both the faithful model and the
     * constant-time lookup the workload needs.
     */
    internal val subtitleCuesByStart:
        Map<Long, List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>> =
        subtitleTracks.firstOrNull { it.index == subtitleIndex }?.cuesByStart.orEmpty()

    internal val subtitlePackets: List<ScriptedSubtitlePacket> =
        subtitleTracks.firstOrNull { it.index == subtitleIndex }?.packets.orEmpty()

    init {
        val streamIndices = buildList {
            if (hasVideo) add(videoIndex)
            addAll(audioTracks.map { it.index })
            addAll(subtitleTracks.map { it.index })
        }
        require(streamIndices.distinct().size == streamIndices.size) {
            "scripted stream indices must be unique, were $streamIndices"
        }
        audioTracks.forEach { track ->
            require(track.marker.isFinite() && track.marker > 0f) {
                "audio marker for stream ${track.index} must be finite and positive"
            }
        }
    }

    fun format(): AudioFormat = AudioFormat(
        sampleRate = sampleRate,
        channels = channels,
        sampleFormat = SampleFormat.F32,
    )
}

/** The timing carried by one scripted subtitle packet. Its cues live in [MediaScript.subtitleCuesByStart]. */
internal data class ScriptedSubtitlePacket(
    val startMicros: Long,
    val endMicros: Long,
)

/**
 * Operation-counting probe for subtitle tests.
 *
 * Virtual time cannot reveal CPU monopolisation. These counters instead expose the algorithmic
 * work: every packet must perform exactly one keyed lookup, however many cues the whole file has.
 * [onPacketSent] also lets a test enqueue a real player command from inside the actor's subtitle
 * drain, reproducing a command that arrives concurrently on a device without thread races.
 */
internal class ScriptedSubtitleProbe {
    var packetsSent: Int = 0
        private set
    var cueLookups: Int = 0
        private set
    var cuesReturned: Int = 0
        private set
    var onPacketSent: ((Int) -> Unit)? = null

    fun recordLookup(cueCount: Int) {
        cueLookups++
        cuesReturned += cueCount
        packetsSent++
        onPacketSent?.invoke(packetsSent)
    }
}

/**
 * What goes wrong, decided in advance from a seed.
 *
 * Fault injection is seeded rather than random so a failing run is a name a test can be written
 * against. The rates are per event and small: the point is not to make playback impossible, it is to
 * make every recovery path run often enough that a hundred seeds cover them all.
 */
internal class FaultPlan(
    seed: Int = 0,
    /** Chance in a hundred that a decoder refuses a packet it could have taken. */
    private val refuseSendPercent: Int = 0,
    /** Chance in a hundred that a decode produces nothing from a packet that should have decoded. */
    private val emptyDecodePercent: Int = 0,
    /** Chance in a hundred that the renderer refuses a frame, the way a lost surface refuses. */
    private val refusePresentPercent: Int = 0,
    /** Chance in a hundred that a packet read fails outright. */
    private val readFailsPercent: Int = 0,
) {
    private val random = Random(seed)

    /** True makes the source throw on the read after this many successful ones. */
    var failReadAfter: Int? = null

    /** True makes every video decoder factory refuse, so the video stream has to be deselected. */
    var videoDecodersRefuse: Boolean = false

    /** True makes every audio decoder factory refuse. */
    var audioDecodersRefuse: Boolean = false

    /** True makes [ScriptedSource.selectStreams] throw, which is buildSession's reachable thrower
     * AFTER the audio path has gone live (interlude item I-03). */
    var failSelectStreams: Boolean = false

    /** True parks every audio decoder's receive until cancellation, a worker that refuses to reach
     * a quiescent boundary (interlude item I-02). */
    var stallAudioDecodeReceive: Boolean = false

    /** True makes the video decoder accept every packet and never produce a frame. */
    var videoDecodeProducesNothing: Boolean = false

    /**
     * Receive throws after this many delivered frames, but only while the decoder reports a
     * hardware status: the shape of a hardware session dying mid-play (VideoToolbox invalidated by
     * backgrounding, a MediaCodec error). The software decoder a recovery builds stays healthy.
     */
    var videoDecodeFailsAfterFrames: Int? = null

    /** True makes opening the sink fail. */
    var sinkOpenFails: Boolean = false

    /** True wedges the next container seek like an uncancellable native scan (KC-CANCEL). */
    var seekWedges: Boolean = false

    /** Reads beyond this count wedge like an uncancellable native read. Null wedges nothing. */
    var readWedgesAfter: Int? = null

    /** False models a source whose interrupt() cannot help, the pre-KC-CANCEL world. */
    var interruptSupported: Boolean = true

    /** True makes the sink's drain never finish, which the core must bound rather than wait out. */
    var drainHangs: Boolean = false

    /** True makes closing the backend session throw, which close must survive. */
    var sessionCloseThrows: Boolean = false

    /**
     * True parks `stop()` for as long as it stays true: a device close that has wedged.
     *
     * Armable and disarmable, and the parked call rechecks it, so a test can wedge the terminal
     * release, prove the close reports a compromised runtime rather than hanging, and then let the
     * release finish so nothing is left parked on the scheduler (audit KP-P1-07).
     */
    var stopHangs: Boolean = false

    fun refuseSend(): Boolean = roll(refuseSendPercent)
    fun emptyDecode(): Boolean = roll(emptyDecodePercent)
    fun refusePresent(): Boolean = roll(refusePresentPercent)
    fun failRead(reads: Int): Boolean = failReadAfter == reads || roll(readFailsPercent)

    private fun roll(percent: Int): Boolean = percent > 0 && random.nextInt(100) < percent

    companion object {
        val None: FaultPlan get() = FaultPlan()
    }
}

/**
 * What the pipeline was asked to do, in order.
 *
 * The seek sequence is an ordering contract, so proving it needs the order and not just the effects. The
 * scripted device, decoders and source each write one line here, which is enough to show that the device
 * was stopped before a decoder was flushed and that both happened before the cursor moved.
 */
internal class ScriptTrace {
    val entries: MutableList<String> = mutableListOf()

    fun record(what: String) {
        entries += what
    }

    fun clear() {
        entries.clear()
    }

}

/**
 * The scripted subtitle decoder: a packet's pts names the cue it carries, and receive hands the
 * cue over exactly once per delivery. Flush drops undelivered cues, the way a real decoder's
 * epoch boundary does.
 */
internal class ScriptedSubtitleDecoderFactory(
    private val script: MediaScript,
) : SubtitleDecoderFactory {
    override val name: String = "scripted-subtitle"
    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? {
        val track = script.subtitleTracks.firstOrNull { it.index == stream.index } ?: return null
        if (!track.decoderAccepted) return null
        return ScriptedSubtitleDecoder(track, script.subtitleProbe)
    }
}

internal class ScriptedSubtitleDecoder(
    private val track: ScriptedSubtitleTrack,
    private val probe: ScriptedSubtitleProbe,
) : SubtitleDecoder {
    private val pending = ArrayDeque<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>()
    var closed: Boolean = false
        private set

    override suspend fun send(packet: PlayerPacket?): Boolean {
        if (packet == null) return true
        val pts = packet.pts?.micros ?: return true
        val cues = track.cuesByStart[pts].orEmpty()
        pending.addAll(cues)
        probe.recordLookup(cues.size)
        return true
    }

    override suspend fun receive(): List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue> {
        if (pending.isEmpty()) return emptyList()
        val out = pending.toList()
        pending.clear()
        return out
    }

    override suspend fun flush(newGeneration: Generation) {
        pending.clear()
    }

    override fun close() {
        closed = true
    }
}

/** The scripted backend. One session per [open]. */
internal class ScriptedBackend(
    private val script: MediaScript = MediaScript(),
    private val ledger: LeakLedger = LeakLedger(),
    private val faults: FaultPlan = FaultPlan.None,
    private val trace: ScriptTrace = ScriptTrace(),
) : MediaBackend {

    /** Mutable decoder truth used to prove that stats do not retain an open-time hardware claim. */
    val videoDecoderStatus: ScriptedVideoDecoderStatus = ScriptedVideoDecoderStatus()

    var openCalls: Int = 0
        private set

    /** Sessions this backend handed out, oldest first. */
    val sessions: MutableList<ScriptedSession> = mutableListOf()

    /** Completed by a test to let a suspended [open] finish. Null means open does not wait. */
    var openGate: CompletableDeferred<Unit>? = null

    /** Thrown by [open] instead of returning a session. */
    var openFailure: Throwable? = null

    /**
     * A ten-line SRT-only parser for the external-subtitle tests (S4.e). The real WebVTT and
     * SubRip parsers live in kiteplayer-subtitles, above this module's dependency arrow; the
     * engine's contract only needs A parser here, and the format goldens live with the real ones.
     */
    override fun subtitleFileParser(): io.github.yuroyami.kiteplayer.spi.SubtitleFileParser =
        io.github.yuroyami.kiteplayer.spi.SubtitleFileParser { text, _ ->
            // A two-line ASS branch so the engine's format LABELLING is testable here: real ASS
            // parsing lives in kiteplayer-subtitles, above this module's dependency arrow.
            if (text.trimStart('﻿', ' ', '\r', '\n').startsWith("[Script Info]", ignoreCase = true)) {
                return@SubtitleFileParser text.lineSequence()
                    .filter { it.startsWith("Dialogue:") }
                    .map { line ->
                        io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text(
                            startMicros = 500_000,
                            endMicros = 2_000_000,
                            spans = listOf(
                                io.github.yuroyami.kiteplayer.subtitle.StyledSpan(line.substringAfterLast(',')),
                            ),
                        )
                    }
                    .toList()
            }
            val timing = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3}) --> (\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")
            text.split(Regex("\r?\n\r?\n")).mapNotNull { block ->
                val lines = block.trim().lines()
                val at = lines.indexOfFirst { timing.matches(it.trim()) }
                if (at < 0 || at + 1 > lines.lastIndex) return@mapNotNull null
                val m = timing.matchEntire(lines[at].trim())!!.groupValues.drop(1).map { it.toLong() }
                fun micros(h: Long, min: Long, s: Long, ms: Long) = ((h * 3600 + min * 60 + s) * 1000 + ms) * 1000
                io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text(
                    startMicros = micros(m[0], m[1], m[2], m[3]),
                    endMicros = micros(m[4], m[5], m[6], m[7]),
                    spans = listOf(
                        io.github.yuroyami.kiteplayer.subtitle.StyledSpan(
                            lines.drop(at + 1).joinToString("\n"),
                        ),
                    ),
                )
            }
        }

    /** The exact item the engine handed over on the LAST open, resolver and cache applied. */
    var lastOpenedItem: MediaItem? = null

    override suspend fun open(media: MediaItem): BackendSession {
        openCalls++
        lastOpenedItem = media
        openGate?.await()
        openFailure?.let { throw it }
        return ScriptedSession(script, ledger, faults, trace, videoDecoderStatus).also { sessions += it }
    }
}

internal class ScriptedSession(
    private val script: MediaScript,
    private val ledger: LeakLedger,
    private val faults: FaultPlan,
    private val trace: ScriptTrace = ScriptTrace(),
    videoDecoderStatus: ScriptedVideoDecoderStatus = ScriptedVideoDecoderStatus(),
) : BackendSession {

    val scriptedSource: ScriptedSource = ScriptedSource(script, ledger, faults, trace)

    override val source: PlayerMediaSource get() = scriptedSource

    val videoDecoderPolicies: MutableList<HwdecPolicy> = mutableListOf()

    override val videoDecoders: List<VideoDecoderFactory> =
        listOf(
            ScriptedVideoDecoderFactory(
                script,
                ledger,
                faults,
                trace,
                videoDecoderStatus,
                videoDecoderPolicies,
            ),
        )

    /** Every audio decoder handed to the core, retained only as a lifecycle probe for tests. */
    val audioDecoderInstances: MutableList<ScriptedAudioDecoder> = mutableListOf()

    override val audioDecoders: List<AudioDecoderFactory> =
        listOf(
            ScriptedAudioDecoderFactory(script, ledger, faults, trace) { decoder ->
                audioDecoderInstances += decoder
            },
        )

    override val subtitleDecoders: List<SubtitleDecoderFactory> =
        if (script.hasSubtitles) listOf(ScriptedSubtitleDecoderFactory(script)) else emptyList()

    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
        scriptedSource.close()
        if (faults.sessionCloseThrows) error("the scripted session refuses to close")
    }
}

/**
 * A cursor over scripted packets.
 *
 * Packets come out in timestamp order across the selected streams, which is what a well interleaved
 * container gives, and each carries the duration its stream implies, so the engine's buffering
 * arithmetic has real numbers to work with.
 */
internal class ScriptedSource(
    private val script: MediaScript,
    private val ledger: LeakLedger,
    private val faults: FaultPlan,
    private val trace: ScriptTrace = ScriptTrace(),
) : PlayerMediaSource {

    override val streams: List<PlayerStreamInfo> = buildList {
        if (script.hasVideo) {
            add(
                PlayerStreamInfo(
                    index = script.videoIndex,
                    kind = TrackKind.Video,
                    codec = "scripted-video",
                    isDefault = true,
                    startTime = Pts.Zero,
                    videoSize = VideoSize(1920, 1080),
                    frameRate = 1_000_000.0 / script.videoFrameDurationUs,
                    isCoverArt = script.videoIsCoverArt,
                ),
            )
        }
        script.subtitleTracks.forEach { track ->
            add(
                PlayerStreamInfo(
                    index = track.index,
                    kind = TrackKind.Subtitle,
                    codec = "scripted-subtitle",
                    language = track.language,
                    title = track.title,
                    isDefault = track.isDefault,
                    isForced = track.isForced,
                ),
            )
        }
        script.audioTracks.forEach { track ->
            val format = track.format(script.sampleRate, script.channels)
            add(
                PlayerStreamInfo(
                    index = track.index,
                    kind = TrackKind.Audio,
                    codec = "scripted-audio",
                    language = track.language,
                    title = track.title,
                    isDefault = track.isDefault,
                    isAccessibility = track.isAccessibility,
                    startTime = Pts.Zero,
                    sampleRate = format.sampleRate,
                    channels = format.channels,
                ),
            )
        }
    }

    override val duration: Pts = Pts(script.durationUs)
    override val seekable: Boolean = script.seekable
    override val metadata: Map<String, String> = mapOf("title" to "scripted")
    override val chapters: List<Chapter> = script.chapters
    override val timestampsMayJump: Boolean = false

    private var selected: Set<Int> = emptySet()
    private var videoCursorUs = 0L
    private val audioCursorsUs: MutableMap<Int, Long> =
        script.audioTracks.associate { it.index to 0L }.toMutableMap()
    private val subtitleCursors: MutableMap<Int, Int> =
        script.subtitleTracks.associate { it.index to 0 }.toMutableMap()
    private val subtitleSeekFloorsUs: MutableMap<Int, Long> =
        script.subtitleTracks.associate { it.index to Long.MIN_VALUE }.toMutableMap()

    /** Timestamp of the furthest packet event this one demux cursor has passed. */
    var demuxFrontierUs: Long = 0L
        private set

    var reads: Int = 0
        private set
    var seeks: Int = 0
        private set
    val seekTargets: MutableList<Long> = mutableListOf()
    var selectCalls: Int = 0
        private set
    var interruptCalls: Int = 0
        private set
    private val interruptGate = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val wedgeReleased = kotlinx.coroutines.CompletableDeferred<Unit>()

    override fun interrupt(): Boolean {
        interruptCalls++
        if (!faults.interruptSupported) return false
        interruptGate.complete(Unit)
        return true
    }

    /** Lets a wedged call return NORMALLY, the way a slow-but-honest scan eventually finishes. */
    fun releaseWedge() {
        wedgeReleased.complete(Unit)
    }

    /**
     * Models an uncancellable native call: suspends immune to cancellation until either the
     * interrupt seam fires (the call then fails, a poisoned source) or [releaseWedge] lets it
     * finish normally. Once released, later calls stop wedging.
     */
    private suspend fun wedge(what: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            while (!interruptGate.isCompleted && !wedgeReleased.isCompleted) {
                kotlinx.coroutines.delay(10)
            }
        }
        if (interruptGate.isCompleted) error("the scripted $what was interrupted")
    }
    val selectionHistory: MutableList<Set<Int>> = mutableListOf()
    var closed: Boolean = false
        private set

    override fun selectStreams(indices: Set<Int>) {
        if (faults.failSelectStreams) error("scripted selectStreams failure (interlude item I-03)")
        check(selectCalls == 0) { "streams must be selected before the first read" }
        require(indices.isNotEmpty()) { "no selectable stream among $indices" }
        require(indices.all { wanted -> streams.any { it.index == wanted } }) {
            "unknown scripted stream in $indices"
        }
        selected = indices.toSet()
        selectionHistory += selected
        selectCalls++
    }

    private data class SubtitleCandidate(
        val track: ScriptedSubtitleTrack,
        val packet: ScriptedSubtitlePacket,
    )

    /** Finds the earliest selected subtitle packet that has reached the interleaved A/V cursor. */
    private fun subtitleCandidate(mediaCursorUs: Long): SubtitleCandidate? {
        var best: SubtitleCandidate? = null
        for (track in script.subtitleTracks) {
            if (track.index !in selected) continue
            var cursor = subtitleCursors.getValue(track.index)
            val seekFloor = subtitleSeekFloorsUs.getValue(track.index)
            // File order, like the real source: a packet interleaved before the seek landing is
            // behind the demux cursor and is NOT redelivered, even when its cue still spans the
            // landing. That missing cue is SALANKE S16, and this model must not paper over it.
            while (
                cursor < track.packets.size &&
                track.packets[cursor].startMicros < seekFloor
            ) {
                cursor++
            }
            subtitleCursors[track.index] = cursor
            val packet = track.packets.getOrNull(cursor) ?: continue
            if (packet.startMicros > mediaCursorUs) continue
            val current = best
            if (current == null || packet.startMicros < current.packet.startMicros ||
                packet.startMicros == current.packet.startMicros && track.index < current.track.index
            ) {
                best = SubtitleCandidate(track, packet)
            }
        }
        return best
    }

    private fun audioDurationUs(track: ScriptedAudioTrack): Long =
        script.audioBufferFrames.toLong() * 1_000_000L /
            track.format(script.sampleRate, script.channels).sampleRate

    private fun audioCandidate(): ScriptedAudioTrack? = script.audioTracks
        .asSequence()
        .filter { track ->
            val trackEndUs = minOf(script.durationUs, track.packetEndUs ?: Long.MAX_VALUE)
            track.index in selected && audioCursorsUs.getValue(track.index) < trackEndUs
        }
        .minWithOrNull(compareBy({ audioCursorsUs.getValue(it.index) }, { it.index }))

    private fun packetRead(packet: FakePacket): FakePacket {
        demuxFrontierUs = maxOf(demuxFrontierUs, packet.pts?.micros ?: demuxFrontierUs)
        return packet
    }

    override suspend fun readPacket(): PlayerPacket? {
        check(selectCalls > 0) { "selectStreams must be called before readPacket" }
        reads++
        faults.readWedgesAfter?.let { limit ->
            if (reads > limit && !wedgeReleased.isCompleted) wedge("read")
        }
        if (faults.failRead(reads)) error("the scripted source failed on read $reads")
        if (script.readDelayUs > 0) delay(script.readDelayUs / 1_000)

        val video = script.videoIndex.takeIf {
            script.hasVideo && it in selected && videoCursorUs < script.durationUs
        }
        val audio = audioCandidate()
        val mediaCursor = minOf(
            if (video != null) videoCursorUs else Long.MAX_VALUE,
            audio?.let { audioCursorsUs.getValue(it.index) } ?: Long.MAX_VALUE,
        )
        subtitleCandidate(mediaCursor)?.let { candidate ->
            subtitleCursors[candidate.track.index] = subtitleCursors.getValue(candidate.track.index) + 1
            return packetRead(
                FakePacket(
                    streamIndex = candidate.track.index,
                    pts = Pts(candidate.packet.startMicros),
                    duration = Pts(candidate.packet.endMicros - candidate.packet.startMicros),
                    isKeyframe = true,
                    ledger = ledger,
                ),
            )
        }

        val pickVideo = when {
            video == null -> false
            audio == null -> true
            script.badlyInterleaved -> true
            else -> videoCursorUs <= audioCursorsUs.getValue(audio.index)
        }
        return when {
            pickVideo -> {
                val pts = videoCursorUs
                videoCursorUs += script.videoFrameDurationUs
                packetRead(
                    FakePacket(
                        streamIndex = script.videoIndex,
                        pts = Pts(pts),
                        duration = Pts(script.videoFrameDurationUs),
                        isKeyframe = pts % script.keyframeIntervalUs == 0L,
                        ledger = ledger,
                    ),
                )
            }
            audio != null -> {
                val pts = audioCursorsUs.getValue(audio.index)
                val durationUs = audioDurationUs(audio)
                audioCursorsUs[audio.index] = pts + durationUs
                packetRead(
                    FakePacket(
                        streamIndex = audio.index,
                        pts = Pts(pts),
                        duration = if (audio.packetDurationKnown) Pts(durationUs) else null,
                        isKeyframe = true,
                        sizeBytes = audio.packetSizeBytes,
                        ledger = ledger,
                    ),
                )
            }
            else -> null
        }
    }

    override suspend fun seekToKeyframe(target: Pts): Pts? {
        check(selectCalls > 0) { "selectStreams must be called before seeking" }
        seeks++
        if (faults.seekWedges && !wedgeReleased.isCompleted) wedge("seek")
        seekTargets += target.micros
        trace.record("source.seek")
        val aimed = (target.micros + script.seekOvershootUs).coerceIn(0L, script.durationUs)
        val landing = aimed / script.keyframeIntervalUs * script.keyframeIntervalUs
        for (track in script.subtitleTracks) {
            subtitleSeekFloorsUs[track.index] = landing
            // Redelivery starts at the landing in FILE order, exactly like a backward
            // avformat_seek_file: a packet whose start sits before the landing is never re-read,
            // however long its cue lasts (SALANKE S16).
            subtitleCursors[track.index] = track.packets.indexOfFirst {
                it.startMicros >= landing
            }.takeIf { it >= 0 } ?: track.packets.size
        }
        videoCursorUs = landing
        script.audioTracks.forEach { audioCursorsUs[it.index] = landing }
        demuxFrontierUs = landing
        // Like libavformat, this cursor does not report where it landed. The engine finds out from the
        // first decoded frame, which is also how it detects an overshoot.
        return null
    }

    override fun close() {
        closed = true
    }
}

private class ScriptedVideoDecoderFactory(
    private val script: MediaScript,
    private val ledger: LeakLedger,
    private val faults: FaultPlan,
    private val trace: ScriptTrace,
    private val hardwareStatus: ScriptedVideoDecoderStatus,
    private val policies: MutableList<HwdecPolicy>,
) : VideoDecoderFactory {
    override val name: String = "scripted video"
    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
        policies += hwdec
        if (stream.kind != TrackKind.Video) return null
        if (faults.videoDecodersRefuse) return null
        // Off is a promise, not a preference: the decoder built under it reports Software no
        // matter what status the test injected for the hardware attempt it is recovering from.
        val status = if (hwdec == HwdecPolicy.Off) ScriptedVideoDecoderStatus() else hardwareStatus
        return ScriptedVideoDecoder(script, ledger, faults, trace, status)
    }
}

/** Cross-thread-safe mutable status injected into the scripted decoder. */
internal class ScriptedVideoDecoderStatus(initial: HwdecStatus = HwdecStatus.Software) {
    private val current = atomic<HwdecStatus>(initial)

    var value: HwdecStatus
        get() = current.value
        set(value) {
            current.value = value
        }
}

/**
 * A decoder with a real send and receive shape: it holds a few frames, refuses input when full, and
 * reports its own drain rather than leaving the engine to guess how many null receives are enough.
 */
internal class ScriptedVideoDecoder(
    private val script: MediaScript,
    private val ledger: LeakLedger,
    private val faults: FaultPlan,
    private val trace: ScriptTrace = ScriptTrace(),
    private val hardwareStatus: ScriptedVideoDecoderStatus = ScriptedVideoDecoderStatus(),
) : VideoDecoder {

    override val hardware: HwdecStatus get() = hardwareStatus.value

    private val pending = ArrayDeque<VideoFrame>()
    private var generation: Generation = Generation.Initial
    private var drained = false
    private var ending = false
    private var delivered = 0

    var closed: Boolean = false
        private set
    var flushes: Int = 0
        private set

    override val isDrained: Boolean get() = drained

    override suspend fun send(packet: PlayerPacket?): Boolean {
        if (pending.size >= CAPACITY) return false
        if (packet == null) {
            ending = true
            return true
        }
        if (faults.refuseSend()) return false
        if (faults.emptyDecode() || faults.videoDecodeProducesNothing) return true
        val pts = packet.pts ?: Pts.Zero
        pending.addLast(
            FakeVideoFrame(
                pts = pts,
                generation = generation,
                duration = Pts(script.videoFrameDurationUs),
                ledger = ledger,
            ),
        )
        return true
    }

    override suspend fun receive(): VideoFrame? {
        val failAfter = faults.videoDecodeFailsAfterFrames
        if (failAfter != null && hardware != HwdecStatus.Software && delivered >= failAfter) {
            error("scripted hardware video decode failed after $delivered frames")
        }
        val frame = pending.removeFirstOrNull()
        if (frame == null && ending) drained = true
        if (frame != null) delivered++
        return frame
    }

    override suspend fun flush(newGeneration: Generation) {
        flushes++
        trace.record("video.flush")
        pending.forEach { it.close() }
        pending.clear()
        generation = newGeneration
        drained = false
        ending = false
    }

    override fun close() {
        closed = true
        pending.forEach { it.close() }
        pending.clear()
    }

    private companion object {
        /** Frames held before input is refused. Three is about what a long-GOP decoder reorders. */
        const val CAPACITY = 3
    }
}

private class ScriptedAudioDecoderFactory(
    private val script: MediaScript,
    private val ledger: LeakLedger,
    private val faults: FaultPlan,
    private val trace: ScriptTrace,
    private val onCreate: (ScriptedAudioDecoder) -> Unit = {},
) : AudioDecoderFactory {
    override val name: String = "scripted audio"
    override suspend fun create(stream: PlayerStreamInfo): AudioDecoder? {
        if (stream.kind != TrackKind.Audio) return null
        if (faults.audioDecodersRefuse) return null
        val track = script.audioTracks.firstOrNull { it.index == stream.index } ?: return null
        if (!track.decoderAccepted) return null
        if (track.decoderCreateDelayUs > 0) delay(track.decoderCreateDelayUs / 1_000)
        return ScriptedAudioDecoder(script, track, ledger, faults, trace).also(onCreate)
    }
}

internal class ScriptedAudioDecoder(
    private val script: MediaScript,
    private val track: ScriptedAudioTrack,
    private val ledger: LeakLedger,
    private val faults: FaultPlan,
    private val trace: ScriptTrace = ScriptTrace(),
) : AudioDecoder {

    private val pending = ArrayDeque<AudioBuffer>()
    private var generation: Generation = Generation.Initial
    private var drained = false
    private var ending = false

    override var outputFormat: AudioFormat = track.format(script.sampleRate, script.channels)
        private set

    override val isDrained: Boolean get() = drained

    var closed: Boolean = false
        private set
    var closeCount: Int = 0
        private set

    override suspend fun send(packet: PlayerPacket?): Boolean {
        if (pending.size >= CAPACITY) return false
        if (packet == null) {
            ending = true
            return true
        }
        if (faults.refuseSend()) return false
        if (faults.emptyDecode()) return true
        pending.addLast(
            ScriptedAudioBuffer(
                pts = packet.pts ?: Pts.Zero,
                format = outputFormat,
                frameCount = script.audioBufferFrames,
                generation = generation,
                trackMarker = track.marker,
                ledger = ledger,
            ),
        )
        return true
    }

    override suspend fun receive(): AudioBuffer? {
        // A worker that never reaches a quiescent boundary: parked on a cancellable suspension,
        // so quiesce fails while cancellation still works (interlude item I-02).
        if (faults.stallAudioDecodeReceive) awaitCancellation()
        val buffer = pending.removeFirstOrNull()
        if (buffer == null && ending) drained = true
        return buffer
    }

    override suspend fun flush(newGeneration: Generation) {
        trace.record("audio.flush")
        pending.forEach { it.close() }
        pending.clear()
        generation = newGeneration
        drained = false
        ending = false
    }

    override fun close() {
        closeCount++
        closed = true
        pending.forEach { it.close() }
        pending.clear()
    }

    private companion object {
        const val CAPACITY = 3
    }
}

/**
 * Decoded audio whose every sample says which epoch it came from.
 *
 * The magnitude is the generation plus one, so silence (zero) is distinguishable from the first epoch's
 * audio, and the SIGN alternates with the generation. The sign is the part that matters: everything
 * between here and the device may scale these samples, because volume is a multiply by a value between
 * zero and one, and a scaled magnitude cannot be told from a smaller one. A non-negative multiplier
 * cannot change a sign, so the sign survives the whole audio path and is what proves that no sample from
 * a superseded epoch was ever heard.
 */
internal class ScriptedAudioBuffer(
    override val pts: Pts,
    override val format: AudioFormat,
    override val frameCount: Int,
    override val generation: Generation,
    private val trackMarker: Float = 1f,
    private val ledger: LeakLedger? = null,
) : AudioBuffer {

    private var isClosed = false
    private val value: Float = trackSample(generation, trackMarker)

    init {
        ledger?.onOpen()
    }

    override fun copyChannel(channel: Int, into: FloatArray, offset: Int) {
        for (i in 0 until frameCount) into[offset + i] = value
    }

    override fun close() {
        ledger?.onClose(isClosed)
        isClosed = true
    }
}

/** The sample value one epoch's audio carries: magnitude names it, sign survives the gain stage. */
internal fun epochSample(generation: Generation): Float =
    (generation.value + 1).toFloat() * if (generation.value % 2L == 0L) 1f else -1f

/** Audio identity that retains the epoch sign while giving each track a distinct magnitude. */
internal fun trackSample(generation: Generation, marker: Float): Float = epochSample(generation) * marker

/** Which epochs a set of heard sample values could have come from, by sign alone. */
internal fun epochSign(generation: Generation): Int = if (generation.value % 2L == 0L) 1 else -1

/**
 * A device that pulls, driven by the test's own clock.
 *
 * A real sink is pulled by a real-time thread the platform owns. This one is pulled by [runDevice],
 * which under virtual time makes the whole device schedule deterministic: one callback every buffer
 * period, at the instant the clock says, with the deadline a real device would report. Everything it is
 * handed is inspected, so a test can name the epochs that became audible.
 */
internal class ScriptedSink(
    private val accepts: AudioFormat? = null,
    override val deviceBufferFrames: Int = 512,
    private val faults: FaultPlan = FaultPlan.None,
    private val trace: ScriptTrace = ScriptTrace(),
    /**
     * Opt-in, and false by default ON PURPOSE. A live [events] flow never completes, so the core's
     * collector stays parked instead of finishing, and switching that on for every suite at once is a
     * change to three hundred tests to serve one. Only the suite that emits events asks for it.
     */
    private val publishesEvents: Boolean = false,
) : AudioSink {

    private var render: AudioRenderCallback? = null
    private var buffer: ScriptedSinkBuffer? = null
    private var negotiated: AudioFormat? = null
    private var running = false

    var openCount: Int = 0
        private set
    val openRequests: MutableList<AudioFormat> = mutableListOf()
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    var drainCount: Int = 0
        private set
    var closed: Boolean = false
        private set
    var callbacks: Int = 0
        private set
    var framesPlayed: Long = 0
        private set
    var silenceFrames: Long = 0
        private set
    val isRunning: Boolean get() = running

    /** Every distinct sample value handed to the device, which names the epochs that were heard. */
    val audibleValues: MutableSet<Float> = mutableSetOf()

    /**
     * The signs of everything heard, which is the gain-proof half of the same question.
     *
     * Volume scales a sample and cannot flip it, so a sign that does not belong to the current epoch is
     * proof that audio from a superseded one reached the device.
     */
    val audibleSigns: MutableSet<Int> = mutableSetOf()

    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        if (faults.sinkOpenFails) error("the scripted device refuses to open")
        openCount++
        openRequests += request
        val format = accepts ?: request
        this.render = render
        this.negotiated = format
        this.buffer = ScriptedSinkBuffer(format, deviceBufferFrames)
        return format
    }

    override suspend fun start() {
        startCount++
        running = true
    }

    override suspend fun stop() {
        stopCount++
        trace.record("sink.stop")
        // A device whose stop has wedged. Rechecked rather than parked for ever, so the test that
        // arms it can also release it and leave nothing running.
        while (faults.stopHangs) delay(1_000)
        running = false
    }

    override suspend fun drain() {
        drainCount++
        if (faults.drainHangs) {
            // A device that never reports its buffer empty. The engine must bound its own wait rather
            // than poll a lost device forever.
            while (true) delay(1_000)
        }
        running = false
    }

    override suspend fun setPaused(paused: Boolean): Boolean {
        running = !paused
        return true
    }

    override fun latencyNanos(): Long = 0

    override val latencyQuality: LatencyQuality = LatencyQuality.Estimated

    private val published = MutableSharedFlow<AudioSinkEvent>(extraBufferCapacity = 16)

    override val events: Flow<AudioSinkEvent> = if (publishesEvents) published else emptyFlow()

    /** Emit a device event as the platform would, for a test that constructed this with events on. */
    suspend fun publish(event: AudioSinkEvent) {
        check(publishesEvents) { "construct ScriptedSink(publishesEvents = true) to emit events" }
        published.emit(event)
    }

    override fun close() {
        closed = true
        running = false
        render = null
    }

    /** One device callback, as if the hardware had asked for a buffer now. */
    fun pump(nowNanos: Long) {
        val callback = render ?: return
        val destination = buffer ?: return
        if (!running) return
        callbacks++
        destination.reset()
        val deadline = nowNanos + bufferNanos()
        val written = callback.onRender(destination, deviceBufferFrames, deadline)
        framesPlayed += written
        silenceFrames += deviceBufferFrames - written
        val heard = destination.distinctValues(written)
        audibleValues += heard
        heard.forEach { audibleSigns += if (it > 0f) 1 else if (it < 0f) -1 else 0 }
    }

    /**
     * Pulls at the device's own period until the caller's scope is cancelled.
     *
     * The period is read every time round, because it is only known once a format has been negotiated.
     * Reading it once, before the device was opened, made this pull ten times too often, and a device
     * that consumes ten times faster than real time makes the audio clock run at ten times speed.
     */
    suspend fun runDevice(clock: MonotonicClock) {
        while (true) {
            pump(clock.nanos())
            delay((bufferNanos() / 1_000_000).coerceAtLeast(1))
        }
    }

    private fun bufferNanos(): Long {
        val rate = negotiated?.sampleRate ?: return 0
        if (rate <= 0) return 0
        return deviceBufferFrames.toLong() * 1_000_000_000L / rate
    }
}

/** The device's own buffer, written in place exactly as a real one is. */
private class ScriptedSinkBuffer(
    override val format: AudioFormat,
    capacityFrames: Int,
) : AudioSinkBuffer {

    private val data = FloatArray(capacityFrames * format.channels)

    fun reset() {
        data.fill(0f)
    }

    fun distinctValues(frames: Int): Set<Float> {
        val seen = mutableSetOf<Float>()
        for (i in 0 until frames * format.channels) seen += data[i]
        return seen
    }

    override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
        source.copyInto(
            destination = data,
            destinationOffset = destinationFrameOffset * format.channels,
            startIndex = sourceOffset,
            endIndex = sourceOffset + frames * format.channels,
        )
    }

    override fun writePlane(
        channel: Int,
        source: FloatArray,
        sourceOffset: Int,
        destinationFrameOffset: Int,
        frames: Int,
    ) = error("the scripted device is interleaved")

    override fun writeSilence(frameOffset: Int, frames: Int) {
        val from = frameOffset * format.channels
        data.fill(0f, from, from + frames * format.channels)
    }
}

/** The output half, scripted: one sink, one clock, and no renderer unless a test provides one. */
internal class ScriptedOutput(
    override val clock: MonotonicClock,
    val sink: ScriptedSink,
    override val videoRenderer: VideoRendererFactory? = null,
) : OutputBackend {
    /** Cue identities handed to the rasterizer, in publication order. */
    val rasterizedCueTexts: MutableList<List<String>> = mutableListOf()

    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "scripted"
        override suspend fun create(): AudioSink = sink
    }

    /** One 1x1 image per cue: enough to prove the raster call and count what was drawn. */
    override val subtitleRasterizer: io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer =
        object : io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer {
            override fun rasterize(
                cues: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>,
                viewportWidth: Int,
                viewportHeight: Int,
                fontScale: Float,
                position: Float,
            ): List<io.github.yuroyami.kiteplayer.spi.OverlayImage> {
                rasterizedCueTexts += cues.map { cue ->
                    when (cue) {
                        is io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text -> cue.plainText
                        is io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Bitmap -> "<bitmap>"
                    }
                }
                return cues.map { cue ->
                    io.github.yuroyami.kiteplayer.spi.OverlayImage(
                        x = 0,
                        y = (viewportHeight * position).toInt() - 1,
                        bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(1, 1, ByteArray(4)),
                    )
                }
            }
        }
}
