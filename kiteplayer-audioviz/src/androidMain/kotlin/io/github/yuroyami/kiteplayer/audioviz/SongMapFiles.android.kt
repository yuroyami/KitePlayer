package io.github.yuroyami.kiteplayer.audioviz

import java.io.File

/** Song map files on Android. A write lands through a temporary file, so a crash leaves no half map. */
internal actual object SongMapFiles {
    actual fun read(path: String): ByteArray? = runCatching {
        File(path).takeIf { it.isFile }?.readBytes()
    }.getOrNull()

    actual fun write(path: String, bytes: ByteArray): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        val partial = File("$path.part")
        partial.writeBytes(bytes)
        file.delete()
        partial.renameTo(file) || run { partial.delete(); false }
    }.getOrDefault(false)

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
