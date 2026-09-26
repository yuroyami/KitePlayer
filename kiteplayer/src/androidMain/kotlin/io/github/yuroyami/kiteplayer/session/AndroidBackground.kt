package io.github.yuroyami.kiteplayer.session

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform

/**
 * Stops [player] decoding video for a screen nobody is looking at.
 *
 * Follows started activities through the application's own callbacks, so no lifecycle library is
 * added and the application registers nothing itself. No started activity means the application
 * is in the background. Attaching after an activity started, from `onResume` or from a composable,
 * works too: the process importance at attach time says whether one was already on screen.
 *
 * With [BackgroundPolicy.ContinueAudio], this handle only parks the picture, so sound in the
 * background also needs `KitePlayerPlatform.attachMediaNotification` to keep the process alive.
 *
 * @throws IllegalArgumentException when [context] does not belong to an [Application].
 */
public fun KitePlayerPlatform.attachBackgroundHandling(
    player: KitePlayer,
    context: Context,
    policy: BackgroundPolicy = BackgroundPolicy.ContinueAudio,
): AutoCloseable {
    val application = context.applicationContext as? Application
        ?: throw IllegalArgumentException("background handling needs a context whose application is reachable")
    return AndroidBackgroundHandle(player, application, policy)
}

private class AndroidBackgroundHandle(
    player: KitePlayer,
    private val application: Application,
    policy: BackgroundPolicy,
) : AutoCloseable {

    private val applier = BackgroundApplier(PlayerSessionTarget(player), policy)
    private val activities = StartedActivities(startedBeforeAttach = activityOnScreenNow())
    private var closed = false

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            activities.onStarted(activity)?.let { applier.handle(foreground = it) }
        }

        override fun onActivityStopped(activity: Activity) {
            activities.onStopped(activity, activity.isChangingConfigurations)?.let { applier.handle(foreground = it) }
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    init {
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    override fun close() {
        if (closed) return
        closed = true
        application.unregisterActivityLifecycleCallbacks(callbacks)
    }
}

/**
 * Whether an activity of this process is on screen now. A foreground service alone reports a
 * lower importance than these two, so a playing media notification does not count.
 */
private fun activityOnScreenNow(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
}
