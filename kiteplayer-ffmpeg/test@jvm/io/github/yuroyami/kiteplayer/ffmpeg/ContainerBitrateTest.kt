package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/**
 * What the container says its bit rate is, carried through to the stats.
 *
 * The number was read by the media library on every open and then dropped on the floor, so the
 * stats had a field for it that was documented as always null. It is deliberately not the same
 * figure as the measured throughput beside it: on variable-bit-rate media the two differ, and
 * which one a reader wants depends on whether they are asking what the file claims or what
 * actually flowed.
 */
class ContainerBitrateTest {

    private val mediaDir: String = System.getenv("KITEPLAYER_TESTMEDIA") ?: "testmedia"

    @Test
    fun `a file that declares a bit rate reports it`() = runTest {
        val file = File(mediaDir, "sync1080p30.mp4")
        assertTrue(file.exists(), "the fixture is missing at ${file.absolutePath}")

        val session = KiteFFmpegMediaBackend().open(MediaItem(file.absolutePath))
        try {
            val declared: Long = assertNotNull(
                session.source.containerBitrateBps,
                "the container declares a bit rate and the source reported none",
            )
            assertTrue(declared > 0L, "a declared bit rate of $declared is not a bit rate")
        } finally {
            session.close()
        }
    }
}
