package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.LatencyQuality
import kotlinx.coroutines.flow.Flow

/**
 * An audio output device.
 *
 * This is the most safety-critical interface in the library. It runs on a real-time thread that must
 * never block, and the master clock is derived from what it reports.
 *
 * **The model is pull.** The device asks the engine for samples; the engine does not push them. Some
 * platforms are natively pull (CoreAudio AudioUnit, AAudio with a data callback, event-driven
 * WASAPI, WebAudio) and some are natively push (ALSA read-write, PulseAudio, `SourceDataLine`,
 * Android `AudioTrack` blocking write). A push platform is wrapped by one writer coroutine that
 * turns "the device has room" into a pull. Standardising on one shape means the clock has one shape.
 *
 * **A sink never converts.** No resampling, no channel remixing, no tempo change. Those live in the
 * engine's filter chain where they are testable in `commonMain` and where their own latency is
 * known. The only conversion a sink may do is trivial bit packing, for example 32-bit to packed
 * 24-bit.
 */
public interface AudioSink : AutoCloseable {

    /**
     * Opens the device.
     *
     * @param request what the engine would like.
     * @param render the callback the device will call. See [AudioRenderCallback] for its contract.
     * @return what the device actually accepted, which may differ in rate, format or layout. The
     *         engine rebuilds its resampler to match.
     */
    public suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat

    public suspend fun start()

    /** Stops and discards everything unplayed. This is the seek path. */
    public suspend fun stop()

    /** Plays out what is queued, then stops. This is the end-of-media path. */
    public suspend fun drain()

    /**
     * Pauses without discarding.
     *
     * @return false when the platform cannot pause, in which case the engine emulates a pause with
     *         [stop] and accepts the restart cost. Saying false honestly is better than pretending.
     */
    public suspend fun setPaused(paused: Boolean): Boolean

    /** The device's own buffer size in sample frames. Sizes the engine's ring. */
    public val deviceBufferFrames: Int

    /**
     * Nanoseconds of audio handed over but not yet audible, including everything inside the OS and
     * the hardware.
     *
     * This is the number the master clock is built on, so an honest answer matters more than a small
     * one. ffplay assumes every device has exactly two buffer periods, which is wrong on most
     * backends and produces a fixed A/V offset of tens to hundreds of milliseconds that nothing ever
     * corrects. Report what the platform actually says, and declare how much it can be trusted
     * through [latencyQuality].
     *
     * No engine code reads this. The audio clock is anchored from the deadline the render callback
     * carries, which needs no latency figure, so this exists for diagnostics and for a later sink
     * that has no such callback. Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public fun latencyNanos(): Long

    public val latencyQuality: LatencyQuality

    /**
     * Device loss, underrun, format change. The sink reports; the engine decides what to do.
     *
     * The engine collects nothing from this feed yet, so a sink is free to publish honestly and no
     * decision follows. Device-change and device-loss recovery is what will read it.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public val events: Flow<AudioSinkEvent>
}

public interface AudioSinkFactory {
    public suspend fun create(): AudioSink
    public val name: String
}

/**
 * Called by the audio device on its own real-time thread.
 *
 * Every clause of this contract exists because breaking it produces an audible glitch:
 *
 * - Not a suspending function. There is no coroutine on this thread and no dispatcher to resume on.
 * - Must not allocate, must not take a contended lock, must not log, must not throw.
 * - Reads from a preallocated single-producer single-consumer ring with a try-lock, and on
 *   contention writes silence and returns rather than blocking the device.
 * - [deadlineNanos] is when the **last** frame of this buffer becomes audible, on the engine's
 *   monotonic clock. It is what the audio clock is anchored to, and it is the reason this callback
 *   takes a time at all.
 *
 * ffplay does the opposite of all of this: it resamples, allocates and computes A/V correction
 * inside the device callback. The busy-wait workaround in its Windows path is the visible scar.
 *
 * @return frames written. Fewer than [frames] means the remainder is silence.
 */
public fun interface AudioRenderCallback {
    public fun onRender(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int
}

/**
 * The device's own buffer, to be written in place.
 *
 * The engine writes through this rather than returning an array so that the CoreAudio and AAudio
 * callbacks can hand over the buffer the OS gave them, with no copy anywhere in the path.
 */
public interface AudioSinkBuffer {
    public val format: AudioFormat

    /**
     * Interleaved write of [frames] sample frames, starting at frame [destinationFrameOffset] of
     * this buffer.
     *
     * The destination offset is not optional. A ring buffer's read can wrap, so one render call
     * becomes two writes, and the second must land after the first rather than overwriting it.
     */
    public fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int)

    /**
     * Planar write, one channel at a time, for devices that want planes.
     *
     * Nothing calls it: the engine's ring is interleaved and writes through [writeInterleaved]. It is
     * here for a platform whose device buffer is planar.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public fun writePlane(
        channel: Int,
        source: FloatArray,
        sourceOffset: Int,
        destinationFrameOffset: Int,
        frames: Int,
    )

    /** Fills [frames] frames from [frameOffset] with silence. */
    public fun writeSilence(frameOffset: Int, frames: Int)
}

public sealed interface AudioSinkEvent {
    /** The device ran dry. The engine counts it and may rebuffer. */
    public data class Underrun(val detail: String) : AudioSinkEvent

    /** The device disappeared: unplugged, taken by another application, session interrupted. */
    public data class DeviceLost(val detail: String) : AudioSinkEvent

    /** The default device changed. The engine tears the sink down and builds a new one. */
    public data class DeviceChanged(val detail: String) : AudioSinkEvent

    /**
     * The device wants a different format than the one negotiated.
     *
     * The engine responds by recreating the sink, never by reconfiguring in place. In-place
     * reconfiguration of an audio device is where every player's device-change bugs live.
     */
    public data class FormatChangeRequested(val detail: String) : AudioSinkEvent
}
