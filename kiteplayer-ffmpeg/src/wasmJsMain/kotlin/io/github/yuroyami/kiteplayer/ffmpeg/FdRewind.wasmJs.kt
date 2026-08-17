package io.github.yuroyami.kiteplayer.ffmpeg

internal actual fun rewindFdOption(options: Map<String, String>) {
    // A browser has no file descriptors, so nothing can mint an fd: item to rewind. Deliberately
    // empty rather than throwing: this runs on every open, and refusing here would break opens
    // that never involved a descriptor.
}
