package io.github.yuroyami.kiteplayer

/**
 * Plays bytes already in memory. Each open has its own cursor and close state, with no array copy.
 * Keep [bytes] unchanged while any reader is open. Size is known and positions from zero through
 * the array size are seekable. Invalid slices or positions throw [IllegalArgumentException]; reads
 * and seeks after close throw [IllegalStateException]. A zero-length read returns zero, even at EOF.
 */
public fun MediaIo.Companion.ofBytes(bytes: ByteArray): MediaIoFactory =
    MediaIoFactory { ByteArrayMediaIo(bytes) }

/** An item read through [io], with [label] used as its URI for probing, logs and display. */
public fun MediaItem.Companion.from(io: MediaIoFactory, label: String): MediaItem =
    MediaItem(uri = label, io = io)

private class ByteArrayMediaIo(private val bytes: ByteArray) : MediaIo {
    private var position = 0
    private var closed = false
    override val size: Long get() = bytes.size.toLong()
    override val seekable: Boolean get() = true

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "MediaIo is closed" }
        require(offset in 0..into.size && length >= 0 && length <= into.size - offset) {
            "Read slice is outside the destination array"
        }
        if (length == 0) return 0
        if (position == bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(into, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun seek(position: Long) {
        check(!closed) { "MediaIo is closed" }
        require(position in 0L..size) { "Seek position $position is outside 0..$size" }
        this.position = position.toInt()
    }

    override fun close() { closed = true }
}
