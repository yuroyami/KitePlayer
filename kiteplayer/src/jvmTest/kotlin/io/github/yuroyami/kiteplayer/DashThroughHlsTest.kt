package io.github.yuroyami.kiteplayer

import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSource
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSourceFactory
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.github.yuroyami.kiteplayer.network.dash.Dash
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import kotlin.time.Duration.Companion.seconds

/**
 * DASH played through the HLS path (#295), with the real FFmpeg backend reading the `dash/`
 * fixtures from a local HTTP server that answers range requests: separate video and audio sets,
 * one numbered set, single files whose segment index names their fragments, and a live manifest
 * written over the numbered set's segments, and the same layouts in WebM (#401). The automatic
 * transport plays the manifests from addresses with no extension and no type (#400). Several
 * Periods play as one presentation, in fMP4, WebM and MPEG-TS (#403), and a live manifest counts
 * its window on the clock its UTCTiming names (#404).
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
    private var livePeriods: List<String> = emptyList()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/")
            asked += path
            val body = when {
                path == "live.mpd" || path == "edge" -> liveManifest?.encodeToByteArray()
                // A time server a minute ahead of this machine's clock (#404).
                path == "time" -> Instant.now().plusSeconds(60).toString().encodeToByteArray()
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
                // The first fetch names one Period; every refresh after it adds the next (#403).
                path == "live-periods.mpd" -> livePeriods.getOrNull(minOf(asked.count { it == path }, livePeriods.size) - 1)?.encodeToByteArray()
                // The sound set named and given roles, and a forced subtitle set beside it (#404).
                path == "named.mpd" -> File(media, "separate.mpd").readText()
                    .replace(
                        """contentType="audio" startWithSAP="1" segmentAlignment="true" bitstreamSwitching="true">""",
                        """contentType="audio" lang="en" startWithSAP="1" segmentAlignment="true" bitstreamSwitching="true">""" +
                            """<Label>English, described</Label><Role schemeIdUri="urn:mpeg:dash:role:2011" value="description"/>""",
                    )
                    .replace("</Period>", FORCED_SET + "</Period>").encodeToByteArray()
                // A thumbnail set beside the picture and the sound, by number and by a timeline far into its media's time (#433).
                path == "thumbs.mpd" -> File(media, "separate.mpd").readText()
                    .replace("</Period>", "$THUMBNAIL_SET</Period>").encodeToByteArray()
                path == "thumbs-offset.mpd" -> File(media, "separate.mpd").readText()
                    .replace("</Period>", "$OFFSET_THUMBNAIL_SET</Period>").encodeToByteArray()
                path.startsWith("thumb-") -> thumbnailImage(path)
                path == "periods.mpd" -> periodsManifest(listOf("a", "b", "c"), PeriodLayout.Mp4).encodeToByteArray()
                path == "webm-periods.mpd" -> periodsManifest(listOf("a", "b"), PeriodLayout.Webm).encodeToByteArray()
                path == "ts-periods.mpd" -> periodsManifest(listOf("a", "b"), PeriodLayout.Ts).encodeToByteArray()
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
    fun aThumbnailSetGivesTheTileForAPosition() = thumbnailsOf("thumbs.mpd", "thumb-1.jpg")

    @Test
    fun aThumbnailSetFarIntoItsMediasTimeGivesTheSameTile() = thumbnailsOf("thumbs-offset.mpd", "thumb-86400.jpg")

    /** The picture for 35 s of [manifest]'s ten tiles by one over 100 s: the fourth tile of [image] (#433). */
    private fun thumbnailsOf(manifest: String, image: String) = runBlocking {
        val session = io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend().open(Dash.mediaItemFor("$root/$manifest", client))
        try {
            val thumbnails = kotlin.test.assertNotNull(session.source.thumbnails, "the thumbnail set is not offered")
            assertEquals(io.github.yuroyami.kiteplayer.ThumbnailSet(width = 160, height = 90), thumbnails.set)
            assertTrue(asked.none { it.startsWith("thumb-") }, "an image was read before one was asked for")
            val picture = kotlin.test.assertNotNull(thumbnails.at(Pts(35_000_000)))
            kotlin.test.assertContentEquals(thumbnailImage(image), picture.image)
            assertEquals(480, picture.x, "the fourth tile")
            assertEquals(0, picture.y)
            assertEquals(160, picture.width)
            assertEquals(90, picture.height)
            assertEquals(30.seconds, picture.start)
            assertEquals(40.seconds, picture.end)
            thumbnails.at(Pts(12_000_000))
            assertEquals(1, asked.count { it == image }, "the image was read once")
        } finally {
            session.close()
        }
    }

    @Test
    fun thePlayersBackendListsTheVariantsToo() = runBlocking {
        // The player opens through the backend rather than the source factory, and the variants
        // must reach the track table that way too, or nothing can choose or step one.
        val session = io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend().open(Dash.mediaItemFor("$root/separate.mpd", client))
        try {
            assertEquals(listOf(180, 360), session.source.variants.map { it.height }, "each video representation is a variant")
            assertEquals(1, session.source.selectedVariant, "the larger variant plays")
        } finally {
            session.close()
        }
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
        assertOnTheTwoSecondGrid(read.subtitleTimes)
        assertTrue("subs.vtt" in asked, "the WebVTT file was not read: $asked")
    }

    @Test
    fun aTtmlSetPlaysAsASubtitleRenditionAtItsOwnTimes() = withSource("ttml.mpd") { source ->
        val subtitle = source.streams.singleOrNull { it.kind == TrackKind.Subtitle }
        assertNotNull(subtitle, "the TTML set is not a stream: ${source.streams.map { it.kind to it.codec }}")
        assertEquals("es", subtitle.language)
        val read = source.readFor(seconds = 6.0)
        assertOnTheTwoSecondGrid(read.subtitleTimes)
        assertTrue("subs.ttml" in asked, "the TTML file was not read: $asked")
    }

    @Test
    fun anStppFilePlaysAsASubtitleRenditionAtItsOwnTimes() = withSource("stpp.mpd", needs = "dash/subs-stpp.mp4") { source ->
        val subtitle = source.streams.singleOrNull { it.kind == TrackKind.Subtitle }
        assertNotNull(subtitle, "the stpp set is not a stream: ${source.streams.map { it.kind to it.codec }}")
        assertEquals("fr", subtitle.language)
        val read = source.readFor(seconds = 6.0)
        assertOnTheTwoSecondGrid(read.subtitleTimes)
        assertTrue("subs-stpp.mp4" in asked, "the stpp file was not read: $asked")
    }

    /**
     * The cues arrive at the times the document names, one every two seconds from zero, the first
     * one included. FFmpeg's HLS reader starts a subtitle playlist at the point the reading has
     * reached when the stream is selected, after it read ahead to find the streams, and catches it
     * up by dropping what came before; the cue at zero is still on screen there, so it survives
     * (KiteFFmpeg#126).
     */
    private fun assertOnTheTwoSecondGrid(times: List<Double>) {
        assertTrue(times.size >= 3, "only $times cues arrived in the first 6 s")
        assertTrue(times.first() < 0.1, "the first cue came at ${times.first()} s: the one at zero was dropped")
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
    fun aLiveManifestCountsItsWindowOnItsTimeServersClock() = runBlocking {
        // By this machine's clock the presentation starts in thirty seconds; by the time server's,
        // a minute ahead, it started thirty seconds ago, so segments up to the fifteenth have ended.
        liveManifest = liveManifest(availabilityStart = Instant.now().plusSeconds(30), clock = true)
        val source = KiteFFmpegSourceFactory().open(Dash.mediaItemFor("$root/live.mpd", client))
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            var timedOut = false
            val watchdog = launch(Dispatchers.Default) {
                delay(20_000)
                timedOut = true
                source.interrupt()
            }
            try {
                source.readPacket()?.close()
            } catch (interrupted: Exception) {
                if (!timedOut) throw interrupted
            } finally {
                watchdog.cancel()
            }
            assertTrue("time" in asked, "the time server was not asked: $asked")
            assertTrue("single-0-13.m4s" in asked && "single-0-12.m4s" !in asked, "playback did not start near the time server's live edge: $asked")
        } finally {
            source.close()
        }
    }

    @Test
    fun aSetsLabelAndRolesReachTheTracks() = withSource("named.mpd") { source ->
        val audio = source.streams.single { it.kind == TrackKind.Audio }
        assertEquals("English, described", audio.title, "the set's label names the track")
        assertTrue(audio.isAccessibility, "a description of the picture is an accessibility track")
        assertTrue(audio.isDefault)
        val subtitle = source.streams.single { it.kind == TrackKind.Subtitle }
        assertTrue(subtitle.isForced, "forced subtitles are forced")
        assertEquals("de forced", subtitle.title)
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
    fun threeMp4PeriodsPlayAsOnePresentationWithoutAGapOrAStepBack() = withSource("periods.mpd") { source ->
        assertEquals(60.0, assertNotNull(source.duration).micros / 1e6, 0.5, "the presentation lasts as long as its three Periods")
        val timeline = source.readTimeline()
        assertContinuous(timeline.video, from = 0.0, to = 60.0, "picture")
        assertContinuous(timeline.audio, from = 0.0, to = 60.0, "sound")
        for (name in listOf("a", "b", "c")) {
            assertTrue(asked.any { it.startsWith("period-$name-0-") }, "no picture of Period $name was read: $asked")
        }
        assertTrue("period-a-1-11.m4s" !in asked, "a segment that begins where its Period ends was read")
    }

    @Test
    fun aPeriodOfAnotherSizeDecodesAtItsOwnSizeAndTheOnesAroundItAtTheirs() = withSource("periods.mpd") { source ->
        val video = source.streams.first { it.kind == TrackKind.Video }
        // One decoder across every seek, as the engine keeps one, so it carries each Period's
        // parameter sets into the next one it is asked to decode.
        val decoder = checkNotNull((source as KiteFFmpegSource).videoDecoderFactories().firstNotNullOfOrNull { it.create(video, HwdecPolicy.Off) })
        try {
            val across = source.picturesBetween(decoder, from = 16.0, until = 22.0)
            val before = across.filter { it.first < 19.99 }
            val after = across.filter { it.first >= 19.99 }
            assertTrue(before.size > 60 && after.size > 30, "only ${before.size} pictures before 20 s and ${after.size} after")
            assertEquals(setOf(VideoSize(320, 180)), before.map { it.second }.toSet(), "the first Period decodes at its size")
            assertEquals(setOf(VideoSize(640, 360)), after.map { it.second }.toSet(), "the second Period decodes at its size")
            assertEquals(20.0, after.first().first, 0.04, "the second Period's first picture is where the Period begins")
            val third = source.picturesBetween(decoder, from = 50.0, until = 52.0, generation = 2)
            assertEquals(setOf(VideoSize(320, 180)), third.map { it.second }.toSet(), "the third Period decodes at its size")
            source.picturesBetween(decoder, from = 30.0, until = 31.0, generation = 3)
            val first = source.picturesBetween(decoder, from = 4.0, until = 5.0, generation = 4)
            assertEquals(setOf(VideoSize(320, 180)), first.map { it.second }.toSet(), "the first Period decodes at its size again")
        } finally {
            decoder.close()
        }
    }

    @Test
    fun twoWebmPeriodsPlayAsOnePresentation() = withSource("webm-periods.mpd") { source ->
        assertEquals(40.0, assertNotNull(source.duration).micros / 1e6, 0.5)
        val timeline = source.readTimeline()
        assertContinuous(timeline.video, from = 0.0, to = 40.0, "picture")
        assertContinuous(timeline.audio, from = 0.0, to = 40.0, "sound")
        // A WebM stream read to its end still seeks, which FFmpeg's Matroska reader once refused (KiteFFmpeg#125).
        // Inside a segment rather than on its boundary, which a clip whose media starts a few
        // milliseconds late moves past the target (#539).
        source.seekToKeyframe(Pts(31_000_000))
        val after = source.readFor(seconds = 1.0)
        assertTrue(after.firstVideo in 29.9..30.1, "the first picture after the seek to 31 s is at ${after.firstVideo} s")
        // VP9 states its size in every keyframe, so the second Period's header need not reach the decoder.
        val video = source.streams.first { it.kind == TrackKind.Video }
        val decoder = checkNotNull((source as KiteFFmpegSource).videoDecoderFactories().firstNotNullOfOrNull { it.create(video, HwdecPolicy.Off) })
        try {
            val across = source.picturesBetween(decoder, from = 16.0, until = 22.0, generation = 2)
            assertEquals(setOf(VideoSize(320, 180)), across.filter { it.first < 19.99 }.map { it.second }.toSet())
            assertEquals(setOf(VideoSize(640, 360)), across.filter { it.first >= 19.99 }.map { it.second }.toSet())
        } finally {
            decoder.close()
        }
    }

    @Test
    fun aWebmSetReadToItsEndSeeksBackToTheMiddle() = withSource("webm.mpd") { source ->
        val timeline = source.readTimeline()
        assertTrue(timeline.video.last() > 69.0, "the reading stopped at ${timeline.video.last()} s, short of the end")
        source.seekToKeyframe(Pts(30_000_000))
        val after = source.readFor(seconds = 2.0)
        assertTrue(after.firstVideo in 27.9..30.1, "the first picture after the seek to 30 s is at ${after.firstVideo} s")
        assertTrue(after.firstAudio in 27.5..30.5, "the first sound after the seek is at ${after.firstAudio} s")
    }

    @Test
    fun twoMpegTsPeriodsPlayAsOnePresentation() = withSource("ts-periods.mpd") { source ->
        assertEquals(40.0, assertNotNull(source.duration).micros / 1e6, 0.5)
        val timeline = source.readTimeline()
        assertContinuous(timeline.video, from = 0.0, to = 40.0, "picture")
        assertContinuous(timeline.audio, from = 0.0, to = 40.0, "sound")
        // Inside a segment: FFmpeg 6.1 starts each Period's picture 21 ms late, behind the AAC
        // encoder's start-up samples, which moves the boundary at 30 s past a target there (#539).
        source.seekToKeyframe(Pts(31_000_000))
        val after = source.readFor(seconds = 1.0)
        assertTrue(after.firstVideo in 29.9..30.1, "the first picture after the seek to 31 s is at ${after.firstVideo} s")
    }


    @Test
    fun aLivePresentationPlaysOnIntoAPeriodThatARefreshAdds() = runBlocking {
        // Sixteen seconds in: the first Period, which ends at twenty, has had eight segments, and
        // the manifest names no second Period until it is fetched again.
        val start = Instant.now().minusSeconds(16)
        livePeriods = listOf(
            periodsManifest(listOf("a"), PeriodLayout.Mp4, availabilityStart = start),
            periodsManifest(listOf("a", "b"), PeriodLayout.Mp4, availabilityStart = start),
        )
        val source = KiteFFmpegSourceFactory().open(Dash.mediaItemFor("$root/live-periods.mpd", client))
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            val kinds = source.streams.associate { it.index to it.kind }
            val video = ArrayList<Double>()
            // A live stream that stops growing waits inside the read for ever, so the deadline interrupts it.
            var timedOut = false
            val watchdog = launch(Dispatchers.Default) {
                delay(30_000)
                timedOut = true
                source.interrupt()
            }
            try {
                while (video.size < 2 || video.last() - video.first() < 12.0) {
                    val packet = try {
                        source.readPacket()
                    } catch (interrupted: Exception) {
                        if (timedOut) null else throw interrupted
                    } ?: break
                    packet.use { if (kinds[it.streamIndex] == TrackKind.Video) it.pts?.let { pts -> video += pts.micros / 1e6 } }
                }
            } finally {
                watchdog.cancel()
            }
            assertTrue(video.size > 2 && video.last() - video.first() >= 12.0, "the stream stopped after ${video.lastOrNull()?.minus(video.first())} s: $asked")
            assertTrue("period-b-0-2.m4s" in asked, "the Period the refresh added was not played: $asked")
            assertContinuous(video, from = video.first(), to = video.last(), "picture")
        } finally {
            source.close()
        }
    }

    /** [times] run from about [from] to about [to] seconds, never step back, and never leave a hole. */
    private fun assertContinuous(times: List<Double>, from: Double, to: Double, what: String) {
        assertTrue(times.isNotEmpty(), "no $what at all")
        assertEquals(from, times.first(), 0.1, "the first $what")
        assertEquals(to, times.last(), 0.2, "the last $what")
        // The sound of each Period starts with its encoder's priming, a frame or so before its media time 0.
        val steps = times.zipWithNext()
        steps.firstOrNull { (a, b) -> b < a - 0.03 }?.let { (a, b) -> error("the $what steps back from $a s to $b s") }
        steps.firstOrNull { (a, b) -> b - a > 0.1 }?.let { (a, b) -> error("the $what has a hole from $a s to $b s") }
    }

    private class Timeline(val video: List<Double>, val audio: List<Double>)

    /** Every packet's time to the end, picture and sound apart. */
    private suspend fun PlayerMediaSource.readTimeline(): Timeline {
        val kinds = streams.associate { it.index to it.kind }
        val video = ArrayList<Double>()
        val audio = ArrayList<Double>()
        while (true) {
            val packet = readPacket() ?: break
            packet.use {
                val at = (it.pts?.micros ?: return@use) / 1e6
                when (kinds[it.streamIndex]) {
                    TrackKind.Video -> video += at
                    TrackKind.Audio -> audio += at
                    else -> {}
                }
            }
        }
        return Timeline(video, audio)
    }

    /**
     * Each picture [decoder] gives from the keyframe before [from] seconds to [until], with its time
     * and size. A [generation] above the first flushes it first, as the engine does at a seek.
     */
    private suspend fun PlayerMediaSource.picturesBetween(decoder: VideoDecoder, from: Double, until: Double, generation: Long = 1): List<Pair<Double, VideoSize>> {
        val video = streams.first { it.kind == TrackKind.Video }
        val out = ArrayList<Pair<Double, VideoSize>>()
        if (generation > 1) decoder.flush(Generation(generation))
        seekToKeyframe(Pts((from * 1e6).toLong()))
        suspend fun drain() {
            while (true) decoder.receive()?.use { out += it.pts.micros / 1e6 to it.size } ?: break
        }
        while (out.lastOrNull()?.first?.let { it < until } != false) {
            val packet = readPacket() ?: break
            packet.use {
                if (it.streamIndex != video.index) return@use
                while (!decoder.send(it)) drain()
            }
            drain()
        }
        return out
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
        /** Ten tiles of 160x90 by one, each image 100 s, by number (#433). */
        const val THUMBNAIL_SET = """<AdaptationSet id="9" contentType="image" mimeType="image/jpeg">
            <SegmentTemplate media="thumb-${'$'}Number${'$'}.jpg" duration="100" startNumber="1" timescale="1"/>
            <Representation id="thumbs" bandwidth="16000" width="1600" height="90">
              <EssentialProperty schemeIdUri="http://dashif.org/thumbnail_tile" value="10x1"/>
            </Representation>
          </AdaptationSet>"""

        /** The same tiles, by a timeline whose media time starts a day in (#433). */
        const val OFFSET_THUMBNAIL_SET = """<AdaptationSet id="9" contentType="image" mimeType="image/jpeg">
            <SegmentTemplate media="thumb-${'$'}Time${'$'}.jpg" timescale="1" presentationTimeOffset="86400">
              <SegmentTimeline><S t="86400" d="100"/></SegmentTimeline>
            </SegmentTemplate>
            <Representation id="thumbs" bandwidth="16000" width="1600" height="90">
              <EssentialProperty schemeIdUri="http://dashif.org/guidelines/thumbnail_tile" value="10x1"/>
            </Representation>
          </AdaptationSet>"""

        /** A WebVTT set of one file, as packagers write subtitles that need no segments. */
        const val SUBTITLE_SET = """<AdaptationSet contentType="text" mimeType="text/vtt" lang="de">
            <Representation id="de" bandwidth="1000"><BaseURL>subs.vtt</BaseURL></Representation>
        </AdaptationSet>"""

        /** The WebVTT set marked as forced subtitles, as a packager marks signs and foreign dialogue (#404). */
        const val FORCED_SET = """<AdaptationSet contentType="text" mimeType="text/vtt" lang="de">
            <Role schemeIdUri="urn:mpeg:dash:role:2011" value="forced-subtitle"/>
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

    /**
     * A live manifest over the numbered set's segments, its presentation started at
     * [availabilityStart], whose time of day is the time server's when [clock] says so.
     */
    private fun liveManifest(availabilityStart: Instant, clock: Boolean = false): String = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="$availabilityStart"
             minimumUpdatePeriod="PT2S" timeShiftBufferDepth="PT20S">
            ${if (clock) """<UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-xsdate:2014" value="time"/>""" else ""}
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

    private enum class PeriodLayout { Mp4, Webm, Ts }

    /**
     * The Period fixtures [names] one after another, twenty seconds each, as an ad-stitched
     * presentation is: each Period's media time starts from zero again, and each second Period's
     * picture is another size, so an MP4 one's parameter sets differ (#403). Live from
     * [availabilityStart] when it is given.
     */
    private fun periodsManifest(names: List<String>, layout: PeriodLayout, availabilityStart: Instant? = null): String = buildString {
        val id = "\$RepresentationID\$"
        val number = "\$Number\$"
        fun template(media: String, init: String?) =
            """<SegmentTemplate timescale="1000000" duration="2000000" startNumber="1" media="$media"""" +
                (init?.let { """ initialization="$it"""" } ?: "") + "/>"
        if (availabilityStart == null) {
            append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT${names.size * 20}S">""")
        } else {
            append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="$availabilityStart" """)
            append("""minimumUpdatePeriod="PT2S" timeShiftBufferDepth="PT20S">""")
        }
        for ((index, name) in names.withIndex()) {
            append("""<Period id="$name" start="PT${index * 20}S" duration="PT20S">""")
            when (layout) {
                PeriodLayout.Mp4 -> {
                    val (width, height) = if (name == "b") 640 to 360 else 320 to 180
                    append("""<AdaptationSet id="0" contentType="video" mimeType="video/mp4">""")
                    append("""<Representation id="0" codecs="avc1.42c01e" bandwidth="300000" width="$width" height="$height">""")
                    append(template("period-$name-$id-$number.m4s", "period-$name-$id-init.m4s"))
                    append("</Representation></AdaptationSet>")
                    append("""<AdaptationSet id="1" contentType="audio" mimeType="audio/mp4" lang="en">""")
                    append("""<Representation id="1" codecs="mp4a.40.2" bandwidth="96000">""")
                    append(template("period-$name-$id-$number.m4s", "period-$name-$id-init.m4s"))
                    append("</Representation></AdaptationSet>")
                }
                PeriodLayout.Webm -> {
                    val (width, height) = if (name == "b") 640 to 360 else 320 to 180
                    append("""<AdaptationSet id="0" contentType="video" mimeType="video/webm">""")
                    append("""<Representation id="0" codecs="vp09.00.11.08" bandwidth="300000" width="$width" height="$height">""")
                    append(template("webm-period-$name-$id-$number.webm", "webm-period-$name-$id-init.webm"))
                    append("</Representation></AdaptationSet>")
                    append("""<AdaptationSet id="1" contentType="audio" mimeType="audio/webm">""")
                    append("""<Representation id="1" codecs="opus" bandwidth="64000">""")
                    append(template("webm-period-$name-$id-$number.webm", "webm-period-$name-$id-init.webm"))
                    append("</Representation></AdaptationSet>")
                }
                PeriodLayout.Ts -> {
                    append("""<AdaptationSet id="0" contentType="video" mimeType="video/mp2t">""")
                    append("""<Representation id="0" codecs="avc1.42c00d,mp4a.40.2" bandwidth="400000" width="320" height="180">""")
                    append(template("ts-period-$name-$number.ts", null))
                    append("</Representation></AdaptationSet>")
                }
            }
            append("</Period>")
        }
        append("</MPD>")
    }

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

    /** The bytes of a thumbnail image the server serves at [path]: a JPEG's first bytes, then the path. */
    private fun thumbnailImage(path: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + path.encodeToByteArray()

    private fun withSource(
        manifest: String,
        needs: String? = null,
        test: suspend (PlayerMediaSource) -> Unit,
    ) = runBlocking {
        if (needs != null) assumeMade(needs)
        val source = KiteFFmpegSourceFactory().open(Dash.mediaItemFor("$root/$manifest", client))
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            test(source)
        } finally {
            source.close()
        }
    }

    /**
     * Skips the test, with the generator's own reason, when `scripts/testmedia.sh` listed [fixture]
     * in its MANIFEST as one the ffmpeg on that machine cannot make (#418), or fails it where the
     * job says it generated every clip (#419). A fixture missing for any other reason fails the test.
     */
    private fun assumeMade(fixture: String) {
        val testmedia = media.parentFile
        val skipped = File(testmedia, "MANIFEST.txt").takeIf { it.isFile }?.readLines().orEmpty()
            .map { it.removePrefix("skipped:").trim() to it.startsWith("skipped:") }
            .firstOrNull { (entry, isSkip) -> isSkip && entry.startsWith("$fixture:") }?.first
        requireTestMedia(skipped == null, "testmedia.sh skipped $skipped")
        check(File(testmedia, fixture).isFile) { "$fixture is missing; run scripts/testmedia.sh" }
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
