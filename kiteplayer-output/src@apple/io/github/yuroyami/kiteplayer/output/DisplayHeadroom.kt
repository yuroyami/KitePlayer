@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import platform.QuartzCore.CAMetalLayer
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * How far the display under a Metal layer goes beyond standard range white, in multiples of it.
 * [potential] is the most it can show, and [current] what it shows now. A display with no extended
 * range answers 1 for both.
 *
 * A display's current headroom rises only after a layer asks for extended range, and moves with the
 * brightness, so the renderer decides with [potential] and tone maps to [current].
 */
internal interface HeadroomSource : AutoCloseable {
    val potential: Float
    val current: Float

    /** False until the first reading arrived. Until then both values are 1, which is a guess. */
    val known: Boolean

    /** Asks for a new reading. It arrives later, read on the main thread, which owns the screens. */
    fun refresh()
}

/**
 * The headroom of the display that shows [layer]. [onChange] runs on the main thread after the
 * current headroom moved by more than two percent.
 */
internal fun displayHeadroom(layer: CAMetalLayer, onChange: () -> Unit): HeadroomSource =
    ScreenHeadroom(layer, onChange)

/** What the screen under [layer] reports as its potential and current headroom. Main thread only. */
internal expect fun readScreenHeadroom(layer: CAMetalLayer): Pair<Float, Float>

/**
 * Asks [layer] for extended-range content, or stops asking. Without it the system clamps the
 * layer's values above 1 and leaves the display's headroom at 1.
 */
internal expect fun setExtendedRangeContent(layer: CAMetalLayer, extended: Boolean)

/**
 * True where a Metal layer with no colour space gets no colour matching at all, so the renderer
 * names the picture's own (#489). That is macOS. iOS treats such a layer as sRGB.
 */
internal expect val tagsStandardRangeLayer: Boolean

private class ScreenHeadroom(private val layer: CAMetalLayer, private val onChange: () -> Unit) : HeadroomSource {
    private val potentialBits = atomic(1f.toRawBits())
    private val currentBits = atomic(1f.toRawBits())
    private val clock = TimeSource.Monotonic.markNow()
    private val requestedAt = atomic(Long.MIN_VALUE)
    private val closed = atomic(false)
    private val read = atomic(false)

    override val potential: Float get() = Float.fromBits(potentialBits.value)
    override val current: Float get() = Float.fromBits(currentBits.value)
    override val known: Boolean get() = read.value

    init {
        // Read at once, so the first HDR frame already knows the display: right here when a host
        // makes the renderer on the main thread, as they do, and on the main thread soon otherwise.
        if (platform.Foundation.NSThread.isMainThread) store(readScreenHeadroom(layer)) else requestRead()
    }

    override fun refresh() {
        val now = clock.elapsedNow().inWholeMilliseconds
        val last = requestedAt.value
        if (last != Long.MIN_VALUE && now - last < REFRESH_MILLIS) return
        if (requestedAt.compareAndSet(last, now)) requestRead()
    }

    private fun requestRead() {
        dispatch_async(dispatch_get_main_queue()) {
            if (!closed.value && store(readScreenHeadroom(layer))) onChange()
        }
    }

    /** Keeps a reading. True when the current headroom moved by more than two percent. */
    private fun store(reading: Pair<Float, Float>): Boolean {
        val (potential, current) = reading
        val before = Float.fromBits(currentBits.value)
        potentialBits.value = potential.coerceAtLeast(1f).toRawBits()
        currentBits.value = current.coerceAtLeast(1f).toRawBits()
        read.value = true
        return abs(current - before) > before * 0.02f
    }

    override fun close() {
        closed.value = true
    }

    private companion object {
        const val REFRESH_MILLIS = 250L
    }
}
