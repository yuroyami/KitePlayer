/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** The file types that the samples offer as songs. */
val SONG_TYPES: List<String> = listOf("mp3", "m4a", "flac", "ogg", "wav", "aac")

/**
 * The sample songs: the one that `kiteplayer.sample.song` names, else every song in
 * `kiteplayer-sample-shared/media`. The name comes from `KITEPLAYER_SAMPLE_SONG` or from the
 * root `local.properties`.
 */
fun sampleSongs(repoRoot: File): List<File> {
    val named = System.getenv("KITEPLAYER_SAMPLE_SONG")?.takeIf { it.isNotBlank() }
        ?: repoRoot.resolve("local.properties").takeIf { it.isFile }?.let { file ->
            Properties().apply { file.reader().use { load(it) } }.getProperty("kiteplayer.sample.song")
        }?.takeIf { it.isNotBlank() }
    if (named != null) return listOf(File(named).let { if (it.isAbsolute) it else repoRoot.resolve(named) })
    return repoRoot.resolve("kiteplayer-sample-shared/media").listFiles().orEmpty()
        .filter { file -> file.isFile && SONG_TYPES.any { file.name.endsWith(".$it", ignoreCase = true) } }
        .sortedBy { it.name }
}

/**
 * Copies [sources] into [outputDir] and deletes every other file there, so a dropped file never
 * ships beside the others. Each copy lands in a sibling `.tmp` file first and is then renamed, so an
 * interrupted build never leaves a partial file. A file with the same size and date is not copied
 * again. Returns the SHA-256 of each copy, for the log.
 */
fun syncMediaFiles(sources: List<File>, outputDir: File, producer: String): Map<String, String> {
    val clashes = sources.groupBy { it.name }.filterValues { it.size > 1 }
    if (clashes.isNotEmpty()) {
        throw BuildFailure("Two media files share a file name, so only one could ship: " +
            clashes.values.flatten().joinToString { it.absolutePath })
    }
    sources.forEach { source ->
        if (!source.isFile || source.length() == 0L) {
            throw BuildFailure("Media file missing or empty at ${source.absolutePath}. $producer")
        }
    }
    outputDir.mkdirs()
    val wanted = sources.map { it.name }.toSet()
    outputDir.listFiles().orEmpty().filter { it.isFile && it.name !in wanted }.forEach { it.delete() }
    return sources.associate { source ->
        val destination = outputDir.resolve(source.name)
        val fresh = destination.isFile && destination.length() == source.length() &&
            destination.lastModified() == source.lastModified()
        if (!fresh) copyThroughTemporary(source, destination)
        source.name to MessageDigest.getInstance("SHA-256").digest(destination.readBytes()).joinToString("") { "%02x".format(it) }
    }
}

private fun copyThroughTemporary(source: File, destination: File) {
    val tmp = destination.resolveSibling("${destination.name}.tmp")
    try {
        source.copyTo(tmp, overwrite = true)
        if (tmp.length() != source.length()) {
            throw BuildFailure("Partial copy: ${tmp.length()} of ${source.length()} bytes reached ${tmp.absolutePath}")
        }
        tmp.setLastModified(source.lastModified())
        if (!tmp.renameTo(destination)) {
            if (destination.isFile) destination.delete()
            if (!tmp.renameTo(destination)) throw BuildFailure("Could not move the copy into ${destination.absolutePath}")
        }
    } finally {
        tmp.delete()
    }
}
