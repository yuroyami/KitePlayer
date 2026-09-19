package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import kotlin.math.round

/**
 * The palette actually drawn with: the one asked for, reached gradually, and leaning slightly
 * towards the key of the music.
 *
 * A change of palette fades over eight accepted pulses instead of cutting, by blending the old colours into the
 * new ones; with no tempo it takes three seconds. When the key is clear, every hue turns up to twenty
 * degrees towards the key's own colour, so two songs in the same palette still look a little different.
 * The lean comes in over two seconds, and its hue moves at most a quarter turn a second. When the key
 * becomes unknown the lean keeps its hue and fades out over eight seconds instead of snapping back.
 */
internal class PaletteFade {
    private var target: VizPalette? = null
    private var from: VizPalette? = null
    private var mixed: VizPalette? = null
    private var progress = 1f
    private var leanedFrom: VizPalette? = null
    private var leanedBy = 0f
    private var leaned: VizPalette? = null
    private var leanHue = 0f
    private var leanAmount = 0f
    private var hasLeanHue = false

    fun advance(wanted: VizPalette, frame: SpectrumFrame, deltaSeconds: Float): VizPalette =
        leanTowardsKey(blend(wanted, frame, deltaSeconds), frame, deltaSeconds)

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
        val seconds = if (frame.rhythm?.usable == true) 8f * 60f / frame.bpm else 3f
        progress = (progress + deltaSeconds / seconds).coerceAtMost(1f)
        val eased = progress * progress * (3f - 2f * progress)
        val next = if (progress >= 1f) wanted else (from ?: wanted).mixedWith(wanted, eased)
        mixed = next
        return next
    }

    private fun leanTowardsKey(base: VizPalette, frame: SpectrumFrame, deltaSeconds: Float): VizPalette {
        val dt = deltaSeconds.takeIf { it.isFinite() && it > 0f } ?: 0f
        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        if (sure > 0f) {
            if (!hasLeanHue) {
                leanHue = frame.keyHue
                hasLeanHue = true
            } else {
                var step = frame.keyHue - leanHue
                if (step > 0.5f) step -= 1f
                if (step < -0.5f) step += 1f
                leanHue += step.coerceIn(-HUE_TURNS_PER_SECOND * dt, HUE_TURNS_PER_SECOND * dt)
                leanHue -= kotlin.math.floor(leanHue)
            }
        }
        // Rise over two seconds; fall over eight, keeping the last hue while it fades.
        leanAmount = if (sure >= leanAmount) minOf(sure, leanAmount + dt / RISE_SECONDS)
            else maxOf(sure, leanAmount - dt / FADE_SECONDS)
        if (leanAmount <= 0f || !hasLeanHue) return base
        var towards = leanHue * 360f - base.baseHue
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        // Whole degrees only, so the palette is rebuilt when the lean moves rather than every frame.
        val degrees = round(towards.coerceIn(-20f, 20f) * leanAmount)
        if (degrees == 0f) return base
        if (base !== leanedFrom || degrees != leanedBy) {
            leaned = base.turned(degrees)
            leanedFrom = base
            leanedBy = degrees
        }
        return leaned ?: base
    }

    private companion object {
        const val HUE_TURNS_PER_SECOND = 0.25f
        const val RISE_SECONDS = 2f
        const val FADE_SECONDS = 8f
    }
}
