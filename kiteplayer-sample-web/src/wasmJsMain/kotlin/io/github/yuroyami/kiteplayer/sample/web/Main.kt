package io.github.yuroyami.kiteplayer.sample.web

import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.ComposeViewport
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * The web stop gate.
 *
 * Answers one question with a number: what does a 1080p frame cost on wasm, with ONE thread,
 * from planar YUV to pixels on the glass. Nothing here decodes; a synthetic frame is honest for
 * this measurement because the conversion cost does not depend on pixel values.
 */
private const val WIDTH = 1920
private const val HEIGHT = 1080

/** `performance.now()`, which is the only clock here with sub-millisecond resolution. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => performance.now()")
private external fun nowMs(): Double

/** Hardware concurrency, reported so the single-thread claim is visible rather than asserted. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => navigator.hardwareConcurrency")
private external fun hardwareConcurrency(): Int

/** True when the page may use SharedArrayBuffer, which is what a threaded artifact would need. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => self.crossOriginIsolated === true")
private external fun crossOriginIsolated(): Boolean

/**
 * A faithful mirror of `Conversions.kt`'s packed path for yuv420p, BT.709 limited range.
 *
 * A mirror rather than the real function because that one is internal to `:kiteplayer-ffmpeg`,
 * which had no web target when this was written. The arithmetic is copied line for line from
 * `writePackedRgba` and `PackedCoefficients`: same offsets, same scales, same rounding, same
 * nearest-neighbour chroma lookup. SERIAL on purpose, per 17.14 S6-D2: `parallelRowSlices` cannot
 * follow the engine here, so measuring the parallel path would flatter the number.
 */
private fun yuv420ToRgbaSerial(planes: ByteArray, width: Int, height: Int, out: ByteArray) {
    val chromaWidth = (width + 1) shr 1
    val chromaHeight = (height + 1) shr 1
    val uOffset = width * height
    val vOffset = uOffset + chromaWidth * chromaHeight
    val lumaScale = 255.0 / 219.0
    val chromaScale = 255.0 / 224.0
    for (row in 0 until height) {
        val chromaRow = (row shr 1).coerceAtMost(chromaHeight - 1)
        var at = row * width * 4
        for (column in 0 until width) {
            val chromaColumn = (column shr 1).coerceAtMost(chromaWidth - 1)
            val luma = planes[row * width + column].toInt() and 0xFF
            val cbRaw = planes[uOffset + chromaRow * chromaWidth + chromaColumn].toInt() and 0xFF
            val crRaw = planes[vOffset + chromaRow * chromaWidth + chromaColumn].toInt() and 0xFF
            val y = (luma - 16) * lumaScale
            val cb = (cbRaw - 128) * chromaScale
            val cr = (crRaw - 128) * chromaScale
            out[at++] = ((y + 1.5748 * cr) + 0.5).toInt().coerceIn(0, 255).toByte()
            out[at++] = ((y - 0.187324 * cb - 0.468124 * cr) + 0.5).toInt().coerceIn(0, 255).toByte()
            out[at++] = ((y + 1.8556 * cb) + 0.5).toInt().coerceIn(0, 255).toByte()
            out[at++] = -1
        }
    }
}

private fun rasterImage(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
    return Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
}

/** Mean and p95 of a sorted-in-place sample, in milliseconds. */
private class Stat(samples: DoubleArray) {
    val mean: Double
    val p95: Double

    init {
        samples.sort()
        mean = samples.average()
        p95 = samples[(samples.size * 95) / 100]
    }

    fun format(label: String): String =
        "$label mean=${mean.round2()} ms p95=${p95.round2()} ms"
}

private fun Double.round2(): String {
    val scaled = ((this * 100).toInt()).toDouble() / 100.0
    return scaled.toString()
}

private class Report {
    val lines = mutableListOf<String>()

    fun add(line: String) {
        lines.add(line)
        println(line)
    }
}

/**
 * Frame intervals over [count] frames, with [perFrame] run inside each frame callback.
 *
 * Intervals rather than a stopwatch around the work, because the compositor finishes after the
 * callback returns and a span timer would under-count it. The desktop graphicsLayer arm is the reason
 * this project measures frame loops this way.
 */
private suspend fun measureFrames(count: Int, perFrame: (Int) -> Unit): DoubleArray {
    val samples = DoubleArray(count)
    var previous = 0.0
    var index = -8 // negative is warmup
    while (index < count) {
        withFrameNanos { nanos ->
            val at = nanos / 1_000_000.0
            perFrame(index)
            if (index >= 0 && previous > 0.0) samples[index] = at - previous
            previous = at
            index++
        }
    }
    return samples
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport { Probe() }
}

