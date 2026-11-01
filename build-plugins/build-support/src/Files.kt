/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File

/**
 * Makes [target] hold exactly the files of [source]. A file whose bytes did not change keeps its
 * time stamp, so the toolchain does not see a changed input and does not repeat the next step.
 */
fun syncDirectory(source: File, target: File) {
    target.mkdirs()
    val wanted = source.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(source).invariantSeparatorsPath }
    target.walkBottomUp().forEach { file ->
        val relative = file.relativeTo(target).invariantSeparatorsPath
        if (file.isFile && relative !in wanted) file.delete()
        if (file.isDirectory && file != target && file.listFiles().isNullOrEmpty()) file.delete()
    }
    wanted.forEach { (relative, file) ->
        val destination = target.resolve(relative)
        if (!destination.isFile || destination.length() != file.length() || !destination.readBytes().contentEquals(file.readBytes())) {
            destination.parentFile.mkdirs()
            file.copyTo(destination, overwrite = true)
        }
    }
}

/** Writes [text] to [file] only when it differs, so an unchanged file keeps its time stamp. */
fun writeIfChanged(file: File, text: String) {
    if (file.isFile && file.readText() == text) return
    file.parentFile.mkdirs()
    file.writeText(text)
}

/**
 * The text of a cinterop definition [template] with [extraProperties] added before the `---` line
 * that starts the C code, or at the end when there is none.
 */
/**
 * [path] as a cinterop def writes it, with forward slashes. A def is read as a properties file, where
 * a backslash escapes the next character, so a Windows path written as it is lost its separators and
 * cinterop found no header in the folder it named.
 */
fun defPath(path: java.io.File): String = path.absolutePath.replace('\\', '/')

fun defWithProperties(template: String, extraProperties: List<String>): String {
    val lines = template.trimEnd().lines()
    val separator = lines.indexOfFirst { it.trim() == "---" }
    val head = if (separator < 0) lines else lines.subList(0, separator)
    val tail = if (separator < 0) emptyList() else lines.subList(separator, lines.size)
    return (head + "" + extraProperties + (if (tail.isEmpty()) emptyList() else listOf("") + tail)).joinToString("\n") + "\n"
}
