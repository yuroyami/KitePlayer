package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pixel displacement observes the inputs a feedback field actually receives. */
class WarpInputsTest {
    init { useSkiaGraphics() }

    private val ramp = ImageBitmap(128, 128).also { image ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(128f, 128f)) {
            repeat(128) { x ->
                drawRect(Color(x / 127f, 0f, 0f), Offset(x.toFloat(), 0f), Size(1f, 128f))
            }
        }
    }

    @Test
    fun feedbackWaveformsUseTheSharedAmplitudeGain() {
        val runner = WarpRunner("float2 warpField(float2 uv) { return float2(scopeAt(0.5), 0); }")
        assertEquals((64f + 0.4f * 64f) / 127f, read(runner, frame(0.2f)), 0.014f)
        assertEquals((64f + 0.1f * 64f) / 127f, read(runner, frame(0.05f)), 0.014f)
        assertEquals((64f - 0.4f * 64f) / 127f, read(runner, frame(-0.2f)), 0.014f)
    }

    @Test
    fun feedbackReceivesAcceptedBeatEvidenceAndClearsItWhenUnknown() {
        val runner = WarpRunner("""
            float2 warpField(float2 uv) {
                return float2(uBeatUsable * 0.1 + uBeatPhase * 0.2 + uBpm / 1200.0 + uBeatIn * 0.2, 0);
            }
        """)
        val rhythm = RhythmEstimate(0L, 20_000L, 1_000_000L, 1L, 120f, 0.8f, 0.9f,
            true, 0.25f, 60f, 0.7f)
        // 0.1 + 0.05 + 0.1 + 0.075 = 0.325 units, each half the image height.
        assertEquals((64f + 0.325f * 64f) / 127f, read(runner, frame(rhythm = rhythm)), 0.014f)
        // Unknown beat has no phase and a -1 countdown, including after accepted evidence.
        assertEquals((64f - 0.2f * 64f) / 127f, read(runner, frame()), 0.014f)
    }

    private fun frame(amplitude: Float = 0f, rhythm: RhythmEstimate? = null): SpectrumFrame {
        val zero = EnergyDriver(0f, 0f, 0f)
        val drivers = EnergyDrivers(zero, zero, zero, zero, emptyArray(), 0.25, 4.0, false, false, 0)
        return SpectrumFrame(0L, FloatArray(40), FloatArray(40), FloatArray(256) { amplitude },
            0f, 0f, 0f, 0f, 0f, 0f, drivers = drivers, rhythm = rhythm,
            bpm = rhythm?.bpm ?: 0f, beatPhase = rhythm?.beatPhase ?: 0f,
            beatInSeconds = rhythm?.beatInSeconds ?: -1f)
    }

    private fun read(runner: WarpRunner, frame: SpectrumFrame): Float {
        val state = VizRenderState(frame, 1f, 1f / 60f, VizPalette.Prism)
        assertTrue(runner.prepare(ramp, state, 128f, 128f, 1f, 1f, 0f, 1f, 1f), runner.error)
        val output = ImageBitmap(128, 128)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output), Size(128f, 128f)) {
            drawRect(checkNotNull(runner.brush()))
        }
        return output.toPixelMap()[64, 64].red
    }
}
