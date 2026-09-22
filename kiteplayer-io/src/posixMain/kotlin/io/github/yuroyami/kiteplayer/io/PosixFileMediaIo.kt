// size_t, ssize_t and mode_t differ in width between these targets, and every use below converts them.
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.fstat
import platform.posix.pread
import platform.posix.stat
import platform.posix.strerror
import kotlin.concurrent.Volatile

/**
 * Plays the file at [path]. Each open opens the file itself and reads it by position, so two
 * sessions never share a position. `MediaItem(path)` also plays a plain path, through FFmpeg's own
 * file reader. This door is for code that wants every read to pass through Kotlin.
 */
public fun MediaIo.Companion.ofPath(path: String): MediaIoFactory = MediaIoFactory { PosixFileMediaIo.open(path) }

/** Positional reads with `pread` over a descriptor that this reader opened and owns. */
internal class PosixFileMediaIo private constructor(
    internal val descriptor: Int,
    override val size: Long,
) : MediaIo {
    override val seekable: Boolean get() = true

    private var position = 0L

    @Volatile
    private var closed = false

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "MediaIo is closed" }
        requireReadSlice(into, offset, length)
        if (length == 0) return 0
        if (position >= size) return -1
        val want = minOf(length.toLong(), size - position).toInt()
        val count: Long = into.usePinned { pinned ->
            pread(descriptor, pinned.addressOf(offset), want.convert(), position.convert()).convert()
        }
        if (count < 0) throw MediaIoException("Read failed at byte $position: ${lastError()}")
        if (count == 0L) return -1
        position += count
        return count.toInt()
    }

    override suspend fun seek(position: Long) {
        check(!closed) { "MediaIo is closed" }
        require(position in 0L..size) { "Seek position $position is outside 0..$size" }
        this.position = position
    }

    override fun close() {
        if (closed) return
        closed = true
        platform.posix.close(descriptor)
    }

    companion object {
        fun open(path: String): PosixFileMediaIo {
            val descriptor = platform.posix.open(path, O_RDONLY)
            if (descriptor < 0) throw MediaIoException("Cannot open $path: ${lastError()}")
            try {
                return memScoped {
                    val status = alloc<stat>()
                    if (fstat(descriptor, status.ptr) != 0) throw MediaIoException("Cannot read the size of $path: ${lastError()}")
                    // A directory opens read-only without an error, and fails only at the first read.
                    if ((status.st_mode.convert<Int>() and S_IFMT) != S_IFREG) throw MediaIoException("Not a regular file: $path")
                    PosixFileMediaIo(descriptor, status.st_size.convert())
                }
            } catch (failure: Throwable) {
                platform.posix.close(descriptor)
                throw failure
            }
        }

        private fun lastError(): String = strerror(errno)?.toKString() ?: "errno $errno"
    }
}
