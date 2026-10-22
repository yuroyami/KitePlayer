/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build.media

import io.github.yuroyami.kiteplayer.build.sampleSongs
import io.github.yuroyami.kiteplayer.build.syncMediaFiles
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path

/**
 * Writes `testmedia/sync1080p30.mp4` and the sample songs into the gitignored `assets/` folder of
 * the module, which the APK packages. [marker] is an empty source folder that makes the compile
 * wait for this task. The task always runs, because the songs live outside the module.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun copySampleMedia(repoRoot: String, assetsDir: String, @Output marker: Path) {
    marker.toFile().mkdirs()
    val root = File(repoRoot)
    val sources = listOf(root.resolve("testmedia/sync1080p30.mp4")) + sampleSongs(root)
    val digests = syncMediaFiles(
        sources,
        File(assetsDir),
        "The clip comes from ./scripts/testmedia.sh, the songs from kiteplayer-sample-shared/media or kiteplayer.sample.song.",
    )
    digests.forEach { (name, sha) -> println("[sample-media] $name sha256=$sha") }
}
