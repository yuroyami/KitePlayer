package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * HLS backup variants (#440): a master playlist's variants that differ only in their address are
 * kept apart for the one that plays, its renditions mapped to theirs, and [HlsFailover] moves a
 * failing address to a backup and stays there.
 */
class HlsBackupsTest {

    private val master = listOf(
        "#EXTM3U",
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a1\",NAME=\"English\",LANGUAGE=\"en\",URI=\"one/audio-en.m3u8\"",
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a2\",NAME=\"English\",LANGUAGE=\"en\",URI=\"https://two.test/audio-en.m3u8\"",
        "#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360,CODECS=\"avc1.42c01e,mp4a.40.2\",AUDIO=\"a1\"",
        "one/video.m3u8",
        "#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360,CODECS=\"avc1.42c01e,mp4a.40.2\",AUDIO=\"a2\"",
        "https://two.test/video.m3u8",
        "#EXT-X-STREAM-INF:BANDWIDTH=500,RESOLUTION=320x180,CODECS=\"avc1.42c00d,mp4a.40.2\",AUDIO=\"a1\"",
        "one/low.m3u8",
    ).joinToString("\n")

    @Test
    fun aVariantThatDiffersOnlyInItsAddressIsTheKeptOnesBackup() {
        val kept = checkNotNull(keepOneHlsVariant(master, null, null))
        assertEquals(0, kept.chosen)
        assertEquals(listOf("one/video.m3u8" to listOf("https://two.test/video.m3u8"), "one/audio-en.m3u8" to listOf("https://two.test/audio-en.m3u8")),
            kept.backups.map { it.primary to it.alternatives })
        // FFmpeg still reads one variant and its own group's rendition.
        assertEquals(1, kept.playlist.lines().count { it.startsWith("#EXT-X-STREAM-INF") })
        assertEquals(listOf("one/audio-en.m3u8"), kept.playlist.lines().filter { it.startsWith("#EXT-X-MEDIA") }.map { parseHlsAttributes(it.substringAfter(':'))["URI"] })
    }

    @Test
    fun aMasterWithoutBackupsHasNone() {
        val kept = checkNotNull(keepOneHlsVariant(master, 600, null))
        assertEquals(2, kept.chosen)
        assertEquals(emptyList(), kept.backups.map { it.primary })
    }

    private class Io(override val location: String) : MediaIo {
        override val size: Long? = null
        override val seekable: Boolean = false
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = -1
        override suspend fun seek(position: Long) = Unit
        override fun close() {}
    }

    @Test
    fun aFailingAddressMovesToTheBackupAndStaysThere() = runTest {
        val failover = HlsFailover(checkNotNull(keepOneHlsVariant(master, null, null)).backups, "https://one.test/hls/master.m3u8")
        val asked = ArrayList<String>()
        suspend fun open(address: String) = failover.open(address) { target ->
            asked += target
            if (target.startsWith("https://one.test/")) throw IllegalStateException("404 from $target")
            Io(target)
        }
        assertEquals("https://two.test/video.m3u8", open("https://one.test/hls/one/video.m3u8")?.location)
        assertEquals("https://two.test/seg-1.ts?k=1", open("https://one.test/hls/one/seg-1.ts?k=1")?.location)
        assertEquals("https://two.test/audio-en.m3u8", open("https://one.test/hls/one/audio-en.m3u8")?.location)
        assertEquals(
            listOf(
                "https://one.test/hls/one/video.m3u8", "https://two.test/video.m3u8",
                "https://two.test/seg-1.ts?k=1",
                "https://one.test/hls/one/audio-en.m3u8", "https://two.test/audio-en.m3u8",
            ),
            asked,
            "after the switch the stream went back to the failed server",
        )
        // The backup's own addresses open as they are, and an address under nothing too.
        asked.clear()
        open("https://two.test/seg-2.ts")
        open("https://other.test/key")
        assertEquals(listOf("https://two.test/seg-2.ts", "https://other.test/key"), asked)
    }

    @Test
    fun aRefusedAddressIsRefusedAndEveryLocationFailingThrowsTheFirstFailure() = runTest {
        val failover = HlsFailover(checkNotNull(keepOneHlsVariant(master, null, null)).backups, "https://one.test/hls/master.m3u8")
        assertNull(failover.open("https://one.test/hls/one/video.m3u8") { null })
        val failure = assertFailsWith<IllegalStateException> {
            failover.open("https://one.test/hls/one/video.m3u8") { target -> throw IllegalStateException("404 from $target") }
        }
        assertEquals("404 from https://one.test/hls/one/video.m3u8", failure.message)
        val io = Io("x")
        assertSame(io, failover.open("https://elsewhere.test/a") { io })
    }
}
