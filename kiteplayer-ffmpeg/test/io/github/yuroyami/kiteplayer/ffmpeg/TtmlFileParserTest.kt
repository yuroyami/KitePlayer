package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An external TTML or DFXP file reaches kiteplayer-subtitles' TTML reader through the FFmpeg
 * backend's file parser, known by its content whatever it is called (#492). No native call is involved.
 */
class TtmlFileParserTest {

    private val parser = KiteFFmpegMediaBackend().subtitleFileParser()

    private fun document(namespace: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="$namespace" xmlns:tts="$namespace#styling" xml:lang="en">
          <head><layout><region xml:id="low" tts:origin="10% 80%" tts:extent="80% 15%" tts:textAlign="center"/></layout></head>
          <body region="low"><div>
            <p begin="00:00:01.000" end="00:00:02.500">First line</p>
            <p begin="00:00:03.000" end="00:00:04.000"><span tts:fontStyle="italic">Second</span> line</p>
          </div></body>
        </tt>"""

    private fun texts(cues: List<SubtitleCue>) = cues.map { (it as SubtitleCue.Text).plainText to it.startMicros }

    @Test
    fun aTtmlFileIsReadWithItsRegion() {
        val cues = parser.parse(document("http://www.w3.org/ns/ttml"), vttHint = false)
        assertEquals(listOf("First line" to 1_000_000L, "Second line" to 3_000_000L), texts(cues))
        val first = cues.first() as SubtitleCue.Text
        assertEquals("low", first.layout.region?.id)
        assertEquals(CueAlignment.TopCenter, first.layout.alignment)
        assertTrue((cues[1] as SubtitleCue.Text).spans.first().style.italic)
    }

    @Test
    fun aDfxpFileIsReadTheSameWay() {
        val cues = parser.parse(document("http://www.w3.org/2006/10/ttaf1"), vttHint = false)
        assertEquals(listOf("First line" to 1_000_000L, "Second line" to 3_000_000L), texts(cues))
    }

    @Test
    fun aTtmlFileWithAWebVttNameIsStillTtml() {
        // The content decides; a hint from the file's name does not.
        assertEquals(2, parser.parse(document("http://www.w3.org/ns/ttml"), vttHint = true).size)
    }

    @Test
    fun aBrokenTtmlFileGivesNoCuesForTheNextReaderToTry() {
        assertEquals(emptyList(), parser.parse("""<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1s" end="2s">x""", vttHint = false))
    }
}
