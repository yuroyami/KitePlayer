@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.js.JsAny

/** The browser feature a web [KitePlayerPictureInPicture] opens its window with. */
public enum class WebPictureInPictureMode {
    /**
     * Document Picture-in-Picture: a small browser window above the others that the page's own
     * canvas moves into, subtitles included. Chromium 116 and later on desktop, and Firefox 151 and
     * later, on HTTPS pages only.
     */
    DocumentWindow,

    /**
     * The picture in picture of a video element: a hidden, muted video plays a live capture of the
     * canvas, and the browser shows that video. Chrome, Edge and Safari.
     */
    VideoElement,
}

/**
 * Puts the canvas a web player draws into in a small window above the viewer's other windows.
 *
 * [createOrNull] picks the first of two browser features that this browser has:
 * 1. [WebPictureInPictureMode.DocumentWindow]. The canvas itself moves into the window, so
 *    everything the renderer draws comes along. An empty element of the canvas's size, with the
 *    class `kiteplayer-pip-placeholder`, keeps its place in the page. When the window closes, the
 *    canvas goes back in its place.
 * 2. [WebPictureInPictureMode.VideoElement]. A hidden, muted video element plays a live capture of
 *    the canvas, and the window's play and pause buttons play and pause the player.
 *
 * A browser opens the window only inside the viewer's own click, so [start] asks at once and never
 * suspends. Call it from the click handler itself, with nothing awaited before it.
 */
public class KitePlayerPictureInPicture internal constructor(
    mode: WebPictureInPictureMode,
    private val window: WebPictureInPictureWindow,
) : AutoCloseable {

    /** The browser feature [start] uses, or null once [close] has run. */
    public var mode: WebPictureInPictureMode? = mode
        private set

    /**
     * True when the browser would open the window now. The video element feature also needs the
     * capture to have delivered a frame, so it is false until the player has drawn one.
     */
    public val isPossible: Boolean get() = window.isPossible

    /** True while the small window is on screen. */
    public val isActive: Boolean get() = window.active.value

    /** [isActive], as a flow that changes when the window opens and when it closes. */
    public val active: StateFlow<Boolean> = window.active.asStateFlow()

    /** Asks the browser for the window. It opens later, when the browser grants it; watch [active]. */
    public fun start() {
        window.start()
    }

    /** Closes the window and puts the picture back in the page. */
    public fun stop() {
        window.stop()
    }

    /** Calls [stop], releases what [createOrNull] prepared, and refuses every later [start]. */
    override fun close() {
        window.close()
        mode = null
    }

    public companion object {
        /**
         * Builds picture in picture for [canvas], or answers null when this browser has neither
         * feature, or when [canvas] is not an element of a page (an `OffscreenCanvas` is not).
         *
         * @param canvas the `HTMLCanvasElement` that [renderer] draws into.
         * @param renderer told the window's size while the canvas is in the document window, and the
         *        page's size again when it comes back.
         * @param player played and paused by the buttons of the video element's window.
         */
        public fun createOrNull(
            canvas: JsAny,
            renderer: VideoRenderer,
            player: KitePlayer,
        ): KitePlayerPictureInPicture? = webPictureInPictureOrNull(
            documentPictureInPicture = webDocumentPictureInPicture(),
            canvas = canvas,
            setViewport = renderer::setViewport,
            play = player::play,
            pause = player::pause,
        )
    }
}

/** One browser feature behind [KitePlayerPictureInPicture]. */
internal abstract class WebPictureInPictureWindow {
    val active = MutableStateFlow(false)
    abstract val isPossible: Boolean
    abstract fun start()
    abstract fun stop()
    abstract fun close()
}

/**
 * Picks the feature for [canvas]: the document window where the browser has one, else the video
 * element where the canvas can be captured, else null.
 *
 * [documentPictureInPicture] is the browser's global of that name, passed in so that a test can
 * hand in a fake one and run in node.
 */
internal fun webPictureInPictureOrNull(
    documentPictureInPicture: JsAny?,
    canvas: JsAny,
    setViewport: (width: Int, height: Int, scale: Float) -> Unit,
    play: () -> Unit,
    pause: () -> Unit,
): KitePlayerPictureInPicture? {
    if (!webIsPageElement(canvas)) return null
    if (documentPictureInPicture != null) {
        return KitePlayerPictureInPicture(
            WebPictureInPictureMode.DocumentWindow,
            DocumentWindowPictureInPicture(documentPictureInPicture, canvas, setViewport),
        )
    }
    if (!webCanFeedVideoElement(canvas)) return null
    val video = webPrepareVideo(canvas) ?: return null
    return KitePlayerPictureInPicture(
        WebPictureInPictureMode.VideoElement,
        VideoElementPictureInPicture(video, play, pause),
    )
}

/**
 * The document window: the canvas moves into it and comes back when it closes.
 *
 * The canvas's backing store is sized for the page, so the renderer is told the window's size
 * while the canvas is there, and the page's size again when it is back. The renderer draws its
 * retained picture after each resize, so a paused picture stays on screen.
 */
