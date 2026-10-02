@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CAMetalLayer
import platform.UIKit.UIScreen
import platform.UIKit.UIView

// The headroom calls exist from iOS 16, so an older system answers standard range.
internal actual fun readScreenHeadroom(layer: CAMetalLayer): Pair<Float, Float> {
    val screen = (layer.delegate as? UIView)?.window?.screen ?: UIScreen.mainScreen
    if (!screen.respondsToSelector(NSSelectorFromString("potentialEDRHeadroom"))) return 1f to 1f
    return screen.potentialEDRHeadroom.toFloat() to screen.currentEDRHeadroom.toFloat()
}

internal actual fun setExtendedRangeContent(layer: CAMetalLayer, extended: Boolean) {
    if (layer.respondsToSelector(NSSelectorFromString("setWantsExtendedDynamicRangeContent:"))) {
        layer.wantsExtendedDynamicRangeContent = extended
    }
}
