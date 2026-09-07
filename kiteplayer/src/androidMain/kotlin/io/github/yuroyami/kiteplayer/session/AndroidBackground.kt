package io.github.yuroyami.kiteplayer.session

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform

/**
 * Stops [player] decoding video for a screen nobody is looking at.
 *
 * Counts started activities through the application's own callbacks, so no lifecycle library is
 * added and the application registers nothing itself. Zero started activities means the
 * application is in the background.
 *
 * With [BackgroundPolicy.ContinueAudio], sound in the background on Android still needs the
 * application's own foreground service. This handle only parks the picture; it cannot keep a
 * process alive.
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
    private var startedActivities = 0

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (startedActivities++ == 0) applier.handle(foreground = true)
        }

        override fun onActivityStopped(activity: Activity) {
            if (--startedActivities == 0) applier.handle(foreground = false)
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
        application.unregisterActivityLifecycleCallbacks(callbacks)
    }
}
