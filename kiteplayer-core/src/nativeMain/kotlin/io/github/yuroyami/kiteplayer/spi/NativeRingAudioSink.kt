// The seam between a Kotlin engine and a C real-time core is a C pointer, so this file crosses into
// cinterop and says so at the top rather than through a compiler flag nobody reads.
@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.spi

import cnames.structs.kprt_ring
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Marks the native ring handoff that exposes the real-time core's raw C pointer.
 *
 * Opting in means the caller accepts that the sink owns the pointer, that it stays valid only until
 * the sink closes, and that no Kotlin code may free it.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Raw ring API: the sink owns this C pointer and releases it on close. " +
        "Opt in only at the native audio handoff boundary.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class RawRingApi

/**
 * A sink that owns its device callback in C, and therefore owns the ring that callback reads.
 *
 * ### Why this exists
 *
 * Register item B1-17. Until B1.8 every sink was handed an [AudioRenderCallback] and called it from
 * whatever thread its device used. On macOS that made the device's real-time thread a Kotlin mutator
 * the garbage collector has to stop at a safepoint, and the measured worst stop-the-world pause on
 * the development machine was 63 to 256 microseconds against a 10.67 millisecond period. The fix is a
 * callback that never leaves C, and a callback that never leaves C cannot be handed a Kotlin lambda.
 * So a sink that has one says so by implementing this interface, and gets a ring instead.
 *
 * ### Why it is a capability and not a fork
 *
 * The choice belongs to the sink, not to the platform, because the sink is what owns the device
 * callback. A native sink with no C callback simply does not implement this interface and keeps
 * working exactly as before, through [AudioSink.open] and a Kotlin ring. Nothing in the engine
 * branches on the operating system, and there is one code path for policy: everything about
 * backpressure, clock anchoring, flush ordering and buffer sizing stays in `commonMain`.
 *
 * ### Ownership, which is the part that bites
 *
 * The ring belongs to the sink for its whole life, and [AudioSink.close] releases it. That is not a
 * convention that could have gone the other way. Clearing or freeing a ring requires the device
 * callback to be provably out of its render path, and the only code that can promise that is the code
 * that stopped the device. So the engine writes into the ring and never frees it.
 *
 * ### What a caller must not do
 *
 * [AudioSink.open] is not the entry point for a sink that implements this. Calling it must fail
 * loudly rather than open a device whose callback ignores the lambda it was given, because a device
 * that plays silence while the caller believes its callback is being called is the worst of the three
 * possible behaviours.
 */
public interface NativeRingAudioSink : AudioSink {

    /**
     * Opens the device and the ring behind it together, and hands back both.
     *
     * The order inside is fixed by a real dependency: the ring's format is the format the device
     * accepted, and its capacity is computed from that format plus the device's own period, so the
     * device has to be negotiated before the ring can exist. That is why the capacity arrives as a
     * function rather than as a number, and why the policy behind that function stays in the engine.
     *
     * @param request what the engine would like. What it gets may differ, which is why the format
     *        comes back in the result.
     * @param capacityFrames how many sample frames to hold, given the format the device accepted.
     * @return the accepted format and the ring the feeder must write into. The ring is owned by this
     *         sink; see the class note.
     */
    public suspend fun openWithRing(
        request: AudioFormat,
        capacityFrames: (AudioFormat) -> Int,
    ): NativeRingHandoff
}

/**
 * What [NativeRingAudioSink.openWithRing] hands back: the format the device accepted, and the C ring
 * its callback reads.
 *
 * Two fields rather than one because neither can be derived from the other honestly. The ring knows
 * its sample rate and its channel count, but not the channel layout, and a layout guessed from a
 * channel count is exactly the kind of fabrication defect D30 is about.
 */
@RawRingApi
public class NativeRingHandoff(
    public val format: AudioFormat,
    public val ring: CPointer<kprt_ring>,
)
