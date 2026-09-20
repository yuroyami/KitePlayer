@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink

/** Song map files on iOS, through POSIX. A write lands through a temporary file and a rename. */
internal actual object SongMapFiles {
    actual fun read(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        try {
            if (fseek(file, 0, SEEK_END) != 0) return null
            val size = ftell(file)
            if (size <= 0 || size > Int.MAX_VALUE) return null
            if (fseek(file, 0, SEEK_SET) != 0) return null
            val bytes = ByteArray(size.toInt())
            val read = bytes.usePinned { pinned -> fread(pinned.addressOf(0), 1u, size.toULong(), file) }
            return if (read.toLong() == size) bytes else null
        } finally {
            fclose(file)
        }
    }

    actual fun write(path: String, bytes: ByteArray): Boolean {
        val separator = path.lastIndexOf('/')
        if (separator > 0) mkdir(path.substring(0, separator), DIRECTORY_MODE)
        val partial = "$path.part"
        val file = fopen(partial, "wb") ?: return false
        val written = try {
            if (bytes.isEmpty()) 0uL
            else bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file) }
        } finally {
            fclose(file)
        }
        if (written.toInt() != bytes.size) {
            unlink(partial)
            return false
        }
        if (rename(partial, path) != 0) {
            unlink(partial)
            return false
        }
        return true
    }

    actual fun delete(path: String) {
        unlink(path)
    }

    actual fun oldestFirst(directory: String, suffix: String): List<String> {
        val handle = opendir(directory) ?: return emptyList()
        val found = ArrayList<Pair<Long, String>>()
        try {
            while (true) {
                val entry = readdir(handle) ?: break
                val name = entry.pointed.d_name.toKString()
                if (!name.endsWith(suffix)) continue
                val full = "$directory/$name"
                memScoped {
                    val info = alloc<stat>()
                    if (stat(full, info.ptr) == 0) found += info.st_mtimespec.tv_sec.toLong() to full
                }
            }
        } finally {
            closedir(handle)
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** 0777, cut down by the process umask, which is what an application's own directory wants. */
    private val DIRECTORY_MODE: UShort = 511u
}

/** Half the cores, at least one, so a scan never takes the device away from playback. */
internal actual fun scanWorkerCount(): Int =
    (NSProcessInfo.processInfo.activeProcessorCount.toInt() / 2).coerceAtLeast(1)
