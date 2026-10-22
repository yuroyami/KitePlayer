/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build.libass

import io.github.yuroyami.kiteplayer.build.AssChain
import io.github.yuroyami.kiteplayer.build.BuildFailure
import io.github.yuroyami.kiteplayer.build.CompileKiteRt
import io.github.yuroyami.kiteplayer.build.LibassAdapters
import io.github.yuroyami.kiteplayer.build.defWithProperties
import io.github.yuroyami.kiteplayer.build.findJniJavaHome
import io.github.yuroyami.kiteplayer.build.hostIsMac
import io.github.yuroyami.kiteplayer.build.hostIsWindows
import io.github.yuroyami.kiteplayer.build.konanDataDir
import io.github.yuroyami.kiteplayer.build.sha256
import io.github.yuroyami.kiteplayer.build.syncDirectory
import io.github.yuroyami.kiteplayer.build.writeIfChanged
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText

private val log: (String) -> Unit = { println("[kiteplayer-libass] $it") }

/**
 * Merges the chain of [konanTarget] into `<workDir>/libkiteass.a` and writes [defFile]: the committed
 * `native/libass.def` plus the archive folder, the header folders and the archive's SHA-256.
 * It always runs, because the chain lives outside this repository. The def file changes only when the archive does.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun mergeAssChain(
    konanTarget: String,
    kotlinVersion: String,
    repoRoot: String,
    @Input pinFile: Path,
    @Input defTemplate: Path,
    @Input driverDir: Path,
    @Output workDir: Path,
    @Output defFile: Path,
) {
    val dirName = AssChain.NATIVE_CHAIN_DIRS[konanTarget] ?: throw BuildFailure("No ass chain folder is known for $konanTarget.")
    val dataDir = konanDataDir()
    CompileKiteRt.ensureToolchain(konanTarget, kotlinVersion, dataDir, log)
    val chain = AssChain.find(dirName, File(repoRoot), AssChain.readPins(pinFile.toFile()), workDir.toFile().resolve("fetched"), log)
        ?: throw BuildFailure(
            "No ass chain for $dirName. Set KITEPLAYER_LIBASS_ROOT to a KiteFFmpeg native-libs/deps folder, keep the " +
                "KiteFFmpeg checkout next to this one, or pin the release asset in kiteplayer-libass/ass-chain.sha256.",
        )
    val archive = AssChain.merge(chain, konanTarget, dataDir, workDir.toFile().resolve("merged"), log)
    writeIfChanged(
        defFile.toFile(),
        defWithProperties(
            defTemplate.readText(),
            listOf(
                "# Added by the libass-native plugin for $konanTarget.",
                "libraryPaths = ${archive.parentFile.absolutePath}",
                "compilerOpts = -I${chain.includeDir.absolutePath} -I${driverDir.toAbsolutePath()}",
                "# ${AssChain.ARCHIVE_NAME} sha256 ${sha256(archive)}",
            ),
        ),
    )
}

/**
 * Links the desktop JNI library of every host this machine can link for, into
 * `<resourcesDir>/kiteplayer-libass-native/<host>/`, which the JVM jar carries.
 * With `KITEPLAYER_LIBASS_REQUIRE_ALL_HOST_JNI=true` a host that cannot be linked fails the build,
 * and the konan sysroots are downloaded first. A release build sets it.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun buildHostJni(
    kotlinVersion: String,
    repoRoot: String,
    @Input pinFile: Path,
    @Input driverDir: Path,
    @Output workDir: Path,
    @Output resourcesDir: Path,
) {
    val requireAll = System.getenv("KITEPLAYER_LIBASS_REQUIRE_ALL_HOST_JNI") == "true"
    val dataDir = konanDataDir()
    val pins = AssChain.readPins(pinFile.toFile())
    val work = workDir.toFile()
    val staging = work.resolve("staging").also { it.deleteRecursively(); it.mkdirs() }
    val javaHome = findJniJavaHome()
        ?: throw BuildFailure("No JDK with include/jni.h. Set JAVA_HOME to a JDK, or build a JVM module once so the toolchain unpacks one.")
    for ((host, chainDir) in LibassAdapters.CHAIN_DIR_FOR_HOST) {
        if (host == "macos-arm64" && !hostIsMac) {
            if (requireAll) throw BuildFailure("The macos-arm64 adapter links only on a Mac.")
            log("jvm jar skips the $host adapter: this host is not a Mac.")
            continue
        }
        val konanTarget = LibassAdapters.KONAN_TARGET_FOR_HOST.getValue(host)
        if (requireAll && konanTarget != null) CompileKiteRt.ensureToolchain(konanTarget, kotlinVersion, dataDir, log)
        if (!LibassAdapters.hostToolchainReady(host, dataDir)) {
            if (requireAll) throw BuildFailure("Cannot link the $host adapter: its konan sysroot is missing.")
            log("jvm jar skips the $host adapter: no konan sysroot for $konanTarget yet. Building that platform once provisions it.")
            continue
        }
        val chain = AssChain.find(chainDir, File(repoRoot), pins, work.resolve("fetched"), log)
        if (chain == null) {
            if (requireAll) throw BuildFailure("Cannot link the $host adapter: no ass chain for $chainDir.")
            log("jvm jar skips the $host adapter: no ass chain for $chainDir.")
            continue
        }
        val mergeTarget = konanTarget ?: "macos_arm64"
        CompileKiteRt.ensureToolchain(mergeTarget, kotlinVersion, dataDir, log)
        val merged = AssChain.merge(chain, mergeTarget, dataDir, work.resolve("merged/$mergeTarget"), log)
        LibassAdapters.buildHostJni(
            hostTriple = host,
            sourceFile = driverDir.toFile().resolve("libass_jni.c"),
            driverDir = driverDir.toFile(),
            chainIncludeDir = chain.includeDir,
            mergedArchive = merged,
            javaHome = javaHome,
            konanDataDir = dataDir,
            outputDir = staging,
            scratchDir = work.resolve("scratch"),
            log = log,
        )
    }
    if (hostIsWindows) log("Windows hosts link only the adapters of other hosts that their konan sysroots allow.")
    syncDirectory(staging, resourcesDir.toFile())
}
