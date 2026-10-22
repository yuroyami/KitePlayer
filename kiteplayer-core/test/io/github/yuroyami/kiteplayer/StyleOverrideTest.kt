package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.applyOverride
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The pure half of the style override: named fields replace the authored style on every span,
 * unnamed fields keep the author's word, and no override means no work at all.
 */
class StyleOverrideTest {

    private fun cue(vararg spans: StyledSpan) = SubtitleCue.Text(
        startMicros = 0,
        endMicros = 1_000_000,
        spans = spans.toList(),
    )

    private val authored = cue(
        StyledSpan("plain ", CueStyle(primaryColor = 0xFFFFFFFF.toInt(), fontSizePx = 40f)),
        StyledSpan("loud", CueStyle(primaryColor = 0xFFFF0000.toInt(), bold = true, italic = true)),
    )

    @Test
    fun `a named field replaces the colour on every span and nothing else moves`() {
        val out = applyOverride(listOf(authored), SubtitleStyleOverride(primaryColor = 0xFF00FF00.toInt()))
        val spans = (out.single() as SubtitleCue.Text).spans
        assertTrue(spans.all { it.style.primaryColor == 0xFF00FF00.toInt() })
        assertEquals(40f, spans[0].style.fontSizePx, "an unnamed field keeps the authored value")
        assertTrue(spans[1].style.bold && spans[1].style.italic)
        assertEquals(listOf("plain ", "loud"), spans.map { it.text }, "the text is untouched")
    }

    @Test
    fun `a null override returns the same list instance`() {
        val cues = listOf<SubtitleCue>(authored)
        assertSame(cues, applyOverride(cues, null))
    }

    @Test
    fun `the background box and its padding reach every span`() {
        val out = applyOverride(
            listOf(authored),
            SubtitleStyleOverride(backgroundColor = 0xC0000000.toInt(), backgroundPaddingPx = 6f),
        )
        val spans = (out.single() as SubtitleCue.Text).spans
        assertTrue(spans.all { it.style.backgroundColor == 0xC0000000.toInt() })
        assertTrue(spans.all { it.style.backgroundPaddingPx == 6f })
    }

    @Test
    fun `bitmap cues pass through untouched`() {
        val bitmap = SubtitleCue.Bitmap(
            startMicros = 0,
            endMicros = 1_000_000,
            regions = listOf(
                BitmapRegion(
                    x = 0, y = 0, width = 1, height = 1, canvasWidth = 10, canvasHeight = 10,
                    bitmap = RgbaBitmap(1, 1, ByteArray(4)),
                ),
            ),
        )
        val out = applyOverride(listOf(bitmap), SubtitleStyleOverride(primaryColor = 0xFF00FF00.toInt()))
        assertSame(bitmap, out.single(), "an override names text properties; authored pixels are not text")
    }

    @Test
    fun `bold and italic can be forced off as well as on`() {
        val out = applyOverride(listOf(authored), SubtitleStyleOverride(bold = false, italic = false))
        val spans = (out.single() as SubtitleCue.Text).spans
        assertTrue(spans.none { it.style.bold || it.style.italic })
    }
}
