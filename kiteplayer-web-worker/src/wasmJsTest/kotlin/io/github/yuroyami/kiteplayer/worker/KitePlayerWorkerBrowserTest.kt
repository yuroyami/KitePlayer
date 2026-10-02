@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.worker

import io.github.yuroyami.kiteplayer.KitePlayerWorker
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

/** The worker, codec and clip paths that karma.config.d/worker.js hands the page, or null outside karma. */
@JsFun(
    """() => {
        const karma = globalThis.__karma__;
        const worker = karma && karma.config ? karma.config.kiteWorker : undefined;
        if (!worker || typeof document === 'undefined') return null;
        const absolute = (p) => new URL(p, document.baseURI).href;
        return absolute(worker.worker) + "\n" + absolute(worker.codec) + "\n" + absolute(worker.media);
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
