package io.github.yuroyami.kiteplayer.ffmpeg

// A page has no file system to write a recording into.
internal actual fun createEmptyFile(path: String): Unit =
    throw UnsupportedOperationException("a browser page has no file to write a recording to")
