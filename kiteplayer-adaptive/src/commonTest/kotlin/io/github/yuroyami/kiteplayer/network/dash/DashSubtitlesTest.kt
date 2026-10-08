package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes.Sample
import kotlin.test.Test
import kotlin.test.assertEquals

/** The samples of a DASH subtitle track as cues on the presentation's timeline (#402). */
class DashSubtitlesTest {

    private fun ttml(vararg paragraphs: String) =
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""

    private val stpp = Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "subt", sampleEntry = "stpp")
    private val wvtt = Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "text", sampleEntry = "wvtt")

    private fun cues(init: ByteArray, segment: ByteArray) = DashSubtitles.mp4Cues(init, segment).map { Triple(it.startMicros, it.endMicros, it.text) }

    @Test
    fun anStppSampleWhoseTimesCountFromTheTrackStartKeepsThem() {
        val doc = ttml("""<p begin="00:01:00.500" end="00:01:01.500">sixty</p>""")
        val segment = Mp4Bytes.segment(1, decodeTime = 60_000, listOf(Sample(2000, doc.encodeToByteArray())))
        assertEquals(listOf(Triple(60_500_000L, 61_500_000L, "sixty")), cues(stpp, segment))
    }

    @Test
    fun anStppSampleWhoseTimesCountFromItsOwnStartIsMovedThere() {
        val doc = ttml("""<p begin="0.5s" end="1.5s">relative</p>""")
        val segment = Mp4Bytes.segment(1, decodeTime = 60_000, listOf(Sample(2000, doc.encodeToByteArray())))
        assertEquals(listOf(Triple(60_500_000L, 61_500_000L, "relative")), cues(stpp, segment))
    }

    @Test
    fun aCueIsCutToTheSampleItCameIn() {
        // Packagers repeat a cue that spans two segments in both, so each keeps only its own part.
        val doc = ttml("""<p begin="00:00:59" end="00:01:03">across</p>""")
        val segment = Mp4Bytes.segment(1, decodeTime = 60_000, listOf(Sample(2000, doc.encodeToByteArray())))
        assertEquals(listOf(Triple(60_000_000L, 62_000_000L, "across")), cues(stpp, segment))
    }

    @Test
    fun wvttSamplesGiveTheirCuesAndAnEmptySampleGivesNone() {
        val segment = Mp4Bytes.segment(
            1,
            decodeTime = 10_000,
            listOf(
                Sample(2000, Mp4Bytes.wvttSample("Hello <i>there</i>")),
                Sample(1000, Mp4Bytes.wvttSample()),
                Sample(2000, Mp4Bytes.wvttSample("top", "bottom")),
            ),
        )
        assertEquals(
            listOf(
                Triple(10_000_000L, 12_000_000L, "Hello <i>there</i>"),
                Triple(13_000_000L, 15_000_000L, "top"),
                Triple(13_000_000L, 15_000_000L, "bottom"),
            ),
            cues(wvtt, segment),
        )
    }

    @Test
    fun cuesAreMovedOntoThePicturesTimeline() {
        val moved = DashSubtitles.shift(listOf(TimedCue(1_000_000, 2_000_000, "x")), 5_000_000)
        assertEquals(listOf(6_000_000L to 7_000_000L), moved.map { it.startMicros to it.endMicros })
    }
}
