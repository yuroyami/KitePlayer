package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation

@OptIn(InternalComposeUiApi::class)
internal actual fun useSkiaGraphics() = registerSkikoComposeImplementation()
