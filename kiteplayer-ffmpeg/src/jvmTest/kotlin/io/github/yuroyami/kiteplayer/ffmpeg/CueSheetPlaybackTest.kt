package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An album in one FLAC file with its cue sheet, played as its tracks through the whole player and
 * FFmpeg (#456): the six seconds of the test media's FLAC as three tracks of two seconds.
 */
class CueSheetPlaybackTest {

    @Test
    fun aCueSheetPlaysItsFileAsItsTracks() = runBlocking {
        val dir = formatMatrixMediaDir() ?: return@runBlocking
        val flac = File(dir, "audio-flac.flac").takeIf { it.isFile } ?: error("testmedia is missing audio-flac.flac; run scripts/testmedia.sh")
        val folder = File.createTempFile("kiteplayer-cue", "").apply { delete(); mkdirs(); deleteOnExit() }
        val cue = File(folder, "album.cue").apply {
            writeText(
                "PERFORMER \"The Band\"\nTITLE \"Six Seconds\"\nFILE \"${flac.absolutePath}\" WAVE\n" +
                    "  TRACK 01 AUDIO\n    TITLE \"One\"\n    INDEX 01 00:00:00\n" +
                    "  TRACK 02 AUDIO\n    TITLE \"Two\"\n    INDEX 01 00:02:00\n" +
                    "  TRACK 03 AUDIO\n    TITLE \"Three\"\n    INDEX 01 00:04:00\n",
            )
            deleteOnExit()
        }
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            val tracks = player.readPlaylist(cue.absolutePath)
            assertEquals(listOf("One", "Two", "Three"), tracks.map { it.title })
            withTimeout(30.seconds) { player.openQueue(tracks) }
            val first = player.state.value
            assertEquals("One", first.media?.title)
            val length = first.duration?.inWholeMilliseconds ?: -1
            assertTrue(length in 1_900..2_100, "the first track is $length ms long")
            player.play()
            withTimeout(20.seconds) {
                while (player.state.value.queueIndex != 2) delay(20)
            }
            assertEquals("Three", player.state.value.media?.title)
            val last = player.state.value.duration?.inWholeMilliseconds ?: -1
            assertTrue(last in 1_900..2_100, "the last track is $last ms long")
            withTimeout(10.seconds) {
                while (player.state.value.status != PlaybackStatus.Ended) delay(20)
            }
            delay(100.milliseconds)
            assertEquals(2, player.state.value.queueIndex)
        } finally {
            player.closeAndAwait()
        }
    }
}
