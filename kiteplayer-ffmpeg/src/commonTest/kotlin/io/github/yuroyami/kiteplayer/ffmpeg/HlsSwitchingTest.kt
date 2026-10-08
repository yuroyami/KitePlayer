package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The variant switch of an HLS stream (#464), with no FFmpeg: the playlist it serves in place of a
 * variant's, which bytes each of its addresses gives, and when it refuses a change.
 */
class HlsSwitchingTest {

    private val base = "https://cdn.test/hls/low/index.m3u8"

    private fun vod(name: String, count: Int = 3, seconds: String = "2.000000", extra: String = "") = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append(extra)
        for (n in 0 until count) append("#EXTINF:$seconds,\n$name-$n.ts\n")
        append("#EXT-X-ENDLIST\n")
    }

    @Test
    fun theServedPlaylistNamesEachSegmentByItsNumberAndKeepsEveryOtherLine() {
        val playlist = assertNotNull(readSwitchablePlaylist(vod("seg"), base))
        assertEquals(
            listOf(
                "#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:2", "#EXT-X-MEDIA-SEQUENCE:7", "#EXT-X-PLAYLIST-TYPE:VOD",
                "#EXTINF:2.000000,", "https://kite-hls.invalid/segment/7.ts",
                "#EXTINF:2.000000,", "https://kite-hls.invalid/segment/8.ts",
                "#EXTINF:2.000000,", "https://kite-hls.invalid/segment/9.ts",
                "#EXT-X-ENDLIST",
            ),
            playlist.served.trim().lines(),
        )
        assertTrue(playlist.ended)
        assertEquals(listOf(7L, 8L, 9L), playlist.segments.map { it.sequence })
        assertEquals("https://cdn.test/hls/low/seg-1.ts", playlist.at(8)?.address)
        assertEquals(listOf(0L, 2_000_000L, 4_000_000L), playlist.segments.map { it.startMicros })
        assertNull(playlist.at(6))
        assertNull(playlist.at(10))
    }

    @Test
    fun byteRangesBecomeWholeAddressesOfTheSwitch() {
        val text = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI=\"all.m4s\",BYTERANGE=\"800@0\"\n" +
            "#EXTINF:2.0,\n#EXT-X-BYTERANGE:1000@800\nall.m4s\n#EXTINF:2.0,\n#EXT-X-BYTERANGE:500\nall.m4s\n#EXT-X-ENDLIST\n"
        val playlist = assertNotNull(readSwitchablePlaylist(text, base))
        assertFalse("BYTERANGE" in playlist.served, playlist.served)
        assertTrue("#EXT-X-MAP:URI=\"https://kite-hls.invalid/init.m4s\"" in playlist.served, playlist.served)
        val init = assertNotNull(playlist.init)
        assertEquals(Triple("https://cdn.test/hls/low/all.m4s", 0L, 800L), Triple(init.address, init.offset, init.length))
        assertEquals(listOf(800L to 1000L, 1800L to 500L), playlist.segments.map { it.offset to it.length })
    }

    @Test
    fun aKeyKeepsItsLineWithItsAddressResolved() {
        val text = vod("seg", extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\",IV=0x01\n")
        val playlist = assertNotNull(readSwitchablePlaylist(text, base))
        assertTrue("#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.test/hls/key.bin\",IV=0x01" in playlist.served, playlist.served)
        assertTrue(playlist.encrypted)
        val other = assertNotNull(readSwitchablePlaylist(vod("seg", extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"other.bin\",IV=0x01\n"), base))
        assertFalse(sameSegments(playlist, other), "another key cannot decrypt the same address of the switch")
        assertTrue(sameSegments(playlist, assertNotNull(readSwitchablePlaylist(text, "https://cdn.test/hls/high/index.m3u8"))))
    }

    @Test
    fun whatTheSwitchDoesNotServeIsNotRead() {
        assertNull(readSwitchablePlaylist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n", base), "a master playlist")
        assertNull(readSwitchablePlaylist(vod("seg", extra = "#EXT-X-DEFINE:NAME=\"a\",VALUE=\"b\"\n"), base), "variables")
        assertNull(readSwitchablePlaylist(vod("seg-{\$token}"), base), "a variable from the master")
        assertNull(readSwitchablePlaylist(vod("seg", extra = "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n"), base), "sample encryption")
        val twoMaps = "#EXTM3U\n#EXT-X-MAP:URI=\"a.mp4\"\n#EXTINF:2,\na.m4s\n#EXT-X-MAP:URI=\"b.mp4\"\n#EXTINF:2,\nb.m4s\n"
        assertNull(readSwitchablePlaylist(twoMaps, base), "two initializations")
        assertNull(readSwitchablePlaylist("not a playlist", base))
    }

    @Test
    fun endedPlaylistsMatchOnlyWhenEverySegmentStartsAtTheSameTime() {
        val low = assertNotNull(readSwitchablePlaylist(vod("low"), base))
        assertTrue(sameSegments(low, assertNotNull(readSwitchablePlaylist(vod("high"), base))))
        val lastLonger = vod("high").replace("#EXTINF:2.000000,\nhigh-2.ts", "#EXTINF:2.033333,\nhigh-2.ts")
        assertTrue(sameSegments(low, assertNotNull(readSwitchablePlaylist(lastLonger, base))), "one frame apart")
        assertFalse(sameSegments(low, assertNotNull(readSwitchablePlaylist(vod("high", seconds = "2.033333"), base))), "drifting apart")
        assertFalse(sameSegments(low, assertNotNull(readSwitchablePlaylist(vod("high", count = 2, seconds = "3.000000"), base))))
        assertFalse(sameSegments(low, assertNotNull(readSwitchablePlaylist(vod("high", count = 4), base))))
        val live = vod("high").replace("#EXT-X-ENDLIST\n", "").replace("#EXT-X-PLAYLIST-TYPE:VOD\n", "")
        assertFalse(sameSegments(low, assertNotNull(readSwitchablePlaylist(live, base))), "a live playlist and an ended one")
    }

    private fun live(name: String, first: Int, count: Int, dateOfFirst: String? = null) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        if (dateOfFirst != null) append("#EXT-X-PROGRAM-DATE-TIME:$dateOfFirst\n")
        for (n in first until first + count) append("#EXTINF:2.000000,\n$name-$n.ts\n")
    }

    @Test
    fun livePlaylistsMatchWhereTheyOverlapAndByDateWhenBothStateOne() {
        fun read(text: String) = assertNotNull(readSwitchablePlaylist(text, base))
        assertTrue(sameSegments(read(live("low", 10, 5)), read(live("high", 12, 5))), "three segments in both")
        assertFalse(sameSegments(read(live("low", 10, 5)), read(live("high", 20, 5))), "no segment in both")
        val dated = read(live("low", 10, 5, "2026-10-08T10:00:20.000Z"))
        assertTrue(sameSegments(dated, read(live("high", 11, 5, "2026-10-08T10:00:22.000Z"))), "segment 11 is at the same date in both")
        assertFalse(sameSegments(dated, read(live("high", 11, 5, "2026-10-08T10:00:30.000Z"))), "the same number, another moment")
    }

    private fun variant(line: String) = HlsVariant(0, 1, parseHlsAttributes(line))

    @Test
    fun aDecoderFollowsOnlyAVariantOfTheSameKind() {
        val low = variant("BANDWIDTH=1,RESOLUTION=320x180,CODECS=\"avc1.42c00d,mp4a.40.2\"")
        assertTrue(HlsVariantSwitch.sameKind(low, variant("BANDWIDTH=2,RESOLUTION=640x360,CODECS=\"avc3.42c01e,mp4a.40.2\"")))
        assertFalse(HlsVariantSwitch.sameKind(low, variant("BANDWIDTH=2,RESOLUTION=640x360,CODECS=\"hvc1.1.6.L93.90,mp4a.40.2\"")), "another codec")
        assertFalse(HlsVariantSwitch.sameKind(low, variant("BANDWIDTH=2,CODECS=\"mp4a.40.2\"")), "sound only")
        assertFalse(
            HlsVariantSwitch.sameKind(low, variant("BANDWIDTH=2,RESOLUTION=640x360,CODECS=\"avc1.42c01e\",AUDIO=\"aac\"")),
            "its sound is a rendition, and the other's is in the segments",
        )
        val hdr = variant("BANDWIDTH=2,RESOLUTION=640x360,CODECS=\"hvc1.2.4.L93.90\",VIDEO-RANGE=PQ")
        assertFalse(HlsVariantSwitch.sameKind(variant("BANDWIDTH=2,RESOLUTION=640x360,CODECS=\"hvc1.2.4.L93.90\""), hdr), "another range")
    }

    /** A reader of [text] at [location] that counts as closed once [close] ran. */
    private class Text(text: String, override val location: String, private val closed: MutableList<String>) : MediaIo {
        private val bytes = text.encodeToByteArray()
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

        override fun close() {
            closed += location
        }
    }

    private suspend fun MediaIo.text(): String = readPlaylist(this, "test").decodeToString().also { close() }

    private val traits = listOf(
        variant("BANDWIDTH=1,RESOLUTION=320x180,CODECS=\"avc1.42c00d,mp4a.40.2\""),
        variant("BANDWIDTH=2,RESOLUTION=640x360,CODECS=\"avc1.42c01e,mp4a.40.2\""),
        variant("BANDWIDTH=3,RESOLUTION=1280x720,CODECS=\"hvc1.1.6.L93.90,mp4a.40.2\""),
    )

    private class Stream(val files: MutableMap<String, String>) {
        val asked = mutableListOf<String>()
        val closed = mutableListOf<String>()
    }

    private fun switchOver(stream: Stream, initial: Int = 0, choose: Int = 1) = HlsVariantSwitch(
        addresses = listOf("https://cdn.test/low.m3u8", "https://cdn.test/high.m3u8", "https://cdn.test/hevc.m3u8"),
        traits = traits,
        initial = initial,
        choose = { choose },
        openAddress = { address ->
            stream.asked += address
            stream.files[address]?.let { Text(it, address, stream.closed) }
        },
    )

    private fun files() = mutableMapOf(
        "https://cdn.test/low.m3u8" to vod("low"),
        "https://cdn.test/high.m3u8" to vod("high"),
        "https://cdn.test/hevc.m3u8" to vod("hevc"),
    ).also { files ->
        for (name in listOf("low", "high", "hevc")) for (n in 0..2) files["https://cdn.test/$name-$n.ts"] = "$name $n"
    }

    @Test
    fun aSegmentAddressGivesTheBytesOfTheVariantThatPlaysWhenItIsAsked() = runTest {
        val stream = Stream(files())
        val switch = switchOver(stream)
        assertFalse(switch.request(1), "nothing was read yet, so nothing is known to match")
        val served = assertNotNull(switch.open(HlsVariantSwitch.PLAYLIST)).text()
        assertTrue("https://kite-hls.invalid/segment/8.ts" in served, served)
        assertEquals("low 0", assertNotNull(switch.open("https://kite-hls.invalid/segment/7.ts")).text())
        assertEquals("https://cdn.test/low-1.ts", switch.nameOf("https://kite-hls.invalid/segment/8.ts"))

        assertTrue(switch.request(1))
        assertEquals(1, switch.selected)
        assertEquals("high 1", assertNotNull(switch.open("https://kite-hls.invalid/segment/8.ts")).text())
        assertEquals("https://cdn.test/high-2.ts", switch.nameOf("https://kite-hls.invalid/segment/9.ts"))
        assertEquals("https://cdn.test/high.m3u8", switch.nameOf(HlsVariantSwitch.PLAYLIST))

        // The player's own choice is the variant its chooser names.
        assertTrue(switch.request(null))
        assertEquals(1, switch.selected)
        assertTrue(switch.request(0))
        assertEquals("low 2", assertNotNull(switch.open("https://kite-hls.invalid/segment/9.ts")).text())
        assertNull(switch.open("https://kite-hls.invalid/segment/10.ts"), "no variant has that segment")
        assertNull(switch.open("https://kite-hls.invalid/other"))
        assertEquals(stream.asked.filter { it.endsWith(".m3u8") }.sorted(), stream.closed.filter { it.endsWith(".m3u8") }.sorted(), "every playlist reader was closed")
    }

    @Test
    fun aChangeIsRefusedForAnotherCodecAnotherCutAndAVariantThatCannotBeRead() = runTest {
        val stream = Stream(files())
        stream.files["https://cdn.test/high.m3u8"] = vod("high", count = 2, seconds = "3.000000")
        val switch = switchOver(stream)
        assertNotNull(switch.open(HlsVariantSwitch.PLAYLIST)).close()
        assertFalse(switch.request(1), "its segments are cut elsewhere")
        assertFalse(switch.request(2), "its codec is another")
        assertFalse("https://cdn.test/hevc.m3u8" in stream.asked, "a variant of another codec is not even read")
        stream.files.remove("https://cdn.test/high.m3u8")
        assertFalse(switch.request(1), "its playlist cannot be read")
        assertFalse(switch.request(7), "there is no such variant")
        assertEquals(0, switch.selected)
        assertEquals("low 0", assertNotNull(switch.open("https://kite-hls.invalid/segment/7.ts")).text())
    }

    @Test
    fun aPlaylistTheSwitchDoesNotServeReachesFFmpegAsItIsAndNeverChanges() = runTest {
        val stream = Stream(files())
        val text = vod("low-{\$token}")
        stream.files["https://cdn.test/low.m3u8"] = text
        val switch = switchOver(stream)
        val reader = assertNotNull(switch.open(HlsVariantSwitch.PLAYLIST))
        assertEquals("https://cdn.test/low.m3u8", reader.location, "FFmpeg resolves its addresses against where it came from")
        assertEquals(text, reader.text())
        assertFalse(switch.request(1))
        assertEquals(0, switch.selected)
    }

    @Test
    fun aLiveSegmentTheNewVariantHasNotListedYetIsLookedForOnceMore() = runTest {
        val stream = Stream(mutableMapOf())
        stream.files["https://cdn.test/low.m3u8"] = live("low", 10, 5)
        stream.files["https://cdn.test/high.m3u8"] = live("high", 9, 5)
        for (n in 9..16) {
            stream.files["https://cdn.test/low-$n.ts"] = "low $n"
            stream.files["https://cdn.test/high-$n.ts"] = "high $n"
        }
        val switch = switchOver(stream)
        assertNotNull(switch.open(HlsVariantSwitch.PLAYLIST)).close()
        assertTrue(switch.request(1))
        // FFmpeg still holds the first variant's list, which ends one segment later than the new one's.
        stream.files["https://cdn.test/high.m3u8"] = live("high", 11, 5)
        assertEquals("high 14", assertNotNull(switch.open("https://kite-hls.invalid/segment/14.ts")).text())
        // The next reload is the new variant's.
        val reloaded = assertNotNull(switch.open(HlsVariantSwitch.PLAYLIST)).text()
        assertTrue("#EXT-X-MEDIA-SEQUENCE:11" in reloaded, reloaded)
        assertEquals("high 15", assertNotNull(switch.open("https://kite-hls.invalid/segment/15.ts")).text())
    }
}
