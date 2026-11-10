package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Compose canvas renderer says whether it applies picture controls, and what it draws must
 * agree (#722): the engine warns the viewer from that flag alone.
 */
class KiteVideoCapabilityTest {
    init { useSkiaGraphics() }

    /** A mid-grey picture drawn as the canvas path draws it, with [filter], read at its centre. */
    private fun drawnGrey(filter: ColorFilter?): Int {
        val grey = FrameImagePool().imageFor(ByteArray(4 * 4 * 4) { if (it % 4 == 3) -1 else 0x80.toByte() }, 4, 4).image
        val layout = videoLayout(40, 40, VideoSize(4, 4), 0)!!
        val target = ImageBitmap(40, 40)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(40f, 40f)) {
            drawVideoPicture(grey, layout, false, FilterQuality.None, colorFilter = filter)
        }
        val pixels = IntArray(40 * 40)
        target.readPixels(pixels)
        return pixels[20 * 40 + 20] and 0xFF
    }

    @Test
    fun theFlagSaysWhetherABrighterPictureIsDrawnBrighter() {
        val state = KiteVideoState()
        try {
            val frame = KiteVideoFrame(ImageBitmap(4, 4), VideoSize(4, 4), 0)
            val plain = drawnGrey(state.pictureFilterFor(frame))
            state.renderer.setAdjustments(VideoAdjustments(brightness = 0.3f))
            val brighter = drawnGrey(state.pictureFilterFor(frame))
            val applies = state.renderer.appliesAdjustments
            assertEquals(applies, brighter != plain, "appliesAdjustments is $applies and the picture read $plain then $brighter")
        } finally {
            state.renderer.close()
        }
    }
}
