package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * Typesetting-grade ASS rendering through libass, emitting the engine's
 * EXISTING bitmap-cue vocabulary: each rendered event becomes [SubtitleCue.Bitmap] regions in the
 * frame's own coordinate space, exactly what the rasterizers already draw for bitmap subtitle
 * formats. Nothing engine-side had to learn a new type, which is the whole point of that path.
 *
 * Two implementations sit behind this, and they differ only in how they REACH libass: the
 * Kotlin/Native targets bind it directly through cinterop, while the JVM targets call a small JNI
 * adapter. Both produce identical pixels, premultiplied RGBA, because the conversion is written
 * once per side against the same rules and checked against each other.
 *
 * Fonts are the one behaviour that genuinely differs, and it is the platform's doing rather than
 * this API's. Apple has CoreText and Windows has GDI/DirectWrite, so libass finds system fonts by
 * itself there. Android, Linux and the JVM have no provider in this chain (fontconfig is
 * deliberately absent, decision D-7), so a caller supplies fonts through [addFont] or gets an empty
 * render. That is why [addFont] is on the common API rather than hidden on one actual.
 */
public expect class LibassRenderer() : AutoCloseable {

    /**
     * Adds one font from memory, under [name], for libass to shape with.
     *
     * On a platform whose libass has a system font provider this ADDS to what it already finds; on
     * one without, these are the only fonts there are.
     */
    public fun addFont(name: String, data: ByteArray)

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
    ): SubtitleCue.Bitmap?

    override fun close()
}
