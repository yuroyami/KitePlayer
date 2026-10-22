/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build.checks

import io.github.yuroyami.kiteplayer.build.DuplicateClasses
import io.github.yuroyami.kiteplayer.build.KitertCoupling
import io.github.yuroyami.kiteplayer.build.BuildFailure
import io.github.yuroyami.kiteplayer.build.projectModules
import io.github.yuroyami.kiteplayer.build.publishedModules
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.TaskAction
import java.io.File

private val log: (String) -> Unit = { println("[repo-checks] $it") }

/** Fails when a source file outside the allowlist in `kitert-coupling-baseline.txt` names the kitert cinterop. */
@TaskAction
fun checkKitertCoupling(repoRoot: String) {
    val root = File(repoRoot)
    val modules = KitertCoupling.selectIncludedModulePaths(projectModules(root).filterNot { it.startsWith("build-plugins/") })
    KitertCoupling.check(root, KitertCoupling.sourceFilesOf(root, modules), modules, root.resolve(KitertCoupling.BASELINE_FILE_NAME), log)
}

/**
 * Fails when two published modules compile a class of the same name. It reads the classes that the
 * last build wrote, so run `./kotlin build -p jvm -p android` first.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkDuplicateClasses(repoRoot: String) {
    val root = File(repoRoot)
    val artifacts = root.resolve("build/artifacts/CompiledJvmArtifact")
    val modules = publishedModules(root).map { it.substringAfterLast('/') }
    fun classes(suffixes: List<String>): Map<String, List<File>> = modules.associateWith { module ->
        suffixes.map { artifacts.resolve("$module$it/kotlin-output") }.filter { it.isDirectory }.take(1)
    }.filterValues { it.isNotEmpty() }
    val jvm = classes(listOf("jvm"))
    val android = classes(listOf("androiddebug", "androidrelease"))
    if (jvm.isEmpty() && android.isEmpty()) {
        throw BuildFailure("No compiled classes under $artifacts. Run `./kotlin build -p jvm -p android` first.")
    }
    DuplicateClasses.check(jvm, android, log)
}
