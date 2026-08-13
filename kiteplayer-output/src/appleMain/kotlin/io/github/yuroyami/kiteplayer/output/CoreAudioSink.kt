// Two opaque C handles and the calls that make them. Opted in at file scope rather than through the
// build file, so the boundary between managed Kotlin and the real-time core is visible in the file
// that crosses it instead of being a compiler flag nobody reads.
@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.output

import cnames.structs.kprt_ring
import cnames.structs.kprt_sink
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_SINK_OK
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_attach_ring
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_create
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_destroy
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_format
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_read_stats
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_ring
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_set_paused
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_start
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_stats
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_stop
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.NativeRingAudioSink
import io.github.yuroyami.kiteplayer.spi.NativeRingHandoff
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test seam around C's void teardown call. Production implementations must not throw and return only
 * after the output unit is stopped, uninitialised and disposed; an injected throw is nevertheless
 * treated as uncertain teardown and therefore never followed by session deactivation.
 */
internal fun interface CoreAudioSinkDestroyer {
    fun destroy(sink: CPointer<kprt_sink>)
}

private object PlatformCoreAudioSinkDestroyer : CoreAudioSinkDestroyer {
    override fun destroy(sink: CPointer<kprt_sink>) {
        // KPRT_SINK_TEARDOWN_UNPROVEN means C could not prove the render callback is out and
        // deliberately leaked the sink and ring instead of freeing them under it. Throwing here
        // routes into the existing uncertain-teardown handling: the session lease is NOT
        // deactivated, matching the fail-closed contract documented on CoreAudioSinkDestroyer.
        val rc = kprt_sink_destroy(sink)
        check(rc == KPRT_SINK_OK.toInt()) {
            "CoreAudio sink teardown unproven (rc=$rc); sink and ring leaked deliberately"
        }
    }
}

