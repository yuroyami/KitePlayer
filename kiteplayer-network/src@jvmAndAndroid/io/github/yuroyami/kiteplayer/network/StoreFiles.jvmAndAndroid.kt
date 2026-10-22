package io.github.yuroyami.kiteplayer.network

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual fun platformStoreFiles(): StoreFiles? = JavaStoreFiles

/** The store's files through `java.io` and `java.nio`. */
private object JavaStoreFiles : StoreFiles {
    override fun lock(path: String): AutoCloseable? {
        val file = RandomAccessFile(path, "rw")
        val lock = try {
            file.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            // Another store of this process holds it.
            null
        }
        if (lock == null) {
            file.close()
            return null
        }
        // Closing the file releases the lock.
        return AutoCloseable { file.close() }
    }

    override fun makeDirectory(path: String) {
        Files.createDirectories(File(path).toPath())
    }

    override fun list(path: String): List<String> = File(path).list()?.toList().orEmpty()

    override fun readAll(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    override fun read(path: String, position: Long, into: ByteArray, offset: Int, length: Int): Int =
        RandomAccessFile(path, "r").use { file ->
            file.seek(position)
            file.read(into, offset, length)
        }

    override fun create(path: String): StoreFileSink {
        val out = FileOutputStream(path)
        return object : StoreFileSink {
            override fun write(from: ByteArray, offset: Int, length: Int) = out.write(from, offset, length)

            override fun close() = out.close()
        }
    }

    override fun rename(from: String, to: String) {
        Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete(path: String) {
        Files.deleteIfExists(File(path).toPath())
    }
}
