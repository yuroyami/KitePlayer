package io.github.yuroyami.kiteplayer.io

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.net.Uri
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * Plays what a content provider serves, such as a file from the system picker. Each open asks the
 * provider for a new descriptor and reads it by position, so a reopen never moves a descriptor
 * that someone else holds. A provider that answers with a pipe plays forward only.
 */
public fun MediaIo.Companion.ofUri(resolver: ContentResolver, uri: Uri): MediaIoFactory = MediaIoFactory {
    val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: throw MediaIoException("The provider gave no file for $uri")
    descriptor.toMediaIo()
}

/**
 * Plays a file from the app's `assets` directory. The asset must be stored uncompressed, because a
 * compressed asset has no descriptor and fails at open. The Android build stores common media
 * extensions, such as mp4 and mkv, uncompressed already.
 */
public fun MediaIo.Companion.ofAsset(assets: AssetManager, name: String): MediaIoFactory = MediaIoFactory {
    assets.openFd(name).toMediaIo()
}

/** Reads the window of this descriptor by position. Closing the reader closes the descriptor. */
internal fun AssetFileDescriptor.toMediaIo(): MediaIo {
    var channel: FileChannel? = null
    try {
        // A pipe or a socket has no size and cannot be read by position. The stream then owns the
        // descriptor and closes it.
        if (parcelFileDescriptor.statSize < 0) return InputStreamMediaIo(createInputStream())
        // A plain stream over the descriptor, not createInputStream(): the channel of that stream
        // changed between Android versions, and this one reads at absolute positions on all of
        // them. It never closes the descriptor, so this AssetFileDescriptor stays the one owner.
        val opened = FileInputStream(fileDescriptor).channel.also { channel = it }
        val length = if (declaredLength >= 0) declaredLength else opened.size() - startOffset
        val owner = AutoCloseable {
            opened.close()
            close()
        }
        return FileChannelMediaIo(opened, owner, start = startOffset, length = length)
    } catch (failure: Throwable) {
        channel?.close()
        close()
        throw failure
    }
}
