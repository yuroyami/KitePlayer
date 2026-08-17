package io.github.yuroyami.kiteplayer.ffmpeg

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants

internal actual fun rewindFdOption(options: Map<String, String>) {
    val fd = options["fd"]?.toIntOrNull() ?: return
    // fromFd dups, and a dup shares the file offset with the original, so seeking the dup
    // rewinds the caller's descriptor too; use closes only the dup.
    runCatching {
        ParcelFileDescriptor.fromFd(fd).use { dup ->
            Os.lseek(dup.fileDescriptor, 0L, OsConstants.SEEK_SET)
        }
    }
}
