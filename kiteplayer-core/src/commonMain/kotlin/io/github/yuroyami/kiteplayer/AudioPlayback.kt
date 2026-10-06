package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioPipeline
import io.github.yuroyami.kiteplayer.internal.GAIN_MAX
import io.github.yuroyami.kiteplayer.internal.KotlinAudioRing
import io.github.yuroyami.kiteplayer.internal.AudioRingHandle
import io.github.yuroyami.kiteplayer.internal.MediaClock
import io.github.yuroyami.kiteplayer.internal.ClockSnapshot
import io.github.yuroyami.kiteplayer.internal.OpenedAudioPath
import io.github.yuroyami.kiteplayer.internal.PlayoutTimeline
import io.github.yuroyami.kiteplayer.internal.SourceTimeline
import io.github.yuroyami.kiteplayer.internal.TempoStage
import io.github.yuroyami.kiteplayer.internal.framesToMicros
import io.github.yuroyami.kiteplayer.internal.openAudioPath
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory
import io.github.yuroyami.kiteplayer.spi.AudioSink
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The engine's audio half: a device, a ring, and the clock derived from them.
 *
 * The full player composes this with the video path. On its own it is a complete audio player, which
 * is why it is public: a music application needs exactly this and nothing more.
 *
 * ### What it is responsible for
 *
 * Accepting decoded PCM, holding it so the device's period size is invisible to the decoder, and
 * maintaining the master clock. That last part is the reason this class exists rather than the caller
 * writing to a sink directly. The clock is not counted from samples submitted; it is anchored to the
 * instant the device says a specific frame becomes audible. Every player that instead estimates the
 * device latency ships a fixed audio delay that nothing corrects.
 *
 * ### Threading
 *
 * [submitDecoded] belongs to one coroutine, the audio feeder, because it is the ring's single
 * producer, and the conversion stage behind it belongs to the same coroutine. The device's real-time
 * callback is the single consumer and touches nothing else in this class, so
 * neither side takes a lock.
 *
 * [anchorClock], [position], [buffered], [underruns], the [speed] setter and every gain change are
 * guarded by one internal lock. The first two write the media clock, which has one writer by
 * design, and a player reports progress from a thread that is not the one driving playback: two
 * callers re-anchoring the same clock at once is what the lock is for. [buffered] and [underruns]
 * take it for a second reason that came with the C callback: they read the ring, and the ring can
 * now be memory [close] frees. A gain change writes into the ring, so it takes the lock for the
 * same reason. Reading [speed] is a plain read of one value and needs nothing.
 *
 * [open], [play], [pause], [flush], [drain], [endOfStream] and [close] are thread confined to the
 * session owner instead. [submitDecoded] runs on the feed worker and reads the ring FIELD under
 * the lock, so the rule is one sentence again: any member that
 * may run beside another thread touches that field only under the lock. A lock cannot be held across a suspension point, so the suspending ones
 * could not be guarded even in principle, and their contract is confinement. The core's session
 * actor is that owner. The seek path already depends on this: the ring's own flush requires both
 * of its sides to be quiescent first.
 *
 * [close] is the one member that is confined AND takes the lock, for one statement. Confinement says
 * no other owner call runs beside it; it says nothing about the four members above, which are
 * documented safe from any thread. Clearing the ring reference inside the lock is what makes those
 * four safe against a teardown that frees a C ring underneath them.
 */
