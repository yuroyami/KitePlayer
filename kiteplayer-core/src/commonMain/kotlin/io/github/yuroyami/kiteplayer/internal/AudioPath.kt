package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSink
import kotlinx.atomicfu.atomic

/**
 * What opening the audio path produced: the format the device accepted, and the ring behind it.
 *
 * One return value rather than two calls, because the two are decided together and a caller holding
 * one without the other cannot do anything with it.
 */
internal class OpenedAudioPath(
    val format: AudioFormat,
    val ring: AudioRingHandle,
)

/**
 * Opens the device and builds the ring the engine will feed.
 *
 * ### Why this is an expect function and not an `if` in [io.github.yuroyami.kiteplayer.AudioPlayback]
 *
 * There are two arrangements now, and exactly one line of code has to know which one it is in. In the
 * portable arrangement the engine hands the sink an
 * [io.github.yuroyami.kiteplayer.spi.AudioRenderCallback] closure that reads a [KotlinAudioRing]. In
 * the native arrangement, from B1.8 onward, a sink may own its device callback in C, in which case it
 * owns the ring too and the engine only writes into it; that is register item B1-17, and the reason is
 * that a Kotlin lambda on a real-time thread is a mutator the garbage collector has to stop.
 *
 * The branch cannot live in `commonMain` because the type it tests for, `NativeRingAudioSink`, names a
 * C pointer and `commonMain` targets js and wasmJs, which can never contain C. It could have lived in
 * `AudioPlayback` behind another expect declaration, but then two things would be platform dependent
 * instead of one. So this is the whole of it: one expect function, one native-only interface, and every
 * line of policy above it, backpressure, clock anchoring, flush ordering and capacity, stays in
 * `commonMain` where it is tested with a virtual clock.
 *
 * @param capacityFrames how many sample frames to hold, given the format the device accepted. A
 *        function and not a number, because the capacity depends on that format and the format is not
 *        known until the device has answered.
 */
internal expect suspend fun openAudioPath(
    sink: AudioSink,
    request: AudioFormat,
    capacityFrames: (AudioFormat) -> Int,
): OpenedAudioPath

/**
 * The portable arrangement: a Kotlin ring behind a Kotlin render callback.
 *
 * Every `actual` of [openAudioPath] ends here except the native one, which ends here too for any sink
 * that does not own a C callback. It is written once and shared rather than repeated per platform,
 * because five copies of the same eight lines is five places for them to drift.
 *
 * ### The holder, and why it is atomic rather than a plain `var`
 *
 * The callback is installed before the ring exists, because the ring's capacity depends on the format
 * the device returns from `open`. So the closure has to read the ring through something mutable. That
 * something is written by the thread opening the path and read by the device's thread, which is a
 * cross-thread handoff, and a plain captured variable would leave it to luck and to whatever barrier
 * the device's own start happened to provide. One atomic reference, allocated once per open, states it
 * instead. It costs one object at open time and nothing per callback.
 */
internal suspend fun openKotlinAudioPath(
    sink: AudioSink,
    request: AudioFormat,
    capacityFrames: (AudioFormat) -> Int,
): OpenedAudioPath {
    val holder = atomic<KotlinAudioRing?>(null)
    val negotiated = sink.open(request) { destination, frames, deadlineNanos ->
        // The real-time path of this arrangement. Everything it touches is preallocated, and it never
        // waits. Before the ring is published this returns zero, which by the contract on
        // `AudioRenderCallback` means the whole buffer is silence and the sink is the one that writes
        // it. That sentence is stated here because B1-19 collapsed the engine's own silence fill into
        // `kprt_ring_render`, so on this path the obligation is the sink's and nowhere else, and an
        // earlier version of this comment described a duty no interface documented any more. The
        // window itself is only reachable for a sink that pulls before `start()`: `sink.open` does not
        // start a device, and the assignment below happens before the engine calls `play`.
        holder.value?.render(destination, frames, deadlineNanos) ?: 0
    }
    val ring = KotlinAudioRing(negotiated, capacityFrames = capacityFrames(negotiated))
    holder.value = ring
    return OpenedAudioPath(negotiated, ring)
}
