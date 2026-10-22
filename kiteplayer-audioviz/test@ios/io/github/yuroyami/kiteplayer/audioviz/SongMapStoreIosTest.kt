@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.TrackId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The directory store on real iOS files, where directories are made through POSIX. */
class SongMapStoreIosTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/kiteplayer-songmaps-${Random.nextLong().toULong()}"

    @AfterTest
    fun clean() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    @Test
    fun aMapComesBackFromADirectoryWhoseParentsDidNotExist() = runBlocking {
        // Two missing levels above the store's own directory, as under Caches on a fresh install.
        val store = SongMapStore.inDirectory("$base/a/b/songmaps")
        val key = "9f3a1c/1/242000000/44100/2/${SongMap.VERSION}"
        val map = SongMap(
            SongMap.VERSION, TrackId(1), 242_000_000L, complete = true, referencePower = 0.25,
            structure = emptyArray(), keys = emptyArray(),
            levelCurve = floatArrayOf(-20f, -21f), curveStartMicros = 0L,
        )
        store.write(key, encodeSongMap(map))
        val back = assertNotNull(decodeSongMap(assertNotNull(store.read(key), "nothing was kept")))
        assertEquals(242_000_000L, back.coveredThroughMicros)
    }
}