/**
 * Audio output through CoreAudio, owned in C.
 *
 * The device pulls. Its render callback runs on a real-time thread CoreAudio owns, and that thread must
 * never be made to wait: it cannot allocate, cannot take a contended lock and cannot suspend. Since
 * B1.8 it also cannot enter Kotlin, and that is what this file is about.
 *
 * ### What changed, and why it had to
 *
 * Register item B1-17. This class used to install a Kotlin lambda as the render callback, whose first
 * instruction was `refCon.asStableRef<CoreAudioSink>().get()`. That made the device's real-time thread
 * a Kotlin mutator the garbage collector has to stop at a safepoint. Thirteen long-lived objects,
 * fourteen atomic wrappers, two virtual interface calls, a scalar copy loop and up to five transient
 * cinterop views were on that path, and worst measured stop-the-world pauses on the development machine
 * were 63 to 256 microseconds against a 10.67 millisecond period at 512 frames and 48 kHz. The honest
 * statement was never that audio glitched; it was that the deadline depended on a pause nobody had
 * bounded.
 *
 * So the callback, the AudioUnit and the sample ring all moved into `kiteplayer-rt`, and what is left
 * here owns two opaque handles and forwards six lifecycle calls. Nothing in this file touches an
 * `AudioUnit`, nothing here runs on the device's thread, and there is no `StableRef` anywhere in it.
 * `kiteplayer-rt/native/scripts/render-audit.sh` proves the first claim from the shipped object's own
 * symbol table rather than from this paragraph.
 *
 * ### The timestamp that makes synchronisation work
 *
 * Unchanged in substance, and now computed in C. CoreAudio hands the callback a timestamp whose host
 * time is when the buffer being filled reaches the device. Converted through a `mach_timebase_info`
 * cached when the sink was created, and offset by the buffer's own length, that is the instant its last
 * frame becomes audible, which is exactly what the audio clock anchors to. The timestamp says which of
 * its fields mean anything, so the host time is used only when the device flags it valid; otherwise the
 * anchor is estimated from the clock and the estimate is counted, which is what [estimatedAnchors]
 * reports.
 *
 * Nothing here estimates a device latency, because nothing needs to. ffplay assumes every device holds
 * exactly two buffer periods, and that assumption is the largest single source of fixed A/V offset in
 * it.
 *
 * ### Silence, which is now owned entirely in C
 *
 * Register item B1-19. This class used to fill the tail of a short read with silence and count an
 * underrun, and the ring did the same thing one level down; the old comment called that duplication
 * deliberate, on the grounds that the render callback could be absent. There is no absent callback now:
 * the callback is a C function installed for the life of the sink. Normal starvation and end-of-stream
 * silence, plus the underrun counter, live in `kprt_ring_render`. The callback itself zeroes only cases
 * the ring renderer cannot address safely: a missing ring during teardown or a malformed device buffer
 * list. Kotlin writes no silence on the device path.
 *
 * ### [open] is not the entry point
 *
 * This sink implements [NativeRingAudioSink], so it is opened through [openWithRing] and it refuses
 * [open]. That refusal is deliberate and is not a rough edge: a device whose C callback ignored the
 * Kotlin lambda it was handed would play correctly while the caller believed its callback was being
 * called, and silent disagreement is worse than a loud failure. The engine never calls [open] on such a
 * sink; `openAudioPath` in `kiteplayer-core` is what makes the choice.
 *
 * ### Opening is transactional
 *
 * Defect D23's rule, moved into C with the device. Either `kprt_sink_create` returns a sink that owns an
 * initialised audio unit, or it returns a verdict having disposed everything it created. A failed open
 * therefore leaves this object owning nothing, which is what [retainedResources] reports and what
 * appleTest asserts.
 *
 * ### Threading
 *
 * [openWithRing], [start], [stop], [drain] and [setPaused] belong to the session owner, which is the
 * same confinement `AudioPlayback` documents for the calls it makes into a sink. [close] belongs to the
 * owner as well but additionally takes an internal lock, and [latencyNanos] and the diagnostic counters
 * take it too, because those are the members another thread may call: after B1.8 the two fields they
 * read are C pointers that [close] frees, and the lock is what orders the free after the read. See the
 * note on the lock itself for the AddressSanitizer report that made this necessary rather than tidy.
 */
