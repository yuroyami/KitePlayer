package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.FieldOrder
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A stream whose frames pass a filter never decodes on the renderer's own decoder (#609). The
 * engine asked the renderer's factories first and they know nothing of filters, so on Android the
 * view's MediaCodec decoder played a cropped item uncropped, an interlaced one undeinterlaced, and a
 * filtered item under HwdecPolicy.Require in hardware, all with no warning.
 */
@OptIn(KitePlayerLowLevelApi::class)
class FilteredStreamDecoderTest {

    /** A renderer decoder that counts what it was asked for and decodes on the backend's decoder. */
    private class RendererDecoders(private val harness: () -> CoreHarness) : VideoDecoderFactory {
        override val name: String = "renderer decoder"
        var asked = 0

        override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
            asked++
            return harness().backend.sessions.last().videoDecoders.first().create(stream, hwdec)
        }
    }

    private class Run(val harness: CoreHarness, val rendererDecoders: RendererDecoders)

    private suspend fun TestScope.play(
        item: MediaItem,
        config: PlayerConfig = PlayerConfig(),
        script: MediaScript = MediaScript(),
    ): Run {
        var started: CoreHarness? = null
        val decoders = RendererDecoders { started!! }
        started = CoreHarness(this, script = script, config = config, renderer = RecordingRenderer(decoderFactories = listOf(decoders)))
        started.attachRenderer()
        started.core.open(item)
        started.core.play()
        started.run(300.milliseconds)
        return Run(started, decoders)
    }

    private fun CoreHarness.hardwareWarnings() =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.HardwareDecodeUnavailable>()

    @Test
    fun anUnfilteredStreamStillDecodesOnTheRenderersDecoder() = runTest {
        val run = play(MediaItem("scripted://media"))
        assertEquals(1, run.rendererDecoders.asked, "the renderer's own decoder is asked first")
        assertTrue(run.harness.hardwareWarnings().isEmpty())
        run.harness.close()
    }

    @Test
    fun aFilteredItemDecodesOnTheBackendWithAWarning() = runTest {
        val run = play(MediaItem("scripted://media", videoFilter = "null"))
        assertEquals(0, run.rendererDecoders.asked, "the renderer's decoder took a stream that must be filtered")
        assertTrue(run.harness.renderer!!.count > 0, "the backend's decoder draws the picture")
        assertTrue(run.harness.hardwareWarnings().isNotEmpty(), "the filter turned the renderer's decoder down silently")
        run.harness.close()
    }

    @Test
    fun anInterlacedStreamDecodesOnTheBackend() = runTest {
        val run = play(MediaItem("scripted://media"), script = MediaScript(videoFieldOrder = FieldOrder.TopFirst))
        assertEquals(0, run.rendererDecoders.asked, "an interlaced stream skipped the deinterlacer")
        run.harness.close()
    }

    @Test
    fun deinterlacingAlwaysDecodesEveryStreamOnTheBackend() = runTest {
        val run = play(MediaItem("scripted://media"), config = PlayerConfig(deinterlace = DeinterlacePolicy.Always))
        assertEquals(0, run.rendererDecoders.asked)
        run.harness.close()
    }

    @Test
    fun deinterlacingOffLeavesAnInterlacedStreamToTheRenderer() = runTest {
        val run = play(
            MediaItem("scripted://media"),
            config = PlayerConfig(deinterlace = DeinterlacePolicy.Off),
            script = MediaScript(videoFieldOrder = FieldOrder.TopFirst),
        )
        assertEquals(1, run.rendererDecoders.asked)
        run.harness.close()
    }

    @Test
    fun requiringHardwareForAFilteredItemRefusesTheVideoTrack() = runTest {
        val run = play(
            MediaItem("scripted://media", videoFilter = "null"),
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Require),
        )
        assertEquals(0, run.rendererDecoders.asked)
        assertEquals(0, run.harness.renderer!!.count, "a filtered item played in hardware under Require")
        assertTrue(
            run.harness.core.warningHistory().map { it.warning }.any { it is PlaybackWarning.TrackDeselected },
            "the video track was not refused",
        )
        run.harness.close()
    }
}
