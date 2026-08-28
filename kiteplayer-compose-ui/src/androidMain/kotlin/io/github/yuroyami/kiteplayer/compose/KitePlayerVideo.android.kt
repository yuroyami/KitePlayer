package io.github.yuroyami.kiteplayer.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath = when (requested) {
    KiteRenderPath.Auto, KiteRenderPath.NativeView -> KiteRenderPath.NativeView
    KiteRenderPath.ComposeCanvas -> KiteRenderPath.ComposeCanvas
}

/** The window unlocks the API 31+ GPU path; without an Activity the software state still works. */
@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState {
    val context = LocalView.current.context
    val activity = generateSequence<Context>(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()
    return if (activity != null) rememberKiteVideoState(activity.window) else rememberKiteVideoState()
}
