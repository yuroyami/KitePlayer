package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Thumbnails, waveforms and loudness open an item through [openSource], the same way playback does. */
class ItemSourcesTest {

    @Test
    fun theFormatHintConfinesTheProbe() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        // The bytes are MP4, so a probe that may only try Matroska finds nothing.
        assertFailsWith<FFmpegException> {
            openSource(MediaItem("$mediaDir/sync1080p30.mp4", formatHint = "matroska")).close()
        }
        Unit
    }

    @Test
    fun anOptionSetTwiceIsRefused() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val item = MediaItem(
            "$mediaDir/sync1080p30.mp4",
            formatHint = "mov",
            openOptions = mapOf("format_whitelist" to "mov"),
        )
        val refusal = assertFailsWith<PlaybackException> { openSource(item).close() }
        assertIs<PlaybackError.ConfigurationInvalid>(refusal.error)
        Unit
    }
}
