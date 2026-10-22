@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.internal

import platform.posix.EOF
import platform.posix.fclose
import platform.posix.fgetc
import platform.posix.fopen

internal actual fun readExternalFile(path: String, limit: Int): ExternalFile {
    // fgetc and nothing else, deliberately. fseek, ftell and fread all speak platform-width
    // numbers (long, size_t), and this file compiles in the intermediate nativeMain source set,
    // which spans 32-bit watch targets beside the 64-bit world; the compiler rightly refuses
    // width-varying signatures there. fgetc returns Int on every libc that exists, so the
    // portable spelling is a byte loop into a growing buffer. A subtitle file is small text and
    // is read once per selection; this is not a hot path.
    val file = fopen(path, "rb") ?: return ExternalFile.Unreadable
    try {
        var buffer = ByteArray(minOf(INITIAL_CAPACITY, limit))
        var length = 0
        while (true) {
            val value = fgetc(file)
            if (value == EOF) break
            if (length == limit) return ExternalFile.TooLarge
            if (length == buffer.size) buffer = buffer.copyOf((buffer.size * 2).coerceAtMost(limit))
            buffer[length++] = value.toByte()
        }
        return ExternalFile.Read(buffer.copyOf(length))
    } finally {
        fclose(file)
    }
}

private const val INITIAL_CAPACITY = 64 * 1024

/** One word per native family; the exact target is the build knows-better detail. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual val playerPlatformName: String = kotlin.native.Platform.osFamily.name.lowercase()
