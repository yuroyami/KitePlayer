package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.FrameShape
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A decoder that declares its frames is checked against the renderer before anything plays (#102).
 * At open, a decoder whose frames the attached renderer cannot show is passed over. At attach, a
 * renderer that cannot show the running decoder's frames is refused, and the one before stays.
 */
class FrameShapeNegotiationTest {

    private val surfaceFrames = FrameShape.Surface(HwSurfaceKind.MediaCodecBuffer, PlayerPixelFormat.Opaque)

    /** [inner] with a declared [output], counting its close. */
    private class Declaring(
        private val inner: VideoDecoder,
        override val output: FrameShape?,
        private val onClose: () -> Unit,
    ) : VideoDecoder by inner {
        override fun close() {
            onClose()
            inner.close()
        }
    }

    @Test
    fun aDecoderWhoseFramesTheRendererCannotShowIsPassedOverAtOpen() = runTest {
        var harness: CoreHarness? = null
        var created = 0
        var closed = 0
        val surfaceDecoders = object : VideoDecoderFactory {
            override val name: String = "surface decoder"
            override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
                val inner = harness!!.backend.sessions.last().videoDecoders.first().create(stream, hwdec) ?: return null
                created++
                return Declaring(inner, surfaceFrames) { closed++ }
            }
        }
        // The recording renderer shows frames in memory and no hardware surface.
        val started = CoreHarness(this, renderer = RecordingRenderer(decoderFactories = listOf(surfaceDecoders)))
        harness = started
        started.openWithRenderer()
        started.core.play()
        started.run(500.milliseconds)
        assertEquals(1, created, "the renderer's own decoder is tried first")
        assertEquals(1, closed, "and passed over, because the renderer cannot show its frames")
        assertTrue(started.renderer!!.count > 0, "the backend's decoder draws the picture instead")
        started.close()
    }

    @Test
    fun aRendererThatCannotShowTheRunningDecodersFramesIsRefusedAndTheOldOneStays() = runTest {
        val faults = FaultPlan().apply { videoDecoderOutput = surfaceFrames }
        val first = RecordingRenderer()
        val showsSurfaces = object : VideoRenderer by first {
            override fun accepts(shape: FrameShape): Boolean = true
        }
        val harness = CoreHarness(this, faults = faults, renderer = null)
        harness.core.attachRenderer(showsSurfaces)
        harness.open()
        harness.core.play()
        harness.run(300.milliseconds)
        val before = first.count
        assertTrue(before > 0, "the first renderer draws")

        val refused = RecordingRenderer()
        val failure = assertFailsWith<PlaybackException> { harness.core.attachRenderer(refused) }
        assertEquals(surfaceFrames, assertIs<PlaybackError.RendererIncompatible>(failure.error).frames)
        harness.run(300.milliseconds)
        assertTrue(first.count > before, "the renderer attached before keeps drawing")
        assertEquals(0, refused.count, "the refused renderer never gets a frame")
        assertTrue(
            harness.core.warningHistory().map { it.warning }
                .any { it is PlaybackWarning.CommandRefused && it.member == "attachRenderer" },
            "the refusal is warned as well",
        )
        harness.close()
    }

    @Test
    fun aDecoderThatDeclaresNothingKeepsTheFirstFrameAsTheOnlyCheck() = runTest {
        val first = RecordingRenderer()
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(first)
        harness.open()
        harness.core.play()
        harness.run(200.milliseconds)
        val second = RecordingRenderer()
        harness.core.attachRenderer(second)
        harness.run(300.milliseconds)
        assertTrue(second.count > 0, "with nothing declared the attach is not checked")
        harness.close()
    }
}
