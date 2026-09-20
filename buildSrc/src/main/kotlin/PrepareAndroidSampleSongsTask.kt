package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * Copies the sample songs into the sample's generated assets, so the APK carries every one of them.
 *
 * The same rules as the single file task: each copy lands in a sibling `.tmp` and is renamed into
 * place, so an interrupted build never leaves a partial file an APK could bundle, and any other
 * file in the output directory is deleted, so a song dropped from the set never ships beside the
 * others. Two songs with the same file name are refused, because only one of them could survive.
 * The task never invokes ffmpeg and never touches a network. Each SHA-256 is logged so a smoke
 * result can be tied to the exact files it played.
 */
abstract class PrepareAndroidSampleSongsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceSongs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val sources = sourceSongs.files.sortedBy { it.name }
        val clashes = sources.groupBy { it.name }.filterValues { it.size > 1 }
        if (clashes.isNotEmpty()) {
            throw GradleException(
                "two sample songs share a file name, so only one could ship: " +
                    clashes.values.flatten().joinToString { it.absolutePath },
            )
        }
        for (source in sources) {
            if (!source.isFile || source.length() == 0L) {
                throw GradleException(
                    "sample song missing or empty at ${source.absolutePath}; the songs live in " +
                        "kiteplayer-sample-shared/media, and kiteplayer.sample.song names one instead",
                )
            }
        }
        val outDir = outputDirectory.get().asFile.also(File::mkdirs)
        val wanted = sources.map { it.name }.toSet()
        outDir.listFiles()?.filter { it.name !in wanted }?.forEach(File::delete)
        for (source in sources) {
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
                        throw GradleException("could not move the copied song into ${destination.absolutePath}")
                    }
                }
            } finally {
                tmp.delete()
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(destination.readBytes())
                .joinToString("") { "%02x".format(it) }
            logger.lifecycle("[KiteFFmpeg sample song] ${destination.name} sha256=$digest")
        }
    }
}
