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
 * the view and one of them pausing must not let the display sleep under the other. The last
 * release puts back the value the app had set (#932). Main thread only.
 */
private object ScreenOnHolds {
    private val holds = WeakHashMap<View, FlagHolds>()

    fun acquire(view: View) {
        holds.getOrPut(view) { FlagHolds(read = { view.keepScreenOn }, write = { view.keepScreenOn = it }) }.acquire()
    }

    fun release(view: View) {
        val flag = holds[view] ?: return
        flag.release()
        if (flag.holds == 0) holds.remove(view)
    }
}
