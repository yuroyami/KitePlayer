package io.github.yuroyami.kiteplayer.view

import platform.AVKit.AVPictureInPictureController

// The macOS controller has no such switch: the window opens only when asked.
internal actual var AVPictureInPictureController.startsAutomaticallyFromInline: Boolean
    get() = false
    set(@Suppress("UNUSED_PARAMETER") value) = Unit
