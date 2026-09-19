@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.KitePlayer
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference

/** Reference-counted analysis ownership. Keys use identity, just like attached player taps. */
internal class AudioVizSessions<K : Any>(
    private val attach: (K, AudioVizFeed) -> Unit,
    private val detach: (K, AudioVizFeed) -> Unit,
    private val create: () -> AudioVizFeed = { AudioVizFeed() },
) {
    private class Entry<K>(val key: K, val feed: AudioVizFeed) {
        val references = AtomicInt(1)

        fun retain(): Boolean {
            while (true) {
                val count = references.load()
                if (count == 0) return false
                if (references.compareAndSet(count, count + 1)) return true
            }
        }
    }

    class Lease(val feed: AudioVizFeed, private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private val active = AtomicReference<List<Entry<K>>>(emptyList())

    fun acquire(key: K): Lease {
        while (true) {
            val entries = active.load()
            val found = entries.firstOrNull { it.key === key }
            if (found != null) {
                if (found.retain()) return lease(found)
                remove(found)
                continue
            }
            val fresh = Entry(key, create())
            if (!active.compareAndSet(entries, entries + fresh)) {
                fresh.feed.close()
                continue
            }
            try {
                attach(key, fresh.feed)
            } catch (failure: Throwable) {
                remove(fresh)
                fresh.feed.close()
                throw failure
            }
            return lease(fresh)
        }
    }

    private fun lease(entry: Entry<K>): Lease = Lease(entry.feed) {
        if (entry.references.fetchAndAdd(-1) == 1) {
            remove(entry)
            try {
                detach(entry.key, entry.feed)
            } finally {
                entry.feed.close()
            }
        }
    }

    private fun remove(entry: Entry<K>) {
        while (true) {
            val entries = active.load()
            if (entries.none { it === entry }) return
            if (active.compareAndSet(entries, entries.filterNot { it === entry })) return
        }
    }
}

internal val playerAudioVizSessions = AudioVizSessions<KitePlayer>(
    attach = { player, feed -> player.attachAudioTap(feed) },
    detach = { player, feed -> player.detachAudioTap(feed) },
)
