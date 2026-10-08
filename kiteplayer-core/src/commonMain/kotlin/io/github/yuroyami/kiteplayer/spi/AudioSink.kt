package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.AudioContent
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.PlaybackError
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
     * **A sink that owns its device callback in C does not implement this.** On such a sink this
     * throws, because the alternative is worse: a device whose C callback ignores the lambda it was
     * handed would play correctly while the caller believed its callback was being called. Those
     * sinks say so by implementing `NativeRingAudioSink`, which exists only in the native source set
     * and hands back a C ring instead of taking a callback, and the engine calls that entry point
     * instead. `CoreAudioSink` is one of them. Every other sink,
     * including every test fake and every push-model sink, is opened here and works exactly as before.
     *
     * @param request what the engine would like.
     * @param render the callback the device will call. See [AudioRenderCallback] for its contract.
     * @return what the device actually accepted, which may differ in rate, format or layout. The
     *         engine rebuilds its resampler to match.
     */
    public suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat

    /**
     * What the sound the next open plays is, for the platform's own sound processing (#446). The
     * engine calls this before every open with the item's [AudioContent], never [AudioContent.Automatic],
     * which it has already resolved. It takes effect at that open: a device already open keeps
     * what it was opened with. A platform with no such setting ignores it, which is the default.
     */
    public fun setContent(content: AudioContent) {}

    /** Starts the device pulling samples through the render callback. */
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

    /**
     * Whether stopping or pausing this device cuts the sound at whatever sample it reached (#486).
     *
     * A wave that drops from its level to silence in one sample is heard as a click, and so is one
     * that jumps back at the resume. When this is true the engine fades the sound out over 5 ms
     * before it pauses, seeks or stops, waits until the device has played that fade, and fades in
     * again after the restart, the way a volume change already walks. It waits by the deadlines the
     * render callback is handed, so a sink that answers true must keep pulling its render callback
     * until it is stopped and must date the deadlines it hands over honestly.
     *
     * False by default, which keeps the old cut, and false is right for a platform that fades on
     * its own: Android's mixer ramps a paused track down, and fading again ahead of a buffer that
     * deep would only make the pause late.
     */
    public val cutsSoundOnStop: Boolean get() = false

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
     * The engine reads it for `PlaybackStats.audioLatency`, from its own thread while the device
     * thread renders, so an implementation must be safe to call concurrently with the render. The
     * audio clock is anchored from the deadline the render callback carries, which needs no
     * latency figure.
     */
    public fun latencyNanos(): Long

    /** How far [latencyNanos] can be trusted. */
    public val latencyQuality: LatencyQuality

    /**
     * A platform handle for effects that attach to this device's stream, or null when the platform
     * has no such concept.
     *
     * Android's audio session id is the only one today, and it is here because nothing above the
     * sink can work it out. `LoudnessEnhancer`, `Equalizer` and `Visualizer` all attach to a
     * session, and a session allocated by an application is one nothing is playing on: the effect
     * attaches and does nothing, with no error. The number has to come from the `AudioTrack` that
     * is actually playing, which only the sink holds.
     *
     * Null while no device is open, and null again after close. A released session id still reads
     * like a valid number and effects attached to it are silently inert, so saying nothing is the
     * honest answer and lets a caller wait instead.
     */
    public val platformSessionId: Int? get() = null

    /**
     * Device loss, underrun, format change. The sink reports; the engine decides what to do.
     *
     * **What the engine does with this today, per event, so a sink author is not guessing.** It
     * collects the feed on the session lane. `DeviceLost` and `DeviceChanged` become a
     * `PlaybackWarning.AudioDeviceChanged`. `Underrun` becomes an `AudioDeviceUnderrun` warning,
     * once per session. `FormatChangeRequested` becomes an `AudioDeviceChanged` warning naming the
     * request. `Failed` stops the session, and the player fails with the event's error. Nothing
     * here rebuilds a sink or recovers a device yet: publish honestly, and expect a warning or a
     * typed failure rather than a repair.
     */
    public val events: Flow<AudioSinkEvent>
}

/** Creates the audio sink of a session. An [OutputBackend] carries one. */
public interface AudioSinkFactory {
    /** A new sink that is not open yet. The engine opens it, and closes it when the session ends. */
    public suspend fun create(): AudioSink
    /** For logs and diagnostics. */
    public val name: String

    /**
     * How many channels the output the next sink would play through carries, or null when the
     * platform does not say (#466). Two for headphones, a phone speaker or a stereo device, six
     * for a 5.1 receiver. This is the route's own count, not what a sink would accept: a sink may
     * take six channels and fold them to two itself. The engine asks at an open when
     * [io.github.yuroyami.kiteplayer.AudioConfig.matchOutputChannels] is on, so it must answer
     * quickly and never throw. Null by default.
     */
    public fun outputChannelCount(): Int? = null
}

