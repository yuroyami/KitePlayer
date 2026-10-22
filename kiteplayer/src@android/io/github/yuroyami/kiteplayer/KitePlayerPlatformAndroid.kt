package io.github.yuroyami.kiteplayer

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether this device allows picture-in-picture at all: the package manager's feature flag.
 *
 * The user's per-app permission and the activity's manifest declaration are still the
 * application's to check, and the activity owns the transition. The context-free
 * `KitePlayer.supportsPictureInPicture` answers a different question on Android: whether a player
 * can be built here, which is the floor for having anything to put in the window.
 */
public fun KitePlayer.Companion.supportsPictureInPicture(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** Whether this device allows picture-in-picture at all. See `KitePlayer.supportsPictureInPicture(context)`. */
@Deprecated(
    "Use KitePlayer.supportsPictureInPicture(context).",
    ReplaceWith("KitePlayer.supportsPictureInPicture(context)", "io.github.yuroyami.kiteplayer.supportsPictureInPicture"),
)
public fun KitePlayerPlatform.supportsPictureInPicture(context: Context): Boolean =
    KitePlayer.supportsPictureInPicture(context)
