package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.SOFT_BUFFER_SCALE
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.drawComposedFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.drawVisualizationFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.restart
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import org.junit.Assume.assumeTrue
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders stills of chosen drawings at chosen seconds of a real song, for looking at rather than
 * for asserting. It skips unless `VIZ_PCM` names a 48 kHz mono float32 little-endian file:
 *
 * ```
 * ffmpeg -ss 20 -t 75 -i song.mp3 -ac 1 -ar 48000 -f f32le song.f32
 * VIZ_PCM=song.f32 VIZ_NAMES="Bars,Alchemy" VIZ_AT="10,25,40" ./gradlew :kiteplayer-audioviz:jvmTest --tests '*StillsTool*'
 * ```
 *
 * `VIZ_NAMES` is a comma list of catalogue names or `all`. `VIZ_AT` lists seconds into the file.
 * `VIZ_SIZE` is `WIDTHxHEIGHT` (960x540 by default; shader drawings render at half that, because
 * the processor runs their program one pixel at a time here). `VIZ_PALETTE` names a palette.
 * `VIZ_OUT` is the output directory (`build/stills` by default). One strip per drawing lands
 * there, all its stills side by side with the second and the song's readings printed under each.
 *
 * The analyser runs at sixty frames a second from the start of the file, so every still hears
 * the same history a window would. Only the last frames before each still are drawn, so trails
 * and particles have time to form and a shader does not cost a whole song of CPU rendering.
 */
class StillsTool {

    @Test
    fun renderStills() {
        val pcm = System.getenv("VIZ_PCM")
        assumeTrue("Set VIZ_PCM to a 48 kHz mono f32le file to render stills", pcm != null)
        useSkiaGraphics()
        val names = (System.getenv("VIZ_NAMES") ?: "all").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val seconds = (System.getenv("VIZ_AT") ?: "10,25,40").split(",").map { it.trim().toFloat() }.sorted()
        val size = (System.getenv("VIZ_SIZE") ?: "960x540").split("x").map { it.toInt() }
        val out = File(System.getenv("VIZ_OUT") ?: "build/stills").apply { mkdirs() }
        val paletteName = System.getenv("VIZ_PALETTE")
        val palette = VizPalette.entries.firstOrNull { it.name.equals(paletteName, ignoreCase = true) } ?: VizPalette.Prism
        val samples = readFloats(File(checkNotNull(pcm)))
        val catalogue = VizCatalog.create()
        val chosen = if (names == listOf("all")) catalogue else names.mapNotNull { wanted ->
            catalogue.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
                ?: run { println("no drawing named $wanted"); null }
        }
        for (drawing in chosen) {
            val shader = drawing is ShaderPreset || drawing.warp != null
            val width = if (shader) size[0] / 2 else size[0]
            val height = if (shader) size[1] / 2 else size[1]
            val warm = (System.getenv("VIZ_WARM")?.toIntOrNull()) ?: if (shader) 24 else 150
            val started = System.nanoTime()
            val stills = renderStills(drawing, samples, seconds, width, height, warm, palette)
            val strip = strip(drawing.name, stills, seconds)
            val file = File(out, drawing.name.replace(Regex("[^A-Za-z0-9]+"), "-") + ".png")
            ImageIO.write(strip, "png", file)
            println("still: ${file.absolutePath} (${(System.nanoTime() - started) / 1_000_000} ms, ${width}x$height, warm $warm)")
        }
    }

    private class Still(val image: BufferedImage, val energy: Float, val mood: Float, val bpm: Float, val kick: Float)

    private fun renderStills(
        drawing: Visualization,
        samples: FloatArray,
        seconds: List<Float>,
        width: Int,
        height: Int,
        warm: Int,
        palette: VizPalette,
    ): List<Still> {
        val player = SongPlayer(samples, bandCount = 48)
        val delta = 1f / 60f
        val scale = if (drawing.bloom > 0) SOFT_BUFFER_SCALE else 1f
        val echoWidth = (width * scale).toInt().coerceAtLeast(1)
        val echoHeight = (height * scale).toInt().coerceAtLeast(1)
        var front = ImageBitmap(echoWidth, echoHeight)
        var back = ImageBitmap(echoWidth, echoHeight)
        val output = ImageBitmap(width, height)
        val scope = CanvasDrawScope()
        val echoSize = Size(echoWidth.toFloat(), echoHeight.toFloat())
        val fullSize = Size(width.toFloat(), height.toFloat())
        drawing.restart()
        var elapsed = 0f
        var musicTime = 0f
        var echoes = 0
        val results = ArrayList<Still>()
        val targets = seconds.map { (it * 60f).toInt() }
        val last = targets.last()
        for (step in 0..last) {
            elapsed += delta
            val frame = player.next(delta)
            musicTime += delta * frame.motionRate
            val nextTarget = targets.first { it >= step }
            if (step < nextTarget - warm) continue
            val state = VizRenderState(frame, elapsed, delta, palette, musicTime, player.future)
            if (drawing.trailAt(frame.mood) > 0f) {
                val previous = if (echoes == 0) null else back
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(front), echoSize) {
                    drawVisualizationFrame(drawing, state, previous)
                }
                val echo = front
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(output), fullSize) {
                    drawComposedFrame(drawing, state, echo)
                }
                val held = front
                front = back
                back = held
                echoes++
            } else {
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(output), fullSize) {
                    drawComposedFrame(drawing, state, null)
                }
            }
            if (step == nextTarget) {
                results += Still(output.toBufferedImage(), frame.energy, frame.mood, frame.bpm, frame.kickPulse)
            }
        }
        return results
    }

    private fun strip(name: String, stills: List<Still>, seconds: List<Float>): BufferedImage {
        val cellWidth = 640
        val cellHeight = (640f * stills.first().image.height / stills.first().image.width).toInt()
        val caption = 22
        val sheet = BufferedImage(cellWidth * stills.size, cellHeight + caption, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = Color(16, 16, 20)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        stills.forEachIndexed { index, still ->
            g.drawImage(still.image, index * cellWidth, 0, cellWidth, cellHeight, null)
            g.color = Color(210, 210, 215)
            g.drawString(
                "%s  %.0fs  energy %.2f  mood %.2f  bpm %.0f  kick %.2f".format(
                    name, seconds[index], still.energy, still.mood, still.bpm, still.kick,
                ),
                index * cellWidth + 6, cellHeight + 16,
            )
        }
        g.dispose()
        return sheet
    }

    private fun readFloats(file: File): FloatArray {
        val floats = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }
}
