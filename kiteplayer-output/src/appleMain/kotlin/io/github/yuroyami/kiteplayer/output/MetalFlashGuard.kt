@file:OptIn(ExperimentalForeignApi::class, KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.spi.VideoFlashGuard
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.invoke
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSNotificationCenter
import platform.Metal.MTLCommandBufferProtocol
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLRegionMake2D
import platform.Metal.MTLTextureProtocol
import platform.posix.RTLD_LAZY
import platform.posix.dlopen
import platform.posix.dlsym

/**
 * The flash guard of a Metal renderer (#500): the small copy of each picture that the frame
 * composer draws beside the picture itself, its read back, and the shared detector. The rule is in
 * `docs/video-flash-guard.md`.
 *
 * The copy is [WIDTH] by [HEIGHT] texels, one for each point the detector samples, so a texel is
 * the sampler's reading of the picture at that point and the detector reads all of them. It is
 * read back when the next picture is about to draw, by which time the GPU has finished it, so a
 * picture is dimmed one frame after the one that made the run.
 *
 * One renderer's, on its render thread.
 */
internal class MetalFlashGuard(device: MTLDeviceProtocol) {

    /** Where the composer draws the copy: pass it as `measureTarget`. */
    val target: MTLTextureProtocol = device.makeTargetTexture(WIDTH, HEIGHT)

    private val guard = VideoFlashGuard()
    private val bytes = ByteArray(WIDTH * HEIGHT * 4)
    private val cells = FloatArray(VideoFlashGuard.CELLS)

    /** The commands that draw the copy not read yet, and when its picture was drawn. */
    private var waiting: MTLCommandBufferProtocol? = null
    private var waitingNanos = 0L

    /** The factor the last picture was drawn with: 1 outside a flashing run. */
    val current: Float get() = guard.current

    /**
     * Reads the copy of the picture drawn last, if one waits, and answers the factor to draw the
     * next picture with.
     */
    fun factorForNext(): Float {
        val commands = waiting ?: return guard.current
        waiting = null
        // Finished long ago in practice: a whole frame time has passed since the commit.
        commands.waitUntilCompleted()
        if (commands.status != platform.Metal.MTLCommandBufferStatusCompleted) return guard.current
        bytes.usePinned { pinned ->
            target.getBytes(
                pinned.addressOf(0),
                bytesPerRow = (WIDTH * 4).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, WIDTH.toULong(), HEIGHT.toULong()),
                mipmapLevel = 0u,
            )
        }
        // The texture is BGRA and the detector reads RGBA.
        for (at in 0 until bytes.size step 4) {
            val blue = bytes[at]
            bytes[at] = bytes[at + 2]
            bytes[at + 2] = blue
        }
        VideoFlashGuard.cellsFromRgba(bytes, WIDTH, HEIGHT, into = cells)
        return guard.factorFor(cells, waitingNanos)
    }

    /** Says that [commands], committed, draw the copy of a picture drawn at [nanos]. */
    fun submitted(commands: MTLCommandBufferProtocol, nanos: Long) {
        waiting = commands
        waitingNanos = nanos
    }

    /** Forgets every picture, as after a clear or a change of mode. */
    fun reset() {
        // The copy is written by commands in flight; the next ones must come after them.
        waiting?.waitUntilCompleted()
        waiting = null
        guard.reset()
    }

    companion object {
        /** Four points across and down in each of the detector's cells. */
        const val WIDTH: Int = VideoFlashGuard.COLUMNS * 4
        const val HEIGHT: Int = VideoFlashGuard.ROWS * 4
    }
}

/**
 * The system's Dim Flashing Lights setting, from MediaAccessibility (iOS 16.4, macOS 13.3). False
 * on a system older than the setting. The function and its change notification are looked up by
 * name at run time, so a binary that links this class still loads on an older system.
 */
internal object AppleDimFlashingLights {

    private val framework = dlopen(
        "/System/Library/Frameworks/MediaAccessibility.framework/MediaAccessibility",
        RTLD_LAZY,
    )

    private val reader: CPointer<CFunction<() -> UByte>>? =
        framework?.let { dlsym(it, "MADimFlashingLightsEnabled") }?.reinterpret()

    private val state = atomic(read())

    init {
        val name = framework?.let { dlsym(it, "kMADimFlashingLightsChangedNotification") }
            ?.reinterpret<CFStringRefVar>()?.pointed?.value
            ?.let { CFBridgingRelease(CFRetain(it)) as String? }
        if (name != null) {
            // Kept for the life of the process, as the setting is.
            NSNotificationCenter.defaultCenter.addObserverForName(name, `object` = null, queue = null) { _ ->
                state.value = read()
            }
        }
    }

    private fun read(): Boolean = reader?.invoke()?.let { it != 0.toUByte() } ?: false

    /** True when this system has the setting. */
    val known: Boolean get() = reader != null

    /** True while the viewer has Dim Flashing Lights on. Any thread. */
    val enabled: Boolean get() = state.value
}
