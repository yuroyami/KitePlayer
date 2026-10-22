package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SubtitleSource
import io.github.yuroyami.kiteplayer.ofBytes
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
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
 * The whole player with an external TTML or DFXP file beside a clip FFmpeg plays (#492): the file
 * loads as a track, by its content whatever it is called, and its paragraphs show at their times
 * with their region.
 */
class TtmlFilePlaybackTest {

    private fun clip(): File? {
        val dir = formatMatrixMediaDir() ?: return null
        return File(dir, "audio-aac.m4a").takeIf { it.isFile } ?: error("testmedia is missing audio-aac.m4a; run scripts/testmedia.sh")
    }

    private fun document(namespace: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="$namespace" xmlns:tts="$namespace#styling">
          <head><layout><region xml:id="low" tts:origin="10% 75%" tts:extent="80% 20%" tts:displayAlign="after"/></layout></head>
          <body region="low"><div>
            <p begin="0.3s" end="2s">The first line</p>
            <p begin="3s" end="5s">The second line</p>
          </div></body>
        </tt>"""

    private fun showsWith(namespace: String, name: String) = runBlocking {
        val file = clip() ?: return@runBlocking
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            val subtitles = SubtitleSource(
                uri = name,
                io = MediaIo.ofBytes(document(namespace).encodeToByteArray()),
                selectImmediately = true,
            )
            withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath, externalSubtitles = listOf(subtitles))) }
            val track = player.state.value.tracks.all.firstOrNull { it.id == player.state.value.tracks.selectedSubtitle }
            assertTrue(track != null, "the file did not load as a track: ${player.state.value.tracks.all}")
            fun showing() = player.subtitleCues.value.filterIsInstance<SubtitleCue.Text>()
            player.play()
            val first = withTimeout(10.seconds) {
                while (showing().none { it.plainText == "The first line" }) delay(10)
                showing().single()
            }
            assertEquals("low", first.layout.region?.id, "the region reached the engine")
            assertTrue(player.position() < 2.seconds, "shown at ${player.position()}")
            player.seek(3500.milliseconds)
            withTimeout(10.seconds) { while (showing().none { it.plainText == "The second line" }) delay(10) }
            player.seek(2500.milliseconds)
            withTimeout(10.seconds) { while (showing().isNotEmpty()) delay(10) }
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun aTtmlFileShowsItsLinesInItsRegion() = showsWith("http://www.w3.org/ns/ttml", "film.ttml")

    @Test
    fun aDfxpFileNamedXmlShowsItsLinesToo() = showsWith("http://www.w3.org/2006/10/ttaf1", "film.en.xml")
}
