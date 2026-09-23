package io.github.yuroyami.kiteplayer.ffmpeg

internal actual fun createEmptyFile(path: String) {
    try {
        java.io.FileOutputStream(path).close()
    } catch (failure: java.io.IOException) {
        throw IllegalArgumentException("cannot create the recording at $path: ${failure.message}", failure)
    }
}
