package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.SegmentStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A new, empty directory for one test, under the platform's temporary directory. */
internal expect fun temporaryDirectory(): String

/** [fileSegmentStore] on the platform's real files (#547). `DirectorySegmentStoreTest` has the rules on files in memory. */
class FileSegmentStoreTest {

    private val directory = temporaryDirectory()
    private val files = assertNotNull(platformStoreFiles())

    @AfterTest
    fun removeTheDirectory() {
        fun remove(path: String) {
            for (name in files.list(path)) remove("$path/$name")
            runCatching { files.delete(path) }
        }
        remove(directory)
    }

    private fun store(maxBytes: Long = 10_000_000): SegmentStore = assertNotNull(fileSegmentStore(directory, maxBytes))

    private fun bytes(count: Int, seed: Int = 0): ByteArray = ByteArray(count) { (it * 31 + seed).toByte() }

    private fun SegmentStore.put(name: String, position: Long, content: ByteArray) {
        open(name).use { entry ->
            entry.putRecord("record of $name".encodeToByteArray())
            entry.write(position).use { writer ->
                // In pieces, as a reader hands them over.
                var at = 0
                while (at < content.size) {
                    val count = minOf(4_096, content.size - at)
                    writer.write(content, at, count)
                    at += count
                }
                writer.publish()
            }
        }
    }

    private fun SegmentStore.get(name: String, position: Long, count: Int): ByteArray? = open(name).use { entry ->
        val into = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = entry.read(position + filled, into, filled, count - filled)
            if (read == 0) return@use null
            filled += read
        }
        into
    }

    @Test
    fun whatWasPublishedIsReadByANewStoreOverTheDirectory() {
        val content = bytes(300_000)
        store().also { it.put("https://host/seg1.m4s", 1_000, content) }.close()
        val again = store()
        try {
            assertEquals(300_000L, again.sizeBytes)
            assertContentEquals(content, again.get("https://host/seg1.m4s", 1_000, content.size))
            assertContentEquals(content.copyOfRange(123_456, 123_556), again.get("https://host/seg1.m4s", 124_456, 100))
            assertNull(again.get("https://host/seg1.m4s", 0, 1))
            assertContentEquals("record of https://host/seg1.m4s".encodeToByteArray(), again.open("https://host/seg1.m4s").use { it.record })
        } finally {
            again.close()
        }
    }

    @Test
    fun anUnfinishedWriteOfADeadProcessIsRemovedAtTheNextStart() {
        val first = store()
        first.put("a", 0, bytes(5_000))
        // The process dies with a second span half written: nothing publishes or drops it.
        first.open("a").write(5_000).write(bytes(2_000), 0, 2_000)
        first.close()
        val entry = "$directory/v1/" + files.list("$directory/v1").single()
        assertTrue(files.list(entry).any { it.endsWith(".tmp") }, "the test wrote no unfinished file: ${files.list(entry)}")

        val again = store()
        try {
            assertTrue(files.list(entry).none { it.endsWith(".tmp") }, "the unfinished write stayed: ${files.list(entry)}")
            assertEquals(5_000L, again.sizeBytes)
            assertNull(again.get("a", 5_000, 1), "an unfinished span was read")
            assertContentEquals(bytes(5_000), again.get("a", 0, 5_000))
        } finally {
            again.close()
        }
    }

    /**
     * A span renamed into place before its bytes reached the disk comes back short after a power
     * loss. The next store must not trust the length in its name (#856).
     */
    @Test
    fun aSpanCutShortByAPowerLossIsDroppedAtTheNextStart() {
        val first = store()
        first.put("a", 0, bytes(5_000))
        first.put("a", 10_000, bytes(3_000, seed = 9))
        first.close()
        val entry = "$directory/v1/" + files.list("$directory/v1").single()
        val cut = files.list(entry).single { it.startsWith("10000-") }
        // The file keeps its name and loses its bytes, as a crash before the data was written leaves it.
        files.delete("$entry/$cut")
        files.create("$entry/$cut").apply { write(bytes(100, seed = 9), 0, 100); close() }

        val again = store()
        try {
            assertNull(again.get("a", 10_000, 3_000), "a span shorter than its name was trusted")
            assertContentEquals(bytes(5_000), again.get("a", 0, 5_000), "an intact span was lost")
            assertEquals(5_000L, again.sizeBytes, "the cut span still counts")
        } finally {
            again.close()
        }
    }

    /**
     * A span still being written counts against maxBytes, so one long read cannot fill the disk
     * with a temporary file the limit never sees (#852). The span is dropped, the writes go on
     * without error, and nothing is published.
     */
    @Test
    fun aSpanBeingWrittenStopsAtTheLimit() {
        val store = store(maxBytes = 20_000)
        try {
            store.open("big").use { entry ->
                entry.write(0).use { writer ->
                    val chunk = bytes(4_096)
                    repeat(20) {
                        writer.write(chunk, 0, chunk.size)
                        val dir = "$directory/v1"
                        val biggest = files.list(dir).flatMap { e -> files.list("$dir/$e").map { "$dir/$e/$it" } }
                            .filter { it.endsWith(".tmp") }
                            .maxOfOrNull { path -> files.readAll(path)?.size ?: 0 } ?: 0
                        assertTrue(biggest <= 20_000, "a span being written grew to $biggest bytes")
                    }
                    writer.publish()
                }
            }
            assertEquals(0L, store.sizeBytes, "a span past the limit was published")
            // The limit is free again for a span that fits.
            store.put("small", 0, bytes(10_000))
            assertEquals(10_000L, store.sizeBytes)
        } finally {
            store.close()
        }
    }

    @Test
    fun oneDirectoryHasOneStoreAtATime() {
        val first = store()
        assertNull(fileSegmentStore(directory, 1_000), "two stores own one directory")
        first.close()
        store().close()
    }

    @Test
    fun theLimitEvictsOnTheRealDisk() {
        val store = store(maxBytes = 25_000)
        try {
            for (name in listOf("a", "b", "c")) store.put(name, 0, bytes(10_000))
            assertEquals(20_000L, store.sizeBytes)
            assertNull(store.get("a", 0, 1))
            assertEquals(2, files.list("$directory/v1").size, "an evicted entry left its directory")
            store.clear()
            assertEquals(0L, store.sizeBytes)
            assertEquals(emptyList(), files.list("$directory/v1"))
        } finally {
            store.close()
        }
    }
}
