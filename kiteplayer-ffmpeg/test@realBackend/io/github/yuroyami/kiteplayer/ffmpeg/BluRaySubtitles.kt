package io.github.yuroyami.kiteplayer.ffmpeg

/** One picture of a display set: where it sits, and whether the disc marks it forced (#513). */
internal class PgsCaption(val x: Int, val y: Int, val forced: Boolean)

/**
 * A PGS elementary stream of display sets, each at its second and each a new epoch holding its
 * captions as 4x2 images, written out as test_subtitle.c in KiteFFmpeg writes one. A set with no
 * caption clears the screen.
 */
internal fun bluRaySubtitles(vararg sets: Pair<Int, List<PgsCaption>>): ByteArray {
    val out = ArrayList<Byte>()
    fun put8(v: Int) { out += v.toByte() }
    fun put16(v: Int) { put8(v shr 8); put8(v) }
    fun put24(v: Int) { put8(v shr 16); put16(v and 0xFFFF) }
    fun put32(v: Int) { put16(v ushr 16); put16(v and 0xFFFF) }
    fun segment(pts: Int, type: Int, size: Int) { put8('P'.code); put8('G'.code); put32(pts); put32(0); put8(type); put16(size) }
    val rle = intArrayOf(0x01, 0x01, 0x02, 0x02, 0x00, 0x00, 0x02, 0x02, 0x01, 0x01, 0x00, 0x00)

    sets.forEachIndexed { number, (second, captions) ->
        val pts = second * 90_000
        segment(pts, 0x16, 11 + 8 * captions.size)
        put16(1920); put16(1080); put8(0x10)
        put16(number); put8(if (captions.isEmpty()) 0x00 else 0x80); put8(0x00); put8(0)
        put8(captions.size)
        captions.forEachIndexed { id, caption ->
            put16(id); put8(id); put8(if (caption.forced) 0x40 else 0x00); put16(caption.x); put16(caption.y)
        }
        if (captions.isNotEmpty()) {
            segment(pts, 0x17, 1 + 9 * captions.size)
            put8(captions.size)
            captions.forEachIndexed { id, caption -> put8(id); put16(caption.x); put16(caption.y); put16(4); put16(2) }
            segment(pts, 0x14, 12)
            put8(0); put8(0)
            put8(1); put8(235); put8(128); put8(128); put8(255)
            put8(2); put8(16); put8(128); put8(128); put8(128)
            captions.indices.forEach { id ->
                segment(pts, 0x15, 11 + rle.size)
                put16(id); put8(0); put8(0xC0)
                put24(4 + rle.size)
                put16(4); put16(2)
                rle.forEach(::put8)
            }
        }
        segment(pts, 0x80, 0)
    }
    return out.toByteArray()
}
