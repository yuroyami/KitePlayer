package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.Foundation.NSActivityIdleDisplaySleepDisabled
import platform.Foundation.NSActivityUserInitiated
import platform.Foundation.NSProcessInfo
// The two calls are a category of NSProcessInfo, so Kotlin needs them imported by name.
import platform.Foundation.beginActivityWithOptions
import platform.Foundation.endActivity

/** A Foundation activity that disables idle display sleep while this is composed. */
@Composable
internal actual fun HoldDisplayAwake() {
    DisposableEffect(Unit) {
        val activity = NSProcessInfo.processInfo.beginActivityWithOptions(
            NSActivityIdleDisplaySleepDisabled or NSActivityUserInitiated,
            "Showing a playing video",
        )
        onDispose { NSProcessInfo.processInfo.endActivity(activity) }
    }
}
