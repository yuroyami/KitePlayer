@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.worker

import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.KitePlayerWorker
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.SubtitleSource
import io.github.yuroyami.kiteplayer.TrackChange
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.view.WebPictureInPictureMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.js.JsAny
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A worker player plays a clip from the network while the page keeps drawing (#100).
 *
 * karma.config.d/worker.js serves the worker binary, the codec module and the repository's clips.
 * The clip is opened by its address, so the worker reads it with range requests, which is the read
 * the page's own player cannot make. A `requestAnimationFrame` loop runs on the page through the
 * open, the playing, a seek, a pause and the close, and records the longest gap between two of its
 * frames. The bound is 50 ms, three frames at 60 Hz, which is where a viewer sees a stall.
 *
 * Measured in headless Chromium on 2026-10-02: no gap over 30 ms with the worker. The same loop
 * around the page's own player, playing the same clip from memory, had its longest gap during the
 * open, 537 to 668 ms in three runs, and another of 114 to 141 ms just after it. So the bound can
 * fail.
 *
 * The loop starts once [KitePlayerWorker.start] has returned. Creating the page's first audio
 * context costs the browser 34 to 43 ms on its own, once, for any page that plays sound, and the
 * first gap measured over that was once 92 ms. That is the browser's work, not the player's.
 *
 * Node has no page, no worker of this kind and no canvas, so the node half of the task skips this.
 */
class KitePlayerWorkerBrowserTest {

    /**
     * A cancelled open leaves the worker player Idle and ready for the next, as on every other
     * platform (#893). The page stopped waiting and told the worker nothing, so the worker went on
     * opening the item, and the player that should be Idle reached Paused and refused the next open.
     * A live DASH open blocks the worker's one thread for tens of seconds, so the cancel arrives
     * after the open has finished there, the hardest case.
     */
    @Test
    fun aCancelledOpenLeavesTheWorkerPlayerIdleAndReadyForAnother() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val probe = probeOf(media)
        withContext(Dispatchers.Default) {
            val player = KitePlayerWorker.start(pageCanvas(320, 180), workerUrl, codecUrl)
            try {
                fetchText("$probe/reset").await<JsString>()
                val slow = launch { player.open(MediaItem("$probe/live.mpd")) }
                withTimeout(30.seconds) { while (asked(probe).manifests == 0) delay(100) }
                slow.cancelAndJoin()
                withTimeout(90.seconds) { player.state.first { it.status != PlaybackStatus.Idle } }
                val settled = withTimeout(90.seconds) { player.state.first { it.status == PlaybackStatus.Idle } }
                assertNull(settled.media, "the cancelled item is still the player's")

                player.open(MediaItem("$media/sync1080p30.mp4"))
                val opened = withTimeout(30.seconds) { player.state.first { it.duration != null } }
                assertEquals(10.seconds, opened.duration)
            } finally {
                player.closeAndAwait()
            }
        }
    }

    @Test
    fun aNetworkClipPlaysInTheWorkerWhileThePageKeepsItsFrames() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        // Real time rather than the test scheduler's: everything here waits on another thread.
        withContext(Dispatchers.Default) {
            val canvas = pageCanvas(320, 180)
            val player = KitePlayerWorker.start(canvas, workerUrl, codecUrl)
            val heartbeat = heartbeatStart()
            val events = Channel<PlayerEvent>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch {
                player.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) }
            }
            try {
                subscribed.await()
                player.setViewport(320, 180, 1f)
                player.open(MediaItem("$media/sync1080p30.mp4"))
                val opened = withTimeout(30.seconds) { player.state.first { it.duration != null } }
                assertEquals(10.seconds, opened.duration, "the worker reports the clip's duration")
                assertTrue(opened.seekable, "a clip read with range requests can seek")

                player.play()
                withTimeout(60.seconds) { player.progress.first { it.position >= 2.seconds } }
                withTimeout(30.seconds) {
                    while (events.receive() !is PlayerEvent.FirstFrameRendered) Unit
                }

                player.seek(6.seconds)
                withTimeout(60.seconds) { player.progress.first { it.position >= 7.seconds } }

                player.pause()
                withTimeout(30.seconds) { player.state.first { it.status == PlaybackStatus.Paused } }
            } finally {
                collector.cancel()
                player.closeAndAwait()
            }
            val frames = heartbeatFrames(heartbeat)
            val worst = heartbeatStop(heartbeat)
            println("WORKER heartbeat: $frames frames, longest gap ${worst.toInt()} ms")
            // A page that is hidden draws no frames at all, and would pass any bound.
            assertTrue(frames > 60, "the page drew only $frames frames, so the gaps measure nothing")
            assertTrue(worst < 50.0, "the page went ${worst.toInt()} ms without a frame while the worker played")
        }
    }

    /**
     * An HLS stream plays in the worker (#546). The worker's reader opens the variant playlist and
     * each segment that the master names with a synchronous request of its own, which the codec
     * module asks for while it reads. `hls/ts.m3u8` has a 320x180 and a 640x360 variant in MPEG-TS
     * segments of two seconds, twelve seconds long.
     *
     * With the related opens taken out of the web backend, the open fails and this test with it. The
     * page's output carries the segments' tone, so the sound that plays is not silence.
     */
    @Test
    fun anHlsStreamPlaysSeeksAndChangesVariantInTheWorker() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        withContext(Dispatchers.Default) {
            val canvas = pageCanvas(320, 180)
            val ear = listenToAudio()
            val player = KitePlayerWorker.start(canvas, workerUrl, codecUrl)
            val events = Channel<PlayerEvent>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch {
                player.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) }
            }
            try {
                subscribed.await()
                player.setViewport(320, 180, 1f)
                player.open(MediaItem("$media/hls/ts.m3u8"))
                val opened = withTimeout(30.seconds) { player.state.first { it.duration != null } }
                assertEquals(12.seconds, opened.duration, "the worker reports the stream's duration")
                assertTrue(opened.seekable, "a stream that has ended can seek")
                assertEquals(listOf(180, 360), opened.tracks.variants.map { it.height }, "both variants of the master are listed")
                assertEquals(listOf(TrackKind.Video, TrackKind.Audio), opened.tracks.all.map { it.kind }, "the picture and the sound of the segments")

                player.play()
                withTimeout(30.seconds) {
                    while (events.receive() !is PlayerEvent.FirstFrameRendered) Unit
                }
                withTimeout(60.seconds) { player.progress.first { it.position >= 1.seconds } }
                assertHeard(ear, "the 440 Hz tone of the stream's segments")

                val other = opened.tracks.variants.first { it.index != opened.tracks.selectedVariant }.index
                player.selectVariant(other)
                withTimeout(30.seconds) { player.state.first { it.tracks.selectedVariant == other } }
                withTimeout(60.seconds) { player.progress.first { it.position >= 3.seconds } }

                player.seek(8.seconds)
                withTimeout(60.seconds) { player.progress.first { it.position >= 9.seconds } }
                assertNull(player.state.value.error, "the stream plays on after the seek")
            } finally {
                collector.cancel()
                player.closeAndAwait()
                stopListening(ear)
            }
        }
    }

    /**
     * An fMP4 stream under AES-128 plays in the worker and changes variant in place (#546, #565).
     * `hls/ladder-aes.m3u8` has 640x360, 1280x720 and 1920x1080, each two seconds a segment. The
     * worker decrypts each segment and, after the change, writes it again for the first variant's
     * initialization, all in Kotlin compiled for the browser.
     */
    @Test
    fun anEncryptedMp4StreamChangesVariantInTheWorker() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        withContext(Dispatchers.Default) {
            val player = KitePlayerWorker.start(pageCanvas(320, 180), workerUrl, codecUrl)
            val events = Channel<PlayerEvent>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch {
                player.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) }
            }
            try {
                subscribed.await()
                player.setViewport(320, 180, 1f)
                player.open(MediaItem("$media/hls/ladder-aes.m3u8", demux = DemuxPolicy(maxVideoHeight = 400)))
                val opened = withTimeout(30.seconds) { player.state.first { it.duration != null } }
                assertEquals(listOf(360, 720, 1080), opened.tracks.variants.map { it.height })
                assertEquals(0, opened.tracks.selectedVariant, "the item asked for at most 400 lines")

                player.play()
                withTimeout(30.seconds) {
                    while (events.receive() !is PlayerEvent.FirstFrameRendered) Unit
                }
                player.selectVariant(1)
                withTimeout(30.seconds) { player.state.first { it.tracks.selectedVariant == 1 } }
                // Past two segment boundaries, so segments of the second variant have played.
                withTimeout(90.seconds) { player.progress.first { it.position >= 5.seconds } }
                assertNull(player.state.value.error, "the stream plays on in the other variant")
            } finally {
                collector.cancel()
                player.closeAndAwait()
            }
        }
    }

    /**
     * A DASH presentation plays in the worker (#546). `dash/separate.mpd` is 70 seconds long, with
     * a 320x180 and a 640x360 picture in one set and the sound in a set of its own, each in
     * fragmented MP4 segments of two seconds that a template names. The worker's reader recognises
     * the manifest by its address, and the DASH door serves it to the codec module as HLS, with
     * every segment read by a synchronous request.
     *
     * The seek goes to the last ten seconds, which is segment 31 of 35, and the test server's list
     * of requests shows that no segment between the start and the target was read. The page's
     * frame loop runs through all of it under the bound of the first test, 50 ms, and the page's
     * output carries the tone of the sound's own set, so the sound is not silence.
     */
    @Test
    fun aDashPresentationWithSeparateSoundPlaysAndSeeksInTheWorker() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        withContext(Dispatchers.Default) {
            val ear = listenToAudio()
            val player = KitePlayerWorker.start(pageCanvas(320, 180), workerUrl, codecUrl)
            val heartbeat = heartbeatStart()
            val events = Channel<PlayerEvent>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch {
                player.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) }
            }
            try {
                subscribed.await()
                player.setViewport(320, 180, 1f)
                player.open(MediaItem("$media/dash/separate.mpd"))
                val opened = withTimeout(30.seconds) { player.state.first { it.duration != null } }
                val duration = assertNotNull(opened.duration)
                assertTrue(duration in 69.seconds..71.seconds, "the worker reports the presentation's 70 seconds, not $duration")
                assertTrue(opened.seekable, "a presentation that has ended can seek")
                assertEquals(listOf(180, 360), opened.tracks.variants.map { it.height }, "both pictures of the manifest are listed")
                assertEquals(setOf(TrackKind.Video, TrackKind.Audio), opened.tracks.all.map { it.kind }.toSet(), "the picture and the sound of the two sets")
                assertEquals(2, opened.tracks.all.size, "one picture and one sound, whatever the number of variants")

                player.play()
                withTimeout(30.seconds) {
                    while (events.receive() !is PlayerEvent.FirstFrameRendered) Unit
                }
                withTimeout(60.seconds) { player.progress.first { it.position >= 1.seconds } }
                assertHeard(ear, "the 440 Hz tone of the sound's own adaptation set")

                val probe = probeOf(media)
                fetchText("$probe/reset").await<JsString>()
                player.seek(60.seconds)
                val after = withTimeout(60.seconds) { player.progress.first { it.position >= 61.seconds } }
                assertTrue(after.position < 66.seconds, "the position after the seek is on the presentation's own timeline, not ${after.position}")
                assertNull(player.state.value.error, "the presentation plays on after the seek")
                // 60 s is in segment 31. The read-ahead of the start was at segment 7 at most, so a
                // number between that and the target means the seek read its way there.
                val numbers = asked(probe).requests.mapNotNull { Regex("dash/separate-\\d-(\\d+)\\.m4s").matchEntire(it.clip)?.groupValues?.get(1)?.toInt() }
                assertTrue(numbers.any { it >= 30 }, "the segment of the target was read: $numbers")
                assertTrue(numbers.none { it in 8..28 }, "the seek read segments before its target: $numbers")
            } finally {
                collector.cancel()
                player.closeAndAwait()
                stopListening(ear)
            }
            // The page draws on while the worker waits for each request of the presentation.
            val frames = heartbeatFrames(heartbeat)
            val worst = heartbeatStop(heartbeat)
            println("WORKER heartbeat with DASH: $frames frames, longest gap ${worst.toInt()} ms")
            assertTrue(frames > 60, "the page drew only $frames frames, so the gaps measure nothing")
            assertTrue(worst < 50.0, "the page went ${worst.toInt()} ms without a frame while the worker played DASH")
        }
    }

    /**
     * WebM DASH plays in the worker (#546), in both forms a packager writes. `dash/webm.mpd` names
     * its segments with a template. `dash/webm-ondemand.mpd` names one file for the picture and one
     * for the sound, and the DASH door reads each file's `Cues` to find its clusters, with a
     * request for that byte range alone, and then serves each cluster as a byte range of the file.
     */
    @Test
    fun webmDashPlaysInTheWorkerFromATemplateAndFromAnIndex() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        withContext(Dispatchers.Default) {
            for (manifest in listOf("webm.mpd", "webm-ondemand.mpd")) {
                val player = KitePlayerWorker.start(pageCanvas(320, 180), workerUrl, codecUrl)
                val events = Channel<PlayerEvent>(Channel.UNLIMITED)
                val subscribed = CompletableDeferred<Unit>()
                val collector = launch {
                    player.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) }
                }
                try {
                    subscribed.await()
                    player.setViewport(320, 180, 1f)
                    player.open(MediaItem("$media/dash/$manifest"))
                    val opened = withTimeout(30.seconds) { player.state.first { it.duration != null } }
                    assertTrue(opened.seekable, "$manifest can seek")
                    assertEquals(setOf(TrackKind.Video, TrackKind.Audio), opened.tracks.all.map { it.kind }.toSet(), "$manifest has a picture and a sound")

                    player.play()
                    withTimeout(30.seconds) {
                        while (events.receive() !is PlayerEvent.FirstFrameRendered) Unit
                    }
                    withTimeout(60.seconds) { player.progress.first { it.position >= 1.seconds } }
                    val half = assertNotNull(opened.duration) / 2
                    player.seek(half)
                    withTimeout(60.seconds) { player.progress.first { it.position >= half + 1.seconds } }
                    assertNull(player.state.value.error, "$manifest plays on after the seek")
                } finally {
                    collector.cancel()
                    player.closeAndAwait()
                }
            }
        }
    }

    /**
     * A live HLS playlist plays in the worker past what it listed at the open (#546). The test
     * server lists two segments of `hls/ts-0` at first and one more every two seconds, and ends the
     * playlist after the sixth, so the last four are read only if the worker loads the playlist
     * again.
     */
    @Test
    fun aLiveHlsPlaylistPlaysTheSegmentsAddedAfterTheOpen() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val probe = probeOf(media)
        withContext(Dispatchers.Default) {
            playing(workerUrl, codecUrl, { fetchText("$probe/reset").await<JsString>(); MediaItem("$probe/live.m3u8") }) { player ->
                withTimeout(60.seconds) {
                    while (asked(probe).requests.none { it.clip == "hls/ts-0-5.ts" }) delay(200)
                }
                withTimeout(60.seconds) { player.state.first { it.status == PlaybackStatus.Ended || it.error != null } }
                assertNull(player.state.value.error, "the stream plays to the end the playlist gave it")
                assertEquals((0..5).map { "hls/ts-0-$it.ts" }, asked(probe).requests.map { it.clip }.filter { it.endsWith(".ts") }.distinct(), "every segment was read, in order")
            }
        }
    }

    /**
     * The worker reads a live DASH manifest past the segments of its open (#546). The test
     * server's manifest is `dash/separate` on the clock: its third segment is the newest at the
     * open, and it asks to be fetched again every two seconds. Segment 7 exists eight seconds
     * later, and the test server's clock shows that the worker asked for it then and not before.
     *
     * The test watches the requests and not the picture: the worker waits for each new segment
     * inside the read, so the open of a live stream answers late there (#568).
     */
    @Test
    fun aLiveDashManifestIsReadOnPastTheSegmentsOfItsOpen() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val probe = probeOf(media)
        withContext(Dispatchers.Default) {
            reading(workerUrl, codecUrl, { fetchText("$probe/reset").await<JsString>(); MediaItem("$probe/live.mpd") }) { player ->
                withTimeout(60.seconds) {
                    while (true) {
                        val clips = asked(probe).requests.map { it.clip }
                        if ("dash/separate-0-00007.m4s" in clips && "dash/separate-2-00007.m4s" in clips) break
                        assertNull(player.state.value.error, "the live presentation plays on")
                        delay(200)
                    }
                }
                val all = asked(probe)
                assertTrue(all.manifests >= 2, "the manifest was fetched again while it played")
                // Segment 7 is whole 14 s after the manifest's start, which is 8 s after the reset.
                val seventh = all.requests.first { it.clip == "dash/separate-0-00007.m4s" }
                assertTrue(seventh.atMillis >= 7_500, "segment 7 was asked for ${seventh.atMillis} ms after the open, before it existed")
                assertTrue(all.requests.first { it.clip == "dash/separate-0-00001.m4s" }.atMillis < 4_000, "the first segment was read at the open")
                assertNull(player.state.value.error)
            }
        }
    }

    /**
     * A manifest of three Periods plays in the worker as one presentation of 60 seconds (#546).
     * Each Period has its own initialization, and the second has a larger picture. Play crosses
     * the first join, a seek lands in the third Period, and the picture's size follows the Period.
     */
    @Test
    fun threeDashPeriodsPlayAsOnePresentationInTheWorker() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val probe = probeOf(media)
        withContext(Dispatchers.Default) {
            playing(workerUrl, codecUrl, { fetchText("$probe/reset").await<JsString>(); MediaItem("$probe/periods.mpd") }) { player ->
                val duration = assertNotNull(player.state.value.duration)
                assertTrue(duration in 59.seconds..61.seconds, "the three Periods of 20 seconds are one presentation, not $duration")
                assertEquals(180, player.state.value.videoSize?.height, "the first Period's picture")

                // The first join is at 20 s.
                player.seek(17.seconds)
                val second = withTimeout(60.seconds) { player.progress.first { it.position >= 23.seconds } }
                assertTrue(second.position < 27.seconds, "the position stepped at the join, to ${second.position}")
                withTimeout(10.seconds) { player.state.first { it.videoSize?.height == 360 } }

                player.seek(45.seconds)
                val third = withTimeout(60.seconds) { player.progress.first { it.position >= 46.seconds } }
                assertTrue(third.position < 50.seconds, "the position after the seek is in the third Period, not ${third.position}")
                withTimeout(10.seconds) { player.state.first { it.videoSize?.height == 180 } }

                val clips = asked(probe).requests.map { it.clip }
                for (name in listOf("a", "b", "c")) {
                    assertTrue("dash/period-$name-0-init.m4s" in clips, "the initialization of Period $name was read: $clips")
                }
                assertNull(player.state.value.error)
            }
        }
    }

    /**
     * A live manifest gains a Period while the worker reads it (#546). The test server's manifest
     * names one Period that ends four seconds after the open, and the second Period only from its
     * second fetch on. The second Period ends too, and no third one follows.
     *
     * The stream then stops growing, and the worker waits inside its read for a segment that never
     * comes (#568). The close still returns, because the page ends a worker that does not answer, and the
     * open that was waiting fails.
     */
    @Test
    fun aPeriodThatARefreshAddsIsReadAndAWorkerInsideAReadStillCloses() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val probe = probeOf(media)
        withContext(Dispatchers.Default) {
            var opening: Deferred<Result<Unit>>? = null
            reading(workerUrl, codecUrl, { fetchText("$probe/reset").await<JsString>(); MediaItem("$probe/live-periods.mpd") }) { player ->
                opening = this
                withTimeout(60.seconds) {
                    while ("dash/period-b-0-10.m4s" !in asked(probe).requests.map { it.clip }) {
                        assertNull(player.state.value.error, "the live presentation is read on")
                        delay(200)
                    }
                }
                val clips = asked(probe).requests.map { it.clip }
                assertTrue(clips.count { it == "live-periods.mpd" } >= 2, "the manifest was fetched again: $clips")
                assertTrue(clips.indexOf("dash/period-a-0-10.m4s") < clips.indexOf("dash/period-b-0-init.m4s"), "the first Period was read to its end first: $clips")
                // Nothing follows the second Period, so the next read never returns.
                delay(3.seconds)
                val closing = TimeSource.Monotonic.markNow()
                withTimeout(20.seconds) { player.closeAndAwait() }
                println("WORKER close of a worker inside a read: ${closing.elapsedNow().inWholeMilliseconds} ms")
            }
            val answer = withTimeout(10.seconds) { assertNotNull(opening).await() }
            assertIs<PlaybackException>(answer.exceptionOrNull(), "the open that waited on the worker fails: $answer")
        }
    }

    /**
     * A manifest behind a redirect plays in the worker (#546): its segments are resolved against
     * the address the manifest came from in the end, not the one that was asked.
     */
    @Test
    fun aManifestBehindARedirectResolvesItsSegmentsFromWhereItLanded() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val probe = probeOf(media)
        withContext(Dispatchers.Default) {
            playing(workerUrl, codecUrl, { fetchText("$probe/reset").await<JsString>(); MediaItem("$probe/moved/dash/separate.mpd") }) { player ->
                withTimeout(60.seconds) { player.progress.first { it.position >= 1.seconds } }
                val clips = asked(probe).requests.map { it.clip }
                assertTrue("dash/separate.mpd" in clips, "the redirect was followed: $clips")
                assertEquals(listOf("moved/dash/separate.mpd"), clips.filter { it.startsWith("moved/") }.distinct(), "only the manifest was asked for at the address that moved")
                assertTrue(clips.any { it.startsWith("dash/separate-") && it.endsWith(".m4s") }, "the segments came from beside the manifest: $clips")
                assertNull(player.state.value.error)
            }
        }
    }

    /**
     * A segment whose signed address the server refuses while the stream plays, as a CDN refuses a
     * token that expired, gets the answer of the other targets (#546, #453): the worker's reader
     * hands the 403 over, and the player warns [PlaybackWarning.AddressRenewed] with the segment's
     * file and no token, and opens the item again for a fresh address. The test server's
     * `expiring.m3u8` names six segments of `hls/ts-0` behind `/kite-probe/signed/`, and refuses
     * every one with 403 once the test calls its `expire`. The worker reads the whole stream ahead
     * while it opens, so the addresses expire after the open and a seek asks for a segment again.
     */
    @Test
    fun aSegmentTheServerRefusesIsRenewedAsOnTheOtherTargets() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val probe = probeOf(media)
        withContext(Dispatchers.Default) {
            val player = KitePlayerWorker.start(pageCanvas(320, 180), workerUrl, codecUrl)
            val events = Channel<PlayerEvent>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch {
                player.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) }
            }
            try {
                subscribed.await()
                fetchText("$probe/reset").await<JsString>()
                player.setViewport(320, 180, 1f)
                player.open(MediaItem("$probe/expiring.m3u8"))
                player.play()
                withTimeout(60.seconds) { player.progress.first { it.position >= 1.seconds } }
                fetchText("$probe/expire").await<JsString>()
                val before = asked(probe).requests.size
                player.seek(0.seconds)
                val seen = mutableListOf<PlayerEvent>()
                val renewed = withTimeoutOrNull(60.seconds) {
                    while (true) {
                        val event = events.receive()
                        seen += event
                        val warning = (event as? PlayerEvent.Warning)?.warning
                        if (warning is PlaybackWarning.AddressRenewed) return@withTimeoutOrNull warning
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                assertNotNull(
                    renewed,
                    "no renewal; asked after the expiry: ${asked(probe).requests.drop(before)}; " +
                        "state ${player.state.value.status} ${player.state.value.error}; events ${seen.takeLast(12)}",
                )
                assertEquals(403, renewed.status, "the server's answer travels with the warning")
                assertTrue(Regex("ts-0-\\d\\.ts").matches(renewed.uri), "the refused segment, named without its token: ${renewed.uri}")
                val signed = asked(probe).requests.map { it.clip }.filter { it.startsWith("signed/") }
                assertTrue("signed/ts-0-0.ts" in signed, "the segments were asked for at their signed addresses: $signed")
            } finally {
                collector.cancel()
                player.closeAndAwait()
            }
        }
    }

    /**
     * The headers of an item go to the origin of its own address and to no other (#546). The test
     * server answers on `localhost` and on `127.0.0.1`, which are two origins to a browser, and its
     * manifest names its segments on either.
     */
    @Test
    fun theHeadersOfAnItemGoToItsOwnOriginOnly() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val probe = probeOf(media)
        val own = probe.removeSuffix("/kite-probe")
        val other = if ("//localhost" in own) own.replace("//localhost", "//127.0.0.1") else own.replace("//127.0.0.1", "//localhost")
        assertTrue(other != own, "the test server's address $own has no second name")
        val headers = mapOf("X-Kite-Test" to "mine")
        withContext(Dispatchers.Default) {
            val elsewhere = { MediaItem("$probe/live.mpd?ended=1&origin=${other.replace(":", "%3A").replace("/", "%2F")}", headers = headers) }
            playing(workerUrl, codecUrl, { fetchText("$probe/reset").await<JsString>(); elsewhere() }) { player ->
                withTimeout(60.seconds) { player.progress.first { it.position > 0.seconds } }
                val requests = asked(probe).requests
                assertEquals(listOf("mine"), requests.filter { it.clip == "live.mpd" }.map { it.marked }.distinct(), "the manifest's own requests carry the headers")
                val segments = requests.filter { it.clip.startsWith("dash/separate-") }
                assertTrue(segments.isNotEmpty(), "the segments were read: $requests")
                assertEquals(listOf(other.substringAfter("//")), segments.map { it.host }.distinct(), "the segments were read from the other origin")
                assertEquals(listOf(""), segments.map { it.marked }.distinct(), "another origin was sent the item's headers")
                assertNull(player.state.value.error)
            }
            playing(workerUrl, codecUrl, { fetchText("$probe/reset").await<JsString>(); MediaItem("$probe/live.mpd?ended=1", headers = headers) }) { player ->
                withTimeout(60.seconds) { player.progress.first { it.position > 0.seconds } }
                val segments = asked(probe).requests.filter { it.clip.startsWith("dash/separate-") }
                assertTrue(segments.isNotEmpty())
                assertEquals(listOf("mine"), segments.map { it.marked }.distinct(), "segments on the manifest's own origin carry the headers")
            }
        }
    }

    /**
     * Starts a worker player, opens what [item] gives, plays it to its first picture and runs
     * [check], then closes the player.
     */
    private suspend fun playing(workerUrl: String, codecUrl: String, item: suspend () -> MediaItem, check: suspend (KitePlayerWorker) -> Unit) = coroutineScope {
        val player = KitePlayerWorker.start(pageCanvas(320, 180), workerUrl, codecUrl)
        val events = Channel<PlayerEvent>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch {
            player.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) }
        }
        try {
            subscribed.await()
            player.setViewport(320, 180, 1f)
            player.open(item())
            player.play()
            withTimeout(30.seconds) {
                while (true) {
                    when (val event = events.receive()) {
                        is PlayerEvent.FirstFrameRendered -> break
                        is PlayerEvent.Failed -> throw AssertionError("the player failed before its first picture: ${event.error}")
                        else -> Unit
                    }
                }
            }
            check(player)
        } finally {
            collector.cancel()
            player.closeAndAwait()
        }
    }

    /**
     * Starts a worker player and opens what [item] gives without waiting for the answer, which is
     * the receiver of [check], then closes the player.
     */
    private suspend fun reading(
        workerUrl: String,
        codecUrl: String,
        item: suspend () -> MediaItem,
        check: suspend Deferred<Result<Unit>>.(KitePlayerWorker) -> Unit,
    ) = coroutineScope {
        val player = KitePlayerWorker.start(pageCanvas(320, 180), workerUrl, codecUrl)
        try {
            val media = item()
            async { runCatching { player.open(media) } }.check(player)
        } finally {
            player.closeAndAwait()
        }
    }

    /** The requests for clips that the test server kept since its last reset, and how often it served its live manifest. */
    private class Asked(val requests: List<Request>, val manifests: Int)

    private class Request(val clip: String, val host: String, val range: String, val marked: String, val atMillis: Long) {
        override fun toString(): String = "$clip from $host" + (if (marked.isEmpty()) "" else " marked $marked")
    }

    private suspend fun asked(probe: String): Asked {
        val lines = fetchAsked("$probe/asked").await<JsString>().toString().split("\n")
        val requests = lines.dropLast(1).filter { it.isNotEmpty() }.map { line -> line.split("|").let { Request(it[0], it[1], it[2], it[3], it[4].toLong()) } }
        return Asked(requests, lines.last().toInt())
    }

    /** The test server of karma.config.d/worker.js, which answers beside the clips of [media]. */
    private fun probeOf(media: String): String = media.removeSuffix("/testmedia") + "/kite-probe"

    /**
     * The calls past open, play, pause and seek reach the worker's player and answer as
     * `KitePlayer` does: tracks, a track selection, an external subtitle, setters seen in the
     * state, a setter the player refuses, a queue, and a dump. `subbed.mkv` holds h264, AAC and an
     * ASS track at stream 2, and no chapters.
     */
    @Test
    fun theRestOfThePlayerAnswersFromTheWorker() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val mediaPath = setup[3]
        withContext(Dispatchers.Default) {
            val player = KitePlayerWorker.start(null, workerUrl, codecUrl)
            val events = Channel<PlayerEvent>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch {
                player.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) }
            }
            try {
                subscribed.await()
                player.open(MediaItem("$media/subbed.mkv"))
                val opened = withTimeout(30.seconds) { player.state.first { it.tracks.all.isNotEmpty() } }
                assertEquals(
                    listOf(TrackKind.Video, TrackKind.Audio, TrackKind.Subtitle),
                    opened.tracks.all.map { it.kind },
                    "the worker reports the clip's three tracks",
                )

                val change = player.selectTrack(TrackKind.Subtitle, TrackId(2))
                assertEquals(TrackChange.Applied(TrackKind.Subtitle, TrackId(2)), change)

                // A relative address: the page reads it against its own.
                val external = player.addExternalSubtitle(SubtitleSource("$mediaPath/subs.srt"))
                assertTrue(external.isExternal, "an external subtitle has a negative id, not $external")
                withTimeout(10.seconds) { player.state.first { it.tracks.find(external) != null } }

                player.setSpeed(1.5)
                player.setLoop(LoopMode.One)
                player.setVolume(0.5f)
                withTimeout(10.seconds) {
                    player.state.first { it.speed == 1.5 && it.loop == LoopMode.One && it.volume == 0.5f }
                }

                player.setSpeed(10.0)
                val refused = withTimeout(10.seconds) {
                    events.receiveAsFlow()
                        .mapNotNull { (it as? PlayerEvent.Warning)?.warning as? PlaybackWarning.CommandRefused }
                        .first()
                }
                assertEquals("setSpeed", refused.member, "the refusal names the setter the page called")
                assertEquals(1.5, player.state.value.speed, "a refused speed leaves the speed as it was")

                assertTrue(player.diagnosticsDump().isNotEmpty(), "the worker player's dump crosses")

                player.stop()
                // Relative addresses again, so the state can only hold these objects if the page
                // mapped the worker's items back to them.
                val first = MediaItem("$mediaPath/sync1080p30.mp4", title = "first")
                val second = MediaItem("$mediaPath/sparse-keyframes.mp4", title = "second")
                player.openQueue(listOf(first, second))
                player.next()
                val moved = withTimeout(30.seconds) { player.state.first { it.queueIndex == 1 && it.media == second } }
                assertEquals(listOf(first, second), moved.queue, "the queue holds the caller's own items")
            } finally {
                collector.cancel()
                player.closeAndAwait()
            }
        }
    }

    /**
     * libass draws an ASS track in the worker. The worker loads `kiteass.mjs` from the address
     * the page gives, and the ASS track of `subbed.mkv` is typeset by it through both of the clip's
     * cues, at 0.5 to 3 s and 3.5 to 6 s.
     *
     * The typesetter is named in the state as soon as the track has one, before the module has
     * landed, and named no more when the module fails to load, with
     * [PlaybackWarning.TypesetterUnavailable]. So the check is that it still names libass after
     * the cues, with no such warning. Served without `kiteass.mjs`, this fails on that warning.
     */
    @Test
    fun anAssTrackIsTypesetByLibassInTheWorker() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        val libassUrl = setup[4]
        withContext(Dispatchers.Default) {
            val player = KitePlayerWorker.start(pageCanvas(640, 360), workerUrl, codecUrl, libassUrl)
            val events = Channel<PlayerEvent>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch {
                player.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) }
            }
            try {
                subscribed.await()
                player.setViewport(640, 360, 1f)
                player.open(MediaItem("$media/subbed.mkv"))
                assertEquals(TrackChange.Applied(TrackKind.Subtitle, TrackId(2)), player.selectTrack(TrackKind.Subtitle, TrackId(2)))
                val typesetter = withTimeout(30.seconds) { player.state.first { it.subtitleTypesetter != null } }.subtitleTypesetter
                player.play()
                withTimeout(60.seconds) { player.progress.first { it.position >= 6.5.seconds } }
                val unavailable = generateSequence { events.tryReceive().getOrNull() }
                    .mapNotNull { (it as? PlayerEvent.Warning)?.warning as? PlaybackWarning.TypesetterUnavailable }
                    .firstOrNull()
                assertNull(unavailable, "libass did not typeset the track in the worker")
                assertEquals(typesetter, player.state.value.subtitleTypesetter, "the track left libass part way")
                println("WORKER typesetter: $typesetter")
            } finally {
                collector.cancel()
                player.closeAndAwait()
            }
        }
    }

    /**
     * Picture in picture carries the canvas the worker draws on.
     *
     * A browser opens its window only inside a viewer's click, and a test page has none, so the
     * document window here is a frame on the page, handed out by a stand-in for
     * `documentPictureInPicture`. The canvas element moves into another document exactly as it does
     * into the window, and the worker must then draw it at the window's size, which shows both that
     * the size reached the worker and that its frames reach the moved canvas. The video element's
     * window plays a live capture of the canvas, so the capture must show the worker's frames too.
     *
     * Measured with real windows on 2026-10-03, in Chromium 141 headless and on a display, with
     * Playwright's clicks: the moved canvas and the capture both kept changing with every frame.
     */
    @Test
    fun pictureInPictureCarriesTheCanvasTheWorkerDrawsOn() = runTest(timeout = 3.minutes) {
        val setup = karmaWorkerConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the worker player runs only in the browser half, where karma serves the worker")
            return@runTest
        }
        val (workerUrl, codecUrl, media) = setup
        withContext(Dispatchers.Default) {
            val canvas = pageCanvas(320, 180)
            val window = documentWindowStandIn(480, 270)
            val player = KitePlayerWorker.start(canvas, workerUrl, codecUrl, libassUrl = null)
            try {
                player.setViewport(320, 180, 1f)
                player.open(MediaItem("$media/sync1080p30.mp4"))
                player.play()
                withTimeout(60.seconds) { player.progress.first { it.position >= 1.seconds } }
                assertEquals(320, capturedWidth(canvas).await<JsNumber>().toInt(), "a capture of the canvas shows no frame")

                val pip = assertNotNull(player.pictureInPictureOrNull(), "a page with a canvas has picture in picture")
                assertEquals(WebPictureInPictureMode.DocumentWindow, pip.mode)
                pip.start()
                withTimeout(10.seconds) { pip.active.first { it } }
                assertTrue(canvasIsIn(canvas, window), "the canvas did not move into the window")
                val inWindow = windowPixelWidth(window)
                withTimeout(10.seconds) { while (canvasWidth(canvas) != inWindow) delay(50) }

                pip.stop()
                assertFalse(pip.isActive)
                assertTrue(canvasIsBackOnPage(canvas), "the canvas did not come back in place of its placeholder")
                withTimeout(10.seconds) { while (canvasWidth(canvas) != 320) delay(50) }
                pip.close()
            } finally {
                removeDocumentWindowStandIn(window)
                player.closeAndAwait()
            }
        }
    }

    @Test
    fun aWorkerThatCannotLoadFailsTheStart() = runTest(timeout = 1.minutes) {
        val setup = karmaWorkerConfig()?.split("\n") ?: return@runTest
        val failure = withContext(Dispatchers.Default) {
            runCatching { KitePlayerWorker.start(null, setup[0].replace("kiteplayer-web-worker.mjs", "missing.mjs"), setup[1]) }
        }.exceptionOrNull()
        assertIs<PlaybackError.Internal>(assertIs<PlaybackException>(failure).error)
    }

    // A start that failed before the worker answered left the page's audio context and its worklet
    // running, with no player for the application to close (#686).
    @Test
    fun aStartThatCannotTakeTheCanvasClosesTheAudioItOpened() = runTest(timeout = 1.minutes) {
        val setup = karmaWorkerConfig()?.split("\n") ?: return@runTest
        val counter = countAudioContexts()
        try {
            val canvas = pageCanvas(32, 32)
            // A canvas that already has a 2D context cannot pass its drawing to a worker.
            takeCanvas2d(canvas)
            val failure = withContext(Dispatchers.Default) {
                runCatching { KitePlayerWorker.start(canvas, setup[0], setup[1]) }
            }.exceptionOrNull()
            assertNotNull(failure, "a canvas with a 2D context passed to the worker")
            assertTrue(audioContextsOpened(counter) > 0, "the start opened no audio context, so it proves nothing")
            assertEquals(audioContextsOpened(counter), audioContextsClosed(counter), "the failed start left its audio open")
        } finally {
            stopCountingAudioContexts(counter)
        }
    }

    @Test
    fun aWorkerAddressTheBrowserRefusesClosesTheAudioItOpened() = runTest(timeout = 1.minutes) {
        val setup = karmaWorkerConfig()?.split("\n") ?: return@runTest
        val counter = countAudioContexts()
        try {
            val failure = withContext(Dispatchers.Default) {
                runCatching { KitePlayerWorker.start(null, "http://[not an address", setup[1]) }
            }.exceptionOrNull()
            assertNotNull(failure, "the browser took a worker address it cannot read")
            assertTrue(audioContextsOpened(counter) > 0, "the start opened no audio context, so it proves nothing")
            assertEquals(audioContextsOpened(counter), audioContextsClosed(counter), "the failed start left its audio open")
        } finally {
            stopCountingAudioContexts(counter)
        }
    }

    @Test
    fun aCodecModuleThatCannotLoadFailsTheStart() = runTest(timeout = 1.minutes) {
        val setup = karmaWorkerConfig()?.split("\n") ?: return@runTest
        val failure = withContext(Dispatchers.Default) {
            runCatching { KitePlayerWorker.start(null, setup[0], setup[1].replace("kite.mjs", "missing.mjs")) }
        }.exceptionOrNull()
        assertIs<PlaybackError.Internal>(assertIs<PlaybackException>(failure).error)
    }
}

