package io.github.yuroyami.kiteplayer.sample.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.view.View

/**
 * The share of sampled pixels brighter than near black in a fresh screenshot, taken in the middle
 * 60 percent of where a 16:9 clip sits when it is fitted into [view]. The sample's performance
 * overlay sits at the view's top edge, outside that area. A screenshot the instrumentation takes
 * cannot capture a secure layer, which then reads as black.
 */
internal fun pictureBrightShare(instrumentation: Instrumentation, view: View): Double {
    val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return 0.0
    try {
        val origin = IntArray(2)
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            view.getLocationOnScreen(origin)
            width = view.width
            height = view.height
        }
        val fitsWidth = width * 9 <= height * 16
        val pictureWidth = if (fitsWidth) width else height * 16 / 9
        val pictureHeight = if (fitsWidth) width * 9 / 16 else height
        val left = origin[0] + (width - pictureWidth) / 2 + pictureWidth / 5
        val top = origin[1] + (height - pictureHeight) / 2 + pictureHeight / 5
        var bright = 0
        var sampled = 0
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                val x = left + pictureWidth * 3 / 5 * column / (GRID - 1)
                val y = top + pictureHeight * 3 / 5 * row / (GRID - 1)
                if (x !in 0 until shot.width || y !in 0 until shot.height) continue
                val pixel = shot.getPixel(x, y)
                val level = maxOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
                if (level > NEAR_BLACK) bright++
                sampled++
            }
        }
        return if (sampled == 0) 0.0 else bright.toDouble() / sampled
    } finally {
        shot.recycle()
    }
}

/** A share above this means the picture is on screen. */
internal const val PICTURE_SHARE = 0.5

/** A share below this means the video area is black. */
internal const val BLACK_SHARE = 0.02

private const val GRID = 16
private const val NEAR_BLACK = 40
