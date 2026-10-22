@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.view

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The browser half of picture in picture, driven against fake pages, fake windows and a fake
 * video, so it runs in node and in a browser alike.
 *
 * What a fake cannot prove is left to a real browser: that the browser grants the window inside a
 * click, that a moved canvas keeps drawing, and that a captured canvas plays in the video window.
 */
class WebPictureInPictureTest {

    private data class Viewport(val width: Int, val height: Int, val scale: Float)

    private val viewports = mutableListOf<Viewport>()
    private var plays = 0
    private var pauses = 0

    private fun build(world: JsAny, documentPictureInPicture: JsAny?): KitePlayerPictureInPicture? =
        webPictureInPictureOrNull(
            documentPictureInPicture = documentPictureInPicture,
            canvas = canvasOf(world),
            setViewport = { width, height, scale -> viewports += Viewport(width, height, scale) },
            play = { plays++ },
            pause = { pauses++ },
        )

    private fun documentWindow(world: JsAny): KitePlayerPictureInPicture =
        assertNotNull(build(world, documentPictureInPictureOf(world)))

    /** Lets the fake browser's promises settle, the way a real window arrives after the click. */
    private suspend fun settle() {
        nextTask().await<JsAny?>()
    }

    @Test
    fun theDocumentWindowIsChosenFirst() {
        val world = fakeWorld(videoElementAllowed = true)
        assertEquals(WebPictureInPictureMode.DocumentWindow, documentWindow(world).mode)
    }

    @Test
    fun startAsksForTheWindowAtOnceAtTheCanvasSize() {
        val world = fakeWorld()
        val pip = documentWindow(world)
        pip.start()
        // Nothing may be awaited before the request, or a browser no longer counts the click.
        assertEquals(1, requestCount(world), "start must ask before it returns")
        assertEquals(640, requestedWidth(world))
        assertEquals(360, requestedHeight(world))
        assertFalse(pip.isActive, "the window has not arrived yet")
        pip.start()
        assertEquals(1, requestCount(world), "a second start while waiting must not ask again")
    }

    @Test
    fun theCanvasMovesIntoTheWindowBehindAPlaceholder() = runTest {
        val world = fakeWorld()
        val pip = documentWindow(world)
        pip.start()
        settle()

        assertTrue(pip.isActive)
        assertTrue(pip.active.value)
        assertTrue(canvasIsInWindow(world), "the canvas itself moves into the window")
        assertEquals(-1, indexInPage(world, "canvas"))
        assertEquals(1, indexInPage(world, "placeholder"), "the placeholder takes the canvas's place")
        assertEquals("display:block;width:640px;height:360px", placeholderStyle(world))
        assertEquals("display:block;width:100vw;height:100vh", canvasStyle(world), "the canvas fills the window")
        assertEquals(listOf(Viewport(640, 360, 2f)), viewports, "the renderer is sized for the window")
    }

    @Test
    fun aResizeOfTheWindowReachesTheRenderer() = runTest {
        val world = fakeWorld()
        val pip = documentWindow(world)
        pip.start()
        settle()
        resizeWindow(world, 800, 450)
        assertEquals(Viewport(800, 450, 2f), viewports.last())
        assertTrue(pip.isActive)
    }

    @Test
    fun closingTheWindowPutsTheCanvasBackAtItsOldSize() = runTest {
        val world = fakeWorld()
        val pip = documentWindow(world)
        pip.start()
        settle()

        viewerClosesWindow(world)

        assertFalse(pip.isActive)
        assertEquals(1, indexInPage(world, "canvas"), "the canvas is back where it was")
        assertEquals(-1, indexInPage(world, "placeholder"), "and the placeholder is gone")
        assertEquals(3, pageChildCount(world))
        assertEquals("border: 1px solid red", canvasStyle(world), "the page's own style comes back")
        assertEquals(Viewport(1280, 720, 1f), viewports.last(), "the renderer is sized for the page again")
    }

    @Test
    fun stopPutsTheCanvasBackAndClosesTheWindow() = runTest {
        val world = fakeWorld()
        val pip = documentWindow(world)
        pip.start()
        settle()

        pip.stop()

        assertFalse(pip.isActive)
        assertTrue(windowClosed(world))
        assertEquals(1, indexInPage(world, "canvas"))
        assertEquals(-1, indexInPage(world, "placeholder"))
        // The closed window's own pagehide arrives afterwards and must change nothing.
        viewerClosesWindow(world)
        assertEquals(listOf(Viewport(640, 360, 2f), Viewport(1280, 720, 1f)), viewports)
    }

