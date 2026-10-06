package io.github.yuroyami.kiteplayer.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSActivityIdleDisplaySleepDisabled
import platform.Foundation.NSActivityUserInitiated
import platform.Foundation.NSProcessInfo
import platform.darwin.NSObjectProtocol
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Keeps a Mac's display awake while a picture plays (#238). The renderers here draw through their
 * own layers, so they do not get the display hold AVPlayer takes during video, and without this the
 * display sleeps in the middle of a film.
 *
 * The hold is a Foundation activity that disables idle display sleep, which `pmset -g assertions`
 * lists while it stands. A renderer says [framePresented] for each picture it shows, and the hold
 * stands until [GRACE] passes with none: a pause, the end, a stall or a closed renderer lets the
 * display sleep, as a player with no picture to watch should.
 *
 * Main thread only, as the renderer shows its pictures there; its own wait runs there too.
 */
internal object MacDisplayAwake {
    /** How long the display stays awake after the last picture shown. */
    val GRACE: Duration = 2.seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activity: NSObjectProtocol? = null
    private var lastFrame: TimeMark? = null
    private var watch: Job? = null

    fun framePresented() {
        lastFrame = TimeSource.Monotonic.markNow()
        if (activity == null) {
            activity = NSProcessInfo.processInfo.beginActivityWithOptions(
                NSActivityIdleDisplaySleepDisabled or NSActivityUserInitiated,
                "Showing a playing video",
            )
        }
        if (watch != null) return
        watch = scope.launch {
            while (true) {
                val since = lastFrame?.elapsedNow() ?: GRACE
                if (since >= GRACE) break
                delay(GRACE - since)
            }
            activity?.let { NSProcessInfo.processInfo.endActivity(it) }
            activity = null
            watch = null
        }
    }
}