public class CoreAudioSink private constructor(
    private val policy: AppleAudioSessionPolicy,
    private val clock: MonotonicClock,
    private val leaseManager: AppleAudioSessionLeaseManager,
    private val destroyer: CoreAudioSinkDestroyer,
) : AudioSink, NativeRingAudioSink {

    /** Preserves the original clock-first API and uses KitePlayer's managed playback policy. */
    public constructor(clock: MonotonicClock = AppleHostClock) : this(
        AppleAudioSessionPolicy.ManagedPlayback,
        clock,
        sharedAppleAudioSessionLeaseManager,
        PlatformCoreAudioSinkDestroyer,
    )

    /** Selects who owns the process-wide iOS audio session while retaining the Apple host clock. */
    public constructor(
        policy: AppleAudioSessionPolicy,
        clock: MonotonicClock = AppleHostClock,
    ) : this(policy, clock, sharedAppleAudioSessionLeaseManager, PlatformCoreAudioSinkDestroyer)

    /** Test seam for proving session ownership around every C lifecycle exit. */
    internal constructor(
        policy: AppleAudioSessionPolicy,
        leaseManager: AppleAudioSessionLeaseManager,
        clock: MonotonicClock = AppleHostClock,
        destroyer: CoreAudioSinkDestroyer = PlatformCoreAudioSinkDestroyer,
    ) : this(policy, clock, leaseManager, destroyer)

    init {
        require(clock === AppleHostClock) {
            "CoreAudioSink must be given AppleHostClock, and was given $clock. The instant this sink " +
                "publishes is a CoreAudio host time converted by that object, so an engine measuring " +
                "time from any other base would sit at a constant offset from the device that no " +
                "correction could find, because both sides would believe they were right. A clock on " +
                "another base needs a sink that translates between the two bases, which is why the " +
                "parameter exists and why it is checked instead of ignored."
        }
    }

    /**
     * Orders [close] against the members that read the two handles, and nothing else.
     *
     * The device's callback never takes it and never could: it is a C function in `kiteplayer-rt`
     * that does not enter this module. What it guards is the seam the independent verification of
     * B1.8 found: [close] calls `kprt_sink_destroy`, which frees the C sink and the C ring, while
     * [latencyNanos] and the diagnostic counters read them. Proved with AddressSanitizer over the
     * equivalent pair of C calls: `heap-use-after-free ... in kprt_ring_anchor ... freed by ...
     * kprt_sink_destroy`. Before B1.8 those reads went to managed Kotlin objects and the same
     * interleaving was harmless.
     *
     * The lock costs one wrapper object at construction and is taken only on the lifecycle and
     * diagnostic calls, which happen a handful of times per session.
     */
    private val lock = SynchronizedObject()

    /** The C sink: the audio unit, the installed callback and the counters it writes. */
    private var handle: CPointer<kprt_sink>? = null

    /** The ring that callback reads. Created by C, owned by C, released by [close]. */
    private var ring: CPointer<kprt_ring>? = null

    private var negotiated: AudioFormat? = null

    /** Acquired before C creates the device and released only after C destroys it. */
    private var sessionLease: AppleAudioSessionLease? = null

    private val eventFlow = MutableSharedFlow<AudioSinkEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<AudioSinkEvent> = eventFlow.asSharedFlow()

    /**
     * The device's period, as C reports it.
     *
     * Before the sink is open there is no device to ask, and the engine needs a number to size its ring
     * with, so the default matches what `kiteplayer-rt` uses: what CoreAudio typically asks for at
     * 48 kHz. Being wrong here is harmless, because the ring is sized at the larger of a multiple of
     * this and the engine's own buffer duration.
     */
    override val deviceBufferFrames: Int get() = deviceBufferFramesOrDefault

    private var deviceBufferFramesOrDefault: Int = DEFAULT_DEVICE_BUFFER_FRAMES

    /**
     * `Estimated`, not `Exact`, and the distinction is deliberate.
     *
     * The host time CoreAudio reports covers the audio unit's own buffer accurately. The full signal
     * path can add more: the device's safety offset, and on a Bluetooth or AirPlay route a large and
     * variable transport delay. Summing those means reading several device properties and following
     * route changes. Until this sink does that, `Estimated` is the honest answer, and the engine widens
     * its tolerances rather than trusting a figure that may be short by tens of milliseconds.
     */
    override val latencyQuality: LatencyQuality = LatencyQuality.Estimated

    /**
     * Refused, and the message says what to call instead. See the class note.
     *
     * @throws UnsupportedOperationException always.
     */
    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat =
        throw UnsupportedOperationException(
            "CoreAudioSink renders in C and cannot call a Kotlin AudioRenderCallback: its device " +
                "callback is a static C function that never enters managed code, which is the whole " +
                "point of it (register item B1-17). Open it through openWithRing, which is what " +
                "AudioPlayback does for any sink implementing NativeRingAudioSink. A sink that does " +
                "want a Kotlin callback is a different sink.",
        )

    override suspend fun openWithRing(
        request: AudioFormat,
        capacityFrames: (AudioFormat) -> Int,
    ): NativeRingHandoff {
        check(handle == null && sessionLease == null) { "this sink is already open" }

        // On iOS the process audio session must be active before RemoteIO is created. Acquiring is the
        // first step in the same transaction as the C device and ring. A later failure hands the lease
        // back only after C destruction is confirmed; uncertain destruction retains it fail-closed.
        val acquiredLease = leaseManager.acquire(policy)
        var ownedSink: CPointer<kprt_sink>? = null

        try {
            // Step one: the device. C negotiates, applies the format, installs the callback and
            // initialises the unit, or disposes everything it made and reports which step refused.
            val created = memScoped {
                val out = allocPointerTo<kprt_sink>()
                val accepted = alloc<kprt_sink_format>()
                val status = alloc<IntVar>()
                val verdict = kprt_sink_create(
                    request.sampleRate,
                    request.channels,
                    out.ptr,
                    accepted.ptr,
                    status.ptr,
                )
                if (verdict != KPRT_SINK_OK.toInt()) {
                    error(
                        "opening the audio device failed at ${verdictName(verdict)}" +
                            if (status.value != 0) " with CoreAudio status ${status.value}" else "",
                    )
                }
                val sink = out.value ?: error("kprt_sink_create reported success and produced no sink")
                ownedSink = sink
                Opened(
                    sink = sink,
                    format = AudioFormat(
                        sampleRate = accepted.sample_rate,
                        channels = accepted.channels,
                        // Both Apple output units take interleaved 32 bit float, so the engine's own
                        // internal format passes through unchanged and neither side converts anything.
                        sampleFormat = SampleFormat.F32,
                        channelLayout = ChannelLayout.forChannelCount(accepted.channels),
                    ),
                    deviceBufferFrames = accepted.device_buffer_frames,
                )
            }

            // Step two: the ring, at the format the device accepted and the capacity the engine asked
            // for. A failure here disposes the device too, because a sink with no ring would play
            // silence for the rest of its life and report nothing.
            //
            // The device's period is published BEFORE the capacity is computed, because the engine's
            // capacity function reads it: `AudioPlayback` sizes the ring at the larger of a multiple of
            // this and its own buffer duration.
            deviceBufferFramesOrDefault = created.deviceBufferFrames
            val capacity = capacityFrames(created.format)
            val attached = kprt_sink_attach_ring(created.sink, capacity)
            if (attached != KPRT_SINK_OK.toInt()) {
                error(
                    "the audio device opened but its ring did not: ${verdictName(attached)} for " +
                        "$capacity frames of ${created.format.channels} channels at " +
                        "${created.format.sampleRate} Hz",
                )
            }
            val attachedRing = kprt_sink_ring(created.sink)
                ?: error("kprt_sink_attach_ring reported success and produced no ring")
            val handoff = NativeRingHandoff(format = created.format, ring = attachedRing)

            // Published inside the lock, so a diagnostic read from another thread sees either nothing
            // or the complete device/ring/session transaction. Opening itself belongs to the session
            // owner; the lock is here for the readers.
            synchronized(lock) {
                handle = created.sink
                ring = attachedRing
                negotiated = created.format
                sessionLease = acquiredLease
            }
            ownedSink = null
            return handoff
        } catch (failure: Throwable) {
            var destroyCompleted = ownedSink == null
            try {
                ownedSink?.let { sink ->
                    destroyer.destroy(sink)
                    destroyCompleted = true
                }
            } catch (destroyFailure: Throwable) {
                failure.addSuppressed(destroyFailure)
            }
            deviceBufferFramesOrDefault = DEFAULT_DEVICE_BUFFER_FRAMES
            if (destroyCompleted) {
                try {
                    acquiredLease.close()
                } catch (releaseFailure: Throwable) {
                    failure.addSuppressed(releaseFailure)
                }
            }
            throw failure
        }
    }

    private class Opened(
        val sink: CPointer<kprt_sink>,
        val format: AudioFormat,
        val deviceBufferFrames: Int,
    )

    override suspend fun start() {
        val sink = handle ?: error("start was called before open")
        callAndCheck("starting the audio device") { status -> kprt_sink_start(sink, status) }
    }

    override suspend fun stop() {
        val sink = handle ?: return
        callAndCheck("stopping the audio device") { status -> kprt_sink_stop(sink, status) }
    }

    /**
     * CoreAudio has no drain of its own. Once the callback stops supplying samples the device plays what
     * it already holds and then silence, so waiting out the buffer already handed over is exactly the
     * drain the engine needs before declaring the end of the media.
     */
    override suspend fun drain() {
        val remaining = lastDeadlineNanos() - clock.nanos()
        if (remaining > 0) delay(remaining / 1_000_000 + 1)
        stop()
    }

    /** Stopping the unit keeps the device open, so nothing buffered is lost and resuming is quick. */
    override suspend fun setPaused(paused: Boolean): Boolean {
        val sink = handle ?: return false
        callAndCheck(if (paused) "pausing the audio device" else "resuming the audio device") { status ->
            kprt_sink_set_paused(sink, if (paused) 1 else 0, status)
        }
        return true
    }

    override fun latencyNanos(): Long {
        val deadline = lastDeadlineNanos()
        if (deadline == 0L) return 0
        return (deadline - clock.nanos()).coerceAtLeast(0)
    }

    /**
     * Stops, uninitialises, disposes, and only then releases the ring, in C and in that order. On iOS,
     * the audio-session lease is released after that complete C teardown, never before RemoteIO stops.
     *
     * The order is the reason the ring belongs to the sink rather than to the engine: after the dispose
     * no callback can be running, so freeing the ring cannot race a render. Idempotent.
     *
     * The handles are taken and cleared inside [lock], and the destroy runs outside it. That ordering
     * is what makes a concurrent [latencyNanos] or counter read safe: a reader in flight finishes
     * before the fields are cleared, and one arriving afterwards finds null and reads zeroes. The
     * destroy is outside the lock on purpose, because it blocks until the device's callback is fenced
     * out and nothing else should wait behind the audio device.
     */
    override fun close() {
        val owned = synchronized(lock) {
            if (handle == null && sessionLease == null) return
            val currentSink = handle
            val currentLease = sessionLease
            handle = null
            ring = null
            negotiated = null
            sessionLease = null
            OwnedLifecycle(currentSink, currentLease)
        }
        owned.sink?.let { sink -> destroyer.destroy(sink) }
        owned.lease?.close()
    }

    private class OwnedLifecycle(
        val sink: CPointer<kprt_sink>?,
        val lease: AppleAudioSessionLease?,
    )

    /**
     * How many anchors had to be estimated because CoreAudio said its host time was not valid.
     *
     * Kept for the appleTest that proves the flag is honoured, and as the note this sink can make about
     * anchor quality: [latencyQuality] already says `Estimated` for every anchor, so there is no quality
     * left to downgrade, and a real-time thread may not log. Counts the whole life of the sink.
     */
    internal val estimatedAnchors: Long get() = readStats { it.estimated_anchors }

    /** Callbacks the device made. Zero until it starts, and the proof that it did. */
    internal val callbacks: Long get() = readStats { it.callbacks }

    /**
     * Callbacks that found no ring and zeroed the whole buffer.
     *
     * After B1.8 that is teardown and nothing else, which is what makes register item B1-19's collapse
     * checkable from outside: a run where this is not zero while the sink was open is a run where the
     * ring went away underneath the device.
     */
    internal val zeroFilledCallbacks: Long get() = readStats { it.zero_filled_callbacks }

    /**
     * The slowest single callback body this sink has seen, in nanoseconds, measured in C from a
     * `mach_absolute_time` pair around it.
     *
     * This is the number the supervised device run judges: at 512 frames and 48 kHz the period is
     * 10,666,666 ns and the budget is half of it.
     */
    internal val worstCallbackNanos: Long get() = readStats { it.worst_callback_nanos }

    private fun lastDeadlineNanos(): Long = readStats { it.last_deadline_nanos }

    /**
     * One stats call, inside [lock].
     *
     * The lock is what stops [close] freeing the sink between the field read and the C call. A NULL
     * sink zeroes the struct rather than refusing, so a closed sink reads zeroes instead of throwing
     * at a diagnostic call site.
     */
    private inline fun <T> readStats(read: (kprt_sink_stats) -> T): T = synchronized(lock) {
        val sink = handle
        memScoped {
            val stats = alloc<kprt_sink_stats>()
            kprt_sink_read_stats(sink, stats.ptr)
            read(stats)
        }
    }

    /**
     * What this sink owns right now: the C sink handle, the ring behind it and the negotiated format.
     * Three when open, zero when closed and zero after a failed [openWithRing].
     *
     * Three and not the five of the Kotlin arrangement, because two of those five are gone rather than
     * hidden: there is no `StableRef` to pin this object into the callback, and no buffer wrapper,
     * because the callback writes the device's memory in C. Only appleTest reads this, and only to prove
     * a failed open kept nothing.
     */
    internal fun retainedResources(): Int = synchronized(lock) {
        var count = 0
        if (handle != null) count++
        if (ring != null) count++
        if (negotiated != null) count++
        count
    }

    private inline fun callAndCheck(what: String, call: (CPointer<IntVar>) -> Int) {
        memScoped {
            val status = alloc<IntVar>()
            val verdict = call(status.ptr)
            if (verdict != KPRT_SINK_OK.toInt()) {
                error(
                    "$what failed at ${verdictName(verdict)}" +
                        if (status.value != 0) " with CoreAudio status ${status.value}" else "",
                )
            }
        }
    }

    private companion object {
        /**
         * What CoreAudio typically asks for at 48 kHz. Used only to size the engine's ring before the
         * device has been asked once, and the ring is generous enough that being wrong here is
         * harmless. Kept equal to `KPRT_DEFAULT_DEVICE_BUFFER_FRAMES` in
         * `kiteplayer-rt/native/src/kite_rt_coreaudio.c`.
         */
        const val DEFAULT_DEVICE_BUFFER_FRAMES: Int = 512

        /**
         * The C verdict, as the name of the step that refused.
         *
         * Spelled out here rather than printed as a number, because "the device would not open" with no
         * further detail is the least useful diagnostic in audio. The values are the KPRT_SINK_ constants
         * of `kite_rt.h`; they are matched by number rather than imported one by one because the
         * cinterop constants are `UInt` and this is the only place that needs all of them.
         */
        fun verdictName(verdict: Int): String = when (verdict) {
            0 -> "no failure"
            1 -> "a bad argument"
            2 -> "an unsupported platform: kiteplayer-rt implements its device glue for macOS and iOS only"
            3 -> "finding the Apple output component"
            4 -> "creating the audio unit"
            5 -> "setting the stream format, which is how a device refuses a rate or a channel count"
            6 -> "installing the render callback"
            7 -> "initialising the audio unit"
            8 -> "starting the audio unit"
            9 -> "stopping the audio unit"
            10 -> "allocating the sink"
            11 -> "creating the ring"
            12 -> "attaching a second ring to a sink that already has one"
            else -> "an unknown step (verdict $verdict)"
        }
    }
}

/**
 * Creates [CoreAudioSink] instances.
 *
 * Reached through [AppleOutputBackend], which is what goes into `PlayerConfig.backends.output`: that
 * object pairs this factory with the clock the engine reads, so the two cannot disagree. Constructing
 * one directly is for a test or a custom assembly, and the clock still has to be [AppleHostClock]; see
 * the check in [CoreAudioSink].
 */
public class CoreAudioSinkFactory private constructor(
    private val policy: AppleAudioSessionPolicy,
    private val clock: MonotonicClock,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : AudioSinkFactory {

    /** Preserves the original clock-first API and uses KitePlayer's managed playback policy. */
    public constructor(clock: MonotonicClock = AppleHostClock) : this(
        AppleAudioSessionPolicy.ManagedPlayback,
        clock,
        Unit,
    )

    /** Selects who owns the iOS audio session for every sink this factory creates. */
    public constructor(
        policy: AppleAudioSessionPolicy,
        clock: MonotonicClock = AppleHostClock,
    ) : this(policy, clock, Unit)

    override val name: String = "CoreAudio"
    override suspend fun create(): AudioSink = CoreAudioSink(policy, clock)
}
