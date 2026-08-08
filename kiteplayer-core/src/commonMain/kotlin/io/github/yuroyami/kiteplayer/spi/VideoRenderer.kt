package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlinx.coroutines.flow.Flow

/**
 * Draws frames.
 *
 * The renderer owns its thread and its GPU context. The engine never touches a graphics API and
 * never assumes which thread it is on.
 *
 * The rules below are stated explicitly because this is the interface libmpv makes hardest to use
 * correctly, and its failure mode is a deadlock rather than an error:
 *
 * 1. The engine calls [present] from the video scheduler coroutine. A renderer may hand the work to
 *    its own thread and return at once, or do it inline. Either is correct.
 * 2. The renderer owns the frame from the moment [present] is called, including when it fails, and
 *    closes it exactly once.
 * 3. A renderer must never call synchronously back into the player from inside [present].
 * 4. [vsyncIntervalNanos] is advisory. Returning null costs smoothness on a high refresh display
 *    and nothing else.
 * 5. Losing a surface is an event, not an exception. Playback continues, video frames are counted as
 *    dropped, and audio keeps playing, because a minimised window should not stop the sound.
 * 6. A renderer may be attached and detached at any time, including while playing. Video decoding
 *    does not depend on one existing. libmpv requires its render context to exist before playback
 *    starts and aborts the process if it is freed in the wrong order; that is not reproduced here.
 */
public interface VideoRenderer : AutoCloseable {

    /** Hardware surface kinds this renderer can present without a download to main memory. */
    public fun supportedHardwareSurfaces(): Set<HwSurfaceKind>

    /** True when this renderer can draw a frame in [format] at all. */
    public fun supports(format: PlayerPixelFormat): Boolean

    /**
     * Presents [frame], aiming for [targetNanos] on the engine's monotonic clock.
     *
     * @return false when the frame was not presented, for example because the surface is gone. The
     *         engine counts that as a drop. The frame is closed either way.
     */
    public suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean

    /** Display refresh interval in nanoseconds, or null when the platform does not report it. */
    public fun vsyncIntervalNanos(): Long?

    /** The output surface changed size. Subtitles are laid out again after this. */
    public fun setViewport(width: Int, height: Int, scale: Float)

    /** Composited above the video. Replaced wholesale rather than diffed. */
    public suspend fun setOverlay(overlay: SubtitleOverlay?)

    public val events: Flow<RendererEvent>
}

public sealed interface RendererEvent {
    /** The surface went away. Playback continues without picture. */
    public data class SurfaceLost(val detail: String) : RendererEvent

    /** A surface is available again. */
    public data object SurfaceAvailable : RendererEvent

    /** The display's refresh interval changed, for example the window moved to another monitor. */
    public data class VsyncChanged(val intervalNanos: Long) : RendererEvent

    /** The renderer failed in a way it cannot recover from. The engine falls back to software. */
    public data class Failed(val detail: String) : RendererEvent
}

/**
 * What to draw above the video, in output pixels.
 *
 * Subtitles are laid out for the output size, not scaled with the video, so this changes when the
 * viewport changes rather than on every frame. [contentHash] lets a renderer skip re-uploading an
 * unchanged overlay, which is the usual case: subtitles change about once a second and frames arrive
 * sixty times a second.
 */
public data class SubtitleOverlay(
    val images: List<OverlayImage>,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val contentHash: Long,
)

public data class OverlayImage(
    val x: Int,
    val y: Int,
    val bitmap: RgbaBitmap,
)
