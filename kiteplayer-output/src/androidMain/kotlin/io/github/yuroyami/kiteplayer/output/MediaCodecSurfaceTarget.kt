package io.github.yuroyami.kiteplayer.output

import android.view.Surface
import io.github.yuroyami.kiteplayer.VideoSize

/**
 * The display Surface shared by one Android video view and every renderer generation it creates.
 *
 * Android's main thread replaces the Surface as the view is created and destroyed. The decoder
 * reads snapshots while processing presentation commands, and a monotonically increasing version
 * proves that the Surface a queued command targeted is still the active one.
 */
internal class MediaCodecSurfaceTarget(
    initialSurface: Surface? = null,
    geometryConsumer: ((VideoSize, Int) -> Unit)? = null,
) {
    private var displaySurface: Surface? = initialSurface
    private var displayVersion: Long = 0L
    private var switcher: Switcher? = null
    private var geometryConsumer: ((VideoSize, Int) -> Unit)? = geometryConsumer

    internal fun interface Switcher {
        /** Called under the target fence. It must switch the codec before returning. */
        fun switchTo(snapshot: Snapshot)
    }

    internal data class Snapshot(val surface: Surface?, val version: Long) {
        val isDisplayable: Boolean get() = surface?.isValid == true
    }

    internal fun snapshot(): Snapshot = synchronized(this) {
        Snapshot(displaySurface, displayVersion)
    }

    /**
     * Runs one presentation decision and its accounting callback under the Surface fence.
     *
     * Renderer close updates this target before snapshotting its counters. Keeping [completion]
     * inside the same fence guarantees that every release which beat close is counted first.
     */
    internal fun withSnapshotCompletion(
        completion: (rendered: Boolean) -> Unit,
        action: (Snapshot) -> Boolean,
    ) {
        synchronized(this) {
            var rendered = false
            try {
                rendered = action(Snapshot(displaySurface, displayVersion))
            } finally {
                completion(rendered)
            }
        }
    }

    /** Installs the live decoder that must follow Surface updates synchronously. */
    internal fun bind(candidate: Switcher) {
        synchronized(this) {
            check(switcher == null) { "a MediaCodec decoder is already bound to this Surface target" }
            candidate.switchTo(Snapshot(displaySurface, displayVersion))
            switcher = candidate
        }
    }

    internal fun unbind(candidate: Switcher) {
        synchronized(this) {
            if (switcher === candidate) switcher = null
        }
    }

    internal fun publishGeometry(size: VideoSize, rotationDegrees: Int) {
        val consumer = synchronized(this) { geometryConsumer }
        consumer?.invoke(size, rotationDegrees)
    }

    internal fun clearGeometryConsumer() {
        synchronized(this) { geometryConsumer = null }
    }

    /** Called from the platform view's main-thread Surface callbacks. */
    internal fun update(surface: Surface?) {
        synchronized(this) {
            if (displaySurface === surface) return
            val next = Snapshot(surface, displayVersion + 1L)
            try {
                switcher?.switchTo(next)
            } catch (failure: Throwable) {
                // The switcher either recovered onto its private fallback or tore the codec down.
                // In both cases no display is active. Publishing null makes every queued frame a
                // discard and avoids claiming a Surface the codec failed to adopt.
                displaySurface = null
                displayVersion = next.version
                throw failure
            }
            displaySurface = surface
            displayVersion = next.version
        }
    }
}

/** A MediaCodec output buffer whose release is serialized back onto the decoder thread. */
internal interface DirectSurfaceVideoFrame : io.github.yuroyami.kiteplayer.spi.VideoFrame {
    val target: MediaCodecSurfaceTarget

    /**
     * Consumes this frame and enqueues presentation for [targetNanos]. False means it enqueued a
     * discard because no live display Surface existed.
     */
    fun renderAt(
        targetNanos: Long,
        beforeRender: (renderTimestampNanos: Long) -> Unit = {},
        onReleased: (rendered: Boolean) -> Unit,
    ): Boolean
}
