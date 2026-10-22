package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.VertexMode
import org.jetbrains.skia.BlendMode as SkiaBlendMode

/** Desktop and iOS: Skia's triangle call, with the texture multiplied by each corner's colour. */
internal actual fun DrawScope.drawSparkSprites(sprites: SparkSprites) {
    drawIntoCanvas { canvas ->
        canvas.skiaCanvas.drawVertices(
            VertexMode.TRIANGLES,
            sprites.positions,
            sprites.colors,
            sprites.texCoords,
            sprites.indices,
            SkiaBlendMode.MODULATE,
            sparkPaint,
        )
    }
}

/** One paint, never changed after it is made, so drawings on different threads can share it. */
private val sparkPaint: Paint by lazy {
    val size = SPARK_TEXTURE_SIZE
    val alpha = sparkTextureAlpha()
    // Premultiplied white: every channel equals the alpha, whatever the byte order.
    val bytes = ByteArray(size * size * 4) { alpha[it / 4].toByte() }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32Premul(size, size))
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    Paint().apply {
        color = -1
        blendMode = SkiaBlendMode.PLUS
        shader = bitmap.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)
    }
}
