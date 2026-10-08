@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.NetworkStatus
import io.github.yuroyami.kiteplayer.SegmentStore
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

    /**
     * True for a provider whose resolver opens local paths, a bare path or a `file:` address (#430).
     * Automatic resolution never asks such a provider, so a local file stays on the backend's own
     * reader. The engine asks it only for an item that needs a Kotlin reader of a local file, which
     * is one still being written ([io.github.yuroyami.kiteplayer.MediaItem.growth]). False by default.
     */
    public val servesLocalFiles: Boolean get() = false

    /**
     * Whether the device has a network, for [io.github.yuroyami.kiteplayer.NetworkRecovery] (#461),
     * or null when this provider cannot tell, which is the default. The player watches the first
     * one a provider gives, only while it waits for the network. The network module gives the
     * platform's.
     */
    public fun networkStatus(): NetworkStatus? = null

    /**
     * A resolver whose readers keep segments in [store], for a player whose
     * [io.github.yuroyami.kiteplayer.NetworkConfig.segmentStore] is set (#547), or null, the
     * default, when this provider keeps none. Called at each such open, so it must be cheap.
     */
    public fun createWith(store: SegmentStore): MediaIoResolver? = null
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

    internal suspend fun resolve(uri: String, headers: Map<String, String>, store: SegmentStore? = null): MediaIo? {
        discovered
        return registry.resolve(uri, headers, store)
    }

    internal suspend fun resolveLocalFile(path: String): MediaIo? {
        discovered
        return registry.resolveLocalFile(path)
    }

    /** The network status of the first provider, by identifier, that gives one (#461). */
    internal fun networkStatus(): NetworkStatus? {
        discovered
        return registry.networkStatus()
    }
}
