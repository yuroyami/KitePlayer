/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build.libass.android

import io.github.yuroyami.kiteplayer.build.AssChain
import io.github.yuroyami.kiteplayer.build.BuildFailure
import io.github.yuroyami.kiteplayer.build.LibassAdapters
import io.github.yuroyami.kiteplayer.build.resolveNdk
import io.github.yuroyami.kiteplayer.build.syncDirectory
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path

private val log: (String) -> Unit = { println("[kiteplayer-libass] $it") }

/**
 * Links `lib/<abi>/libkiteplayer_libass_jni.so` for every ABI with a chain, into [resourcesDir].
 * AGP takes `lib/<abi>/` out of a plain jar dependency and packages it as native libraries.
 * Without an NDK or a chain the jar ships no library and the build says so, unless
 * `KITEPLAYER_LIBASS_REQUIRE_ANDROID_JNI=true`, which a release build sets.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun buildAndroidJni(
    repoRoot: String,
    @Input pinFile: Path,
    @Input sourceFile: Path,
    @Output workDir: Path,
    @Output resourcesDir: Path,
) {
    val require = System.getenv("KITEPLAYER_LIBASS_REQUIRE_ANDROID_JNI") == "true"
    val work = workDir.toFile()
    val staging = work.resolve("staging").also { it.deleteRecursively(); it.mkdirs() }
    val ndk = resolveNdk()
    if (ndk == null) {
        if (require) throw BuildFailure("No Android NDK found. Set ANDROID_NDK_HOME or install one into the SDK that ANDROID_HOME names.")
        log("WARNING: no Android NDK found, so the Android artifact carries no libass adapter.")
    } else {
        val pins = AssChain.readPins(pinFile.toFile())
        for (abi in LibassAdapters.ANDROID_ABIS) {
            val chain = AssChain.find(abi.depsDirName, File(repoRoot), pins, work.resolve("fetched"), log)
            if (chain == null) {
                if (require) throw BuildFailure("No ass chain for ${abi.depsDirName}.")
                log("WARNING: no ass chain for ${abi.depsDirName}, so ${abi.abiDirName} carries no libass adapter.")
                continue
            }
            LibassAdapters.buildAndroidJni(abi, chain, sourceFile.toFile(), ndk, staging, log)
        }
    }
    syncDirectory(staging, resourcesDir.toFile())
}
