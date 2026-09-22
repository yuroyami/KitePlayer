package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.ProbeDepth
import io.github.yuroyami.kiteplayer.ofBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The typed-field respelling, tested pure: what reaches the pre-open funnel for
 * each combination of [MediaItem.headers], [MediaItem.formatHint], [MediaItem.demux] and a raw
 * [MediaItem.openOptions] that collides with them. FFmpeg consumes the result; producing it is
 * plain string work and is proven here without a single native call.
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
    fun aRawKeyThatATypedFieldAlsoSetsIsRefusedNamingBoth() {
        // Each item sets one option twice, and the pair is the option key and the typed field.
        val collisions = listOf(
            MediaItem(
                "https://example.test/movie.mkv",
                headers = mapOf("X-Typed" to "yes"),
                openOptions = mapOf("headers" to "X-Raw: yes\r\n"),
            ) to ("headers" to "headers"),
            MediaItem(
                "pipe:0",
                formatHint = "mpegts",
                openOptions = mapOf("format_whitelist" to "matroska,webm"),
            ) to ("format_whitelist" to "formatHint"),
            MediaItem(
                "file.mkv",
                demux = DemuxPolicy(probe = ProbeDepth.Fast),
                openOptions = mapOf("probesize" to "32768"),
            ) to ("probesize" to "demux"),
            MediaItem(
                "udp://239.0.0.1:1234",
                demux = DemuxPolicy(lowLatency = true),
                openOptions = mapOf("fflags" to "+igndts"),
            ) to ("fflags" to "demux"),
        )
        for ((item, collision) in collisions) {
            val (key, field) = collision
            val refusal = assertFailsWith<PlaybackException>("$key against $field") { preOpenOptions(item) }
            val detail = assertIs<PlaybackError.ConfigurationInvalid>(refusal.error).detail
            assertTrue("\"$key\"" in detail && "MediaItem.$field " in detail, detail)
        }
    }

    @Test
    fun bothDoorsRefuseACollisionBeforeTheyMakeAReader() = runTest {
        var readersMade = 0
        val item = MediaItem(
            "label.ts",
            io = MediaIoFactory {
                readersMade++
                MediaIo.ofBytes(ByteArray(188)).open()
            },
            formatHint = "mpegts",
            openOptions = mapOf("format_whitelist" to "mpegts"),
        )
        assertFailsWith<PlaybackException> { KiteFFmpegMediaBackend().open(item) }
        assertFailsWith<PlaybackException> { KiteFFmpegSourceFactory().open(item) }
        assertEquals(0, readersMade, "a refused open must not make a reader that it then has to close")
    }

    @Test
    fun typedAndRawKeysThatDoNotCollideAreBothApplied() {
        val options = preOpenOptions(
            MediaItem(
                "https://example.test/live.ts",
                formatHint = "mpegts",
                demux = DemuxPolicy(probe = ProbeDepth.Fast),
                openOptions = mapOf("fflags" to "+igndts", "reconnect" to "1"),
            ),
        )
        assertEquals(
            mapOf(
                "format_whitelist" to "mpegts",
                "probesize" to "524288",
                "analyzeduration" to "200000",
                "fflags" to "+igndts",
                "reconnect" to "1",
            ),
            options,
        )
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