/**
 * The worker, codec and clip addresses that the browser runner hands the page, the clips' path as
 * the config gives it, relative to the page, and the libass module's address. Null in Node and in
 * the toolchain's own browser runner, neither of which can host the worker player, so those skip.
 * In the page scripts/web-tests serves, the config must be there: its absence is a broken mount,
 * and it throws rather than let every test of this class pass with nothing checked (#727).
 */
@JsFun(
    """() => {
        if (typeof document === 'undefined') return null;
        const karma = globalThis.__karma__;
        const worker = karma && karma.config ? karma.config.kiteWorker : undefined;
        if (!worker) {
            if (globalThis.__kiteWebTests !== true) return null;
            throw new Error('the browser runner gave this page no kiteWorker config, so the worker player cannot be tested: ' +
                'check the worker mount in scripts/web-tests/run.py');
        }
        const absolute = (p) => new URL(p, document.baseURI).href;
        return [absolute(worker.worker), absolute(worker.codec), absolute(worker.media), worker.media, absolute(worker.libass)].join("\n");
    }""",
)
private external fun karmaWorkerConfig(): String?

@JsFun("(url) => fetch(url, { cache: 'no-store' }).then((answer) => answer.text())")
private external fun fetchText(url: String): Promise<JsString>

/** The test server's list of requests: a line of clip, host, range, header value and time for each, then the manifest count. */
@JsFun(
    """(url) => fetch(url, { cache: 'no-store' }).then((answer) => answer.json()).then((all) =>
        all.asked.map((one) => [one.clip, one.host, one.range || '', one.marked || '', String(one.at)].join('|')).concat([String(all.manifests)]).join("\n"))""",
)
private external fun fetchAsked(url: String): Promise<JsString>

