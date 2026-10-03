package io.github.yuroyami.kiteplayer.network.dash

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How the automatic transport tells a DASH manifest from media (#400). */
class DashDetectionTest {

    @Test
    fun aManifestIsDeclaredByItsTypeOrItsPath() {
        assertTrue(DashDetection.declared("application/dash+xml", "https://cdn.test/play"))
        assertTrue(DashDetection.declared("Application/DASH+XML; charset=utf-8", null))
        assertTrue(DashDetection.declared(null, "https://cdn.test/vod/manifest.mpd?token=a/b.mp4"))
        assertTrue(DashDetection.declared("text/plain", "https://cdn.test/vod/MANIFEST.MPD#t=10"))
        assertFalse(DashDetection.declared(null, "https://cdn.test/vod/movie.mp4?list=a.mpd"))
        assertFalse(DashDetection.declared(null, "https://cdn.test/mpd/movie.mp4"))
        // A server that calls the bytes video is believed over a path that says otherwise.
        assertFalse(DashDetection.declared("video/mp4", "https://cdn.test/vod/movie.mpd"))
        assertFalse(DashDetection.declared(null, null))
    }

    @Test
    fun onlyATypeThatLeavesRoomForAManifestIsWorthALook() {
        assertTrue(DashDetection.worthSniffing(null))
        assertTrue(DashDetection.worthSniffing("application/octet-stream"))
        assertTrue(DashDetection.worthSniffing("text/plain; charset=utf-8"))
        assertTrue(DashDetection.worthSniffing("application/xml"))
        assertTrue(DashDetection.worthSniffing("binary/octet-stream"))
        assertFalse(DashDetection.worthSniffing("video/mp4"))
        assertFalse(DashDetection.worthSniffing("audio/mpeg"))
        assertFalse(DashDetection.worthSniffing("image/jpeg"))
        // An HLS playlist is the backend's to recognise.
        assertFalse(DashDetection.worthSniffing("application/vnd.apple.mpegurl"))
        assertFalse(DashDetection.worthSniffing("application/x-mpegURL"))
    }

    @Test
    fun aManifestIsRecognisedByItsRootElement() {
        assertTrue(sniff("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\">"))
        assertTrue(sniff("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<MPD type=\"dynamic\">"))
        assertTrue(sniff("﻿<?xml version=\"1.0\"?>\r\n<!-- Created with a packager, version 1.2 -->\n<MPD>"))
        assertTrue(sniff("<?xml version=\"1.0\"?><!DOCTYPE MPD><mpd:MPD xmlns:mpd=\"urn:mpeg:dash:schema:mpd:2011\">"))
        assertTrue(sniff("  \n\t<MPD\n  type=\"static\">"))
        assertFalse(sniff("<?xml version=\"1.0\"?><MPDX>"))
        assertFalse(sniff("<?xml version=\"1.0\"?><SmoothStreamingMedia MajorVersion=\"2\">"))
        assertFalse(sniff("<!DOCTYPE html><html><head><title>Not found</title>"))
        assertFalse(sniff("#EXTM3U\n#EXT-X-VERSION:3\n"))
        assertFalse(sniff("WEBVTT\n\n"))
        // A comment that the bytes read so far do not close says nothing yet, so it is not a manifest.
        assertFalse(sniff("<?xml version=\"1.0\"?><!-- a comment that goes on"))
        assertFalse(DashDetection.startsLikeMpd(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70), 8))
        assertFalse(DashDetection.startsLikeMpd(ByteArray(0), 0))
    }

    @Test
    fun onlyTheBytesReadCount() {
        val bytes = "<MPD type=\"static\">".encodeToByteArray().copyOf(64)
        assertTrue(DashDetection.startsLikeMpd(bytes, 19))
        assertFalse(DashDetection.startsLikeMpd(bytes, 3))
    }

    private fun sniff(text: String): Boolean = text.encodeToByteArray().let { DashDetection.startsLikeMpd(it, it.size) }
}
