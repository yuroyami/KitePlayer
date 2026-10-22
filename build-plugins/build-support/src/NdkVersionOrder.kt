/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File

/**
 * Orders Android NDK folder names such as `29.0.14206865` by their numbers.
 * Text order would put `9.0` above `29.0`. A suffixed part (`rc1`) sorts below the plain number.
 */
object NdkVersionOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (index in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(index) ?: return -1
            val y = right.getOrNull(index) ?: return 1
            val order = comparePart(x, y)
            if (order != 0) return order
        }
        return 0
    }

    private fun comparePart(x: String, y: String): Int {
        val xDigits = x.takeWhile(Char::isDigit)
        val yDigits = y.takeWhile(Char::isDigit)
        val byNumber = compareValues(xDigits.toBigIntegerOrNull(), yDigits.toBigIntegerOrNull())
        if (byNumber != 0) return byNumber
        val xRest = x.drop(xDigits.length)
        val yRest = y.drop(yDigits.length)
        return when {
            xRest == yRest -> 0
            xRest.isEmpty() -> 1
            yRest.isEmpty() -> -1
            else -> xRest.compareTo(yRest)
        }
    }
}

/** The newest NDK under [ndkRoot], an SDK's `ndk` folder. */
fun newestNdk(ndkRoot: File): File? =
    ndkRoot.listFiles { file: File -> file.isDirectory }?.maxWithOrNull(compareBy(NdkVersionOrder) { it.name })

/**
 * The Android NDK: `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT` or `ANDROID_NDK_LATEST_HOME`, else the newest
 * one in the SDK that `ANDROID_HOME` or `ANDROID_SDK_ROOT` names, else in the default SDK folders.
 */
fun resolveNdk(): File? {
    sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
        .mapNotNull(System::getenv)
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?.let { return it }
    val home = System.getProperty("user.home")
    val sdkDirs = listOfNotNull(
        System.getenv("ANDROID_HOME")?.let(::File),
        System.getenv("ANDROID_SDK_ROOT")?.let(::File),
        File(home, "Library/Android/sdk"),
        File(home, "Android/Sdk"),
    )
    return sdkDirs.map { it.resolve("ndk") }.firstOrNull { it.isDirectory }?.let(::newestNdk)
}
