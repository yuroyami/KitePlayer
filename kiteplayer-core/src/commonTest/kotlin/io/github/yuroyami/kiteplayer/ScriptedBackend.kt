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
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

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
    /** True marks the track as descriptive/accessibility audio. */
    val isAccessibility: Boolean = false,
    /** Optional early packet cutoff, used to model an alternate cache that stops growing. */
    val packetEndUs: Long? = null,
    /** A span of the track with no packets at all, as a sound with a long hole has (#570). */
    val packetHoleUs: LongRange? = null,
    /** Virtual preparation cost before this track's decoder is returned. */
    val decoderCreateDelayUs: Long = 0,
    /** Bytes per scripted packet, so one lane can dominate a byte budget the way a lossless track does. */
    val packetSizeBytes: Int = 1024,
    /** False models FFmpeg's routine zero duration: the packet carries a start and no duration. */
    val packetDurationKnown: Boolean = true,
    /** The stream's own tags, such as a ReplayGain gain that overrides the container's. */
    val metadata: Map<String, String> = emptyMap(),
    /**
     * When the stream first appears, as a live transport stream's sound can a few seconds in (#509).
     * Null lists it at the open. A later one is listed by the first packet read at or past it, and
     * its packets start there.
     */
    val appearsAtUs: Long? = null,
    /** A multiplier per channel, so a test can tell the sides apart (#462). Null is 1 on every one. */
    val channelMarkers: List<Float>? = null,
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
    /** True marks the track as forced subtitles. */
    val isForced: Boolean = false,
    /** False makes the scripted decoder factory refuse this specific track. */
    val decoderAccepted: Boolean = true,
    /** True makes this track's decoder refuse every packet, as a decoder that is full does. */
    val refusesPackets: Boolean = false,
    /**
     * True makes this track's decoder hold its last cue until the null packet that ends the
     * stream, as FFmpeg's caption decoder holds the caption on screen.
     */
    val holdsLastCue: Boolean = false,
    /** True makes this track's decoder refuse that null packet every time it is offered. */
    val refusesDrain: Boolean = false,
    /** When the stream first appears, as [ScriptedAudioTrack.appearsAtUs] says for a sound (#509). */
    val appearsAtUs: Long? = null,
    /** The codec name this stream declares, in place of [MediaScript.subtitleCodec]. */
    val codec: String? = null,
) {
    val cuesByStart: Map<Long, List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>> =
        cues.groupBy { it.startMicros }

    val packets: List<ScriptedSubtitlePacket> = cuesByStart
        .map { (startMicros, atStart) ->
            ScriptedSubtitlePacket(
                startMicros = startMicros,
                endMicros = atStart.maxOf { it.endMicros },
                // The packet's own bytes, in the Matroska ASS event shape a typesetter reads, so a
                // test can assert which events reached it. Text cues only; a bitmap carries none.
                payload = atStart.filterIsInstance<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text>()
                    .joinToString("\n") { "0,0,Default,,0,0,0,,${it.plainText}" }
                    .encodeToByteArray(),
            )
        }
        .sortedBy { it.startMicros }
}

