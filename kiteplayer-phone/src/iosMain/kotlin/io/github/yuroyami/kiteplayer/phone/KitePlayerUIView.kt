@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.phone

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.mobile.installMobileRenderer
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIColor
import platform.UIKit.UIView

/** Use [io.github.yuroyami.kiteplayer.view.KitePlayerUIView] from `kiteplayer-view`. */
@Deprecated("Use KitePlayerUIView from kiteplayer-view")
public class KitePlayerUIView : UIView(frame = CGRectZero.readValue()) {
    private val delegate = io.github.yuroyami.kiteplayer.view.KitePlayerUIView().apply {
        installMobileRenderer()
    }

    public var preferMetal: Boolean
        get() = delegate.preferMetal
        set(value) {
            delegate.preferMetal = value
        }

    public var player: KitePlayer?
        get() = delegate.player
        set(value) {
            delegate.player = value
        }

    public val presentedFrames: Long get() = delegate.presentedFrames
    public val supersededFrames: Long get() = delegate.supersededFrames
    public val failedFrames: Long get() = delegate.failedFrames
    public val hasPicture: Boolean get() = delegate.hasPicture

    init {
        backgroundColor = UIColor.blackColor
        addSubview(delegate)
    }

    public fun release() {
        delegate.release()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        delegate.setFrame(bounds)
    }
}
