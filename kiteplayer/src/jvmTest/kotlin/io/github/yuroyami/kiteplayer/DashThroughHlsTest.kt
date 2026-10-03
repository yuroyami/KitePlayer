package io.github.yuroyami.kiteplayer

import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSourceFactory
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.github.yuroyami.kiteplayer.network.dash.Dash
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DASH played through the HLS path (#295), with the real FFmpeg backend reading the `dash/`
 * fixtures from a local HTTP server that answers range requests: separate video and audio sets,
 * one numbered set, single files whose segment index names their fragments, and a live manifest
 * written over the numbered set's segments, and the same layouts in WebM (#401). The automatic
 * transport plays the manifests from addresses with no extension and no type (#400).
 */
class DashThroughHlsTest {

    private val media: File = listOfNotNull(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, "dash") },
        File("testmedia/dash"),
        File("../testmedia/dash"),
    ).firstOrNull { File(it, "separate.mpd").isFile }
        ?: error("testmedia/dash is missing; run scripts/testmedia.sh")

    /** Every path the server was asked for, in order. */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var liveManifest: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/")
            asked += path
            val body = when {
                path == "live.mpd" || path == "edge" -> liveManifest?.encodeToByteArray()
                // Addresses with no extension, as tokenised CDN addresses are, and one sent with its own type (#400).
                path == "watch" || path == "typed" -> File(media, "separate.mpd").readBytes()
                path == "ondemand.mpd" -> onDemandManifest().encodeToByteArray()
                path == "subtitled.mpd" -> File(media, "separate.mpd").readText()
                    .replace("</Period>", "$SUBTITLE_SET</Period>").encodeToByteArray()
                path == "subs.vtt" -> SUBTITLES.encodeToByteArray()
                path == "ttml.mpd" -> File(media, "separate.mpd").readText()
                    .replace("</Period>", "$TTML_SET</Period>").encodeToByteArray()
                path == "subs.ttml" -> TTML.encodeToByteArray()
                path == "stpp.mpd" -> File(media, "separate.mpd").readText()
                    .replace("</Period>", "$STPP_SET</Period>").encodeToByteArray()
                else -> File(media, path).takeIf { it.isFile && it.parentFile == media }?.readBytes()
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
            val first = range?.substringBefore('-')?.toLong() ?: 0L
            val last = range?.substringAfter('-')?.toLongOrNull()?.coerceAtMost(body.size - 1L) ?: (body.size - 1L)
            val part = body.copyOfRange(first.toInt(), last.toInt() + 1)
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (path == "typed") exchange.responseHeaders.add("Content-Type", "application/dash+xml")
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${body.size}")
                exchange.sendResponseHeaders(206, part.size.toLong())
            } else {
                exchange.sendResponseHeaders(200, part.size.toLong())
            }
            exchange.responseBody.use { it.write(part) }
        }
        // The reader fetches ahead in the background, so requests overlap.
        executor = Executors.newCachedThreadPool()
        start()
    }
    private val root = "http://127.0.0.1:${server.address.port}"
    private val client = HttpClient()

    @AfterTest
    fun stop() {
        client.close()
        server.stop(0)
    }

    @Test
    fun separateVideoAndAudioSetsPlayTogetherAndSeekToSixtySeconds() = withSource("separate.mpd") { source ->
        assertEquals(listOf(180, 360), source.variants.map { it.height }, "each video representation is a variant")
        assertEquals(1, source.selectedVariant, "the larger variant plays")
        val read = source.readFor(seconds = 4.0)
        assertTrue(read.video > 90, "only ${read.video} video packets in the first seconds")
        assertTrue(read.audio > 150, "only ${read.audio} audio packets: the sound set did not play")
        source.seekToKeyframe(Pts(60_000_000))
        val after = source.readFor(seconds = 2.0)
        assertTrue(after.firstVideo in 57.9..60.1, "the first picture after the seek to 60 s is at ${after.firstVideo} s")
        assertTrue(after.firstAudio in 57.5..60.5, "the first sound after the seek is at ${after.firstAudio} s")
        assertTrue(asked.any { it.startsWith("separate-1-0003") }, "no segment near 60 s of the larger variant was read: $asked")
        assertTrue(asked.none { it.startsWith("separate-0-") }, "the smaller variant was read too: $asked")
    }

    @Test
    fun aWebVttSetPlaysAsASubtitleRendition() = withSource("subtitled.mpd") { source ->
        val subtitle = source.streams.singleOrNull { it.kind == TrackKind.Subtitle }
        assertNotNull(subtitle, "the WebVTT set is not a stream: ${source.streams.map { it.kind to it.codec }}")
        assertEquals("de", subtitle.language)
        val read = source.readFor(seconds = 6.0)
        assertTrue(read.subtitleTimes.size >= 2, "only ${read.subtitleTimes} cues arrived in the first 6 s")
        assertTrue("subs.vtt" in asked, "the WebVTT file was not read: $asked")
    }

    @Test
    fun aTtmlSetPlaysAsASubtitleRenditionAtItsOwnTimes() = withSource("ttml.mpd") { source ->
        val subtitle = source.streams.singleOrNull { it.kind == TrackKind.Subtitle }
        assertNotNull(subtitle, "the TTML set is not a stream: ${source.streams.map { it.kind to it.codec }}")
        assertEquals("es", subtitle.language)
        val read = source.readFor(seconds = 6.0)
        assertTrue(read.subtitleTimes.size >= 2, "only ${read.subtitleTimes} cues arrived in the first 6 s")
        assertOnTheTwoSecondGrid(read.subtitleTimes)
        assertTrue("subs.ttml" in asked, "the TTML file was not read: $asked")
    }

    @Test
    fun anStppFilePlaysAsASubtitleRenditionAtItsOwnTimes() = withSource("stpp.mpd") { source ->
        val subtitle = source.streams.singleOrNull { it.kind == TrackKind.Subtitle }
        assertNotNull(subtitle, "the stpp set is not a stream: ${source.streams.map { it.kind to it.codec }}")
        assertEquals("fr", subtitle.language)
        val read = source.readFor(seconds = 6.0)
        assertTrue(read.subtitleTimes.size >= 2, "only ${read.subtitleTimes} cues arrived in the first 6 s")
        assertOnTheTwoSecondGrid(read.subtitleTimes)
        assertTrue("subs-stpp.mp4" in asked, "the stpp file was not read: $asked")
    }

    /**
     * The cues arrive at the times the document names, one every two seconds from zero, and no
     * later than the first segment. FFmpeg's HLS reader starts a subtitle playlist at the point the
     * reading has reached when the stream is selected, after it read ahead to find the streams, and
     * drops the cues that begin before it, so the cue at zero may be missing; the WebVTT set loses
     * it the same way.
     */
    private fun assertOnTheTwoSecondGrid(times: List<Double>) {
        assertTrue(times.size >= 2, "only $times cues arrived in the first 6 s")
        assertTrue(times.first() < 2.5, "the first cue came at ${times.first()} s, past the first two")
        // FFmpeg's HLS reader moves every playlist by the same small offset, which the picture has too.
        assertTrue(times.all { abs(it - 2 * kotlin.math.round(it / 2)) < 0.1 }, "the cues are not at the times the document names: $times")
    }

    @Test
    fun oneSetWithATemplateSeeksToSixtySeconds() = withSource("single.mpd") { source ->
        val seconds = assertNotNull(source.duration).micros / 1e6
        assertTrue(seconds in 69.5..70.5, "the presentation lasts $seconds s")
        assertTrue(source.seekable, "a finished presentation can seek")
        source.seekToKeyframe(Pts(60_000_000))
        val after = source.readFor(seconds = 2.0)
        assertTrue(after.firstVideo in 57.9..60.1, "the first picture after the seek to 60 s is at ${after.firstVideo} s")
        assertTrue("single-0-31.m4s" in asked, "the segment that holds 60 s was not read: $asked")
        assertTrue("single-0-2.m4s" !in asked.drop(asked.indexOf("single-0-31.m4s")), "the seek read from the start again")
    }

    @Test
    fun singleFilesWhoseIndexNamesTheirFragmentsPlayAndSeek() = withSource("ondemand.mpd") { source ->
        val read = source.readFor(seconds = 3.0)
        assertTrue(read.video > 60, "only ${read.video} video packets")
        assertTrue(read.audio > 100, "only ${read.audio} audio packets")
        source.seekToKeyframe(Pts(60_000_000))
        val after = source.readFor(seconds = 2.0)
        assertTrue(after.firstVideo in 57.9..60.1, "the first picture after the seek to 60 s is at ${after.firstVideo} s")
        assertTrue(after.firstAudio in 57.5..60.5, "the first sound after the seek is at ${after.firstAudio} s")
    }

    @Test
    fun aLiveManifestPlaysAtTheEdgeAndReadsTheSegmentsThatArriveLater() = runBlocking {
        val openedAt = Instant.now()
        // Thirty seconds in: segments up to the fifteenth have ended, and later ones arrive every 2 s.
        liveManifest = liveManifest(availabilityStart = openedAt.minusSeconds(30))
        val item = Dash.mediaItemFor("$root/live.mpd", client)
        val source = KiteFFmpegSourceFactory().open(item)
        try {
            assertNull(source.duration, "a live presentation has no duration")
            source.selectStreams(source.streams.map { it.index }.toSet())
            // The source's timeline starts at the first segment it reads, so progress is measured
            // from there. FFmpeg starts three segments before the edge, so 6 s were available.
            val first = source.readFor(seconds = 0.5).firstVideo
            assertTrue("single-0-13.m4s" in asked && "single-0-12.m4s" !in asked, "playback did not start near the live edge: $asked")
            // Reading on past the edge waits for the segments that arrive, about one every 2 s.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            var latest = first
            while (latest - first < 12.0 && System.nanoTime() < deadline) {
                latest = maxOf(latest, source.readFor(seconds = 0.5).lastVideo)
            }
            assertTrue(latest - first >= 12.0, "the stream stopped after ${latest - first} s instead of following the edge")
            assertTrue("single-0-19.m4s" in asked, "a segment that arrived after the open was not read: $asked")
            assertTrue(asked.count { it == "live.mpd" } >= 2, "the live manifest was not fetched again: $asked")
        } finally {
            source.close()
        }
    }

    @Test
    fun webmSetsOfVp9AndOpusPlayTogetherAndSeekToSixtySeconds() = withSource("webm.mpd") { source ->
        assertEquals(listOf("vp9", "opus"), source.streams.map { it.codec }.sortedDescending(), "the WebM sets are not both streams")
        val read = source.readFor(seconds = 4.0)
        assertTrue(read.video > 90, "only ${read.video} video packets in the first seconds")
        assertTrue(read.audio > 150, "only ${read.audio} audio packets: the sound set did not play")
        source.seekToKeyframe(Pts(60_000_000))
        val after = source.readFor(seconds = 2.0)
        assertTrue(after.firstVideo in 57.9..60.1, "the first picture after the seek to 60 s is at ${after.firstVideo} s")
        assertTrue(after.firstAudio in 57.5..60.5, "the first sound after the seek is at ${after.firstAudio} s")
        assertTrue(asked.any { it.startsWith("webm-0-0003") }, "no segment near 60 s was read: $asked")
    }

    @Test
    fun singleWebmFilesWhoseCuesNameTheirClustersPlayAndSeek() = withSource("webm-ondemand.mpd") { source ->
        val read = source.readFor(seconds = 3.0)
        assertTrue(read.video > 60, "only ${read.video} video packets")
        assertTrue(read.audio > 100, "only ${read.audio} audio packets")
        source.seekToKeyframe(Pts(60_000_000))
        val after = source.readFor(seconds = 2.0)
        assertTrue(after.firstVideo in 57.9..60.1, "the first picture after the seek to 60 s is at ${after.firstVideo} s")
        assertTrue(after.firstAudio in 57.5..60.5, "the first sound after the seek is at ${after.firstAudio} s")
    }

    @Test
    fun aManifestWithNoExtensionAndNoTypePlaysThroughTheAutomaticTransport() = withAutomaticSource("watch?session=7") { source ->
        assertEquals(listOf(180, 360), source.variants.map { it.height }, "each video representation is a variant")
        val read = source.readFor(seconds = 4.0)
        assertTrue(read.video > 90, "only ${read.video} video packets in the first seconds")
        assertTrue(read.audio > 150, "only ${read.audio} audio packets: the sound set did not play")
        source.seekToKeyframe(Pts(60_000_000))
        val after = source.readFor(seconds = 2.0)
        assertTrue(after.firstVideo in 57.9..60.1, "the first picture after the seek to 60 s is at ${after.firstVideo} s")
        assertTrue(after.firstAudio in 57.5..60.5, "the first sound after the seek is at ${after.firstAudio} s")
    }

    @Test
    fun aManifestSentWithItsOwnTypePlaysThroughTheAutomaticTransport() = withAutomaticSource("typed") { source ->
        val read = source.readFor(seconds = 2.0)
        assertTrue(read.video > 30 && read.audio > 50, "${read.video} video and ${read.audio} audio packets arrived")
    }

    @Test
    fun aLiveManifestWithNoExtensionPlaysThroughTheAutomaticTransport() = runBlocking {
        liveManifest = liveManifest(availabilityStart = Instant.now().minusSeconds(30))
        val reader = KtorMediaIoResolver(client).resolve("$root/edge")
        val source = KiteFFmpegSourceFactory().open(MediaItem("$root/edge", io = { checkNotNull(reader) }))
        try {
            assertNull(source.duration, "a live presentation has no duration")
            source.selectStreams(source.streams.map { it.index }.toSet())
            val read = source.readFor(seconds = 2.0)
            assertTrue(read.video > 30, "only ${read.video} video packets arrived from the live edge")
            assertTrue("single-0-13.m4s" in asked && "single-0-12.m4s" !in asked, "playback did not start near the live edge: $asked")
        } finally {
            source.close()
        }
    }

    private companion object {
        /** A WebVTT set of one file, as packagers write subtitles that need no segments. */
        const val SUBTITLE_SET = """<AdaptationSet contentType="text" mimeType="text/vtt" lang="de">
            <Representation id="de" bandwidth="1000"><BaseURL>subs.vtt</BaseURL></Representation>
        </AdaptationSet>"""

        /** A sidecar TTML set, as broadcast packagers write subtitles (#402). */
        const val TTML_SET = """<AdaptationSet contentType="text" mimeType="application/ttml+xml" lang="es">
            <Representation id="es" bandwidth="1000"><BaseURL>subs.ttml</BaseURL></Representation>
        </AdaptationSet>"""

        /** TTML in MP4, one file found through its segment index (#402). */
        const val STPP_SET = """<AdaptationSet contentType="text" mimeType="application/mp4" codecs="stpp" lang="fr">
            <Representation id="fr" bandwidth="1000"><BaseURL>subs-stpp.mp4</BaseURL><SegmentBase/></Representation>
        </AdaptationSet>"""

        /** The same cues as [SUBTITLES], in TTML. */
        val TTML: String = buildString {
            fun clock(seconds: Int, millis: Int) = "00:%02d:%02d.%03d".format(seconds / 60, seconds % 60, millis)
            append("""<?xml version="1.0" encoding="utf-8"?><tt xmlns="http://www.w3.org/ns/ttml"><body><div>""")
            for (cue in 0 until 35) {
                append("""<p begin="${clock(cue * 2, 0)}" end="${clock(cue * 2, 900)}">Línea ${cue + 1}</p>""")
            }
            append("</div></body></tt>")
        }

        /** A cue every two seconds across the seventy. */
        val SUBTITLES: String = buildString {
            fun clock(seconds: Int, millis: Int) = "00:%02d:%02d.%03d".format(seconds / 60, seconds % 60, millis)
            append("WEBVTT\n\n")
            for (cue in 0 until 35) {
                append("${clock(cue * 2, 0)} --> ${clock(cue * 2, 900)}\n")
                append("Zeile ${cue + 1}\n\n")
            }
        }
    }

    /** A live manifest over the numbered set's segments, its presentation started at [availabilityStart]. */
    private fun liveManifest(availabilityStart: Instant): String = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="$availabilityStart"
             minimumUpdatePeriod="PT2S" timeShiftBufferDepth="PT20S">
            <Period id="0" start="PT0S">
                <AdaptationSet contentType="video" mimeType="video/mp4">
                    <Representation id="0" codecs="avc1.42c00d" bandwidth="300000" width="320" height="180">
                        <SegmentTemplate timescale="1000000" duration="2000000" startNumber="1"
                            initialization="single-${'$'}RepresentationID${'$'}-init.m4s"
                            media="single-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s"/>
                    </Representation>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    /** The single files as an on-demand manifest: the video's index found by its boxes, the sound's named by range. */
    private fun onDemandManifest(): String {
        val index = indexOf(File(media, "ondemand-audio.mp4").readBytes())
        return """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT70S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/mp4">
                        <Representation id="v" codecs="avc1.42c00d" bandwidth="300000" width="320" height="180">
                            <BaseURL>ondemand-video.mp4</BaseURL>
                            <SegmentBase/>
                        </Representation>
                    </AdaptationSet>
                    <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
                        <Representation id="a" codecs="mp4a.40.2" bandwidth="96000">
                            <BaseURL>ondemand-audio.mp4</BaseURL>
                            <SegmentBase indexRange="${index.first}-${index.last}"><Initialization range="0-${index.first - 1}"/></SegmentBase>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()
    }

    /** The bytes of the `sidx` box among the top-level boxes of [file]. */
    private fun indexOf(file: ByteArray): IntRange {
        var at = 0
        while (at + 8 <= file.size) {
            val size = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (file[at + i].toInt() and 0xFF) }
            val type = String(file, at + 4, 4, Charsets.ISO_8859_1)
            if (type == "sidx") return at until at + size
            at += size
        }
        error("no sidx box")
    }

    /** [path] played as the default player stack plays an address: through the automatic transport's resolver. */
    private fun withAutomaticSource(path: String, test: suspend (PlayerMediaSource) -> Unit) = runBlocking {
        val reader = KtorMediaIoResolver(client).resolve("$root/$path")
        val source = KiteFFmpegSourceFactory().open(MediaItem("$root/$path", io = { checkNotNull(reader) }))
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            test(source)
        } finally {
            source.close()
        }
    }

    private fun withSource(manifest: String, test: suspend (PlayerMediaSource) -> Unit) = runBlocking {
        val source = KiteFFmpegSourceFactory().open(Dash.mediaItemFor("$root/$manifest", client))
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            test(source)
        } finally {
            source.close()
        }
    }

    private class Read(
        val video: Int,
        val audio: Int,
        val subtitleTimes: List<Double>,
        val firstVideo: Double,
        val firstAudio: Double,
        val lastVideo: Double,
    )

    /** Packets until [seconds] of video have passed the first one read, with the first and last times seen. */
    private suspend fun PlayerMediaSource.readFor(seconds: Double): Read {
        val kinds = streams.associate { it.index to it.kind }
        var video = 0
        var audio = 0
        val subtitles = mutableListOf<Double>()
        var firstVideo = Double.NaN
        var firstAudio = Double.NaN
        var lastVideo = Double.NaN
        while (true) {
            val packet = readPacket() ?: break
            packet.use {
                val at = (it.pts?.micros ?: return@use) / 1e6
                when (kinds[it.streamIndex]) {
                    TrackKind.Video -> {
                        video++
                        if (firstVideo.isNaN()) firstVideo = at
                        lastVideo = if (lastVideo.isNaN()) at else maxOf(lastVideo, at)
                    }
                    TrackKind.Audio -> {
                        audio++
                        if (firstAudio.isNaN()) firstAudio = at
                    }
                    TrackKind.Subtitle -> subtitles += at
                    null -> {}
                }
            }
            if (!firstVideo.isNaN() && lastVideo - firstVideo >= seconds) break
        }
        return Read(video, audio, subtitles, firstVideo, firstAudio, lastVideo)
    }
}
