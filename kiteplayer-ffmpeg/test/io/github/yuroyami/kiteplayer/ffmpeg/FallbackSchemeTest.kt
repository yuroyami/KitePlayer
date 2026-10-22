package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The URL fallback reads an address's scheme as FFmpeg does, and refuses one it has no protocol for (#395). */
class FallbackSchemeTest {

    @Test
    fun aPathHasTheFileScheme() {
        assertEquals("file", fallbackScheme("/videos/a.mkv"))
        assertEquals("file", fallbackScheme("videos/a.mkv"))
        assertEquals("file", fallbackScheme("C:\\videos\\a.mkv"))
        assertEquals("file", fallbackScheme("my videos/a:b.mkv"))
    }

    @Test
    fun aSchemeIsReadInLowerCase() {
        assertEquals("rtsp", fallbackScheme("RTSP://camera.test/stream1"))
        assertEquals("rtmp", fallbackScheme("rtmp://example.test/live/kite"))
        assertEquals("fd", fallbackScheme("fd:"))
        assertEquals("file", fallbackScheme("file:///videos/a.mkv"))
    }

    @Test
    fun theSchemeReachesFFmpegInLowerCase() {
        // FFmpeg compares a protocol's name exactly, so RTSP:// would find none.
        assertEquals("rtsp://camera.test/Stream1", fallbackAddress("RTSP://camera.test/Stream1"))
        assertEquals("/videos/A.mkv", fallbackAddress("/videos/A.mkv"))
    }

    @Test
    fun anRtpAddressCarriesTheReadTimeout() {
        // FFmpeg's RTP reader waits for its first packet through the protocol, which reads the address alone.
        assertEquals("rtp://239.0.0.1:5004?timeout=10000000", fallbackAddress("rtp://239.0.0.1:5004"))
        assertEquals("rtp://239.0.0.1:5004?sources=10.0.0.2&timeout=10000000", fallbackAddress("RTP://239.0.0.1:5004?sources=10.0.0.2"))
        assertEquals("rtp://239.0.0.1:5004?timeout=500000", fallbackAddress("rtp://239.0.0.1:5004?timeout=500000"))
    }

    @Test
    fun anSrtAddressIsRefusedForItsScheme() {
        val refusal = assertFailsWith<PlaybackException> { requireFallbackScheme("srt://contribution.test:9000") }
        val error = assertIs<PlaybackError.SchemeUnsupported>(refusal.error)
        assertEquals("srt", error.scheme)
        if (fallbackSchemes.isNotEmpty()) assertTrue("libsrt" in assertNotNull(error.detail), "${error.detail}")
    }

    @Test
    fun aTlsSchemeIsRefusedAndSaysWhy() {
        for (scheme in listOf("rtmps", "rtsps")) {
            val refusal = assertFailsWith<PlaybackException> { requireFallbackScheme("$scheme://camera.test/live") }
            val error = assertIs<PlaybackError.SchemeUnsupported>(refusal.error)
            assertEquals(scheme, error.scheme)
            if (fallbackSchemes.isNotEmpty()) assertTrue("TLS" in assertNotNull(error.detail), "${error.detail}")
        }
    }

    @Test
    fun anHttpsAddressNamesTheModuleThatOpensIt() {
        val refusal = assertFailsWith<PlaybackException> { requireFallbackScheme("https://example.test/a.mkv") }
        val error = assertIs<PlaybackError.SchemeUnsupported>(refusal.error)
        assertEquals("https", error.scheme)
        assertTrue("kiteplayer-network" in assertNotNull(error.detail), "${error.detail}")
    }

    @Test
    fun everyListedSchemePasses() {
        for (scheme in fallbackSchemes) requireFallbackScheme("$scheme://example.test/a")
    }
}
