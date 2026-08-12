package io.github.yuroyami.kiteplayer.ffmpeg

import androidx.test.platform.app.InstrumentationRegistry

/**
 * The matrix clips are pushed once, after install, into the instrumentation package's own
 * external files directory, which needs no permission to read:
 * `adb push testmedia/. /storage/emulated/0/Android/data/<pkg>/files/testmedia/`.
 *
 * Two sharp edges, both measured in S1.e.4: a directory created over adb belongs to the shell
 * uid and the emulated-storage FUSE answers the app EACCES for everything under it, so after
 * the push run `adb root` and `chown -R <appId>:ext_data_rw` on the pushed tree (the app id is
 * in `dumpsys package <pkg>`), then `adb unroot`; and the managed connectedAndroidDeviceTest
 * task reinstalls the APK, which orphans the pushed tree, so run the installed instrumentation
 * directly: `adb shell am instrument -w -e class <this class> <pkg>/androidx.test.runner.AndroidJUnitRunner`.
 */
internal actual fun formatMatrixMediaDir(): String? {
    val context = InstrumentationRegistry.getInstrumentation().context
    val root = context.getExternalFilesDir(null) ?: return null
    return "${root.absolutePath}/testmedia"
}
