package io.github.yuroyami.kiteplayer.ffmpeg

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An item that names a descriptor with the `fd` open option, on Android, where a picked
 * `content://` file often arrives as such a descriptor.
 *
 * The same checks as the Apple and Linux test: a regular file is read by position, so no open
 * moves the offset of the caller, and a pipe plays as a stream.
 */
internal class DescriptorItemDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun descriptorItem(descriptor: ParcelFileDescriptor) =
        MediaItem(uri = "fd:", openOptions = mapOf("fd" to descriptor.fd.toString()))

    private fun offsetOf(descriptor: ParcelFileDescriptor): Long =
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)

    @Test
    fun twoOpensThatReadAndSeekLeaveTheOffsetWhereTheCallerPutIt() = runBlocking {
        val file = AndroidMedia.H264.materialize(context)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            Os.lseek(descriptor.fileDescriptor, CALLER_OFFSET, OsConstants.SEEK_SET)
            val item = descriptorItem(descriptor)

            repeat(2) { round ->
                val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
                try {
                    assertTrue(source.seekable, "a regular file seeks")
                    source.selectStreams(setOf(source.streams.first { it.kind == TrackKind.Video }.index))
                    repeat(6) { source.readPacket()?.close() }
                    source.seekToKeyframe(Pts(200_000))
                    repeat(6) { source.readPacket()?.close() }
                    assertEquals(CALLER_OFFSET, offsetOf(descriptor), "open ${round + 1} moved the offset of the caller")
                } finally {
                    source.close()
                }
                assertEquals(CALLER_OFFSET, offsetOf(descriptor), "closing open ${round + 1} moved the offset of the caller")
            }
        }
    }

    @Test
    fun aPipePlaysAsAStream() = runBlocking {
        val bytes = AndroidMedia.H264.bytes()
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        // The writer fills the pipe while the source drains it, so the fixture need not fit in it.
        // A failed open closes the read side first, and the write then fails instead of hanging.
        val writer = thread(name = "pipe writer") {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(bytes) } }
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
                assertEquals(12, packets, "the stream must deliver every frame of the fixture")
            } finally {
                source.close()
            }
        } finally {
            readEnd.close()
            writer.join()
        }
    }

    private companion object {
        /** Somewhere the caller left its offset, away from zero so a rewind is caught too. */
        const val CALLER_OFFSET = 1234L
    }
}
