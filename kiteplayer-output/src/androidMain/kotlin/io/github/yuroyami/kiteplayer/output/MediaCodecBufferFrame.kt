package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import java.util.concurrent.atomic.AtomicBoolean

/** Receives frame-release commands from the scheduler without calling MediaCodec on its thread. */
internal fun interface MediaCodecFrameOwner {
    fun release(command: MediaCodecReleaseCommand)
}

internal data class MediaCodecReleaseCommand(
    val outputIndex: Int,
    val decoderEpoch: Long,
    val renderNanos: Long?,
    val displayVersion: Long?,
    val beforeRender: (renderTimestampNanos: Long) -> Unit,
    val onRenderFailed: (renderTimestampNanos: Long) -> Unit,
    val completion: (rendered: Boolean) -> Unit,
) {
    fun complete(rendered: Boolean) {
        completion(rendered)
    }
}

/**
 * One client-owned MediaCodec output buffer.
 *
 * Closing discards it. [renderAt] transfers it to the current display Surface. Both operations only
 * enqueue a command; the decoder performs the MediaCodec call on its confined video-decode thread.
 */
internal class MediaCodecBufferFrame(
    private val owner: MediaCodecFrameOwner,
    private val outputIndex: Int,
    private val decoderEpoch: Long,
    override val target: MediaCodecSurfaceTarget,
    override val pts: Pts,
    override val duration: Pts?,
    override val generation: Generation,
    override val size: VideoSize,
    override val colorSpace: ColorSpaceInfo,
    override val rotationDegrees: Int = 0,
) : DirectSurfaceVideoFrame {
    private val released = AtomicBoolean(false)

    override val pixelFormat: PlayerPixelFormat get() = PlayerPixelFormat.Opaque
    override val hardwareSurface: HwSurfaceKind get() = HwSurfaceKind.MediaCodecBuffer

    override fun renderAt(
        targetNanos: Long,
        beforeRender: (renderTimestampNanos: Long) -> Unit,
        onRenderFailed: (renderTimestampNanos: Long) -> Unit,
        onReleased: (rendered: Boolean) -> Unit,
    ): Boolean {
        if (!released.compareAndSet(false, true)) return false
        val display = target.snapshot()
        val canRender = display.isDisplayable
        val codecTargetNanos = translatePresentationTime(
            targetNanos = targetNanos,
            engineNowNanos = AndroidMonotonicClock.nanos(),
            codecNowNanos = System.nanoTime(),
        )
        owner.release(
            MediaCodecReleaseCommand(
                outputIndex = outputIndex,
                decoderEpoch = decoderEpoch,
                renderNanos = codecTargetNanos.takeIf { canRender },
                displayVersion = display.version.takeIf { canRender },
                beforeRender = beforeRender,
                onRenderFailed = onRenderFailed,
                completion = if (canRender) onReleased else NO_COMPLETION,
            ),
        )
        return canRender
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        owner.release(
            MediaCodecReleaseCommand(
                outputIndex = outputIndex,
                decoderEpoch = decoderEpoch,
                renderNanos = null,
                displayVersion = null,
                beforeRender = NO_PREPARE,
                onRenderFailed = NO_PREPARE,
                completion = NO_COMPLETION,
            ),
        )
    }

    private companion object {
        val NO_COMPLETION: (Boolean) -> Unit = {}
        val NO_PREPARE: (Long) -> Unit = {}
    }
}

/** Preserves the remaining delay while crossing from the engine clock to MediaCodec's nanoTime base. */
internal fun translatePresentationTime(
    targetNanos: Long,
    engineNowNanos: Long,
    codecNowNanos: Long,
): Long {
    val delay = targetNanos - engineNowNanos
    return if (delay > 0L && codecNowNanos > Long.MAX_VALUE - delay) Long.MAX_VALUE else codecNowNanos + delay
}
