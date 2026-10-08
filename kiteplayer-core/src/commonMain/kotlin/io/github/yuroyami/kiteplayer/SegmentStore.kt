package io.github.yuroyami.kiteplayer

/**
 * Keeps the bytes of media segments between player lifetimes (#547), so a presentation that is
 * played again reads them from here instead of from the network.
 *
 * A store is opt-in. Give one to [NetworkConfig.segmentStore], or to the resolver of the network
 * module, and close it when the application is done with it. One store can serve several players
 * at once. The network module's `fileSegmentStore` makes one that keeps files in a directory.
 *
 * The store knows nothing about HTTP. The transport that uses it decides what is kept and for how
 * long, and gives each resource a name. An entry holds spans, which are runs of bytes of one
 * resource, and one record, which is a small block of bytes that only the transport reads.
 *
 * Every member may be called from any thread. A member that fails throws, and the transport then
 * reads from the network and reports [PlaybackWarning.SegmentStoreFailed].
 */
public interface SegmentStore : AutoCloseable {
    /**
     * What separates this store's entries from those of another use of the same storage, such as
     * another account. The transport puts it in every name, so an entry that was written under one
     * namespace is never read under another.
     */
    public val namespace: String

    /**
     * True when the application made this store for one account only. The transport keeps the
     * answer to a request that carried a login or a cookie only in such a store.
     */
    public val privateToOneAccount: Boolean

    /** The bytes of every span this store keeps now. */
    public val sizeBytes: Long

    /**
     * The entry called [name], which is made empty when the store has none. The caller holds the
     * entry until it closes it, and the store removes no byte of an entry that is held.
     */
    public fun open(name: String): SegmentStoreEntry

    /**
     * Removes the entry called [name] with its record and every span. A caller that holds the
     * entry keeps reading what it held, and the bytes go when the last holder closes.
     */
    public fun remove(name: String)

    /** Removes every entry, as [remove] does for one. */
    public fun clear()

    /** Releases the storage. Idempotent. The entries stay for the next store over the same storage. */
    override fun close()
}

/** One resource in a [SegmentStore]: its record and its spans. Close it to let the store evict it. */
public interface SegmentStoreEntry : AutoCloseable {
    /** The record that [putRecord] stored last, or null for an entry that has none yet. */
    public val record: ByteArray?

    /** Replaces the record. The change is whole or absent, never half written. */
    public fun putRecord(record: ByteArray)

    /** The published spans, as ranges of byte positions in the resource, in ascending order. */
    public fun spans(): List<LongRange>

    /**
     * Reads at most [length] bytes of the resource at [position] into [into] at [offset], from
     * one published span. Returns how many it read, or 0 when no published span holds [position].
     */
    public fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int

    /** Starts a new span at [position]. Nobody reads its bytes before [SegmentStoreWriter.publish]. */
    public fun write(position: Long): SegmentStoreWriter

    /** Lets go of the entry. Idempotent. */
    override fun close()
}

/** One span of a [SegmentStoreEntry] while it is written. */
public interface SegmentStoreWriter : AutoCloseable {
    /** Adds [length] bytes of [from], starting at [offset], to the end of the span. */
    public fun write(from: ByteArray, offset: Int, length: Int)

    /** Makes the span readable, whole and at once. The writer takes no more bytes after it. */
    public fun publish()

    /** Drops the span when it was not published. Idempotent. */
    override fun close()
}
