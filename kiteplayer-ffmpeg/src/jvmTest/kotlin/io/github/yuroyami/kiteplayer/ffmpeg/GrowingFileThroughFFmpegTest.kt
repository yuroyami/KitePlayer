package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.FileGrowth
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A transport stream that is still being written while FFmpeg reads it (#430). The first third of
 * the ten second `tsoffset1400.ts` exists at the open, and the rest arrives in pieces. Marked as
 * growing, the source reads past the size the file had at the open and ends one wait after the
 * writer stops, a seek reaches the part written after the open without waiting at the end, and the
 * length follows the file. Not marked, the same file ends where it stood at the open.
 */
class GrowingFileThroughFFmpegTest {

    private val clip = File(System.getenv("KITEPLAYER_TESTMEDIA") ?: "testmedia", "tsoffset1400.ts")
    private val written = File.createTempFile("growing", ".ts")

    @AfterTest
    fun cleanup() {
        written.delete()
    }

    /** The clip's first third, cut on a transport packet, as the file stands at the open. */
    private fun startRecording(): ByteArray {
        assertTrue(clip.isFile, "the fixture is missing at ${clip.absolutePath}; run scripts/testmedia.sh")
        val bytes = clip.readBytes()
        written.writeBytes(bytes.copyOf(bytes.size / 3 / TS_PACKET * TS_PACKET))
        return bytes
    }

    /** Appends the rest of [bytes] in eight pieces, one every 150 ms, and answers when it wrote the last. */
    private fun writeTheRest(bytes: ByteArray): () -> TimeSource.Monotonic.ValueTimeMark? {
        var finished: TimeSource.Monotonic.ValueTimeMark? = null
        val writer = thread {
            val from = written.length().toInt()
            val piece = (bytes.size - from) / 8 + 1
            var at = from
            while (at < bytes.size) {
                Thread.sleep(150)
                val end = minOf(bytes.size, at + piece)
                written.appendBytes(bytes.copyOfRange(at, end))
                at = end
            }
            finished = TimeSource.Monotonic.markNow()
        }
        return {
            writer.join()
            finished
        }
    }

    private fun open(growth: FileGrowth?): PlayerMediaSource = runBlocking {
        KiteFFmpegSourceFactory().open(MediaItem(written.path, io = { FileReader(written) }, growth = growth)).also { source ->
            source.selectStreams(source.streams.map { it.index }.toSet())
        }
    }

    /** The time of the last picture read before the end, in seconds of the content. */
    private suspend fun PlayerMediaSource.readToTheEnd(): Double {
        val video = streams.first { it.kind == TrackKind.Video }.index
        var last = 0.0
        while (true) {
            val packet = readPacket() ?: return last
            packet.use { if (it.streamIndex == video) it.pts?.let { pts -> last = maxOf(last, pts.micros / 1e6) } }
        }
    }

    @Test
    fun aGrowingFileIsReadPastItsSizeAtTheOpenAndEndsOneWaitAfterTheWriterStops() = runBlocking {
        val bytes = startRecording()
        val source = open(FileGrowth(endsAfter = 1.seconds))
        try {
            assertTrue(source.durationIsEstimate, "the length of a file still being written is an estimate")
            val atOpen = assertNotNull(source.duration).micros / 1e6
            assertTrue(atOpen < 5.0, "the open saw $atOpen s, more than the first third")
            val writerDone = writeTheRest(bytes)
            val last = source.readToTheEnd()
            val ended = TimeSource.Monotonic.markNow()
            val stopped = assertNotNull(writerDone())
            assertTrue(last > 9.0, "playback stopped at $last s, where the file stood at the open ($atOpen s)")
            val after = ended - stopped
            assertTrue(after >= 0.9.seconds && after < 3.seconds, "the end came ${after.inWholeMilliseconds} ms after the writer stopped")
            val grown = assertNotNull(source.duration).micros / 1e6
            assertTrue(grown > 8.0, "the length stayed at $grown s")
        } finally {
            source.close()
        }
    }

    @Test
    fun aSeekReachesThePartWrittenAfterTheOpenWithoutWaitingAtTheEnd() = runBlocking {
        val bytes = startRecording()
        val source = open(FileGrowth(endsAfter = 2.seconds))
        try {
            writeTheRest(bytes)()
            val started = TimeSource.Monotonic.markNow()
            source.seekToKeyframe(Pts(7_000_000))
            val took = started.elapsedNow()
            assertTrue(took < 1.seconds, "the seek waited ${took.inWholeMilliseconds} ms at the end of the file")
            val video = source.streams.first { it.kind == TrackKind.Video }.index
            var landed: Double? = null
            while (landed == null) {
                val packet = assertNotNull(source.readPacket(), "the file ended right after the seek")
                packet.use { if (it.streamIndex == video) landed = it.pts?.micros?.div(1e6) }
            }
            assertTrue(landed!! > 5.5, "the seek to 7 s landed at $landed s, inside the part the open saw")
        } finally {
            source.close()
        }
    }

    @Test
    fun theSameFileNotMarkedEndsWhereItStoodAtTheOpen() = runBlocking {
        val bytes = startRecording()
        val source = open(growth = null)
        try {
            assertFalse(source.durationIsEstimate)
            val writerDone = writeTheRest(bytes)
            val last = source.readToTheEnd()
            writerDone()
            assertTrue(last < 5.0, "a complete file read on to $last s")
        } finally {
            source.close()
        }
    }

    /** A plain reader over [file] that says how large it is now, as the file doors do. */
    private class FileReader(file: File) : MediaIo {
        private val raf = RandomAccessFile(file, "r")
        override val size: Long get() = raf.length()
        override val seekable: Boolean get() = true

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = raf.read(into, offset, length)

        override suspend fun seek(position: Long) = raf.seek(position)

        override fun close() = raf.close()
    }

    private companion object {
        const val TS_PACKET = 188
    }
}