internal class DocumentWindowPictureInPicture(
    private val documentPictureInPicture: JsAny,
    private val canvas: JsAny,
    private val setViewport: (width: Int, height: Int, scale: Float) -> Unit,
) : WebPictureInPictureWindow() {

    private var window: JsAny? = null

    /** What [webMoveIntoWindow] needs to undo the move: the placeholder and the canvas's own style. */
    private var moved: JsAny? = null

    /** Counts opened windows, so an event from a window that is already gone changes nothing. */
    private var generation = 0
    private var requested = false
    private var wanted = false
    private var closed = false
    private var pageWidth = 0
    private var pageHeight = 0

    override val isPossible: Boolean get() = !closed

    override fun start() {
        if (closed || window != null || requested) return
        wanted = true
        requested = true
        webRequestWindow(
            dpip = documentPictureInPicture,
            width = webCssWidth(canvas),
            height = webCssHeight(canvas),
            onOpened = { opened ->
                requested = false
                open(opened)
            },
            onRefused = {
                requested = false
                wanted = false
            },
        )
    }

    private fun open(opened: JsAny) {
        // Stopped or closed while the browser was still opening it.
        if (!wanted || closed) {
            webCloseWindow(opened)
            return
        }
        pageWidth = webBackingWidth(canvas)
        pageHeight = webBackingHeight(canvas)
        val record = webMoveIntoWindow(canvas, opened)
        if (record == null) {
            // The canvas left the page before the window arrived, so there is nothing to show.
            wanted = false
            webCloseWindow(opened)
            return
        }
        moved = record
        window = opened
        val current = ++generation
        webListen(opened, "resize") { if (current == generation && window != null) fitTo(opened) }
        webListen(opened, "pagehide") { if (current == generation && window != null) putBack() }
        fitTo(opened)
        active.value = true
    }

    private fun fitTo(opened: JsAny) {
        setViewport(webInnerWidth(opened), webInnerHeight(opened), webPixelRatio(opened).toFloat())
    }

    override fun stop() {
        wanted = false
        val opened = window ?: return
        putBack()
        webCloseWindow(opened)
    }

    /** Puts the canvas back in the page at its old backing store size. */
    private fun putBack() {
        val record = moved
        window = null
        moved = null
        if (record != null) webMoveBack(canvas, record)
        setViewport(pageWidth, pageHeight, 1f)
        active.value = false
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
    }
}

/**
 * The video element: [video] plays a live capture of the canvas from the moment it is made, so
 * [start] only has to ask for the window, inside the viewer's click.
 */
internal class VideoElementPictureInPicture(
    private val video: JsAny,
    private val play: () -> Unit,
    private val pause: () -> Unit,
) : WebPictureInPictureWindow() {

    private var closed = false

    init {
        webListen(video, "enterpictureinpicture") { if (!closed) active.value = true }
        webListen(video, "leavepictureinpicture") {
            active.value = false
            // Kept playing outside the window, so the next start finds the capture live.
            if (!closed) webKeepPlaying(video)
        }
        // Forwarded only while the window shows: outside it the video plays and pauses for this
        // class's own reasons, not the viewer's.
        webListen(video, "play") { if (active.value) play() }
        webListen(video, "pause") { if (active.value) pause() }
    }

    override val isPossible: Boolean get() = !closed && webVideoCanOpen(video)

    override fun start() {
        if (closed || active.value) return
        webRequestVideoWindow(video)
    }

    override fun stop() {
        webExitVideoWindow(video)
    }

    override fun close() {
        if (closed) return
        closed = true
        webExitVideoWindow(video)
        active.value = false
        webReleaseVideo(video)
    }
}

/* The JavaScript half. Each call is one small step over objects the browser owns, so a test can
 * replace every one of those objects with a plain fake. */

/** The browser's `documentPictureInPicture`, or null where there is none, which includes node. */
@JsFun("() => globalThis.documentPictureInPicture || null")
private external fun webDocumentPictureInPicture(): JsAny?

/** True for an element that belongs to a page, which both features need. */
@JsFun("(c) => !!(c && c.ownerDocument && typeof c.ownerDocument.createElement === 'function')")
private external fun webIsPageElement(canvas: JsAny): Boolean

/** True when the page allows the video element feature and the canvas can be captured for it. */
@JsFun("(c) => c.ownerDocument.pictureInPictureEnabled === true && typeof c.captureStream === 'function'")
private external fun webCanFeedVideoElement(canvas: JsAny): Boolean

/**
 * Asks for the window at once, which keeps the viewer's click valid for it, and reports the window
 * or the refusal later. A size of zero is left out, and the browser picks one.
 */
