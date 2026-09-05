@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaIo
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

    suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? {
        val snapshot = synchronized(lock) { entries.entries.sortedBy { it.key }.map { it.value } }
        for (entry in snapshot) {
            entry.resolver.resolve(uri, headers)?.let { return it }
        }
        return null
    }
}

/** JVM/Android use service metadata. Other targets register through eager module hooks. */
internal expect fun platformMediaIoProviders(): List<MediaIoResolverProvider>