/**
 * Called by the audio device on its own real-time thread.
 *
 * Every clause of this contract exists because breaking it produces an audible glitch:
 *
 * - Not a suspending function. There is no coroutine on this thread and no dispatcher to resume on.
 * - Must not allocate, must not take a contended lock, must not log, must not throw.
 * - Reads from a preallocated single-producer single-consumer ring, and when that ring is dry writes
 *   silence and returns rather than waiting for the feeder.
 * - The `deadlineNanos` argument of [onRender] is when the **last** frame of this buffer becomes
 *   audible, on the engine's monotonic clock. It is what the audio clock is anchored to, and it is
 *   the reason this callback takes a time at all.
 *
 * ffplay does the opposite of all of this: it resamples, allocates and computes A/V correction
 * inside the device callback. The busy-wait workaround in its Windows path is the visible scar.
 *
 * ### What this interface can and cannot promise, corrected
 *
 * An earlier version of this note said the ring is read "with a try-lock, and on contention writes
 * silence". That was never true of any ring in this library: `KotlinAudioRing` takes no lock at all.
 * What it does instead is worse in one specific place: while publishing the
 * clock anchor, the real-time thread reads a sequence counter the feeder writes, and it retries with
 * no bound if it catches the feeder mid-update. That is a priority inversion on a real-time thread,
 * and it is why the shipped macOS path no longer goes through this interface at all: `CoreAudioSink`
 * owns a C callback over a C ring in which the real-time thread is the writer and never waits.
 *
 * This interface remains the contract for every other sink, and `KotlinAudioRing` remains its
 * implementation, permanently: js and wasmJs can never contain C, and the Kotlin ring is the only
 * oracle the C one can be checked against. What it must not be presented as is
 * coverage of the macOS device path.
 *
 * @return frames written, counted from the start of the buffer. Fewer than the `frames` asked for
 *         means the remainder is silence, and writing that silence is the sink's own obligation.
 *         That is stated rather than implied because the engine's silence fill moved into
 *         `kprt_ring_render`, which is the C path and is not this one: on this path nothing above the
 *         sink zeroes the tail, and an unwritten device buffer plays whatever was left in it.
 *         [AudioSinkBuffer.writeSilence] is what a sink writes it with.
 */
public fun interface AudioRenderCallback {
    /** Writes up to [frames] sample frames into [destination], whose last frame becomes audible at [deadlineNanos]. */
    public fun onRender(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int
}

/**
 * The device's own buffer, to be written in place.
 *
 * The engine writes through this rather than returning an array so that a device callback can hand over
 * the buffer the OS gave it, with no copy anywhere in the path.
 *
 * CoreAudio no longer arrives here: its callback is C and writes the device's memory with
 * `memcpy` and `memset` inside `kprt_ring_render`. This stays because it is the shape every other sink
 * uses, including a future AAudio one, and because it is how the portable ring is tested.
 */
public interface AudioSinkBuffer {
    /** The layout of the device buffer, which is the format the sink accepted at open. */
    public val format: AudioFormat

    /**
     * Interleaved write of [frames] sample frames, starting at frame [destinationFrameOffset] of
     * this buffer.
     *
     * The destination offset is not optional. A ring buffer's read can wrap, so one render call
     * becomes two writes, and the second must land after the first rather than overwriting it.
     */
    public fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int)

    /** Fills [frames] frames from [frameOffset] with silence. */
    public fun writeSilence(frameOffset: Int, frames: Int)
}

/** What a sink reports about its device, on [AudioSink.events]. */
public sealed interface AudioSinkEvent {
    /** The device ran dry. The engine warns (`AudioDeviceUnderrun`), once per session; it does not rebuffer on it. */
    public data class Underrun(val detail: String) : AudioSinkEvent

    /** The device disappeared: unplugged, taken by another application, session interrupted. */
    public data class DeviceLost(val detail: String) : AudioSinkEvent

    /** The default device changed. The engine warns (`AudioDeviceChanged`) and keeps the sink. */
    public data class DeviceChanged(val detail: String) : AudioSinkEvent

    /**
     * The sound moved from a private output to a loud one while the sink kept playing: headphones
     * were unplugged or went out of range, and the system's speakers took over (#503).
     *
     * A sink reports this only where the platform does not report it by another road, which today
     * is macOS. The engine passes it on as `PlayerEvent.AudioOutputBecameNoisy` and does nothing
     * else: whether to pause is the application's policy, and the media session applies it.
     *
     * A sink keeps the newest one until a collector takes it, so the engine hears of a change that
     * came before it subscribed. Several that nobody took yet may arrive as one, the newest.
     *
     * @param atNanos when the sink received the route notice, on the clock of its output backend.
     *        It is the time of the notice, not of the unplugging, which nobody can know. The engine
     *        drops a notice that is not newer than the last play, pause, stop or open a caller made.
     */
    public data class BecameNoisy(val atNanos: Long) : AudioSinkEvent

    /**
     * The device wants a different format than the one negotiated.
     *
     * The engine warns (`AudioDeviceChanged`, naming the request) and keeps the sink: it cannot
     * renegotiate a device yet. When it does act it will recreate the sink rather than
     * reconfigure in place, because in-place reconfiguration is where every player's device-change
     * bugs live.
     */
    public data class FormatChangeRequested(val detail: String) : AudioSinkEvent

    /**
     * The sink cannot play again, and [error] says why. For example, the one device it was bound to
     * is gone, and a bound sink never moves to another device.
     *
     * The engine stops the session and the player fails with [error]. A sink that can recover
     * reports `DeviceLost` instead.
     */
    public data class Failed(val error: PlaybackError) : AudioSinkEvent
}
