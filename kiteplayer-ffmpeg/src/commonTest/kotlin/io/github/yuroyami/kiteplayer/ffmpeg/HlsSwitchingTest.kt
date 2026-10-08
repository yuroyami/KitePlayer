package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.mp4.Fmp4
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertTrue(playlist.segments.all { it.keyAddress == "https://cdn.test/hls/key.bin" && it.keyIv?.last() == 1.toByte() })
        val other = assertNotNull(readSwitchablePlaylist(vod("seg", extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"other.bin\",IV=0x01\n"), base))
        assertFalse(sameSegments(playlist, other), "another key cannot decrypt the same address of the switch")
        assertTrue(sameSegments(playlist, assertNotNull(readSwitchablePlaylist(text, "https://cdn.test/hls/high/index.m3u8"))))
    }

    @Test
    fun aPlaylistWithAnInitializationIsServedWithNoKey() {
        // The switch decrypts MP4 segments itself, so FFmpeg must not decrypt them again.
        val before = assertNotNull(readSwitchablePlaylist(mp4Vod("low", "#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x0A0b\n"), base))
        assertFalse("EXT-X-KEY" in before.served, before.served)
        assertTrue(before.served.endsWith("#EXT-X-ENDLIST\n"), before.served)
        val init = assertNotNull(before.init)
        assertEquals("https://cdn.test/hls/low/k", init.keyAddress)
        assertEquals(ByteArray(14).toList() + listOf<Byte>(0x0A, 0x0B), init.keyIv?.toList())
        assertEquals(7, init.sequence, "the number of the segment after it, for a key with no IV")
        assertTrue(before.segments.all { it.keyAddress == init.keyAddress })

        val after = "#EXTM3U\n#EXT-X-MAP:URI=\"i.mp4\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:2,\na.m4s\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:2,\nb.m4s\n"
        val late = assertNotNull(readSwitchablePlaylist(after, base))
        assertEquals("", assertNotNull(late.init).key, "a key after the tag leaves the initialization plain")
        assertEquals(listOf("https://cdn.test/hls/low/k", null), late.segments.map { it.keyAddress })
        assertEquals(null, late.segments[0].keyIv)
        assertFalse("EXT-X-KEY" in late.served, late.served)

        // Variants of an MP4 stream may each have a key of their own.
        val other = assertNotNull(readSwitchablePlaylist(mp4Vod("low", "#EXT-X-KEY:METHOD=AES-128,URI=\"other\"\n"), base))
        assertTrue(sameSegments(before, other))
        assertNull(readSwitchablePlaylist(mp4Vod("low", "#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0xNO\n"), base), "an IV that is no number")
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
        /** Files that are no text. */
        val bytes = mutableMapOf<String, ByteArray>()
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
            stream.files[address]?.let { Text(it, address, stream.closed) } ?: stream.bytes[address]?.let { BytesMediaIo(it, address) }
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

    private fun mp4Box(type: String, payload: ByteArray): ByteArray =
        byteArrayOf(0, 0, ((8 + payload.size) shr 8).toByte(), (8 + payload.size).toByte()) + type.encodeToByteArray() + payload

    /** An H.264 picture track whose configuration holds [sps], with NAL lengths of [lengthSize] bytes. */
    private fun pictures(id: Long, sps: Int, lengthSize: Int = 4): Fmp4.Track {
        val avcC = byteArrayOf(1, 0x64, 0, 0x1F, (0xFC or (lengthSize - 1)).toByte(), 0xE1.toByte(), 0, 2, 0x67, sps.toByte(), 1, 0, 1, 0x68)
        return Fmp4.Track(id, 15_360, "vide", "avc1", 0, 0, 0, mp4Box("avc1", ByteArray(78) + mp4Box("avcC", avcC)))
    }

    private fun track(id: Long, handler: String, entry: String, setup: Int = 0): Fmp4.Track =
        Fmp4.Track(id, 48_000, handler, entry, 0, 0, 0, mp4Box(entry, ByteArray(28) + mp4Box("conf", byteArrayOf(setup.toByte()))))

    @Test
    fun eachTrackOfAVariantIsWrittenForTheFirstInitializationsTrackOfItsKind() {
        val base = listOf(pictures(7, sps = 1), track(8, "soun", "mp4a"))
        val own = listOf(track(1, "soun", "mp4a"), pictures(2, sps = 2))
        val plans = assertNotNull(HlsVariantSwitch.plansFor(own, base))
        assertEquals(listOf(2L to 7L, 1L to 8L), plans.map { it.source.id to it.target.id }, "in the order of the first initialization")
        assertNotNull(HlsVariantSwitch.plansFor(base, base), "the first variant's own segments are written again too")
    }

    @Test
    fun noPlanIsMadeForTracksThatCannotStandInForTheFirstOnes() {
        val base = listOf(pictures(1, sps = 1), track(2, "soun", "mp4a"))
        assertNull(HlsVariantSwitch.plansFor(listOf(pictures(1, sps = 2)), base), "no sound where the stream began with sound")
        assertNull(HlsVariantSwitch.plansFor(listOf(pictures(1, sps = 2), pictures(2, sps = 3)), base), "two picture tracks")
        assertNull(HlsVariantSwitch.plansFor(listOf(pictures(1, sps = 2), track(2, "soun", "mp4a", setup = 9)), base), "sound set up another way")
        assertNull(HlsVariantSwitch.plansFor(listOf(pictures(1, sps = 2, lengthSize = 2), track(2, "soun", "mp4a")), base), "NAL lengths of another size")
        assertNull(HlsVariantSwitch.plansFor(listOf(pictures(1, sps = 2), track(2, "soun", "ac-3")), base), "sound in another codec")
        val titled = base + track(3, "subt", "stpp")
        assertNull(HlsVariantSwitch.plansFor(titled, titled), "a kind of track the switch does not write")
        val unknown = listOf(track(1, "vide", "mjpg"))
        assertNull(HlsVariantSwitch.plansFor(unknown, unknown), "pictures in a codec that is not known to take another size")
        assertNull(HlsVariantSwitch.plansFor(emptyList(), emptyList()), "an initialization that is not MP4 has no track")
    }

    /** An AV1 track whose `av1C` has [level] and the profile and bit depth bits [profile] and [depth]. */
    private fun av1(level: Int, profile: Int = 0, depth: Int = 0x0C): Fmp4.Track {
        val av1C = byteArrayOf(0x81.toByte(), ((profile shl 5) or level).toByte(), depth.toByte(), 0)
        return Fmp4.Track(1, 15_360, "vide", "av01", 0, 0, 0, mp4Box("av01", ByteArray(78) + mp4Box("av1C", av1C)))
    }

    /** A VP9 track whose `vpcC` has [level], [profile] and the bit depth and chroma byte [depth]. */
    private fun vp9(level: Int, profile: Int = 0, depth: Int = 0x82): Fmp4.Track {
        val vpcC = byteArrayOf(1, 0, 0, 0, profile.toByte(), level.toByte(), depth.toByte(), 1, 1, 1, 0, 0)
        return Fmp4.Track(1, 15_360, "vide", "vp09", 0, 0, 0, mp4Box("vp09", ByteArray(78) + mp4Box("vpcC", vpcC)))
    }

    @Test
    fun picturesWhoseKeyFramesStateTheirSizePairAcrossLevelsOfOneProfileAndBitDepth() {
        // An AV1 key frame holds a sequence header and a VP9 key frame its size, so nothing goes in band.
        assertNotNull(HlsVariantSwitch.plansFor(listOf(av1(level = 8)), listOf(av1(level = 0))), "AV1 at another level")
        assertNotNull(HlsVariantSwitch.plansFor(listOf(vp9(level = 31)), listOf(vp9(level = 11))), "VP9 at another level")
        assertNull(HlsVariantSwitch.plansFor(listOf(av1(level = 8, profile = 1)), listOf(av1(level = 0))), "AV1 in another profile")
        assertNull(HlsVariantSwitch.plansFor(listOf(av1(level = 8, depth = 0x4C)), listOf(av1(level = 0))), "AV1 in ten bits after eight")
        assertNull(HlsVariantSwitch.plansFor(listOf(vp9(level = 31, profile = 2)), listOf(vp9(level = 11))), "VP9 in another profile")
        assertNull(HlsVariantSwitch.plansFor(listOf(vp9(level = 31, depth = 0xA2)), listOf(vp9(level = 11))), "VP9 in ten bits after eight")
        assertNull(HlsVariantSwitch.plansFor(listOf(vp9(level = 31)), listOf(av1(level = 0))), "VP9 after AV1")
    }

    private fun mp4Vod(name: String, extra: String = "") = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append(extra)
        append("#EXT-X-MAP:URI=\"$name-init.mp4\"\n")
        for (n in 0..2) append("#EXTINF:2.000000,\n$name-$n.m4s\n")
        append("#EXT-X-ENDLIST\n")
    }

    @Test
    fun encryptedMp4SegmentsReachFfmpegInPlainBytes() = runTest {
        fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        // Both made by `openssl aes-128-cbc -e` with the key below: the first with the IV 7, which
        // is the number of the playlist's first segment, the second with the IV the playlist states.
        val numbered = bytes("0ad24560e1d7cb2518ea8b33a15bc68bdd55653692dd0d82f9d716228ad5f005")
        val stated = bytes("b966ed4454734ffdf516aca94970b5e7da574a5178fc40b19b5cc9e85d67edcc")
        val stream = Stream(files())
        stream.files["https://cdn.test/low.m3u8"] = mp4Vod("low", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n")
        stream.files["https://cdn.test/high.m3u8"] = mp4Vod("high", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x000102030405060708090a0b0c0d0e0f\n")
        stream.files["https://cdn.test/key"] = "0123456789abcdef"
        stream.bytes["https://cdn.test/low-init.mp4"] = numbered
        stream.bytes["https://cdn.test/low-0.m4s"] = numbered
        stream.bytes["https://cdn.test/high-1.m4s"] = stated
        val low = switchOver(stream)
        val served = assertNotNull(low.open(HlsVariantSwitch.PLAYLIST)).text()
        assertFalse("EXT-X-KEY" in served, served)
        assertEquals("0123456789abcdef", assertNotNull(low.open("https://kite-hls.invalid/init.mp4")).text(), "an initialization after the key is encrypted too")
        assertEquals("0123456789abcdef", assertNotNull(low.open("https://kite-hls.invalid/segment/7.m4s")).text())
        val high = switchOver(stream, initial = 1)
        assertNotNull(high.open(HlsVariantSwitch.PLAYLIST)).close()
        assertEquals("a segment of an HLS stream", assertNotNull(high.open("https://kite-hls.invalid/segment/8.m4s")).text())
        assertEquals(2, stream.asked.count { it == "https://cdn.test/key" }, "each of the two switches reads the key once")

        // A key that is not there, and a key that is another one.
        stream.files.remove("https://cdn.test/key")
        val keyless = switchOver(stream)
        assertNotNull(keyless.open(HlsVariantSwitch.PLAYLIST)).close()
        assertNull(keyless.open("https://kite-hls.invalid/segment/7.m4s"), "a segment whose key cannot be read is one that cannot be read")
        stream.files["https://cdn.test/key"] = "fedcba9876543210"
        val wrong = switchOver(stream)
        assertNotNull(wrong.open(HlsVariantSwitch.PLAYLIST)).close()
        val failure = assertFailsWith<IllegalStateException> { wrong.open("https://kite-hls.invalid/segment/7.m4s") }
        assertTrue("https://cdn.test/low-0.m4s cannot be decrypted" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun anMp4ChangeIsRefusedForAnInitializationThatIsNotMp4() = runTest {
        val stream = Stream(files())
        stream.files["https://cdn.test/low.m3u8"] = mp4Vod("low")
        stream.files["https://cdn.test/high.m3u8"] = mp4Vod("high")
        stream.files["https://cdn.test/low-init.mp4"] = "this is no MP4"
        stream.files["https://cdn.test/high-init.mp4"] = "nor is this"
        stream.files["https://cdn.test/low-0.m4s"] = "low 0"
        val switch = switchOver(stream)
        assertNotNull(switch.open(HlsVariantSwitch.PLAYLIST)).close()
        assertFalse(switch.request(1), "no track can be read from the first initialization")
        assertTrue("https://cdn.test/low-init.mp4" in stream.asked, "which was read to find that out")
        assertEquals(0, switch.selected)
        assertEquals("low 0", assertNotNull(switch.open("https://kite-hls.invalid/segment/7.m4s")).text(), "the stream goes on as it was")
    }
}
