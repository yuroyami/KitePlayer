package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * A handful of drawings through the real surface, with the finishing pass off and then on.
 *
 * Not an assertion so much as a picture to look at. The glow is a matter of taste as much as of
 * numbers, and the only honest check on taste is to put the two side by side.
 */
class PostSheetTest {

    init { useSkiaGraphics() }

    private val cellWidth = 320
    private val cellHeight = 200
    private val frames = 60

    @Test
    fun renderThePostSheet() {
        val picks = listOf("Pipe", "Mandala", "Prism Burst", "Starfield", "Plasma Field", "Riot")
        val catalogue = VizCatalog.create()
        val chosen = picks.map { name -> catalogue.first { it.name == name } }

        val labelHeight = 22
        val padding = 6
        val sheet = BufferedImage(
            2 * (cellWidth + padding) + padding,
            chosen.size * (cellHeight + labelHeight + padding) + padding,
            BufferedImage.TYPE_INT_RGB,
        )
        val graphics = sheet.createGraphics()
        graphics.color = AwtColor(18, 18, 22)
        graphics.fillRect(0, 0, sheet.width, sheet.height)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)

        chosen.forEachIndexed { row, visualization ->
            for (column in 0..1) {
                val post = column == 1
                val image = renderThroughCompose(visualization, post)
                val x = padding + column * (cellWidth + padding)
                val y = padding + row * (cellHeight + labelHeight + padding)
                graphics.drawImage(image, x, y, null)
                graphics.color = AwtColor(205, 210, 220)
                graphics.drawString("${visualization.name}, finishing pass ${if (post) "on" else "off"}", x + 3, y + cellHeight + 16)
                println("${visualization.name} ${if (post) "with" else "without"} the finishing pass: mean ${mean(image)}")
            }
        }
        graphics.dispose()
        val file = File(File("build/reports/visualizations").apply { mkdirs() }, "post-sheet.png")
        ImageIO.write(sheet, "png", file)
        println("post sheet: ${file.absolutePath}")
    }

    /** Runs a drawing through the same composable a window shows, fed by real audio. */
    private fun renderThroughCompose(visualization: Visualization, post: Boolean): BufferedImage {
        val player = SongPlayer(SyntheticSong.drumLoop(frames / 60f + 4f))
        var current = player.latest
        val scene = ImageComposeScene(cellWidth, cellHeight, Density(1f), content = {
            VisualizerSurface(
                visualization = visualization,
                frame = { current },
                palette = VizPalette.Prism,
                modifier = Modifier.fillMaxSize(),
                post = post,
            )
        })
        var nanos = 0L
        var last: org.jetbrains.skia.Image? = null
        repeat(frames) {
            current = player.next(1f / 60f)
            nanos += 16_666_667L
            last = scene.render(nanos)
        }
        val picture = last!!.toComposeImageBitmap().toAwtImage()
        scene.close()
        return picture
    }

    private fun mean(image: BufferedImage): Float {
        var total = 0L
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val pixel = image.getRGB(x, y)
                total += (pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)
            }
        }
        return total.toFloat() / ((image.width / 2) * (image.height / 2)) / 765f
    }
}
