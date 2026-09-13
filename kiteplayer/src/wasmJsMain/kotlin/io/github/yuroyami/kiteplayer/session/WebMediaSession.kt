@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.js.JsAny
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors [player] into the browser's media session and routes its buttons back.
 *
 * That session is what the operating system's media overlay, the keyboard's media keys and a
 * headset read for a page. Browsers show it only while the page plays sound through an `audio` or
 * `video` element. KitePlayer plays through Web Audio, which no browser counts today, so the
 * overlay appears only if the page also plays through such an element.
 *
 * Artwork is a URL here, because that is the only form a browser takes. Close it with the player.
 */
public class KitePlayerMediaSession(
    private val player: KitePlayer,
    session: JsAny? = navigatorMediaSession(),
) : AutoCloseable {

    private val bridge: WebMediaSessionBridge? = session?.let(::WebMediaSessionBridge)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val artwork = MutableStateFlow<String?>(null)

    /** False when this browser has no media session. Nothing is mirrored then. */
    public val isAvailable: Boolean = bridge != null

    /** The browser has no token to hand out. Always null. */
    public val platformToken: Any? = null

    init {
        bridge?.let(::start)
    }

    /** The picture the overlay shows, as a URL the page can serve. Null removes it. */
    public fun setArtwork(url: String?) {
        artwork.value = url
    }

    private fun start(bridge: WebMediaSessionBridge) {
        bridge.onAction("play") { _, _ -> player.play() }
        bridge.onAction("pause") { _, _ -> player.pause() }
        bridge.onAction("stop") { _, _ -> player.pause() }
        bridge.onAction("nexttrack") { _, _ -> scope.launch { runCatching { player.next() } } }
        bridge.onAction("previoustrack") { _, _ -> scope.launch { runCatching { player.previous() } } }
        bridge.onAction("seekforward") { _, offset -> skipBy(if (offset > 0.0) offset.seconds else SKIP) }
        bridge.onAction("seekbackward") { _, offset -> skipBy(-(if (offset > 0.0) offset.seconds else SKIP)) }
        bridge.onAction("seekto") { time, _ ->
            if (time >= 0.0) scope.launch { runCatching { player.seek(time.seconds) } }
        }
        val mirror = MediaSessionMirror<String>(bridge::publishMetadata, bridge::publishPlayback)
        scope.launch {
            combine(player.state, player.progress, artwork) { snapshot, progress, url ->
                snapshot.toMediaSessionState(progress) to url
            }.collect { (state, url) -> mirror.update(state, url) }
        }
    }

    private fun skipBy(by: Duration) {
        scope.launch {
            val target = (player.position() + by).coerceAtLeast(Duration.ZERO)
            runCatching { player.seek(target) }
        }
    }

    override fun close() {
        scope.cancel()
        bridge?.clear()
    }

    private companion object {
        val SKIP = 15.seconds
    }
}
