package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The URL fallback bounds every network read with the timeout option of the protocol that reads
 * it, in microseconds, lets an SDP file reach the RTP session it describes, and leaves every other
 * URI and every option the item set alone.
 */
class UrlFallbackOptionsTest {

    @Test
    fun anHttpUriGetsTheReadTimeout() {
        assertEquals(mapOf("rw_timeout" to "10000000"), urlFallbackOptions("http://example.test/a.mkv", emptyMap()))
    }

    @Test
    fun aTcpUriGetsTheReadTimeout() {
        assertEquals(mapOf("rw_timeout" to "10000000"), urlFallbackOptions("tcp://example.test:9000", emptyMap()))
    }

    @Test
    fun aLocalFileGetsNoTimeout() {
        assertEquals(emptyMap(), urlFallbackOptions("/videos/a.mkv", emptyMap()))
        assertEquals(emptyMap(), urlFallbackOptions("file:///videos/a.mkv", emptyMap()))
    }

    @Test
    fun anItemsOwnReadTimeoutWins() {
        assertEquals(
            mapOf("rw_timeout" to "500000"),
            urlFallbackOptions("http://example.test/a.mkv", mapOf("rw_timeout" to "500000")),
        )
    }

    @Test
    fun theOtherOptionsAreKept() {
        val options = urlFallbackOptions("HTTP://example.test/a.mkv", mapOf("headers" to "X-Test: 1\r\n"))
        assertEquals("X-Test: 1\r\n", options["headers"])
        assertEquals("10000000", options["rw_timeout"])
    }

    @Test
    fun anRtmpUriGetsTheReadTimeout() {
        assertEquals(mapOf("rw_timeout" to "10000000"), urlFallbackOptions("rtmp://example.test/live/kite", emptyMap()))
    }

    @Test
    fun aUdpUriGetsTheTimeoutOfItsOwnProtocol() {
        // FFmpeg's udp protocol replaces rw_timeout with its own timeout when it opens.
        assertEquals(mapOf("timeout" to "10000000"), urlFallbackOptions("udp://239.0.0.1:5000", emptyMap()))
    }

    @Test
    fun anRtspUriGetsTheTimeoutOfItsDemuxer() {
        // The RTSP demuxer waits for ever unless its own timeout is set.
        assertEquals(mapOf("timeout" to "10000000"), urlFallbackOptions("rtsp://camera.test/stream1", emptyMap()))
    }

    @Test
    fun anItemsOwnTimeoutWinsForALiveScheme() {
        assertEquals(mapOf("timeout" to "2000000"), urlFallbackOptions("RTSP://camera.test/stream1", mapOf("timeout" to "2000000")))
    }

    @Test
    fun anSdpFileMayReachTheRtpSessionItDescribes() {
        val expected = mapOf("protocol_whitelist" to "file,udp,rtp")
        assertEquals(expected, urlFallbackOptions("/cameras/door.sdp", emptyMap()))
        assertEquals(expected, urlFallbackOptions("file:///cameras/door.SDP", emptyMap()))
        assertEquals(expected, urlFallbackOptions("/cameras/door.txt", emptyMap(), formatHint = "sdp"))
    }

    @Test
    fun anSdpFileKeepsTheProtocolsTheItemNames() {
        val own = mapOf("protocol_whitelist" to "file,udp,rtp,crypto")
        assertEquals(own, urlFallbackOptions("/cameras/door.sdp", own))
    }

    @Test
    fun anSdpServedOverHttpNeedsNoList() {
        // Only an input opened through file holds its nested opens to file, crypto and data.
        assertEquals(mapOf("rw_timeout" to "10000000"), urlFallbackOptions("http://example.test/door.sdp", emptyMap()))
    }
}
