package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioOutputDevice
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.currentRoute

/**
 * iOS has no device list and no default device of its own: the audio session owns the route, and
 * RemoteIO follows it. Route changes reach the player through the session's route notice instead.
 */
internal actual fun platformAppleOutputDevices(): AppleOutputDevices = object : AppleOutputDevices {
    override fun watchDefaultOutput(onChange: (detail: String) -> Unit): AutoCloseable? = null

    /** The outputs of the current route, which are the only devices an app on iOS can name. */
    override fun devices(): List<AudioOutputDevice> =
        AVAudioSession.sharedInstance().currentRoute.outputs.mapNotNull { output ->
            (output as? AVAudioSessionPortDescription)?.let {
                AudioOutputDevice(id = it.UID, name = it.portName, isDefault = true)
            }
        }

    /** 0 for an output of the current route, which needs no binding, and null for anything else. */
    override fun deviceFor(id: String): UInt? = if (devices().any { it.id == id }) 0u else null
}
