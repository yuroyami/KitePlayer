package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.PlaybackWarning
import kotlinx.coroutines.delay

/**
 * [upstream] with its first bytes already read into [head], which the reads give back before
 * anything else. Reading them is how a playlist that nothing marks is recognised (#400), and
 * giving them back is what keeps that look free: the media is not opened a second time, and a
 * reader that cannot seek loses nothing.
 */
internal class SniffedMediaIo private constructor(
    private val upstream: MediaIo,
    /** The first bytes of [upstream], all of them when there were fewer than asked for. */
    val head: ByteArray,
) : MediaIo {

    /** Where the next read starts, in the bytes of [upstream]. */
    private var position = 0L

    /** Where [upstream] stands, which is past [head] until a seek moves it. */
    private var upstreamPosition = head.size.toLong()

    override val size: Long? get() = upstream.size
    override val seekable: Boolean get() = upstream.seekable
    override val location: String? get() = upstream.location
    override val contentType: String? get() = upstream.contentType

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (position < head.size) {
            val count = minOf(length.toLong(), head.size - position).toInt()
            head.copyInto(into, offset, position.toInt(), position.toInt() + count)
            position += count
            return count
        }
        if (upstreamPosition != position) {
            upstream.seek(position)
            upstreamPosition = position
        }
        val count = upstream.read(into, offset, length)
        if (count > 0) {
            position += count
            upstreamPosition += count
        }
        return count
    }

    /** Lazy: a target inside [head] needs no upstream seek, and one past it moves [upstream] at the next read. */
    override suspend fun seek(position: Long) {
        this.position = position
    }

    override fun setWarningSink(sink: (PlaybackWarning) -> Unit) = upstream.setWarningSink(sink)

    override suspend fun openRelated(uri: String): MediaIo? = upstream.openRelated(uri)

    override fun networkBitsPerSecond(): Long? = upstream.networkBitsPerSecond()

    override fun close() = upstream.close()

    companion object {
        /**
         * [io] with up to [count] of its first bytes read. Fewer arrive only when the media is
         * shorter. A reader that has nothing yet is asked again, as the backend would ask it.
         */
        suspend fun sniff(io: MediaIo, count: Int): SniffedMediaIo {
            val head = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val read = io.read(head, filled, count - filled)
                when {
                    read < 0 -> break
                    read == 0 -> delay(1)
                    else -> filled += read
                }
            }
            return SniffedMediaIo(io, if (filled == count) head else head.copyOf(filled))
        }
    }
}