    @Test
    fun stopBeforeTheWindowArrivesClosesItUnused() = runTest {
        val world = fakeWorld()
        val pip = documentWindow(world)
        pip.start()
        pip.stop()
        settle()

        assertTrue(windowClosed(world), "a window that arrives after stop is closed at once")
        assertFalse(pip.isActive)
        assertEquals(1, indexInPage(world, "canvas"), "the canvas never left the page")
        assertEquals(-1, indexInPage(world, "placeholder"))
        assertTrue(viewports.isEmpty(), "the renderer was never resized: $viewports")
    }

    @Test
    fun aRefusedWindowLeavesThePageAloneAndCanBeAskedAgain() = runTest {
        val world = fakeWorld(refuseWindows = true)
        val pip = documentWindow(world)
        pip.start()
        settle()
        assertFalse(pip.isActive)
        assertEquals(1, indexInPage(world, "canvas"))
        pip.start()
        assertEquals(2, requestCount(world), "a refusal must not block the next click")
    }

    @Test
    fun theWindowCanOpenAgainAfterItClosed() = runTest {
        val world = fakeWorld()
        val pip = documentWindow(world)
        pip.start()
        settle()
        viewerClosesWindow(world)
        pip.start()
        settle()
        assertTrue(pip.isActive)
        assertTrue(canvasIsInWindow(world))
        // The first window's late events belong to a window that is gone.
        fireOnFirstWindow(world, "pagehide")
        assertTrue(pip.isActive, "an event from the old window must not close the new one")
    }

    @Test
    fun closeStopsAndRefusesEveryLaterStart() = runTest {
        val world = fakeWorld()
        val pip = documentWindow(world)
        pip.start()
        settle()
        pip.close()
        assertFalse(pip.isActive)
        assertNull(pip.mode)
        assertFalse(pip.isPossible)
        assertEquals(1, indexInPage(world, "canvas"))
        pip.start()
        assertEquals(1, requestCount(world))
    }

    @Test
    fun withoutTheDocumentWindowTheVideoIsPreparedWhenBuilt() {
        val world = fakeWorld(videoElementAllowed = true)
        val pip = assertNotNull(build(world, documentPictureInPicture = null))
        assertEquals(WebPictureInPictureMode.VideoElement, pip.mode)
        assertTrue(videoIsMuted(world), "the capture must never play sound")
        assertTrue(videoPlaysTheCapture(world), "the video plays a capture of the canvas")
        assertEquals(1, videoPlayCalls(world), "it plays before any click, so start has nothing to wait for")
        assertEquals(0, plays, "the video's own start is not the viewer's")
    }

    @Test
    fun theVideoPathAsksForTheWindowSynchronouslyInsideStart() {
        val world = fakeWorld(videoElementAllowed = true)
        val pip = assertNotNull(build(world, documentPictureInPicture = null))
        pip.start()
        assertEquals(1, videoWindowRequests(world), "start must ask before it returns")
    }

    @Test
    fun theVideoWindowsButtonsPlayAndPauseThePlayer() {
        val world = fakeWorld(videoElementAllowed = true)
        val pip = assertNotNull(build(world, documentPictureInPicture = null))
        pip.start()
        browserOpensVideoWindow(world)
        assertTrue(pip.isActive)

        fireOnVideo(world, "pause")
        fireOnVideo(world, "play")
        assertEquals(1, pauses)
        assertEquals(1, plays)

        pip.stop()
        assertFalse(pip.isActive, "leaving the window clears the state")
        fireOnVideo(world, "pause")
        assertEquals(1, pauses, "outside the window the video's pauses are not the viewer's")
    }

    @Test
    fun theVideoIsKeptPlayingAfterTheWindowCloses() {
        val world = fakeWorld(videoElementAllowed = true)
        val pip = assertNotNull(build(world, documentPictureInPicture = null))
        pip.start()
        browserOpensVideoWindow(world)
        fireOnVideo(world, "pause")
        pauseVideo(world)
        pip.stop()
        assertEquals(2, videoPlayCalls(world), "a capture paused in the window plays on for the next start")
        assertEquals(0, plays, "and that is not the viewer pressing play")
    }

    @Test
    fun closingTheVideoPathReleasesTheCapture() {
        val world = fakeWorld(videoElementAllowed = true)
        val pip = assertNotNull(build(world, documentPictureInPicture = null))
        pip.close()
        assertTrue(captureStopped(world))
        assertNull(pip.mode)
        pip.start()
        assertEquals(0, videoWindowRequests(world))
    }

