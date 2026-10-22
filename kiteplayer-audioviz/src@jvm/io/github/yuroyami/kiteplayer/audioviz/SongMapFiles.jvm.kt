package io.github.yuroyami.kiteplayer.audioviz

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Song map files on the desktop. A write lands through a temporary file, so a crash leaves no half map. */
internal actual object SongMapFiles {
    actual fun read(path: String): ByteArray? = runCatching {
        File(path).takeIf { it.isFile }?.readBytes()
    }.getOrNull()

    actual fun write(path: String, bytes: ByteArray): Boolean {
        val file = File(path)
        val partial = File("$path.part")
        return try {
            file.parentFile?.mkdirs()
            partial.writeBytes(bytes)
            // One move that replaces the old map, so a crash keeps either the old map or the new one.
            try {
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: Exception) {
            // A failed write must not leave its temporary file behind.
            partial.delete()
            false
        }
    }

    actual fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    actual fun oldestFirst(directory: String, suffix: String): List<String> = runCatching {
        File(directory).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(suffix) }
            .sortedBy { it.lastModified() }
            .map { it.absolutePath }
    }.getOrDefault(emptyList())
}

/** Half the cores, at least one, so a scan never takes the machine away from playback. */
internal actual fun scanWorkerCount(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
