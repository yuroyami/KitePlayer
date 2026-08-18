@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MediaItem.io end to end (M1): a real container's bytes flow through a SUSPENDING MediaIo
 * into the FFmpeg backend, with no path and no FFmpeg protocol. The suspension is real
 * (yield before every read), so this also proves the blocking adapter parks the demux thread
 * correctly instead of deadlocking or dropping the continuation.
 */
class MediaIoBridgeTest {

    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    private fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("cannot open $path. Run scripts/testmedia.sh first.")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, 0)
            val bytes = ByteArray(size)
            bytes.usePinned { pinned ->
                val read = fread(pinned.addressOf(0), 1uL, size.toULong(), file)
                check(read.toInt() == size) { "short read on $path: $read of $size" }
            }
            return bytes
        } finally {
            fclose(file)
        }
    }

    private class SuspendingMemoryIo(private val bytes: ByteArray) : MediaIo {
        var position = 0
        var reads = 0
        var closed = false
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            yield()  // a genuine suspension point on every call
            reads++
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }
        override suspend fun seek(position: Long) {
            yield()
            this.position = position.toInt()
        }
        override fun close() { closed = true }
    }

    @Test
    fun `a media item whose bytes come from MediaIo opens demuxes and closes`() = runBlocking {
        val io = SuspendingMemoryIo(readFile("$mediaDir/subbed.mkv"))
        // A factory, because the item carries one now (audit KP-P1-03). This test keeps a handle
        // on the reader it makes so it can assert the bridge read through it and closed it.
        val session = KiteCodecSourceFactory().open(MediaItem("mem://subbed.mkv", io = { io }))
        val source = session as KiteCodecSource
        try {
            assertTrue(source.streams.isNotEmpty(), "no streams demuxed through MediaIo")
            assertTrue(
                source.streams.any { it.kind == TrackKind.Video },
                "the mkv's video stream did not surface through MediaIo",
            )
            source.selectStreams(source.streams.map { it.index }.toSet())
            val packet = source.readPacket()
            assertTrue(packet != null, "no packet arrived through MediaIo")
            (packet as? AutoCloseable)?.close()
            assertTrue(io.reads > 0, "no read ever reached the MediaIo: the bridge is dead")
        } finally {
            source.close()
        }
        assertTrue(io.closed, "closing the source must close the MediaIo it owns")
    }
}
