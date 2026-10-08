package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioOutputDevice
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.currentRoute
import platform.AVFAudio.maximumOutputNumberOfChannels
import platform.AVFAudio.outputLatency
import platform.Foundation.NSNotificationCenter

/**
 * iOS has no device list and no default device of its own: the audio session owns the route, and
 * RemoteIO follows it. Route changes reach the player through the session's route notice instead.
 */
internal actual fun platformAppleOutputDevices(): AppleOutputDevices = object : AppleOutputDevices {
    override fun watchDefaultOutput(onChange: (detail: String) -> Unit): AutoCloseable? = null

    /** Nothing is ever bound on iOS, so there is no device to watch. */
    override fun watchDevice(device: UInt, onLost: (detail: String) -> Unit): AutoCloseable? = null

    /** The outputs of the current route, which are the only devices an app on iOS can name. */
    override fun devices(): List<AudioOutputDevice> =
        AVAudioSession.sharedInstance().currentRoute.outputs.mapNotNull { output ->
            (output as? AVAudioSessionPortDescription)?.let {
                AudioOutputDevice(id = it.UID, name = it.portName, isDefault = true)
            }
        }

    /** 0 for an output of the current route, which needs no binding, and null for anything else. */
    override fun deviceFor(id: String): UInt? = if (devices().any { it.id == id }) 0u else null

    /** What the current route can carry: two for headphones or the speaker, more for a surround receiver. */
    override fun outputChannelCount(): Int? =
        AVAudioSession.sharedInstance().maximumOutputNumberOfChannels.toInt().takeIf { it > 0 }

    /** The session's output latency for the current route, which is the only route there is. */
    override fun outputLatencyNanos(device: UInt): Long? =
        latencySecondsToNanos(AVAudioSession.sharedInstance().outputLatency)

    /** The session's route notice: headphones connecting or leaving change the latency. */
    override fun watchOutputLatency(device: UInt, onChange: () -> Unit): AutoCloseable? {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(AVAudioSessionRouteChangeNotification, null, null) { _ -> onChange() }
        return AutoCloseable { center.removeObserver(observer) }
    }
}
