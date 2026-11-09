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
 * thread's dictionary, because the idle timer is one flag per app. The value the app had set
 * before the first hold sits under [BEFORE_KEY], which the twin reads too, and the last release
 * puts it back (#932). Main thread only.
 */
private object IdleTimerHolds {
    private const val KEY = "io.github.yuroyami.kiteplayer.idleTimerHolds"
    private const val BEFORE_KEY = "io.github.yuroyami.kiteplayer.idleTimerBefore"

    fun acquire() {
        val count = count() + 1
        store(count)
        if (count != 1) return
        val before = UIApplication.sharedApplication.idleTimerDisabled
        NSThread.mainThread.threadDictionary.setObject(NSNumber(bool = before), forKey = BEFORE_KEY as NSString)
        if (!before) UIApplication.sharedApplication.idleTimerDisabled = true
    }

    fun release() {
        val count = count() - 1
        if (count < 0) return
        store(count)
        if (count != 0) return
        val dictionary = NSThread.mainThread.threadDictionary
        val before = (dictionary.objectForKey(BEFORE_KEY) as? NSNumber)?.boolValue ?: false
        dictionary.removeObjectForKey(BEFORE_KEY)
        if (!before) UIApplication.sharedApplication.idleTimerDisabled = false
    }

    private fun count(): Int = (NSThread.mainThread.threadDictionary.objectForKey(KEY) as? NSNumber)?.intValue ?: 0

    private fun store(count: Int) {
        NSThread.mainThread.threadDictionary.setObject(NSNumber(int = count), forKey = KEY as NSString)
    }
}
