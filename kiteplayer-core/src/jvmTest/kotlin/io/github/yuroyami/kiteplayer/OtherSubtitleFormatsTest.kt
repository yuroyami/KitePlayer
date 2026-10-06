@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.SubtitleFileReading
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * External subtitle files in a format the text readers do not know go to the backend parser's other
 * reader (#492), which the FFmpeg backend answers with FFmpeg. JVM-hosted because the files are real
 * files.
 */
class OtherSubtitleFormatsTest {

    private fun file(name: String, text: String): File =
        File(File.createTempFile("kiteplayer-other", "").apply { delete(); mkdirs(); deleteOnExit() }, name).apply {
            writeText(text)
            deleteOnExit()
        }

    private fun cue(startMicros: Long, endMicros: Long, text: String): SubtitleCue =
        SubtitleCue.Text(startMicros, endMicros, listOf(StyledSpan(text)))

    private fun texts(harness: CoreHarness) =
        harness.core.subtitleCues.value.filterIsInstance<SubtitleCue.Text>().map { it.plainText }

    private suspend fun open(harness: CoreHarness, file: File) {
        harness.attachRenderer()
        harness.core.open(
            MediaItem("scripted://media", externalSubtitles = listOf(SubtitleSource(uri = file.absolutePath, selectImmediately = true))),
        )
    }

    @Test
    fun aFormatTheTextReadersDoNotKnowIsReadByTheOtherReader() = runTest {
        val sami = file("film.smi", "<SAMI><BODY><SYNC Start=500><P>from sami</BODY></SAMI>")
        val harness = CoreHarness(this)
        val asked = mutableListOf<String>()
        harness.backend.otherSubtitleReader = { _, text, uri ->
            asked += uri
            if ("SAMI" in text) SubtitleFileReading(listOf(cue(500_000, 2_000_000, "from sami")), format = "sami") else null
        }
        open(harness, sami)
        val external = harness.core.snapshots.value.tracks.all.filter { it.id.isExternal }
        assertEquals(listOf("external/sami"), external.map { it.codec }, harness.core.warningHistory().map { it.warning.message }.toString())
        assertEquals(listOf(sami.absolutePath), asked)
        harness.core.play()
        harness.run(800.milliseconds)
        assertEquals(listOf("from sami"), texts(harness))
        harness.close()
    }

    @Test
    fun aFormatTheTextReadersKnowNeverReachesTheOtherReader() = runTest {
        val srt = file("film.srt", "1\n00:00:00,500 --> 00:00:02,000\nfrom subrip\n")
        val harness = CoreHarness(this)
        var asked = 0
        harness.backend.otherSubtitleReader = { _, _, _ -> asked++; null }
        open(harness, srt)
        assertEquals(listOf("external/subrip"), harness.core.snapshots.value.tracks.all.filter { it.id.isExternal }.map { it.codec })
        assertEquals(0, asked)
        harness.close()
    }

    /**
     * Frame 240 of a MicroDVD file that names no rate is ten seconds in at the reader's 23.976 frames
     * a second and 9.6 seconds into the 25 frame video it was made for.
     */
    @Test
    fun cuesCountedAtAnAssumedRateFollowTheVideosRate() = runTest {
        val microDvd = file("film.sub", "{240}{288}Frame 240\n")
        val harness = CoreHarness(this, script = MediaScript(durationUs = 14_000_000))
        val assumed = 24_000.0 / 1_001.0
        harness.backend.otherSubtitleReader = { _, _, _ ->
            SubtitleFileReading(
                listOf(cue((240 / assumed * 1_000_000).toLong(), (288 / assumed * 1_000_000).toLong(), "Frame 240")),
                format = "microdvd",
                assumedFrameRate = assumed,
            )
        }
        open(harness, microDvd)
        harness.core.play()
        harness.run(9_750.milliseconds)
        assertEquals(listOf("Frame 240"), texts(harness), "the line did not start at 9.6 seconds")
        harness.run(2_000.milliseconds)
        assertEquals(emptyList(), texts(harness), "the line did not end at 11.52 seconds")
        harness.close()
    }

    @Test
    fun aFileNoReaderCanReadIsRefusedNamingItsFormat() = runTest {
        val sami = file("film.smi", "<SAMI><BODY>broken</BODY></SAMI>")
        val harness = CoreHarness(this)
        harness.backend.otherSubtitleReader = { _, _, _ -> null }
        open(harness, sami)
        val refusal = harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.SubtitleSourceUnreadable>().single()
        assertTrue("SAMI" in refusal.message, "the refusal did not name the format: ${refusal.message}")
        harness.close()
    }
}
