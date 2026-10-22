/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build.kitert

import io.github.yuroyami.kiteplayer.build.BuildFailure
import io.github.yuroyami.kiteplayer.build.CompileKiteRt
import io.github.yuroyami.kiteplayer.build.konanDataDir
import io.github.yuroyami.kiteplayer.build.defWithProperties
import io.github.yuroyami.kiteplayer.build.sha256
import io.github.yuroyami.kiteplayer.build.writeIfChanged
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Compiles the C ring for [konanTarget] into [archiveDir] and writes [defFile]: the committed
 * `native/kitert.def` plus the archive folder, the header folder and the archive's SHA-256.
 * cinterop reruns only when the def file changes, so the digest line makes a rebuilt archive reach the klib.
 */
@TaskAction
fun compileKiteRt(
    konanTarget: String,
    kotlinVersion: String,
    @Input sourceDir: Path,
    @Input includeDir: Path,
    @Input defTemplate: Path,
    @Output archiveDir: Path,
    @Output defFile: Path,
) {
    if (!CompileKiteRt.hostCanCompile(konanTarget)) {
        throw BuildFailure("This host cannot compile the C ring for $konanTarget: Apple targets need macOS with Xcode.")
    }
    val log: (String) -> Unit = { println("[kiteplayer-rt] $it") }
    val dataDir = konanDataDir()
    CompileKiteRt.ensureToolchain(konanTarget, kotlinVersion, dataDir, log)
    CompileKiteRt(
        konanTargetName = konanTarget,
        sourceDir = sourceDir.toFile(),
        includeDir = includeDir.toFile(),
        konanDataDir = dataDir,
        outputDir = archiveDir.toFile(),
    ).compile(log)
    val archive = archiveDir.resolve(CompileKiteRt.ARCHIVE_NAME).toFile()
    writeIfChanged(
        defFile.toFile(),
        defWithProperties(
            defTemplate.readText(),
            listOf(
                "# Added by the kitert-c plugin for $konanTarget.",
                "libraryPaths = ${archiveDir.toAbsolutePath()}",
                "compilerOpts = -I${includeDir.toAbsolutePath()}",
                "# ${CompileKiteRt.ARCHIVE_NAME} sha256 ${sha256(archive)}",
            ),
        ),
    )
}
