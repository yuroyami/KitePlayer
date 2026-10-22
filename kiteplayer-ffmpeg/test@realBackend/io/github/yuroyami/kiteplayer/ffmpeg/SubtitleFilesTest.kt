package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.SubtitleFileReading
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * External subtitle files in the formats the Kotlin readers do not know, read by FFmpeg's own
 * demuxers and decoders through the backend's file parser (#492).
 */
class SubtitleFilesTest {

    private val parser = KiteFFmpegMediaBackend().subtitleFileParser()

    private fun read(text: String, uri: String, bytes: ByteArray = text.encodeToByteArray()): SubtitleFileReading? =
        runBlocking { parser.parseOther(bytes, text, uri) }

    private fun lines(reading: SubtitleFileReading?): List<Pair<Long, String>> =
        assertNotNull(reading, "FFmpeg read nothing").cues.map { it.startMicros to (it as SubtitleCue.Text).plainText }

    @Test
    fun aSamiFileReadsWithItsColour() {
        val sami = """
            <SAMI>
            <HEAD><TITLE>Test</TITLE>
            <STYLE TYPE="text/css"><!--
            P { margin-left:8pt; }
            .KRCC { Name:Korean; lang:ko-KR; SAMIType:CC; }
            --></STYLE></HEAD>
            <BODY>
            <SYNC Start=1000><P Class=KRCC>안녕하세요
            <SYNC Start=2500><P Class=KRCC>&nbsp;
            <SYNC Start=3000><P Class=KRCC><font color="#ff0000">Red line</font>
            <SYNC Start=4000><P Class=KRCC>&nbsp;
            </BODY></SAMI>
        """.trimIndent()
        val reading = read(sami, "/films/film.smi")
        assertEquals("sami", reading?.format)
        assertEquals(listOf(1_000_000L to "안녕하세요", 3_000_000L to "Red line"), lines(reading))
        val red = reading!!.cues[1] as SubtitleCue.Text
        assertEquals(0xFFFF0000.toInt(), red.spans.single { it.text.isNotBlank() }.style.primaryColor, "the font colour was lost")
        assertTrue(reading.cues.first().endMicros in 2_000_000L..2_500_000L, "the first line ended at ${reading.cues.first().endMicros}")
        assertNull(reading.assumedFrameRate)
    }

    @Test
    fun aMicroDvdFileCountsFramesAtTheRateItNames() {
        val reading = read("{1}{1}25.000\n{25}{50}First\n{75}{100}{y:i}Second\n{125}{150}Third\n", "/films/film.sub")
        assertEquals("microdvd", reading?.format)
        assertEquals(listOf(1_000_000L to "First", 3_000_000L to "Second", 5_000_000L to "Third"), lines(reading))
        assertEquals(2_000_000L, reading!!.cues.first().endMicros)
        assertTrue((reading.cues[1] as SubtitleCue.Text).spans.all { it.style.italic }, "the italics were lost")
        assertNull(reading.assumedFrameRate, "a file that names its rate assumed one")
    }

    @Test
    fun aMicroDvdFileThatNamesNoRateSaysWhichItAssumed() {
        val reading = read("{240}{288}Ten seconds in\n{300}{350}Two\n{400}{450}Three\n", "/films/film.sub")
        assertEquals("microdvd", reading?.format)
        assertEquals(24_000.0 / 1_001.0, reading?.assumedFrameRate)
        val start = reading!!.cues.first().startMicros
        assertTrue(start in 10_009_000L..10_011_000L, "frame 240 at 23.976 a second read as $start")
    }

    @Test
    fun anSbvFileReads() {
        val sbv = "0:00:01.000,0:00:02.000\nFirst\n\n0:00:03.000,0:00:04.500\nSecond\n"
        val reading = read(sbv, "/films/film.sbv")
        assertEquals(listOf(1_000_000L to "First", 3_000_000L to "Second"), lines(reading))
        assertEquals(4_500_000L, reading!!.cues[1].endMicros)
    }

    @Test
    fun anMpl2FileReads() {
        val reading = read("[10][20]First\n[30][45]Second\n[50][60]Third\n", "/films/film.txt")
        assertEquals("mpl2", reading?.format)
        assertEquals(listOf(1_000_000L to "First", 3_000_000L to "Second", 5_000_000L to "Third"), lines(reading))
    }

    @Test
    fun aBluRaySupFileShowsItsPictures() {
        val sup = bluRaySubtitles(1 to listOf(PgsCaption(10, 20, forced = false)), 2 to emptyList())
        val reading = runBlocking { parser.parseOther(sup, sup.decodeToString(), "/films/film.sup") }
        assertNotNull(reading, "FFmpeg read nothing from the .sup")
        assertEquals("sup", reading.format)
        val picture = reading.cues.single() as SubtitleCue.Bitmap
        assertEquals(1_000_000L, picture.startMicros)
        assertEquals(2_000_000L, picture.endMicros, "the clearing set did not end the picture")
        val region = picture.regions.single()
        assertEquals(listOf(10, 20, 4, 2, 1920, 1080), listOf(region.x, region.y, region.width, region.height, region.canvasWidth, region.canvasHeight))
    }

    @Test
    fun aFileNoReaderKnowsReadsAsNothing() {
        assertNull(read("Just some words\nwith no times at all\n", "/films/notes.txt"))
    }
}
