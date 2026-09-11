package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import kotlin.math.round

/**
 * The palette actually drawn with: the one asked for, reached gradually, and leaning slightly
 * towards the key of the music.
 *
 * A change of palette fades over two bars instead of cutting, by blending the old colours into the
 * new ones; with no tempo it takes three seconds. When the key is clear, every hue turns up to twenty
 * degrees towards the key's own colour, so two songs in the same palette still look a little different.
 */
internal class PaletteFade {
    private var target: VizPalette? = null
    private var from: VizPalette? = null
    private var mixed: VizPalette? = null
    private var progress = 1f
    private var leanedFrom: VizPalette? = null
    private var leanedBy = 0f
    private var leaned: VizPalette? = null

    fun advance(wanted: VizPalette, frame: SpectrumFrame, deltaSeconds: Float): VizPalette =
        leanTowardsKey(blend(wanted, frame, deltaSeconds), frame)

    private fun blend(wanted: VizPalette, frame: SpectrumFrame, deltaSeconds: Float): VizPalette {
        val shown = mixed
        if (shown == null) {
            target = wanted
            mixed = wanted
            return wanted
        }
        if (wanted !== target) {
            from = shown
            target = wanted
            progress = 0f
        }
        if (progress >= 1f) return shown
        val seconds = if (frame.bpm > 0f && frame.beatConfidence > 0.4f) 8f * 60f / frame.bpm else 3f
        progress = (progress + deltaSeconds / seconds).coerceAtMost(1f)
        val eased = progress * progress * (3f - 2f * progress)
        val next = if (progress >= 1f) wanted else (from ?: wanted).mixedWith(wanted, eased)
        mixed = next
        return next
    }

    private fun leanTowardsKey(base: VizPalette, frame: SpectrumFrame): VizPalette {
        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = frame.keyHue * 360f - base.baseHue
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        // Whole degrees only, so the palette is rebuilt when the lean moves rather than every frame.
        val degrees = round(towards.coerceIn(-20f, 20f) * sure)
        if (degrees == 0f) return base
        if (base !== leanedFrom || degrees != leanedBy) {
            leaned = base.turned(degrees)
            leanedFrom = base
            leanedBy = degrees
        }
        return leaned ?: base
    }
}
