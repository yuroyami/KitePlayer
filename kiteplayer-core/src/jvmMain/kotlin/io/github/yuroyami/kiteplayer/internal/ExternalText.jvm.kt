package io.github.yuroyami.kiteplayer.internal

internal actual fun readExternalFile(path: String, limit: Int): ExternalFile {
    val file = java.io.File(path)
    if (!file.isFile || !file.canRead()) return ExternalFile.Unreadable
    // The length first, then a bounded read, because the file can grow between the two.
    if (file.length() > limit) return ExternalFile.TooLarge
    return runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(minOf(file.length(), limit.toLong()).toInt() + 1)
            var length = 0
            while (length < buffer.size) {
                val read = input.read(buffer, length, buffer.size - length)
                if (read < 0) break
                length += read
            }
            if (length > limit) ExternalFile.TooLarge else ExternalFile.Read(buffer.copyOf(length))
        }
    }.getOrElse { ExternalFile.Unreadable }
}

internal actual val playerPlatformName: String = "jvm"