@Composable
private fun Probe() {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    // Read by the Canvas below purely so every measured frame really invalidates the draw.
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val chromaWidth = (WIDTH + 1) shr 1
        val chromaHeight = (HEIGHT + 1) shr 1
        val planes = ByteArray(WIDTH * HEIGHT + 2 * chromaWidth * chromaHeight) {
            (it * 31 % 255).toByte()
        }
        val rgba = ByteArray(WIDTH * HEIGHT * 4)
        val report = Report()
        report.add("web draw-cost probe, ${WIDTH}x$HEIGHT yuv420p, one thread")
        // The real kiteffmpeg-core web backend, driven through the API every platform uses.
        val clip = fetchClip("./clip.mp4")
        if (clip == null) {
            report.add("backend: no ./clip.mp4 beside the page, skipping the decode proof")
        } else {
            runCatching { runBackendProof(clip) { line -> report.add("backend: $line") } }
                .onFailure { report.add("backend FAILED: ${it.message}") }
        }
        report.add("hardwareConcurrency=${hardwareConcurrency()} crossOriginIsolated=${crossOriginIsolated()}")

        // Phase 1, the conversion, serial per S6-D2.
        repeat(5) { yuv420ToRgbaSerial(planes, WIDTH, HEIGHT, rgba) }
        val convert = DoubleArray(30)
        for (i in convert.indices) {
            val start = nowMs()
            yuv420ToRgbaSerial(planes, WIDTH, HEIGHT, rgba)
            convert[i] = nowMs() - start
        }
        report.add(Stat(convert).format("convert 1080p serial"))

        // Phase 2, a SIZE LADDER over the raster build. The 1080p number alone cannot tell a
        // per-byte copy from a fixed overhead, and those have different fixes: a per-byte cost is
        // the Kotlin-heap-to-Skia crossing and is avoided by never materialising the frame in
        // Kotlin, while a fixed cost would be Skia setup and would amortise.
        for (scale in listOf(4, 2, 1)) {
            val w = WIDTH / scale
            val h = HEIGHT / scale
            val bytes = ByteArray(w * h * 4) { (it and 0xFF).toByte() }
            repeat(3) { rasterImage(bytes, w, h) }
            val ladder = DoubleArray(15)
            for (i in ladder.indices) {
                val start = nowMs()
                rasterImage(bytes, w, h)
                ladder[i] = nowMs() - start
            }
            val stat = Stat(ladder)
            val perByte = stat.mean * 1_000_000.0 / (w * h * 4)
            report.add(stat.format("raster build ${w}x$h") + " perByte=${perByte.round2()} ns")
        }

        val resident = rasterImage(rgba, WIDTH, HEIGHT)
        image = resident

        // Phase 3, DRAW ONLY, and synchronous ON PURPOSE. The frame clock needs
        // requestAnimationFrame, which a hidden browser pane never fires, so a frame-driven
        // measurement here would hang rather than answer. This draws an already-resident image
        // into an offscreen Skia surface, which is the blit a renderer actually controls.
        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val skiaImage = Image.makeRaster(
            ImageInfo(WIDTH, HEIGHT, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), rgba, WIDTH * 4,
        )
        repeat(5) { surface.canvas.drawImage(skiaImage, 0f, 0f) }
        val blit = DoubleArray(30)
        for (i in blit.indices) {
            val start = nowMs()
            surface.canvas.drawImage(skiaImage, 0f, 0f)
            blit[i] = nowMs() - start
        }
        report.add(Stat(blit).format("draw resident image, offscreen Skia"))

        // Phase 4, the real frame loop, ATTEMPTED and allowed to fail loudly. It needs
        // requestAnimationFrame; a hidden pane never fires it. A timeout is the difference
        // between "the environment could not measure this" and a hang that reads as a bad number.
        val full = withTimeoutOrNull(20_000) {
            measureFrames(20) {
                tick = it
                yuv420ToRgbaSerial(planes, WIDTH, HEIGHT, rgba)
                image = rasterImage(rgba, WIDTH, HEIGHT)
            }
        }
        if (full == null) {
            report.add("full path, convert+build+draw: NOT MEASURED, the frame clock never ticked")
        } else {
            report.add(Stat(full).format("full path, convert+build+draw"))
        }

        val budget = 1000.0 / 30.0
        report.add("30 fps budget is ${budget.round2()} ms")
    }

    Canvas(Modifier.fillMaxSize()) {
        if (tick >= 0) image?.let { drawProbeImage(it) }
    }
}

/** Draws the frame scaled to fit, so a 1080p image is visible in any viewport. */
private fun DrawScope.drawProbeImage(bitmap: ImageBitmap) {
    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
    )
}
