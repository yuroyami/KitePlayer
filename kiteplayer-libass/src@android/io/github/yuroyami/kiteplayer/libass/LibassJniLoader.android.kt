package io.github.yuroyami.kiteplayer.libass

/** Android's loader finds the packaged `.so` by name; the AAR carries one per ABI. */
internal actual fun loadLibassJni() = System.loadLibrary("kiteplayer_libass_jni")

/** Android has no font provider in this chain, so the system's font directory is read directly. */
internal actual fun defaultFontDirectories(): List<String> = listOf("/system/fonts", "/system/font", "/data/fonts")

internal actual val platformNeedsSystemFontFiles: Boolean = true
