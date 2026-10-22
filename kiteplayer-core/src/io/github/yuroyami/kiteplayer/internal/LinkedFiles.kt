package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import kotlin.coroutines.cancellation.CancellationException

/** The extensions of the files beside the main file that may hold a linked segment (#501). */
private val MATROSKA_EXTENSIONS = setOf("mkv", "mka", "mks", "mk3d")

/** How many files beside the main file the search for linked segments reads at most (#501). */
internal const val LINKED_FOLDER_LIMIT: Int = 256

/**
 * The names of the entries in the local folder [folder], or null when it cannot be listed (#501).
 * The browser targets have no folder, and answer null.
 */
internal expect fun namesInFolder(folder: String): List<String>?

/**
 * The Matroska files beside the local file [path] that may hold a segment its ordered edition
 * links to (#501): the files of its folder with a Matroska extension, itself left out, in the order
 * of their names, at most [LINKED_FOLDER_LIMIT] of them.
 */
internal fun matroskaFilesBeside(path: String): List<String> {
    val slash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    val folder = when {
        slash < 0 -> "."
        slash == 0 -> path.substring(0, 1)
        else -> path.substring(0, slash)
    }
    val own = path.substring(slash + 1)
    val names = namesInFolder(folder) ?: return emptyList()
    return names
        .filter { it != own && it.substringAfterLast('.', "").lowercase() in MATROSKA_EXTENSIONS }
        .sorted()
        .take(LINKED_FOLDER_LIMIT)
        .map { if (slash < 0) it else path.substring(0, slash + 1) + it }
}

/**
 * The files the search for an ordered edition's linked segments reads, in order (#501): the item's
 * own [MediaItem.linkedFiles], then, for a main file named by a local path with no reader of its
 * own, the Matroska files beside it. The item's headers go only to a file on the item's own server,
 * as for an external subtitle.
 */
internal fun linkedCandidates(item: MediaItem): List<MediaItem> {
    fun asItem(uri: String, io: io.github.yuroyami.kiteplayer.MediaIoFactory?) = MediaItem(
        uri = uri,
        headers = if (sameHttpOrigin(uri, item.uri)) item.headers else emptyMap(),
        io = io,
    )
    val declared = item.linkedFiles.map { asItem(it.uri, it.io) }
    val path = if (item.io == null) localPathOf(item.uri) else null
    val beside = path?.let(::matroskaFilesBeside).orEmpty()
        .filter { candidate -> declared.none { it.uri == candidate } }
        .map { asItem(it, null) }
    return declared + beside
}

/**
 * Finds the file that holds a segment UID among [candidates], each read once, in order, and only as
 * far as the segments asked for so far need (#501). [open] opens one, and the search keeps open the
 * files a timeline is being made from until [close], so their editions and their times can be read.
 * A file read for another segment is closed again, and opened once more if a later link asks for it.
 */
internal class LinkedFileSearch(
    private val candidates: List<MediaItem>,
    private val open: suspend (MediaItem) -> BackendSession,
    private val closeSession: suspend (BackendSession) -> Unit,
    /** True for a failure that ends the search itself, such as a stop that preempts the open. */
    private val ends: (Throwable) -> Boolean = { false },
) {
    private var next = 0
    private val opened = HashMap<String, Pair<MediaItem, BackendSession>>()
    private val known = HashMap<String, MediaItem>()

    /** How many candidates could not be opened, which a segment no file holds is told with. */
    var unreadable: Int = 0
        private set

    /** The item and the open source of the file that holds [uid], or null when no candidate does. */
    suspend fun find(uid: String): Pair<MediaItem, PlayerMediaSource>? {
        opened[uid]?.let { (item, session) -> return item to session.source }
        known[uid]?.let { item ->
            val session = tryOpen(item) ?: return null
            opened[uid] = item to session
            return item to session.source
        }
        while (next < candidates.size) {
            val candidate = candidates[next++]
            val session = tryOpen(candidate) ?: continue
            val found = session.source.segmentUid
            when {
                found == null || found in known || found in opened -> closeSession(session)
                found == uid -> {
                    opened[uid] = candidate to session
                    return candidate to session.source
                }
                else -> {
                    known[found] = candidate
                    closeSession(session)
                }
            }
        }
        return null
    }

    private suspend fun tryOpen(item: MediaItem): BackendSession? = try {
        open(item)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        if (ends(failure)) throw failure
        unreadable++
        null
    }

    /** Closes every file the search holds open. */
    suspend fun close() {
        val held = opened.values.toList()
        opened.clear()
        for ((_, session) in held) runCatching { closeSession(session) }
    }
}
