package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * When a radio station's server closes the connection, the network reader connects again and the
 * new answer starts at the live edge, which is somewhere inside an MP3 frame rather than where the
 * last answer stopped (#508). The demuxer and the decoder must find the next frame by themselves
 * and play on. Here the reader jumps from 40 percent of the file to just past 60 percent, at a byte
 * no frame starts at.
 */
class LiveSpliceTest {

    @Test
    fun anMp3StreamPlaysOnPastAJumpToTheMiddleOfAFrame() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val file = checkNotNull(readTestFile("$mediaDir/audio-mp3.mp3")) { "testmedia is missing; run scripts/testmedia.sh" }
        val before = file.copyOfRange(0, file.size * 4 / 10)
        val after = file.copyOfRange(file.size * 6 / 10 + 7, file.size)
        val whole = decode(file)
        val spliced = decode(before + after)
        val beforeOnly = decode(before)

        // 6 s of 1152-sample frames at 44.1 kHz is 230 frames. The splice drops a fifth of the file.
        assertTrue(whole.packets >= 220, "the whole file gave only ${whole.packets} packets")
        assertTrue(
            spliced.packets >= beforeOnly.packets + (whole.packets * 3 / 10),
            "the stream stopped at the jump: ${spliced.packets} packets, ${beforeOnly.packets} before it",
        )
        assertTrue(
            spliced.frames >= beforeOnly.frames + whole.frames * 3 / 10,
            "the decoder stopped at the jump: ${spliced.frames} sample frames, ${beforeOnly.frames} before it",
        )
    }

    private class Decoded(val packets: Int, val frames: Long)

    /** Every audio packet and decoded sample frame of [bytes], read as a stream with no length and no seeking. */
    private suspend fun decode(bytes: ByteArray): Decoded {
        val item = MediaItem("http://radio.test/live", io = { Stream(bytes) })
        val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Audio })
            source.selectStreams(setOf(stream.index))
            val decoder = source.newAudioDecoder(stream)
            var packets = 0
            var frames = 0L
            try {
                suspend fun drain() {
                    while (true) {
                        val buffer = decoder.receive() ?: break
                        frames += buffer.frameCount
                        buffer.close()
                    }
                }
                while (true) {
                    val packet = source.readPacket() ?: break
                    try {
                        if (packet.streamIndex == stream.index) {
                            packets++
                            while (!decoder.send(packet)) drain()
                        }
                    } finally {
                        packet.close()
                    }
                    drain()
                }
                decoder.send(null)
                drain()
            } finally {
                decoder.close()
            }
            return Decoded(packets, frames)
        } finally {
            source.close()
        }
    }

    /** Bytes as a station sends them: no size, no seeking. */
    private class Stream(private val bytes: ByteArray) : MediaIo {
        private var position = 0
        override val size: Long? get() = null
        override val seekable: Boolean get() = false
        override val contentType: String get() = "audio/mpeg"

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= bytes.size) return -1
            // A few kilobytes at a time, as a network delivers them.
            val count = minOf(length, bytes.size - position, 4_096)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun seek(position: Long) = error("a stream cannot seek")

        override fun close() {}
    }
}
