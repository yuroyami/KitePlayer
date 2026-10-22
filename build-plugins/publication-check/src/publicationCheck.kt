/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build.publication

import io.github.yuroyami.kiteplayer.build.BuildFailure
import io.github.yuroyami.kiteplayer.build.PublicationReadiness
import io.github.yuroyami.kiteplayer.build.projectModules
import org.jetbrains.amper.plugins.TaskAction
import java.io.File

/**
 * Reads the POMs of this module from the local Maven repository (`KITEPLAYER_POM_REPOSITORY`, else
 * `~/.m2/repository`), so run `./kotlin publish mavenLocal` first. With
 * `KITEPLAYER_REQUIRE_SIGNING=true`, a run without `KOTLIN_TOOLCHAIN_SIGNING_KEY` fails.
 */
@TaskAction
fun checkPublication(repoRoot: String, group: String?, artifactId: String, version: String?) {
    if (group == null || version == null) {
        throw BuildFailure("$artifactId has no settings.publishing.group or settings.publishing.version.")
    }
    val repository = System.getenv("KITEPLAYER_POM_REPOSITORY")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".m2/repository")
    PublicationReadiness.checkLocalPublication(
        repository = repository,
        group = group,
        artifactId = artifactId,
        version = version,
        projectModules = projectModules(File(repoRoot)),
        signingConfigured = !System.getenv("KOTLIN_TOOLCHAIN_SIGNING_KEY").isNullOrBlank(),
        requireSigning = System.getenv("KITEPLAYER_REQUIRE_SIGNING") == "true",
        log = { println("[publication] $it") },
    )
}
