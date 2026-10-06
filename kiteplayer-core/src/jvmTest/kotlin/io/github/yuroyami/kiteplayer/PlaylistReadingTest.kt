@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Playlist files read from disk into a queue (#490): their encoding decided from the bytes, a list
 * they name read in its place one level deep, and a list that names itself refused. JVM-hosted
 * because the files are real files.
 */
class PlaylistReadingTest {

    private val folder = File.createTempFile("kiteplayer-lists", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun file(name: String, bytes: ByteArray): File = File(folder, name).apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    private fun file(name: String, text: String): File = file(name, text.encodeToByteArray())

    @Test
    fun aListOnDiskIsReadAndOpenedAsTheQueue() = runTest {
        val list = file("album.m3u", "#EXTM3U\r\n#EXTINF:1,One\r\na.mp3\r\n#EXTINF:1,Two\r\nb.mp3\r\n")
        val harness = CoreHarness(this)
        val items = harness.core.readPlaylist(list.absolutePath, emptyMap())
        assertEquals(listOf("${folder.absolutePath}/a.mp3" to "One", "${folder.absolutePath}/b.mp3" to "Two"), items.map { it.uri to it.title })
        harness.attachRenderer()
        harness.core.openQueue(items, 0)
        assertEquals(2, harness.core.snapshots.value.queue.size)
        harness.close()
    }

    @Test
    fun aListInALegacyCodePageKeepsItsTitles() = runTest {
        // "Café" and "Déjà vu" in windows-1252, as an old player wrote them.
        val bytes = "#EXTINF:1,Café\na.mp3\n#EXTINF:1,Déjà vu\nb.mp3\n".toByteArray(Charsets.ISO_8859_1)
        val list = file("old.m3u", bytes)
        val harness = CoreHarness(this)
        assertEquals(listOf("Café", "Déjà vu"), harness.core.readPlaylist(list.absolutePath, emptyMap()).map { it.title })
        harness.close()
    }

    @Test
    fun aListItNamesIsReadInItsPlaceOneLevelDeep() = runTest {
        file("inner.pls", "[playlist]\nFile1=c.mp3\nTitle1=Three\nFile2=deeper.m3u\n")
        file("deeper.m3u", "d.mp3\n")
        val outer = file("outer.m3u", "a.mp3\ninner.pls\nb.mp3\n")
        val harness = CoreHarness(this)
        val uris = harness.core.readPlaylist(outer.absolutePath, emptyMap()).map { it.uri.substringAfterLast('/') }
        assertEquals(listOf("a.mp3", "c.mp3", "deeper.m3u", "b.mp3"), uris)
        harness.close()
    }

    @Test
    fun aListThatNamesItselfIsRefused() = runTest {
        val loop = file("loop.m3u", "a.mp3\nloop.m3u\n")
        file("back.m3u", "outer2.m3u\n")
        val outer = file("outer2.m3u", "back.m3u\n")
        val harness = CoreHarness(this)
        val self = assertFailsWith<PlaylistException> { harness.core.readPlaylist(loop.absolutePath, emptyMap()) }
        assertEquals(loop.absolutePath, self.uri)
        assertFailsWith<PlaylistException> { harness.core.readPlaylist(outer.absolutePath, emptyMap()) }
        harness.close()
    }

    @Test
    fun whatCannotBecomeAQueueIsRefused() = runTest {
        val harness = CoreHarness(this)
        val missing = assertFailsWith<PlaylistException> { harness.core.readPlaylist("${folder.absolutePath}/missing.m3u", emptyMap()) }
        assertTrue("could not be read" in missing.message.orEmpty(), missing.message)
        val hls = file("live.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n")
        assertFailsWith<PlaylistException> { harness.core.readPlaylist(hls.absolutePath, emptyMap()) }
        val empty = file("empty.pls", "[playlist]\nNumberOfEntries=0\n")
        val nothing = assertFailsWith<PlaylistException> { harness.core.readPlaylist(empty.absolutePath, emptyMap()) }
        assertTrue("names nothing" in nothing.message.orEmpty(), nothing.message)
        harness.close()
    }
}
