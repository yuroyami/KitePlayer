package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import java.awt.Canvas
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * The placement contract on the desktop AWT renderer, headless. The renderer reports the size of
 * its canvas, and the presenter composes a frame into a plain image of that size, the same seam
 * the renderer's own geometry tests use.
 */
class AwtOverlayPlacementTest : OverlayPlacementContractTest() {

    override fun reportedOutput(scene: Scene): VideoSize? {
        val renderer = AwtCanvasVideoRenderer(painter = { _, _, _, _ -> true })
        return try {
            renderer.setCanvas(Canvas().apply { setSize(scene.outputWidth, scene.outputHeight) })
            renderer.outputSize
        } finally {
            renderer.close()
        }
    }

    override fun compose(scene: Scene, overlay: SubtitleOverlay): Composite {
        val width = scene.outputWidth
        val height = scene.outputHeight
        val picture = BufferedImage(scene.picture.width, scene.picture.height, BufferedImage.TYPE_INT_RGB)
        (picture.raster.dataBuffer as DataBufferInt).data.fill(pictureColor)
        val layout = checkNotNull(
            frameLayout(width, height, scene.picture, scene.rotationDegrees, scene.scale, scene.transform),
        ) { "the scene's picture has no place on its output" }
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        try {
            AwtCanvasPresenter.compose(graphics, width, height, picture, layout, overlay)
        } finally {
            graphics.dispose()
        }
        val boxes = findMarkers(width, height, overlay.images.size) { x, y -> output.getRGB(x, y) and 0xFFFFFF }
        return Composite(width, height, boxes)
    }
}
