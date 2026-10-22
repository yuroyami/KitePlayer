@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetterProvider
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** The same shape as [MediaIoProviderRegistry]: immutable snapshots, sorted by identifier. */
internal class SubtitleTypesetterRegistry {
    private val lock = SynchronizedObject()
    private var providers = emptyMap<String, SubtitleTypesetterProvider>()

    fun register(provider: SubtitleTypesetterProvider) {
        val id = provider.id
        require(id.isNotBlank()) { "a subtitle typesetter provider needs a stable nonblank identifier" }
        synchronized(lock) {
            val existing = providers[id]
            require(existing == null || existing === provider) {
                "multiple subtitle typesetter providers registered identifier $id"
            }
            if (existing == null) providers = providers + (id to provider)
        }
    }

    fun ids(): List<String> = synchronized(lock) { providers.keys.sorted() }

    fun first(): SubtitleTypesetterProvider? = synchronized(lock) {
        providers.entries.minByOrNull { it.key }?.value
    }

    fun clear() {
        synchronized(lock) { providers = emptyMap() }
    }
}

/** JVM/Android use service metadata. Other targets register through eager module hooks. */
internal expect fun platformSubtitleTypesetterProviders(): List<SubtitleTypesetterProvider>
