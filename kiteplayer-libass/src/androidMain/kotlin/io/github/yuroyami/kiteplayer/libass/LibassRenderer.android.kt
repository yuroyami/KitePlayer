package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The raw boundary. Every method here has a body in `native/src/libass_jni.c` and nowhere else. */
internal object LibassNative {
    init {
        System.loadLibrary("kiteplayer_libass_jni")
    }

    external fun open(): Long
    external fun addFont(handle: Long, name: String, data: ByteArray)
    external fun renderPacked(
        handle: Long,
        script: String,
        timeMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
    ): ByteArray?
    external fun close(handle: Long)
}

/**
 * The JNI half: identical pixels to the cinterop one, reached through a shared library.
 *
 * Android has NO system font provider in this chain, so a renderer with no [addFont] call finds
 * nothing to shape with and renders empty. That is libass' behaviour on a fontconfig-less build
 * rather than a gap here, and it is why [addFont] exists on the common API. An app typically feeds
 * it a font from its own assets, or one of the system files under `/system/fonts`.
 *
 * One JNI call renders one frame. The adapter packs every region into a single byte array rather
 * than crossing the boundary per region, and this parses it back; the layout is documented on
 * `renderPacked` in the C.
 */
public actual class LibassRenderer actual constructor() : AutoCloseable {

    private var handle: Long = LibassNative.open()

    init {
        check(handle != 0L) { "libass refused a library instance" }
    }

    public actual fun addFont(name: String, data: ByteArray) {
        check(handle != 0L) { "LibassRenderer is closed" }
        if (data.isEmpty()) return
        LibassNative.addFont(handle, name, data)
    }

    public actual fun renderDocument(
        script: String,
        timeMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
        startMicros: Long,
        endMicros: Long,
    ): SubtitleCue.Bitmap? {
        check(handle != 0L) { "LibassRenderer is closed" }
        require(frameWidth > 0 && frameHeight > 0) { "frame has no dimensions: ${frameWidth}x$frameHeight" }
        val packed = LibassNative.renderPacked(handle, script, timeMillis, frameWidth, frameHeight)
            ?: return null
        val regions = unpack(packed, frameWidth, frameHeight)
        if (regions.isEmpty()) return null
        return SubtitleCue.Bitmap(startMicros = startMicros, endMicros = endMicros, regions = regions)
    }

    /** Native byte order, because the adapter writes its int32 headers with a plain memcpy. */
    private fun unpack(packed: ByteArray, canvasWidth: Int, canvasHeight: Int): List<BitmapRegion> {
        val buffer = ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder())
        val count = buffer.int
        if (count <= 0) return emptyList()
        val headers = ArrayList<IntArray>(count)
        repeat(count) {
            headers += intArrayOf(buffer.int, buffer.int, buffer.int, buffer.int, buffer.int)
        }
        val regions = ArrayList<BitmapRegion>(count)
        headers.forEach { (x, y, width, height, byteCount) ->
            val pixels = ByteArray(byteCount)
            buffer.get(pixels)
            regions += BitmapRegion(
                x = x,
                y = y,
                width = width,
                height = height,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                bitmap = RgbaBitmap(width, height, pixels),
            )
        }
        return regions
    }

    actual override fun close() {
        val open = handle
        if (open == 0L) return
        handle = 0L
        LibassNative.close(open)
    }
}

private operator fun IntArray.component1(): Int = this[0]
private operator fun IntArray.component2(): Int = this[1]
private operator fun IntArray.component3(): Int = this[2]
private operator fun IntArray.component4(): Int = this[3]
private operator fun IntArray.component5(): Int = this[4]
