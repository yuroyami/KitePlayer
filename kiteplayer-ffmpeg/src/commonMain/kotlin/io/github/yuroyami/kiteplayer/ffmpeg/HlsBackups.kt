package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Playlists
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException

/**
 * The backup variants of an HLS stream, tried when an address fails (#440), as RFC 8216 asks of a
 * client given redundant variants. Each address under a playlist that has backups opens at the
 * location the stream is on, and, when that fails, at each backup in the master's order. The one
 * that answers is where every later address under that playlist goes first, its reloads included,
 * so a server that went down costs one failed request. The switch is logged once.
 *
 * An address is moved by its path below its playlist: everything after the playlist's last `/` is
 * kept, so a segment named relative to the playlist keeps its name and its query, and the playlist
 * itself moves to its backup's. A backup's playlist reaches FFmpeg from the backup's address, which
 * it then resolves the segments against, as after a redirect, so a backup that names its segments
 * otherwise plays too. An address under no such playlist opens as it is.
 */
internal class HlsFailover(backups: List<HlsBackup>, base: String) {

    /** Each playlist with backups, resolved, the master's own first, then its backups. */
    private val sets: List<List<String>> = backups
        .map { backup -> (listOf(backup.primary) + backup.alternatives).map { Playlists.resolve(base, it) }.distinct() }
        .filter { it.size > 1 }

    /** For each set, the location the stream is on. */
    private val current = IntArray(sets.size)
    private val lock = SynchronizedObject()

    /** True when nothing has a backup, so every address opens as it is. */
    val isEmpty: Boolean get() = sets.isEmpty()

    /**
     * Opens [address] through [open] at the location its playlist is on, and at each other location
     * while one fails. Null when the reader refuses the address itself, as it would have without a
     * backup. Throws the first failure when every location fails.
     */
    suspend fun open(address: String, open: suspend (String) -> MediaIo?): MediaIo? {
        val (set, from) = locate(address) ?: return open(address)
        val locations = sets[set]
        val start = synchronized(lock) { current[set] }
        var failure: Exception? = null
        for (step in locations.indices) {
            val at = (start + step) % locations.size
            val target = moved(address, locations[from], locations[at]) ?: continue
            val opened = try {
                open(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                failure = failure ?: failed
                continue
            }
            if (opened == null) {
                if (target == address) return null
                continue
            }
            if (at != start) settle(set, start, at, failure)
            return opened
        }
        throw failure ?: IllegalStateException("no location of $address could be opened")
    }

    /**
     * The set and the location in it that [address] is, or else is under, the most specific
     * playlist first: a rendition's own playlist wins over the variant beside it.
     */
    private fun locate(address: String): Pair<Int, Int>? {
        for ((set, locations) in sets.withIndex()) {
            val index = locations.indexOf(address)
            if (index >= 0) return set to index
        }
        var best: Pair<Int, Int>? = null
        var bestLength = -1
        for ((set, locations) in sets.withIndex()) {
            for ((index, location) in locations.withIndex()) {
                if (moved(address, location, location) == null) continue
                val length = directory(location).length
                if (length > bestLength) {
                    best = set to index
                    bestLength = length
                }
            }
        }
        return best
    }

    private fun settle(set: Int, from: Int, to: Int, failure: Exception?) {
        val moved = synchronized(lock) {
            if (current[set] != from) {
                false
            } else {
                current[set] = to
                true
            }
        }
        if (moved) {
            KiteLog.log(
                "KiteHls",
                "${MediaItem(sets[set][from]).label} failed (${failure?.message}); its stream now comes from the backup " +
                    MediaItem(sets[set][to]).label,
            )
        }
    }

    internal companion object {
        /**
         * [address] moved from the playlist at [from] to the one at [to]: [to] itself for [from]
         * itself, and otherwise what follows the last `/` of [from]'s path put after the last `/` of
         * [to]'s. Null when [address] is not under [from].
         */
        fun moved(address: String, from: String, to: String): String? {
            if (address == from) return to
            val source = directory(from)
            if (!address.startsWith(source)) return null
            return directory(to) + address.substring(source.length)
        }

        /** [address] up to and including the last `/` of its path, with its query and fragment gone. */
        fun directory(address: String): String {
            val bare = address.substringBefore('#').substringBefore('?')
            val pathStart = bare.indexOf("://").let { if (it < 0) 0 else bare.indexOf('/', it + 3) }
            if (pathStart < 0) return "$bare/"
            return bare.substring(0, bare.lastIndexOf('/') + 1)
        }
    }
}
