// off_t, size_t and ssize_t differ in width between these targets, and every use below converts them.
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.O_RDONLY
import platform.posix.SEEK_CUR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.close
import platform.posix.getenv
import platform.posix.lseek
import platform.posix.open
import platform.posix.pipe
import platform.posix.read
import platform.posix.signal
import platform.posix.write
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An item that names a descriptor with the `fd` open option.
 *
 * The caller keeps that descriptor, and every duplicate of it shares one file offset with it. A
 * regular file is read by position, so no open, read or seek of the item moves that offset. A pipe
 * is a stream, which has no offset to keep, and it plays as one.
 */
class DescriptorItemTest {

    private val mediaDir: String = getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"
    private val descriptors = mutableListOf<Int>()

    @AfterTest
    fun cleanup() {
        descriptors.forEach { close(it) }
        descriptors.clear()
    }

    private fun openFixture(name: String): Int {
        val descriptor = open("$mediaDir/$name", O_RDONLY)
        assertTrue(descriptor >= 0, "the fixture $name must open. Run scripts/testmedia.sh first.")
        descriptors += descriptor
        return descriptor
    }

    private fun offsetOf(descriptor: Int): Long = lseek(descriptor, 0.convert(), SEEK_CUR).convert()

    private fun descriptorItem(descriptor: Int) =
        MediaItem(uri = "fd:", openOptions = mapOf("fd" to descriptor.toString()))

    @Test
    fun `the same descriptor opens a second time after the first open consumed it`() = runBlocking {
        // A track change rebuilds the container from the same item, which is exactly this shape.
        val item = descriptorItem(openFixture("sync1080p30.mp4"))

        val first = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            assertTrue(first.streams.any { it.kind == TrackKind.Video }, "the first open sees the video stream")
            first.selectStreams(setOf(first.streams.first { it.kind == TrackKind.Video }.index))
            repeat(20) { first.readPacket()?.close() ?: return@repeat }
        } finally {
            first.close()
        }

        val second = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            assertTrue(
                second.streams.any { it.kind == TrackKind.Video },
                "the second open must see the same container, not bytes from the middle of the file",
            )
        } finally {
            second.close()
        }
    }

    @Test
    fun `two opens that read and seek leave the offset where the caller put it`() = runBlocking {
        val descriptor = openFixture("sync1080p30.mp4")
        lseek(descriptor, CALLER_OFFSET.convert(), SEEK_SET)
        val item = descriptorItem(descriptor)

        repeat(2) { round ->
            val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
            try {
                assertTrue(source.seekable, "a regular file seeks")
                source.selectStreams(setOf(source.streams.first { it.kind == TrackKind.Video }.index))
                repeat(20) { source.readPacket()?.close() }
                source.seekToKeyframe(Pts(5_000_000))
                repeat(20) { source.readPacket()?.close() }
                assertEquals(CALLER_OFFSET, offsetOf(descriptor), "open ${round + 1} moved the offset of the caller")
            } finally {
                source.close()
            }
            assertEquals(CALLER_OFFSET, offsetOf(descriptor), "closing open ${round + 1} moved the offset of the caller")
        }
    }

    @Test
    fun `a pipe plays as a stream`() = runBlocking {
        val bytes = readFixture("colors-gbr.mkv")
        val (readEnd, writeEnd) = memScoped {
            val ends = allocArray<IntVar>(2)
            check(pipe(ends) == 0) { "pipe failed" }
            ends[0] to ends[1]
        }
        descriptors += readEnd
        // A writer blocked on a full pipe gets an error instead of a signal when the read side
        // closes first, so a failed open fails this test and does not stop the whole binary.
        signal(SIGPIPE, SIG_IGN)
        val writer = launch(Dispatchers.Default) {
            bytes.usePinned { pinned ->
                var done = 0
                while (done < bytes.size) {
                    val count: Long = write(writeEnd, pinned.addressOf(done), (bytes.size - done).convert()).convert()
                    if (count <= 0) break
                    done += count.toInt()
                }
            }
            close(writeEnd)
        }
        try {
            val source = KiteFFmpegSourceFactory().open(descriptorItem(readEnd)) as KiteFFmpegSource
            try {
                assertFalse(source.seekable, "a pipe cannot seek")
                source.selectStreams(setOf(source.streams.first { it.kind == TrackKind.Video }.index))
                var packets = 0
                while (true) {
                    val packet = source.readPacket() ?: break
                    packet.close()
                    packets++
                }
                assertEquals(2, packets, "the stream must deliver both frames of the fixture")
            } finally {
                source.close()
            }
        } finally {
            descriptors.remove(readEnd)
            close(readEnd)
            writer.join()
        }
    }

    private fun readFixture(name: String): ByteArray {
        val descriptor = openFixture(name)
        val size: Long = lseek(descriptor, 0.convert(), SEEK_END).convert()
        lseek(descriptor, 0.convert(), SEEK_SET)
        val bytes = ByteArray(size.toInt())
        bytes.usePinned { pinned ->
            var done = 0
            while (done < bytes.size) {
                val count: Long = read(descriptor, pinned.addressOf(done), (bytes.size - done).convert()).convert()
                check(count > 0) { "short read on $name at byte $done" }
                done += count.toInt()
            }
        }
        return bytes
    }

    private companion object {
        /** Somewhere the caller left its offset, away from zero so a rewind is caught too. */
        const val CALLER_OFFSET = 4321L
    }
}
