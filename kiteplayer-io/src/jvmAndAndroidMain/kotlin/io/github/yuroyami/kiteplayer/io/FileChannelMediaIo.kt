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
        FileChannelMediaIo(channel, ownsChannel = true)
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
    FileChannelMediaIo(channel, ownsChannel = false)
}

/** Positional reads over [channel]. The size is read once, when the reader opens. */
internal class FileChannelMediaIo(
    private val channel: FileChannel,
    private val ownsChannel: Boolean,
) : MediaIo {
    override val size: Long = channel.size()
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
        val count = channel.read(ByteBuffer.wrap(into, offset, want), position)
        if (count <= 0) return -1
        position += count
        return count
    }

    override suspend fun seek(position: Long) {
        check(!closed) { "MediaIo is closed" }
        require(position in 0L..size) { "Seek position $position is outside 0..$size" }
        this.position = position
    }

    override fun close() {
        if (closed) return
        closed = true
        if (ownsChannel) channel.close()
    }
}
