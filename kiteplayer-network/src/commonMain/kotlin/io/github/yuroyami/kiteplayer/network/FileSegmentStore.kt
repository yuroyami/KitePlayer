@file:OptIn(ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.SegmentStore
import io.github.yuroyami.kiteplayer.SegmentStoreEntry
import io.github.yuroyami.kiteplayer.SegmentStoreWriter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A [SegmentStore] that keeps its entries as files in [directory] (#547), or null when the
 * directory cannot be used: another store, in this process or in another, already owns it, the
 * platform has no files, as on the web, or the directory cannot be made. A player with no store
 * reads from the network, so a null here is safe to pass on.
 *
 * Give the store a directory of its own, such as a folder in the application's cache directory,
 * and close the store when the application is done with it. It removes what it does not know in
 * the part of the directory it writes.
 *
 * [maxBytes] is the most bytes of media the store keeps. When a new span would pass it, the store
 * removes the entries used longest ago that no reader holds, and it drops the new span when that
 * frees too little. [namespace] separates what is written for one use, such as one account, from
 * what is written for another over the same directory. Pass [privateToOneAccount] as true only
 * when every item played with this store belongs to one account: the store then also keeps the
 * answers to requests that carry a login or a cookie.
 *
 * A span reaches its entry through a temporary file and a rename, so a reader never sees half of
 * one, and a store that starts over a directory whose last process died removes what was left
 * unfinished.
 */
public fun fileSegmentStore(
    directory: String,
    maxBytes: Long,
    namespace: String = "",
    privateToOneAccount: Boolean = false,
): SegmentStore? {
    require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
    val files = platformStoreFiles() ?: return null
    return directorySegmentStore(files, directory.trimEnd('/').ifEmpty { "/" }, maxBytes, namespace, privateToOneAccount)
}

/** [fileSegmentStore] over [files], which a test replaces. */
internal fun directorySegmentStore(
    files: StoreFiles,
    directory: String,
    maxBytes: Long,
    namespace: String = "",
    privateToOneAccount: Boolean = false,
): SegmentStore? {
    val owned = try {
        files.makeDirectory(directory)
        files.lock("$directory/lock") ?: return null
    } catch (_: Exception) {
        return null
    }
    return try {
        DirectorySegmentStore(files, directory, maxBytes, namespace, privateToOneAccount, owned)
    } catch (_: Exception) {
        owned.close()
        null
    }
}

/** The files of this platform, or null where a store cannot keep any. */
internal expect fun platformStoreFiles(): StoreFiles?

/**
 * The file operations a [DirectorySegmentStore] needs. A path is absolute, with `/` between its
 * levels. Every member throws when the platform refuses the operation.
 */
internal interface StoreFiles {
    /** Takes [path] as a lock file for this process until the handle closes, or returns null when another holder has it. */
    fun lock(path: String): AutoCloseable?

    /** Makes [path] a directory, with every missing level above it. */
    fun makeDirectory(path: String)

    /** The names in the directory [path], or none when it does not exist. */
    fun list(path: String): List<String>

    /** The whole of the file [path], or null when it does not exist. */
    fun readAll(path: String): ByteArray?

    /** Reads up to [length] bytes of [path] from [position]. Returns how many, or -1 at its end. */
    fun read(path: String, position: Long, into: ByteArray, offset: Int, length: Int): Int

    /** Starts [path] as a new, empty file. */
    fun create(path: String): StoreFileSink

    /** Moves [from] to [to] in one step, in place of any file there. */
    fun rename(from: String, to: String)

    /** Removes the file or the empty directory [path]. One that does not exist is left alone. */
    fun delete(path: String)
}

/** A file being written. [close] reports a write that did not reach the disk. */
internal interface StoreFileSink {
    fun write(from: ByteArray, offset: Int, length: Int)

    fun close()
}

/**
 * The lock of a store and of a list of named addresses. A section can write a small file, rename
 * or delete, so a thread that waits for it sleeps and does not spin.
 */
internal class StoreLock : SynchronizedObject() {
    inline fun <T> withLock(block: () -> T): T = synchronized(this, block)
}

/**
 * The store behind [fileSegmentStore]. Each entry is a directory under `v1`, named by the digest
 * of the entry's name and a sequence number, and holds the record, one file for each span, and a
 * count that orders the entries by their last use.
 *
 * An entry that was removed while a reader held it keeps its directory, marked, until the reader
 * closes, and a new entry of the same name starts in a new directory.
 */
internal class DirectorySegmentStore(
    private val files: StoreFiles,
    root: String,
    private val maxBytes: Long,
    override val namespace: String,
    override val privateToOneAccount: Boolean,
    private val owned: AutoCloseable,
) : SegmentStore {

    private class Span(val start: Long, val length: Long, val file: String) {
        val end: Long get() = start + length
    }

    private class Resource(val key: String, val directory: String, val number: Long) {
        var record: ByteArray? = null
        val spans = ArrayList<Span>()
        var bytes = 0L
        var used = 0L
        var holders = 0
        var removed = false
        var onDisk = false
    }

    private val lock = StoreLock()
    private val base = "$root/$VERSION_DIRECTORY"
    private val live = HashMap<String, Resource>()

    /** The bytes of every span on the disk, those of removed entries that a reader still holds included. */
    private var total = 0L
    private var useCount = 0L
    private var sequence = 0L
    private var closed = false

    init {
        files.makeDirectory(base)
        for (name in files.list(base)) recover(name)
        lock.withLock { makeRoom(0, keep = null) }
    }

    override val sizeBytes: Long get() = lock.withLock { total }

    override fun open(name: String): SegmentStoreEntry {
        val key = sha256Hex(name.encodeToByteArray())
        val resource = lock.withLock {
            check(!closed) { "the segment store is closed" }
            val found = live.getOrPut(key) { (sequence++).let { Resource(key, "$base/$key.$it", it) } }
            found.holders++
            found.used = ++useCount
            found
        }
        if (resource.onDisk) {
            // Only the order of eviction reads this, so a write that fails loses nothing.
            runCatching { writeWhole("${resource.directory}/$USED_FILE", resource.used.toString().encodeToByteArray()) }
        }
        return Handle(resource)
    }

    override fun remove(name: String) {
        val key = sha256Hex(name.encodeToByteArray())
        lock.withLock {
            check(!closed) { "the segment store is closed" }
            live.remove(key)?.let(::retire)
        }
    }

    override fun clear() {
        lock.withLock {
            check(!closed) { "the segment store is closed" }
            val all = live.values.toList()
            live.clear()
            all.forEach(::retire)
        }
    }

    override fun close() {
        val wasOpen = lock.withLock { !closed.also { closed = true } }
        if (wasOpen) owned.close()
    }

    /** Takes [resource] out of the store. Its files go now, or when its last holder closes. Called under [lock]. */
    private fun retire(resource: Resource) {
        resource.removed = true
        if (!resource.onDisk) return
        if (resource.holders == 0) {
            erase(resource)
        } else {
            // The mark outlives a crash, so the next start removes the directory.
            files.create("${resource.directory}/$GONE_FILE").close()
        }
    }

    /** Deletes the directory of [resource] and uncounts its bytes. Called under [lock]. */
    private fun erase(resource: Resource) {
        deleteDirectory(resource.directory)
        total -= resource.bytes
        resource.bytes = 0
        resource.spans.clear()
        resource.onDisk = false
    }

    private fun deleteDirectory(directory: String) {
        for (name in files.list(directory)) files.delete("$directory/$name")
        files.delete(directory)
    }

    private fun release(resource: Resource) {
        lock.withLock {
            resource.holders--
            if (resource.holders > 0) return
            if (resource.removed) {
                if (resource.onDisk && !closed) erase(resource)
            } else if (!resource.onDisk && live[resource.key] === resource) {
                // Nothing was written, so the entry leaves no trace.
                live.remove(resource.key)
            }
        }
    }

    /**
     * Removes the entries used longest ago that nobody holds, until [needed] more bytes fit under
     * the limit. False when they do not fit even then. [keep] is never removed. Called under [lock].
     */
    private fun makeRoom(needed: Long, keep: Resource?): Boolean {
        if (needed > maxBytes) return false
        while (total + needed > maxBytes) {
            val oldest = live.values
                .filter { it.holders == 0 && it !== keep && it.onDisk }
                .minByOrNull { it.used } ?: return false
            live.remove(oldest.key)
            oldest.removed = true
            erase(oldest)
        }
        return true
    }

    /** Reads the directory [name] of an earlier process into the index, or removes it when it is not a whole entry. */
    private fun recover(name: String) {
        val directory = "$base/$name"
        val key = name.substringBeforeLast('.')
        val number = name.substringAfterLast('.', "").toLongOrNull()
        val found = files.list(directory)
        if (number == null || key.isEmpty() || GONE_FILE in found) {
            deleteDirectory(directory)
            return
        }
        sequence = maxOf(sequence, number + 1)
        val resource = Resource(key, directory, number)
        resource.onDisk = true
        for (file in found) {
            when {
                file == RECORD_FILE -> resource.record = files.readAll("$directory/$file")
                file == USED_FILE -> resource.used = files.readAll("$directory/$file")?.decodeToString()?.trim()?.toLongOrNull() ?: 0
                file.endsWith(SPAN_SUFFIX) -> {
                    val range = file.removeSuffix(SPAN_SUFFIX)
                    val start = range.substringBefore('-').toLongOrNull()
                    val length = range.substringAfter('-', "").toLongOrNull()
                    if (start == null || length == null || start < 0 || length <= 0) {
                        files.delete("$directory/$file")
                    } else {
                        resource.spans += Span(start, length, file)
                        resource.bytes += length
                    }
                }
                // A temporary file is a write that its process never finished.
                else -> files.delete("$directory/$file")
            }
        }
        val other = live[key]
        if ((resource.record == null && resource.spans.isEmpty()) || (other != null && other.number > number)) {
            deleteDirectory(directory)
            return
        }
        if (other != null) {
            deleteDirectory(other.directory)
            total -= other.bytes
        }
        resource.spans.sortBy { it.start }
        useCount = maxOf(useCount, resource.used)
        total += resource.bytes
        live[key] = resource
    }

    /** Makes the directory of [resource] when it has none yet. Called under [lock]. */
    private fun materialize(resource: Resource) {
        if (resource.onDisk) return
        files.makeDirectory(resource.directory)
        resource.onDisk = true
        writeWhole("${resource.directory}/$USED_FILE", resource.used.toString().encodeToByteArray())
    }

    private fun writeWhole(path: String, bytes: ByteArray) {
        val sink = files.create(path)
        try {
            sink.write(bytes, 0, bytes.size)
        } finally {
            sink.close()
        }
    }

    private inner class Handle(private val resource: Resource) : SegmentStoreEntry {
        private val released = AtomicBoolean(false)

        override val record: ByteArray? get() = lock.withLock { resource.record }

        override fun putRecord(record: ByteArray) {
            lock.withLock {
                check(!closed) { "the segment store is closed" }
                if (resource.removed) return
                materialize(resource)
                val partial = "${resource.directory}/${sequence++}$TEMPORARY_SUFFIX"
                writeWhole(partial, record)
                files.rename(partial, "${resource.directory}/$RECORD_FILE")
                resource.record = record
            }
        }

        override fun spans(): List<LongRange> = lock.withLock { resource.spans.map { it.start until it.end } }

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            val span = lock.withLock {
                check(!closed) { "the segment store is closed" }
                resource.spans.firstOrNull { position >= it.start && position < it.end }
            } ?: return 0
            // The entry is held, so nothing removes this file while it is read.
            val count = minOf(length.toLong(), span.end - position).toInt()
            val read = files.read("${resource.directory}/${span.file}", position - span.start, into, offset, count)
            check(read > 0) { "a stored span ended before its length" }
            return read
        }

        override fun write(position: Long): SegmentStoreWriter {
            require(position >= 0) { "a span cannot start at $position" }
            return Writer(resource, position)
        }

        override fun close() {
            if (released.compareAndSet(expectedValue = false, newValue = true)) release(resource)
        }
    }

    private inner class Writer(private val resource: Resource, private val start: Long) : SegmentStoreWriter {
        private var sink: StoreFileSink? = null
        private var partial: String? = null
        private var length = 0L
        private var done = false

        override fun write(from: ByteArray, offset: Int, length: Int) {
            check(!done) { "the span is already published or dropped" }
            if (length <= 0) return
            val open = sink ?: run {
                val path = lock.withLock {
                    check(!closed) { "the segment store is closed" }
                    materialize(resource)
                    "${resource.directory}/${sequence++}$TEMPORARY_SUFFIX"
                }
                partial = path
                files.create(path).also { sink = it }
            }
            open.write(from, offset, length)
            this.length += length
        }

        override fun publish() {
            if (done) return
            done = true
            val path = partial ?: return
            try {
                sink?.close()
            } catch (failure: Exception) {
                runCatching { files.delete(path) }
                throw failure
            }
            lock.withLock {
                val end = start + length
                val covered = resource.spans.any { it.start <= start && it.end >= end }
                // The entry itself is held by this writer's reader, so only others make room.
                if (closed || resource.removed || covered || !makeRoom(length, keep = resource)) {
                    files.delete(path)
                    return
                }
                val name = "$start-$length$SPAN_SUFFIX"
                files.rename(path, "${resource.directory}/$name")
                // With one holder, which is this writer's, no reader is inside a span that this one covers.
                if (resource.holders == 1) {
                    val inside = resource.spans.filter { it.start >= start && it.end <= end }
                    for (span in inside) {
                        files.delete("${resource.directory}/${span.file}")
                        resource.spans -= span
                        resource.bytes -= span.length
                        total -= span.length
                    }
                }
                resource.spans += Span(start, length, name)
                resource.spans.sortBy { it.start }
                resource.bytes += length
                total += length
            }
        }

        override fun close() {
            if (done) return
            done = true
            val path = partial ?: return
            runCatching { sink?.close() }
            runCatching { files.delete(path) }
        }
    }

    private companion object {
        /** The layout's version. A later layout takes another directory and leaves this one. */
        const val VERSION_DIRECTORY = "v1"
        const val RECORD_FILE = "record"
        const val USED_FILE = "used"
        const val GONE_FILE = "gone"
        const val SPAN_SUFFIX = ".span"
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}
