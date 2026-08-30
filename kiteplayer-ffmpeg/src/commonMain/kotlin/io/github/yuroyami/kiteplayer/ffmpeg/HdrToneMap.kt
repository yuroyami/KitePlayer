package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The software half of the HDR-to-SDR law: the same arithmetic as the Metal
 * shader's kp_tone_map, LUT-accelerated for the CPU tier. PQ or HLG electricals are linearized
 * per channel, BT.2020 primaries fold to BT.709, luminance rolls off through the BT.2390 EETF
 * anchored at 203 nits (BT.2408 reference white), and the result encodes as gamma 2.2.
 *
 * Honest limits, shared with the GPU path: srcPeak is fixed at 1000 nits because mastering
 * metadata is not plumbed yet, and the CPU pass costs one extra sweep over the RGBA buffer,
 * which is acceptable only because this tier is already the declared last resort.
 */
internal class HdrToneMap private constructor(
    transfer: ColorTransfer,
    private val gamut2020: Boolean,
) {
    private val isHlg = transfer == ColorTransfer.Hlg

    /** Electrical byte -> linear light in nits (PQ) or scene light 0..1 (HLG, pre-OOTF). */
    private val eotf = DoubleArray(256) { code ->
        val e = code / 255.0
        if (isHlg) {
            if (e <= 0.5) e * e / 3.0
            else (kotlin.math.exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
        } else {
            pqDecode(e) * 10000.0
        }
    }

    /** sqrt-warped scene luminance 0..1 -> the HLG OOTF's Ys^0.2 factor. */
    private val hlgOotf = DoubleArray(1024) { i ->
        val ys = (i / 1023.0).let { it * it }
        if (ys <= 0.0) 0.0 else ys.pow(0.2)
    }

    /** sqrt-warped display luminance 0..lumaMax nits -> the EETF's output/input ratio. */
    private val lumaMax = if (isHlg) 1000.0 else SRC_PEAK
    private val eetfRatio = DoubleArray(1024) { i ->
        val nits = (i / 1023.0).let { it * it } * lumaMax
        if (nits <= 1e-4) 1.0 else eetfNits(nits) / nits
    }

    /** sqrt-warped SDR linear 0..1 -> gamma 2.2 byte. */
    private val encode = IntArray(4096) { i ->
        val lin = (i / 4095.0).let { it * it }
        (lin.pow(1.0 / 2.2) * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    /** Tone-maps [rgba] in place. Alpha bytes are untouched. */
    fun mapInPlace(rgba: ByteArray) {
        var at = 0
        while (at < rgba.size) {
            var r = eotf[rgba[at].toInt() and 0xFF]
            var g = eotf[rgba[at + 1].toInt() and 0xFF]
            var b = eotf[rgba[at + 2].toInt() and 0xFF]
            if (isHlg) {
                // BT.2100 OOTF at Lw = 1000: display = 1000 * Ys^0.2 * scene.
                val ys = 0.2627 * r + 0.6780 * g + 0.0593 * b
                val factor = 1000.0 * hlgOotf[warp1023(ys, 1.0)]
                r *= factor; g *= factor; b *= factor
            }
            if (gamut2020) {
                val r709 = 1.6605 * r - 0.5876 * g - 0.0728 * b
                val g709 = -0.1246 * r + 1.1329 * g - 0.0083 * b
                val b709 = -0.0182 * r - 0.1006 * g + 1.1187 * b
                r = if (r709 > 0.0) r709 else 0.0
                g = if (g709 > 0.0) g709 else 0.0
                b = if (b709 > 0.0) b709 else 0.0
            }
            val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            // Above lumaMax the EETF is flat (its e1 clamp), so the mapped luminance is the
            // one at lumaMax and the ratio shrinks with the real luminance.
            val lumaClamped = if (luma > lumaMax) lumaMax else luma
            val mappedNits = eetfRatio[warp1023(lumaClamped, lumaMax)] * lumaClamped
            val ratio = if (luma > 1e-4) mappedNits / luma / 203.0 else 1.0 / 203.0
            rgba[at] = encode[warp4095(r * ratio)].toByte()
            rgba[at + 1] = encode[warp4095(g * ratio)].toByte()
            rgba[at + 2] = encode[warp4095(b * ratio)].toByte()
            at += 4
        }
    }

    private fun warp1023(value: Double, max: Double): Int {
        val n = value / max
        if (n <= 0.0) return 0
        if (n >= 1.0) return 1023
        return (sqrt(n) * 1023.0 + 0.5).toInt()
    }

    private fun warp4095(linear: Double): Int {
        if (linear <= 0.0) return 0
        if (linear >= 1.0) return 4095
        return (sqrt(linear) * 4095.0 + 0.5).toInt()
    }

    internal companion object {
        internal const val SRC_PEAK = 1000.0

        private const val PQ_M1 = 0.1593017578125
        private const val PQ_M2 = 78.84375
        private const val PQ_C1 = 0.8359375
        private const val PQ_C2 = 18.8515625
        private const val PQ_C3 = 18.6875

        internal fun pqEncode(y: Double): Double {
            val p = y.coerceAtLeast(0.0).pow(PQ_M1)
            return ((PQ_C1 + PQ_C2 * p) / (1.0 + PQ_C3 * p)).pow(PQ_M2)
        }

        internal fun pqDecode(e: Double): Double {
            val p = e.coerceAtLeast(0.0).pow(1.0 / PQ_M2)
            return ((p - PQ_C1).coerceAtLeast(0.0) / (PQ_C2 - PQ_C3 * p)).pow(1.0 / PQ_M1)
        }

        /** BT.2390 EETF from [0, SRC_PEAK] into [0, 203] nits, in normalized PQ space. */
        internal fun eetfNits(nits: Double): Double {
            val srcPq = pqEncode(SRC_PEAK / 10000.0)
            val dstPq = pqEncode(203.0 / 10000.0)
            val e1 = (pqEncode(nits / 10000.0) / srcPq).coerceIn(0.0, 1.0)
            val maxLum = dstPq / srcPq
            val ks = 1.5 * maxLum - 0.5
            val e2 = if (e1 <= ks) e1 else {
                val t = (e1 - ks) / (1.0 - ks)
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * ks + (t3 - 2 * t2 + t) * (1 - ks) + (-2 * t3 + 3 * t2) * maxLum
            }
            return pqDecode(e2 * srcPq) * 10000.0
        }

        /** Null when the frame is SDR, which is what keeps the SDR path bit-exact. */
        internal fun forColorSpaceOrNull(colorSpace: ColorSpaceInfo): HdrToneMap? {
            if (!colorSpace.isHdr) return null
            return HdrToneMap(
                transfer = colorSpace.transfer,
                gamut2020 = colorSpace.primaries == ColorPrimaries.Bt2020,
            )
        }
    }
}

/**
 * Whether the software converter will roll this colour off to SDR.
 *
 * The SAME decision `toRgba` makes, exposed so a renderer can PUBLISH what happened rather than
 * guess it from the stream's metadata. A renderer that hands HDR to a display able to show it
 * must stay quiet, and only the converter knows which of the two occurred.
 */
internal fun toneMapsHdrColor(colorSpace: ColorSpaceInfo): Boolean =
    HdrToneMap.forColorSpaceOrNull(colorSpace) != null
