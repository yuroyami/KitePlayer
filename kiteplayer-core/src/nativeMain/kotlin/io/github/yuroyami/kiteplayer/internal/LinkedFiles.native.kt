@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.internal

import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir

internal actual fun namesInFolder(folder: String): List<String>? {
    val directory = opendir(folder) ?: return null
    try {
        val names = ArrayList<String>()
        while (true) {
            val entry = readdir(directory) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") names += name
        }
        return names
    } finally {
        closedir(directory)
    }
}
