/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File

/** The module folders that `project.yaml` lists, relative to [repoRoot], in file order. */
fun projectModules(repoRoot: File): List<String> {
    val lines = repoRoot.resolve("project.yaml").readLines()
    val start = lines.indexOfFirst { it.trimEnd() == "modules:" }
    if (start < 0) throw BuildFailure("No modules list in ${repoRoot.resolve("project.yaml")}.")
    return lines.drop(start + 1)
        .takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("#") }
        .mapNotNull { Regex("""^\s+-\s+(\S+)""").find(it)?.groupValues?.get(1) }
        .map { it.removePrefix("./").trimEnd('/') }
}

/** The published modules: each one enables the `publication-check` plugin in its `module.yaml`. */
fun publishedModules(repoRoot: File): List<String> = projectModules(repoRoot).filter { module ->
    repoRoot.resolve("$module/module.yaml").readLines().any { it.trim().startsWith("publication-check:") }
}
