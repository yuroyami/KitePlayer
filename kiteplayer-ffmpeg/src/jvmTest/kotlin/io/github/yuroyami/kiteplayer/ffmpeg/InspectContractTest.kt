package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.inspect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** inspect types its failures as the player does and never blocks the caller's thread (#202). */
class InspectContractTest {

    @Test
    fun aMissingFileFailsAsAPlaybackExceptionNotTheMediaLibrarysType() = runBlocking<Unit> {
        val failure = runCatching { inspect(MediaItem("/definitely/missing.mkv"), KiteFFmpegMediaBackend()) }
            .exceptionOrNull()
        val typed = assertIs<PlaybackException>(failure, "was $failure")
        assertIs<PlaybackError.SourceUnavailable>(typed.error)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aSlowReaderLeavesTheCallersThreadFree() = runBlocking<Unit> {
        val bytes = wav(sampleRate = 8_000, seconds = 0.5)
        val item = MediaItem("slow.wav", io = { SlowIo(bytes) })
        val caller = Dispatchers.Default.limitedParallelism(1)
        var ticks = 0
        withContext(caller) {
            val ticker = launch { while (true) { ticks++; delay(10) } }
            val seen = inspect(item, KiteFFmpegMediaBackend())
            ticker.cancel()
            assertTrue(seen.tracks.all.isNotEmpty(), "the WAV must be read")
        }
        assertTrue(ticks >= 20, "the caller's thread must stay free while the reader waits, ticked $ticks times")
    }

    /** Every read waits 150 ms and hands over at most 1,024 bytes. */
    private class SlowIo(private val bytes: ByteArray) : MediaIo {
        private var position = 0
        override val size: Long = bytes.size.toLong()
        override val seekable: Boolean = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            delay(150)
            if (position >= bytes.size) return -1
            val count = minOf(length, 1_024, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }
        override suspend fun seek(position: Long) {
            this.position = position.toInt()
        }
        override fun close() {}
    }

    /** A mono 16-bit PCM WAV of silence. */
    private fun wav(sampleRate: Int, seconds: Double): ByteArray {
        val samples = (sampleRate * seconds).toInt()
        val data = samples * 2
        val out = java.io.ByteArrayOutputStream()
        fun int(value: Int) = repeat(4) { out.write((value shr (8 * it)) and 0xFF) }
        fun short(value: Int) = repeat(2) { out.write((value shr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray()); int(36 + data); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(data); out.write(ByteArray(data))
        return out.toByteArray()
    }
}
