package io.github.yuroyami.kiteplayer.libass

import java.io.File

/** Scans the directories with java.io, reading only the files [pickFontFiles] chose. */
internal actual fun readFontFiles(directories: List<String>, budgetBytes: Long): List<Pair<String, ByteArray>> {
    val candidates = directories.map(::File).filter { it.isDirectory }.flatMap { directory ->
        directory.walkTopDown().maxDepth(3).filter { it.isFile }
            .map { FontFileCandidate(it.name, it.length(), it.absolutePath) }
            .toList()
    }
    return pickFontFiles(candidates, budgetBytes).mapNotNull { candidate ->
        runCatching { candidate.name to File(candidate.path).readBytes() }.getOrNull()
    }
}
