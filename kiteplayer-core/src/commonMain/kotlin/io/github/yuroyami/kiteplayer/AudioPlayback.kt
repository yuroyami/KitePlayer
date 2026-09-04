package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioPipeline
import io.github.yuroyami.kiteplayer.internal.GAIN_MAX
import io.github.yuroyami.kiteplayer.internal.AudioRingHandle
import io.github.yuroyami.kiteplayer.internal.MediaClock
import io.github.yuroyami.kiteplayer.internal.TempoStage
import io.github.yuroyami.kiteplayer.internal.framesToMicros
import io.github.yuroyami.kiteplayer.internal.openAudioPath
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSink
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

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
 * [submit] and [submitDecoded] belong to one coroutine, the audio feeder, because it is the ring's
 * single producer, and the conversion stage behind [submitDecoded] belongs to the same coroutine. The
 * device's real-time callback is the single consumer and touches nothing else in this class, so
 * neither side takes a lock.
 *
 * [anchorClock], [position], [buffered], [underruns] and the [speed] setter are guarded by one
 * internal lock. The first two write the media clock, which has one writer by design, and a player
 * reports progress from a thread that is not the one driving playback: two callers re-anchoring the
 * same clock at once is what the lock is for. [buffered] and [underruns] take it for a second reason
 * that arrived with B1.8: they read the ring, and the ring can now be memory [close] frees. Reading
 * [speed] is a plain read of one value and needs nothing.
 *
 * [open], [play], [pause], [flush], [drain], [endOfStream] and [close] are thread confined to the
 * session owner instead. [submit] and [submitDecoded] run on the feed worker, and each reads the
 * ring FIELD under the lock (interlude, I-02), so the rule is one sentence again: any member that
 * may run beside another thread touches that field only under the lock. A lock cannot be held across a suspension point, so the suspending ones
 * could not be guarded even in principle, and their contract is confinement. In A5 the core's session
 * actor becomes that owner. The seek path already depends on this: the ring's own flush requires both
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

    private val mediaClock = MediaClock(clock)

    /**
     * The conversion from what the decoder produces to what the device took. Built on the first
     * [submitDecoded] and rebuilt whenever the decoder's format changes, so a caller that converts on
     * its own and uses [submit] never has one.
     */
    private var pipeline: AudioPipeline? = null

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

    /** The equaliser the feeder applies. Held here so a pipeline rebuild cannot lose it. */
    private val wantedEqualizer = atomic(EqualizerSettings.Flat)

    private var generation: Generation = Generation.Initial
    private var format: AudioFormat? = null
    private var warnedAboutLatency = false
    private var closed = false

    /**
     * The rate the NEXT epoch will run at. An epoch is the stretch between two flushes, and speed
     * changes ride the flush: the engine spells a live change as a precise seek to the current
     * position, so one epoch never mixes two rates. Written under the lock by [speed], read at
     * [open] and [flush] where the epoch turns over.
     */
    private var wantedSpeed: Double = 1.0

    /**
     * Whether the NEXT epoch keeps pitch at speeds other than 1.0. Rides the flush exactly as
     * [wantedSpeed] does and for the same reason: the mechanism (tempo stage against folded
     * resampler) may only change where the ring is empty and no buffer spans the change.
     */
    private var wantedPreservePitch: Boolean = true

    /** The CURRENT epoch's pitch law, the one every sample in the ring was produced under. */
    private var epochPreservePitch: Boolean = true

    /**
     * The rate of the CURRENT epoch, the one every sample in the ring belongs to.
     *
     * At any rate other than 1.0 the ring is fed on a scaled time axis: each buffer's pts is
     * `media pts / epochSpeed`, dated purely by counting tempo-stage output frames. On that axis
     * one ring frame is exactly one device frame of wall time, so the ring's own interpolation
     * between segments, which assumes precisely that, stays sample-exact at every speed. The two
     * conversions back to media time are [anchorLocked] multiplying the anchor out, and the
     * media clock extrapolating at [MediaClock.speed] between anchors.
     */
    private var epochSpeed: Double = 1.0

    /**
     * Where the scaled axis is anchored: the first timestamped buffer of the epoch fixes it as
     * `media pts / epochSpeed` minus the scaled duration of whatever the tempo stage had already
     * emitted this epoch. Null until that buffer arrives, exactly like a fresh clock.
     */
    private var scaledBaseUs: Long? = null

    /** The format the device accepted. Null before [open]. */
    public val negotiatedFormat: AudioFormat? get() = format

    /**
     * How much submitted audio has not yet been handed to the device.
     *
     * Under the lock, like [position], and for the reason given on [close]: after B1.8 the ring can be
     * a pointer into C that [close] frees, so every member that may be called from another thread
     * reads the field inside the lock that [close] clears it in.
     */
    public val buffered: Duration
        get() = synchronized(lock) { (ring?.bufferedUs ?: 0L).microseconds }

    /** Callbacks handed silence because the ring had run dry. Under the lock, as [buffered] is. */
    public val underruns: Long get() = synchronized(lock) { ring?.underruns ?: 0 }

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
        val opened = openAudioPath(sink, request) { negotiated ->
            max(
                sink.deviceBufferFrames * DEVICE_BUFFER_MULTIPLE,
                negotiated.framesIn(Pts(bufferDuration.inWholeMicroseconds)),
            )
        }
        val negotiated = opened.format
        ring = opened.ring
        format = negotiated
        // The gain is the ring's now, so a path opened while muted or turned down must start there
        // rather than at unity. Without this, opening a file at volume 0 plays one ramp of
        // full-scale audio before the walk catches up, which is what `adoptRamp` used to exist for
        // on the pipeline side.
        pushGain()
        // A fresh path is a fresh epoch: the rate wanted now is the rate this ring plays at.
        synchronized(lock) {
            epochSpeed = wantedSpeed
            epochPreservePitch = wantedPreservePitch
            scaledBaseUs = null
            mediaClock.speed = epochSpeed
        }

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

    /**
     * Hands decoded audio over, suspending until all of it has been accepted.
     *
     * Suspending here is the backpressure that paces decoding to playback. The caller does not need
     * to know how full the device is.
     *
     * @param pts the media timestamp of the first frame, when the decoder gave one. Passing null is
     *        normal for buffers that continue from the previous one.
     * @param interleaved channel-interleaved float samples in the negotiated format.
     * @param frames sample frames in [interleaved], meaning one value per channel each.
     * @param abort polled while the ring is full. Returning true gives the buffer up: whatever
     *        was already accepted stays accepted, the remainder is abandoned, and the caller is
     *        expected to flush (a seek's quiescence is the intended trigger). This exists so an
     *        incremental commit is NEVER retried from the outside: cancelling this function
     *        mid-buffer and calling it again would replay samples the ring already took and run
     *        the conversion state twice.
     */
    public suspend fun submit(
        pts: Pts?,
        interleaved: FloatArray,
        frames: Int,
        abort: () -> Boolean = { false },
    ) {
        // The FIELD is read under the lock, extending the one-sentence rule to the producer
        // (interlude item I-02): a member that may run beside [close] touches `ring` only under
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
                // The ring is full, which means the device has as much as it can hold. Waiting a
                // fraction of a device period is exactly the right amount of patience.
                delay(FULL_RING_WAIT)
                continue
            }
            offset += accepted
            firstChunk = false
        }
    }

    /**
     * Converts decoded audio into the negotiated format and hands it over.
     *
     * [submit] takes samples that are already in the negotiated format. This takes them in
     * [sourceFormat], which is what the decoder said it produced, and runs the audio path's
     * conversion stage first: the channel mix, then the rate conversion, then volume. A decoder
     * feeding a device that took a different layout or a different rate has to come through here,
     * because nothing else in the player converts anything.
     *
     * The stage is keyed on [sourceFormat] against the format it was built for, so a decoder that
     * changes format mid-stream gets a new one on the buffer that changed rather than one buffer
     * later. When [sourceFormat] already is the negotiated format every stage is a copy and the cost
     * is one pass over the samples.
     *
     * @param pts the media timestamp of the first frame, as in [submit].
     * @param interleaved channel-interleaved float samples in [sourceFormat].
     * @param frames sample frames in [interleaved], meaning one value per channel each.
     */
    public suspend fun submitDecoded(
        pts: Pts?,
        interleaved: FloatArray,
        frames: Int,
        sourceFormat: AudioFormat,
        abort: () -> Boolean = { false },
    ) {
        val negotiated = format ?: error("submitDecoded was called before open")
        // The epoch's pitch law, read with the rate below: both only ever change across a flush,
        // and a pipeline built under the old law is rebuilt rather than reconfigured, the same
        // rule a format change follows.
        val pitchNow = synchronized(lock) { epochPreservePitch }
        val existing = pipeline
        val stage = when {
            existing == null ->
                AudioPipeline(sourceFormat, negotiated, onWarning, preservePitch = pitchNow, downmix = downmix)
            existing.matches(sourceFormat) && existing.preservePitch == pitchNow -> existing
            else -> existing.rebuiltFor(sourceFormat, pitchNow)
        }
        // Only a FORMAT change is worth saying out loud. A pitch-law change rebuilds the same
        // stage too, and that one the caller asked for, so it is not news.
        if (existing != null && !existing.matches(sourceFormat)) {
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
        }
        if (stage !== existing && existing != null) {
            // A fresh stage counts its emitted frames from zero, so the scaled axis must drop
            // its base and re-anchor on the next timestamped buffer. Keeping the
            // old base dated every post-rebuild buffer back at the start of the epoch.
            synchronized(lock) { scaledBaseUs = null }
        }
        pipeline = stage
        // The gain is NOT picked up here any more. It moved to the ring's read side, because a gain
        // applied on the way into the ring cannot reach audio already buffered, and a listener hears
        // the whole ring depth of the old volume before the change arrives. See AudioRingHandle.setGain.
        // The epoch's rate, reasserted per buffer for the same one-owner reason. It only ever
        // differs across a flush, so mid-epoch this is an assignment of the value it already has.
        val speedNow = synchronized(lock) { epochSpeed }
        stage.speed = speedNow
        // Reasserted per buffer for the same reason the rate is: a pipeline rebuilt for a format
        // change starts at unity, and the trim has to survive that without the rebuild knowing.
        // Idempotent and a handful of floats, so the common case costs a compare.
        applyTrim(stage)
        // Reasserted per buffer like the trim and for the same reason: a pipeline rebuilt for a
        // format change starts flat, and a flat stage is skipped, so the cost when nothing is set
        // is one reference compare.
        val wantedEq = wantedEqualizer.value
        if (appliedEqualizer !== wantedEq) {
            stage.equalizer.set(wantedEq)
            appliedEqualizer = wantedEq
        }

        val emittedBefore = stage.tempoEmittedFrames
        // Converted exactly once: the mixer, resampler and gain ramp all carry state, so running
        // process twice over the same input is audible, not just wasteful.
        val produced = stage.process(interleaved, frames)

        if (speedNow == 1.0) {
            // The exact pre-speed path: media pts straight through, byte for byte.
            if (produced > 0) submit(pts, stage.output, produced, abort)
            return
        }

        // The scaled axis. Anchor it on the first timestamped buffer of the epoch, then date
        // every chunk by the tempo stage's own output count: continuity on this axis is exact by
        // construction, so the ring never opens a spurious segment and never interpolates wrong.
        val rate = negotiated.sampleRate
        if (scaledBaseUs == null && pts != null) {
            synchronized(lock) {
                scaledBaseUs = (pts.micros / speedNow).toLong() - framesToMicros(emittedBefore, rate)
            }
        }
        if (produced > 0) {
            val base = scaledBaseUs
            val scaledPts = base?.let { Pts(it + framesToMicros(emittedBefore, rate)) }
            submit(scaledPts, stage.output, produced, abort)
        }
    }

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
        mediaClock.nowOrNull()
    }

    private fun anchorLocked() {
        val anchor = ring?.anchor() ?: return
        // The ring speaks the scaled axis at any epoch rate other than 1.0; multiplying out here
        // is the one place playout time turns back into media time.
        val mediaPts =
            if (epochSpeed == 1.0) anchor.pts
            else Pts((anchor.pts.micros * epochSpeed).toLong())
        mediaClock.setAt(mediaPts, generation, anchor.audibleAtNanos)
    }

    /** Starts the device and lets the clock run. Belongs to the session owner. */
    public suspend fun play() {
        mediaClock.resume()
        sink.setPaused(false)
        sink.start()
    }

    /** Freezes the clock and holds the device without discarding. Belongs to the session owner. */
    public suspend fun pause() {
        mediaClock.pause()
        if (!sink.setPaused(true)) sink.stop()
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
        sink.stop()
        // Under the lock since the interlude: the C ring's flush clears the anchor and both
        // caches, and [position] and [anchorClock] read them under this same lock, so without it
        // nothing excluded a progress report from interleaving with the clearing. The C contract
        // now names the anchor reader in its quiescence sentence; this lock is how this class
        // honours it.
        //
        // The epoch turns over here too: the flush is the boundary a speed change rides, so the
        // wanted rate becomes the ruling one, the scaled axis drops its base for the new epoch,
        // and the clock adopts the new extrapolation rate while it is invalid anyway.
        synchronized(lock) {
            ring?.flush()
            epochSpeed = wantedSpeed
            epochPreservePitch = wantedPreservePitch
            scaledBaseUs = null
            mediaClock.invalidate()
            mediaClock.speed = epochSpeed
        }
        // The conversion stage holds one sample frame across buffers. After a seek that frame belongs
        // to the position that was abandoned, so interpolating the new position out of it would mix
        // the two.
        pipeline?.reset()
        generation = newGeneration
    }

    /**
     * Pushes the last of the decoded audio out of the DSP stages and into the ring.
     *
     * The tempo stage holds up to two pitch periods it cannot splice without the audio that comes
     * after them, and at the end of a stream nothing comes after them. Until this call existed they
     * were discarded by the next reset, so every clip played at a non-1x speed lost its final
     * fragment and short clips lost an audible share of themselves.
     *
     * Call it once, after the decoder is drained and every decoded buffer has been submitted, and
     * before [drain]. Belongs to the feeder, like [submitDecoded]: it runs the same pipeline and
     * the same timestamp arithmetic, and nothing else may touch either.
     *
     * @param abort polled while the ring is full, exactly as [submitDecoded] polls it.
     * @return sample frames handed to the ring, zero when the stages were already empty.
     */
    public suspend fun finishDecoded(abort: () -> Boolean = { false }): Int {
        val stage = pipeline ?: return 0
        val negotiated = format ?: return 0
        val emittedBefore = stage.tempoEmittedFrames
        val produced = stage.finish()
        if (produced <= 0) return 0
        val speedNow = synchronized(lock) { epochSpeed }
        if (speedNow == 1.0) {
            // Dated by the ring's own continuity, like every 1.0 buffer: the tail follows the
            // buffer before it with no gap, so a null pts is the truthful answer rather than a
            // guess at a media timestamp the tempo stage never carried.
            submit(null, stage.output, produced, abort)
            return produced
        }
        val base = synchronized(lock) { scaledBaseUs }
        val scaledPts = base?.let { Pts(it + framesToMicros(emittedBefore, negotiated.sampleRate)) }
        submit(scaledPts, stage.output, produced, abort)
        return produced
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
        // Wait for the ring itself to empty first, then let the device finish its own buffer.
        while (ring.bufferedFrames > 0) delay(FULL_RING_WAIT)
        sink.drain()
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
     * `ReplayGainMode`. Already clamped by the file's own peak, so a value here cannot clip.
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
    /** The ten-band equaliser. Flat by default, and free while it is. */
    public var equalizer: EqualizerSettings
        get() = wantedEqualizer.value
        set(value) {
            wantedEqualizer.value = value
        }

    public var balance: Float
        get() = wantedBalance.value
        set(value) {
            require(value.isFinite() && value >= -1f && value <= 1f) {
                "balance must be between -1 and 1, was $value"
            }
            wantedBalance.value = value
        }

    /**
     * Folds the replay gain and the balance into the pipeline's one per-channel stage.
     *
     * Reasserted per buffer for the reason the rate is: a pipeline rebuilt for a format change
     * starts at unity, and neither setting is carried across the rebuild by anything else.
     */
    /** The settings last written into the current pipeline, so an unchanged one is not rebuilt. */
    private var appliedEqualizer: EqualizerSettings? = null

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
     * The ring reads the field under its own atomic, so this needs no ordering of its own. The
     * `ring` FIELD is read under the lock for the same reason every other member does it: a member
     * that may run beside [close] must not load the reference in the same instant close is clearing
     * it. With no path open the value is simply stored, and [open] pushes it into the fresh ring.
     */
    private fun pushGain() {
        val target = if (wantedMute.value) 0f else wantedVolume.value * fadeLevel.value
        synchronized(lock) { ring }?.setGain(target)
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
     * The playback rate as a multiplier of real time, within [TempoStage.MIN_SPEED] to
     * [TempoStage.MAX_SPEED]. Real: the tempo stage in the pipeline makes the sound take
     * `1/speed` as long at its own pitch, and the clock runs to match.
     *
     * The value rules from the NEXT flush onward, because a rate change and the samples already
     * queued at the old rate cannot share a ring: the engine spells a live change as this
     * assignment followed by a precise seek to the current position, which is one brief,
     * gapless-sounding rebuffer, the same trade mpv makes. A caller driving this class directly
     * follows the same recipe with [flush].
     *
     * Setting it takes the lock because [flush] and [open] read it under the same lock from other
     * threads. Reading it reports the wanted rate.
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
     * Whether [speed] keeps pitch. True runs the tempo stage; false folds the rate into the
     * resampler, which is cheaper and shifts pitch with the rate, mpv's
     * `audio-pitch-correction=no`. Rules from the NEXT flush onward, by exactly the recipe
     * [speed] documents, and none of the pts arithmetic changes: both mechanisms emit the same
     * frame count per input second, so the scaled axis cannot tell them apart.
     */
    public var preservePitch: Boolean
        get() = synchronized(lock) { wantedPreservePitch }
        set(value) {
            synchronized(lock) { wantedPreservePitch = value }
        }

    /**
     * Quiescence precondition, stated in the same words [flush]'s is (interlude item I-02): the
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
        // UNDER THE LOCK, and this is not tidiness. [position], [anchorClock], [buffered] and
        // [underruns] are documented safe from any thread and all four read this field; before B1.8
        // the ring was a managed object and a reader that had already loaded the reference was
        // merely reading a ring nobody would use again. After B1.8 it can be a pointer that
        // `sink.close()` frees, and clearing the field first narrows that window without closing
        // it: a reader already inside `anchor()` is still there. Proved rather than argued, with
        // AddressSanitizer over the two C calls in that order:
        // `heap-use-after-free ... READ of size 8 ... in kprt_ring_anchor ... freed by ...
        // kprt_sink_destroy`. Taking the lock here is what orders the two, because every
        // cross-thread reader takes it: a reader in flight finishes before the field is cleared,
        // and one that arrives afterwards sees null. The lock is released before `sink.close()`,
        // which is correct and necessary: the sink's own teardown fences the device callback out,
        // and holding a lock across it would put the session owner behind the audio device.
        synchronized(lock) { ring = null }
        pipeline = null
        sink.close()
    }

    private companion object {
        /** Ring capacity as a multiple of the device buffer, when that is the larger figure. */
        const val DEVICE_BUFFER_MULTIPLE = 8

        val FULL_RING_WAIT: Duration = 2.milliseconds
    }
}
