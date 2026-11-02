package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes
import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes.Sample
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TTML subtitles in HLS (#439): the renditions of the groups a variant naming `stpp` names are
 * served as WebVTT, their playlists naming each segment under the reader's own host, and every
 * other address opens as it would.
 */
class HlsTtmlTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="imsc",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="vtt",NAME="English",LANGUAGE="en",URI="vtt/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS="avc1.64001f,mp4a.40.2,stpp.ttml.im1t",SUBTITLES="imsc"
        video/hi.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=900,CODECS="avc1.64001f,mp4a.40.2",SUBTITLES="vtt"
        video/hi-vtt.m3u8
    """.trimIndent()

    @Test
    fun onlyTheRenditionsOfAGroupAnStppVariantNamesAreTtml() {
        assertEquals(setOf("https://cdn.test/hls/subs/en.m3u8"), HlsTtmlMediaIo.ttmlRenditions(master, "https://cdn.test/hls/master.m3u8"))
        assertEquals(emptySet(), HlsTtmlMediaIo.ttmlRenditions(master.replace(",stpp.ttml.im1t", ""), "https://cdn.test/hls/master.m3u8"))
    }

    private fun ttml(vararg paragraphs: String) =
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""

    private val init = Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "subt", sampleEntry = "stpp")
    private val first = Mp4Bytes.segment(1, decodeTime = 0, listOf(Sample(2000, ttml("""<p begin="0.5s" end="1.5s">Hello <span tts:fontStyle="italic" xmlns:tts="http://www.w3.org/ns/ttml#styling">there</span></p>""").encodeToByteArray())))
    private val second = Mp4Bytes.segment(1, decodeTime = 2000, listOf(Sample(2000, ttml("""<p begin="00:00:02.250" end="00:00:03.000">Line one<br/>line two</p>""").encodeToByteArray())))

    /** One file holding the initialization and both segments, as a packager's byte ranges name them. */
    private val file = init + first + second

    private val files = mapOf(
        "https://cdn.test/hls/subs/en.m3u8" to """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-MAP:URI="subs.mp4",BYTERANGE="${init.size}@0"
            #EXTINF:2.0,
            #EXT-X-BYTERANGE:${first.size}@${init.size}
            subs.mp4
            #EXTINF:2.0,
            #EXT-X-BYTERANGE:${second.size}
            subs.mp4
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray(),
        "https://cdn.test/hls/subs/subs.mp4" to file,
        "https://cdn.test/hls/vtt/en.m3u8" to "#EXTM3U\n#EXTINF:2.0,\nen-0.vtt\n#EXT-X-ENDLIST\n".encodeToByteArray(),
    )

    private val opened = ArrayList<String>()

    private inner class Served(override val location: String, private val bytes: ByteArray) : MediaIo {
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

        override suspend fun openRelated(uri: String): MediaIo? {
            opened += uri
            return files[uri]?.let { Served(uri, it) }
        }

        override fun close() {}
    }

    private suspend fun MediaIo.text(): String {
        val out = StringBuilder()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) return out.toString()
            out.append(buffer.decodeToString(0, count))
        }
    }

    @Test
    fun aTtmlRenditionIsServedAsWebVttSegmentBySegment() = runTest {
        val reader = HlsTtmlMediaIo(Served("https://cdn.test/hls/master.m3u8", master.encodeToByteArray()), setOf("https://cdn.test/hls/subs/en.m3u8"))
        val playlist = reader.openRelated("https://cdn.test/hls/subs/en.m3u8")!!.text().lines()
        assertTrue(playlist.none { it.startsWith("#EXT-X-MAP") || it.startsWith("#EXT-X-BYTERANGE") }, "the playlist kept a tag WebVTT cannot take: $playlist")
        assertEquals(2, playlist.count { it.startsWith("#EXTINF") })
        val segments = playlist.filter { it.startsWith("https://") }
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.startsWith("https://${HlsTtmlMediaIo.HOST}/") }, "a segment is not named under the reader's host: $segments")
        assertEquals(
            "WEBVTT\n\n00:00:00.500 --> 00:00:01.500\nHello <i>there</i>\n\n",
            reader.openRelated(segments[0])!!.text(),
        )
        assertEquals(
            "WEBVTT\n\n00:00:02.250 --> 00:00:03.000\nLine one\nline two\n\n",
            reader.openRelated(segments[1])!!.text(),
        )
        // The same playlist loaded again names its segments the same.
        assertEquals(segments, reader.openRelated("https://cdn.test/hls/subs/en.m3u8")!!.text().lines().filter { it.startsWith("https://") })
    }

    @Test
    fun everyOtherAddressOpensAsItWould() = runTest {
        val reader = HlsTtmlMediaIo(Served("https://cdn.test/hls/master.m3u8", master.encodeToByteArray()), setOf("https://cdn.test/hls/subs/en.m3u8"))
        assertEquals("#EXTM3U\n#EXTINF:2.0,\nen-0.vtt\n#EXT-X-ENDLIST\n", reader.openRelated("https://cdn.test/hls/vtt/en.m3u8")!!.text())
        assertNull(reader.openRelated("https://${HlsTtmlMediaIo.HOST}/99.vtt"), "an address the reader never named opened")
        assertEquals(master, reader.text(), "the master playlist reads as it came")
    }

    /** A rendition of one segment file per cue, numbered from zero, whose playlist the test writes. */
    private inner class Numbered(private val playlistUri: String) : MediaIo {
        var playlist = ""
        override val location: String get() = playlistUri
        override val size: Long? get() = null
        override val seekable: Boolean get() = false
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = -1
        override suspend fun seek(position: Long) {}
        override fun close() {}

        override suspend fun openRelated(uri: String): MediaIo? = when {
            uri == playlistUri -> Served(uri, playlist.encodeToByteArray())
            uri == "https://cdn.test/live/init.mp4" -> Served(uri, init)
            uri.startsWith("https://cdn.test/live/s-") -> {
                val n = uri.removePrefix("https://cdn.test/live/s-").removeSuffix(".mp4").toLong()
                val cue = ttml("""<p begin="${n * 2}s" end="${n * 2 + 1}s">cue $n</p>""").encodeToByteArray()
                Served(uri, Mp4Bytes.segment(1, decodeTime = n * 2000, listOf(Sample(2000, cue))))
            }
            else -> null
        }

        fun window(from: Long, count: Int, ended: Boolean = false) {
            playlist = buildString {
                append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$from\n")
                append("#EXT-X-MAP:URI=\"init.mp4\"\n")
                for (n in from until from + count) append("#EXTINF:2.0,\ns-$n.mp4\n")
                if (ended) append("#EXT-X-ENDLIST\n")
            }
        }
    }

    /**
     * A live window that moves one segment a reload names every segment under its own address once
     * the reader holds [HlsTtmlMediaIo.MAX_SEGMENTS] of them (#618). Numbered by the map's size, the
     * third reload named its last two segments alike and served the newer cues for both.
     */
    @Test
    fun aLongLiveWindowNamesEverySegmentOnce() = runTest {
        val uri = "https://cdn.test/live/subs.m3u8"
        val upstream = Numbered(uri)
        val reader = HlsTtmlMediaIo(upstream, setOf(uri))
        val size = HlsTtmlMediaIo.MAX_SEGMENTS
        var named = emptyList<String>()
        for (moved in 0L..3L) {
            upstream.window(from = moved, count = size)
            named = reader.openRelated(uri)!!.text().lines().filter { it.startsWith("https://") }
            assertEquals(size, named.toSet().size, "reload $moved named ${named.size - named.toSet().size} segments twice")
        }
        val last = 3L + size - 1
        assertEquals("cue ${last - 1}", reader.openRelated(named[size - 2])!!.text().lines()[3])
        assertEquals("cue $last", reader.openRelated(named[size - 1])!!.text().lines()[3])
    }

    /** A playlist that has ended is never reloaded, so every segment it names stays named, however many. */
    @Test
    fun anEndedPlaylistLongerThanTheLimitKeepsItsFirstSegments() = runTest {
        val uri = "https://cdn.test/live/subs.m3u8"
        val upstream = Numbered(uri)
        val reader = HlsTtmlMediaIo(upstream, setOf(uri))
        upstream.window(from = 0, count = HlsTtmlMediaIo.MAX_SEGMENTS + 100, ended = true)
        val named = reader.openRelated(uri)!!.text().lines().filter { it.startsWith("https://") }
        assertEquals("cue 0", reader.openRelated(named.first())?.text()?.lines()?.get(3), "the first segment no longer opens")
    }
}
