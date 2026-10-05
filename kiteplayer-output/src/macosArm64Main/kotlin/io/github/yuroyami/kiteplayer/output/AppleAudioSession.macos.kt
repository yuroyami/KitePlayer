package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioContent

/** macOS has no process-wide AVAudioSession; the shared lease is intentionally a no-op there. */
internal actual fun platformAppleAudioSessionController(): AppleAudioSessionController =
    object : AppleAudioSessionController {
        override fun setPlaybackCategory(content: AudioContent) = Unit

        override fun setActive(active: Boolean, notifyOthers: Boolean) = Unit
    }
