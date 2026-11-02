package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlin.test.Test
import kotlin.test.assertEquals

/** The system's caption settings, as the override the player draws with (#679). */
class SystemCaptionStyleTest {

    private val yellow = 0xFFFFFF00.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun largeYellowOnBlackComesThroughAsItIsSet() {
        val style = systemCaptionOverride(yellow, black, window = 0, CaptionEdge.Unspecified, edgeColor = null, fontFamily = null)
        assertEquals(yellow, style.primaryColor)
        assertEquals(black, style.backgroundColor)
        assertEquals(null, style.outlineColor, "an edge the system leaves unset keeps the author's")
        assertEquals(null, style.shadowColor)
    }

    @Test
    fun anOutlineColoursTheOutlineAndDrawsNoShadow() {
        val style = systemCaptionOverride(null, null, null, CaptionEdge.Outline, edgeColor = 0xFF0000FF.toInt(), fontFamily = null)
        assertEquals(0xFF0000FF.toInt(), style.outlineColor)
        assertEquals(null, style.outlineWidthPx, "the width stays the renderer's")
        assertEquals(0, style.shadowColor)
    }

    @Test
    fun aDropShadowDrawsAShadowAndNoOutline() {
        val style = systemCaptionOverride(null, null, null, CaptionEdge.DropShadow, edgeColor = null, fontFamily = null)
        assertEquals(black, style.shadowColor, "a shadow with no colour of its own is black")
        assertEquals(0f, style.outlineWidthPx)
    }

    @Test
    fun noEdgeDrawsNeither() {
        val style = systemCaptionOverride(null, null, null, CaptionEdge.None, edgeColor = null, fontFamily = null)
        assertEquals(0f, style.outlineWidthPx)
        assertEquals(0, style.shadowColor)
    }

    @Test
    fun theWindowColourStandsInForAMissingLineBackground() {
        val grey = 0x80404040.toInt()
        assertEquals(grey, systemCaptionOverride(null, null, grey, CaptionEdge.Unspecified, null, null).backgroundColor)
        assertEquals(grey, systemCaptionOverride(null, 0, grey, CaptionEdge.Unspecified, null, null).backgroundColor)
        assertEquals(black, systemCaptionOverride(null, black, grey, CaptionEdge.Unspecified, null, null).backgroundColor)
    }

    @Test
    fun nothingSetChangesNothing() {
        assertEquals(SubtitleStyleOverride(), systemCaptionOverride(null, null, null, CaptionEdge.Unspecified, null, null))
    }

    @Test
    fun theTypefaceComesThrough() {
        assertEquals("monospace", systemCaptionOverride(null, null, null, CaptionEdge.Unspecified, null, "monospace").fontFamily)
    }
}
