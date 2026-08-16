@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.internal

import platform.posix.EOF
import platform.posix.fclose
import platform.posix.fgetc
import platform.posix.fopen

internal actual fun readExternalTextOrNull(path: String): String? {
    // fgetc and nothing else, deliberately. fseek, ftell and fread all speak platform-width
    // numbers (long, size_t), and this file compiles in the intermediate nativeMain source set,
    // which spans 32-bit watch targets beside the 64-bit world; the compiler rightly refuses
    // width-varying signatures there. fgetc returns Int on every libc that exists, so the
    // portable spelling is a byte loop into a growing buffer. A subtitle file is small text and
    // is read once per selection; this is not a hot path.
    val file = fopen(path, "rb") ?: return null
    try {
        var buffer = ByteArray(INITIAL_CAPACITY)
        var length = 0
        while (true) {
            val value = fgetc(file)
            if (value == EOF) break
            if (length == buffer.size) {
                if (length >= MAX_EXTERNAL_SUBTITLE_BYTES) return null
                buffer = buffer.copyOf((buffer.size * 2).coerceAtMost(MAX_EXTERNAL_SUBTITLE_BYTES))
            }
            buffer[length++] = value.toByte()
        }
        return buffer.decodeToString(0, length)
    } finally {
        fclose(file)
    }
}

private const val INITIAL_CAPACITY = 64 * 1024

/** A subtitle file is text; anything past this bound is not one. */
private const val MAX_EXTERNAL_SUBTITLE_BYTES = 32 * 1024 * 1024

/** One word per native family; the exact target is the build knows-better detail. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual val playerPlatformName: String = kotlin.native.Platform.osFamily.name.lowercase()
