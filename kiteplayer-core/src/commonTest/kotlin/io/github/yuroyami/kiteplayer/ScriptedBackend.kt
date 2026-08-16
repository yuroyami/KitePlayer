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
) {
    val videoIndex: Int = 0
    val audioIndex: Int = if (hasVideo) 1 else 0
    val hasSubtitles: Boolean get() = subtitleCues.isNotEmpty()
    val subtitleIndex: Int = (if (hasVideo) 1 else 0) + (if (hasAudio) 1 else 0)
    val audioBufferDurationUs: Long = audioBufferFrames.toLong() * 1_000_000L / sampleRate

    fun format(): AudioFormat = AudioFormat(
        sampleRate = sampleRate,
        channels = channels,
        sampleFormat = SampleFormat.F32,
    )
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

    /** True makes the sink's drain never finish, which the core must bound rather than wait out. */
    var drainHangs: Boolean = false

    /** True makes closing the backend session throw, which close must survive. */
    var sessionCloseThrows: Boolean = false

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
    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder = ScriptedSubtitleDecoder(script)
}

internal class ScriptedSubtitleDecoder(private val script: MediaScript) : SubtitleDecoder {
    private val pending = ArrayDeque<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>()
    var closed: Boolean = false
        private set

    override suspend fun send(packet: PlayerPacket?): Boolean {
        if (packet == null) return true
        val pts = packet.pts?.micros ?: return true
        script.subtitleCues.filterTo(pending) { it.startMicros == pts }
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

    override suspend fun open(media: MediaItem): BackendSession {
        openCalls++
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

    override val audioDecoders: List<AudioDecoderFactory> =
        listOf(ScriptedAudioDecoderFactory(script, ledger, faults, trace))

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
        if (script.hasSubtitles) {
            add(
                PlayerStreamInfo(
                    index = script.subtitleIndex,
                    kind = TrackKind.Subtitle,
                    codec = "scripted-subtitle",
                    language = script.subtitleLanguage,
                ),
            )
        }
        if (script.hasAudio) {
            add(
                PlayerStreamInfo(
                    index = script.audioIndex,
                    kind = TrackKind.Audio,
                    codec = "scripted-audio",
                    language = "eng",
                    isDefault = true,
                    startTime = Pts.Zero,
                    sampleRate = script.sampleRate,
                    channels = script.channels,
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
    private var audioCursorUs = 0L

    var reads: Int = 0
        private set
    var seeks: Int = 0
        private set
    val seekTargets: MutableList<Long> = mutableListOf()
    var selectCalls: Int = 0
        private set
    var closed: Boolean = false
        private set

    override fun selectStreams(indices: Set<Int>) {
        if (faults.failSelectStreams) error("scripted selectStreams failure (interlude item I-03)")
        check(selectCalls == 0) { "streams must be selected before the first read" }
        require(indices.isNotEmpty()) { "no selectable stream among $indices" }
        selected = indices
        selectCalls++
    }

    /** The next scripted cue to emit as a packet. Reset by seeks to redeliver from the landing. */
    private var subtitleCursor: Int = 0

    override suspend fun readPacket(): PlayerPacket? {
        check(selectCalls > 0) { "selectStreams must be called before readPacket" }
        reads++
        if (faults.failRead(reads)) error("the scripted source failed on read $reads")
        if (script.readDelayUs > 0) delay(script.readDelayUs / 1_000)

        // Subtitle packets interleave by start time, ahead of the picture like a real muxer.
        if (script.hasSubtitles && script.subtitleIndex in selected && subtitleCursor < script.subtitleCues.size) {
            val cue = script.subtitleCues[subtitleCursor]
            val mediaCursor = minOf(
                if (script.hasVideo) videoCursorUs else Long.MAX_VALUE,
                if (script.hasAudio) audioCursorUs else Long.MAX_VALUE,
            )
            if (cue.startMicros <= mediaCursor) {
                subtitleCursor++
                return FakePacket(
                    streamIndex = script.subtitleIndex,
                    pts = Pts(cue.startMicros),
                    duration = Pts(cue.endMicros - cue.startMicros),
                    isKeyframe = true,
                    ledger = ledger,
                )
            }
        }

        val video = script.videoIndex.takeIf {
            script.hasVideo && it in selected && videoCursorUs < script.durationUs
        }
        val audio = script.audioIndex.takeIf {
            script.hasAudio && it in selected && audioCursorUs < script.durationUs
        }
        val pickVideo = when {
            video == null -> false
            audio == null -> true
            script.badlyInterleaved -> true
            else -> videoCursorUs <= audioCursorUs
        }
        return when {
            pickVideo -> {
                val pts = videoCursorUs
                videoCursorUs += script.videoFrameDurationUs
                FakePacket(
                    streamIndex = script.videoIndex,
                    pts = Pts(pts),
                    duration = Pts(script.videoFrameDurationUs),
                    isKeyframe = pts % script.keyframeIntervalUs == 0L,
                    ledger = ledger,
                )
            }
            audio != null -> {
                val pts = audioCursorUs
                audioCursorUs += script.audioBufferDurationUs
                FakePacket(
                    streamIndex = script.audioIndex,
                    pts = Pts(pts),
                    duration = Pts(script.audioBufferDurationUs),
                    isKeyframe = true,
                    ledger = ledger,
                )
            }
            else -> null
        }
    }

    override suspend fun seekToKeyframe(target: Pts): Pts? {
        check(selectCalls > 0) { "selectStreams must be called before seeking" }
        seeks++
        seekTargets += target.micros
        trace.record("source.seek")
        val aimed = (target.micros + script.seekOvershootUs).coerceIn(0L, script.durationUs)
        val landing = aimed / script.keyframeIntervalUs * script.keyframeIntervalUs
        subtitleCursor = script.subtitleCues.indexOfFirst { it.startMicros >= landing }
            .takeIf { it >= 0 } ?: script.subtitleCues.size
        videoCursorUs = landing
        audioCursorUs = landing
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
) : AudioDecoderFactory {
    override val name: String = "scripted audio"
    override suspend fun create(stream: PlayerStreamInfo): AudioDecoder? {
        if (stream.kind != TrackKind.Audio) return null
        if (faults.audioDecodersRefuse) return null
        return ScriptedAudioDecoder(script, ledger, faults, trace)
    }
}

internal class ScriptedAudioDecoder(
    private val script: MediaScript,
    private val ledger: LeakLedger,
    private val faults: FaultPlan,
    private val trace: ScriptTrace = ScriptTrace(),
) : AudioDecoder {

    private val pending = ArrayDeque<AudioBuffer>()
    private var generation: Generation = Generation.Initial
    private var drained = false
    private var ending = false

    override var outputFormat: AudioFormat = script.format()
        private set

    override val isDrained: Boolean get() = drained

    var closed: Boolean = false
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
    private val ledger: LeakLedger? = null,
) : AudioBuffer {

    private var isClosed = false
    private val value: Float = epochSample(generation)

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
) : AudioSink {

    private var render: AudioRenderCallback? = null
    private var buffer: ScriptedSinkBuffer? = null
    private var negotiated: AudioFormat? = null
    private var running = false

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

    override val events: Flow<AudioSinkEvent> = emptyFlow()

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
            ): List<io.github.yuroyami.kiteplayer.spi.OverlayImage> = cues.map { cue ->
                io.github.yuroyami.kiteplayer.spi.OverlayImage(
                    x = 0,
                    y = viewportHeight - 1,
                    bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(1, 1, ByteArray(4)),
                )
            }
        }
}
