package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.UIKit.UIApplication

@Composable
internal actual fun HoldDisplayAwake() {
    DisposableEffect(Unit) {
        IdleTimerHolds.acquire()
        onDispose { IdleTimerHolds.release() }
    }
}

/**
 * The app's holds on the idle timer, counted once for the whole process. The twin of the object of
 * the same name in `kiteplayer-view`, and both keep the count under the same key in the main
 * thread's dictionary, because the idle timer is one flag per app. Main thread only.
 */
private object IdleTimerHolds {
    private const val KEY = "io.github.yuroyami.kiteplayer.idleTimerHolds"

    fun acquire() {
        val count = count() + 1
        store(count)
        if (count == 1) UIApplication.sharedApplication.idleTimerDisabled = true
    }

    fun release() {
        val count = count() - 1
        if (count < 0) return
        store(count)
        if (count == 0) UIApplication.sharedApplication.idleTimerDisabled = false
    }

    private fun count(): Int = (NSThread.mainThread.threadDictionary.objectForKey(KEY) as? NSNumber)?.intValue ?: 0

    private fun store(count: Int) {
        NSThread.mainThread.threadDictionary.setObject(NSNumber(int = count), forKey = KEY as NSString)
    }
}
