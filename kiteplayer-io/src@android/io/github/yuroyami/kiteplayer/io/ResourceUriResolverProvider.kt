@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.io

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
import kotlin.concurrent.Volatile

/**
 * Plays a Compose Multiplatform resource's `android_asset` address as it is (#457), through
 * [MediaIo.ofResourceUri][ofResourceUri] with the application context [KiteIoContextProvider] keeps, for every
 * item with no reader of its own. Found by ServiceLoader, so a public class in bytecode with a
 * constructor that takes nothing. Without the context, an app that removed the provider from its
 * manifest, the address plays as it is and fails as before.
 */
internal class ResourceUriResolverProvider : MediaIoResolverProvider {
    override val id: String = RESOURCE_RESOLVER_ID

    override fun create(): MediaIoResolver = object : MediaIoResolver {
        override suspend fun resolve(uri: String): MediaIo? {
            val context = KiteIoContextProvider.application ?: return null
            return MediaIo.ofResourceUri(context, uri)?.open()
        }
    }
}

/**
 * Keeps the application context from the moment the app starts (#457), as Compose Multiplatform's
 * own resources do, so a bundled asset's address plays with no context passed by hand. It serves
 * no data. An app that does not want it removes it in its manifest with `tools:node="remove"`, and
 * then passes a context to [MediaIo.ofResourceUri][ofResourceUri] itself.
 */
internal class KiteIoContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        application = context?.applicationContext
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    internal companion object {
        @Volatile
        var application: Context? = null
    }
}
