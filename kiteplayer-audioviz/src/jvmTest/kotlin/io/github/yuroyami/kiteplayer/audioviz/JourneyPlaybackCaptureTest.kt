package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Mandala
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Pipe
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Directed form changes with real analysed audio. Offline 12 fps, not phone cadence evidence. */
class JourneyPlaybackCaptureTest {
    @Test
    fun captureTheContinuousFormsWhenRequested() {
        val path = System.getenv("JOURNEY_PCM")
        assumeTrue("Optional 48 kHz mono f32le capture", path != null)
        useSkiaGraphics()
        val buffer = ByteBuffer.wrap(File(checkNotNull(path)).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(buffer.remaining()).also { buffer.get(it) }
        val seconds = minOf(36, samples.size / 48000 - 3)
        assertTrue(seconds >= 30)
        for (viz in listOf(Mandala(), Pipe())) {
            viz.restart()
            viz.params.single { it.name == "Journey" }.value = 0f
            val selector = viz.params.single { it.name == "Form" }
            val forms = when (viz) {
                is Mandala -> listOf(0, 1, 2, 3, 4)
                is Pipe -> listOf(0, 1, 2, 0)
                else -> listOf(0, 1, 2, 3, 4)
            }
            val directory = File("build/journey-preview/music/${viz.name.replace(' ', '-')}").apply { mkdirs() }
            directory.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
            val player = SongPlayer(samples, bandCount = 40)
            val image = ImageBitmap(512, 288)
            val scope = CanvasDrawScope()
            var musicTime = 0f
            for (frame in 0 until seconds * 12) {
                val audio = player.next(1f / 12f)
                musicTime += audio.motionRate / 12f
                val state = VizRenderState(audio, (frame + 1f) / 12f, 1f / 12f, VizPalette.Prism, musicTime, player.future)
                selector.value = forms[minOf(forms.lastIndex, frame / 12 / 7)].toFloat()
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(512f, 288f)) {
                    drawComposedFrame(viz, state, null)
                }
                ImageIO.write(with(RenderHarness) { image.toBufferedImage() }, "png", File(directory, "%04d.png".format(frame)))
                if (frame % 84 == 0) println("${viz.name}: ${frame / 12}s, form=${selector.value}")
            }
        }
    }
}
