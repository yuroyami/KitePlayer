package io.github.yuroyami.kiteplayer.audioviz

/**
 * Registers Compose's Skia graphics before a test draws. From 1.13, Compose registers them only when a
 * window or a scene opens, so no `Path` or `ImageBitmap` can be made in a test until this runs.
 */
internal expect fun useSkiaGraphics()
