@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider

/**
 * Plays a Compose Multiplatform resource's `jar:` address as it is (#457), through
 * [MediaIo.ofResourceUri][ofResourceUri], for every item with no reader of its own. Found by ServiceLoader, so a
 * public class in bytecode with a constructor that takes nothing.
 */
internal class ResourceUriResolverProvider : MediaIoResolverProvider {
    override val id: String = RESOURCE_RESOLVER_ID

    override fun create(): MediaIoResolver = object : MediaIoResolver {
        override suspend fun resolve(uri: String): MediaIo? = MediaIo.ofResourceUri(uri)?.open()
    }
}