internal class MediaScript(
    val durationUs: Long = 4_000_000,
    /**
     * The length the scripted container declares, when it is not [durationUs], the length it
     * really plays. With [durationIsEstimate] it models FFmpeg's guess from the bit rate (#422).
     */
    val declaredDurationUs: Long? = null,
    val durationIsEstimate: Boolean = false,
    /** The declared length as it stands at each read, for a file still being written (#430). Overrides [declaredDurationUs]. */
    val declaredDurationNowUs: (() -> Long)? = null,
    /** True models an HLS master whose sounds are renditions downloaded on their own (#455). */
    val separateAudioRenditions: Boolean = false,
    /**
     * The closed captions each decoded picture carries, by its time, or null for none (#236). The
     * scripted caption decoder reads the bytes as text: a cue that holds until the next, and
     * [SCRIPTED_CAPTION_CLEAR] an empty one that clears the screen.
     */
    val videoCaptions: ((ptsUs: Long) -> String?)? = null,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true,
    /** The chapter table the scripted container declares. */
    val chapters: List<Chapter> = emptyList(),
    /** 40 ms, which is 25 frames a second. */
    val videoFrameDurationUs: Long = 40_000,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    /** Sample frames in one decoded audio buffer. 1024 is what AAC produces. */
    val audioBufferFrames: Int = 1024,
    /** How far apart the keyframes are. A seek can only land on one of these. */
    val keyframeIntervalUs: Long = 400_000,
    /**
     * Explicit video timestamps, in order, for a variable frame rate. Null keeps the constant
     * [videoFrameDurationUs] grid. Each packet lasts until the next timestamp, or until the end.
     */
    val videoTimestampsUs: List<Long>? = null,
    /** The keyframes among [videoTimestampsUs]. Null keeps the [keyframeIntervalUs] grid. */
    val videoKeyframesUs: Set<Long>? = null,
    /** True makes the only video stream a still image, which must never carry the timeline. */
    val videoIsCoverArt: Boolean = false,
    /** The quarter turn the scripted video stream declares, as a phone recording on its side does. */
    val videoRotationDegrees: Int = 0,
    /** The video stream's colour as the container states it, or null for none stated (#499). */
    val videoColor: io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo? = null,
    val seekable: Boolean = true,
    /** Extra container tags, for the suites that read them. Merged over the harness's own three. */
    val containerTags: Map<String, String> = emptyMap(),
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
    /** The codec name the scripted subtitle streams declare. "ass" routes them to a typesetter. */
    val subtitleCodec: String = "scripted-subtitle",
    /** The codec header the scripted subtitle streams declare, an ASS script header in practice. */
    val subtitleHeader: ByteArray? = null,
    /** Files the scripted container attaches, fonts in practice. */
    val attachments: List<io.github.yuroyami.kiteplayer.spi.MediaAttachment> = emptyList(),
    /** Counts the scripted decoder's work without putting timing assumptions into a virtual-time test. */
    val subtitleProbe: ScriptedSubtitleProbe = ScriptedSubtitleProbe(),
    /** The scripted subtitle track's decoder holds its last cue until the end-of-stream drain. */
    val subtitleHoldsLastCue: Boolean = false,
    /** The scripted subtitle track's decoder refuses the end-of-stream drain every time. */
    val subtitleRefusesDrain: Boolean = false,
    /** Extra container audio tracks. Explicit indices make identity assertions unambiguous. */
    val additionalAudioTracks: List<ScriptedAudioTrack> = emptyList(),
    /** Fields the container declares one way and the decoder answers another. */
    val streamDivergences: List<io.github.yuroyami.kiteplayer.spi.StreamDivergence> = emptyList(),
    /** Extra container subtitle tracks. Explicit indices make identity assertions unambiguous. */
    val additionalSubtitleTracks: List<ScriptedSubtitleTrack> = emptyList(),
    /** True gives the source the recording capability. It writes nothing and logs each call. */
    val recordable: Boolean = false,
    /** The first audio stream's own tags. */
    val audioMetadata: Map<String, String> = emptyMap(),
    /** The default audio track's multiplier per channel, so a test can tell the sides apart (#462). */
    val audioChannelMarkers: List<Float>? = null,
    /** Stretches, in microseconds, whose audio buffers are digital silence, as a podcast's pauses are (#429). */
    val audioSilentUs: List<LongRange> = emptyList(),
    /** The variants the source offers, as an HLS master playlist would. The item's choice picks one. */
    val variants: List<io.github.yuroyami.kiteplayer.StreamVariant> = emptyList(),
    /** The seek bar pictures the scripted stream carries, or null (#433). */
    val thumbnails: io.github.yuroyami.kiteplayer.spi.PlayerThumbnails? = null,
    /**
     * The time of day of the stream's start, in milliseconds since 1970 UTC, as an HLS stream's
     * dates state it (#444), or null for a stream that states none. Positions then run in step
     * with it, up to the duration.
     */
    val timeOfDayOriginMillis: Long? = null,
    /**
     * A sine tone of this frequency in place of the constant sound, its phase following each
     * buffer's time, so two items of different pitch can be told apart where they overlap (#434).
     * Null keeps the constant that names the epoch.
     */
    val audioToneHz: Double? = null,
    /** How long the default audio track's decoder takes to create, as a slow one does. */
    val audioDecoderCreateDelayUs: Long = 0,
    /** The default audio track's level, which scales every sample it makes. */
    val audioMarker: Float = 1f,
    /**
     * How long each of the first [slowStartReads] packet reads takes on top of [readDelayUs], as a
     * server slow to answer at first: the source opens at once and fills its queues late (#434).
     */
    val slowStartReadUs: Long = 0,
    val slowStartReads: Int = 0,
    /** A read delay for one variant, in place of [readDelayUs]: a link too slow for that variant. */
    val readDelayUsByVariant: Map<Int, Long> = emptyMap(),
    /** The variants the source moves to while it plays, with no new open, as an HLS stream with shared segments does (#464). */
    val variantsMovedInPlace: Set<Int> = emptySet(),
    /** How long the source takes to answer a variant change it is asked to make in place. */
    val variantMoveUs: Long = 0,
    /** The size of the frame at a time in microseconds, for a picture that changes size while it plays, or null for 1920x1080. */
    val videoFrameSize: ((Long) -> VideoSize)? = null,
    /**
     * A network link, in bits per second at a time of the test's clock in microseconds. Each video
     * packet then takes as long as its media of the selected variant's bitrate takes over the link,
     * so the same link is fast for a low variant and slow for a high one. Null is no link.
     */
    val linkBitsPerSecond: ((clockUs: Long) -> Long)? = null,
    /**
     * True makes the source a sender that pushes in real time, as a camera does (#395): a packet
     * arrives when the test's clock reaches its time, counted from the source's creation, and the
     * source says it is real time and has no duration.
     */
    val live: Boolean = false,
    /**
     * Stretches of the test's clock, in microseconds from the source's creation, in which nothing
     * arrives. The packets due inside one arrive together at its end, as they do from a network that
     * stalls and then delivers.
     */
    val liveHolds: List<LongRange> = emptyList(),
    /**
     * True makes the live sender one that takes a pause, as an RTSP camera does (#441): the source's
     * pauseReading answers true and stops the sender, and resumeReading starts it again at the live
     * edge, so what was sent meanwhile is never read.
     */
    val livePause: Boolean = false,
    /**
     * How long, in microseconds, the live sender keeps a session it hears nothing from, as an RTSP
     * server does. A read, a pause and a resume are each a request it hears. Past it the session is
     * gone, and every call after that fails. Null keeps a session for ever.
     */
    val liveSessionTimeoutUs: Long? = null,
    /**
     * When the live sender ends the session whatever it hears, in microseconds of its own time, as a
     * camera that restarts does. Every call after that fails. A source opened again starts its own
     * time from zero.
     */
    val liveSessionEndsAtUs: Long? = null,
    /** True makes the live sender refuse every resume, as a server that dropped the session does. */
    val liveResumeRefused: Boolean = false,
    /**
     * True makes every other picture between keyframes one no other picture is built on, as the
     * B-frames of a stream coded I, B, P, B, P are, and the scripted video decoder then skips those
     * when the engine asks it to (#468).
     */
    val alternateFramesAreNonReference: Boolean = false,
    /** What the scripted video decoder decoded and skipped, by packet time. */
    val videoProbe: ScriptedVideoProbe = ScriptedVideoProbe(),
    /** The channels the container declares, as a transport stream multiplex does (#505). */
    val programs: List<io.github.yuroyami.kiteplayer.MediaProgram> = emptyList(),
    /**
     * The channels from a time on, as a live multiplex announces them in a new programme table
     * (#509). Each applies from the first packet read at or past its time.
     */
    val programChanges: List<Pair<Long, List<io.github.yuroyami.kiteplayer.MediaProgram>>> = emptyList(),
    /**
     * The container's tags that change from a time on, as a radio station's next song does (#423).
     * Each is merged over the tags before it and announced, whole, on the first packet read at or
     * past its time.
     */
    val tagChanges: List<Pair<Long, Map<String, String>>> = emptyList(),
    /**
     * When the picture first appears, as [ScriptedAudioTrack.appearsAtUs] says for a sound (#527): a
     * slideshow a radio service adds, or a channel joined during a break with no picture. Its first
     * packet is the first frame on the grid at or past this time, which need not be a keyframe.
     */
    val videoAppearsAtUs: Long? = null,
    /** Where the picture stops being carried, as a channel that moves it to a new stream does (#527). */
    val videoEndUs: Long? = null,
    /**
     * The stream a channel's picture moves to at [videoEndUs] (#527), on the same grid and with the
     * same keyframes from there on. Null is no second picture.
     */
    val movedVideoIndex: Int? = null,
) {
    /** Whether the picture at [ptsUs] is one nothing is built on, under [alternateFramesAreNonReference]. */
    fun isNonReferenceVideo(ptsUs: Long, isKeyframe: Boolean): Boolean {
        if (!alternateFramesAreNonReference || isKeyframe) return false
        val index = videoTimestampsUs?.indexOf(ptsUs)?.toLong() ?: (ptsUs / videoFrameDurationUs)
        return index % 2 == 1L
    }

    val videoIndex: Int = 0
    val audioIndex: Int = if (hasVideo) 1 else 0
    val subtitleIndex: Int = (if (hasVideo) 1 else 0) + (if (hasAudio) 1 else 0)
    val audioBufferDurationUs: Long = audioBufferFrames.toLong() * 1_000_000L / sampleRate

    val audioTracks: List<ScriptedAudioTrack> = buildList {
        if (hasAudio) {
            add(
                ScriptedAudioTrack(
                    index = audioIndex,
                    marker = audioMarker,
                    language = "eng",
                    title = "scripted audio A",
                    sampleRate = sampleRate,
                    channels = channels,
                    isDefault = true,
                    metadata = audioMetadata,
                    channelMarkers = audioChannelMarkers,
                    decoderCreateDelayUs = audioDecoderCreateDelayUs,
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
                    holdsLastCue = subtitleHoldsLastCue,
                    refusesDrain = subtitleRefusesDrain,
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

    /** The first video timestamp: zero on the grid, the first listed one otherwise. */
    val firstVideoPtsUs: Long get() = videoTimestampsUs?.first() ?: 0L

    /** The first video timestamp at or past [atUs]. */
    fun firstVideoAtOrAfter(atUs: Long): Long =
        videoTimestampsUs?.firstOrNull { it >= atUs }
            ?: if (atUs <= firstVideoPtsUs) firstVideoPtsUs else (atUs + videoFrameDurationUs - 1) / videoFrameDurationUs * videoFrameDurationUs

    /** The video timestamp after [pts], or [durationUs] when [pts] is the last one. */
    fun videoPtsAfter(pts: Long): Long =
        videoTimestampsUs?.let { list -> list.firstOrNull { it > pts } ?: durationUs }
            ?: (pts + videoFrameDurationUs)

    fun isVideoKeyframe(pts: Long): Boolean =
        videoKeyframesUs?.contains(pts) ?: (pts % keyframeIntervalUs == 0L)

    /** Where a keyframe seek aimed at [aimedUs] lands: the last keyframe at or before it. */
    fun keyframeAtOrBefore(aimedUs: Long): Long =
        videoKeyframesUs?.let { keys -> keys.filter { it <= aimedUs }.maxOrNull() ?: keys.min() }
            ?: (aimedUs / keyframeIntervalUs * keyframeIntervalUs)

    /** The first keyframe at or after [aimedUs], or null when none follows it. */
    fun keyframeAtOrAfter(aimedUs: Long): Long? {
        val keys = videoKeyframesUs ?: return ((aimedUs + keyframeIntervalUs - 1) / keyframeIntervalUs * keyframeIntervalUs)
            .takeIf { it < durationUs }
        return keys.filter { it >= aimedUs }.minOrNull()
    }

    init {
        videoTimestampsUs?.let { list ->
            require(list.isNotEmpty() && list.zipWithNext().all { (a, b) -> a < b } && list.last() < durationUs) {
                "scripted video timestamps must be strictly increasing and end before the duration, were $list"
            }
            val keys = requireNotNull(videoKeyframesUs) { "explicit video timestamps need explicit keyframes" }
            require(list.first() in keys && list.containsAll(keys)) {
                "the keyframes $keys must be among the timestamps $list and include the first"
            }
        }
        require(movedVideoIndex == null || (hasVideo && videoEndUs != null)) {
            "a picture moves to a new stream only from one that ends"
        }
        val streamIndices = buildList {
            if (hasVideo) add(videoIndex)
            movedVideoIndex?.let(::add)
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
internal class ScriptedSubtitlePacket(
    val startMicros: Long,
    val endMicros: Long,
    val payload: ByteArray = ByteArray(0),
)

/**
 * Operation-counting probe for subtitle tests.
 *
 * Virtual time cannot reveal CPU monopolisation. These counters instead expose the algorithmic
 * work: every packet must perform exactly one keyed lookup, however many cues the whole file has.
 * [onPacketSent] also lets a test enqueue a real player command from inside the actor's subtitle
 * drain, reproducing a command that arrives concurrently on a device without thread races.
 */
/** The packet times the scripted video decoder decoded and skipped, in microseconds, in order. */
internal class ScriptedVideoProbe {
    val decodedUs: MutableList<Long> = mutableListOf()
    val skippedUs: MutableList<Long> = mutableListOf()

    fun clear() {
        decodedUs.clear()
        skippedUs.clear()
    }
}

internal class ScriptedSubtitleProbe {
    /** How many end-of-stream drains the scripted subtitle decoders took. */
    var drains: Int = 0
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

    /** True makes every read fail, as a source does while the network is gone (#461). */
    var readsFail: Boolean = false

    /** True makes every video decoder factory refuse, so the video stream has to be deselected. */
    var videoDecodersRefuse: Boolean = false

    /** True makes every audio decoder factory refuse. */
    var audioDecodersRefuse: Boolean = false

    /** Set makes every subtitle decoder factory throw it, as a build without that decoder does. */
    var subtitleDecodersThrow: Throwable? = null

    /** What every scripted video decoder declares as its frames. Null declares nothing. */
    var videoDecoderOutput: io.github.yuroyami.kiteplayer.spi.FrameShape? = null

    /** True makes [ScriptedSource.selectStreams] throw, which is buildSession's reachable thrower
     * AFTER the audio path has gone live. */
    var failSelectStreams: Boolean = false

    /** True parks every audio decoder's receive until cancellation, a worker that refuses to reach
     * a quiescent boundary. */
    var stallAudioDecodeReceive: Boolean = false

    /** True makes the video decoder accept every packet and never produce a frame. */
    var videoDecodeProducesNothing: Boolean = false

    /**
     * How long every video `send` takes: a decoder that cannot keep up with real time.
     *
     * Zero is the ordinary instant decoder. Anything above one frame's duration makes the video
     * lane fall behind the audio clock, which is the only way to exercise a policy that fires on
     * lateness rather than on a fault.
     */
    var videoDecodeSendDelay: Duration = Duration.ZERO

    /**
     * How long every video `receive` that yields a frame takes: the decode itself, since a real
     * decoder does its work on the receive side. Zero is the instant decoder.
     */
    var videoDecodeReceiveDelay: Duration = Duration.ZERO

    /**
     * Receive throws after this many delivered frames, but only while the decoder reports a
     * hardware status: the shape of a hardware session dying mid-play (VideoToolbox invalidated by
     * backgrounding, a MediaCodec error). The software decoder a recovery builds stays healthy.
     */
    var videoDecodeFailsAfterFrames: Int? = null

    /** True makes opening the sink fail. */
    var sinkOpenFails: Boolean = false

    /** True wedges the next container seek like an uncancellable native scan. */
    var seekWedges: Boolean = false

    /** Reads beyond this count wedge like an uncancellable native read. Null wedges nothing. */
    var readWedgesAfter: Int? = null

    /**
     * How many reader reads one packet costs, when the item carries a reader. More than one models a
     * packet that arrives in many small pieces over a slow link.
     */
    var ioReadsPerPacket: Int = 1

    /** False models a source whose interrupt() cannot help. */
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
     * release finish so nothing is left parked on the scheduler.
     */
    var stopHangs: Boolean = false

    fun refuseSend(): Boolean = roll(refuseSendPercent)
    fun emptyDecode(): Boolean = roll(emptyDecodePercent)
    fun refusePresent(): Boolean = roll(refusePresentPercent)
    fun failRead(reads: Int): Boolean = readsFail || failReadAfter == reads || roll(readFailsPercent)

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
    private val faults: FaultPlan? = null,
) : SubtitleDecoderFactory {
    override val name: String = "scripted-subtitle"
    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? {
        faults?.subtitleDecodersThrow?.let { throw it }
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
    private val held = ArrayDeque<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>()
    private val lastStart = track.cues.maxOfOrNull { it.startMicros }
    private var drained = false
    var closed: Boolean = false
        private set

    override suspend fun send(packet: PlayerPacket?): Boolean {
        if (packet == null) {
            if (track.refusesDrain) return false
            probe.drains++
            pending.addAll(held)
            held.clear()
            drained = true
            return true
        }
        // The contract of SubtitleDecoder: the next packet after a drain comes only after a flush.
        check(!drained) { "a subtitle packet was sent after the drain signal with no flush" }
        if (track.refusesPackets) return false
        val pts = packet.pts?.micros ?: return true
        val cues = track.cuesByStart[pts].orEmpty()
        if (track.holdsLastCue && pts == lastStart) held.addAll(cues) else pending.addAll(cues)
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
        held.clear()
        drained = false
    }

    override fun close() {
        closed = true
    }
}

/** What a scripted picture's captions say to clear the screen (#236). */
internal const val SCRIPTED_CAPTION_CLEAR: String = "<clear>"

/**
 * The decoder of the captions inside a scripted picture (#236), answering in real time as the
 * FFmpeg backend's does: each packet's text is the screen from the packet's time, until the next.
 */
internal object ScriptedCaptionDecoderFactory : SubtitleDecoderFactory {
    override val name: String = "scripted-captions"

    /** Flushes of every caption decoder made here. A test reads the difference. */
    var flushes: Int = 0
        private set

    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? {
        if (stream.codec != io.github.yuroyami.kiteplayer.spi.CLOSED_CAPTIONS_CODEC) return null
        return object : SubtitleDecoder {
            private val pending = ArrayDeque<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>()
            private var drained = false

            override suspend fun send(packet: PlayerPacket?): Boolean {
                if (packet == null) {
                    drained = true
                    return true
                }
                // The contract of SubtitleDecoder: the next packet after a drain comes only after a flush.
                check(!drained) { "a caption packet was sent after the drain signal with no flush" }
                val pts = packet.pts?.micros ?: return true
                val text = packet.copyBytes().decodeToString()
                val spans = if (text == SCRIPTED_CAPTION_CLEAR) emptyList() else listOf(io.github.yuroyami.kiteplayer.subtitle.StyledSpan(text))
                pending.addLast(io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text(pts, io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.OPEN_END, spans))
                return true
            }

            override suspend fun receive(): List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue> {
                val out = pending.toList()
                pending.clear()
                return out
            }

            override suspend fun flush(newGeneration: Generation) {
                pending.clear()
                drained = false
                flushes++
            }

            override fun close() = Unit
        }
    }
}

/** The scripted backend. One session per [open]. */
internal class ScriptedBackend(
    private val script: MediaScript = MediaScript(),
    private val ledger: LeakLedger = LeakLedger(),
    private val faults: FaultPlan = FaultPlan.None,
    private val trace: ScriptTrace = ScriptTrace(),
    /** The harness clock, so a source can say when a wedge began. Null leaves that unrecorded. */
    private val clock: MonotonicClock? = null,
) : MediaBackend {

    /** Mutable decoder truth used to prove that stats do not retain an open-time hardware claim. */
    val videoDecoderStatus: ScriptedVideoDecoderStatus = ScriptedVideoDecoderStatus()

    var openCalls: Int = 0
        private set

    /** Sessions this backend handed out, oldest first. */
    val sessions: MutableList<ScriptedSession> = mutableListOf()

    /** Completed by a test to let a suspended [open] finish. Null means open does not wait. */
    var openGate: CompletableDeferred<Unit>? = null

    /** How long each [open] takes on the test's clock, as a source slow to open does. */
    var openDelay: Duration = Duration.ZERO

    /** Thrown by [open] instead of returning a session. */
    var openFailure: Throwable? = null

    /** The script of one item, for a queue whose items differ. Null gives every item the one script. */
    var scriptFor: ((MediaItem) -> MediaScript?)? = null

    /** Thrown by [open] for one item instead of returning a session. */
    var openFailureFor: ((MediaItem) -> Throwable?)? = null

    /**
     * How many reads [open] makes from the item's reader before it returns, the way a demuxer reads
     * while it discovers the streams. A reader that hangs then holds the open.
     */
    var readsDuringOpen: Int = 0

    private val openScratch = ByteArray(4096)

    /**
     * What the parser answers when the engine asks it to read East Asian bytes. Null is a parser
     * with no tables. The real tables live in kiteplayer-subtitles, above this module's arrow.
     */
    var textDecoder: ((ByteArray, String) -> String?)? = null

    /** The parser's reader of the other formats (#492), as the FFmpeg backend's asks FFmpeg. */
    var otherSubtitleReader: (suspend (bytes: ByteArray, text: String, uri: String) -> io.github.yuroyami.kiteplayer.spi.SubtitleFileReading?)? = null

    /**
     * A ten-line SRT-only parser for the external-subtitle tests. The real WebVTT and
     * SubRip parsers live in kiteplayer-subtitles, above this module's dependency arrow; the
     * engine's contract only needs A parser here, and the format goldens live with the real ones.
     */
    override fun subtitleFileParser(): io.github.yuroyami.kiteplayer.spi.SubtitleFileParser {
        val parse = io.github.yuroyami.kiteplayer.spi.SubtitleFileParser { text, _ ->
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
            // A one-stamp LRC branch for the engine's labelling of lyrics (#443): the real reader
            // lives in kiteplayer-subtitles too.
            val lrcLine = Regex("""^\[(\d{2}):(\d{2})\.(\d{2})\](.*)$""")
            if (text.trimStart().startsWith("[")) {
                val stamped = text.lines().mapNotNull { lrcLine.matchEntire(it.trim())?.groupValues }
                if (stamped.isNotEmpty()) {
                    return@SubtitleFileParser stamped.map { (_, min, s, cs, words) ->
                        val start = ((min.toLong() * 60 + s.toLong()) * 100 + cs.toLong()) * 10_000
                        io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text(
                            startMicros = start,
                            endMicros = start + 1_000_000,
                            spans = listOf(io.github.yuroyami.kiteplayer.subtitle.StyledSpan(words)),
                        )
                    }
                }
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
        return object : io.github.yuroyami.kiteplayer.spi.SubtitleFileParser by parse {
            override fun decode(bytes: ByteArray, encoding: String): String? =
                textDecoder?.invoke(bytes, encoding)

            override suspend fun parseOther(bytes: ByteArray, text: String, uri: String) =
                otherSubtitleReader?.invoke(bytes, text, uri)
        }
    }

    /** The exact item the engine handed over on the LAST open, resolver and cache applied. */
    var lastOpenedItem: MediaItem? = null

    override suspend fun open(media: MediaItem): BackendSession {
        openCalls++
        lastOpenedItem = media
        openGate?.await()
        if (openDelay > Duration.ZERO) delay(openDelay)
        openFailure?.let { throw it }
        openFailureFor?.invoke(media)?.let { throw it }
        // A real demuxer reads bytes; this one is scripted and normally does not. When the item
        // carries a reader it drains a little from it per packet, so the engine's byte path is
        // exercised rather than assumed: without this, anything measuring what a source delivered
        // measures a reader nobody ever called.
        val io = media.io?.open()
        repeat(readsDuringOpen) { io?.read(openScratch, 0, openScratch.size) }
        val itemScript = scriptFor?.invoke(media) ?: script
        // The variant the item asks for, or else the highest bitrate within the item's caps and its
        // fit, in the dynamic range the fit prefers, as the FFmpeg backend chooses (#447).
        val variant = if (itemScript.variants.isEmpty()) {
            null
        } else {
            val showsHdr = media.demux.fit?.showsHdr == true
            val pool = itemScript.variants.filter { it.hdr == showsHdr }.ifEmpty { itemScript.variants }
            val pixelCap = media.demux.fit?.pixelCap(pool.mapNotNull { variant ->
                variant.width?.let { width -> variant.height?.let { VideoSize(width, it) } }
            })
            media.demux.variant?.takeIf { it in itemScript.variants.indices }
                ?: pool.filter { candidate ->
                    (media.demux.maxBitrate?.let { candidate.bitrate <= it } ?: true) &&
                        (media.demux.maxVideoHeight?.let { cap -> candidate.height?.let { it <= cap } ?: true } ?: true) &&
                        (pixelCap == null || candidate.width == null || candidate.height == null ||
                            candidate.width.toLong() * candidate.height <= pixelCap)
                }.maxByOrNull { it.bitrate }?.index
                ?: 0
        }
        return ScriptedSession(itemScript, ledger, faults, trace, videoDecoderStatus, io, clock, variant)
            .also { sessions += it }
    }
}

internal class ScriptedSession(
    private val script: MediaScript,
    private val ledger: LeakLedger,
    private val faults: FaultPlan,
    private val trace: ScriptTrace = ScriptTrace(),
    videoDecoderStatus: ScriptedVideoDecoderStatus = ScriptedVideoDecoderStatus(),
    /** The engine's byte reader, when the item carried one. Drained a little per packet. */
    private val io: io.github.yuroyami.kiteplayer.MediaIo? = null,
    clock: MonotonicClock? = null,
    selectedVariant: Int? = null,
) : BackendSession {

    val scriptedSource: ScriptedSource = ScriptedSource(script, ledger, faults, trace, io, clock, selectedVariant)

    /** The same source with the recording capability, when the script asks for it. */
    val recordingSource: ScriptedRecordingSource? =
        if (script.recordable) ScriptedRecordingSource(scriptedSource) else null

    override val source: PlayerMediaSource get() = recordingSource ?: scriptedSource

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
        listOfNotNull(
            ScriptedSubtitleDecoderFactory(script, faults).takeIf { script.hasSubtitles },
            ScriptedCaptionDecoderFactory.takeIf { script.videoCaptions != null },
        )

    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
        scriptedSource.close()
        if (faults.sessionCloseThrows) error("the scripted session refuses to close")
    }
}

/** A scripted source that can record. It writes no file and logs each call instead. */
internal class ScriptedRecordingSource(private val inner: ScriptedSource) :
    PlayerMediaSource by inner, io.github.yuroyami.kiteplayer.spi.RecordingCapable {

    /** Each call in order, as "start <path>" or "stop". */
    val calls: MutableList<String> = mutableListOf()

    override var recordingPath: String? = null
        private set

    override fun startRecording(path: String) {
        check(recordingPath == null) { "a recording to $recordingPath already runs" }
        calls += "start $path"
        recordingPath = path
    }

    /** Runs inside every stop that ends a recording, before it does: a file whose trailer is slow to write. */
    var finishing: (() -> Unit)? = null

    override fun stopRecording() {
        if (recordingPath == null) return
        finishing?.invoke()
        calls += "stop"
        recordingPath = null
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
    /** The engine's byte reader when the item carried one; a real demuxer reads, so this one does too. */
    private val io: io.github.yuroyami.kiteplayer.MediaIo? = null,
    /** Reads when a wedge began into [wedgedAtNanos]. */
    private val clock: MonotonicClock? = null,
    openedVariant: Int? = null,
) : PlayerMediaSource {

    override var selectedVariant: Int? = openedVariant
        private set

    /** Every variant the engine asked this source to move to in place, in order. */
    val variantMoves = mutableListOf<Int?>()

    override suspend fun switchVariant(index: Int?): Boolean {
        variantMoves += index
        if (script.variantMoveUs > 0) kotlinx.coroutines.delay(script.variantMoveUs / 1_000)
        val wanted = index ?: 0
        if (wanted !in script.variantsMovedInPlace) return false
        selectedVariant = wanted
        return true
    }

    override val variants: List<io.github.yuroyami.kiteplayer.StreamVariant> get() = script.variants

    override val thumbnails: io.github.yuroyami.kiteplayer.spi.PlayerThumbnails? get() = script.thumbnails

    override fun timeOfDayAt(position: Pts): Long? =
        script.timeOfDayOriginMillis?.takeIf { position.micros in 0..script.durationUs }?.let { it + position.micros / 1_000 }

    override fun positionAtTimeOfDay(epochMillis: Long): Pts? {
        val origin = script.timeOfDayOriginMillis ?: return null
        return Pts((epochMillis - origin) * 1_000).takeIf { it.micros in 0..script.durationUs }
    }

    override val timeOfDaySpan: LongRange?
        get() = script.timeOfDayOriginMillis?.let { it..(it + script.durationUs / 1_000) }

    override var programs: List<io.github.yuroyami.kiteplayer.MediaProgram> = script.programs
        private set

    /** The streams that appear after the open which the reads have reached, and so listed (#509). */
    private val lateListed = HashSet<Int>()

    /** How many of [MediaScript.programChanges] the reads have reached. */
    private var programChangesApplied = 0
    private var tagChangesApplied = 0
    private var currentTags: Map<String, String>? = null

    private val lateAt: Map<Int, Long> = buildMap {
        script.videoAppearsAtUs?.let { put(script.videoIndex, it) }
        script.movedVideoIndex?.let { put(it, requireNotNull(script.videoEndUs)) }
        script.audioTracks.forEach { track -> track.appearsAtUs?.let { put(track.index, it) } }
        script.subtitleTracks.forEach { track -> track.appearsAtUs?.let { put(track.index, it) } }
    }

    /** When the live sender began, on the test's clock. */
    val liveOriginNanos: Long = clock?.nanos() ?: 0L

    /** Waits until the live sender's packet at [ptsUs] has arrived. */
    private suspend fun waitForArrival(ptsUs: Long) {
        if (!script.live) return
        val clock = clock ?: return
        val dueUs = script.liveHolds.firstOrNull { ptsUs in it }?.let { it.last + 1 } ?: ptsUs
        val nowUs = (clock.nanos() - liveOriginNanos) / 1_000
        if (dueUs > nowUs) delay((dueUs - nowUs).microseconds)
    }

    /** The live sender's own time, in microseconds since the source was created. */
    private fun senderUs(): Long = clock?.let { (it.nanos() - liveOriginNanos) / 1_000 } ?: 0L

    /** When the live sender last heard a request, by [senderUs]. */
    private var heardAtUs = 0L

    /** True once the live sender ended the session. */
    private var sessionEnded = false

    /** True while the live sender holds a pause. */
    private var senderPaused = false

    /** Calls of [pauseReading] and [resumeReading], whether or not they did anything. */
    var pauseReadingCalls: Int = 0
        private set
    var resumeReadingCalls: Int = 0
        private set

    /** The live sender hears a request for [what], or fails it when the session is gone. */
    private fun hear(what: String) {
        if (!script.live) return
        val nowUs = senderUs()
        val timeoutUs = script.liveSessionTimeoutUs
        if (timeoutUs != null && nowUs - heardAtUs > timeoutUs) sessionEnded = true
        if (script.liveSessionEndsAtUs?.let { nowUs >= it } == true) sessionEnded = true
        if (sessionEnded) error("the scripted sender ended the session before the $what")
        heardAtUs = nowUs
    }

    override fun pauseReading(): Boolean {
        pauseReadingCalls++
        if (!script.live || !script.livePause) return false
        hear("pause")
        senderPaused = true
        return true
    }

    override fun resumeReading(): Boolean {
        resumeReadingCalls++
        if (!senderPaused) return false
        if (script.liveResumeRefused) error("the scripted sender refused to play on")
        hear("resume")
        senderPaused = false
        // The sender sent nothing while paused, so the reads start again at its live edge.
        val nowUs = senderUs()
        videoCursorUs = maxOf(videoCursorUs, script.firstVideoAtOrAfter(nowUs))
        script.audioTracks.forEach { track ->
            val durationUs = audioDurationUs(track)
            val cursorUs = audioCursorsUs.getValue(track.index)
            if (cursorUs < nowUs) audioCursorsUs[track.index] = cursorUs + (nowUs - cursorUs + durationUs - 1) / durationUs * durationUs
        }
        return true
    }

    /** How long [mediaUs] of the selected variant takes over the script's link. */
    private suspend fun waitForLink(mediaUs: Long) {
        val link = script.linkBitsPerSecond ?: return
        val bitrate = script.variants.firstOrNull { it.index == selectedVariant }?.bitrate ?: return
        val bitsPerSecond = link((clock?.nanos() ?: 0L) / 1_000)
        if (bitsPerSecond <= 0) return
        val waitUs = mediaUs * bitrate / bitsPerSecond
        if (waitUs > 0) delay(waitUs.microseconds)
    }

    /** What the container lists now: every stream, less those that appear later and have not yet. */
    override val streams: List<PlayerStreamInfo>
        get() = allStreams.filter { stream -> stream.index !in lateAt || stream.index in lateListed }

    private val allStreams: List<PlayerStreamInfo> = buildList {
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
                    rotationDegrees = script.videoRotationDegrees,
                    colorSpace = script.videoColor,
                    // A key with no TrackInfo field of its own, so a test can prove the raw tags
                    // travel and not just the two the type happens to parse.
                    metadata = mapOf("handler_name" to "scripted video handler"),
                ),
            )
        }
        script.movedVideoIndex?.let { index ->
            add(
                PlayerStreamInfo(
                    index = index,
                    kind = TrackKind.Video,
                    codec = "scripted-video",
                    startTime = Pts.Zero,
                    videoSize = VideoSize(1920, 1080),
                    frameRate = 1_000_000.0 / script.videoFrameDurationUs,
                ),
            )
        }
        script.subtitleTracks.forEach { track ->
            add(
                PlayerStreamInfo(
                    index = track.index,
                    kind = TrackKind.Subtitle,
                    codec = track.codec ?: script.subtitleCodec,
                    codecExtradata = script.subtitleHeader,
                    language = track.language,
                    title = track.title,
                    isDefault = track.isDefault,
                    isForced = track.isForced,
                    metadata = buildMap {
                        track.language?.let { put("language", it) }
                        track.title?.let { put("title", it) }
                    },
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
                    metadata = track.metadata,
                ),
            )
        }
    }

    override val duration: Pts? get() = if (script.live) null else Pts(script.declaredDurationNowUs?.invoke() ?: script.declaredDurationUs ?: script.durationUs)
    override val durationIsEstimate: Boolean get() = script.durationIsEstimate
    override val separateAudioRenditions: Boolean get() = script.separateAudioRenditions
    override val seekable: Boolean = script.seekable
    override val realTime: Boolean = script.live
    override val metadata: Map<String, String> =
        mapOf("title" to "scripted", "artist" to "the harness", "encoder" to "none") + script.containerTags
    override val chapters: List<Chapter> = script.chapters

    /** What the script says the container and the decoder disagree about. Empty unless asked. */
    override val streamDivergences: List<io.github.yuroyami.kiteplayer.spi.StreamDivergence> =
        script.streamDivergences

    override val attachments: List<io.github.yuroyami.kiteplayer.spi.MediaAttachment> = script.attachments
    override val timestampsMayJump: Boolean = false

    private var selected: Set<Int> = emptySet()
    private var videoCursorUs = script.firstVideoAtOrAfter(script.videoAppearsAtUs ?: script.firstVideoPtsUs)
    private var movedVideoCursorUs = script.videoEndUs?.let(script::firstVideoAtOrAfter) ?: Long.MAX_VALUE
    private val audioCursorsUs: MutableMap<Int, Long> =
        script.audioTracks.associate { it.index to (it.appearsAtUs ?: 0L) }.toMutableMap()
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

    /** The keyframe choice each seek asked for, beside [seekTargets]. */
    val seekChoices: MutableList<KeyframeChoice> = mutableListOf()
    var selectCalls: Int = 0
        private set
    var interruptCalls: Int = 0
        private set

    /** When the first wedge began, by the harness clock. Null until a call wedges. */
    var wedgedAtNanos: Long? = null
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
        if (wedgedAtNanos == null) wedgedAtNanos = clock?.nanos()
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

    /** Selects before the first read, and again between reads to add a stream that appeared (#509). */
    override fun selectStreams(indices: Set<Int>) {
        if (faults.failSelectStreams) error("scripted selectStreams failure")
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
            // landing. That missing cue is a known open defect, and this model must not paper over it.
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

    private fun audioCandidate(): ScriptedAudioTrack? {
        // A cursor inside a track's hole moves to the first packet after it.
        script.audioTracks.forEach { track ->
            val hole = track.packetHoleUs ?: return@forEach
            val at = audioCursorsUs.getValue(track.index)
            if (at in hole) {
                val durationUs = audioDurationUs(track)
                audioCursorsUs[track.index] = at + (hole.last + 1 - at + durationUs - 1) / durationUs * durationUs
            }
        }
        return script.audioTracks
            .asSequence()
            .filter { track ->
                val trackEndUs = minOf(script.durationUs, track.packetEndUs ?: Long.MAX_VALUE)
                track.index in selected && audioCursorsUs.getValue(track.index) < trackEndUs
            }
            .minWithOrNull(compareBy({ audioCursorsUs.getValue(it.index) }, { it.index }))
    }

    private fun packetRead(packet: FakePacket): FakePacket {
        demuxFrontierUs = maxOf(demuxFrontierUs, packet.pts?.micros ?: demuxFrontierUs)
        // A late stream and a new programme table are announced on the first packet that reaches them.
        val at = packet.pts?.micros ?: return packet
        val appeared = lateAt.filter { (index, from) -> index !in lateListed && from <= at }.keys
        lateListed += appeared
        var programsChanged = false
        while (programChangesApplied < script.programChanges.size && script.programChanges[programChangesApplied].first <= at) {
            programs = script.programChanges[programChangesApplied].second
            programChangesApplied++
            programsChanged = true
        }
        packet.newStreams = if (appeared.isNotEmpty()) streams else null
        packet.newPrograms = if (programsChanged) programs else null
        var tagsChanged = false
        while (tagChangesApplied < script.tagChanges.size && script.tagChanges[tagChangesApplied].first <= at) {
            currentTags = (currentTags ?: metadata) + script.tagChanges[tagChangesApplied].second
            tagChangesApplied++
            tagsChanged = true
        }
        packet.newContainerTags = if (tagsChanged) currentTags else null
        return packet
    }

    /** Scratch for the byte drain; one buffer reused, because a demuxer does not allocate per read. */
    private val ioScratch = ByteArray(4096)

    override suspend fun readPacket(): PlayerPacket? {
        check(selectCalls > 0) { "selectStreams must be called before readPacket" }
        reads++
        check(!senderPaused) { "read while the live sender was paused" }
        hear("read")
        // What a real demuxer does and this one otherwise would not: pull bytes. Without it the
        // engine's byte cache is handed a reader nobody ever calls, and anything measuring what a
        // source delivered measures nothing at all.
        io?.let { reader -> repeat(faults.ioReadsPerPacket) { runCatching { reader.read(ioScratch, 0, ioScratch.size) } } }
        faults.readWedgesAfter?.let { limit ->
            if (reads > limit && !wedgeReleased.isCompleted) wedge("read")
        }
        if (faults.failRead(reads)) error("the scripted source failed on read $reads")
        val readDelayUs = selectedVariant?.let { script.readDelayUsByVariant[it] } ?: script.readDelayUs
        if (readDelayUs > 0) delay(readDelayUs / 1_000)
        if (reads <= script.slowStartReads && script.slowStartReadUs > 0) delay(script.slowStartReadUs / 1_000)

        val primaryVideo = script.hasVideo && script.videoIndex in selected &&
            videoCursorUs < minOf(script.durationUs, script.videoEndUs ?: Long.MAX_VALUE)
        val movedVideo = script.movedVideoIndex?.takeIf { it in selected && movedVideoCursorUs < script.durationUs }
        // The picture that is due first, when two are read.
        val video = when {
            movedVideo != null && (!primaryVideo || movedVideoCursorUs < videoCursorUs) -> movedVideo
            primaryVideo -> script.videoIndex
            else -> null
        }
        val videoAtUs = if (video == script.videoIndex) videoCursorUs else movedVideoCursorUs
        val audio = audioCandidate()
        val mediaCursor = minOf(
            if (video != null) videoAtUs else Long.MAX_VALUE,
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
                    packetBytes = candidate.packet.payload,
                ),
            )
        }

        val pickVideo = when {
            video == null -> false
            audio == null -> true
            script.badlyInterleaved -> true
            else -> videoAtUs <= audioCursorsUs.getValue(audio.index)
        }
        return when {
            pickVideo -> {
                val pts = videoAtUs
                val next = script.videoPtsAfter(pts)
                if (video == script.videoIndex) videoCursorUs = next else movedVideoCursorUs = next
                waitForLink(next - pts)
                waitForArrival(pts)
                packetRead(
                    FakePacket(
                        streamIndex = checkNotNull(video),
                        pts = Pts(pts),
                        duration = Pts(next - pts),
                        isKeyframe = script.isVideoKeyframe(pts),
                        ledger = ledger,
                    ),
                )
            }
            audio != null -> {
                val pts = audioCursorsUs.getValue(audio.index)
                val durationUs = audioDurationUs(audio)
                audioCursorsUs[audio.index] = pts + durationUs
                waitForArrival(pts)
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

    override suspend fun seekToKeyframe(target: Pts): Pts? = seekToKeyframe(target, KeyframeChoice.Before)

    override suspend fun seekToKeyframe(target: Pts, choice: KeyframeChoice): Pts? {
        check(selectCalls > 0) { "selectStreams must be called before seeking" }
        check(choice != KeyframeChoice.InSeekDirection) { "the engine resolves the seek's direction itself" }
        seeks++
        if (faults.seekWedges && !wedgeReleased.isCompleted) wedge("seek")
        seekTargets += target.micros
        seekChoices += choice
        trace.record("source.seek")
        val aimed = (target.micros + script.seekOvershootUs).coerceIn(0L, script.durationUs)
        val before = script.keyframeAtOrBefore(aimed)
        val after = script.keyframeAtOrAfter(aimed)
        val landing = when {
            after == null || choice == KeyframeChoice.Before -> before
            choice == KeyframeChoice.After -> after
            else -> if (after - aimed < aimed - before) after else before
        }
        for (track in script.subtitleTracks) {
            subtitleSeekFloorsUs[track.index] = landing
            // Redelivery starts at the landing in FILE order, exactly like a backward
            // avformat_seek_file: a packet whose start sits before the landing is never re-read,
            // however long its cue lasts.
            subtitleCursors[track.index] = track.packets.indexOfFirst {
                it.startMicros >= landing
            }.takeIf { it >= 0 } ?: track.packets.size
        }
        videoCursorUs = maxOf(landing, script.videoAppearsAtUs?.let(script::firstVideoAtOrAfter) ?: Long.MIN_VALUE)
        movedVideoCursorUs = script.videoEndUs?.let { maxOf(landing, script.firstVideoAtOrAfter(it)) } ?: Long.MAX_VALUE
        script.audioTracks.forEach { audioCursorsUs[it.index] = maxOf(landing, it.appearsAtUs ?: 0L) }
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

    override val output: io.github.yuroyami.kiteplayer.spi.FrameShape? get() = faults.videoDecoderOutput

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
        // libavcodec refuses a packet after the drain signal until a flush.
        check(!ending) { "a packet was sent after the drain signal with no flush" }
        if (faults.refuseSend()) return false
        if (faults.videoDecodeSendDelay > Duration.ZERO) delay(faults.videoDecodeSendDelay)
        if (faults.emptyDecode() || faults.videoDecodeProducesNothing) return true
        val pts = packet.pts ?: Pts.Zero
        if (skippingNonReference && script.isNonReferenceVideo(pts.micros, packet.isKeyframe)) {
            script.videoProbe.skippedUs += pts.micros
            return true
        }
        script.videoProbe.decodedUs += pts.micros
        pending.addLast(
            FakeVideoFrame(
                pts = pts,
                generation = generation,
                duration = packet.duration ?: Pts(script.videoFrameDurationUs),
                size = script.videoFrameSize?.invoke(pts.micros) ?: VideoSize(1920, 1080),
                ledger = ledger,
                closedCaptions = script.videoCaptions?.invoke(pts.micros)?.encodeToByteArray(),
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
        if (frame != null) {
            delivered++
            if (faults.videoDecodeReceiveDelay > Duration.ZERO) delay(faults.videoDecodeReceiveDelay)
        }
        return frame
    }

    /** What the engine last asked of [skipNonReferenceFrames]. A flush keeps it, as FFmpeg's does. */
    var skippingNonReference: Boolean = false
        private set

    override fun skipNonReferenceFrames(skip: Boolean) {
        skippingNonReference = skip
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
        // libavcodec refuses a packet after the drain signal until a flush.
        check(!ending) { "a packet was sent after the drain signal with no flush" }
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
                channelMarkers = track.channelMarkers,
                silent = (packet.pts?.micros ?: 0L).let { at -> script.audioSilentUs.any { at in it } },
                toneHz = script.audioToneHz,
            ),
        )
        return true
    }

    override suspend fun receive(): AudioBuffer? {
        // A worker that never reaches a quiescent boundary: parked on a cancellable suspension,
        // so quiesce fails while cancellation still works.
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
    private val channelMarkers: List<Float>? = null,
    private val silent: Boolean = false,
    private val toneHz: Double? = null,
) : AudioBuffer {

    private var isClosed = false
    private val value: Float = if (silent) 0f else trackSample(generation, trackMarker)

    init {
        ledger?.onOpen()
    }

    override fun copyChannel(channel: Int, into: FloatArray, offset: Int) {
        val sample = value * (channelMarkers?.getOrNull(channel) ?: 1f)
        val tone = toneHz
        if (tone == null || format.sampleRate <= 0) {
            for (i in 0 until frameCount) into[offset + i] = sample
            return
        }
        val start = pts.micros / 1_000_000.0
        for (i in 0 until frameCount) {
            val at = start + i.toDouble() / format.sampleRate
            into[offset + i] = sample * kotlin.math.sin(2 * kotlin.math.PI * tone * at).toFloat()
        }
    }

    override fun close() {
        ledger?.onClose(isClosed)
        isClosed = true
    }
}

/**
 * The sample value one epoch's audio carries: magnitude names it, sign survives the gain stage.
 *
 * The magnitude is 1 / (epoch + 1), so the first epoch is a constant at full scale and every later
 * one is a distinct value inside it. Anything past full scale would be turned down by the ring's peak
 * limiter (#504) and no longer name its epoch.
 */
internal fun epochSample(generation: Generation): Float =
    (1f / (generation.value + 1).toFloat()) * if (generation.value % 2L == 0L) 1f else -1f

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
    /** What the device claims it is holding, so a test can prove the figure travels. */
    private val latencyNanosAnswer: Long = 0L,
    private val faults: FaultPlan = FaultPlan.None,
    private val trace: ScriptTrace = ScriptTrace(),
    /**
     * Opt-in, and false by default ON PURPOSE. A live [events] flow never completes, so the core's
     * collector stays parked instead of finishing, and switching that on for every suite at once is a
     * change to three hundred tests to serve one. Only the suite that emits events asks for it.
     */
    private val publishesEvents: Boolean = false,
    /**
     * The platform effect handle this sink claims while a device is open, or null for a platform
     * that has none. Null by default, which is what every sink but Android's answers.
     */
    private val sessionIdAnswer: Int? = null,
) : AudioSink {

    /* Mirrors the real sinks: a handle from open until close, and nothing outside that. Keyed on
     * the negotiated format rather than on `running`, because a paused device still has a session
     * and an effect attached to it is still live. */
    override val platformSessionId: Int? get() = if (negotiated != null && !closed) sessionIdAnswer else null

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

    /** What the engine declared the sound to be before each open, in order (#446). */
    val declaredContents: MutableList<io.github.yuroyami.kiteplayer.AudioContent> = mutableListOf()

    override fun setContent(content: io.github.yuroyami.kiteplayer.AudioContent) {
        declaredContents += content
    }

    /** Every call the engine made on the device, in order: open, start, stop, pause, resume, drain, close. */
    val calls: MutableList<String> = mutableListOf()

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
        calls += "open"
        openCount++
        openRequests += request
        val format = accepts ?: request
        this.render = render
        this.negotiated = format
        this.buffer = ScriptedSinkBuffer(format, deviceBufferFrames)
        return format
    }

    override suspend fun start() {
        calls += "start"
        startCount++
        running = true
    }

    override suspend fun stop() {
        calls += "stop"
        stopCount++
        trace.record("sink.stop")
        // A device whose stop has wedged. Rechecked rather than parked for ever, so the test that
        // arms it can also release it and leave nothing running.
        while (faults.stopHangs) delay(1_000)
        running = false
    }

    override suspend fun drain() {
        calls += "drain"
        drainCount++
        if (faults.drainHangs) {
            // A device that never reports its buffer empty. The engine must bound its own wait rather
            // than poll a lost device forever.
            while (true) delay(1_000)
        }
        running = false
    }

    override suspend fun setPaused(paused: Boolean): Boolean {
        calls += if (paused) "pause" else "resume"
        running = !paused
        return true
    }

    override fun latencyNanos(): Long = latencyNanosAnswer

    override val latencyQuality: LatencyQuality = LatencyQuality.Estimated

    private val published = MutableSharedFlow<AudioSinkEvent>(extraBufferCapacity = 16)

    override val events: Flow<AudioSinkEvent> = if (publishesEvents) published else emptyFlow()

    /** Emit a device event as the platform would, for a test that constructed this with events on. */
    suspend fun publish(event: AudioSinkEvent) {
        check(publishesEvents) { "construct ScriptedSink(publishesEvents = true) to emit events" }
        published.emit(event)
    }

    override fun close() {
        calls += "close"
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
        if (recordsSamples) destination.appendChannel(0, written, recorded)
        val heard = destination.distinctValues(written)
        audibleValues += heard
        heard.forEach { audibleSigns += if (it > 0f) 1 else if (it < 0f) -1 else 0 }
        recordChannelPeaks(destination, written)
    }

    /** True makes the device keep every sample it plays of its first channel, in [recorded]. */
    var recordsSamples: Boolean = false

    /** Every sample of the first channel the device played while [recordsSamples] was true, in order. */
    val recorded: SampleRecord = SampleRecord()

    /** The loudest magnitude heard in each channel, which is what a balance test measures. */
    val channelPeaks: MutableMap<Int, Float> = mutableMapOf()

    /** The loudest magnitude heard in [channel] so far, or 0 when that channel never carried any. */
    fun channelPeak(channel: Int): Float = channelPeaks[channel] ?: 0f

    /**
     * Forgets every channel peak, so what follows is measured on its own.
     *
     * A peak is a running maximum, so a test that changes something mid-playback and then reads one
     * is reading the loudest moment of the WHOLE session, including everything before the change.
     */
    fun clearChannelPeaks() { channelPeaks.clear() }

    private fun recordChannelPeaks(destination: ScriptedSinkBuffer, frames: Int) {
        val channels = negotiated?.channels ?: return
        for (channel in 0 until channels) {
            val magnitude = destination.channelPeak(channel, frames)
            val seen = channelPeaks[channel] ?: 0f
            if (magnitude > seen) channelPeaks[channel] = magnitude
        }
    }

    /**
     * Pulls at the device's own period until the caller's scope is cancelled.
     *
     * The period is read every time round, because it is only known once a format has been negotiated.
     * Reading it once, before the device was opened, made this pull ten times too often, and a device
     * that consumes ten times faster than real time makes the audio clock run at ten times speed.
     */
    suspend fun runDevice(clock: MonotonicClock) {
        // The next pull is scheduled on an exact running total. Waiting the period truncated to
        // whole milliseconds pulled 512 frames every 10 ms at 48 kHz, so the device played 10.67 ms
        // of audio per 10 ms and the audio clock ran 6.7 percent fast against the picture.
        var due = clock.nanos()
        while (true) {
            pump(clock.nanos())
            due += bufferNanos().coerceAtLeast(1_000_000)
            val waitNanos = due - clock.nanos()
            delay(((waitNanos + 999_999) / 1_000_000).coerceAtLeast(1))
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

    /** The loudest magnitude in [channel] over the first [frames] frames. */
    fun channelPeak(channel: Int, frames: Int): Float {
        var peak = 0f
        for (frame in 0 until frames) {
            val value = data[frame * format.channels + channel]
            val magnitude = if (value < 0f) -value else value
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    /** Appends [channel] of the first [frames] frames to [into]. */
    fun appendChannel(channel: Int, frames: Int, into: SampleRecord) {
        for (frame in 0 until frames) into.add(data[frame * format.channels + channel])
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

    override fun writeSilence(frameOffset: Int, frames: Int) {
        val from = frameOffset * format.channels
        data.fill(0f, from, from + frames * format.channels)
    }
}

/** The output half, scripted: one sink and one clock. */
internal class ScriptedOutput(
    override val clock: MonotonicClock,
    val sink: ScriptedSink,
) : OutputBackend {
    /** Cue identities handed to the rasterizer, in publication order. */
    val rasterizedCueTexts: MutableList<List<String>> = mutableListOf()

    /** The first-span style of every TEXT cue each raster call received, for the override tests. */
    val rasterizedCueStyles: MutableList<List<io.github.yuroyami.kiteplayer.subtitle.CueStyle>> = mutableListOf()

    /** Thrown by every raster call while set, after the call is recorded, as a broken font engine would. */
    var rasterizeFailure: Exception? = null

    /**
     * Sinks the factory hands out before it hands out [sink] again, in order. A test of an output
     * that is replaced puts the replacement here and drives it with [ScriptedSink.runDevice] (#563).
     */
    val laterSinks: ArrayDeque<ScriptedSink> = ArrayDeque()

    /** What the scripted output says it carries, and how often the engine asked (#466). */
    var outputChannels: Int? = null
    var outputChannelQuestions: Int = 0

    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "scripted"
        override suspend fun create(): AudioSink = laterSinks.removeFirstOrNull() ?: sink
        override fun outputChannelCount(): Int? = outputChannels.also { outputChannelQuestions++ }
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
                rasterizedCueStyles += cues
                    .filterIsInstance<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text>()
                    .mapNotNull { it.spans.firstOrNull()?.style }
                rasterizedCueTexts += cues.map { cue ->
                    when (cue) {
                        is io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text -> cue.plainText
                        is io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Bitmap -> "<bitmap>"
                    }
                }
                rasterizeFailure?.let { throw it }
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

/**
 * A reader with no bytes of its own that reports the script's link as its measured network rate,
 * as a network reader would. The scripted source still serves the packets.
 */
internal class LinkIo(private val script: MediaScript, private val clock: MonotonicClock) : MediaIo {
    override val size: Long? get() = null
    override val seekable: Boolean get() = true

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = -1

    override suspend fun seek(position: Long) {}

    override fun close() {}

    override fun networkBitsPerSecond(): Long? = script.linkBitsPerSecond?.invoke(clock.nanos() / 1_000)
}

/** A growing run of samples, kept as floats without boxing, for a test that reads what was heard. */
internal class SampleRecord {
    private var data = FloatArray(1 shl 16)

    var size: Int = 0
        private set

    fun add(sample: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = sample
    }

    operator fun get(index: Int): Float = data[index]

    fun toFloatArray(): FloatArray = data.copyOf(size)
}
