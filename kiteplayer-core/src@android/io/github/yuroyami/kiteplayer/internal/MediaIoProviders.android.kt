@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * The reader providers the services files name. A provider that cannot load is logged and left
 * out: collected with one toList, its ServiceConfigurationError ended the whole collection, so one
 * broken provider turned every automatic reader off, the working ones included (#869).
 */
internal actual fun platformMediaIoProviders(): List<MediaIoResolverProvider> {
    val providers = ServiceLoader.load(MediaIoResolverProvider::class.java, MediaIoResolverProvider::class.java.classLoader).iterator()
    val found = ArrayList<MediaIoResolverProvider>()
    // A provider that fails to load is passed over, but a broken services file can fail every
    // step from then on, so a run of failures ends the walk with what was found.
    var failuresInARow = 0
    while (failuresInARow < MAX_PROVIDER_FAILURES_IN_A_ROW) {
        try {
            if (!providers.hasNext()) break
            found += providers.next()
            failuresInARow = 0
        } catch (failure: ServiceConfigurationError) {
            failuresInARow++
            android.util.Log.w("KitePlayer", "a reader provider could not be loaded and is left out", failure)
        }
    }
    return found
}

private const val MAX_PROVIDER_FAILURES_IN_A_ROW = 16
