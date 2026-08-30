package io.github.yuroyami.kiteplayer.sample.desktop

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The decisive experiment: is Skia's SkSL actually faster than the scalar Kotlin loop?
 *
 * The register item assumed it is, and the amendment already showed one assumption in this area
 * was wrong. This settles the second one BEFORE the shared frame pipeline is touched, because a
 * shader that is not faster is only more surface (18.2 rule 10: when you do not know, measure).
 *
 * Both paths run in one JVM, on the same synthetic 1080p yuv420p planes, with the same warmup.
 * Run it with:
 *   java -cp "$(./gradlew -q :kiteplayer-sample-desktop:printRunClasspath)" \
 *        io.github.yuroyami.kiteplayer.sample.desktop.ShaderBenchmarkKt
 */
private const val WIDTH = 1920
private const val HEIGHT = 1080

/**
 * A faithful mirror of Conversions.kt's `convertPlanarYuv` for yuv420p, BT.709 limited range.
 *
 * A mirror rather than the real function because that one is internal to :kiteplayer-ffmpeg. It
 * keeps the shape that matters for cost: scalar, per pixel, Double maths, one coerce per channel.
 */
private fun scalarYuv420ToRgba(planes: ByteArray, width: Int, height: Int, out: ByteArray) {
    val chromaWidth = (width + 1) shr 1
    val chromaHeight = (height + 1) shr 1
    val uOffset = width * height
    val vOffset = uOffset + chromaWidth * chromaHeight
    val lumaScale = 255.0 / 219.0
    val chromaScale = 255.0 / 224.0
    for (row in 0 until height) {
        val chromaRow = row shr 1
        var outIndex = row * width * 4
        for (column in 0 until width) {
            val chromaColumn = (column shr 1).coerceIn(0, chromaWidth - 1)
            val luma = planes[row * width + column].toInt() and 0xFF
            val cbRaw = planes[uOffset + chromaRow * chromaWidth + chromaColumn].toInt() and 0xFF
            val crRaw = planes[vOffset + chromaRow * chromaWidth + chromaColumn].toInt() and 0xFF
            val y = (luma - 16) * lumaScale
            val cb = (cbRaw - 128) * chromaScale
            val cr = (crRaw - 128) * chromaScale
            out[outIndex++] = ((y + 1.5748 * cr) + 0.5).toInt().coerceIn(0, 255).toByte()
            out[outIndex++] = ((y - 0.187324 * cb - 0.468124 * cr) + 0.5).toInt().coerceIn(0, 255).toByte()
            out[outIndex++] = ((y + 1.8556 * cb) + 0.5).toInt().coerceIn(0, 255).toByte()
            out[outIndex++] = -1
        }
    }
}

/** The same law as SkSL. Planes arrive as three single-channel images sampled nearest. */
private val SKSL = """
    uniform shader luma;
    uniform shader chromaB;
    uniform shader chromaR;
    uniform float2 chromaScaleXY;
    uniform float lumaOffset;
    uniform float lumaGain;
    uniform float chromaGain;
    uniform float3 coeff;   // rCr, gCb, gCr
    uniform float bCb;

    half4 main(float2 p) {
        float2 c = floor(p * chromaScaleXY) + 0.5;
        float yy = (luma.eval(floor(p) + 0.5).r * 255.0 - lumaOffset) * lumaGain;
        float cb = (chromaB.eval(c).r * 255.0 - 128.0) * chromaGain;
        float cr = (chromaR.eval(c).r * 255.0 - 128.0) * chromaGain;
        float r = yy + coeff.x * cr;
        float g = yy - coeff.y * cb - coeff.z * cr;
        float b = yy + bCb * cb;
        return half4(half3(clamp(float3(r, g, b) / 255.0, 0.0, 1.0)), 1.0);
    }
""".trimIndent()

private fun planeImage(bytes: ByteArray, offset: Int, width: Int, height: Int): Image {
    val info = ImageInfo(width, height, ColorType.GRAY_8, ColorAlphaType.OPAQUE)
    val slice = bytes.copyOfRange(offset, offset + width * height)
    return Image.makeRaster(info, slice, width)
}

