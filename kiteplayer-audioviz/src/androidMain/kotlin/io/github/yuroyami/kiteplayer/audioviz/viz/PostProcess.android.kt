package io.github.yuroyami.kiteplayer.audioviz.viz

import android.os.Build

/** Layer effects arrived in Android 12. Below that a blur request is silently ignored. */
internal actual val blurAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
