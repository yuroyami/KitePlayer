package io.github.yuroyami.kiteplayer.ffmpeg

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
        val kept = keepOneHlsVariant(MASTER, maxBitrate = null, maxVideoHeight = null)!!
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
        assertTrue("video/720.m3u8" in keepOneHlsVariant(MASTER, maxBitrate = 3_000_000, maxVideoHeight = null)!!)
        assertTrue("video/720.m3u8" in keepOneHlsVariant(MASTER, maxBitrate = null, maxVideoHeight = 720)!!)
        assertTrue("video/360.m3u8" in keepOneHlsVariant(MASTER, maxBitrate = 900_000, maxVideoHeight = null)!!)
        // Nothing fits, so the variant with the lowest bitrate plays: the one with a picture.
        val lowest = keepOneHlsVariant(MASTER, maxBitrate = 1_000, maxVideoHeight = null)!!
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

    @Test
    fun crlfLinesAndAByteOrderMarkAreRead() {
        val master = "﻿#EXTM3U\r\n#EXT-X-STREAM-INF:BANDWIDTH=100\r\nlow.m3u8\r\n#EXT-X-STREAM-INF:BANDWIDTH=200\r\nhigh.m3u8\r\n"
        assertEquals(listOf("#EXTM3U", "#EXT-X-STREAM-INF:BANDWIDTH=200", "high.m3u8", ""), keepOneHlsVariant(master, null, null)!!.split('\n'))
    }

    /** RFC 3986, section 5.4, against its base `http://a/b/c/d;p?q`. */
    @Test
    fun referencesResolveAsRfc3986Says() {
        val base = "http://a/b/c/d;p?q"
        val cases = mapOf(
            "g:h" to "g:h",
            "g" to "http://a/b/c/g",
            "./g" to "http://a/b/c/g",
            "g/" to "http://a/b/c/g/",
            "/g" to "http://a/g",
            "//g" to "http://g",
            "?y" to "http://a/b/c/d;p?y",
            "g?y" to "http://a/b/c/g?y",
            "#s" to "http://a/b/c/d;p?q#s",
            "g#s" to "http://a/b/c/g#s",
            "g?y#s" to "http://a/b/c/g?y#s",
            ";x" to "http://a/b/c/;x",
            "g;x" to "http://a/b/c/g;x",
            "" to "http://a/b/c/d;p?q",
            "." to "http://a/b/c/",
            "./" to "http://a/b/c/",
            ".." to "http://a/b/",
            "../" to "http://a/b/",
            "../g" to "http://a/b/g",
            "../.." to "http://a/",
            "../../" to "http://a/",
            "../../g" to "http://a/g",
            "../../../g" to "http://a/g",
            "../../../../g" to "http://a/g",
            "/./g" to "http://a/g",
            "/../g" to "http://a/g",
            "g." to "http://a/b/c/g.",
            ".g" to "http://a/b/c/.g",
            "g.." to "http://a/b/c/g..",
            "..g" to "http://a/b/c/..g",
            "./../g" to "http://a/b/g",
            "./g/." to "http://a/b/c/g/",
            "g/./h" to "http://a/b/c/g/h",
            "g/../h" to "http://a/b/c/h",
            "g;x=1/./y" to "http://a/b/c/g;x=1/y",
            "g;x=1/../y" to "http://a/b/c/y",
        )
        for ((reference, expected) in cases) {
            assertEquals(expected, resolveUriReference(base, reference), "for \"$reference\"")
        }
    }

    @Test
    fun aRedirectedPlaylistNamesItsSegmentsAbsolutely() {
        val playlist = "#EXTM3U\r\n#EXT-X-KEY:METHOD=AES-128,URI=\"../keys/k1\",IV=0x01\r\n" +
            "#EXT-X-MAP:URI=\"init.mp4\"\r\n#EXTINF:2.0,\r\nseg-0.m4s?sig=1\r\n" +
            "#EXTINF:2.0,\r\nhttps://other.test/seg-1.m4s\r\n#EXT-X-ENDLIST\r\n"
        val rewritten = absoluteHlsAddresses(playlist, "https://edge.test/moved/v1/index.m3u8")
        assertEquals(
            "#EXTM3U\r\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://edge.test/moved/keys/k1\",IV=0x01\r\n" +
                "#EXT-X-MAP:URI=\"https://edge.test/moved/v1/init.mp4\"\r\n#EXTINF:2.0,\r\n" +
                "https://edge.test/moved/v1/seg-0.m4s?sig=1\r\n#EXTINF:2.0,\r\nhttps://other.test/seg-1.m4s\r\n" +
                "#EXT-X-ENDLIST\r\n",
            rewritten,
        )
    }

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
