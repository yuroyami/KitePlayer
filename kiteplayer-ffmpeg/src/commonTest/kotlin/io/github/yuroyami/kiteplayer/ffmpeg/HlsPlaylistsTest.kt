package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.VariantFit
import io.github.yuroyami.kiteplayer.VideoSize

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The text half of the HLS path: recognising a playlist, keeping one variant, resolving addresses. */
class HlsPlaylistsTest {

    @Test
    fun aPlaylistIsRecognisedByHintTypeOrExtension() {
        assertTrue(looksLikeHls("hls", null, null))
        assertTrue(looksLikeHls(null, "application/vnd.apple.mpegurl", "https://cdn.test/play"))
        assertTrue(looksLikeHls(null, "Application/X-MpegURL; charset=UTF-8", null))
        assertTrue(looksLikeHls(null, null, "https://cdn.test/live/index.m3u8?token=a.b"))
        assertTrue(looksLikeHls(null, null, "https://cdn.test/radio.M3U#top"))
        assertFalse(looksLikeHls(null, "video/mp4", "https://cdn.test/movie.mp4"))
        assertFalse(looksLikeHls(null, null, "https://cdn.test/m3u8/movie.mp4"))
        // A format hint names the format, so an HLS-looking address does not override it.
        assertFalse(looksLikeHls("mpegts", null, "https://cdn.test/index.m3u8"))
    }

