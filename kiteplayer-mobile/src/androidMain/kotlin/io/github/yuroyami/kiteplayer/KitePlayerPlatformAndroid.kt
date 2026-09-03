package io.github.yuroyami.kiteplayer

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether this device allows picture-in-picture at all: the package manager's feature flag.
 *
 * The user's per-app permission and the activity's manifest declaration are still the
 * application's to check, and the activity owns the transition. The context-free
 * [KitePlayerPlatform.supportsPictureInPicture] answers a different question: whether a player
 * can be built here, which is the floor for having anything to put in the window.
 */
public fun KitePlayerPlatform.supportsPictureInPicture(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
