@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.network

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fclose
import platform.posix.flock
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove

internal actual fun platformStoreFiles(): StoreFiles? = PosixStoreFiles

/** The store's files through POSIX. */
private object PosixStoreFiles : StoreFiles {
    override fun lock(path: String): AutoCloseable? {
        val descriptor = open(path, O_CREAT or O_RDWR, FILE_MODE)
        if (descriptor < 0) throw failure("open", path)
        // The lock belongs to this descriptor, so a second store of this process is refused too.
        if (flock(descriptor, LOCK_EX or LOCK_NB) != 0) {
            close(descriptor)
            return null
        }
        return AutoCloseable { close(descriptor) }
    }

    override fun makeDirectory(path: String) {
        var end = path.indexOf('/', startIndex = 1)
        while (end > 0) {
            mkdir(path.substring(0, end), DIRECTORY_MODE)
            end = path.indexOf('/', end + 1)
        }
        if (mkdir(path, DIRECTORY_MODE) != 0 && errno != EEXIST) throw failure("mkdir", path)
    }

    override fun list(path: String): List<String> {
        val handle = opendir(path) ?: return emptyList()
        val names = ArrayList<String>()
        try {
            while (true) {
                val entry = readdir(handle) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") names += name
            }
        } finally {
            closedir(handle)
        }
        return names
    }

    override fun readAll(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        try {
            if (fseeko(file, 0, SEEK_END) != 0) throw failure("seek", path)
            val size = ftello(file)
            if (size < 0 || size > Int.MAX_VALUE) throw failure("size", path)
            if (fseeko(file, 0, SEEK_SET) != 0) throw failure("seek", path)
            val bytes = ByteArray(size.toInt())
            if (bytes.isEmpty()) return bytes
            val read = bytes.usePinned { pinned -> fread(pinned.addressOf(0), 1u, bytes.size.toULong(), file) }
            if (read.toInt() != bytes.size) throw failure("read", path)
            return bytes
        } finally {
            fclose(file)
        }
    }

    override fun read(path: String, position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        val file = fopen(path, "rb") ?: throw failure("open", path)
        try {
            if (fseeko(file, position, SEEK_SET) != 0) throw failure("seek", path)
            val read = into.usePinned { pinned -> fread(pinned.addressOf(offset), 1u, length.toULong(), file) }
            return if (read == 0uL) -1 else read.toInt()
        } finally {
            fclose(file)
        }
    }

    override fun create(path: String): StoreFileSink {
        val file = fopen(path, "wb") ?: throw failure("create", path)
        return object : StoreFileSink {
            private var failed = false

            override fun write(from: ByteArray, offset: Int, length: Int) {
                if (length <= 0) return
                val written = from.usePinned { pinned -> fwrite(pinned.addressOf(offset), 1u, length.toULong(), file) }
                if (written.toInt() != length) {
                    failed = true
                    throw failure("write", path)
                }
            }

            // The close flushes the last buffer, so a full disk shows here.
            override fun close() {
                if (fclose(file) != 0 || failed) throw failure("close", path)
            }
        }
    }

    override fun rename(from: String, to: String) {
        if (platform.posix.rename(from, to) != 0) throw failure("rename", from)
    }

    override fun delete(path: String) {
        if (remove(path) != 0 && errno != ENOENT) throw failure("delete", path)
    }

    private fun failure(what: String, path: String): Exception =
        IllegalStateException("$what failed with errno $errno on ${path.substringAfterLast('/')}")

    /** 0700 and 0600: the stored media is this application's own. */
    private val DIRECTORY_MODE: UShort = 448u
    private const val FILE_MODE = 384
}
