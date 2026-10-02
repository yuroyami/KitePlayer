@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSScreen
import platform.AppKit.NSView
import platform.AppKit.maximumExtendedDynamicRangeColorComponentValue
import platform.AppKit.maximumPotentialExtendedDynamicRangeColorComponentValue
import platform.QuartzCore.CAMetalLayer

// The screen of the window that hosts the layer, or the main screen for a layer with no view.
internal actual fun readScreenHeadroom(layer: CAMetalLayer): Pair<Float, Float> {
    val screen = (layer.delegate as? NSView)?.window?.screen ?: NSScreen.mainScreen ?: return 1f to 1f
    return screen.maximumPotentialExtendedDynamicRangeColorComponentValue.toFloat() to
        screen.maximumExtendedDynamicRangeColorComponentValue.toFloat()
}

internal actual fun setExtendedRangeContent(layer: CAMetalLayer, extended: Boolean) {
    layer.wantsExtendedDynamicRangeContent = extended
}
