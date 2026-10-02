@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

internal actual fun readTestFile(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file).toInt()
        fseek(file, 0, SEEK_SET)
        val bytes = ByteArray(size)
        if (size > 0) {
            bytes.usePinned { pinned ->
                val read = fread(pinned.addressOf(0), 1uL, size.toULong(), file)
                check(read.toInt() == size) { "short read on $path: $read of $size" }
            }
        }
        return bytes
    } finally {
        fclose(file)
    }
}
