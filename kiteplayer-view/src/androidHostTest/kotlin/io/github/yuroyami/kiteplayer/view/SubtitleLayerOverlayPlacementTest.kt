package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay

/**
 * The placement contract on the subtitle layer of [KitePlayerView]. The layer covers the whole
 * view, and the view gives the renderer that size, so the engine lays the overlay out for it.
 */
class SubtitleLayerOverlayPlacementTest : OverlayPlacementContractTest() {

    override fun reportedOutput(scene: Scene): VideoSize = VideoSize(scene.outputWidth, scene.outputHeight)

    override fun compose(scene: Scene, overlay: SubtitleOverlay): Composite {
        val boxes = overlay.images.map { image ->
            overlayDestination(scene.outputWidth, scene.outputHeight, overlay, image)
                ?.let { Box(it.left, it.top, it.right - it.left, it.bottom - it.top) }
        }
        return Composite(scene.outputWidth, scene.outputHeight, boxes)
    }
}
