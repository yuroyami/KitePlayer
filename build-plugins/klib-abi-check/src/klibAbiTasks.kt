/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.klibabi

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.kotlin.abi.tools.AbiTools
import org.jetbrains.kotlin.abi.tools.KlibDump
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

// The klib half of build-plugins/abi-check, for a module that has no JVM platform.

/** Fails when the built klib dump differs from the committed one. Line endings are ignored. */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkKlibAbi(
    moduleName: String,
    @Input builtDump: Path,
    @Input(inferTaskDependency = false) committedDump: Path,
) = compareDumps(moduleName, builtDump, committedDump, "updateKlibOnlyAbi")

private fun compareDumps(moduleName: String, builtDump: Path, committedDump: Path, updateCommand: String) {
    check(committedDump.exists()) {
        "No ABI dump at $committedDump. Run `./kotlin do $updateCommand -m $moduleName` to create it."
    }
    val expected = committedDump.readText().lines()
    val actual = builtDump.readText().lines()
    if (expected == actual) return
    val diff = AbiTools.getInstance(AbiTools::class.java.classLoader)
        .filesDiff(committedDump.toFile(), builtDump.toFile())
    error(
        "The public API of $moduleName changed.\n$diff\n\n" +
            "If the change is intended, run `./kotlin do $updateCommand -m $moduleName` and commit the dump."
    )
}

/** Overwrites the committed dump with the built one. */
@TaskAction
fun updateKlibAbi(
    @Input builtDump: Path,
    @Output committedDump: Path,
) {
    builtDump.copyTo(committedDump.createParentDirectories(), overwrite = true)
}

/**
 * Writes the merged klib ABI dump of [platforms], in the same format as the Kotlin Gradle plugin.
 * No plugin reference gives the klibs, so this reads them from the build folder: run `./kotlin build` first.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun dumpKlibAbi(
    moduleName: String,
    platforms: List<String>,
    // A String, not an @Input Path: the build folder holds this task's own output.
    buildDir: String,
    @Output dumpFile: Path,
) {
    val tools = AbiTools.getInstance(AbiTools::class.java.classLoader)
    val merged: KlibDump = tools.createKlibDump()
    for (platform in platforms) {
        val task = platform.replaceFirstChar { it.uppercase() }
        // Native platforms compile a debug and a release klib. Web platforms compile one.
        val klib = listOf("_${moduleName}_compile${task}Debug", "_${moduleName}_compile$task")
            .map { Path(buildDir, "tasks", it, "$moduleName.klib") }
            .firstOrNull { it.exists() }
            ?: error("No $platform klib of $moduleName. Run `./kotlin build -p $platform` first.")
        val dump = tools.extractKlibAbi(klib.toFile())
        println("$platform: ${dump.targets}")
        merged.merge(dump)
    }
    merged.print(dumpFile.createParentDirectories().toFile())
}
