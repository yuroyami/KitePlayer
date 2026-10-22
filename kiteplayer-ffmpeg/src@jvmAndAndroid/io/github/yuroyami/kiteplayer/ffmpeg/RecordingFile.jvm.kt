package io.github.yuroyami.kiteplayer.ffmpeg

internal actual fun createEmptyFile(path: String) {
    try {
        // Appending creates a missing file and leaves an existing one as it is: the file may be the
        // one playing, which the sink refuses only when it declares its streams (#471).
        java.io.FileOutputStream(path, true).close()
    } catch (failure: java.io.IOException) {
        throw IllegalArgumentException("cannot create the recording at $path: ${failure.message}", failure)
    }
}
