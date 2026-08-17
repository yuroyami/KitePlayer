package io.github.yuroyami.kiteplayer.ffmpeg

import platform.posix.SEEK_SET
import platform.posix.lseek

internal actual fun rewindFdOption(options: Map<String, String>) {
    val fd = options["fd"]?.toIntOrNull() ?: return
    // An unseekable descriptor answers -1 with ESPIPE, which is the streamed case and fine.
    lseek(fd, 0, SEEK_SET)
}
