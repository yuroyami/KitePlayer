package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MediaItemBuilderTest {

    @Test
    fun anEmptyBlockBuildsThePlainItem() {
        assertEquals(MediaItem("movie.mkv"), mediaItem("movie.mkv"))
        assertEquals(MediaItem("movie.mkv"), mediaItem("movie.mkv") {})
        assertEquals(DemuxPolicy(), MediaItem("movie.mkv").demux)
    }

    @OptIn(KitePlayerLowLevelApi::class)
    @Test
    fun aFullBlockMatchesTheItemBuiltByHand() {
        val io = MediaIo.ofBytes(byteArrayOf(1))
        val english = SubtitleSource("/sdcard/en.srt", language = "en")
        val french = SubtitleSource("/sdcard/fr.srt", title = "French", selectImmediately = true)
        val built = mediaItem("https://cdn.example/movie.mkv") {
            header("Authorization", "Bearer token")
            headers("X-Session" to "42", "User-Agent" to "kite")
            externalSubtitle(english)
            externalSubtitle(french)
            videoFilter("scale=1280:720")
            startPosition(90.seconds)
            io(io)
            formatHint("matroska")
            openOption("probesize", "4096")
            probe(ProbeDepth.Thorough)
            corruptPackets(CorruptPackets.Drop)
            generateTimestamps()
            lowLatency()
            skipInitialBytes(512)
        }
        val byHand = MediaItem(
            uri = "https://cdn.example/movie.mkv",
            headers = mapOf("Authorization" to "Bearer token", "X-Session" to "42", "User-Agent" to "kite"),
            externalSubtitles = listOf(english, french),
            videoFilter = "scale=1280:720",
            startPosition = 90.seconds,
            io = io,
            formatHint = "matroska",
            openOptions = mapOf("probesize" to "4096"),
            demux = DemuxPolicy(
                probe = ProbeDepth.Thorough,
                corruptPackets = CorruptPackets.Drop,
                generateTimestamps = true,
                lowLatency = true,
                skipInitialBytes = 512,
            ),
        )
        assertEquals(byHand, built)
    }

    @Test
    fun invalidSettingsAreRefusedWhereTheyAreMade() {
        assertFailsWith<IllegalArgumentException> { ProbeDepth.Custom(bytes = 0, duration = 1.seconds) }
        assertFailsWith<IllegalArgumentException> { ProbeDepth.Custom(bytes = 4096, duration = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { DemuxPolicy(skipInitialBytes = -1) }
        assertFailsWith<IllegalArgumentException> { mediaItem("movie.mkv") { skipInitialBytes(-1) } }
        assertEquals(4096L, ProbeDepth.Custom(bytes = 4096, duration = 250.milliseconds).bytes)
    }
}
