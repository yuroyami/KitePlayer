@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.NetworkStatus
import io.github.yuroyami.kiteplayer.SegmentStore
import io.github.yuroyami.kiteplayer.network.dash.Dash
import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider

/** Public JVM bytecode and a no-argument constructor are required by ServiceLoader. */
internal class KtorMediaIoResolverProvider : MediaIoResolverProvider {
    override val id: String = "io.github.yuroyami.kiteplayer.network.ktor"

    override fun create(): MediaIoResolver = resolver(store = null)

    // The player's own store, given at each open, so the provider holds none (#547).
    override fun createWith(store: SegmentStore): MediaIoResolver = resolver(store)

    private fun resolver(store: SegmentStore?): MediaIoResolver = object : MediaIoResolver {
        override suspend fun resolve(uri: String): MediaIo? = resolve(uri, emptyMap())

        override suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? {
            if (!uri.isHttpUri()) return null
            // Each session owns its reader and private client, including failed-open cleanup.
            return playableReader(KtorMediaIo.open(uri, null, headers, HttpReaderPolicy(), redirects = null, reuse = store?.let { SegmentReuse(it) }))
        }
    }

    // The platform's, for the player's wait after the network failed an item (#461).
    override fun networkStatus(): NetworkStatus? = platformNetworkStatus()
}

/**
 * What the automatic transport hands the player for [io]: the reader of a DASH presentation when
 * [io] answers with a manifest (#400), the reader that serves TTML subtitles as WebVTT when it
 * answers with an HLS master that has them (#439), otherwise [io] itself. A response that says it
 * is HLS is never taken for a manifest. [io] is closed when this fails.
 */
internal suspend fun playableReader(io: KtorMediaIo): MediaIo = try {
    if (HlsTtml.declaredHls(io.contentType, io.location)) {
        HlsTtml.readerIfTtml(io, io::peek) ?: io
    } else {
        Dash.readerIfManifest(io) ?: io
    }
} catch (failure: Throwable) {
    io.close()
    throw failure
}

internal fun String.isHttpUri(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
