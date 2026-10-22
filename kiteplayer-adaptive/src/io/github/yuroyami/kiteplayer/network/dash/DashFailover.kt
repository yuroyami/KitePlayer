package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.KiteLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * The other locations of a manifest's segments, tried when one fails (#440), as dash.js and the
 * DVB-DASH guidelines do. Each open of an address under a base that the manifest names other
 * locations for goes to the location this reader is on, and, when that fails, to the next one in
 * the manifest's order, leaving those on the same `serviceLocation` as one that failed for last.
 * The one that answers is where every later address under that base goes first, so a server that
 * went down costs one failed request and not one per segment. The switch is logged once.
 *
 * Where a reader starts is DVB's choice (ETSI TS 103 285, 10.8.2.1) when the manifest states a
 * priority or a weight: among the locations of the lowest priority, one at random in proportion to
 * its weight, so the players of one service share its networks as the manifest says. A manifest
 * that states neither starts at its first location, as the document orders them.
 *
 * An address is moved from one location to another by its path below the base: everything after
 * the base's last `/` is kept, so a segment named relative to the base keeps its name and its
 * query. An address that is not under any such base, such as one a template names absolutely,
 * opens as it is.
 */
internal class DashFailover(
    sets: List<DashBaseUrls>,
    private val random: Random = Random.Default,
) {
    private val lock = Mutex()

    /** The bases, the most specific first, so a representation's own base wins over its Period's. */
    private var sets: List<DashBaseUrls> = sorted(sets)

    /** For each base, by its primary address, the index of the location this reader is on. */
    private val current = HashMap<String, Int>()

    /** Takes the bases of a manifest fetched again. A base this reader already moved stays where it went. */
    suspend fun update(next: List<DashBaseUrls>) = lock.withLock { sets = sorted(next) }

    /**
     * Opens [url] through [open], at the location its base is on, and at each other location in
     * turn while one fails. Throws the first failure when every location fails.
     */
    suspend fun <T> open(url: String, open: suspend (String) -> T): T {
        val found = lock.withLock {
            sets.firstOrNull { moved(url, it.primary, it.primary) != null }?.let { set -> set to current.getOrPut(set.primary) { startOf(set) } }
        }
        val (set, start) = found ?: return open(url)
        val locations = set.locations
        var failure: Exception? = null
        val failedNetworks = HashSet<String>()

        suspend fun attempt(index: Int): Result<T>? {
            val target = moved(url, set.primary, locations[index].url) ?: return null
            return try {
                Result.success(open(target))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                failure = failure ?: failed
                locations[index].serviceLocation?.let(failedNetworks::add)
                null
            }
        }

        // The location this reader is on, then the rest in the manifest's order, and those on a
        // network that already failed once the others have.
        val later = ArrayList<Int>()
        for (step in locations.indices) {
            val index = (start + step).mod(locations.size)
            val network = locations[index].serviceLocation
            if (step > 0 && network != null && network in failedNetworks) {
                later += index
                continue
            }
            attempt(index)?.let { opened ->
                if (index != start) settle(set, start, index, failure)
                return opened.getOrThrow()
            }
        }
        for (index in later) {
            attempt(index)?.let { opened ->
                settle(set, start, index, failure)
                return opened.getOrThrow()
            }
        }
        throw failure ?: IllegalStateException("no location of ${shownUri(url)} could be opened")
    }

    private suspend fun settle(set: DashBaseUrls, from: Int, to: Int, failure: Exception?) {
        val moved = lock.withLock {
            if (current[set.primary] != from) return@withLock false
            current[set.primary] = to
            true
        }
        if (moved) {
            KiteLog.log(
                "KiteDash",
                "${shownUri(set.locations[from].url)} failed (${failure?.message}); its segments now come from " +
                    shownUri(set.locations[to].url),
            )
        }
    }

    /** DVB's first location: by weight, at random, among those of the lowest priority. */
    private fun startOf(set: DashBaseUrls): Int {
        if (set.locations.all { it.priority == null && it.weight == null }) return 0
        val lowest = set.locations.minOf { it.priority ?: 1 }
        val candidates = set.locations.withIndex().filter { (it.value.priority ?: 1) == lowest }
        val total = candidates.sumOf { (it.value.weight ?: 1).coerceAtLeast(0).toLong() }
        if (total <= 0) return candidates.first().index
        var pick = random.nextLong(total)
        for (candidate in candidates) {
            pick -= (candidate.value.weight ?: 1).coerceAtLeast(0)
            if (pick < 0) return candidate.index
        }
        return candidates.first().index
    }

    internal companion object {
        private fun sorted(sets: List<DashBaseUrls>): List<DashBaseUrls> =
            sets.filter { it.locations.size > 1 }.sortedByDescending { directory(it.primary).length }

        /**
         * [url] moved from the base [from] to the base [to]: [to] itself for [from] itself, and
         * otherwise what follows the last `/` of [from]'s path put after the last `/` of [to]'s.
         * Null when [url] is not under [from].
         */
        fun moved(url: String, from: String, to: String): String? {
            if (url == from) return to
            val source = directory(from)
            if (!url.startsWith(source)) return null
            return directory(to) + url.substring(source.length)
        }

        /** [base] up to and including the last `/` of its path, with its query and fragment gone. */
        fun directory(base: String): String {
            val bare = base.substringBefore('#').substringBefore('?')
            val pathStart = bare.indexOf("://").let { if (it < 0) 0 else bare.indexOf('/', it + 3) }
            if (pathStart < 0) return "$bare/"
            return bare.substring(0, bare.lastIndexOf('/') + 1)
        }
    }
}
