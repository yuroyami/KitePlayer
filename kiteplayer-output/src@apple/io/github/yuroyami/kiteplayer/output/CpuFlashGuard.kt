@file:OptIn(KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.FlashGuard
import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.spi.VideoFlashGuard
import kotlinx.atomicfu.atomic

/**
 * The flash guard of the UIKit and AppKit CPU fallbacks (#500): the mode, the system setting it
 * follows, and the shared detector run on the converted pixels before they are drawn. The rule is
 * in `docs/video-flash-guard.md`.
 *
 * [setMode] and [forget] are for any thread. The rest is the conversion worker's.
 */
internal class CpuFlashGuard(
    /** Reads the system's Dim Flashing Lights setting; a test passes its own. */
    private val systemSetting: () -> Boolean = { AppleDimFlashingLights.enabled },
    /** When a picture is converted, in nanoseconds; a test passes its own clock. */
    private val nanos: () -> Long = { AppleHostClock.nanos() },
) {
    private val mode = atomic(FlashGuard.FollowSystem)

    /** Set when the picture is taken off or the mode changes, so the next picture starts afresh. */
    private val forgets = atomic(false)

    private val guard = VideoFlashGuard()
    private val cells = FloatArray(VideoFlashGuard.MEASURES)
    private var guarding = false

    /** Sets the mode and answers whether it changed. A change starts the history afresh. */
    fun setMode(next: FlashGuard): Boolean {
        if (mode.getAndSet(next) == next) return false
        forgets.value = true
        return true
    }

    /** Starts the history afresh at the next picture, as after the picture is taken off. */
    fun forget() {
        forgets.value = true
    }

    private fun wanted(): Boolean = when (mode.value) {
        FlashGuard.On -> true
        FlashGuard.FollowSystem -> systemSetting()
        FlashGuard.Off -> false
    }

    /**
     * The factor to draw [rgba] with, tightly packed RGBA rows of [width] pixels: 1 outside a
     * flashing run and while the guard is off. Measures [rgba] on a sparse lattice of it.
     */
    fun factorFor(rgba: ByteArray, width: Int, height: Int): Float {
        val wanted = wanted()
        val forget = forgets.getAndSet(false)
        if (wanted != guarding || forget) guard.reset()
        guarding = wanted
        if (!wanted || width <= 0 || height <= 0 || rgba.size.toLong() != width.toLong() * height * 4) return 1f
        VideoFlashGuard.cellsFromRgba(rgba, width, height, into = cells)
        return guard.factorFor(cells, nanos())
    }

    /** The factor to draw a picture again with that was shown with [shown]: 1 once the guard is off. */
    fun held(shown: Float): Float = if (guarding && !forgets.value && wanted()) shown else 1f
}
