package io.github.yuroyami.kiteplayer.network

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
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

    // A channel, not a RandomAccessFile: Windows lets a file opened this way be deleted while it
    // is open, and the store deletes a span another span covers while a handle may hold it (#872).
    override fun openReader(path: String): StoreFileReader {
        val channel = FileChannel.open(File(path).toPath(), StandardOpenOption.READ)
        return object : StoreFileReader {
            override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int =
                channel.read(ByteBuffer.wrap(into, offset, length), position)

            override fun close() = channel.close()
        }
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
