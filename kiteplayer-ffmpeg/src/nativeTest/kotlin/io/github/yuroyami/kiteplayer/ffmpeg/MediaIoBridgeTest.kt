@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private class SuspendingMemoryIo(private val delegate: MediaIo) : MediaIo by delegate {
        var reads = 0
        var closed = false
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            yield()  // a genuine suspension point on every call
            reads++
            return delegate.read(into, offset, length)
        }
        override suspend fun seek(position: Long) {
            yield()
            delegate.seek(position)
        }
        override fun close() { closed = true; delegate.close() }
    }

    @Test
    fun `a media item whose bytes come from MediaIo opens demuxes and closes`() = runBlocking {
        val io = SuspendingMemoryIo(MediaIo.ofBytes(readFile("$mediaDir/subbed.mkv")).open())
        // A factory, because the item carries one now. This test keeps a handle
        // on the reader it makes so it can assert the bridge read through it and closed it.
        val session = KiteFFmpegSourceFactory().open(MediaItem("mem://subbed.mkv", io = { io }))
        val source = session as KiteFFmpegSource
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

    @Test
    fun `byte array input decodes the same subtitled media as disk`() = runBlocking {
        val path = "$mediaDir/subbed.mkv"
        val factory = MediaIo.ofBytes(readFile(path))
        val disk = KiteFFmpegSourceFactory().open(MediaItem(path)) as KiteFFmpegSource
        val backend = KiteFFmpegMediaBackend().open(MediaItem.from(factory, "subbed.mkv"))
        try {
            val source = backend.source
            assertEquals(disk.streams, source.streams)
            assertTrue(source.streams.any { it.kind == TrackKind.Subtitle })
            val video = source.streams.first { it.kind == TrackKind.Video }
            val audio = source.streams.first { it.kind == TrackKind.Audio }
            val decoder = assertNotNull(backend.videoDecoders.first().create(video, HwdecPolicy.Off))
            val audioDecoder = assertNotNull(backend.audioDecoders.first().create(audio))
            try {
                source.selectStreams(setOf(video.index, audio.index))
                var frames = 0
                var buffers = 0
                while (true) {
                    val packet = source.readPacket() ?: break
                    packet.use {
                        if (packet.streamIndex == video.index) {
                            assertTrue(decoder.send(packet))
                            while (true) {
                                val frame = decoder.receive() ?: break
                                frame.close()
                                frames++
                            }
                        } else {
                            assertTrue(audioDecoder.send(packet))
                            while (true) {
                                val buffer = audioDecoder.receive() ?: break
                                buffer.close()
                                buffers++
                            }
                        }
                    }
                }
                assertTrue(frames >= 290, "only $frames video frames decoded")
                assertTrue(buffers > 100, "only $buffers audio buffers decoded")
            } finally {
                audioDecoder.close()
                decoder.close()
            }
        } finally {
            backend.close()
            disk.close()
        }
    }
}
