package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An external `.lrc` file reaches the lyrics reader of kiteplayer-subtitles through the FFmpeg
 * backend's file parser (#443), and a SubRip file still reaches SubRip's. No native call is involved.
 */
class LyricsFileParserTest {

    private val parser = KiteFFmpegMediaBackend().subtitleFileParser()

    private fun texts(cues: List<SubtitleCue>) = cues.map { (it as SubtitleCue.Text).plainText to it.startMicros }

    @Test
    fun anLrcFileIsReadAsLyrics() {
        val lyrics = "[ti:A Song]\n[offset:500]\n[00:02.00][00:06.00]Chorus\n[00:04.00]Verse"
        assertEquals(
            listOf("Chorus" to 1_500_000L, "Verse" to 3_500_000L, "Chorus" to 5_500_000L),
            texts(parser.parse(lyrics, vttHint = false)),
        )
    }

    @Test
    fun aSubRipFileIsStillReadAsSubRip() {
        val subRip = "1\n00:00:01,000 --> 00:00:02,000\n[music]\n"
        assertEquals(listOf("[music]" to 1_000_000L), texts(parser.parse(subRip, vttHint = false)))
    }
}
