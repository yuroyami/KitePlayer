package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Plays [file]. Each open reads through its own channel, so two sessions never share a position. */
public fun MediaIo.Companion.ofFile(file: File): MediaIoFactory = ofPath(file.toPath())

/** Plays the file at [path]. Each open reads through its own channel, so two sessions never share a position. */
public fun MediaIo.Companion.ofPath(path: Path): MediaIoFactory = MediaIoFactory {
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    try {
        FileChannelMediaIo(channel, owner = channel)
    } catch (failure: Throwable) {
        channel.close()
        throw failure
    }
}

/**
 * Plays from a [channel] you already have. Reads are positional, so the position of [channel]
 * never moves, and no reader closes it. You own [channel]: keep it open while a reader from this
 * factory is open, and close it yourself.
 */
public fun MediaIo.Companion.ofChannel(channel: FileChannel): MediaIoFactory = MediaIoFactory {
    FileChannelMediaIo(channel, owner = null)
}

/**
 * Positional reads over the [length] bytes that start at [start] in [channel], or, with no
 * [length], over the whole channel from [start] as it stands at each call, so a file that is still
 * being written reads on as it grows and says how large it is now (#430), as FFmpeg's own file
 * reader does. A window's size is fixed. Closing the reader closes [owner], which is null when the
 * caller owns the channel.
 */
internal class FileChannelMediaIo(
    private val channel: FileChannel,
    private val owner: AutoCloseable?,
    private val start: Long = 0,
    private val length: Long? = null,
) : MediaIo {
    init {
        require(start >= 0 && (length == null || length >= 0)) { "The window at $start of $length bytes is not valid" }
    }

    override val size: Long get() = length ?: (channel.size() - start).coerceAtLeast(0L)
    override val seekable: Boolean get() = true

    private var position = 0L

    @Volatile
    private var closed = false

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "MediaIo is closed" }
        requireReadSlice(into, offset, length)
        if (length == 0) return 0
        val size = size
        if (position >= size) return -1
        val want = minOf(length.toLong(), size - position).toInt()
        val count = channel.read(ByteBuffer.wrap(into, offset, want), start + position)
        if (count <= 0) return -1
        position += count
        return count
    }

    override suspend fun seek(position: Long) {
        check(!closed) { "MediaIo is closed" }
        val size = size
        require(position in 0L..size) { "Seek position $position is outside 0..$size" }
        this.position = position
    }

    override fun close() {
        if (closed) return
        closed = true
        owner?.close()
    }
}
