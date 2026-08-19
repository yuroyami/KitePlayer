package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.SEEK_SET
import platform.posix.lseek

/**
 * The POSIX rewind. Deliberately NOT in `nativeMain`, and this file has a twin for that reason.
 *
 * `lseek` takes and returns `off_t`, which is 64 bit here and 32 bit on mingw, and a shared native
 * source set cannot type a declaration whose width differs between its targets. Per-target compiles
 * never noticed; only `compileNativeMainKotlinMetadata`, the klib a publication is built from, did.
 * Windows gets the documented no-op instead.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun rewindFdOption(options: Map<String, String>) {
    val fd = options["fd"]?.toIntOrNull() ?: return
    // An unseekable descriptor answers -1 with ESPIPE, which is the streamed case and fine.
    lseek(fd, 0, SEEK_SET)
}
