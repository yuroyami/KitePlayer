package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which addresses the DASH door hands to a transport that keeps segments in a store (#547): only
 * the media and initialization segments that the manifest of an ended presentation named. A live
 * presentation, its clock and an address the manifest did not name open the plain way.
 */
class DashSegmentStoreDoorTest {

    private fun manifest(type: String, extra: String = "") = """<?xml version="1.0" encoding="utf-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type" $extra minBufferTime="PT2S">
          <UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-xsdate:2014" value="https://time.test/now"/>
          <Period id="0" start="PT0S">
            <AdaptationSet id="0" contentType="video">
              <Representation id="v" mimeType="video/mp4" codecs="avc1.42c00d" bandwidth="300000" width="320" height="180">
                <SegmentTemplate timescale="1000" duration="2000" initialization="v-init.m4s" media="v-${'$'}Number${'$'}.m4s" startNumber="1"/>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent().encodeToByteArray()

    /** The door over [bytes], with what opened the plain way in [plain] and what opened as a segment in [kept]. */
    private suspend fun door(bytes: ByteArray, plain: MutableList<String>, kept: MutableList<String>): MediaIo = assertNotNull(
        DashDoor.readerIfManifest(
            io = Found(bytes, "https://cdn.test/show/manifest.mpd", "application/dash+xml"),
            peek = { count -> bytes.copyOf(minOf(count, bytes.size)) },
            open = { url ->
                plain += url
                Found(if ("time.test" in url) "2026-10-08T00:00:00Z".encodeToByteArray() else byteArrayOf(1, 2, 3), url, null)
            },
            dateOf = { null },
            openSegment = { url ->
                kept += url
                Found(byteArrayOf(1, 2, 3), url, null)
            },
        ),
    )

    private suspend fun readAll(io: MediaIo): ByteArray {
        val out = ArrayList<Byte>()
        val buffer = ByteArray(4096)
        while (true) {
            val count = io.read(buffer, 0, buffer.size)
            if (count < 0) break
            for (i in 0 until count) out += buffer[i]
        }
        return out.toByteArray()
    }

    /** Reads the master playlist and its first media playlist, and returns the addresses that playlist names. */
    private suspend fun playlistAddresses(reader: MediaIo): List<String> {
        val master = readAll(reader).decodeToString()
        val variant = master.lines().first { it.isNotBlank() && !it.startsWith("#") }
        val playlist = readAll(assertNotNull(reader.openRelated(variant))).decodeToString()
        val initialization = playlist.lines().firstOrNull { it.startsWith("#EXT-X-MAP:") }?.substringAfter("URI=\"")?.substringBefore('"')
        return listOfNotNull(initialization) + playlist.lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun onlyWhatTheManifestOfAnEndedPresentationNamedOpensAsASegment() = runTest {
        val plain = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val reader = door(manifest("static", "mediaPresentationDuration=\"PT8S\""), plain, kept)
        val named = playlistAddresses(reader)
        assertTrue("https://cdn.test/show/v-init.m4s" in named && "https://cdn.test/show/v-4.m4s" in named, "$named")
        assertEquals(emptyList(), plain + kept, "the playlists of a template ask the network for nothing")

        for (address in named) readAll(assertNotNull(reader.openRelated(address)))
        assertEquals(named, kept, "every address the playlist named opens as a segment")
        assertEquals(emptyList(), plain)

        readAll(assertNotNull(reader.openRelated("https://cdn.test/show/other.bin")))
        assertEquals(listOf("https://cdn.test/show/other.bin"), plain, "an address the manifest did not name opens the plain way")
        assertEquals(named, kept)
        reader.close()
    }

    @Test
    fun aLivePresentationOpensNothingAsASegmentAndItsClockNeither() = runTest {
        val plain = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val live = "availabilityStartTime=\"2026-01-01T00:00:00Z\" minimumUpdatePeriod=\"PT2S\" timeShiftBufferDepth=\"PT10S\""
        val reader = door(manifest("dynamic", live), plain, kept)
        val named = playlistAddresses(reader)
        assertTrue(named.size >= 2, "the live playlist names its window: $named")
        assertTrue("https://time.test/now" in plain, "the clock of the manifest was asked: $plain")

        for (address in named) readAll(assertNotNull(reader.openRelated(address)))
        assertEquals(emptyList(), kept, "a live presentation keeps nothing")
        assertTrue(plain.containsAll(named), "its segments open the plain way: $plain")
        reader.close()
    }

    private class Found(private val bytes: ByteArray, override val location: String, override val contentType: String?) : MediaIo {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close() {}
    }
}
