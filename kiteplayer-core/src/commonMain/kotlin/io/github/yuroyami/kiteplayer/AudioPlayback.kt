package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioPipeline
import io.github.yuroyami.kiteplayer.internal.AudioRingHandle
import io.github.yuroyami.kiteplayer.internal.MediaClock
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
) : AutoCloseable {

    /**
     * The ring, behind the interface, because there are two implementations of it and there always
     * will be: see [AudioRingHandle] and register item B1-20.
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

    private var generation: Generation = Generation.Initial
    private var format: AudioFormat? = null
    private var warnedAboutLatency = false
    private var closed = false

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
     */
    public suspend fun submit(pts: Pts?, interleaved: FloatArray, frames: Int) {
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
    ) {
        val negotiated = format ?: error("submitDecoded was called before open")
        val existing = pipeline
        val stage = when {
            existing == null -> AudioPipeline(sourceFormat, negotiated, onWarning)
            existing.matches(sourceFormat) -> existing
            else -> existing.rebuiltFor(sourceFormat)
        }
        pipeline = stage
        // Picked up here, on the feeder, because the gain stage has exactly one owner. The ramp inside it
        // is what makes a change arriving mid-buffer inaudible rather than a click.
        stage.volume = wantedVolume.value
        stage.muted = wantedMute.value

        val produced = stage.process(interleaved, frames)
        if (produced > 0) submit(pts, stage.output, produced)
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
        mediaClock.setAt(anchor.pts, generation, anchor.audibleAtNanos)
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
        // Under the lock since the interlude (I-06): the C ring's flush clears the anchor and both
        // caches, and [position] and [anchorClock] read them under this same lock, so without it
        // nothing excluded a progress report from interleaving with the clearing. The C contract
        // now names the anchor reader in its quiescence sentence; this lock is how this class
        // honours it.
        synchronized(lock) { ring?.flush() }
        // The conversion stage holds one sample frame across buffers. After a seek that frame belongs
        // to the position that was abandoned, so interpolating the new position out of it would mix
        // the two.
        pipeline?.reset()
        mediaClock.invalidate()
        generation = newGeneration
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
     * Playback volume, from silence at 0 to unity at 1. Above unity is amplification and is refused.
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
            require(value.isFinite() && value >= 0f && value <= 1f) {
                "volume must be between 0 and 1, was $value"
            }
            wantedVolume.value = value
        }

    /** Silence without losing the volume setting. Ramped like [volume], and safe from any thread. */
    public var muted: Boolean
        get() = wantedMute.value
        set(value) {
            wantedMute.value = value
        }

    /**
     * The playback rate as a multiplier of real time. Finite and positive.
     *
     * **While audio is open the only accepted value is 1.0.** Anything else throws
     * [UnsupportedOperationException], because there is no tempo stage: the samples would still reach
     * the device at the device's rate, so the sound would play at normal speed while this clock, and
     * every frame timed against it, ran at another. A player that accepts the value and does nothing
     * with it is worse than one that refuses, because the caller cannot tell which it got.
     *
     * With no ring open the value is stored and nothing refuses it, which is what keeps the rate a
     * property of the clock rather than of the device. That is not a working speed control on its own:
     * the video scheduler still paces frames by their own durations. A real one needs the tempo stage
     * and a scaled frame timer, and both are Horizon B; see KPKMP.md section 11.
     *
     * Setting it takes the lock, because a rate change re-anchors the clock and because it asks
     * whether an audio path is open, so it is safe from any thread. Reading it is a plain read of one
     * value.
     */
    public var speed: Double
        get() = mediaClock.speed
        set(value) {
            // The null test is inside the lock with every other cross-thread read of this field, so
            // the rule is one sentence rather than a case analysis: a member that may be called from
            // another thread touches `ring` only under `lock`. This one never dereferences it, so it
            // was not part of the use-after-free the B1.8 verification found, and an exception to a
            // rule about a freed pointer is not worth the reader's time.
            val open = synchronized(lock) { ring != null }
            if (open && value != 1.0) {
                throw UnsupportedOperationException(
                    "audio playback runs at 1.0 only: there is no tempo stage, so a rate of $value " +
                        "would move the clock without moving the sound",
                )
            }
            synchronized(lock) { mediaClock.speed = value }
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