@JsFun(
    """(w, h) => {
        const canvas = document.createElement('canvas');
        canvas.width = w; canvas.height = h;
        document.body.appendChild(canvas);
        return canvas;
    }""",
)
private external fun pageCanvas(width: Int, height: Int): JsAny

/**
 * A frame of [width] by [height] on the page, whose window `documentPictureInPicture.requestWindow`
 * now answers with, in place of the browser's own.
 */
@JsFun(
    """(width, height) => {
        const frame = document.createElement('iframe');
        frame.style.cssText = 'border:0;width:' + width + 'px;height:' + height + 'px';
        document.body.appendChild(frame);
        const standIn = { requestWindow: () => Promise.resolve(frame.contentWindow) };
        Object.defineProperty(window, 'documentPictureInPicture', { value: standIn, configurable: true });
        return frame;
    }""",
)
private external fun documentWindowStandIn(width: Int, height: Int): JsAny

/** Gives the browser its own `documentPictureInPicture` back and removes the frame. */
@JsFun("(frame) => { delete window.documentPictureInPicture; frame.remove(); }")
private external fun removeDocumentWindowStandIn(frame: JsAny)

@JsFun("(canvas, frame) => canvas.ownerDocument === frame.contentDocument")
private external fun canvasIsIn(canvas: JsAny, frame: JsAny): Boolean

