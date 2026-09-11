package io.github.yuroyami.kiteplayer.audioviz

// Compose 1.12 registers Skia by itself. From 1.13 on, this must call registerSkikoComposeImplementation().
internal actual fun useSkiaGraphics() {}
