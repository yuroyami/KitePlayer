package io.github.yuroyami.kiteplayer.phone

import android.content.Context
import android.util.AttributeSet
import io.github.yuroyami.kiteplayer.mobile.installMobileRenderer

/** Use [io.github.yuroyami.kiteplayer.view.KitePlayerView] from `kiteplayer-view`. */
@Deprecated("Use KitePlayerView from kiteplayer-view")
public class KitePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : io.github.yuroyami.kiteplayer.view.KitePlayerView(context, attrs, defStyleAttr) {
    init {
        installMobileRenderer()
    }
}
