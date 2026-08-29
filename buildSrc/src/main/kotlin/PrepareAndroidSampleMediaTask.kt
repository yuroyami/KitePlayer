package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * Copies the conformance sync clip into the sample's generated assets (S1.c.6 step 3).
 *
 * The copy is transactional: bytes land in a sibling `.tmp` and are renamed into place, so an
 * interrupted build never leaves a partial clip an APK could bundle. The task never invokes
 * ffmpeg and never touches a network; `scripts/testmedia.sh` is the producer and runs first,
 * which is why a missing or empty input is a loud failure naming it. The SHA-256 of the copied
 * bytes is logged so a smoke result can be tied to the exact clip it played.
 */
abstract class PrepareAndroidSampleMediaTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceMedia: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val source = sourceMedia.get().asFile
        if (!source.isFile || source.length() == 0L) {
            throw GradleException(
                "sample media missing or empty at ${source.absolutePath}; run scripts/testmedia.sh first",
            )
        }
        val outDir = outputDirectory.get().asFile.also(File::mkdirs)
        val destination = outDir.resolve(source.name)
        val tmp = outDir.resolve("${source.name}.tmp")
        try {
            source.copyTo(tmp, overwrite = true)
            if (tmp.length() != source.length()) {
                throw GradleException(
                    "partial copy: ${tmp.length()} of ${source.length()} bytes reached ${tmp.absolutePath}",
                )
            }
            if (!tmp.renameTo(destination)) {
                destination.delete()
                if (!tmp.renameTo(destination)) {
                    throw GradleException("could not move the copied clip into ${destination.absolutePath}")
                }
            }
        } finally {
            tmp.delete()
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(destination.readBytes())
            .joinToString("") { "%02x".format(it) }
        logger.lifecycle("[KiteFFmpeg sample media] ${destination.name} sha256=$digest")
    }
}
