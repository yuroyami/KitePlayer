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
 * Copies one media file, the conformance clip or the sample song, into the sample's generated assets.
 *
 * The copy is transactional: bytes land in a sibling `.tmp` and are renamed into place, so an
 * interrupted build never leaves a partial file an APK could bundle. Other files in the output
 * directory are deleted first, so a song swapped for another never ships beside the old one. The
 * task never invokes ffmpeg and never touches a network. The SHA-256 of the copied bytes is logged
 * so a smoke result can be tied to the exact file it played.
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
                "sample media missing or empty at ${source.absolutePath}; the test clip comes from " +
                    "scripts/testmedia.sh, and the song from kiteplayer.sample.song",
            )
        }
        val outDir = outputDirectory.get().asFile.also(File::mkdirs)
        outDir.listFiles()?.filter { it.name != source.name }?.forEach(File::delete)
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
