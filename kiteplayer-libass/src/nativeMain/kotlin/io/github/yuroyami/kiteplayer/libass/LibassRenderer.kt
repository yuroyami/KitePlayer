@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.usePinned
import libass.ASS_Image
import libass.ASS_Library
import libass.ASS_Renderer
import libass.ASS_Track
import libass.ass_add_font
import libass.ass_free_track
import libass.ass_library_done
import libass.ass_library_init
import libass.ass_read_memory
import libass.ass_render_frame
import libass.ass_renderer_done
import libass.ass_renderer_init
import libass.ass_set_fonts
import libass.ass_set_frame_size

/**
 * The cinterop half: libass reached directly, with no adapter in between.
 *
 * Font discovery is the platform's here. Apple's CoreText provider and Windows' GDI/DirectWrite
 * find system fonts by themselves; Linux has no provider in this chain and renders only what
 * [addFont] supplies.
 *
 * Honest limit of this first slice, shared with the JVM half: rendering is snapshot-per-call, and
 * the engine hook that re-renders animated typesetting per video frame is the next one.
 */
public actual class LibassRenderer actual constructor() : AutoCloseable {

    private val library: CPointer<ASS_Library> =
        requireNotNull(ass_library_init()) { "libass refused a library instance" }
    private val renderer: CPointer<ASS_Renderer> =
        requireNotNull(ass_renderer_init(library)) { "libass refused a renderer" }
    private var closed = false

    init {
        // Provider 1 is ASS_FONTPROVIDER_AUTODETECT: CoreText here, whatever exists elsewhere.
        ass_set_fonts(renderer, null, "sans-serif", 1, null, 1)
    }

    public actual fun addFont(name: String, data: ByteArray) {
        check(!closed) { "LibassRenderer is closed" }
        if (data.isEmpty()) return
        // Both char* parameters are raw here, see noStringConversion in libass.def.
        memScoped {
            data.usePinned { pinned ->
                ass_add_font(library, name.cstr.ptr, pinned.addressOf(0), data.size)
            }
        }
    }

    public actual fun renderDocument(
        script: String,
        timeMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
        startMicros: Long,
        endMicros: Long,
    ): SubtitleCue.Bitmap? {
        check(!closed) { "LibassRenderer is closed" }
        require(frameWidth > 0 && frameHeight > 0) { "frame has no dimensions: ${frameWidth}x$frameHeight" }
        val bytes = script.encodeToByteArray()
        val track = bytes.usePinned { pinned ->
            ass_read_memory(library, pinned.addressOf(0), bytes.size.toULong(), null)
        } ?: return null
        try {
            ass_set_frame_size(renderer, frameWidth, frameHeight)
            val image = ass_render_frame(renderer, track, timeMillis, null) ?: return null
            val regions = collectRegions(image, frameWidth, frameHeight)
            if (regions.isEmpty()) return null
            return SubtitleCue.Bitmap(
                startMicros = startMicros,
                endMicros = endMicros,
                regions = regions,
            )
        } finally {
            ass_free_track(track)
        }
    }

    /**
     * One [BitmapRegion] per libass image: the 8-bit coverage bitmap times the image's RGBA
     * colour, emitted PREMULTIPLIED exactly as [RgbaBitmap] documents since the 2026-08-17
     * audit unified the contract. libass colour is RRGGBBAA with AA as TRANSPARENCY (0 opaque),
     * inverted here once, and the colour channels are scaled by the pixel's own alpha at emit.
     */
    private fun collectRegions(first: CPointer<ASS_Image>, canvasWidth: Int, canvasHeight: Int): List<BitmapRegion> {
        val regions = mutableListOf<BitmapRegion>()
        var image: CPointer<ASS_Image>? = first
        while (image != null) {
            val img = image.pointed
            val width = img.w
            val height = img.h
            if (width > 0 && height > 0) {
                val color = img.color.toLong()
                val red = (color shr 24 and 0xFF).toInt()
                val green = (color shr 16 and 0xFF).toInt()
                val blue = (color shr 8 and 0xFF).toInt()
                val opacity = 255 - (color and 0xFF).toInt()
                val bitmap = requireNotNull(img.bitmap) { "libass produced an image with no bitmap" }
                val stride = img.stride
                val pixels = ByteArray(width * height * 4)
                var at = 0
                for (row in 0 until height) {
                    val rowBase = row * stride
                    for (column in 0 until width) {
                        val coverage = bitmap[rowBase + column].toInt() and 0xFF
                        val alpha = (coverage * opacity) / 255
                        pixels[at++] = ((red * alpha) / 255).toByte()
                        pixels[at++] = ((green * alpha) / 255).toByte()
                        pixels[at++] = ((blue * alpha) / 255).toByte()
                        pixels[at++] = alpha.toByte()
                    }
                }
                regions += BitmapRegion(
                    x = img.dst_x,
                    y = img.dst_y,
                    width = width,
                    height = height,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    bitmap = RgbaBitmap(width, height, pixels),
                )
            }
            image = img.next
        }
        return regions
    }

    actual override fun close() {
        if (closed) return
        closed = true
        ass_renderer_done(renderer)
        ass_library_done(library)
    }
}