@JsFun(
    """(dpip, width, height, onOpened, onRefused) => {
      const options = (width > 0 && height > 0) ? { width: width, height: height } : {};
      let request;
      try { request = dpip.requestWindow(options); } catch (e) { onRefused(); return; }
      Promise.resolve(request).then((w) => onOpened(w), () => onRefused());
    }""",
)
private external fun webRequestWindow(
    dpip: JsAny,
    width: Int,
    height: Int,
    onOpened: (JsAny) -> Unit,
    onRefused: () -> Unit,
)

@JsFun("(c) => c.offsetWidth | 0")
private external fun webCssWidth(canvas: JsAny): Int

@JsFun("(c) => c.offsetHeight | 0")
private external fun webCssHeight(canvas: JsAny): Int

@JsFun("(c) => c.width | 0")
private external fun webBackingWidth(canvas: JsAny): Int

@JsFun("(c) => c.height | 0")
private external fun webBackingHeight(canvas: JsAny): Int

@JsFun("(w) => w.innerWidth | 0")
private external fun webInnerWidth(window: JsAny): Int

@JsFun("(w) => w.innerHeight | 0")
private external fun webInnerHeight(window: JsAny): Int

@JsFun("(w) => w.devicePixelRatio || 1")
private external fun webPixelRatio(window: JsAny): Double

/**
 * Leaves a placeholder of the canvas's size where the canvas is, then moves the canvas into the
 * window's body and makes it fill the window. Answers what it takes to undo that, or null when the
 * canvas is not in a page.
 */
@JsFun(
    """(canvas, win) => {
      const doc = canvas.ownerDocument, parent = canvas.parentNode;
      if (!doc || !parent) return null;
      const view = doc.defaultView;
      const shown = (view && view.getComputedStyle) ? view.getComputedStyle(canvas).display : 'block';
      const placeholder = doc.createElement('div');
      placeholder.className = 'kiteplayer-pip-placeholder';
      placeholder.style.cssText = 'display:' + (shown === 'inline' ? 'inline-block' : shown) +
        ';width:' + canvas.offsetWidth + 'px;height:' + canvas.offsetHeight + 'px';
      parent.insertBefore(placeholder, canvas);
      const record = { placeholder: placeholder, style: canvas.style.cssText };
      const body = win.document.body;
      body.style.cssText = 'margin:0;background:#000;overflow:hidden';
      canvas.style.cssText = 'display:block;width:100vw;height:100vh';
      body.append(canvas);
      return record;
    }""",
)
private external fun webMoveIntoWindow(canvas: JsAny, window: JsAny): JsAny?

/** Puts the canvas where the placeholder is, which removes the placeholder, and restores its style. */
@JsFun(
    """(canvas, record) => {
      const placeholder = record.placeholder;
      if (placeholder.parentNode) placeholder.parentNode.replaceChild(canvas, placeholder);
      canvas.style.cssText = record.style;
    }""",
)
private external fun webMoveBack(canvas: JsAny, record: JsAny)

@JsFun("(target, type, handler) => { target.addEventListener(type, () => handler()); }")
private external fun webListen(target: JsAny, type: String, handler: () -> Unit)

@JsFun("(w) => { try { w.close(); } catch (e) {} }")
private external fun webCloseWindow(window: JsAny)

/**
 * A muted video element that plays a live capture of the canvas, or null when the canvas refuses
 * to be captured. It stays out of the page: the browser shows it only in its window.
 */
@JsFun(
    """(canvas) => {
      let stream;
      try { stream = canvas.captureStream(); } catch (e) { return null; }
      const video = canvas.ownerDocument.createElement('video');
      video.muted = true;
      video.playsInline = true;
      video.srcObject = stream;
      const playing = video.play();
      if (playing && playing.catch) playing.catch(() => {});
      return video;
    }""",
)
private external fun webPrepareVideo(canvas: JsAny): JsAny?

/** True when the page allows the window now and the capture has delivered a frame to show. */
@JsFun("(v) => { const doc = v.ownerDocument; return !!(doc && doc.pictureInPictureEnabled) && v.readyState >= 1; }")
private external fun webVideoCanOpen(video: JsAny): Boolean

@JsFun(
    """(v) => {
      try {
        const request = v.requestPictureInPicture();
        if (request && request.catch) request.catch(() => {});
      } catch (e) {}
    }""",
)
private external fun webRequestVideoWindow(video: JsAny)

@JsFun(
    """(v) => {
      const doc = v.ownerDocument;
      if (!doc || doc.pictureInPictureElement !== v) return;
      try {
        const leaving = doc.exitPictureInPicture();
        if (leaving && leaving.catch) leaving.catch(() => {});
      } catch (e) {}
    }""",
)
private external fun webExitVideoWindow(video: JsAny)

@JsFun("(v) => { if (v.paused) { const p = v.play(); if (p && p.catch) p.catch(() => {}); } }")
private external fun webKeepPlaying(video: JsAny)

@JsFun(
    """(v) => {
      const stream = v.srcObject;
      if (stream && stream.getTracks) stream.getTracks().forEach((track) => track.stop());
      v.srcObject = null;
    }""",
)
private external fun webReleaseVideo(video: JsAny)
