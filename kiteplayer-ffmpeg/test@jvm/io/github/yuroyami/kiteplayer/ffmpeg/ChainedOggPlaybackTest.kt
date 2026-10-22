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

/**
 * A chained Ogg file, two songs of three seconds one after the other as a station plays them, through
 * the whole player and FFmpeg (#423): the snapshot names the first song at the open, from the sound's
 * comments where an Ogg keeps them, and the second once it is heard, not when it is read.
 */
class ChainedOggPlaybackTest {

    @Test
    fun theNextSongsCommentsShowWhenItIsHeard() = runBlocking {
        val dir = formatMatrixMediaDir() ?: return@runBlocking
        val file = File(dir, "audio-chained.ogg").takeIf { it.isFile } ?: error("testmedia is missing audio-chained.ogg; run scripts/testmedia.sh")
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath)) }
            assertEquals("First Song", player.state.value.metadata["title"], "the open named ${player.state.value.metadata}")
            player.play()
            delay(1_500)
            assertEquals("First Song", player.state.value.metadata["title"], "the second song showed when it was read")
            withTimeout(10.seconds) {
                while (player.state.value.metadata["title"] != "Second Song") delay(20)
            }
            val heardAt = player.position()
            assertTrue(heardAt >= 2_900.milliseconds(), "the second song showed at $heardAt, before it began at 3 s")
        } finally {
            player.closeAndAwait()
        }
    }

    private fun Int.milliseconds() = kotlin.time.Duration.parse("${this}ms")
}
