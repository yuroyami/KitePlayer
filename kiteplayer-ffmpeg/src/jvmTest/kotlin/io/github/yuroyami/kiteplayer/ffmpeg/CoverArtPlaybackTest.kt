package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** An MP3's album cover through the whole player and FFmpeg, as the JPEG its ID3 tag holds (#425). */
class CoverArtPlaybackTest {

    @Test
    fun anMp3sCoverIsItsJpeg() = runBlocking {
        val dir = formatMatrixMediaDir() ?: return@runBlocking
        val file = File(dir, "audio-cover.mp3").takeIf { it.isFile } ?: error("testmedia is missing audio-cover.mp3; run scripts/testmedia.sh")
        for (video in listOf(true, false)) {
            val player = KitePlayer.create(
                PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()), videoEnabled = video),
            )
            try {
                withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath)) }
                val cover = withTimeout(5.seconds) {
                    while (player.coverArt.value == null) delay(10)
                    player.coverArt.value!!
                }
                assertEquals("image/jpeg", cover.mimeType)
                assertTrue(
                    cover.bytes.size > 1_000 && cover.bytes[0] == 0xFF.toByte() && cover.bytes[1] == 0xD8.toByte(),
                    "the cover is ${cover.bytes.size} bytes starting ${cover.bytes.take(4)}, with video $video",
                )
            } finally {
                player.closeAndAwait()
            }
        }
    }
}
