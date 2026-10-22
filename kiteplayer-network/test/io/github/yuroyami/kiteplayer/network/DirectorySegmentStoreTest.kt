package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.SegmentStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules that keep the file store safe (#547), on files held in memory so every target runs them. */
class DirectorySegmentStoreTest {

    private val files = MemoryStoreFiles()

    private fun store(maxBytes: Long = 1_000): SegmentStore = assertNotNull(directorySegmentStore(files, "/cache", maxBytes))

    private fun bytes(count: Int, seed: Int = 0): ByteArray = ByteArray(count) { (it + seed).toByte() }

    private fun SegmentStore.put(name: String, position: Long, content: ByteArray) {
        open(name).use { entry ->
            entry.putRecord(name.encodeToByteArray())
            entry.write(position).use { writer ->
                writer.write(content, 0, content.size)
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
    fun aSpanIsReadOnlyAfterItIsPublished() {
        val store = store()
        store.open("a").use { entry ->
            val writer = entry.write(100)
            writer.write(bytes(40), 0, 40)
            assertEquals(emptyList(), entry.spans(), "an unpublished span was listed")
            assertEquals(0, entry.read(100, ByteArray(40), 0, 40), "an unpublished span was read")
            assertEquals(0L, store.sizeBytes)
            writer.publish()
            assertEquals(listOf(100L..139L), entry.spans())
            assertEquals(40L, store.sizeBytes)
        }
        assertContentEquals(bytes(40), store.get("a", 100, 40))
        assertContentEquals(bytes(10, seed = 30), store.get("a", 130, 10))
        assertNull(store.get("a", 140, 1), "a byte past the span was read")
    }

    @Test
    fun aWriterThatClosesUnpublishedLeavesNothing() {
        val store = store()
        store.open("a").use { entry ->
            entry.write(0).use { it.write(bytes(40), 0, 40) }
            assertEquals(emptyList(), entry.spans())
        }
        assertEquals(0L, store.sizeBytes)
        assertTrue(files.files.keys.none { it.endsWith(".tmp") }, "a temporary file stayed: ${files.files.keys}")
    }

    @Test
    fun aNewStoreOverTheDirectoryFindsWhatWasPublished() {
        store().also { it.put("a", 0, bytes(64)) }.close()
        val again = store()
        assertEquals(64L, again.sizeBytes)
        assertContentEquals(bytes(64), again.get("a", 0, 64))
        assertContentEquals("a".encodeToByteArray(), again.open("a").use { it.record })
    }

    @Test
    fun aWriteThatItsProcessNeverFinishedIsRemovedAtTheNextStart() {
        val first = store()
        first.put("a", 0, bytes(64))
        // A second span is half written when the process dies: its writer never publishes or closes.
        val entry = first.open("a")
        entry.write(64).write(bytes(32), 0, 32)
        assertTrue(files.files.keys.any { it.endsWith(".tmp") })
        first.close()

        val again = store()
        assertTrue(files.files.keys.none { it.endsWith(".tmp") }, "the unfinished write stayed: ${files.files.keys}")
        assertEquals(64L, again.sizeBytes)
        assertEquals(listOf(0L..63L), again.open("a").use { it.spans() })
        assertNull(again.get("a", 64, 1), "the unfinished bytes were read")
    }

    @Test
    fun theLeastRecentlyUsedEntriesGoWhenTheLimitIsPassed() {
        val store = store(maxBytes = 300)
        store.put("a", 0, bytes(100))
        store.put("b", 0, bytes(100))
        store.put("c", 0, bytes(100))
        // Using "a" again makes "b" the one used longest ago.
        assertNotNull(store.get("a", 0, 100))
        store.put("d", 0, bytes(100))
        assertEquals(300L, store.sizeBytes)
        assertNull(store.get("b", 0, 1), "the least recently used entry stayed")
        assertNotNull(store.get("a", 0, 100))
        assertNotNull(store.get("c", 0, 100))
        assertNotNull(store.get("d", 0, 100))
    }

    @Test
    fun anEntryAReaderHoldsIsNeverEvictedAndTheSizeStaysBounded() {
        val store = store(maxBytes = 200)
        store.put("a", 0, bytes(100))
        store.put("b", 0, bytes(100))
        val held = store.open("a")
        val other = store.open("b")
        // Both entries are held, so nothing can make room, and the new span is dropped.
        store.put("c", 0, bytes(100))
        assertEquals(200L, store.sizeBytes)
        assertNull(store.get("c", 0, 1))
        other.close()
        store.put("c", 0, bytes(100))
        assertEquals(200L, store.sizeBytes)
        assertNull(store.get("b", 0, 1), "the entry nobody held stayed")
        val into = ByteArray(100)
        assertEquals(100, held.read(0, into, 0, 100), "the held entry lost its bytes")
        assertContentEquals(bytes(100), into)
        held.close()
    }

    @Test
    fun aSpanLargerThanTheLimitIsDroppedAndEvictsNothing() {
        val store = store(maxBytes = 150)
        store.put("a", 0, bytes(100))
        store.put("b", 0, bytes(200))
        assertEquals(100L, store.sizeBytes)
        assertNotNull(store.get("a", 0, 100))
    }

    @Test
    fun clearNeverCutsAReadAndTheBytesGoWhenTheReaderCloses() {
        val store = store()
        store.put("a", 0, bytes(100))
        val reader = store.open("a")
        val into = ByteArray(100)
        assertEquals(50, reader.read(0, into, 0, 50))
        store.clear()
        assertEquals(50, reader.read(50, into, 50, 50), "clear cut the read")
        assertContentEquals(bytes(100), into)
        // A new reader does not find what was cleared, and what it writes is another entry.
        assertNull(store.get("a", 0, 1))
        store.put("a", 0, bytes(10, seed = 7))
        reader.close()
        assertEquals(10L, store.sizeBytes)
        assertContentEquals(bytes(10, seed = 7), store.get("a", 0, 10))
    }

    @Test
    fun anEntryRemovedWhileHeldDoesNotComeBackAfterACrash() {
        val first = store()
        first.put("a", 0, bytes(100))
        val reader = first.open("a")
        first.remove("a")
        // The process dies with the reader open.
        first.close()
        val again = store()
        assertEquals(0L, again.sizeBytes)
        assertNull(again.get("a", 0, 1), "a removed entry came back")
        reader.close()
    }

    @Test
    fun aSecondStoreOverTheSameDirectoryGetsNoStore() {
        val first = store()
        assertNull(directorySegmentStore(files, "/cache", 1_000), "two stores own one directory")
        first.close()
        assertNotNull(directorySegmentStore(files, "/cache", 1_000)).close()
    }

    @Test
    fun aDirectoryThatCannotBeUsedGivesNoStore() {
        files.failing = true
        assertNull(directorySegmentStore(files, "/cache", 1_000))
    }

    @Test
    fun aStoreThatIsClosedRefusesEveryUse() {
        val store = store()
        store.put("a", 0, bytes(10))
        val entry = store.open("a")
        store.close()
        assertFailsWith<IllegalStateException> { store.open("a") }
        assertFailsWith<IllegalStateException> { entry.read(0, ByteArray(10), 0, 10) }
        entry.close()
    }

    @Test
    fun aSpanThatCoversOlderOnesReplacesThem() {
        val store = store()
        store.put("a", 0, bytes(10))
        store.put("a", 10, bytes(10, seed = 10))
        store.put("a", 0, bytes(30))
        assertEquals(30L, store.sizeBytes)
        assertEquals(listOf(0L..29L), store.open("a").use { it.spans() })
        // A span that an older one already covers adds nothing.
        store.put("a", 5, bytes(10, seed = 5))
        assertEquals(30L, store.sizeBytes)
    }

    @Test
    fun anEntryThatNothingWasWrittenToLeavesNoTrace() {
        val store = store()
        store.open("a").use { assertNull(it.record) }
        assertTrue(files.files.keys.none { "/v1/" in it }, "an empty entry was written: ${files.files.keys}")
    }
}