    @Test
    fun withNeitherFeatureThereIsNoPictureInPicture() {
        assertNull(build(fakeWorld(videoElementAllowed = false), documentPictureInPicture = null))
    }

    @Test
    fun aCanvasOutsideAPageHasNoPictureInPicture() {
        val world = fakeWorld()
        assertNull(
            webPictureInPictureOrNull(
                documentPictureInPicture = documentPictureInPictureOf(world),
                canvas = offscreenCanvas(),
                setViewport = { _, _, _ -> },
                play = {},
                pause = {},
            ),
        )
    }
}

/**
 * A page with one canvas between two paragraphs, a fake `documentPictureInPicture` that opens fake
 * windows, and a document that can make a fake video element.
 */
@JsFun(
    """(videoElementAllowed, refuseWindows) => {
      const element = (doc, tag) => {
        const el = { tagName: tag, ownerDocument: doc, parentNode: null, children: [], style: { cssText: '' },
          className: '', listeners: {}, offsetWidth: 0, offsetHeight: 0 };
        const detach = (child) => { if (child.parentNode) child.parentNode.removeChild(child); };
        el.removeChild = (child) => {
          const i = el.children.indexOf(child);
          if (i >= 0) el.children.splice(i, 1);
          child.parentNode = null;
          return child;
        };
        el.append = (child) => { detach(child); el.children.push(child); child.parentNode = el; };
        el.insertBefore = (child, before) => {
          detach(child);
          const i = el.children.indexOf(before);
          el.children.splice(i < 0 ? el.children.length : i, 0, child);
          child.parentNode = el;
          return child;
        };
        el.replaceChild = (child, old) => {
          detach(child);
          const i = el.children.indexOf(old);
          el.children[i] = child;
          child.parentNode = el;
          old.parentNode = null;
          return old;
        };
        el.addEventListener = (type, f) => { (el.listeners[type] = el.listeners[type] || []).push(f); };
        el.fire = (type) => { (el.listeners[type] || []).slice().forEach((f) => f({ type: type })); };
        if (tag === 'video') {
          el.paused = true; el.readyState = 0; el.playCalls = 0; el.windowRequests = 0;
          el.play = () => { el.playCalls++; el.paused = false; el.fire('play'); return Promise.resolve(); };
          el.requestPictureInPicture = () => { el.windowRequests++; return Promise.resolve({}); };
        }
        return el;
      };
      const doc = { pictureInPictureEnabled: videoElementAllowed, pictureInPictureElement: null, defaultView: null, created: [] };
      doc.createElement = (tag) => { const el = element(doc, tag); doc.created.push(el); return el; };
      doc.exitPictureInPicture = () => {
        const v = doc.pictureInPictureElement;
        doc.pictureInPictureElement = null;
        if (v) v.fire('leavepictureinpicture');
        return Promise.resolve();
      };
      const page = element(doc, 'div');
      const canvas = element(doc, 'canvas');
      canvas.width = 1280; canvas.height = 720; canvas.offsetWidth = 640; canvas.offsetHeight = 360;
      canvas.style.cssText = 'border: 1px solid red';
      const track = { stopped: false, stop() { this.stopped = true; } };
      canvas.captureStream = () => ({ getTracks: () => [track] });
      page.append(element(doc, 'p'));
      page.append(canvas);
      page.append(element(doc, 'p'));
      const dpip = { requests: [], windows: [] };
      dpip.requestWindow = (options) => {
        dpip.requests.push(options);
        if (refuseWindows) return Promise.reject(new Error('refused'));
        const pipDoc = {};
        pipDoc.createElement = (tag) => element(pipDoc, tag);
        pipDoc.body = element(pipDoc, 'body');
        const win = { document: pipDoc, innerWidth: options.width || 300, innerHeight: options.height || 150,
          devicePixelRatio: 2, closed: false, listeners: {} };
        win.addEventListener = (type, f) => { (win.listeners[type] = win.listeners[type] || []).push(f); };
        win.fire = (type) => { (win.listeners[type] || []).slice().forEach((f) => f({ type: type })); };
        win.close = () => { if (win.closed) return; win.closed = true; win.fire('pagehide'); };
        dpip.windows.push(win);
        return Promise.resolve(win);
      };
      return { doc: doc, page: page, canvas: canvas, dpip: dpip, track: track };
    }""",
)
private external fun fakeWorldOf(videoElementAllowed: Boolean, refuseWindows: Boolean): JsAny

