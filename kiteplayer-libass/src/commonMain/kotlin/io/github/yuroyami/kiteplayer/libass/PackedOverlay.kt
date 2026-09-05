package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap

/**
 * Reads the one buffer `kite_ass_render` answers back into overlay images.
 *
 * Layout, every field a little-endian int32 (the driver writes native order, and every target this
 * module ships for is little-endian: arm64, x86-64 and wasm32):
 *
 *   int32 regionCount
 *   regionCount x { int32 x, y, w, h, pixelByteCount }
 *   the pixel blobs back to back, premultiplied RGBA8888
 *
 * The pixel bytes are COPIED out of the packed buffer into one array per region, because
 * [RgbaBitmap] shares its array with every renderer that draws it, and the packed buffer is reused
 * by the next render.
 */
internal fun unpackOverlay(packed: ByteArray): List<OverlayImage> {
    if (packed.size < 4) return emptyList()
    val count = packed.int32At(0)
    if (count <= 0) return emptyList()
    val headerBytes = 4 + count * 20
    require(packed.size >= headerBytes) { "packed overlay names $count regions but carries ${packed.size} bytes" }
    val images = ArrayList<OverlayImage>(count)
    var pixelAt = headerBytes
    for (index in 0 until count) {
        val header = 4 + index * 20
        val x = packed.int32At(header)
        val y = packed.int32At(header + 4)
        val width = packed.int32At(header + 8)
        val height = packed.int32At(header + 12)
        val byteCount = packed.int32At(header + 16)
        require(byteCount == width * height * 4 && pixelAt + byteCount <= packed.size) {
            "packed region $index is ${width}x$height with $byteCount bytes at $pixelAt of ${packed.size}"
        }
        val pixels = packed.copyOfRange(pixelAt, pixelAt + byteCount)
        pixelAt += byteCount
        images += OverlayImage(x = x, y = y, bitmap = RgbaBitmap(width, height, pixels))
    }
    return images
}

private fun ByteArray.int32At(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
