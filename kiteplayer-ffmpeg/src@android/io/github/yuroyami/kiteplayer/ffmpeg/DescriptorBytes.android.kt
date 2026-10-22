package io.github.yuroyami.kiteplayer.ffmpeg

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.yuroyami.kiteffmpeg.MediaByteSource
import java.io.IOException

internal actual fun descriptorByteSource(descriptor: Int): MediaByteSource? {
    // fromFd duplicates the descriptor, and the duplicate is the one this reader owns.
    val own = try {
        ParcelFileDescriptor.fromFd(descriptor)
    } catch (_: IOException) {
        return null
    }
    val status = try {
        Os.fstat(own.fileDescriptor)
    } catch (_: ErrnoException) {
        null
    }
    if (status == null || !OsConstants.S_ISREG(status.st_mode)) {
        own.close()
        return null
    }
    return AndroidDescriptorBytes(own, status.st_size)
}

/** Positional reads with `pread` over [own], which this reader closes. */
private class AndroidDescriptorBytes(private val own: ParcelFileDescriptor, override val size: Long) : MediaByteSource {
    override val seekable: Boolean get() = true

    private var position = 0L

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        // Os.pread retries an interrupted call itself and throws for a real failure.
        val count = Os.pread(own.fileDescriptor, into, offset, length, position)
        if (count <= 0) return -1
        position += count
        return count
    }

    override fun seek(position: Long) {
        this.position = position
    }

    override fun close() {
        own.close()
    }
}
