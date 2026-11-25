package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FFmpeg backend's decoder options and low-delay decoding reach only its own decoders, so a
 * session says so and the engine keeps the stream off a renderer's own decoder (#609).
 */
class OwnVideoDecodersReasonTest {

    private val clip = File(formatMatrixMediaDir() ?: error("testmedia missing; run scripts/testmedia.sh"), "tsoffset1400.ts")

    private fun reasonOf(backend: KiteFFmpegMediaBackend): String? = runBlocking {
        backend.open(MediaItem(clip.absolutePath)).use { it.ownVideoDecodersReason }
    }

    @Test
    fun anUntunedBackendLeavesTheRendererFree() {
        assertNull(reasonOf(KiteFFmpegMediaBackend()))
    }

    @Test
    fun decoderOptionsAreNamed() {
        val reason = assertNotNull(reasonOf(KiteFFmpegMediaBackend(decoderOptions = mapOf("skip_frame" to "nokey"))))
        assertTrue("skip_frame" in reason, reason)
    }

    @Test
    fun lowDelayDecodingIsNamed() {
        assertNotNull(reasonOf(KiteFFmpegMediaBackend(lowDelayDecode = true)))
    }
}
