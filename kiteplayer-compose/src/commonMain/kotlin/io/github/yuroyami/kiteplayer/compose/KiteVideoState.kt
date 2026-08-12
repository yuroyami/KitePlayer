package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer

/**
 * The connection between a player and [KiteVideo]: it owns the renderer the player draws
 * through and the one snapshot slot the draw phase reads.
 *
 * ```
 * val video = rememberKiteVideoState()
 * val player = remember { KitePlayer.create(PlayerConfig(backends = phoneBackends())) }
 * player.attachRenderer(video.renderer)
 * KiteVideo(video, Modifier.clip(RoundedCornerShape(24.dp)))
 * ```
 *
 * Built outside composition, close [renderer] when done; [rememberKiteVideoState] does that
 * automatically when it leaves composition. Closing is always safe while the player still holds
 * the renderer: a closed renderer refuses frames and the engine counts them, exactly like every
 * other renderer in this library.
 */
public class KiteVideoState internal constructor(
    convert: (VideoFrame) -> ByteArray,
    makeImage: (rgba: ByteArray, width: Int, height: Int) -> ImageBitmap,
) {
    public constructor() : this(
        convert = { frame -> phoneFrameToRgba(frame) },
        makeImage = ::rgbaToImageBitmap,
    )

    /**
     * The newest finished frame. LAW 1 OF 17.9 LIVES HERE: this state is read at exactly one
     * site, inside the draw phase of [KiteVideo]. Reading it during composition or layout turns
     * every video frame into a recomposition, which is the difference between smooth and
     * slideshow. Writes come from the renderer's worker thread; the snapshot system carries the
     * invalidation to the draw scope.
     */
    internal val frame: MutableState<KiteVideoFrame?> = mutableStateOf(null)

    private val videoRenderer = KiteVideoRenderer(
        convert = convert,
        makeImage = makeImage,
        publish = { newest -> frame.value = newest },
    )

    /** Attach this to the player. Close it when the video surface is done (or use [rememberKiteVideoState]). */
    public val renderer: VideoRenderer get() = videoRenderer

    /** Frames whose picture was published for drawing. */
    public val presentedFrames: Long get() = videoRenderer.presentedFrames

    /** Frames replaced by a newer one before they could be converted. */
    public val supersededFrames: Long get() = videoRenderer.supersededFrames

    /** Frames that published nothing for a reason other than being superseded. */
    public val failedFrames: Long get() = videoRenderer.failedFrames
}

/**
 * Remembers one [KiteVideoState] and closes its renderer when it leaves composition.
 */
@Composable
public fun rememberKiteVideoState(): KiteVideoState {
    val state = remember { KiteVideoState() }
    DisposableEffect(state) {
        onDispose { state.renderer.close() }
    }
    return state
}
