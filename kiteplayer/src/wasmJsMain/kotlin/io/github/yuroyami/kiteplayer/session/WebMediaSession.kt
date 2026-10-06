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
 * Artwork is a URL here, because that is the only form a browser takes. The item's own cover shows
 * when the page sets none, from a URL made for its bytes (#425). Close it with the player.
 * A button press that arrives after the player closed does nothing.
 *
 * @param skipInterval how far the skip back and skip forward actions move when the browser names no
 *        offset of its own. Positive.
 */
public class KitePlayerMediaSession(
    private val player: KitePlayer,
    session: JsAny? = navigatorMediaSession(),
    private val skipInterval: Duration = 15.seconds,
) : AutoCloseable {

    init {
        require(skipInterval.isPositive()) { "the skip interval must be positive, was $skipInterval" }
    }

    private val bridge: WebMediaSessionBridge? = session?.let(::WebMediaSessionBridge)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val artwork = MutableStateFlow<String?>(null)

    /** A URL for the item's own cover, made for its bytes and let go with the next (#425). */
    private val fileArtwork = MutableStateFlow<String?>(null)

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
        bridge.onAction("play") { _, _ -> player.playFromRemote() }
        bridge.onAction("pause") { _, _ -> player.pauseFromRemote() }
        bridge.onAction("stop") { _, _ -> player.pauseFromRemote() }
        bridge.onAction("nexttrack") { _, _ -> scope.launch { runCatching { player.next() } } }
        bridge.onAction("previoustrack") { _, _ -> scope.launch { runCatching { player.pressPrevious() } } }
        bridge.onAction("seekforward") { _, offset -> skipBy(if (offset > 0.0) offset.seconds else skipInterval) }
        bridge.onAction("seekbackward") { _, offset -> skipBy(-(if (offset > 0.0) offset.seconds else skipInterval)) }
        bridge.onAction("seekto") { time, _ ->
            if (time >= 0.0) scope.launch { runCatching { player.seek(time.seconds) } }
        }
        val mirror = MediaSessionMirror<String>(bridge::publishMetadata, bridge::publishPlayback)
        scope.launch {
            combine(player.state, player.progress, artwork, fileArtwork) { snapshot, progress, url, file ->
                snapshot.toMediaSessionState(progress) to (url ?: file)
            }.collect { (state, url) -> mirror.update(state, url) }
        }
        scope.launch {
            player.coverArt.collect { cover ->
                fileArtwork.value?.let(::revokeObjectUrl)
                fileArtwork.value = cover?.let { picture ->
                    // Each byte as one character, which a direct call carries as it is (#425).
                    val latin1 = CharArray(picture.bytes.size) { (picture.bytes[it].toInt() and 0xFF).toChar() }.concatToString()
                    objectUrlOf(latin1, picture.mimeType ?: "image/jpeg")
                }
            }
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
        fileArtwork.value?.let(::revokeObjectUrl)
        fileArtwork.value = null
    }

    /** Closes this session once [player] is asked to close. */
    internal fun closeWithPlayer() {
        scope.closeWithPlayer(player, ::close)
    }
}

/**
 * Creates the media session for this player and returns it. It closes itself when the player
 * closes. See [KitePlayerMediaSession] for what a browser shows.
 */
public fun KitePlayer.attachMediaSession(skipInterval: Duration = 15.seconds): KitePlayerMediaSession =
    KitePlayerMediaSession(this, skipInterval = skipInterval).also { it.closeWithPlayer() }

/** A URL for the bytes that [latin1] holds one to a character, as [type]. */
@JsFun(
    """(s, type) => {
      const bytes = new Uint8Array(s.length);
      for (let i = 0; i < s.length; i++) bytes[i] = s.charCodeAt(i);
      return URL.createObjectURL(new Blob([bytes], { type: type }));
    }""",
)
private external fun objectUrlOf(latin1: String, type: String): String

@JsFun("(url) => URL.revokeObjectURL(url)")
private external fun revokeObjectUrl(url: String)
