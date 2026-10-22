package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A web page has no sockets and no file system of its own, so on the web an item with no reader is
 * refused for its scheme, whatever the scheme, before the codec module is asked anything (#395).
 */
class WebSchemeTest {

    @Test
    fun everyAddressWithNoReaderIsRefusedForItsScheme() = runTest {
        val addresses = mapOf(
            "file" to "/videos/a.mkv",
            "fd" to "fd:",
            "pipe" to "pipe:0",
            "data" to "data:,nothing",
            "http" to "http://example.test/a.mkv",
            "https" to "https://example.test/a.mkv",
            "tcp" to "tcp://example.test:9000",
            "udp" to "udp://239.0.0.1:5004",
            "rtp" to "rtp://239.0.0.1:5004",
            "rtsp" to "rtsp://camera.test/stream1",
            "rtmp" to "rtmp://example.test/live/kite",
            "srt" to "srt://example.test:9000",
        )
        for ((scheme, address) in addresses) {
            val refusal = try {
                openItem(MediaItem(address)).source.close()
                fail("$address opened on the web")
            } catch (refused: PlaybackException) {
                refused
            }
            val error = assertIs<PlaybackError.SchemeUnsupported>(refusal.error, "$address was ${refusal.error}")
            assertEquals(scheme, error.scheme)
            assertTrue("kiteplayer-network" in assertNotNull(error.detail), "${error.detail}")
        }
    }
}
