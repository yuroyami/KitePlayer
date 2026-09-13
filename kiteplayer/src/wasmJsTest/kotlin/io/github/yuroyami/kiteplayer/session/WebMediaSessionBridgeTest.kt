@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.session

import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The browser half of the media session, driven against a fake session object so it runs in node
 * and in a browser alike. Nothing here claims a browser drew an overlay; that is a device step.
 */
class WebMediaSessionBridgeTest {

    private fun state(
        phase: MediaSessionPhase = MediaSessionPhase.Playing,
        duration: Duration? = 60.seconds,
        hasNext: Boolean = false,
    ) = MediaSessionState(
        phase = phase,
        position = 12.seconds,
        duration = duration,
        speed = 1.0,
        canSeek = duration != null,
        hasVideo = false,
        title = "A Holiday",
        artist = "Someone",
        album = null,
        hasNext = hasNext,
        hasPrevious = false,
    )

    private fun bridgeWithEveryAction(session: JsAny) = WebMediaSessionBridge(session).apply {
        for (name in WebMediaSessionBridge.ACTIONS) onAction(name) { _, _ -> }
    }

    @Test
    fun playingWritesThePlayingStateAndAPositionState() {
        val session = fakeSession()
        WebMediaSessionBridge(session).publishPlayback(state())
        assertEquals("playing", playbackState(session))
        assertEquals(60.0, positionDuration(session))
        assertEquals(12.0, positionValue(session))
        assertEquals(1.0, positionRate(session))
    }

    @Test
    fun pausedKeepsAPositiveRateBecauseBrowsersRefuseZero() {
        val session = fakeSession()
        WebMediaSessionBridge(session).publishPlayback(state(phase = MediaSessionPhase.Paused))
        assertEquals("paused", playbackState(session))
        assertTrue(positionRate(session) > 0.0)
    }

    @Test
    fun bufferingReadsAsPausedAndStoppedAsNone() {
        val session = fakeSession()
        val bridge = WebMediaSessionBridge(session)
        bridge.publishPlayback(state(phase = MediaSessionPhase.Buffering))
        assertEquals("paused", playbackState(session))
        bridge.publishPlayback(state(phase = MediaSessionPhase.Stopped))
        assertEquals("none", playbackState(session))
    }

    @Test
    fun aLiveStreamClearsThePositionState() {
        val session = fakeSession()
        val bridge = WebMediaSessionBridge(session)
        bridge.publishPlayback(state())
        bridge.publishPlayback(state(duration = null))
        assertFalse(hasPositionState(session))
    }

    @Test
    fun metadataCarriesTheTitleTheArtistAndTheArtwork() {
        val session = fakeSession()
        val bridge = WebMediaSessionBridge(session)
        bridge.publishMetadata(state().metadata(), artworkUrl = "https://example.test/cover.png")
        assertEquals("A Holiday", metadataTitle(session))
        assertEquals("Someone", metadataArtist(session))
        assertEquals("https://example.test/cover.png", artworkSrc(session))
        bridge.publishMetadata(state().metadata(), artworkUrl = null)
        assertEquals("", artworkSrc(session))
    }

    @Test
    fun onlyTheActionsTheStateCanHonourAreOffered() {
        val session = fakeSession()
        val bridge = bridgeWithEveryAction(session)
        bridge.publishPlayback(state(duration = null))
        assertTrue(hasHandler(session, "play"))
        assertTrue(hasHandler(session, "pause"))
        assertFalse(hasHandler(session, "nexttrack"))
        assertFalse(hasHandler(session, "seekto"))
        bridge.publishPlayback(state(hasNext = true))
        assertTrue(hasHandler(session, "nexttrack"))
        assertTrue(hasHandler(session, "seekto"))
        assertFalse(hasHandler(session, "previoustrack"))
    }

    @Test
    fun aSeekToHandlerReceivesTheSeekTime() {
        val session = fakeSession()
        var seenTime = -1.0
        var seenOffset = 0.0
        val bridge = WebMediaSessionBridge(session)
        bridge.onAction("seekto") { time, offset ->
            seenTime = time
            seenOffset = offset
        }
        bridge.publishPlayback(state())
        fireSeekTo(session, 42.0)
        assertEquals(42.0, seenTime)
        assertEquals(-1.0, seenOffset)
    }

    @Test
    fun clearRemovesEveryHandlerAndTheState() {
        val session = fakeSession()
        val bridge = bridgeWithEveryAction(session)
        bridge.publishMetadata(state().metadata(), artworkUrl = null)
        bridge.publishPlayback(state())
        bridge.clear()
        assertFalse(hasHandler(session, "play"))
        assertEquals("none", playbackState(session))
        assertFalse(hasPositionState(session))
        assertEquals("", metadataTitle(session))
    }
}

@JsFun(
    "() => { const s = { metadata: null, playbackState: 'none', position: null, handlers: {} };" +
        " s.setActionHandler = (n, h) => { s.handlers[n] = h; };" +
        " s.setPositionState = (p) => { s.position = p || null; };" +
        " return s; }",
)
private external fun fakeSession(): JsAny

@JsFun("(s) => String(s.playbackState)")
private external fun playbackState(s: JsAny): String

@JsFun("(s) => s.position !== null")
private external fun hasPositionState(s: JsAny): Boolean

@JsFun("(s) => s.position ? s.position.duration : -1")
private external fun positionDuration(s: JsAny): Double

@JsFun("(s) => s.position ? s.position.position : -1")
private external fun positionValue(s: JsAny): Double

@JsFun("(s) => s.position ? s.position.playbackRate : -1")
private external fun positionRate(s: JsAny): Double

@JsFun("(s) => s.metadata ? String(s.metadata.title) : ''")
private external fun metadataTitle(s: JsAny): String

@JsFun("(s) => s.metadata ? String(s.metadata.artist) : ''")
private external fun metadataArtist(s: JsAny): String

@JsFun("(s) => (s.metadata && s.metadata.artwork && s.metadata.artwork.length) ? String(s.metadata.artwork[0].src) : ''")
private external fun artworkSrc(s: JsAny): String

@JsFun("(s, n) => typeof s.handlers[n] === 'function'")
private external fun hasHandler(s: JsAny, n: String): Boolean

@JsFun("(s, t) => { s.handlers['seekto']({ action: 'seekto', seekTime: t }); }")
private external fun fireSeekTo(s: JsAny, t: Double)
