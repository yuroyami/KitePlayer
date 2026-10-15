package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The connection between a player and [KiteVideo]: it owns the renderer the player draws
 * through and the one snapshot slot the draw phase reads.
 *
 * ```
 * val video = rememberKiteVideoState()
 * val player = remember { KitePlayer.create(PlayerConfig(backends = mobileBackends())) }
 * player.attachRenderer(video.renderer)
 * KiteVideo(video, Modifier.clip(RoundedCornerShape(24.dp)))
 * ```
 *
 * Built outside composition, close [renderer] when done; [rememberKiteVideoState] does that
 * automatically when it leaves composition. Closing is always safe while the player still holds
 * the renderer: a closed renderer refuses frames and the engine counts them, exactly like every
 * other renderer in this library.
 *
 * On the web the renderer converts a frame only when a [KiteVideo] is about to draw it, so a state
 * that no [KiteVideo] draws keeps the newest frame and lets the rest go as superseded.
 */
public expect class KiteVideoState {
    public constructor()

    /** Attach this to the player. Close it when the video surface is done (or use [rememberKiteVideoState]). */
    public val renderer: VideoRenderer

    /** Frames whose picture was published for drawing. */
    public val presentedFrames: Long

    /** Frames replaced by a newer one before they could be converted. */
    public val supersededFrames: Long

    /** Frames that published nothing for a reason other than being superseded. */
    public val failedFrames: Long

    /**
     * The measured CPU cost of the software path per published frame. On an emulator this is
     * provisional evidence; the numbers that count are re-taken on devices.
     */
    public val frameCost: KiteVideoFrameCost
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

/** Pure multi-node viewport ownership, split out so lifecycle and deduplication stay host-testable. */
internal class KiteVideoViewportBook(
    private val apply: (width: Int, height: Int, scale: Float) -> Unit,
) {
    private val fence = SynchronizedObject()
    private val byOwner = mutableMapOf<Any, Viewport>()
    private var applied: Viewport? = null

    fun update(owner: Any, width: Int, height: Int) {
        synchronized(fence) {
            val changed = if (width > 0 && height > 0) {
                val viewport = Viewport(width, height)
                byOwner.put(owner, viewport) != viewport
            } else {
                byOwner.remove(owner) != null
            }
            if (changed) applyAggregateIfChanged()
        }
    }

    fun remove(owner: Any) {
        synchronized(fence) {
            if (byOwner.remove(owner) != null) applyAggregateIfChanged()
        }
    }

    /** Size changes are rare UI events, so serialize emission with ownership to preserve ordering. */
    private fun applyAggregateIfChanged() {
        val next = if (byOwner.isEmpty()) {
            Viewport.Zero
        } else {
            Viewport(
                width = byOwner.values.maxOf(Viewport::width),
                height = byOwner.values.maxOf(Viewport::height),
            )
        }
        if (next == applied) return
        apply(next.width, next.height, PHYSICAL_PIXEL_SCALE)
        applied = next
    }

    private data class Viewport(val width: Int, val height: Int) {
        companion object {
            val Zero = Viewport(0, 0)
        }
    }

    private companion object {
        const val PHYSICAL_PIXEL_SCALE = 1f
    }
}
