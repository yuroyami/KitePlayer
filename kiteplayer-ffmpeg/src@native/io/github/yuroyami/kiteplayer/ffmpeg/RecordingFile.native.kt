@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.strerror

internal actual fun createEmptyFile(path: String) {
    // Appending creates a missing file and leaves an existing one as it is: the file may be the one
    // playing, which the sink refuses only when it declares its streams (#471).
    val file = fopen(path, "ab")
        ?: throw IllegalArgumentException("cannot create the recording at $path: ${strerror(errno)?.toKString()}")
    fclose(file)
}