    @Test
    fun aPlaylistIsRecognisedByItsFirstBytes() {
        assertTrue(startsLikeHls("#EXTM3U\n#EXT-X-VERSION:3\n".encodeToByteArray()))
        assertTrue(startsLikeHls("﻿#EXTM3U\r\n#EXT-X-TARGETDURATION:2".encodeToByteArray()))
        assertTrue(startsLikeHls(" \r\n\t#EXTM3U".encodeToByteArray()))
        // Only the first bytes a reader gave count, however large the buffer.
        assertFalse(startsLikeHls("#EXTM3U".encodeToByteArray().copyOf(32), length = 6))
        assertFalse(startsLikeHls("#EXTM3".encodeToByteArray()))
        assertFalse(startsLikeHls("WEBVTT\n\n00:00.000 --> 00:01.000\n".encodeToByteArray()))
        assertFalse(startsLikeHls("#EXTINF:2.0,\nseg-0.ts\n".encodeToByteArray()))
        assertFalse(startsLikeHls("<?xml version=\"1.0\"?><MPD/>".encodeToByteArray()))
        // The start of an MP4 file: a box size, then ftyp.
        assertFalse(startsLikeHls(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d)))
        assertFalse(startsLikeHls(ByteArray(0)))
    }

    @Test
    fun attributesKeepCommasInsideQuotes() {
        val attributes = parseHlsAttributes("BANDWIDTH=1280000,CODECS=\"avc1.4d401f,mp4a.40.2\",RESOLUTION=1280x720,AUDIO=\"aac\"")
        assertEquals("1280000", attributes["BANDWIDTH"])
        assertEquals("avc1.4d401f,mp4a.40.2", attributes["CODECS"])
        assertEquals("1280x720", attributes["RESOLUTION"])
        assertEquals("aac", attributes["AUDIO"])
    }

    @Test
    fun aMediaPlaylistIsNotAMaster() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\nseg-0.ts\n#EXT-X-ENDLIST\n"
        assertNull(keepOneHlsVariant(media, maxBitrate = null, maxVideoHeight = null))
    }

    @Test
    fun theHighestBitrateVariantStaysWithItsOwnRenditionsOnly() {
        val kept = keepOneHlsVariant(MASTER, maxBitrate = null, maxVideoHeight = null)!!.playlist
        assertEquals(
            listOf(
                "#EXTM3U",
                "#EXT-X-VERSION:6",
                "#EXT-X-INDEPENDENT-SEGMENTS",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"stereo\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,URI=\"audio/en.m3u8\"",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"stereo\",NAME=\"Deutsch\",LANGUAGE=\"de\",URI=\"audio/de.m3u8\"",
                "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",LANGUAGE=\"en\",URI=\"subs/en.m3u8\"",
                "",
                "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\",AUDIO=\"stereo\",SUBTITLES=\"subs\"",
                "video/1080.m3u8",
                "",
            ),
            kept.split('\n'),
        )
    }

    @Test
    fun limitsChooseTheBestVariantThatFits() {
        assertTrue("video/720.m3u8" in keepOneHlsVariant(MASTER, maxBitrate = 3_000_000, maxVideoHeight = null)!!.playlist)
        assertTrue("video/720.m3u8" in keepOneHlsVariant(MASTER, maxBitrate = null, maxVideoHeight = 720)!!.playlist)
        assertTrue("video/360.m3u8" in keepOneHlsVariant(MASTER, maxBitrate = 900_000, maxVideoHeight = null)!!.playlist)
        // Nothing fits, so the variant with the lowest bitrate plays: the one with a picture.
        val lowest = keepOneHlsVariant(MASTER, maxBitrate = 1_000, maxVideoHeight = null)!!.playlist
        assertTrue("video/360.m3u8" in lowest, lowest)
        // The 5.1 group belongs to the 1080p HDR variant only, so it goes with that variant.
        assertFalse("surround" in lowest)
    }

    @Test
    fun aVariantWithAPictureWinsOverSoundOnlyAndSdrOverHdr() {
        val variants = listOf(
            HlsVariant(0, 1, parseHlsAttributes("BANDWIDTH=9000000,RESOLUTION=1920x1080,VIDEO-RANGE=PQ")),
            HlsVariant(2, 3, parseHlsAttributes("BANDWIDTH=2000000,RESOLUTION=1280x720")),
            HlsVariant(4, 5, parseHlsAttributes("BANDWIDTH=96000,CODECS=\"mp4a.40.2\"")),
            HlsVariant(6, 7, parseHlsAttributes("BANDWIDTH=12000000,RESOLUTION=3840x2160,CODECS=\"dvh1.05.06\"")),
        )
        assertEquals(2, chooseHlsVariant(variants, maxBitrate = null, maxVideoHeight = null).tagLine)
        // Sound only plays when nothing else is offered.
        assertEquals(4, chooseHlsVariant(listOf(variants[2]), maxBitrate = null, maxVideoHeight = null).tagLine)
        // Dolby Vision profile 5 too, and among HDR variants a playable one wins.
        assertEquals(0, chooseHlsVariant(listOf(variants[0], variants[3]), maxBitrate = null, maxVideoHeight = null).tagLine)
    }

    /** An HDR display gets the HDR version of the same picture, and any other the SDR one (#447). */
    @Test
    fun anHdrVariantPlaysWhenTheOutputShowsHdr() {
        val variants = listOf(
            HlsVariant(0, 1, parseHlsAttributes("BANDWIDTH=6000000,RESOLUTION=1920x1080,VIDEO-RANGE=SDR")),
            HlsVariant(2, 3, parseHlsAttributes("BANDWIDTH=8000000,RESOLUTION=1920x1080,VIDEO-RANGE=PQ")),
            HlsVariant(4, 5, parseHlsAttributes("BANDWIDTH=7000000,RESOLUTION=1920x1080,VIDEO-RANGE=HLG")),
        )
        assertEquals(2, chooseHlsVariant(variants, null, null, VariantFit(showsHdr = true)).tagLine)
        assertEquals(0, chooseHlsVariant(variants, null, null, VariantFit(showsHdr = false)).tagLine)
        assertEquals(0, chooseHlsVariant(variants, null, null, fit = null).tagLine)
        // HLG when it is the only HDR on offer.
        assertEquals(4, chooseHlsVariant(listOf(variants[0], variants[2]), null, null, VariantFit(showsHdr = true)).tagLine)
        // An SDR stream on an HDR display still plays.
        assertEquals(0, chooseHlsVariant(listOf(variants[0]), null, null, VariantFit(showsHdr = true)).tagLine)
    }

    /**
     * A ladder to 2160p plays nothing larger than the smallest variant that fills the view without
     * being scaled up (#447): a 720p view gets 720p, and a 1080p one 1080p.
     */
    @Test
    fun theVariantIsCappedAtTheSmallestOneThatFillsTheView() {
        val ladder = listOf(360, 720, 1080, 1440, 2160).mapIndexed { index, height ->
            HlsVariant(index * 2, index * 2 + 1, parseHlsAttributes("BANDWIDTH=${(index + 1) * 2_000_000},RESOLUTION=${height * 16 / 9}x$height"))
        }
        fun heightFor(width: Int?, height: Int?, maxVideoHeight: Int? = null) =
            chooseHlsVariant(ladder, null, maxVideoHeight, VariantFit(drawnWidth = width, drawnHeight = height)).height
        assertEquals(720, heightFor(1280, 720))
        assertEquals(1080, heightFor(1920, 1080))
        // Between two variants, the larger one, so the picture is never scaled up.
        assertEquals(1080, heightFor(1422, 800))
        // A landscape picture on a portrait phone is drawn as wide as the phone, 1080 by 608.
        assertEquals(720, heightFor(1080, 2400))
        // Larger than every variant, or no view at all, caps nothing.
        assertEquals(2160, heightFor(5000, 3000))
        assertEquals(2160, heightFor(null, null))
        // The application's own cap still applies.
        assertEquals(720, heightFor(1920, 1080, maxVideoHeight = 720))
    }

    @Test
    fun aFitCapsByTheSmallestSizeThatFillsItsArea() {
        val sizes = listOf(VideoSize(1280, 720), VideoSize(2560, 1440), VideoSize(3840, 2160))
        assertEquals(2560L * 1440, VariantFit(drawnWidth = 1920, drawnHeight = 1080).pixelCap(sizes))
        // An encoder's rounding to a multiple of 16 does not push the cap a rung up.
        assertEquals(1280L * 720, VariantFit(drawnWidth = 1300, drawnHeight = 732).pixelCap(sizes))
        assertNull(VariantFit(drawnWidth = 5000, drawnHeight = 3000).pixelCap(sizes))
        assertNull(VariantFit(drawnHeight = 1080).pixelCap(sizes))
    }

    @Test
    fun crlfLinesAndAByteOrderMarkAreRead() {
        val master = "﻿#EXTM3U\r\n#EXT-X-STREAM-INF:BANDWIDTH=100\r\nlow.m3u8\r\n#EXT-X-STREAM-INF:BANDWIDTH=200\r\nhigh.m3u8\r\n"
        assertEquals(listOf("#EXTM3U", "#EXT-X-STREAM-INF:BANDWIDTH=200", "high.m3u8", ""), keepOneHlsVariant(master, null, null)!!.playlist.split('\n'))
    }

    @Test
    fun aChosenVariantIsKeptWhatEverTheLimitsSayAndAMissingOneIsIgnored() {
        val all = keepOneHlsVariant(MASTER, maxBitrate = null, maxVideoHeight = null)!!
        val lowestIndex = all.variants.indices.minBy { all.variants[it].bandwidth }
        val chosen = keepOneHlsVariant(MASTER, maxBitrate = null, maxVideoHeight = null, wanted = lowestIndex)!!
        assertEquals(lowestIndex, chosen.chosen)
        assertTrue(all.variants[lowestIndex].attributes.getValue("BANDWIDTH") in chosen.playlist.lines().first { it.startsWith("#EXT-X-STREAM-INF") })
        // An index the playlist does not have falls back to the choice by limits.
        assertEquals(all.chosen, keepOneHlsVariant(MASTER, maxBitrate = null, maxVideoHeight = null, wanted = 99)!!.chosen)
    }

    /** RFC 3986, section 5.4, against its base `http://a/b/c/d;p?q`. */
    private companion object {
        val MASTER = listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:6",
            "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"stereo\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,URI=\"audio/en.m3u8\"",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"stereo\",NAME=\"Deutsch\",LANGUAGE=\"de\",URI=\"audio/de.m3u8\"",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"surround\",NAME=\"English 5.1\",LANGUAGE=\"en\",URI=\"audio/en-51.m3u8\"",
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",LANGUAGE=\"en\",URI=\"subs/en.m3u8\"",
            "",
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS=\"avc1.4d401e,mp4a.40.2\",AUDIO=\"stereo\",SUBTITLES=\"subs\"",
            "video/360.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"stereo\",SUBTITLES=\"subs\"",
            "video/720.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\",AUDIO=\"stereo\",SUBTITLES=\"subs\"",
            "video/1080.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1080,CODECS=\"hvc1.2.4.L123.B0,ec-3\",VIDEO-RANGE=PQ,AUDIO=\"surround\"",
            "video/1080-hdr.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.5\",AUDIO=\"stereo\"",
            "audio-only.m3u8",
            "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=200000,RESOLUTION=640x360,URI=\"video/360-iframes.m3u8\"",
            "",
        ).joinToString("\n")
    }
}