@JsFun("(canvas) => canvas.ownerDocument === document && canvas.isConnected && !document.querySelector('.kiteplayer-pip-placeholder')")
private external fun canvasIsBackOnPage(canvas: JsAny): Boolean

/** The drawing buffer width the renderer gives the canvas in [frame]: its CSS width at its pixel ratio. */
@JsFun("(frame) => Math.trunc(frame.contentWindow.innerWidth * (frame.contentWindow.devicePixelRatio || 1))")
private external fun windowPixelWidth(frame: JsAny): Int

/** The width of the last frame the worker drew, which a placeholder canvas reports. */
@JsFun("(canvas) => canvas.width")
private external fun canvasWidth(canvas: JsAny): Int

/** The frame width a live capture of [canvas] plays, after its first frame or two seconds. */
@JsFun(
    """(canvas) => new Promise((resolve) => {
        const video = document.createElement('video');
        video.muted = true;
        video.srcObject = canvas.captureStream();
        const done = () => { const width = video.videoWidth; video.srcObject.getTracks().forEach((t) => t.stop()); resolve(width); };
        video.addEventListener('loadeddata', done, { once: true });
        setTimeout(done, 2000);
        video.play().catch(() => {});
    })""",
)
private external fun capturedWidth(canvas: JsAny): Promise<JsNumber>

