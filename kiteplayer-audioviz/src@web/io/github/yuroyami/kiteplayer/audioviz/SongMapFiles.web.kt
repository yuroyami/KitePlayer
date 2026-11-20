package io.github.yuroyami.kiteplayer.audioviz

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** A browser page has no file system, so a directory store keeps nothing there. */
internal actual object SongMapFiles {
    actual fun read(path: String): ByteArray? = null

    actual fun write(path: String, bytes: ByteArray): Boolean = false

    actual fun delete(path: String) {}

    actual fun oldestFirst(directory: String, suffix: String): List<String> = emptyList()
}

/** A page has one thread for Kotlin, so the scan runs one song at a time. */
internal actual fun scanWorkerCount(): Int = 1

internal actual val songScanDispatcher: CoroutineDispatcher get() = Dispatchers.Default
