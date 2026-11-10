package io.github.yuroyami.kiteplayer.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import io.github.yuroyami.kiteplayer.KiteLog

internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath = when (requested) {
    KiteRenderPath.Auto, KiteRenderPath.NativeView -> KiteRenderPath.NativeView
    KiteRenderPath.ComposeCanvas -> KiteRenderPath.ComposeCanvas
}

/** The window unlocks the API 31+ GPU path; without an Activity the software state still works. */
@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState {
    val window = hostWindow(LocalView.current)
    return if (window != null) rememberKiteVideoState(window) else rememberKiteVideoState()
}

/**
 * The window that hosts [view] (#933). A Compose `Dialog` has a window of its own, which its
 * layout hands out as a [DialogWindowProvider] above the view, and the GPU tier refuses any window
 * but the one that hosts the view, so the Activity's window there sent the engine to software
 * decoding with no word. The Activity's window counts only when the view sits in it; in a window
 * with no [Window] object, such as a `Popup`'s, the state is the software one and says so once.
 */
private fun hostWindow(view: View): Window? {
    var parent: ViewParent? = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    val activity = generateSequence<Context>(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull() ?: return null
    val token = view.windowToken
    if (token != null && token != activity.window.decorView.windowToken) {
        if (!foreignWindowSaid) {
            foreignWindowSaid = true
            KiteLog.log("KitePlayerVideo", "the video sits in a window other than its Activity's, such as a Popup's, so it decodes in software")
        }
        return null
    }
    return activity.window
}

/** Composition runs on the main thread, so a plain flag says it once per process. */
private var foreignWindowSaid = false

/** Compose content over the native view takes clicks here. */
internal actual val nativeViewTakesOverlayClicks: Boolean = true