private fun uniforms(): Data {
    val buffer = ByteBuffer.allocate(4 * 9).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putFloat(0.5f).putFloat(0.5f)          // chromaScaleXY, 4:2:0
    buffer.putFloat(16f)                          // lumaOffset, limited range
    buffer.putFloat((255.0 / 219.0).toFloat())    // lumaGain
    buffer.putFloat((255.0 / 224.0).toFloat())    // chromaGain
    buffer.putFloat(1.5748f).putFloat(0.187324f).putFloat(0.468124f) // BT.709
    buffer.putFloat(1.8556f)
    return Data.makeFromBytes(buffer.array())
}

private fun skiaYuv420ToRgba(
    planes: ByteArray,
    width: Int,
    height: Int,
    surface: Surface,
    effect: RuntimeEffect,
    readback: org.jetbrains.skia.Bitmap,
) {
    val chromaWidth = (width + 1) shr 1
    val chromaHeight = (height + 1) shr 1
    val uOffset = width * height
    val vOffset = uOffset + chromaWidth * chromaHeight
    val y = planeImage(planes, 0, width, height)
    val u = planeImage(planes, uOffset, chromaWidth, chromaHeight)
    val v = planeImage(planes, vOffset, chromaWidth, chromaHeight)
    // CLAMP on both axes: a chroma sample at the right or bottom edge must not wrap to the
    // opposite side, which is what the scalar loop's coerceIn does.
    val tile = org.jetbrains.skia.FilterTileMode.CLAMP
    val children = arrayOf<org.jetbrains.skia.Shader?>(
        y.makeShader(tile, tile, SamplingMode.DEFAULT, null),
        u.makeShader(tile, tile, SamplingMode.DEFAULT, null),
        v.makeShader(tile, tile, SamplingMode.DEFAULT, null),
    )
    val paint = Paint().apply { shader = effect.makeShader(uniforms(), children, null) }
    surface.canvas.drawRect(Rect.makeXYWH(0f, 0f, width.toFloat(), height.toFloat()), paint)
    // The readback is part of the cost ONLY for the raster-surface variant, which is what this
    // benchmark is deciding. The real shader path draws straight to the screen and pays none of it.
    check(surface.readPixels(readback, 0, 0)) { "readPixels refused" }
}

private fun time(label: String, repeats: Int, block: () -> Unit): Double {
    repeat(10) { block() }
    val samples = DoubleArray(repeats)
    for (i in 0 until repeats) {
        val start = System.nanoTime()
        block()
        samples[i] = (System.nanoTime() - start) / 1_000_000.0
    }
    samples.sort()
    val mean = samples.average()
    val p95 = samples[(repeats * 95) / 100]
    println("$label mean=${"%.2f".format(mean)} ms p95=${"%.2f".format(p95)} ms")
    return mean
}

fun main() {
    val chromaWidth = (WIDTH + 1) shr 1
    val chromaHeight = (HEIGHT + 1) shr 1
    val planes = ByteArray(WIDTH * HEIGHT + 2 * chromaWidth * chromaHeight) { (it * 31 % 255).toByte() }
    val out = ByteArray(WIDTH * HEIGHT * 4)

    val cpu = time("scalar-kotlin", 60) { scalarYuv420ToRgba(planes, WIDTH, HEIGHT, out) }

    val effect = RuntimeEffect.makeForShader(SKSL)
    val surface = Surface.makeRaster(ImageInfo(WIDTH, HEIGHT, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
    val readback = org.jetbrains.skia.Bitmap().apply {
        allocPixels(ImageInfo(WIDTH, HEIGHT, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
    }
    val gpu = time("skia-sksl-raster", 60) { skiaYuv420ToRgba(planes, WIDTH, HEIGHT, surface, effect, readback) }

    println("verdict: skia is ${"%.2f".format(cpu / gpu)}x the scalar loop")

    // The re-decision after W-19. The upload path is two costs: the YUV to RGBA conversion,
    // which row parallelism cut from 6.3 ms to about 2.1, and the Skia raster build that turns
    // those bytes into an image. This times the second half, so the verdict rests on both.
    val rgba = ByteArray(WIDTH * HEIGHT * 4) { (it and 0xFF).toByte() }
    val rasterInfo = ImageInfo(WIDTH, HEIGHT, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
    time("skia-raster-image-build", 60) {
        Image.makeRaster(rasterInfo, rgba, WIDTH * 4).close()
    }
}