public class AudioPlayback(
    private val sink: AudioSink,
    private val clock: MonotonicClock = MonotonicClock.System,
    /**
     * How much decoded audio to hold ahead of the device. Sized so the device's own period never
     * matters to the rest of the player.
     */
    private val bufferDuration: Duration = 200.milliseconds,
    private val onWarning: (PlaybackWarning) -> Unit = {},
    /** The LFE and headroom policy the downmix applies; see [DownmixConfig]. */
    private val downmix: DownmixConfig = DownmixConfig(),
    /** Makes the rate conversion when the rates differ. Null uses the engine's own sinc. */
    private val resampler: AudioResamplerFactory? = null,
    /** Whether mono or stereo also fills a surround device; see [UpmixMode]. */
    private val upmix: UpmixMode = UpmixMode.Off,
) : AutoCloseable {

    /**
     * The ring, behind the interface, because there are two implementations of it and there always
     * will be: see [AudioRingHandle].
     *
     * Which one this is depends on the sink and not on the platform, and this class does not know
     * which it got. A sink that owns its device callback in C owns a C ring and the engine writes into
     * it; every other sink gets a `KotlinAudioRing` behind a Kotlin render callback. The one line that
     * decides is `openAudioPath`, and nothing else in this file changes with the answer.
     */
    private var ring: AudioRingHandle? = null

    /**
     * How long the feeder waits on a full ring before it tries again: the time the device takes to
     * play a quarter of the ring, set at open. A shorter wait refills sooner and buys nothing, because
     * three quarters of the ring still stand between the device and an underrun, and each wait is a
     * wake-up that keeps a phone's cores out of idle. A paused device frees nothing at all.
     */
    private var fullRingWait: Duration = FULL_RING_WAIT_MIN

    /** Opens the device and the ring behind it. A test replaces it to supply its own ring. */
    internal var openPath: suspend (AudioSink, AudioFormat, (AudioFormat) -> Int) -> OpenedAudioPath =
        ::openAudioPath

    /**
     * The conversion from what the decoder produces to what the device took. Built on the first
     * [submitDecoded] and rebuilt whenever the decoder's format changes, so a caller that converts on
     * its own and uses [submit] never has one.
     */
    private var pipeline: AudioPipeline? = null

    /**
     * The factory each new pipeline asks for its resampler, until it refuses once. Then null, and
     * the engine's own sinc from there on. Read and written by the feeder only.
     */
    private var resamplerFactory: AudioResamplerFactory? = resampler

    private val onResamplerRefused: (Throwable) -> Unit = { failure ->
        resamplerFactory = null
        onWarning(
            PlaybackWarning.ResamplerUnavailable(failure.message ?: failure::class.simpleName ?: "failed to start"),
        )
    }

    /** Guards the media clock against the members that may be called from more than one thread. */
    private val lock = SynchronizedObject()

    // The wanted volume and mute, held here rather than written straight into the gain stage. The stage
    // belongs to the feeder and is not thread safe, so a change arriving from another thread is a value
    // the feeder picks up on its next buffer, which is one sample frame of delay and no race.
    private val wantedVolume = atomic(1f)
    private val wantedMute = atomic(false)

    /**
     * The linear ReplayGain for the track being fed, applied by the pipeline's trim stage.
     *
     * On the way IN, unlike the volume, and deliberately: this is a property of the material rather
     * than of the listener, it changes only when a track does, and it must be part of what the ring
     * buffers rather than something applied to it afterwards.
     */
    private val wantedReplayGain = atomic(1f)

    /** Stereo balance, -1 hard left to 1 hard right. Combined with the replay gain in one stage. */
    private val wantedBalance = atomic(0f)

    /** What the two front speakers play (#462). */
    private val wantedStereoMode = atomic(StereoMode.Stereo)

    /** The equaliser the feeder applies. Held here so a pipeline rebuild cannot lose it. */
    private val wantedEqualizer = atomic(EqualizerSettings.Flat)

    private var generation: Generation = Generation.Initial
    private var format: AudioFormat? = null
    private var warnedAboutLatency = false
    private var closed = false

    /** The rate the feeder applies to the next buffer. Written under the lock by [speed]. */
    private var wantedSpeed: Double = 1.0

    /** Whether the next buffer keeps pitch away from 1.0. Written under the lock by [preservePitch]. */
    private var wantedPreservePitch: Boolean = true

    /**
     * The device clock on the ring's playout axis, where one frame further along is always one
     * frame period later. Anchored from the ring and read through [timeline], so it never needs to
     * know the speed.
     */
    private val playoutClock = MediaClock(clock)

    /**
     * Playout time to media time, line by line. The feeder appends a line wherever the speed, the
     * media or the item changes; readers map through it. Under [lock].
     */
    private val timeline = PlayoutTimeline()

    /** Source frames of the current pipeline to media time. The feeder's alone. */
    private val sourceTimeline = SourceTimeline()

    /** Source frames handed to the current pipeline since it was built or reset. Feeder only. */
    private var sourceFrames: Long = 0

    /** Frames written to the ring since the last flush or open. Feeder only. */
    private var ringFrames: Long = 0

    /**
     * Where the playout axis is pinned: ring frame [playoutBaseFrame] plays at playout time
     * [playoutBaseUs]. Set on the first frame with a known media time, so playout and media time
     * agree there. Feeder only.
     */
    private var playoutBaseFrame: Long = -1
    private var playoutBaseUs: Long = 0

    /** True once the ring holds a timestamp for this epoch; later writes continue it. Feeder only. */
    private var ringDated = false

    /**
     * The instant of the ring's last anchor when [play] ran, until the device publishes a newer one.
     *
     * The ring keeps its last anchor through a pause. Applied after [play], that anchor dates the
     * clock from before the pause, so the reading counts the whole pause as played time. Guarded
     * by [lock].
     */
    private var anchorFloorNanos: Long? = null

    /**
     * The gapless join: the next queue item's audio continuing this ring. See
     * `docs/gapless-queue.md`. Under [lock], because the feeder moves it forward until
     * [Join.Writing] and a reader on any thread moves it to [Join.Crossed].
     */
    private var join: Join = Join.None

    /** Where the next item's first sample plays, on the playout axis. */
    private var joinBoundaryUs: Long = 0

    /** The next item's own timestamp at that sample. */
    private var joinOriginUs: Long = 0

    /** The end of the item before the join, in its own media time. The clock holds there until [commitJoin]. */
    private var joinHeldUs: Long = 0

    private enum class Join {
        None,

        /** [beginJoin] ran; the next item's first timestamped buffer has not arrived. */
        Armed,

        /** The next item's first buffer is in the pipeline; no output of it has reached the ring. */
        Pending,

        /** The next item's samples are in the ring; the device still plays the item before it. */
        Writing,

        /** The device reached the next item's first sample; the owner has not swapped yet. */
        Crossed,

        /** The owner swapped: the clock reads the new item's own timestamps until the next flush. */
        Committed,
    }

    /** The format the device accepted. Null before [open]. */
    public val negotiatedFormat: AudioFormat? get() = format

    /**
     * How much submitted audio has not yet been handed to the device.
     *
     * Under the lock, like [position], and for the reason given on [close]: the ring can now be
     * a pointer into C that [close] frees, so every member that may be called from another thread
     * reads the field inside the lock that [close] clears it in.
     */
    public val buffered: Duration
        get() = synchronized(lock) { (ring?.bufferedUs ?: 0L).microseconds }

    /** Callbacks handed silence because the ring had run dry. Under the lock, as [buffered] is. */
    public val underruns: Long get() = synchronized(lock) { ring?.underruns ?: 0 }

    /**
     * Rendered frames the ring's peak limiter turned down because they would have passed full
     * scale. Under the lock, as [buffered] is.
     */
    public val limitedFrames: Long get() = synchronized(lock) { ring?.limitedFrames ?: 0 }

    public val latencyQuality: LatencyQuality get() = sink.latencyQuality

    /** The sink's platform handle for audio effects, or null. See `AudioSink.platformSessionId`. */
    public val platformSessionId: Int? get() = sink.platformSessionId

    /**
     * What the device says it is holding: handed over, not yet audible.
     *
     * Diagnostic only. The clock is anchored to the instant the device reports a specific frame
     * became audible, so nothing here needs this figure to keep time. It is worth reporting because
     * it is the number that explains a device whose buffer is enormous, and reading it costs a
     * field access per stats interval.
     */
    public val latencyNanos: Long get() = sink.latencyNanos()

    /** The sink's own event feed, surfaced so the engine can warn on device loss. */
    public val events: kotlinx.coroutines.flow.Flow<io.github.yuroyami.kiteplayer.spi.AudioSinkEvent>
        get() = sink.events

    /**
     * Opens the device and sizes the ring for it.
     *
     * @param request the format the decoder produces.
     * @return the format the device accepted. Resample to this before calling [submit].
     */
    public suspend fun open(request: AudioFormat): AudioFormat {
        check(ring == null) { "this audio path is already open" }

        // The device and the ring, opened together, because the ring's format is the format the device
        // accepted and its capacity depends on that format and on the device's own period. Which kind
        // of ring comes back is the sink's choice; see `openAudioPath`.
        var capacityFrames = 0
        val opened = openPath(sink, request) { negotiated ->
            max(
                sink.deviceBufferFrames * DEVICE_BUFFER_MULTIPLE,
                negotiated.framesIn(Pts(bufferDuration.inWholeMicroseconds)),
            ).also { capacityFrames = it }
        }
        val negotiated = opened.format
        fullRingWait = if (negotiated.sampleRate > 0) {
            (capacityFrames / 4 * 1_000_000L / negotiated.sampleRate).microseconds
                .coerceIn(FULL_RING_WAIT_MIN, FULL_RING_WAIT_MAX)
        } else {
            FULL_RING_WAIT_MIN
        }
        // Under the lock, like every write of the field that a member on another thread reads.
        synchronized(lock) { ring = opened.ring }
        format = negotiated
        // The gain is the ring's now, so a path opened while muted or turned down must start there
        // rather than at unity. Without this, opening a file at volume 0 plays one ramp of
        // full-scale audio before the walk catches up, which is what `adoptRamp` used to exist for
        // on the pipeline side.
        pushGain()
        synchronized(lock) {
            timeline.clear()
            join = Join.None
        }
        restartEpoch()

        if (sink.latencyQuality == LatencyQuality.Unreliable && !warnedAboutLatency) {
            warnedAboutLatency = true
            onWarning(
                PlaybackWarning.AudioLatencyUnreliable(
                    "the ${sink::class.simpleName} sink cannot measure its latency, so synchronisation is approximate",
                ),
            )
        }
        return negotiated
    }

    /** The feeder's bookkeeping for an empty ring. */
    private fun restartEpoch() {
        sourceTimeline.clear(pipeline?.sourceFormat?.sampleRate ?: 1)
        sourceFrames = 0
        ringFrames = 0
        playoutBaseFrame = -1
        playoutBaseUs = 0
        ringDated = false
    }

    /**
     * Writes samples that are already in the negotiated format straight into the ring, suspending
     * until all of them have been accepted.
     *
     * It skips every stage that [submitDecoded] runs: the channel mix, the rate conversion, the
     * tempo stage, the equaliser, the balance and the ReplayGain, and it skips the timeline too, so
     * [pts] is the ring's own time and the clock reads it unconverted. Only the volume and the mute
     * still apply, because they live on the ring's read side. [submitDecoded] is its only caller
     * outside the tests.
     *
     * @param pts the timestamp of the first frame on the ring's axis, or null to continue the
     *        previous buffer.
     * @param interleaved channel-interleaved float samples in the negotiated format.
     * @param frames sample frames in [interleaved], meaning one value per channel each.
     * @param abort polled while the ring is full. Returning true gives the buffer up: whatever
     *        was already accepted stays accepted, the remainder is abandoned, and the caller is
     *        expected to flush (a seek's quiescence is the intended trigger). This exists so an
     *        incremental commit is NEVER retried from the outside: cancelling this function
     *        mid-buffer and calling it again would replay samples the ring already took and run
     *        the conversion state twice.
     */
    internal suspend fun submit(
        pts: Pts?,
        interleaved: FloatArray,
        frames: Int,
        abort: () -> Boolean = { false },
        idle: suspend (Duration) -> Unit = { delay(it) },
    ) {
        // The FIELD is read under the lock, extending the one-sentence rule to the producer:
        // a member that may run beside [close] touches `ring` only under
        // the lock, so a submit can never load the reference in the same instant close is
        // clearing and freeing it. What the lock cannot do is protect the rest of this loop; that
        // is [close]'s quiescence precondition, and the engine honours it by joining the feeder
        // before anything frees a ring.
        val ring = synchronized(lock) { ring } ?: error("submit was called before open")
        var offset = 0
        var firstChunk = true
        while (offset < frames) {
            val accepted = ring.write(
                source = interleaved,
                sourceOffset = offset * ring.format.channels,
                frames = frames - offset,
                pts = if (firstChunk) pts else null,
            )
            if (accepted == 0) {
                if (abort()) return
                // The ring is full, which means the device has as much as it can hold. The engine
                // passes a nap that a quiesce request ends at once, so a seek does not wait this out.
                idle(fullRingWait)
                continue
            }
            ringFrames += accepted
            offset += accepted
            firstChunk = false
        }
    }

    /**
     * Hands decoded audio over, suspending until all of it has been accepted.
     *
     * The samples arrive in [sourceFormat], which is what the decoder said it produced, and run
     * through the whole audio path before they reach the ring: the channel mix, the rate
     * conversion, the tempo stage, the equaliser, the balance and the ReplayGain. This is the one
     * feed, so every setting of this class acts on what arrives here.
     *
     * Suspending here is the backpressure that paces decoding to playback. The caller does not
     * need to know how full the device is.
     *
     * The stage is keyed on [sourceFormat] against the format it was built for, so a decoder that
     * changes format mid-stream gets a new one on the buffer that changed rather than one buffer
     * later. The old one plays out what it still holds first. When [sourceFormat] already is the
     * negotiated format every stage is a copy and the cost is one pass over the samples.
     *
     * @param pts the media timestamp of the first frame, when the decoder gave one. Passing null is
     *        normal for buffers that continue from the previous one.
     * @param interleaved channel-interleaved float samples in [sourceFormat].
     * @param frames sample frames in [interleaved], meaning one value per channel each.
     * @param abort polled while the ring is full. Returning true gives up the rest of the buffer,
     *        and the caller is expected to flush, as a seek does.
     * @throws PlaybackException carrying [PlaybackError.DecoderFailed] when [sourceFormat] has no
     *         channels or no rate, or when a stage would need more than 4 Mi float values for this
     *         buffer. The refused stage allocates nothing.
     */
    public suspend fun submitDecoded(
        pts: Pts?,
        interleaved: FloatArray,
        frames: Int,
        sourceFormat: AudioFormat,
        abort: () -> Boolean = { false },
    ): Unit = submitDecoded(pts, interleaved, frames, sourceFormat, abort) { delay(it) }

    /**
     * [submitDecoded] with the wait on a full ring supplied by the caller. The engine passes its
     * worker's nap, which a quiesce request ends at once.
     */
    internal suspend fun submitDecoded(
        pts: Pts?,
        interleaved: FloatArray,
        frames: Int,
        sourceFormat: AudioFormat,
        abort: () -> Boolean,
        idle: suspend (Duration) -> Unit,
    ) {
        val negotiated = format ?: error("submitDecoded was called before open")
        // Read once per buffer: a change between two buffers applies to the second, and the
        // timeline dates the boundary wherever the tempo stage puts it.
        val speedNow = synchronized(lock) { wantedSpeed }
        val pitchNow = synchronized(lock) { wantedPreservePitch }
        val joinStarts = pts != null && synchronized(lock) { join == Join.Armed }
        val existing = pipeline
        val stage = when {
            existing == null -> AudioPipeline(
                sourceFormat, negotiated, onWarning, preservePitch = pitchNow, downmix = downmix,
                resamplerFactory = resamplerFactory, onResamplerRefused = onResamplerRefused,
                initialSpeed = speedNow, upmix = upmix,
            )
            existing.matches(sourceFormat) -> existing
            else -> {
                // The old stages still hold the end of the old format. They play it out first, so
                // nothing goes missing at the change and the timeline dates it the old way.
                drainPipeline(existing, abort, idle)
                existing.rebuiltFor(sourceFormat, pitchNow, speedNow, resamplerFactory)
            }
        }
        // Only a FORMAT change is worth saying out loud. A join is not news: the next item is
        // allowed a sample format of its own.
        if (existing != null && stage !== existing && !joinStarts) {
            onWarning(
                PlaybackWarning.AudioSourceFormatChanged(
                    fromSampleRate = existing.sourceFormat.sampleRate,
                    fromChannels = existing.sourceFormat.channels,
                    toSampleRate = sourceFormat.sampleRate,
                    toChannels = sourceFormat.channels,
                ),
            )
        }
        if (stage !== existing) {
            // A fresh pipeline has a fresh equaliser at flat, so the cache of what was written
            // into the OLD one must not stop the new one being configured.
            appliedEqualizer = null
            // A fresh pipeline counts its source frames from zero.
            sourceTimeline.clear(sourceFormat.sampleRate)
            sourceFrames = 0
            // The old stage's resampler may hold native memory.
            existing?.close()
        }
        pipeline = stage
        // The gain is NOT picked up here any more. It moved to the ring's read side, because a gain
        // applied on the way into the ring cannot reach audio already buffered, and a listener hears
        // the whole ring depth of the old volume before the change arrives. See AudioRingHandle.setGain.
        stage.speed = speedNow
        stage.preservePitch = pitchNow
        // Reasserted per buffer: a pipeline rebuilt for a format change starts at unity, and the
        // trim has to survive that without the rebuild knowing. Idempotent and a handful of
        // floats, so the common case costs a compare.
        applyTrim(stage)
        // Reasserted per buffer like the trim: a rebuilt pipeline's fresh stage takes the mode at
        // once, and a running one ramps to a change.
        stage.stereo.set(wantedStereoMode.value)
        // Reasserted per buffer like the trim and for the same reason: a pipeline rebuilt for a
        // format change starts flat, and a flat stage is skipped, so the cost when nothing is set
        // is one reference compare.
        val wantedEq = wantedEqualizer.value
        if (appliedEqualizer !== wantedEq) {
            stage.equalizer.set(wantedEq)
            appliedEqualizer = wantedEq
        }

        if (pts != null) {
            sourceTimeline.record(sourceFrames, pts.micros, join = joinStarts)
            if (joinStarts) synchronized(lock) { join = Join.Pending }
        }
        // Converted exactly once: the mixer, resampler and tempo stage all carry state, so running
        // process twice over the same input is audible, not just wasteful.
        val produced = stage.process(interleaved, frames)
        sourceFrames += frames
        writeDated(stage, produced, abort, idle)
    }

    /** Plays out what [stage] still holds, for a format change, dated by the old source timeline. */
    private suspend fun drainPipeline(stage: AudioPipeline, abort: () -> Boolean, idle: suspend (Duration) -> Unit) {
        val produced = stage.finish()
        writeDated(stage, produced, abort, idle)
    }

    /**
     * Writes what [stage] just produced into the ring, after recording where each run of it sits in
     * media time.
     *
     * The ring is written by continuity: it gets one timestamp per epoch, on the first frame whose
     * media time is known, and dates everything after it by counting frames. Every change of speed,
     * every jump in the decoder's timestamps and the start of the next queue item becomes a line in
     * [timeline] instead, at the exact frame where the output reaches it.
     */
    private suspend fun writeDated(
        stage: AudioPipeline,
        produced: Int,
        abort: () -> Boolean,
        idle: suspend (Duration) -> Unit,
    ) {
        if (produced <= 0) return
        val chunkStart = ringFrames
        if (!sourceTimeline.isEmpty) {
            val sourceRate = stage.sourceFormat.sampleRate
            for (index in 0 until stage.pieceCount) {
                val start = stage.pieceStart(index)
                val end = if (index + 1 < stage.pieceCount) stage.pieceStart(index + 1) else produced
                if (end <= start) continue
                val source = stage.pieceSource(index)
                val step = stage.pieceSlope(index)
                // Media microseconds per playout microsecond.
                val slope = stage.pieceRate(index)
                val first = chunkStart + start
                // Points at or before the run's first source position, such as a join at the first
                // frame of a fresh pipeline, take effect at its first frame.
                var after = sourceTimeline.takenThrough.toDouble()
                while (true) {
                    val point = sourceTimeline.nextPointIn(after, source)
                    if (point < 0) break
                    takePoint(point, first)
                    after = sourceTimeline.pointFrame(point).toDouble()
                }
                addLine(first, source, slope)
                // Points inside the run take effect at the first output frame that reaches them.
                val reach = source + (end - start) * step
                after = maxOf(after, source)
                while (true) {
                    val point = sourceTimeline.nextPointIn(after, reach)
                    if (point < 0) break
                    val at = sourceTimeline.pointFrame(point).toDouble()
                    val offset = kotlin.math.ceil((at - source) / step).toLong().coerceAtLeast(0)
                    if (start + offset >= end) break
                    takePoint(point, first + offset)
                    addLine(first + offset, source + offset * step, slope)
                    after = at
                }
                // A second of source kept back, for a stretch that settles a little behind.
                sourceTimeline.prune(source - sourceRate)
            }
        }
        val pts = if (!ringDated && playoutBaseFrame >= 0) Pts(playoutAt(chunkStart)) else null
        if (pts != null) ringDated = true
        submit(pts, stage.output, produced, abort, idle)
    }

    /** The output reaches source point [point] at ring frame [frame]: a join starts writing there. */
    private fun takePoint(point: Int, frame: Long) {
        sourceTimeline.takenThrough = sourceTimeline.pointFrame(point)
        if (!sourceTimeline.pointIsJoin(point)) return
        val boundary = playoutAt(frame)
        synchronized(lock) {
            if (join != Join.Pending) return@synchronized
            joinBoundaryUs = boundary
            joinOriginUs = sourceTimeline.pointMedia(point)
            joinHeldUs = timeline.predictLast(boundary)?.toLong() ?: boundary
            join = Join.Writing
        }
    }

    /** Records that ring frame [frame] plays source position [source] onward at [slope]. */
    private fun addLine(frame: Long, source: Double, slope: Double) {
        val media = sourceTimeline.mediaAt(source)
        if (playoutBaseFrame < 0) {
            // The playout axis is pinned where media time is first known, so the two agree there.
            playoutBaseFrame = frame
            playoutBaseUs = media.toLong()
        }
        val playout = playoutAt(frame)
        synchronized(lock) { timeline.append(playout, media, slope) }
    }

    /** Ring frame [frame]'s playout time. */
    private fun playoutAt(frame: Long): Long =
        playoutBaseUs + framesToMicros(frame - playoutBaseFrame, format?.sampleRate ?: 1)

    /**
     * Anchors the clock from what the device last reported.
     *
     * The core calls this once at the top of an iteration, so the anchoring happens at one known
     * point rather than wherever the first reader of the clock happens to be. It is not a promise
     * that every later reader in the same iteration sees one frozen reading: [position] anchors
     * again before it reads, and a clock reading advances with the monotonic clock in any case.
     *
     * Safe from any thread.
     */
    public fun anchorClock(): Unit = synchronized(lock) { anchorLocked() }

    /**
     * What media timestamp is audible now, or null when nothing has played since the last flush.
     *
     * Anchors first, so the answer is never older than the device's last report. Null is a normal
     * answer, not an error: it is what the clock says between a seek and the first audio of the new
     * position.
     *
     * Safe from any thread.
     */
    public fun position(): Pts? = synchronized(lock) {
        anchorLocked()
        crossLocked()
        playoutClock.nowOrNull()?.let(::mediaLocked)
    }

    /** Actor publication uses one mapping instead of separately reading position and rate. */
    internal fun clockSnapshot(): ClockSnapshot = synchronized(lock) {
        anchorLocked()
        crossLocked()
        val snapshot = playoutClock.snapshot()
        val playout = snapshot.pts
        snapshot.copy(
            pts = playout?.let(::mediaLocked),
            speed = playout?.let { timeline.slopeAt(it.micros) } ?: timeline.slopeAt(0),
        )
    }

    /**
     * The media time that plays at [playout], with the join's hold applied: the item before a join
     * never reads past its end, and once the owner commits, the new item never reads before its
     * start.
     */
    private fun mediaLocked(playout: Pts): Pts {
        val media = timeline.mediaAt(playout.micros)?.let(::Pts) ?: playout
        return when (join) {
            Join.Crossed -> Pts(joinHeldUs)
            Join.Writing -> if (media.micros > joinHeldUs) Pts(joinHeldUs) else media
            Join.Committed -> if (playout.micros < joinBoundaryUs) Pts(joinOriginUs) else media
            else -> media
        }
    }

    /**
     * Notices that the device is past the item before a join. Read from the clock and not from the
     * anchor, because an anchor dates the end of what the device took, which it plays later: the
     * clock already counts that delay, and it stands still while the device is paused.
     */
    private fun crossLocked() {
        if (join != Join.Writing) return
        val playout = playoutClock.nowOrNull() ?: return
        if (playout.micros >= joinBoundaryUs) join = Join.Crossed
    }

    private fun anchorLocked() {
        val anchor = ring?.anchor() ?: return
        anchorFloorNanos?.let { floor ->
            if (anchor.audibleAtNanos <= floor) return
            anchorFloorNanos = null
        }
        playoutClock.setAt(anchor.pts, generation, anchor.audibleAtNanos)
        // The clock reads up to the device's latency behind the anchor, so lines are kept for a
        // generous margin before it.
        timeline.prune(anchor.pts.micros - TIMELINE_KEEP_US)
    }

    /**
     * Starts a gapless join: the next buffers handed to [submitDecoded] belong to the next queue
     * item and continue this ring after the last sample already in it. The device, the ring and
     * the conversion stages carry on untouched.
     *
     * Belongs to the session owner, with the feeder of the previous item parked and the feeder of
     * the next one not yet started, because the next [submitDecoded] reads it.
     */
    internal fun beginJoin(): Unit = synchronized(lock) {
        check(ring != null) { "a join needs an open audio path" }
        join = Join.Armed
    }

    /** True once the device has played the first sample of the next item. Safe from any thread. */
    internal val joinCrossed: Boolean
        get() = synchronized(lock) {
            anchorLocked()
            crossLocked()
            join == Join.Crossed
        }

    /**
     * Ends the join: from here the clock reads the new item's own timestamps. The owner calls it
     * when it makes the next item the current one. A flush ends a join too.
     */
    internal fun commitJoin(): Unit = synchronized(lock) {
        if (join == Join.None || join == Join.Committed) return
        join = Join.Committed
        anchorLocked()
    }

    /**
     * Whether the device was started and has not been stopped since, so a fade has a device pulling
     * it. The session owner's alone, like [play] and [pause].
     */
    private var deviceRunning = false

    /**
     * Starts the device and lets the clock run. Belongs to the session owner.
     *
     * A ring that a fade left silent walks back up to the volume over its first frames, so the
     * sound fades in rather than starting at full level (#486).
     */
    public suspend fun play() {
        synchronized(lock) {
            playoutClock.resume()
            anchorFloorNanos = ring?.anchor()?.audibleAtNanos
            ring?.hold(false)
        }
        sink.setPaused(false)
        sink.start()
        deviceRunning = true
    }

    /**
     * Freezes the clock and holds the device without discarding. Belongs to the session owner.
     *
     * On a device that cuts the sound where it stops, the sound fades out first and the clock
     * freezes where the fade ended, so the paused position is the last one the listener heard and
     * the resume carries on from the frame after it (#486).
     */
    public suspend fun pause() {
        val faded = fadeOut()
        // Under the lock like play: position and anchorClock move this clock from other threads.
        synchronized(lock) {
            playoutClock.pause()
            if (faded) settleOnFadeLocked()
        }
        if (faded) {
            // The device holds only the silence after the fade now, so dropping it costs nothing
            // and saves the resume from playing that silence before the sound.
            sink.stop()
            sink.setPaused(true)
        } else if (!sink.setPaused(true)) {
            sink.stop()
        }
        deviceRunning = false
    }

    /**
     * Fades the sound out and stops the device, discarding what it holds. The seek path and the
     * end of a session take the device down through this, so neither cuts the sound mid-wave
     * (#486). Belongs to the session owner.
     */
    internal suspend fun stopDevice() {
        fadeOut()
        sink.stop()
        deviceRunning = false
    }

    /**
     * Fades the sound out through the ring and waits until the device has played the fade (#486).
     *
     * Only on a running device that says it [AudioSink.cutsSoundOnStop]. The wait is twofold, both
     * halves bounded: until the ring reports silence, which the device's next pull or two brings,
     * and then until the last faded frame is due at the speaker by the deadline the device dated it
     * with, which covers whatever the device buffers beyond the ring. A device that stops pulling
     * leaves the first wait at its bound, and the stop then cuts as it did before.
     *
     * @return true when the device is now playing silence, so stopping it cannot click.
     */
    private suspend fun fadeOut(): Boolean {
        if (!deviceRunning || !sink.cutsSoundOnStop) return false
        if (synchronized(lock) { ring?.also { it.hold(true) } } == null) return false
        var polls = 0
        while (synchronized(lock) { ring?.silent != true }) {
            if (polls++ >= FADE_SILENCE_POLLS) return false
            delay(FADE_POLL)
        }
        val due = synchronized(lock) { ring?.anchor()?.audibleAtNanos } ?: return true
        val ahead = (due - clock.nanos()).coerceAtMost(FADE_SPEAKER_WAIT.inWholeNanoseconds)
        if (ahead > 0) delay(ahead.nanoseconds)
        return true
    }

    /**
     * Freezes the paused clock at the end of the fade rather than at the moment the device stopped.
     * Between the two the device played the silence after the fade, which is not media heard, and
     * the clock, running on from the fade's last anchor, counted it. Anchoring the frozen clock
     * sets it to that anchor, which is the frame after the last one faded. The floor still rules:
     * when the device published nothing since [play], the clock keeps the position the resume gave
     * it.
     */
    private fun settleOnFadeLocked() {
        anchorLocked()
    }

    /**
     * Discards everything unplayed and invalidates the clock. This is the seek path.
     *
     * Belongs to the session owner, and the feeder must be quiescent before it is called: clearing
     * the ring writes a counter the device callback owns, and drops the timestamp segments that
     * callback dates the clock from.
     *
     * Order matters: the device is stopped before the ring is cleared, because a device still pulling
     * from a ring being cleared would play a mixture of the old position and the new one.
     */
    public suspend fun flush(newGeneration: Generation) {
        // Fades out first on a device that is still playing, so the seek does not cut the wave; the
        // sound of the new position then fades in from the silence the fade left (#486).
        stopDevice()
        // Under the lock: the C ring's flush clears the anchor and both
        // caches, and [position] and [anchorClock] read them under this same lock, so without it
        // nothing excluded a progress report from interleaving with the clearing. The C contract
        // now names the anchor reader in its quiescence sentence; this lock is how this class
        // honours it.
        synchronized(lock) {
            ring?.flush()
            playoutClock.invalidate()
            timeline.clear()
            // An empty ring has nothing on either side of a join, and the samples that follow
            // carry the current item's own timestamps.
            join = Join.None
        }
        // The conversion stages hold audio of the position that was abandoned. After a seek,
        // carrying it into the new position would mix the two.
        pipeline?.reset()
        restartEpoch()
        generation = newGeneration
    }

    /**
     * Pushes the last of the decoded audio out of the DSP stages and into the ring.
     *
     * The tempo stage holds up to about 60 ms it cannot place without the audio that comes after
     * it, and at the end of a stream nothing comes after it. Until this call existed that audio was
     * discarded by the next reset, so every clip played at a non-1x speed lost its final fragment
     * and short clips lost an audible share of themselves.
     *
     * Call it once, after the decoder is drained and every decoded buffer has been submitted, and
     * before [drain]. Belongs to the feeder, like [submitDecoded]: it runs the same pipeline and
     * the same timestamp arithmetic, and nothing else may touch either.
     *
     * @param abort polled while the ring is full, exactly as [submitDecoded] polls it.
     * @return sample frames handed to the ring, zero when the stages were already empty.
     * @throws PlaybackException carrying [PlaybackError.DecoderFailed] when the rate conversion's
     *         tail would need more than 4 Mi float values.
     */
    public suspend fun finishDecoded(abort: () -> Boolean = { false }): Int = finishDecoded(abort) { delay(it) }

    /** [finishDecoded] with the wait on a full ring supplied by the caller, as [submitDecoded] takes it. */
    internal suspend fun finishDecoded(abort: () -> Boolean, idle: suspend (Duration) -> Unit): Int {
        val stage = pipeline ?: return 0
        if (format == null) return 0
        val produced = stage.finish()
        writeDated(stage, produced, abort, idle)
        return produced.coerceAtLeast(0)
    }

    /**
     * Tells the audio path that no more audio is coming.
     *
     * Call this as soon as the decoder finishes, not when the buffer empties. Between those two
     * moments the ring runs dry and the device is handed silence, and that silence is the end of the
     * media rather than a failure to keep up. Marking it late means every file finishes by reporting
     * a handful of underruns, which makes the counter useless for spotting the real thing.
     */
    public fun endOfStream() {
        ring?.markEnding()
    }

    /**
     * Plays out what is already submitted, then stops. This is the end-of-media path.
     *
     * Belongs to the session owner.
     */
    public suspend fun drain() {
        val ring = ring ?: return
        ring.markEnding()
        // Wait for the ring itself to empty first, then let the device finish its own buffer. Each
        // wait is the time the rest takes to play, so the end is noticed on time with few wake-ups.
        while (ring.bufferedFrames > 0) {
            delay(ring.bufferedUs.microseconds.coerceIn(FULL_RING_WAIT_MIN, fullRingWait))
        }
        sink.drain()
        deviceRunning = false
    }

    /**
     * Playback volume, from silence at 0 through unity at 1 to amplification at 2.
     *
     * The pipeline's own bound is the ring's, because that is where the gain is applied and where
     * a boost is folded through the saturator. The POLICY bound is the player's
     * [AudioConfig.volumeCeiling], which is 1 unless a consumer raised it: this class is reached
     * through the engine, and the engine refuses a boost the configuration did not allow.
     *
     * Real, and applied by the pipeline's gain stage as one multiply per sample with a short ramp, so a
     * change never clicks. It takes effect on the next buffer the feeder converts, which is why setting
     * it is safe from any thread: this stores a value and the feeder reads it, rather than writing into
     * a stage that has one owner.
     *
     * With no audio path open the value is stored and applied when one opens.
     */
    public var volume: Float
        get() = wantedVolume.value
        set(value) {
            require(value.isFinite() && value >= 0f && value <= GAIN_MAX) {
                "volume must be between 0 and $GAIN_MAX, was $value"
            }
            wantedVolume.value = value
            pushGain()
        }

    /**
     * The ReplayGain to apply to the material, as a linear multiplier. 1 applies nothing.
     *
     * Set by the engine when a track opens, from the container's tags and the configured mode; see
     * `ReplayGainMode`. The engine's values are already clamped by the file's own peak, so they
     * cannot clip.
     * Applied on the way into the ring, which is right for a per-track constant and wrong for a
     * live control: the volume is the live one and lives on the other side.
     */
    public var replayGain: Float
        get() = wantedReplayGain.value
        set(value) {
            require(value.isFinite() && value > 0f) {
                "a replay gain must be finite and positive, was $value"
            }
            wantedReplayGain.value = value
        }

    /** The ten-band equaliser. Flat by default, and free while it is. */
    public var equalizer: EqualizerSettings
        get() = wantedEqualizer.value
        set(value) {
            wantedEqualizer.value = value
        }

    /**
     * Stereo balance: -1 is hard left, 0 is centre, 1 is hard right.
     *
     * An ATTENUATION of the channel being turned away from, never a boost of the other one. A
     * balance that amplified could push material already at full scale past it, and there is no
     * limiter on this side of the ring to catch that.
     *
     * Only the first two channels move. Panning a centre or a surround channel is a different
     * feature with a different name, and doing it quietly here would surprise anyone who asked for
     * what the word means.
     *
     * A change is heard after the audio already buffered has drained, which is the ring's depth:
     * at least 200 ms, and longer on Android where the device's own buffer sets it. That is the
     * cost of applying it on the way IN, and it is the right trade here because a balance is set
     * once and left, unlike the volume, which lives on the ring's read side precisely so that it
     * is heard within one device period.
     */
    public var balance: Float
        get() = wantedBalance.value
        set(value) {
            require(value.isFinite() && value >= -1f && value <= 1f) {
                "balance must be between -1 and 1, was $value"
            }
            wantedBalance.value = value
        }

    /**
     * What the two front speakers play: see [StereoMode]. Applied as audio is written, after the
     * downmix and before the balance, so it is heard after the ring's depth, as the balance is.
     */
    public var stereoMode: StereoMode
        get() = wantedStereoMode.value
        set(value) {
            wantedStereoMode.value = value
        }

    /** The settings last written into the current pipeline, so an unchanged one is not rebuilt. */
    private var appliedEqualizer: EqualizerSettings? = null

    /**
     * Folds the replay gain and the balance into the pipeline's one per-channel stage.
     *
     * Reasserted per buffer for the reason the rate is: a pipeline rebuilt for a format change
     * starts at unity, and neither setting is carried across the rebuild by anything else.
     */
    private fun applyTrim(stage: AudioPipeline) {
        val gain = wantedReplayGain.value
        val balanceNow = wantedBalance.value
        if (balanceNow == 0f) {
            stage.trim.setAll(gain)
            return
        }
        val perChannel = FloatArray(stage.trim.channels) { gain }
        if (perChannel.size >= 2) {
            if (balanceNow > 0f) perChannel[0] = gain * (1f - balanceNow)
            if (balanceNow < 0f) perChannel[1] = gain * (1f + balanceNow)
        }
        stage.trim.set(perChannel)
    }

    /** Silence without losing the volume setting. Ramped like [volume], and safe from any thread. */
    public var muted: Boolean
        get() = wantedMute.value
        set(value) {
            wantedMute.value = value
            pushGain()
        }

    /**
     * Hands the ring the one number it walks towards: the volume, or silence when muted.
     *
     * The whole call runs under the lock [close] takes, so a gain change in flight finishes before
     * close clears the field and the sink frees a C ring, and one that arrives later finds no ring.
     * The target is computed inside too, so the last change to take the lock is the one the ring
     * keeps. With no path open the value is simply stored, and [open] pushes it into the fresh
     * ring.
     */
    private fun pushGain(): Unit = synchronized(lock) {
        val target = if (wantedMute.value) 0f else wantedVolume.value * fadeLevel.value * duckLevel.value
        ring?.setGain(target)
    }

    /**
     * A multiplier the ENGINE applies on top of the user's volume, from 1 down to 0.
     *
     * The sleep timer's fade uses it. Separate from [volume] on purpose: a fade that drove the
     * public volume down would leave the user at zero the next time they pressed play, and would
     * make a UI bound to the volume slide to the bottom while they watched.
     */
    private val fadeLevel = atomic(1f)

    /** Sets the engine's own fade multiplier. 1 is no fade. Rides the ring's ramp, so it never clicks. */
    internal fun setFadeLevel(level: Float) {
        require(level.isFinite() && level in 0f..1f) { "a fade level must be between 0 and 1, was $level" }
        fadeLevel.value = level
        pushGain()
    }

    /**
     * A second multiplier on top of the user's volume, for a duck under a notification. Separate
     * from the fade so the two never overwrite each other, and from [volume] for the reason the
     * fade is: a duck that wrote the volume raised quiet playback and lost the listener's own
     * setting (#280).
     */
    private val duckLevel = atomic(1f)

    /** Sets the duck multiplier. 1 is no duck. Rides the ring's ramp, so it never clicks. */
    internal fun setDuckLevel(level: Float) {
        require(level.isFinite() && level in 0f..1f) { "a duck level must be between 0 and 1, was $level" }
        duckLevel.value = level
        pushGain()
    }

    /**
     * The playback rate as a multiplier of real time, within [TempoStage.MIN_SPEED] to
     * [TempoStage.MAX_SPEED]. Real: the tempo stage in the pipeline makes the sound take
     * `1/speed` as long, at its own pitch unless [preservePitch] is false, and the clock runs to
     * match.
     *
     * A change applies to the next buffer the feeder converts, with no seek, no flush and no stop.
     * The audio already in the ring plays out at the rate it was made at, so the new rate is heard
     * after the ring's depth: about 200 ms, longer on Android where the device buffer sets it. The
     * clock follows the rate the listener hears, frame by frame, so video stays in step through the
     * change.
     *
     * Safe from any thread. Reading it reports the wanted rate.
     *
     * @throws IllegalArgumentException outside the supported range: below and above it, splice
     *         artifacts dominate the signal and pretending otherwise would be a lie.
     */
    public var speed: Double
        get() = synchronized(lock) { wantedSpeed }
        set(value) {
            require(
                value.isFinite() && value >= TempoStage.MIN_SPEED && value <= TempoStage.MAX_SPEED,
            ) {
                "speed must be within ${TempoStage.MIN_SPEED}..${TempoStage.MAX_SPEED}, was $value"
            }
            synchronized(lock) { wantedSpeed = value }
        }

    /**
     * Whether [speed] keeps pitch. True stretches the sound in time; false plays it faster or
     * slower like a turntable, so pitch moves with the rate, mpv's `audio-pitch-correction=no`.
     * A change applies to the next buffer with no seam, the same way a [speed] change does.
     */
    public var preservePitch: Boolean
        get() = synchronized(lock) { wantedPreservePitch }
        set(value) {
            synchronized(lock) { wantedPreservePitch = value }
        }

    /**
     * Quiescence precondition, stated in the same words [flush]'s is: the
     * feeder must not be between a [submit] call's start and its return when this runs. Confinement
     * alone does not give that, because [submit] runs on the feed worker rather than the session
     * owner; what gives it is the engine joining the feeder's job before teardown reaches this
     * call. A submit that races a close anyway reads the cleared field under the lock and fails
     * loudly instead of touching freed memory.
     */
    override fun close() {
        if (closed) return
        closed = true
        // The reference goes first and the device second, and the order matters now that a ring can
        // belong to the sink: after `sink.close()` a C ring has been freed, so a field still pointing
        // at it is a dangling pointer waiting for a reader. Dropping it first also costs the device
        // nothing, because a callback that finds no ring writes silence, which is what closing means.
        //
        // UNDER THE LOCK, and this is not tidiness. [position], [anchorClock], [buffered],
        // [underruns] and every gain change are documented safe from any thread and all of them use
        // this field. While the ring was always a managed object, a reader that had already loaded
        // the reference was merely reading a ring nobody would use again. Now it can be a pointer
        // that `sink.close()` frees, and clearing the field first narrows that window without
        // closing it: a reader already inside `anchor()` is still there. Proved rather than argued,
        // with AddressSanitizer over the two C calls in that order:
        // `heap-use-after-free ... READ of size 8 ... in kprt_ring_anchor ... freed by ...
        // kprt_sink_destroy`. Taking the lock here is what orders the two, because every
        // cross-thread reader takes it: a reader in flight finishes before the field is cleared,
        // and one that arrives afterwards sees null. The lock is released before `sink.close()`,
        // which is correct and necessary: the sink's own teardown fences the device callback out,
        // and holding a lock across it would put the session owner behind the audio device.
        synchronized(lock) { ring = null }
        // The feeder is joined before the owner closes, so no conversion is running.
        pipeline?.close()
        pipeline = null
        sink.close()
    }

    private companion object {
        /**
         * Ring capacity as a multiple of the device buffer, when that is the larger figure.
         *
         * Two, not more. Android reports its whole AudioTrack buffer here, about 120 ms on a
         * current phone, and eight of those made a 960 ms ring: a speed, balance or equaliser
         * change was heard more than a second late. The device holds its own buffer on top.
         */
        const val DEVICE_BUFFER_MULTIPLE = 2

        /** The shortest wait on a full ring, for a ring so small that a quarter of it is less. */
        val FULL_RING_WAIT_MIN: Duration = 2.milliseconds

        /** The longest wait on a full ring, so a deep ring still refills in small steps. */
        val FULL_RING_WAIT_MAX: Duration = 50.milliseconds

        /** Timeline lines kept behind the newest anchor, well past any device's latency. */
        const val TIMELINE_KEEP_US = 2_000_000L

        /** How often a fade asks the ring whether it has reached silence. */
        val FADE_POLL: Duration = 1.milliseconds

        /**
         * How many times a fade asks before it gives up on a device that stopped pulling: about
         * 200 ms, where a pulling device answers within one or two of its periods.
         */
        const val FADE_SILENCE_POLLS = 200

        /**
         * The longest a fade waits for its last frame to reach the speaker. The deepest device
         * buffer here is the web's queue of 4096 frames, about 85 ms at 48 kHz, before whatever
         * output latency the browser adds.
         */
        val FADE_SPEAKER_WAIT: Duration = 250.milliseconds
    }
}
