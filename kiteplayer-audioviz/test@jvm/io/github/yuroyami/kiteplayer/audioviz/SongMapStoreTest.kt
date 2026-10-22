package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.TrackId
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The directory store on real files: what it keeps, what it drops, and what it survives. */
class SongMapStoreTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "kiteplayer-songmaps-${System.nanoTime()}")

    @AfterTest
    fun clean() {
        directory.deleteRecursively()
    }

    private fun map(covered: Long) = SongMap(
        SongMap.VERSION, TrackId(1), covered, complete = true, referencePower = 0.25,
        structure = emptyArray(), keys = emptyArray(),
        levelCurve = floatArrayOf(-20f, -21f), curveStartMicros = 0L,
    )

    /** The real key format, which carries separators that cannot go into a file name as they are. */
    private fun key(index: Int) = "9f3a1c$index/1/242000000/44100/2/${SongMap.VERSION}"

    @Test
    fun aMapComesBackFromADirectoryThatDidNotExist() = runBlocking {
        assertTrue(!directory.exists(), "the test started with a directory already there")
        val store = SongMapStore.inDirectory(directory.absolutePath)
        assertNull(store.read(key(0)), "an empty store answered something")
        store.write(key(0), encodeSongMap(map(242_000_000L)))
        val back = assertNotNull(decodeSongMap(assertNotNull(store.read(key(0)), "nothing was kept")))
        assertEquals(242_000_000L, back.coveredThroughMicros)
        assertTrue(directory.isDirectory, "the store did not create its directory")
    }

    @Test
    fun aSecondWriteReplacesTheFirstAndLeavesNoTemporaryFile() = runBlocking {
        val store = SongMapStore.inDirectory(directory.absolutePath)
        store.write(key(0), encodeSongMap(map(1_000_000L)))
        store.write(key(0), encodeSongMap(map(2_000_000L)))
        val back = assertNotNull(decodeSongMap(assertNotNull(store.read(key(0)))))
        assertEquals(2_000_000L, back.coveredThroughMicros)
        assertEquals(listOf(".songmap"), directory.listFiles().orEmpty().map { it.name.substringAfterLast('.').let { ext -> ".$ext" } },
            "expected one map file and nothing else: ${directory.listFiles().orEmpty().map { it.name }}")
    }

    @Test
    fun aReaderNeverFindsTheFileMissingWhileItIsReplaced() {
        directory.mkdirs()
        val target = File(directory, "replaced.songmap").path
        assertTrue(SongMapFiles.write(target, byteArrayOf(0)))
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val misses = java.util.concurrent.atomic.AtomicInteger()
        val file = File(target)
        // Only a missing file counts. A read can fail for other reasons while a file is replaced on Windows.
        val reader = Thread {
            while (!stop.get()) if (!file.exists()) misses.incrementAndGet()
        }
        reader.start()
        try {
            repeat(3_000) { SongMapFiles.write(target, byteArrayOf((it % 100).toByte(), 1, 2, 3)) }
        } finally {
            stop.set(true)
            reader.join(10_000)
        }
        // Delete and rename leaves a moment in which the file does not exist. One move does not.
        assertEquals(0, misses.get(), "a reader found the map missing ${misses.get()} times during a replace")
    }

    @Test
    fun aFailedWriteLeavesNothingBehind() {
        directory.mkdirs()
        val blocker = File(directory, "blocked")
        blocker.writeText("a file where a directory is needed")
        assertTrue(!SongMapFiles.write(File(blocker, "x.songmap").path, byteArrayOf(1, 2, 3)))
        assertEquals(listOf("blocked"), directory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun aKeyWithSeparatorsStaysInsideTheDirectory() = runBlocking {
        val store = SongMapStore.inDirectory(directory.absolutePath)
        store.write("../../escape/1/2/3/${SongMap.VERSION}", encodeSongMap(map(1_000L)))
        val files = directory.listFiles().orEmpty()
        assertEquals(1, files.size, "expected one file, found ${files.map { it.name }}")
        assertTrue(!files.single().name.contains('/'), "a separator reached the file name")
        assertEquals(directory.absolutePath, files.single().parentFile.absolutePath,
            "the store wrote outside its own directory")
    }

    @Test
    fun theOldestMapsGoWhenTheDirectoryIsFull() = runBlocking {
        val store = SongMapStore.inDirectory(directory.absolutePath, maximumEntries = 3)
        val clock = System.currentTimeMillis() - 100_000L
        for (index in 0 until 5) {
            store.write(key(index), encodeSongMap(map(index * 1_000_000L)))
            // The store orders by modification time, which a file system may report in whole
            // seconds, so the ages are set here rather than waited for.
            directory.listFiles().orEmpty().single { it.name.startsWith("9f3a1c$index") }
                .setLastModified(clock + index * 10_000L)
        }
        assertEquals(3, directory.listFiles().orEmpty().size, "the store grew past its limit")
        assertNull(store.read(key(0)), "the oldest map survived")
        assertNull(store.read(key(1)), "the second oldest map survived")
        assertNotNull(store.read(key(4)), "the newest map was dropped")
        Unit
    }

    @Test
    fun aRemovedMapDoesNotComeBack() = runBlocking {
        val store = SongMapStore.inDirectory(directory.absolutePath)
        store.write(key(0), encodeSongMap(map(5L)))
        store.remove(key(0))
        assertNull(store.read(key(0)))
    }

    @Test
    fun anUnreadableEntryIsAMissRatherThanAFailure() = runBlocking {
        val store = SongMapStore.inDirectory(directory.absolutePath)
        store.write(key(0), encodeSongMap(map(5L)))
        val file = directory.listFiles().orEmpty().single()
        file.writeBytes(ByteArray(12) { 0x5A })
        assertNull(decodeSongMap(assertNotNull(store.read(key(0)))), "a corrupt file decoded to a map")
        // A store that cannot reach its directory at all still answers rather than throwing.
        val missing = SongMapStore.inDirectory(File(directory, "gone/deeper").absolutePath)
        assertNull(missing.read(key(0)))
        missing.remove(key(0))
    }
}
