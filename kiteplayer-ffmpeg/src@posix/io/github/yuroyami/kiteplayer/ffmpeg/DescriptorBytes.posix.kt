// off_t, size_t, ssize_t and mode_t differ in width between these targets, and every use below converts them.
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaByteSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.F_DUPFD_CLOEXEC
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.pread
import platform.posix.stat
import platform.posix.strerror

internal actual fun descriptorByteSource(descriptor: Int): MediaByteSource? {
    val own = fcntl(descriptor, F_DUPFD_CLOEXEC, 0)
    if (own < 0) return null
    val size: Long? = memScoped {
        val status = alloc<stat>()
        val regular = fstat(own, status.ptr) == 0 && (status.st_mode.convert<Int>() and S_IFMT) == S_IFREG
        if (regular) status.st_size.convert<Long>() else null
    }
    if (size == null) {
        close(own)
        return null
    }
    return PosixDescriptorBytes(own, size)
}

/** Positional reads with `pread` over [descriptor], which this reader owns and closes. */
private class PosixDescriptorBytes(private val descriptor: Int, override val size: Long) : MediaByteSource {
    override val seekable: Boolean get() = true

    private var position = 0L

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val count: Long = into.usePinned { pinned ->
                pread(descriptor, pinned.addressOf(offset), length.convert(), position.convert()).convert()
            }
            if (count > 0) {
                position += count
                return count.toInt()
            }
            if (count == 0L) return -1
            if (errno != EINTR) error("Read failed at byte $position: ${strerror(errno)?.toKString() ?: "errno $errno"}")
        }
    }

    override fun seek(position: Long) {
        this.position = position
    }

    override fun close() {
        close(descriptor)
    }
}
