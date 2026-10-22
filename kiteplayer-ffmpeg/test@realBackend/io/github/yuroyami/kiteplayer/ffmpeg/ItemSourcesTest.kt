package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.ofBytes
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Thumbnails, waveforms and loudness open an item through [openSource], the same way playback does. */
class ItemSourcesTest {

    @Test
    fun theFormatHintForcesTheDemuxer() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        // The bytes are MP4, so the Matroska demuxer it is forced to use finds nothing.
        assertFailsWith<FFmpegException> {
            openSource(MediaItem("$mediaDir/sync1080p30.mp4", formatHint = "matroska")).close()
        }
        Unit
    }

    @Test
    fun aForcedFormatOpensRawPcm() = runBlocking {
        // Headerless 16-bit PCM: nothing for a probe to find, so only a forced demuxer opens it.
        val frames = 48_000
        val bytes = ByteArray(frames * 2) { index -> if (index % 2 == 0) 0 else ((index / 2) % 64).toByte() }
        val item = MediaItem(
            "memory://raw.pcm",
            io = MediaIo.ofBytes(bytes),
            formatHint = "s16le",
            openOptions = mapOf("sample_rate" to "48000", "ch_layout" to "mono"),
        )
        openSource(item).use { source ->
            val audio = source.streams.firstOrNull { it.audio != null }?.audio
            assertTrue(audio?.sampleRate == 48_000, "the forced demuxer read no 48 kHz audio from raw PCM: $audio")
        }
        Unit
    }

    @Test
    fun anOptionSetTwiceIsRefused() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val item = MediaItem(
            "$mediaDir/sync1080p30.mp4",
            headers = mapOf("X-Test" to "1"),
            openOptions = mapOf("headers" to "X-Test: 2\r\n"),
        )
        val refusal = assertFailsWith<PlaybackException> { openSource(item).close() }
        assertIs<PlaybackError.ConfigurationInvalid>(refusal.error)
        Unit
    }
}
