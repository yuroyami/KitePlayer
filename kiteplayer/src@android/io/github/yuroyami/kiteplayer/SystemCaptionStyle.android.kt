package io.github.yuroyami.kiteplayer

import android.content.Context
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import java.util.Locale

/**
 * The caption style the viewer set under Accessibility, Captions, once for every app (#679), or
 * null while captions are off there, which leaves the author's style. It carries the text and
 * background colours, the edge and the typeface; the text size is [systemSubtitleScale]. Pass it to
 * [KitePlayer.setSubtitleStyle], or let [followSystemCaptionStyle] do that and follow each change.
 *
 * ASS that libass typesets keeps its own look, as with any style override.
 */
public fun systemSubtitleStyle(context: Context): SubtitleStyleOverride? {
    val manager = context.getSystemService(CaptioningManager::class.java) ?: return null
    if (!manager.isEnabled) return null
    return overrideOf(manager.userStyle)
}

/**
 * The caption text size the viewer set there, as a factor for [KitePlayer.setSubtitleScale]: 1 for
 * the standard size, and 1 while captions are off there.
 */
public fun systemSubtitleScale(context: Context): Float {
    val manager = context.getSystemService(CaptioningManager::class.java) ?: return 1f
    return if (manager.isEnabled) manager.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f else 1f
}

/**
 * Applies the viewer's system caption settings to this player now, its [systemSubtitleStyle] and
 * [systemSubtitleScale], and again at every change of them, until the handle is closed (#679).
 * While the viewer has captions off, it takes the override away and sets the scale back to 1, so
 * a style or scale the app set itself is replaced for as long as the handle is open.
 */
public fun KitePlayer.followSystemCaptionStyle(context: Context): AutoCloseable {
    val manager = context.getSystemService(CaptioningManager::class.java) ?: return AutoCloseable {}
    val player = this
    val app = context.applicationContext ?: context
    fun apply() {
        try {
            player.setSubtitleStyle(systemSubtitleStyle(app))
            player.setSubtitleScale(systemSubtitleScale(app))
        } catch (_: IllegalStateException) {
            // The player closed while the handle was open; there is nothing left to style.
        }
    }
    val listener = object : CaptioningManager.CaptioningChangeListener() {
        override fun onEnabledChanged(enabled: Boolean) = apply()
        override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) = apply()
        override fun onFontScaleChanged(fontScale: Float) = apply()
        override fun onLocaleChanged(locale: Locale?) = Unit
    }
    apply()
    manager.addCaptioningChangeListener(listener)
    return AutoCloseable { manager.removeCaptioningChangeListener(listener) }
}

private fun overrideOf(style: CaptioningManager.CaptionStyle): SubtitleStyleOverride = systemCaptionOverride(
    foreground = style.foregroundColor.takeIf { style.hasForegroundColor() },
    background = style.backgroundColor.takeIf { style.hasBackgroundColor() },
    window = style.windowColor.takeIf { style.hasWindowColor() },
    edge = if (style.hasEdgeType()) captionEdgeOf(style.edgeType) else CaptionEdge.Unspecified,
    edgeColor = style.edgeColor.takeIf { style.hasEdgeColor() },
    fontFamily = style.typeface?.let(::familyOf),
)

/** Android's edge type, as the shared mapping names it. */
internal fun captionEdgeOf(edgeType: Int): CaptionEdge = when (edgeType) {
    CaptioningManager.CaptionStyle.EDGE_TYPE_NONE -> CaptionEdge.None
    CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE -> CaptionEdge.Outline
    CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW -> CaptionEdge.DropShadow
    CaptioningManager.CaptionStyle.EDGE_TYPE_RAISED -> CaptionEdge.Raised
    CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED -> CaptionEdge.Depressed
    else -> CaptionEdge.Unspecified
}

/**
 * The family name of one of the typefaces the caption settings offer, which the subtitle
 * rasterizer opens by name, or null for the default. Android gives a typeface and no name, and
 * hands back the cached instance of each family, so each is compared with the one its name opens.
 */
private fun familyOf(typeface: Typeface): String? =
    CAPTION_FAMILIES.firstOrNull { Typeface.create(it, Typeface.NORMAL) == typeface }

private val CAPTION_FAMILIES = listOf(
    "sans-serif", "sans-serif-condensed", "sans-serif-monospace", "serif", "serif-monospace",
    "casual", "cursive", "sans-serif-smallcaps", "monospace",
)