/** A frame loop on the page that keeps the longest gap between two frames. */
@JsFun(
    """() => {
        const h = { on: true, frames: 0, worst: 0, last: performance.now() };
        const tick = () => {
            if (!h.on) return;
            const now = performance.now();
            h.worst = Math.max(h.worst, now - h.last);
            h.last = now;
            h.frames++;
            requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
        return h;
    }""",
)
private external fun heartbeatStart(): JsAny

@JsFun("(h) => h.frames")
private external fun heartbeatFrames(heartbeat: JsAny): Int

/** Stops the loop and answers the longest gap in milliseconds, counting the one still open. */
@JsFun("(h) => { h.on = false; return Math.max(h.worst, performance.now() - h.last); }")
private external fun heartbeatStop(heartbeat: JsAny): Double

/**
 * Puts a counting `AudioContext` in place of the page's own, so a test can tell how many the
 * player opened and how many it closed (#686). The count lives on the returned object.
 */
@JsFun(
    """() => {
        const Real = globalThis.AudioContext;
        const counter = { opened: 0, closed: 0, Real: Real };
        if (!Real) return counter;
        class Counting extends Real {
            constructor(...args) { super(...args); counter.opened += 1; }
            close() { counter.closed += 1; return super.close(); }
        }
        globalThis.AudioContext = Counting;
        return counter;
    }""",
)
private external fun countAudioContexts(): JsAny