private fun fakeWorld(videoElementAllowed: Boolean = false, refuseWindows: Boolean = false): JsAny =
    fakeWorldOf(videoElementAllowed, refuseWindows)

@JsFun("(w) => w.canvas")
private external fun canvasOf(world: JsAny): JsAny

@JsFun("(w) => w.dpip")
private external fun documentPictureInPictureOf(world: JsAny): JsAny

@JsFun("() => ({ width: 640, height: 360, getContext: () => null })")
private external fun offscreenCanvas(): JsAny

@JsFun("() => new Promise((resolve) => setTimeout(() => resolve(null), 0))")
private external fun nextTask(): Promise<JsAny?>

@JsFun("(w) => w.dpip.requests.length")
private external fun requestCount(world: JsAny): Int

@JsFun("(w) => w.dpip.requests[0].width")
private external fun requestedWidth(world: JsAny): Int

@JsFun("(w) => w.dpip.requests[0].height")
private external fun requestedHeight(world: JsAny): Int

@JsFun("(w) => { const last = w.dpip.windows[w.dpip.windows.length - 1]; return !!last && w.canvas.parentNode === last.document.body; }")
private external fun canvasIsInWindow(world: JsAny): Boolean

/** Where the canvas, or the placeholder, sits among the page's children, or -1 when it is not there. */
@JsFun(
    """(w, which) => w.page.children.findIndex((c) =>
      which === 'canvas' ? c === w.canvas : c.className === 'kiteplayer-pip-placeholder')""",
)
private external fun indexInPage(world: JsAny, which: String): Int

@JsFun("(w) => w.page.children.length")
private external fun pageChildCount(world: JsAny): Int

@JsFun("(w) => { const p = w.page.children.find((c) => c.className === 'kiteplayer-pip-placeholder'); return p ? p.style.cssText : ''; }")
private external fun placeholderStyle(world: JsAny): String

@JsFun("(w) => w.canvas.style.cssText")
private external fun canvasStyle(world: JsAny): String

@JsFun(
    """(w, width, height) => {
      const last = w.dpip.windows[w.dpip.windows.length - 1];
      last.innerWidth = width; last.innerHeight = height; last.fire('resize');
    }""",
)
private external fun resizeWindow(world: JsAny, width: Int, height: Int)

/** What a browser does when the viewer closes the window: the window's page hides. */
@JsFun("(w) => { const last = w.dpip.windows[w.dpip.windows.length - 1]; last.closed = true; last.fire('pagehide'); }")
private external fun viewerClosesWindow(world: JsAny)

@JsFun("(w, type) => { w.dpip.windows[0].fire(type); }")
private external fun fireOnFirstWindow(world: JsAny, type: String)

@JsFun("(w) => { const last = w.dpip.windows[w.dpip.windows.length - 1]; return !!last && last.closed; }")
private external fun windowClosed(world: JsAny): Boolean

@JsFun("(w) => { const v = w.doc.created.find((e) => e.tagName === 'video'); return !!v && v.muted === true; }")
private external fun videoIsMuted(world: JsAny): Boolean

@JsFun("(w) => { const v = w.doc.created.find((e) => e.tagName === 'video'); return !!v && !!v.srcObject && v.srcObject.getTracks()[0] === w.track; }")
private external fun videoPlaysTheCapture(world: JsAny): Boolean

@JsFun("(w) => w.doc.created.find((e) => e.tagName === 'video').playCalls")
private external fun videoPlayCalls(world: JsAny): Int

@JsFun("(w) => { const v = w.doc.created.find((e) => e.tagName === 'video'); return v ? v.windowRequests : 0; }")
private external fun videoWindowRequests(world: JsAny): Int

/** What a browser does once it has opened the video's window. */
@JsFun("(w) => { const v = w.doc.created.find((e) => e.tagName === 'video'); w.doc.pictureInPictureElement = v; v.fire('enterpictureinpicture'); }")
private external fun browserOpensVideoWindow(world: JsAny)

@JsFun("(w, type) => { w.doc.created.find((e) => e.tagName === 'video').fire(type); }")
private external fun fireOnVideo(world: JsAny, type: String)

@JsFun("(w) => { w.doc.created.find((e) => e.tagName === 'video').paused = true; }")
private external fun pauseVideo(world: JsAny)

@JsFun("(w) => w.track.stopped === true")
private external fun captureStopped(world: JsAny): Boolean
