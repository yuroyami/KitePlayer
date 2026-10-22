package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioOutputDevice
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_route_channels

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
     * How many channels a sink opened now on [device] for [requested] channels would get, or zero
     * when the output does not say (#563). 0 for [device] is the route the system already plays
     * through. A sink asks after a route change, to learn whether it still fits its output.
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun routeChannels(device: UInt, requested: Int): Int = kprt_sink_route_channels(device, requested)

    /**
     * How long a frame takes from the device to the ear on the route that [device] plays through,
     * in nanoseconds, as the system reports it (#495). 0 for [device] is the route the system
     * already plays through. A built-in speaker answers a few milliseconds and a Bluetooth route
     * far more. Null when the system does not say.
     */
    fun outputLatencyNanos(device: UInt): Long? = null

    /**
     * What the route that [device] plays through is now: headphones, speakers or something this
     * cannot tell (#503). 0 for [device] is the route the system already plays through. iOS
     * answers unknown, because its audio session reports headphones leaving by itself.
     */
    fun outputRoute(device: UInt): OutputRoute = OutputRoute.Unknown

    /**
     * Calls [onChange] each time [outputLatencyNanos] or [outputRoute] may answer differently for
     * [device]: the route changed, the device switched between its speaker and its headphone
     * jack, or it changed its latency or its sample rate. [onChange] runs on a notification
     * thread, never on the device's render thread.
     *
     * @return the registration, which `close` releases, or null when the platform has no such notice.
     */
    fun watchRoute(device: UInt, onChange: () -> Unit): AutoCloseable? = null
}

/** Where the sound comes out, as far as the system's description of the device tells. */
internal enum class OutputRouteKind {
    /** Headphones or earphones, by the device's own word. */
    Headphones,

    /** A Bluetooth device that does not say what it is. Most are headphones, so losing one counts. */
    UnnamedBluetooth,

    /** Speakers, by the device's own word. */
    Speakers,

    /** Anything else: HDMI, USB, AirPlay, or no description at all. */
    Unknown,
}

/** One route: its [kind], and the device's [name] for a sentence about it. */
internal data class OutputRoute(val kind: OutputRouteKind, val name: String? = null) {
    companion object {
        val Unknown = OutputRoute(OutputRouteKind.Unknown)
    }
}

/**
 * Classifies a CoreAudio output from three of its properties, each null when the device does not
 * answer it. The terminal type and the data source say what the device is, so they come first. The
 * transport says only how it is connected: a Bluetooth device that names itself a speaker is a
 * speaker, and one that names nothing is taken for headphones.
 *
 * @param transport `kAudioDevicePropertyTransportType`
 * @param terminal `kAudioStreamPropertyTerminalType` of the first output stream
 * @param dataSource `kAudioDevicePropertyDataSource`, which a built-in device with a jack changes
 */
internal fun classifyOutput(transport: UInt?, terminal: UInt?, dataSource: UInt?): OutputRouteKind = when {
    terminal == TERMINAL_HEADPHONES || dataSource == SOURCE_HEADPHONES -> OutputRouteKind.Headphones
    terminal == TERMINAL_SPEAKER || dataSource == SOURCE_INTERNAL_SPEAKER -> OutputRouteKind.Speakers
    transport == TRANSPORT_BLUETOOTH || transport == TRANSPORT_BLUETOOTH_LE -> OutputRouteKind.UnnamedBluetooth
    else -> OutputRouteKind.Unknown
}

/**
 * Whether a change of route made private sound loud: headphones, or a Bluetooth device that may be
 * headphones, gave way to speakers. Speakers to speakers, and anything to or from an unknown
 * route, is not that.
 */
internal fun becameNoisy(from: OutputRouteKind, to: OutputRouteKind): Boolean =
    (from == OutputRouteKind.Headphones || from == OutputRouteKind.UnnamedBluetooth) && to == OutputRouteKind.Speakers

/** A CoreAudio four-character code as its number. */
private fun fourCharacters(code: String): UInt = code.fold(0u) { value, character -> (value shl 8) or character.code.toUInt() }

private val TERMINAL_HEADPHONES = fourCharacters("hdph")
private val TERMINAL_SPEAKER = fourCharacters("spkr")
private val SOURCE_HEADPHONES = fourCharacters("hdpn")
private val SOURCE_INTERNAL_SPEAKER = fourCharacters("ispk")
private val TRANSPORT_BLUETOOTH = fourCharacters("blue")
private val TRANSPORT_BLUETOOTH_LE = fourCharacters("blea")

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
