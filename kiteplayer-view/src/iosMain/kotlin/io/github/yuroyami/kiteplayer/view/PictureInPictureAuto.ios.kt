package io.github.yuroyami.kiteplayer.view

import platform.AVKit.AVPictureInPictureController

internal actual var AVPictureInPictureController.startsAutomaticallyFromInline: Boolean
    get() = canStartPictureInPictureAutomaticallyFromInline
    set(value) {
        canStartPictureInPictureAutomaticallyFromInline = value
    }
