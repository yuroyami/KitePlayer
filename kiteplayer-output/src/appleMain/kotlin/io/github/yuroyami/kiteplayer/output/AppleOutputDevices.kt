package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioOutputDevice

/**
 * The questions about Apple output devices that are not the audio unit's own lifecycle.
 *
 * The unit is created, started and disposed in C, in `kiteplayer-rt`, because its render callback
 * is real-time code. The members here run on the caller's thread or on a CoreAudio notification
 * thread, never on the device's thread, so they are Kotlin.
 *
 * macOS answers through the CoreAudio hardware API. iOS has no such API, because its audio session
 * owns the route: there the device list is the current route, and nothing can be bound.
 */
internal interface AppleOutputDevices {

    /**
     * Calls [onChange] each time the system default output changes, with a sentence that names the
     * new default. [onChange] runs on a CoreAudio notification thread.
     *
     * @return the registration, which `close` releases. After `close` returns, [onChange] is not
     *         called again. Null when the platform has no such notice or refused the registration,
     *         which costs the notice and nothing else.
     */
    fun watchDefaultOutput(onChange: (detail: String) -> Unit): AutoCloseable?

    /**
     * Calls [onLost] once when the CoreAudio device [device] disappears, with a sentence that names
     * it. A device that is already gone when this is called is reported at once, on the calling
     * thread. Otherwise [onLost] runs on a CoreAudio notification thread.
     *
     * @return the registration, which `close` releases, or null when the platform has no such
     *         notice or refused the registration.
     */
    fun watchDevice(device: UInt, onLost: (detail: String) -> Unit): AutoCloseable?

    /** The output devices an application can play through, the system default among them. */
    fun devices(): List<AudioOutputDevice>

    /**
     * The CoreAudio device that [id] names, 0 when [id] needs no binding because it is the route the
     * system already plays through, or null when no such device exists now.
     */
    fun deviceFor(id: String): UInt?

    /** How many channels the current output route carries, or null when this platform does not say (#466). */
    fun outputChannelCount(): Int? = null

    /**
     * How long a frame takes from the device to the ear on the route that [device] plays through,
     * in nanoseconds, as the system reports it (#495). 0 for [device] is the route the system
     * already plays through. A built-in speaker answers a few milliseconds and a Bluetooth route
     * far more. Null when the system does not say.
     */
    fun outputLatencyNanos(device: UInt): Long? = null

    /**
     * Calls [onChange] each time [outputLatencyNanos] may answer differently for [device]: the
     * route changed, or the device changed its latency or its sample rate. [onChange] runs on a
     * notification thread, never on the device's render thread.
     *
     * @return the registration, which `close` releases, or null when the platform has no such notice.
     */
    fun watchOutputLatency(device: UInt, onChange: () -> Unit): AutoCloseable? = null
}

/**
 * [frames] sample frames at [sampleRate] in nanoseconds, or null for a rate that is not a rate.
 * CoreAudio reports a device's latency in frames of its own sample rate.
 */
internal fun latencyFramesToNanos(frames: Long, sampleRate: Double): Long? =
    if (sampleRate > 0.0 && sampleRate.isFinite() && frames >= 0) (frames * 1_000_000_000.0 / sampleRate).toLong() else null

/** [seconds] in nanoseconds, or null for a figure that is not a time. The iOS session reports seconds. */
internal fun latencySecondsToNanos(seconds: Double): Long? =
    if (seconds >= 0.0 && seconds.isFinite()) (seconds * 1_000_000_000.0).toLong() else null

/** The CoreAudio answers on macOS, and the session's on iOS. */
internal expect fun platformAppleOutputDevices(): AppleOutputDevices
