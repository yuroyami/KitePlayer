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
 * this object uses too, and so does the value the app had set before the first hold, under
 * [BEFORE_KEY]: the last release puts that back rather than clearing a flag the app set itself
 * (#932). Main thread only, like every UIKit call.
 */
internal object IdleTimerHolds {
    private const val KEY = "io.github.yuroyami.kiteplayer.idleTimerHolds"
    private const val BEFORE_KEY = "io.github.yuroyami.kiteplayer.idleTimerBefore"

    /** Sets the app's idle timer flag. A test replaces it, because a test binary has no app. */
    internal var setIdleTimerDisabled: (Boolean) -> Unit = { UIApplication.sharedApplication.idleTimerDisabled = it }

    /** Reads the app's idle timer flag. A test replaces it with [setIdleTimerDisabled]. */
    internal var isIdleTimerDisabled: () -> Boolean = { UIApplication.sharedApplication.idleTimerDisabled }

    fun acquire() {
        val count = count() + 1
        store(count)
        if (count != 1) return
        val before = isIdleTimerDisabled()
        NSThread.mainThread.threadDictionary.setObject(NSNumber(bool = before), forKey = BEFORE_KEY as NSString)
        if (!before) setIdleTimerDisabled(true)
    }

    fun release() {
        val count = count() - 1
        if (count < 0) return
        store(count)
        if (count != 0) return
        val dictionary = NSThread.mainThread.threadDictionary
        val before = (dictionary.objectForKey(BEFORE_KEY) as? NSNumber)?.boolValue ?: false
        dictionary.removeObjectForKey(BEFORE_KEY)
        if (!before) setIdleTimerDisabled(false)
    }

    internal fun count(): Int = (NSThread.mainThread.threadDictionary.objectForKey(KEY) as? NSNumber)?.intValue ?: 0

    private fun store(count: Int) {
        NSThread.mainThread.threadDictionary.setObject(NSNumber(int = count), forKey = KEY as NSString)
    }
}
