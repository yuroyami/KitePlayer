package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Unpacks every zip in [archives] into [outputDir], and removes what the zips no longer hold.
 *
 * A `Sync` task in a build script cannot open a zip lazily: the lambda that calls `zipTree` holds a
 * reference to the script, and the configuration cache cannot store one.
 */
abstract class UnpackZipsTask @Inject constructor(
    private val archiveOperations: ArchiveOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archives: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun unpack() {
        fileSystemOperations.sync {
            archives.forEach { from(archiveOperations.zipTree(it)) }
            into(outputDir)
        }
    }
}
