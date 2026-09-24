package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Android's triangle call, which multiplies the texture by each corner's colour.
 *
 * Before Android 10 a hardware accelerated canvas ignores that call, so those versions draw each
 * sprite as the texture tinted by a colour filter. Every sprite of one call shares its colour.
 */
internal actual fun DrawScope.drawSparkSprites(sprites: SparkSprites) {
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        if (native.isHardwareAccelerated && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            drawOneByOne(native, sprites)
            return@drawIntoCanvas
        }
        native.drawVertices(
            AndroidCanvas.VertexMode.TRIANGLES,
            sprites.capacity * 8,
            sprites.positions,
            0,
            sprites.texCoords,
            0,
            sprites.colors,
            0,
            sprites.indices,
            0,
            sprites.capacity * 6,
            sparkPaint,
        )
    }
}

private fun drawOneByOne(canvas: AndroidCanvas, sprites: SparkSprites) {
    var paint: Paint? = null
    val bounds = RectF()
    for (slot in 0 until sprites.capacity) {
        val argb = sprites.colors[slot * 4]
        if (argb ushr 24 == 0) continue
        // A paint for this call only: its colour filter changes with the sprites' colour.
        val tinted = paint ?: Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            colorFilter = PorterDuffColorFilter(argb, PorterDuff.Mode.MULTIPLY)
        }.also { paint = it }
        val at = slot * 8
        bounds.set(sprites.positions[at], sprites.positions[at + 1], sprites.positions[at + 4], sprites.positions[at + 5])
        canvas.drawBitmap(sparkBitmap, null, bounds, tinted)
    }
}

/** Premultiplied white with the falloff's alpha; Android premultiplies the colours it is given. */
private val sparkBitmap: Bitmap by lazy {
    val size = SPARK_TEXTURE_SIZE
    val alpha = sparkTextureAlpha()
    val colours = IntArray(size * size) { (alpha[it] shl 24) or 0x00FFFFFF }
    Bitmap.createBitmap(colours, size, size, Bitmap.Config.ARGB_8888)
}

/** One paint, never changed after it is made, so drawings on different threads can share it. */
private val sparkPaint: Paint by lazy {
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(sparkBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
}
