package io.github.yuroyami.kiteplayer.output

/**
 * The questions about Apple output devices that are not the audio unit's own lifecycle.
 *
 * The unit is created, started and disposed in C, in `kiteplayer-rt`, because its render callback
 * is real-time code. The members here run on the caller's thread or on a CoreAudio notification
 * thread, never on the device's thread, so they are Kotlin.
 *
 * macOS answers through the CoreAudio hardware API. iOS has no such API, because its audio session
 * owns the route, so every member there answers "nothing".
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
}

/** The CoreAudio answers on macOS, and the empty ones on iOS. */
internal expect fun platformAppleOutputDevices(): AppleOutputDevices