@JsFun("(counter) => { if (counter.Real) globalThis.AudioContext = counter.Real; }")
private external fun stopCountingAudioContexts(counter: JsAny)

@JsFun("(counter) => counter.opened")
private external fun audioContextsOpened(counter: JsAny): Int

@JsFun("(counter) => counter.closed")
private external fun audioContextsClosed(counter: JsAny): Int

@JsFun("(canvas) => { canvas.getContext('2d'); }")
private external fun takeCanvas2d(canvas: JsAny)

/**
 * Waits up to five seconds for [ear] to hear a sample louder than a tenth of the tone the clips
 * carry, `sine` at its default eighth of full scale, so the sound that reaches the page's output is
 * proven not to be silence (#546). A frame drawn and a clock that runs prove nothing of it: the
 * clock runs on frames consumed, and silence is consumed as well as a tone.
 */
private suspend fun assertHeard(ear: JsAny, what: String) {
    val heard = withTimeoutOrNull(5.seconds) {
        while (loudestHeard(ear) < 0.0125) delay(20)
        true
    } ?: false
    assertTrue(audioNodesHeard(ear) > 0, "the player made no audio node on the page for $what")
    assertTrue(heard, "the page's output carried only silence, loudest ${loudestHeard(ear)}, where it should carry $what")
}

