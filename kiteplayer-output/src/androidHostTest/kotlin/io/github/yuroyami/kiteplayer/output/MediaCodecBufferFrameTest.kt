package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaCodecBufferFrameTest {
    @Test
    fun renderWithoutSurfaceConsumesExactlyOnceAsDiscard() {
        val commands = mutableListOf<MediaCodecReleaseCommand>()
        val frame = frame(commands)

        assertFalse(frame.renderAt(123L) {})
        frame.close()
        assertFalse(frame.renderAt(456L) {})

        assertEquals(1, commands.size)
        assertEquals(null, commands.single().renderNanos)
        assertEquals(null, commands.single().displayVersion)
    }

    @Test
    fun closeConsumesExactlyOnce() {
        val commands = mutableListOf<MediaCodecReleaseCommand>()
        val frame = frame(commands)

        frame.close()
        frame.close()

        assertEquals(1, commands.size)
        assertEquals(7, commands.single().outputIndex)
        assertEquals(9L, commands.single().decoderEpoch)
    }

    @Test
    fun presentationTranslationPreservesTheRemainingDelay() {
        assertEquals(
            8_000_000_400L,
            translatePresentationTime(
                targetNanos = 1_000_000_500L,
                engineNowNanos = 1_000_000_100L,
                codecNowNanos = 8_000_000_000L,
            ),
        )
    }

    @Test
    fun headlessRendererAdvertisesItsPairedDecoderAndSurfaceKind() = runBlocking {
        val overlays = mutableListOf<Any?>()
        val renderer = AndroidSurfaceVideoRenderer(
            convert = { ByteArray(0) },
            onOverlay = { overlays += it },
        )
        try {
            assertEquals(1, renderer.videoDecoderFactories().size)
            assertTrue(HwSurfaceKind.MediaCodecBuffer in renderer.supportedHardwareSurfaces())
            renderer.setOverlay(null)
            assertEquals(listOf<Any?>(null), overlays)
        } finally {
            renderer.close()
        }
        assertEquals(listOf<Any?>(null, null), overlays)
    }

    private fun frame(commands: MutableList<MediaCodecReleaseCommand>) = MediaCodecBufferFrame(
        owner = MediaCodecFrameOwner(commands::add),
        outputIndex = 7,
        decoderEpoch = 9L,
        target = MediaCodecSurfaceTarget(),
        pts = Pts(11),
        duration = Pts(12),
        generation = Generation(13),
        size = VideoSize(16, 9),
        colorSpace = ColorSpaceInfo.Unspecified,
    )
}
