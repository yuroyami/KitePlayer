@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRefVar
import platform.CoreGraphics.CGColorGetComponents
import platform.CoreGraphics.CGColorGetNumberOfComponents
import platform.CoreGraphics.CGColorRelease
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSNotificationCenter
import platform.posix.RTLD_LAZY
import platform.posix.dlopen
import platform.posix.dlsym

/**
 * The caption style the viewer set under Accessibility, Subtitles and Captioning, once for every
 * app (#679): the text and background colours with their opacities, and the edge. A value the
 * viewer left to the video is left to the author. The text size is [systemSubtitleScale]. Pass it
 * to [KitePlayer.setSubtitleStyle], or let [followSystemCaptionStyle] do that and follow each
 * change. Null on a system with no caption settings to read.
 *
 * ASS that libass typesets keeps its own look, as with any style override.
 */
public fun systemSubtitleStyle(): SubtitleStyleOverride? = AppleCaptionAppearance.style()

/** The caption text size the viewer set there, as a factor for [KitePlayer.setSubtitleScale]: 1 for the standard size. */
public fun systemSubtitleScale(): Float = AppleCaptionAppearance.scale()

/**
 * Applies the viewer's system caption settings to this player now, its [systemSubtitleStyle] and
 * [systemSubtitleScale], and again at every change of them, until the handle is closed (#679). A
 * style or scale the app set itself is replaced for as long as the handle is open.
 */
public fun KitePlayer.followSystemCaptionStyle(): AutoCloseable {
    val player = this
    fun apply() {
        try {
            player.setSubtitleStyle(systemSubtitleStyle())
            player.setSubtitleScale(systemSubtitleScale())
        } catch (_: IllegalStateException) {
            // The player closed while the handle was open; there is nothing left to style.
        }
    }
    apply()
    val name = AppleCaptionAppearance.changedNotification ?: return AutoCloseable {}
    val observer = NSNotificationCenter.defaultCenter.addObserverForName(name, `object` = null, queue = null) { _ -> apply() }
    return AutoCloseable { NSNotificationCenter.defaultCenter.removeObserver(observer) }
}

/**
 * MediaAccessibility's caption appearance calls, looked up by name, as the flash guard looks up
 * its own, so nothing here depends on how the platform bindings spell them. Each takes the domain
 * and a place for the behaviour: whether the viewer's value is to be used, or the video's own when
 * it has one.
 */
private object AppleCaptionAppearance {
    private val framework = dlopen("/System/Library/Frameworks/MediaAccessibility.framework/MediaAccessibility", RTLD_LAZY)

    private fun symbol(name: String): COpaquePointer? = framework?.let { dlsym(it, name) }

    private val copyForeground = symbol("MACaptionAppearanceCopyForegroundColor")
        ?.reinterpret<CFunction<(Long, CPointer<LongVar>?) -> COpaquePointer?>>()
    private val copyBackground = symbol("MACaptionAppearanceCopyBackgroundColor")
        ?.reinterpret<CFunction<(Long, CPointer<LongVar>?) -> COpaquePointer?>>()
    private val copyWindow = symbol("MACaptionAppearanceCopyWindowColor")
        ?.reinterpret<CFunction<(Long, CPointer<LongVar>?) -> COpaquePointer?>>()
    private val foregroundOpacity = symbol("MACaptionAppearanceGetForegroundOpacity")
        ?.reinterpret<CFunction<(Long, CPointer<LongVar>?) -> Double>>()
    private val backgroundOpacity = symbol("MACaptionAppearanceGetBackgroundOpacity")
        ?.reinterpret<CFunction<(Long, CPointer<LongVar>?) -> Double>>()
    private val windowOpacity = symbol("MACaptionAppearanceGetWindowOpacity")
        ?.reinterpret<CFunction<(Long, CPointer<LongVar>?) -> Double>>()
    private val textEdgeStyle = symbol("MACaptionAppearanceGetTextEdgeStyle")
        ?.reinterpret<CFunction<(Long, CPointer<LongVar>?) -> Long>>()
    private val relativeSize = symbol("MACaptionAppearanceGetRelativeCharacterSize")
        ?.reinterpret<CFunction<(Long, CPointer<LongVar>?) -> Double>>()

    /** The name of the notification sent when the viewer changes a caption setting. */
    val changedNotification: String? = symbol("kMACaptionAppearanceSettingsChangedNotification")
        ?.reinterpret<CFStringRefVar>()?.pointed?.value
        ?.let { CFBridgingRelease(CFRetain(it)) as String? }

    fun style(): SubtitleStyleOverride? {
        if (copyForeground == null) return null
        return systemCaptionOverride(
            foreground = color(copyForeground, foregroundOpacity),
            background = color(copyBackground, backgroundOpacity),
            window = color(copyWindow, windowOpacity),
            edge = edge(),
            edgeColor = null,
            fontFamily = null,
        )
    }

    fun scale(): Float = memScoped {
        val behavior = alloc<LongVar>()
        val size = relativeSize?.invoke(DOMAIN_USER, behavior.ptr) ?: return 1f
        if (behavior.value != BEHAVIOR_USE_VALUE || !size.isFinite() || size <= 0.0) 1f else size.toFloat()
    }

    private fun edge(): CaptionEdge = memScoped {
        val behavior = alloc<LongVar>()
        val style = textEdgeStyle?.invoke(DOMAIN_USER, behavior.ptr) ?: return CaptionEdge.Unspecified
        if (behavior.value != BEHAVIOR_USE_VALUE) return CaptionEdge.Unspecified
        when (style) {
            EDGE_NONE -> CaptionEdge.None
            EDGE_RAISED -> CaptionEdge.Raised
            EDGE_DEPRESSED -> CaptionEdge.Depressed
            EDGE_UNIFORM -> CaptionEdge.Outline
            EDGE_DROP_SHADOW -> CaptionEdge.DropShadow
            else -> CaptionEdge.Unspecified
        }
    }

    /** One colour with its opacity, as ARGB, or null when the viewer leaves it to the video. */
    private fun color(
        copy: CPointer<CFunction<(Long, CPointer<LongVar>?) -> COpaquePointer?>>?,
        opacityOf: CPointer<CFunction<(Long, CPointer<LongVar>?) -> Double>>?,
    ): Int? = memScoped {
        val behavior = alloc<LongVar>()
        val color = copy?.invoke(DOMAIN_USER, behavior.ptr) ?: return null
        try {
            if (behavior.value != BEHAVIOR_USE_VALUE) return null
            val components = CGColorGetComponents(color.reinterpret()) ?: return null
            val count = CGColorGetNumberOfComponents(color.reinterpret()).toInt()
            if (count < 2) return null
            val rgb = count >= 4
            val red = components[0]
            val green = if (rgb) components[1] else red
            val blue = if (rgb) components[2] else red
            val opacityBehavior = alloc<LongVar>()
            val opacity = opacityOf?.invoke(DOMAIN_USER, opacityBehavior.ptr) ?: 1.0
            val alpha = components[count - 1] * (if (opacityBehavior.value == BEHAVIOR_USE_VALUE) opacity else 1.0)
            fun channel(value: Double) = (value.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
            (channel(alpha) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
        } finally {
            CGColorRelease(color.reinterpret())
        }
    }

    private const val DOMAIN_USER = 1L
    private const val BEHAVIOR_USE_VALUE = 0L
    private const val EDGE_NONE = 1L
    private const val EDGE_RAISED = 2L
    private const val EDGE_DEPRESSED = 3L
    private const val EDGE_UNIFORM = 4L
    private const val EDGE_DROP_SHADOW = 5L
}
