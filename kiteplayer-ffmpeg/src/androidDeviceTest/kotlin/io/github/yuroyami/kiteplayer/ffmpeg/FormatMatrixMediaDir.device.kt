package io.github.yuroyami.kiteplayer.ffmpeg

import androidx.test.platform.app.InstrumentationRegistry

/**
 * The matrix clips are pushed once, after install, into the instrumentation package's own
 * external files directory, which needs no permission to read:
 * `adb push testmedia/. /storage/emulated/0/Android/data/<pkg>/files/testmedia/`.
 */
internal actual fun formatMatrixMediaDir(): String? {
    val context = InstrumentationRegistry.getInstrumentation().context
    val root = context.getExternalFilesDir(null) ?: return null
    return "${root.absolutePath}/testmedia"
}
