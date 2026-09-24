package io.github.yuroyami.kiteplayer.view

import platform.AVKit.AVPictureInPictureController

/**
 * Whether the controller opens the window by itself when the viewer leaves the app while playing.
 *
 * iOS has this switch. macOS has no such feature, so there it reads false and a write does nothing.
 */
internal expect var AVPictureInPictureController.startsAutomaticallyFromInline: Boolean
