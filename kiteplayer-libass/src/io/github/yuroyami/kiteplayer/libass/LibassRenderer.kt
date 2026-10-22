package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * Whole-document rendering, one call per picture, for tools and tests.
 *
 * Playback does not use this: the engine streams events into a [LibassTypesetter] and renders per
 * frame. This wraps the same typesetter for the case where a caller holds a complete script and
 * wants the picture at one instant as a bitmap cue, which is what the module's reference tests
 * compare the streamed path against.
 */
public class LibassRenderer() : AutoCloseable {

    private val typesetter: LibassTypesetter = LibassTypesetter()
    private var document: ByteArray? = null

    /** Adds one font from memory, under [name], for libass to shape with. */
    public fun addFont(name: String, data: ByteArray): Unit = typesetter.addFont(name, data)

    /**
     * Renders [script] at [timeMillis] into bitmap regions for a [frameWidth] x [frameHeight]
     * frame. Returns null when nothing is visible there. The script is parsed once and kept until
     * a different one arrives.
     */
    public fun renderDocument(
        script: String,
        timeMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
        startMicros: Long = timeMillis * 1000,
        endMicros: Long = (timeMillis + 1) * 1000,
    ): SubtitleCue.Bitmap? {
        require(frameWidth > 0 && frameHeight > 0) { "frame has no dimensions: ${frameWidth}x$frameHeight" }
        val bytes = script.encodeToByteArray()
        if (document?.contentEquals(bytes) != true) {
            typesetter.openDocument(bytes)
            document = bytes
        }
        val frame = TypesetFrame(frameWidth, frameHeight, frameWidth, frameHeight)
        // "Unchanged" means the last answer still stands. The driver always answers the first
        // render after a document opens, so there is always a last answer to stand.
        typesetter.render(timeMillis, frame)?.let { lastImages = it }
        return lastAnswer(frameWidth, frameHeight, startMicros, endMicros)
    }

    private var lastImages: List<io.github.yuroyami.kiteplayer.spi.OverlayImage> = emptyList()

    private fun lastAnswer(width: Int, height: Int, startMicros: Long, endMicros: Long): SubtitleCue.Bitmap? {
        if (lastImages.isEmpty()) return null
        return SubtitleCue.Bitmap(
            startMicros = startMicros,
            endMicros = endMicros,
            regions = lastImages.map { image ->
                BitmapRegion(
                    x = image.x,
                    y = image.y,
                    width = image.bitmap.width,
                    height = image.bitmap.height,
                    canvasWidth = width,
                    canvasHeight = height,
                    bitmap = image.bitmap,
                )
            },
        )
    }

    override fun close(): Unit = typesetter.close()
}