/**
 * Puts a listening `AudioWorkletNode` in place of the page's own (#546): each node the player makes
 * feeds an analyser as well as its output, and the loudest sample heard since is kept on the
 * returned object, read every 20 ms.
 */
@JsFun(
    """() => {
        const Real = globalThis.AudioWorkletNode;
        const ear = { loudest: 0, nodes: 0, on: true, Real: Real };
        if (!Real) return ear;
        class Listening extends Real {
            constructor(context, name, options) {
                super(context, name, options);
                ear.nodes += 1;
                const analyser = context.createAnalyser();
                analyser.fftSize = 2048;
                this.connect(analyser);
                const samples = new Float32Array(analyser.fftSize);
                const listen = () => {
                    if (!ear.on) return;
                    analyser.getFloatTimeDomainData(samples);
                    for (let i = 0; i < samples.length; i++) {
                        const level = Math.abs(samples[i]);
                        if (level > ear.loudest) ear.loudest = level;
                    }
                    setTimeout(listen, 20);
                };
                listen();
            }
        }
        globalThis.AudioWorkletNode = Listening;
        return ear;
    }""",
)
private external fun listenToAudio(): JsAny

@JsFun("(ear) => { ear.on = false; if (ear.Real) globalThis.AudioWorkletNode = ear.Real; }")
private external fun stopListening(ear: JsAny)

@JsFun("(ear) => ear.loudest")
private external fun loudestHeard(ear: JsAny): Double

@JsFun("(ear) => ear.nodes")
private external fun audioNodesHeard(ear: JsAny): Int
