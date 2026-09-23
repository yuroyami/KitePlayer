@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.strerror

internal actual fun createEmptyFile(path: String) {
    val file = fopen(path, "wb")
        ?: throw IllegalArgumentException("cannot create the recording at $path: ${strerror(errno)?.toKString()}")
    fclose(file)
}
