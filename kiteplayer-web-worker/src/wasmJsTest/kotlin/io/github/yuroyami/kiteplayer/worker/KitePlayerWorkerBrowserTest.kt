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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.js.JsAny
import kotlin.js.JsNumber
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
     * With the related opens taken out of the web backend, the open fails and this test with it.
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
 * The worker, codec and clip addresses that karma.config.d/worker.js hands the page, the clips'
 * path as the config gives it, relative to the page, and the libass module's address. Null outside
 * karma.
 */
@JsFun(
    """() => {
        const karma = globalThis.__karma__;
        const worker = karma && karma.config ? karma.config.kiteWorker : undefined;
        if (!worker || typeof document === 'undefined') return null;
        const absolute = (p) => new URL(p, document.baseURI).href;
        return [absolute(worker.worker), absolute(worker.codec), absolute(worker.media), worker.media, absolute(worker.libass)].join("\n");
    }""",
)
private external fun karmaWorkerConfig(): String?

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
