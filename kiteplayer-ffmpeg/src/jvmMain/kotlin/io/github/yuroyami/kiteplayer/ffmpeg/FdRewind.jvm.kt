package io.github.yuroyami.kiteplayer.ffmpeg

internal actual fun rewindFdOption(options: Map<String, String>) {
    // No portable way to seek a raw integer descriptor on the JVM, and no JVM caller mints
    // fd: items today: content:// resolution is Android's and the posix door is native's.
}
