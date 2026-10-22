package io.github.yuroyami.kiteplayer.view

import platform.QuartzCore.CALayer
import platform.QuartzCore.CAMetalLayer

/**
 * Creates the renderer hosted by [KitePlayerUIView].
 *
 * The view owns UIKit lifecycle and layer geometry; an adapter module owns codec-specific frame
 * conversion and the concrete renderer. This keeps the XML/UIKit widget independent from any
 * media backend while still allowing a convenience stack to install its default adapter.
 */
public fun interface ApplePlayerViewRendererFactory {
    public fun create(
        videoLayer: CALayer,
        metalLayer: CAMetalLayer,
        preferMetal: Boolean,
    ): PlayerViewRenderer
}
