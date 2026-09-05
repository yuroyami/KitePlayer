@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.internal.MediaIoProviderRegistry
import io.github.yuroyami.kiteplayer.internal.platformMediaIoProviders

/**
 * An optional transport module's lightweight discovery entry. Construction must not perform I/O.
 * A provider creates one process-lived resolver on first automatic resolution. That resolver must
 * support concurrent opens, keep player state out of the registry, and give each returned reader
 * ownership of its resources. An unsupported URI returns null so the next provider may handle it.
 */
@KitePlayerLowLevelApi
public interface MediaIoResolverProvider {
    /** Stable unique identifier. Providers are tried in ascending identifier order. */
    public val id: String

    /** Creates the resolver lazily. Do not open clients or other closeable resources here. */
    public fun create(): MediaIoResolver
}

/**
 * Registration seam for optional modules on targets without classpath service discovery.
 * Applications normally only add the module dependency. Registration is thread-safe and creates
 * no clients. Registering distinct providers under the same identifier refuses with an error.
 */
@KitePlayerLowLevelApi
public object MediaIoProviders {
    private val registry = MediaIoProviderRegistry()
    private val discovered: Unit by lazy {
        platformMediaIoProviders().forEach(registry::register)
    }

    /** Installs a provider. Re-registering the same instance is harmless. */
    public fun register(provider: MediaIoResolverProvider): Unit = registry.register(provider)

    internal suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? {
        discovered
        return registry.resolve(uri, headers)
    }
}
