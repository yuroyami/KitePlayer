package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The URL fallback bounds every network read with FFmpeg's `rw_timeout`, in microseconds, and
 * leaves every other URI and every option the item set alone.
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
}
