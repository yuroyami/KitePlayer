package io.github.yuroyami.kiteplayer.libass

import java.io.File
import java.lang.ref.SoftReference

/**
 * Scans the directories with java.io, reading only the files [pickFontFiles] chose.
 *
 * The engine makes a new typesetter for every ASS track change and every item, and each one asks
 * for the system fonts, up to [KiteLibass.systemFontBudgetBytes] (24 MB by default, a Noto CJK
 * collection alone is about 19 MB). The scan runs each time, because listing the folders is cheap,
 * but when it picks the same files with the same sizes the bytes read last time are handed over
 * again rather than read from disk (#883). They are held softly, so a process short of memory
 * drops them and the next typesetter reads them afresh. libass copies what it is given, so the
 * arrays are never written after they are read.
 */
internal actual fun readFontFiles(directories: List<String>, budgetBytes: Long): List<Pair<String, ByteArray>> {
    val candidates = directories.map(::File).filter { it.isDirectory }.flatMap { directory ->
        directory.walkTopDown().maxDepth(3).filter { it.isFile }
            .map { FontFileCandidate(it.name, it.length(), it.absolutePath) }
            .toList()
    }
    val chosen = pickFontFiles(candidates, budgetBytes)
    return SystemFontBytes.read(chosen.map { FontFileKey(it.path, it.sizeBytes) }) {
        chosen.mapNotNull { candidate ->
            runCatching { candidate.name to File(candidate.path).readBytes() }.getOrNull()
        }
    }
}

/** A chosen font file as the scan saw it. A change of size, or another file, reads again. */
internal data class FontFileKey(val path: String, val sizeBytes: Long)

/** The bytes of the last font scan, by the files it chose. */
internal object SystemFontBytes {
    private var chosen: List<FontFileKey>? = null
    private var bytes: SoftReference<List<Pair<String, ByteArray>>>? = null

    /** How many times [read] went to disk. Tests only. */
    @Volatile
    internal var reads: Int = 0
        private set

    @Synchronized
    fun read(files: List<FontFileKey>, load: () -> List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> {
        if (files == chosen) bytes?.get()?.let { return it }
        val fresh = load()
        reads++
        chosen = files
        bytes = SoftReference(fresh)
        return fresh
    }

    /** Forgets the cached bytes. Tests only. */
    @Synchronized
    internal fun forget() {
        chosen = null
        bytes = null
    }
}
