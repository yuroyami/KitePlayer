package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Odyssey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Optional offline listening/viewing capture. This is not a device performance measurement. */
class OdysseyPlaybackCaptureTest {
    @Test
    fun captureDecodedMusicThroughTheRealAnalyzerAndDrawing() {
        val path = System.getenv("ODYSSEY_PCM")
        assumeTrue("Set ODYSSEY_PCM to 48 kHz mono f32le audio for an offline capture", path != null)
        useSkiaGraphics()
        val floats = ByteBuffer.wrap(File(checkNotNull(path)).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(floats.remaining()).also { floats.get(it) }
        val seconds = minOf(30, samples.size / 48_000 - 3)
        assertTrue(seconds > 5)
        val player = SongPlayer(samples, bandCount = 40)
        val odyssey = Odyssey() // A fresh world each run; this clip is for eyes, not for a regression.
        val directory = File("build/odyssey-playback").apply { mkdirs() }
        val output = ImageBitmap(384, 240)
        val scope = CanvasDrawScope()
        var motionTime = 0f
        for (index in 0 until seconds * 12) {
            val frame = player.next(1f / 12f)
            motionTime += frame.motionRate / 12f
            val state = VizRenderState(frame, (index + 1f) / 12f, 1f / 12f, VizPalette.Prism, motionTime, player.future)
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(output), Size(384f, 240f)) {
                with(odyssey) { draw(state) }
            }
            ImageIO.write(output.toBufferedImage(), "png", File(directory, "%04d.png".format(index)))
            if (index % 36 == 0) println("Odyssey music capture: ${index / 12}s / ${seconds}s, energy=${frame.energy}, density=${frame.density}")
        }
    }
}
