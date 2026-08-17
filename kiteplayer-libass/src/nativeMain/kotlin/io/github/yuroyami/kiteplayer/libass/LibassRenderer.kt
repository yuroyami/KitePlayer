@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.usePinned
import libass.ASS_Image
import libass.ASS_Library
import libass.ASS_Renderer
import libass.ASS_Track
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
 * Typesetting-grade ASS rendering through libass (KPKMP 17.12 phase L), emitting the engine's
 * EXISTING bitmap-cue vocabulary: each rendered event becomes [SubtitleCue.Bitmap] regions in
 * the frame's own coordinate space, exactly what the rasterizers already draw for bitmap
 * subtitle formats. Nothing engine-side had to learn a new type, which is the whole point of
 * the bitmap-cue path.
 *
 * Font discovery is the platform's: on Apple, libass' CoreText provider finds system fonts by
 * itself (fontconfig deliberately never enters the chain, decision D-7).
 *
 * Honest limits of this first slice: rendering is snapshot-per-call (the engine hook that
 * re-renders animated typesetting per video frame is the next slice), and the Android half
 * needs a JNI bridge before this module serves JVM targets.
 */
public class LibassRenderer : AutoCloseable {

    private val library: CPointer<ASS_Library> =
        requireNotNull(ass_library_init()) { "libass refused a library instance" }
    private val renderer: CPointer<ASS_Renderer> =
        requireNotNull(ass_renderer_init(library)) { "libass refused a renderer" }
    private var closed = false

    init {
        // Provider 1 is ASS_FONTPROVIDER_AUTODETECT: CoreText here, whatever exists elsewhere.
        ass_set_fonts(renderer, null, "sans-serif", 1, null, 1)
    }

    /**
     * Renders one whole ASS document at [timeMillis] into bitmap regions for a
     * [frameWidth] x [frameHeight] video frame. Returns null when nothing is visible there.
     */
    public fun renderDocument(
        script: String,
        timeMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
        startMicros: Long = timeMillis * 1000,
        endMicros: Long = (timeMillis + 1) * 1000,
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

    override fun close() {
        if (closed) return
        closed = true
        ass_renderer_done(renderer)
        ass_library_done(library)
    }
}
