@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.NetworkStatus
import io.github.yuroyami.kiteplayer.SegmentStore
import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Immutable snapshots keep provider callbacks and suspended opens outside the registration lock. */
internal class MediaIoProviderRegistry {
    private class Entry(val provider: MediaIoResolverProvider) {
        val resolver by lazy { provider.create() }
    }

    private val lock = SynchronizedObject()
    private var entries = emptyMap<String, Entry>()

    fun register(provider: MediaIoResolverProvider) {
        val id = provider.id
        require(id.isNotBlank()) { "a media IO provider needs a stable nonblank identifier" }
        synchronized(lock) {
            val existing = entries[id]
            require(existing == null || existing.provider === provider) {
                "multiple media IO providers registered identifier $id"
            }
            if (existing == null) entries = entries + (id to Entry(provider))
        }
    }

    /** A reader from the first provider that answers [uri], never one that serves local files. */
    suspend fun resolve(uri: String, headers: Map<String, String>, store: SegmentStore? = null): MediaIo? =
        firstAnswer(localFiles = false, uri, headers, store)

    /**
     * A Kotlin reader of the local file [path], from the first provider that serves local files, for
     * an item that needs one (#430).
     */
    suspend fun resolveLocalFile(path: String): MediaIo? = firstAnswer(localFiles = true, path, emptyMap())

    /** The network status of the first provider, by identifier, that gives one (#461). */
    fun networkStatus(): NetworkStatus? {
        val snapshot = synchronized(lock) { entries.entries.sortedBy { it.key }.map { it.value } }
        return snapshot.firstNotNullOfOrNull { it.provider.networkStatus() }
    }

    private suspend fun firstAnswer(
        localFiles: Boolean,
        uri: String,
        headers: Map<String, String>,
        store: SegmentStore? = null,
    ): MediaIo? {
        val snapshot = synchronized(lock) {
            entries.entries.sortedBy { it.key }.map { it.value }.filter { it.provider.servesLocalFiles == localFiles }
        }
        for (entry in snapshot) {
            // A provider that keeps segments answers with the player's store (#547).
            val resolver = store?.let { entry.provider.createWith(it) } ?: entry.resolver
            resolver.resolve(uri, headers)?.let { return it }
        }
        return null
    }
}

/** JVM/Android use service metadata. Other targets register through eager module hooks. */
internal expect fun platformMediaIoProviders(): List<MediaIoResolverProvider>
