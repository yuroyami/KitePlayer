package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
            audioFilter("volume=0.5")
            startPosition(90.seconds)
            clip(30.seconds, 5.minutes)
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
            audioFilter = "volume=0.5",
            startPosition = 90.seconds,
            clip = MediaClip(30.seconds, 5.minutes),
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
        assertTrue("audioFilter=volume=0.5" in built.toString(), "a printed item names its audio filter: $built")
    }

    /** Java has no block, so it chains the same calls on a builder it makes (#420). */
    @Test
    fun chainedCallsBuildTheItemABlockBuilds() {
        val english = SubtitleSource("/sdcard/en.srt", language = "en")
        val french = SubtitleSource("/sdcard/fr.srt", title = "French")
        val chained = MediaItemBuilder("movie.mkv")
            .header("Authorization", "Bearer token")
            .headers(mapOf("X-Session" to "42"))
            .externalSubtitles(listOf(english, french))
            .startPositionMillis(90_000)
            .clipMillis(30_000)
            .formatHint("matroska")
            .demux(DemuxPolicy(lowLatency = true))
            .probe(ProbeDepth.Thorough)
            .title("Movie")
            .artist(null)
            .build()
        val block = mediaItem("movie.mkv") {
            header("Authorization", "Bearer token")
            header("X-Session", "42")
            externalSubtitle(english)
            externalSubtitle(french)
            startPosition(90.seconds)
            clip(30.seconds)
            formatHint("matroska")
            lowLatency()
            probe(ProbeDepth.Thorough)
            title("Movie")
        }
        assertEquals(block, chained)
        assertEquals(MediaItem("movie.mkv"), MediaItemBuilder("movie.mkv").build())
    }

    @Test
    fun invalidSettingsAreRefusedWhereTheyAreMade() {
        assertFailsWith<IllegalArgumentException> { ProbeDepth.Custom(bytes = 0, duration = 1.seconds) }
        assertFailsWith<IllegalArgumentException> { ProbeDepth.Custom(bytes = 4096, duration = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { DemuxPolicy(skipInitialBytes = -1) }
        assertFailsWith<IllegalArgumentException> { mediaItem("movie.mkv") { skipInitialBytes(-1) } }
        assertEquals(4096L, ProbeDepth.Custom(bytes = 4096, duration = 250.milliseconds).bytes)
        assertFailsWith<IllegalArgumentException> { MediaClip(start = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { MediaClip(start = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { MediaClip(start = 20.seconds, end = 20.seconds) }
        assertFailsWith<IllegalArgumentException> { MediaClip(start = 20.seconds, end = 10.seconds) }
        assertFailsWith<IllegalArgumentException> { MediaClip(end = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { mediaItem("album.flac") { clip(20.seconds, 10.seconds) } }
        assertEquals(MediaClip(1.seconds, 2.5.seconds), MediaClip.ofMillis(1_000, 2_500))
        assertEquals(1_500L, MediaClip.ofMillis(0, 1_500).length?.inWholeMilliseconds)
        assertEquals(null, MediaClip.ofMillis(1_000).endMillis)
    }
}
