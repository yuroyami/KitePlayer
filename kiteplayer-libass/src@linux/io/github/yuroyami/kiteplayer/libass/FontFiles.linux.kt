@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.libass

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.DT_DIR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

/** The Linux chain carries no fontconfig, so the usual font directories are read through POSIX. */
internal actual fun readFontFiles(directories: List<String>, budgetBytes: Long): List<Pair<String, ByteArray>> {
    val candidates = ArrayList<FontFileCandidate>()
    directories.forEach { walk(it, depth = 0, into = candidates) }
    return pickFontFiles(candidates, budgetBytes).mapNotNull { candidate ->
        readWhole(candidate.path)?.let { candidate.name to it }
    }
}

private fun walk(path: String, depth: Int, into: MutableList<FontFileCandidate>) {
    if (depth > 3) return
    val dir = opendir(path) ?: return
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            val child = "$path/$name"
            if (entry.pointed.d_type.toInt() == DT_DIR) {
                walk(child, depth + 1, into)
            } else {
                memScoped {
                    val info = alloc<stat>()
                    if (stat(child, info.ptr) == 0) into += FontFileCandidate(name, info.st_size.toLong(), child)
                }
            }
        }
    } finally {
        closedir(dir)
    }
}

private fun readWhole(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size <= 0 || size > Int.MAX_VALUE) return null
        fseek(file, 0, SEEK_SET)
        val bytes = ByteArray(size.toInt())
        val read = bytes.usePinned { pinned -> fread(pinned.addressOf(0), 1u, size.toULong(), file) }
        return if (read.toLong() == size) bytes else null
    } finally {
        fclose(file)
    }
}

internal actual fun defaultFontDirectories(): List<String> {
    val home = getenv("HOME")?.toKString().orEmpty()
    return listOf("/usr/share/fonts", "/usr/local/share/fonts", "$home/.fonts", "$home/.local/share/fonts")
}

internal actual val platformNeedsSystemFontFiles: Boolean = true
