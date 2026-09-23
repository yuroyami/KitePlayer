@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.cinterop.toKString
import platform.posix.getenv

// TMPDIR on Apple and Linux, TEMP on Windows.
internal actual fun recordingScratchPath(name: String): String {
    val dir = getenv("TMPDIR")?.toKString() ?: getenv("TEMP")?.toKString() ?: "/tmp"
    return "${dir.trimEnd('/', '\\')}/$name"
}
