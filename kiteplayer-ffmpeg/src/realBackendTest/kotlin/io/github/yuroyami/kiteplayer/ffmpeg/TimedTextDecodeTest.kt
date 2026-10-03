package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The MP4 timed text that FFmpeg's encoder made from SubRip styles decodes with those styles on
 * exactly their characters (#512). The colour is proven on a hand-made sample in TimedTextTest,
 * because not every FFmpeg the fixtures are made with writes one.
 */
class TimedTextDecodeTest {

    @Test
    fun theStylesOfAnMp4SubtitleTrackReachItsCues() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val path = "$mediaDir/movtext-styled.mp4"
        checkNotNull(readTestFile(path)) { "$path is missing; run scripts/testmedia.sh" }
        val source = KiteFFmpegSourceFactory().open(MediaItem(path)) as KiteFFmpegSource
        val cues = mutableListOf<SubtitleCue.Text>()
        try {
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Subtitle })
            assertEquals("mov_text", stream.codec)
            source.selectStreams(setOf(stream.index))
            val decoder = assertNotNull(KiteFFmpegSubtitleDecoderFactory().create(stream))
            try {
                while (true) {
                    val packet = source.readPacket() ?: break
                    packet.use { if (it.streamIndex == stream.index) decoder.send(it) }
                    decoder.receive().filterIsInstance<SubtitleCue.Text>().forEach(cues::add)
                }
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }

        assertEquals(3, cues.size, "cues: ${cues.map { cue -> cue.spans.joinToString("") { it.text } }}")
        val (voice, bold, japanese) = cues

        assertEquals(listOf("Off screen, a voice"), voice.spans.map { it.text })
        assertEquals(listOf(true), voice.spans.map { it.style.italic })

        assertEquals(listOf("A plain line with one ", "bold", " word"), bold.spans.map { it.text })
        assertEquals(listOf(false, true, false), bold.spans.map { it.style.bold })

        val underlined = japanese.spans.filter { it.style.underline }
        assertEquals(listOf("word"), underlined.map { it.text }, "spans: ${japanese.spans}")
        assertEquals("日本語 and a red word", japanese.spans.joinToString("") { it.text })
    }
}
