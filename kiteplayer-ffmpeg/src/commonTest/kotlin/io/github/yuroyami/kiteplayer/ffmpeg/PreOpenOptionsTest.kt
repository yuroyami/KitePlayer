package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The typed-field respelling, tested pure: what reaches KD-4's pre-open funnel for
 * each combination of [MediaItem.headers], [MediaItem.formatHint] and a raw [MediaItem.openOptions]
 * that collides with them. FFmpeg consumes the result; producing it is plain string work and is
 * proven here without a single native call.
 */
class PreOpenOptionsTest {

    @Test
    fun headersBecomeOneCrlfJoinedHttpOptionBlock() {
        val options = preOpenOptions(
            MediaItem(
                "https://example.test/movie.mkv",
                headers = linkedMapOf(
                    "Authorization" to "Bearer abc",
                    "X-Session" to "42",
                ),
            ),
        )
        assertEquals("Authorization: Bearer abc\r\nX-Session: 42\r\n", options["headers"])
    }

    @Test
    fun formatHintBecomesAFormatWhitelistOfOne() {
        val options = preOpenOptions(MediaItem("pipe:0", formatHint = "mpegts"))
        assertEquals("mpegts", options["format_whitelist"])
    }

    @Test
    fun aRawOpenOptionsKeyWinsOverTheTypedSugar() {
        val options = preOpenOptions(
            MediaItem(
                "https://example.test/movie.mkv",
                headers = mapOf("X-Ignored" to "yes"),
                formatHint = "ignored",
                openOptions = mapOf(
                    "headers" to "X-Raw: kept\r\n",
                    "format_whitelist" to "matroska,webm",
                ),
            ),
        )
        assertEquals("X-Raw: kept\r\n", options["headers"])
        assertEquals("matroska,webm", options["format_whitelist"])
    }

    @Test
    fun anItemWithNeitherAddsNothing() {
        assertTrue(preOpenOptions(MediaItem("file.mkv")).isEmpty())
        assertEquals(
            mapOf("probesize" to "32768"),
            preOpenOptions(MediaItem("file.mkv", openOptions = mapOf("probesize" to "32768"))),
        )
    }
}
