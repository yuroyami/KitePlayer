package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A teletext stream that carries two subtitle pages is two tracks, read through FFmpeg and decoded
 * by the Kotlin page reader, each page with its own language and cues (#510). The stream is built
 * here as a broadcaster's inserter writes one: English on page 888 and German hearing impaired
 * subtitles on page 150, each shown and then erased.
 */
class TeletextTrackTest {

    private val media = teletextTransportStream(
        pages = listOf(TeletextListing("eng", 2, 0x888), TeletextListing("deu", 5, 0x150)),
        sends = listOf(
            TeletextSend(1.0, 0x888, "Hello"),
            TeletextSend(1.5, 0x150, "Hallo"),
            TeletextSend(3.0, 0x888, null),
            TeletextSend(3.5, 0x150, null),
        ),
    )

    private val secondPage = 0x1000000 or 0x150

    private suspend fun open(): KiteFFmpegSource =
        KiteFFmpegSourceFactory().open(MediaItem.from(MediaIo.ofBytes(media), label = "teletext.ts")) as KiteFFmpegSource

    /** Every cue of the [chosen] tracks, by track, with the stream index each read packet carried. */
    private suspend fun KiteFFmpegSource.play(chosen: List<PlayerStreamInfo>): Pair<Map<Int, List<SubtitleCue.Text>>, List<Int>> {
        selectStreams(chosen.map { it.index }.toSet())
        val decoders: Map<Int, SubtitleDecoder> =
            chosen.associate { it.index to assertNotNull(KiteFFmpegSubtitleDecoderFactory().create(it), "no decoder for ${it.codec}") }
        val cues = chosen.associate { it.index to ArrayList<SubtitleCue.Text>() }
        val labels = ArrayList<Int>()
        try {
            while (true) {
                val packet = readPacket() ?: break
                packet.use {
                    labels += it.streamIndex
                    assertTrue(assertNotNull(decoders[it.streamIndex], "a packet for ${it.streamIndex}, which nothing chose").send(it))
                }
                for ((index, decoder) in decoders) cues.getValue(index) += decoder.receive().filterIsInstance<SubtitleCue.Text>()
            }
            for ((index, decoder) in decoders) {
                decoder.send(null)
                cues.getValue(index) += decoder.receive().filterIsInstance<SubtitleCue.Text>()
            }
        } finally {
            decoders.values.forEach { it.close() }
        }
        return cues to labels
    }

    private fun List<SubtitleCue.Text>.texts(): List<String> = map { cue -> cue.spans.joinToString("") { it.text } }

    @Test
    fun eachSubtitlePageIsATrackOfItsOwn() = runBlocking {
        val source = open()
        try {
            val tracks = source.streams.filter { it.codec == "dvb_teletext" }
            assertEquals(listOf(0, secondPage), tracks.map { it.index })
            assertEquals(listOf("eng", "deu"), tracks.map { it.language })
            assertEquals(listOf(false, true), tracks.map { it.isAccessibility })
            assertEquals(listOf(TrackId(0), TrackId(secondPage)), source.programs.single().tracks)
        } finally {
            source.close()
        }
    }

    @Test
    fun twoPagesChosenTogetherEachGetEveryPacketAndTheirOwnCues() = runBlocking {
        val source = open()
        try {
            val (cues, labels) = source.play(source.streams.filter { it.codec == "dvb_teletext" })
            assertEquals(4, labels.count { it == 0 }, "labels: $labels")
            assertEquals(4, labels.count { it == secondPage }, "labels: $labels")

            val english = cues.getValue(0)
            assertEquals(listOf("Hello", ""), english.texts())
            assertEquals(2_000_000L, english[1].startMicros - english[0].startMicros)
            assertEquals(SubtitleCue.OPEN_END, english[0].endMicros)

            val german = cues.getValue(secondPage)
            assertEquals(listOf("Hallo", ""), german.texts())
            assertEquals(500_000L, german[0].startMicros - english[0].startMicros)
        } finally {
            source.close()
        }
    }

    @Test
    fun aLaterPageChosenAloneGetsThePacketsUnderItsOwnId() = runBlocking {
        val source = open()
        try {
            val (cues, labels) = source.play(source.streams.filter { it.index == secondPage })
            assertEquals(List(4) { secondPage }, labels)
            assertEquals(listOf("Hallo", ""), cues.getValue(secondPage).texts())
        } finally {
            source.close()
        }
    }

    @Test
    fun aSeekDropsTheCopiesStillOwedToTheOtherPage() = runBlocking {
        val source = open()
        try {
            source.selectStreams(setOf(0, secondPage))
            val first = assertNotNull(source.readPacket())
            assertEquals(0, first.streamIndex)
            first.close()
            source.seekToKeyframe(Pts(0))
            // The copy for page 150 belonged to the read before the seek, so after it every packet
            // comes for the first page and then its copy, and never the old copy first.
            val labels = ArrayList<Int>()
            while (true) source.readPacket()?.use { labels += it.streamIndex } ?: break
            assertTrue(labels.isNotEmpty())
            assertEquals(List(labels.size / 2) { listOf(0, secondPage) }.flatten(), labels)
        } finally {
            source.close()
        }
    }
}
