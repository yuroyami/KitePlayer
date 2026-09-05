@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider

/** Public JVM bytecode and a no-argument constructor are required by ServiceLoader. */
internal class KtorMediaIoResolverProvider : MediaIoResolverProvider {
    override val id: String = "io.github.yuroyami.kiteplayer.network.ktor"

    override fun create(): MediaIoResolver = object : MediaIoResolver {
        override suspend fun resolve(uri: String): MediaIo? = resolve(uri, emptyMap())

        override suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? {
            if (!uri.isHttpUri()) return null
            // Each session owns its reader and private client, including failed-open cleanup.
            return KtorMediaIo.open(uri, headers = headers)
        }
    }
}

internal fun String.isHttpUri(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
