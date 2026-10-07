package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation

/** From Compose 1.13, Skia graphics are registered only when a window or a scene opens. */
@OptIn(InternalComposeUiApi::class)
internal fun useSkiaGraphics() = registerSkikoComposeImplementation()
