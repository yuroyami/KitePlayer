package io.github.yuroyami.kiteplayer.output

/**
 * iOS has no device list and no default device of its own: the audio session owns the route, and
 * RemoteIO follows it. Route changes reach the player through the session's route notice instead.
 */
internal actual fun platformAppleOutputDevices(): AppleOutputDevices = object : AppleOutputDevices {
    override fun watchDefaultOutput(onChange: (detail: String) -> Unit): AutoCloseable? = null
}
