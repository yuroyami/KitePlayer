package io.github.yuroyami.kiteplayer.view

import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.UIKit.UIApplication

/**
 * The app's holds on the idle timer, counted once for the whole process.
 *
 * The idle timer is one flag per app, so two counters would switch it back on under each other.
 * The count lives in the main thread's dictionary under [KEY], which the Compose module's twin of
 * this object uses too. Main thread only, like every UIKit call.
 */
internal object IdleTimerHolds {
    private const val KEY = "io.github.yuroyami.kiteplayer.idleTimerHolds"

    /** Sets the app's idle timer flag. A test replaces it, because a test binary has no app. */
    internal var setIdleTimerDisabled: (Boolean) -> Unit = { UIApplication.sharedApplication.idleTimerDisabled = it }

    fun acquire() {
        val count = count() + 1
        store(count)
        if (count == 1) setIdleTimerDisabled(true)
    }

    fun release() {
        val count = count() - 1
        if (count < 0) return
        store(count)
        if (count == 0) setIdleTimerDisabled(false)
    }

    internal fun count(): Int = (NSThread.mainThread.threadDictionary.objectForKey(KEY) as? NSNumber)?.intValue ?: 0

    private fun store(count: Int) {
        NSThread.mainThread.threadDictionary.setObject(NSNumber(int = count), forKey = KEY as NSString)
    }
}
