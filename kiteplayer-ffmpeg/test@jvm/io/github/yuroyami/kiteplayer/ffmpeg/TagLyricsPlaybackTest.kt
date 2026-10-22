package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The whole player on songs whose own tags carry lyrics (#443), read by FFmpeg: LRC lines in an MP3's
 * ID3 `USLT` frame show line by line, and a FLAC's plain `LYRICS` comment is text for the application.
 */
class TagLyricsPlaybackTest {

    private fun clip(name: String): File? {
        val dir = formatMatrixMediaDir() ?: return null
        return File(dir, name).takeIf { it.isFile } ?: error("testmedia is missing $name; run scripts/testmedia.sh")
    }

    private fun player(): KitePlayer = KitePlayer.create(
        PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())),
    )

    @Test
    fun lyricsInAnId3FrameShowLineByLine() = runBlocking {
        val file = clip("audio-lyrics.mp3") ?: return@runBlocking
        val player = player()
        try {
            withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath)) }
            val tracks = player.state.value.tracks
            val lyrics = tracks.all.firstOrNull { it.codec == "tag/lrc" }
            assertTrue(lyrics != null, "no lyrics track: ${tracks.all} with tags ${player.state.value.metadata}")
            assertEquals("eng", lyrics.language)
            assertEquals(lyrics.id, tracks.selectedSubtitle)
            assertNull(player.state.value.lyrics)
            player.play()
            fun showing() = player.subtitleCues.value.filterIsInstance<SubtitleCue.Text>().map { it.plainText }
            withTimeout(15.seconds) { while (showing() != listOf("Second line")) delay(20) }
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun plainLyricsInAFlacCommentAreText() = runBlocking {
        val file = clip("audio-lyrics.flac") ?: return@runBlocking
        val player = player()
        try {
            withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath)) }
            assertEquals("A plain first line\nA plain second line", player.state.value.lyrics)
            assertTrue(player.state.value.tracks.all.none { it.codec == "tag/lrc" })
        } finally {
            player.closeAndAwait()
        }
    }
}
