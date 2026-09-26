package io.github.yuroyami.kiteplayer.phone

import android.content.Context
import android.util.AttributeSet
import io.github.yuroyami.kiteplayer.mobile.installMobileRenderer

/**
 * Use [io.github.yuroyami.kiteplayer.view.KitePlayerView] from `kiteplayer-view`, and call
 * `installMobileRenderer()` from `kiteplayer` on it. This view installs that renderer by itself;
 * the replacement draws nothing until it has one.
 */
@Deprecated(
    "Use KitePlayerView from kiteplayer-view and call installMobileRenderer() from kiteplayer on it. " +
        "Without that call the new view plays the audio and draws nothing.",
)
public class KitePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : io.github.yuroyami.kiteplayer.view.KitePlayerView(context, attrs, defStyleAttr) {
    init {
        installMobileRenderer()
    }
}
