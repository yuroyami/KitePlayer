package io.github.yuroyami.kiteplayer.compose

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

@Composable
internal actual fun HoldDisplayAwake() {
    val view = LocalView.current
    DisposableEffect(view) {
        ScreenOnHolds.acquire(view)
        onDispose { ScreenOnHolds.release(view) }
    }
}

/**
 * Counts the holds on each Compose view's `keepScreenOn`, because two videos in one window share
 * the view and one of them pausing must not let the display sleep under the other. Main thread only.
 */
private object ScreenOnHolds {
    private val holds = WeakHashMap<View, Int>()

    fun acquire(view: View) {
        val count = (holds[view] ?: 0) + 1
        holds[view] = count
        if (count == 1) view.keepScreenOn = true
    }

    fun release(view: View) {
        val count = (holds[view] ?: return) - 1
        if (count > 0) {
            holds[view] = count
        } else {
            holds.remove(view)
            view.keepScreenOn = false
        }
    }
}
