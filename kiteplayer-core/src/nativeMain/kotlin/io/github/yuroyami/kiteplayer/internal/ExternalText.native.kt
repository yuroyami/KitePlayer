@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.internal

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

internal actual fun readExternalTextOrNull(path: String): String? {
    val file = fopen(path, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size < 0L || size > MAX_EXTERNAL_SUBTITLE_BYTES) return null
        if (fseek(file, 0, SEEK_SET) != 0) return null
        val bytes = ByteArray(size.toInt())
        if (bytes.isEmpty()) return ""
        val read = bytes.usePinned { pinned ->
            // size_t differs across the native targets; convert() speaks each one's own width.
            fread(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toLong()
        }
        if (read != size.toLong()) return null
        return bytes.decodeToString()
    } finally {
        fclose(file)
    }
}

/** A subtitle file is text; anything past this bound is not one. */
private const val MAX_EXTERNAL_SUBTITLE_BYTES = 32L * 1024 * 1024

/** One word per native family; the exact target is the build knows-better detail. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual val playerPlatformName: String = kotlin.native.Platform.osFamily.name.lowercase()
