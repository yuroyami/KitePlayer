package io.github.yuroyami.kiteplayer.audioviz

/**
 * Makes sure Compose's Skia graphics are registered before a test draws. Compose 1.12 registers
 * them by itself; from 1.13 on, no `Path` or `ImageBitmap` can be made until a test does it.
 */
internal expect fun useSkiaGraphics()
