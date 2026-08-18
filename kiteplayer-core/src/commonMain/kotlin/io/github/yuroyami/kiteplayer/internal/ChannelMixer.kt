package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlin.math.min

/**
 * A channel layout this mixer has a matrix for, identified by the native order mask.
 *
 * The mask is the identity, not the channel count. Six channels are 5.1 with side surrounds or 5.1
 * with back surrounds, and the two carry their surround content in differently named speakers. They
 * happen to share a stereo matrix, and they are still separate cases here: conflating them in the
 * model is how a later target layout that treats side and back differently starts routing dialogue
 * to the wrong speaker.
 *
 * [channels] is the number of bits set in [mask], and the channel order inside a buffer is the order
 * of those bits from the lowest upward. That is the order the matrices in [ChannelMixer] are written
 * in, and the order the label of each entry below spells out.
 */
internal enum class MixLayout(val mask: Long, val channels: Int, val label: String) {
    /** `FC` */
    Mono(0x4L, 1, "mono"),

    /** `FL FR` */
    Stereo(0x3L, 2, "stereo"),

    /** `FL FR LFE` */
    Surround21(0xBL, 3, "2.1"),

    /** `FL FR BL BR` */
    Quad(0x33L, 4, "quad"),

    /** `FL FR FC BL BR` */
    Surround50(0x37L, 5, "5.0"),

    /** `FL FR FC LFE BL BR` */
    Surround51(0x3FL, 6, "5.1"),

    /** `FL FR FC LFE SL SR` */
    Surround51Side(0x60FL, 6, "5.1 side"),

    /** `FL FR FC LFE BC SL SR` */
    Surround61(0x70FL, 7, "6.1"),

    /** `FL FR FC LFE BL BR SL SR` */
    Surround71(0x63FL, 8, "7.1"),
    ;

    companion object {
        fun forMask(mask: Long): MixLayout? = entries.firstOrNull { it.mask == mask }

        /**
         * The conventional layout for a channel count, for a stream that declared no mask.
         *
         * Every use of this is reported as a guess, because a count cannot answer the question. The
         * choice for each count is the layout containers and decoders declare by default, and where
         * that default is a layout not named here (three channels are usually `FL FR FC`, five are
         * usually the side variant of 5.0) the layout named here carries the same coefficients in
         * the same channel positions, so the stereo result is the same either way.
         */
        fun forChannelCount(channels: Int): MixLayout? = when (channels) {
            1 -> Mono
            2 -> Stereo
            3 -> Surround21
            4 -> Quad
            5 -> Surround50
            6 -> Surround51Side
            7 -> Surround61
            8 -> Surround71
            else -> null
        }
    }
}

/**
 * Mixes a decoder's channels into the layout the device accepted.
 *
 * Without this stage a multichannel file plays as garbage: the decoder hands over six interleaved
 * channels, the device takes two, and every third sample lands in the wrong speaker at the wrong
 * time. The fix is a matrix, and the matrix has to be chosen by what the channels *are*, which is
 * the layout mask, and never by how many of them there are.
 *
 * ### The matrices, into stereo
 *
 * `M` is -3 dB, which is `1 / sqrt(2)`, which is 0.70710678. Centre, LFE and every surround enter
 * both stereo channels at that level, which is the standard downmix. The rows below are exactly what
 * the code applies, with the source channels in native order:
 *
 * - mono `FC`: `L = FC`, `R = FC`
 * - stereo `FL FR`: `L = FL`, `R = FR`
 * - 2.1 `FL FR LFE`: `L = FL + M*LFE`, `R = FR + M*LFE`
 * - quad `FL FR BL BR`: `L = FL + M*BL`, `R = FR + M*BR`
 * - 5.0 `FL FR FC BL BR`: `L = FL + M*FC + M*BL`, `R = FR + M*FC + M*BR`
 * - 5.1 `FL FR FC LFE BL BR`: `L = FL + M*FC + M*LFE + M*BL`, `R = FR + M*FC + M*LFE + M*BR`
 * - 5.1 side `FL FR FC LFE SL SR`: `L = FL + M*FC + M*LFE + M*SL`,
 *   `R = FR + M*FC + M*LFE + M*SR`
 * - 6.1 `FL FR FC LFE BC SL SR`: `L = FL + M*FC + M*LFE + M*BC + M*SL`,
 *   `R = FR + M*FC + M*LFE + M*BC + M*SR`
 * - 7.1 `FL FR FC LFE BL BR SL SR`: `L = FL + M*FC + M*LFE + M*BL + M*SL`,
 *   `R = FR + M*FC + M*LFE + M*BR + M*SR`
 *
 * There is no normalisation and no limiter. The coefficients above are applied as written, so a
 * source that is loud in several channels at once can sum above full scale and clip at the device.
 * A measured normalisation belongs with the production rate conversion, which replaces this stage
 * and the one next to it; see KPKMP-PAST.md section 11.
 *
 * ### When the layout is not certain
 *
 * Three cases where the mix cannot be keyed on a layout the source declared, and all three are
 * reported once through the warning callback rather than being papered over:
 *
 * - the stream declared no mask, so the layout is guessed from the channel count and mixed with that
 *   layout's matrix;
 * - the mask names something not modelled here, or disagrees with the channel count, in which case
 *   the first channels pass through in source order;
 * - the source layout is known and no matrix reaches the target channel count, same pass-through.
 *
 * Only the source layout is ever guessed. The target is the device, its channel count is the
 * authority, and a device asking for two channels wants stereo.
 *
 * One instance per format pair, held by [AudioPipeline]. Not thread safe: it belongs to the audio
 * feeder, like everything else in the pipeline.
 */
internal class ChannelMixer(
    private val source: AudioFormat,
    private val target: AudioFormat,
    onWarning: (PlaybackWarning) -> Unit = {},
) {
    init {
        require(source.channels > 0) { "a source format with ${source.channels} channels cannot be mixed" }
        require(target.channels > 0) { "a target format with ${target.channels} channels cannot be mixed" }
    }

    private val sourceChannels: Int = source.channels
    private val targetChannels: Int = target.channels

    /** The layout the mask named, when it named one this mixer models and the count agrees with it. */
    private val maskedLayout: MixLayout? = source.channelLayoutMask
        ?.let { MixLayout.forMask(it) }
        ?.takeIf { it.channels == sourceChannels }

    /**
     * What the mix keys on: the mask's layout, or the conventional layout for the channel count when
     * the stream declared no mask at all. A mask that names something else is not overridden by a
     * guess, because it already said the layout is not one of these.
     */
    val sourceLayout: MixLayout? =
        if (source.channelLayoutMask == null) MixLayout.forChannelCount(sourceChannels) else maskedLayout

    /** Row-major, `targetChannels` rows of `sourceChannels` gains. Null means pass channels through. */
    private val matrix: FloatArray? = matrixFor(sourceLayout, sourceChannels, targetChannels)

    /**
     * True when the channels are copied rather than mixed, which is either a target that already has
     * the source's channel count or a layout pair with no matrix. A pass-through with UNEQUAL counts
     * still restrides frame by frame in [mix]; only [isIdentity] may skip this stage entirely.
     */
    val isPassThrough: Boolean get() = matrix == null

    /**
     * True when the output would be byte-for-byte the input, so a caller may alias instead of
     * calling [mix] (audit F-MIX1). This is NOT [isPassThrough]: a pass-through with unequal
     * counts must still run [mix] for its frame-wise restride, and aliasing it handed a
     * three-channel interleave to a stereo consumer with every sample on the wrong speaker.
     */
    val isIdentity: Boolean get() = matrix == null && sourceChannels == targetChannels

    init {
        layoutWarning()?.let(onWarning)
    }

    /**
     * Mixes [frames] sample frames of interleaved [input] into interleaved [output].
     *
     * The two arrays are never the same array: the channel count changes, so a mix in place would
     * overwrite input it has not read yet.
     */
    fun mix(input: FloatArray, output: FloatArray, frames: Int) {
        if (frames <= 0) return
        require(input.size >= frames * sourceChannels) {
            "$frames frames of $sourceChannels channels need ${frames * sourceChannels} values, got ${input.size}"
        }
        require(output.size >= frames * targetChannels) {
            "$frames frames of $targetChannels channels need ${frames * targetChannels} values, got ${output.size}"
        }

        val rows = matrix
        if (rows == null) {
            passThrough(input, output, frames)
            return
        }

        var inBase = 0
        var outBase = 0
        for (frame in 0 until frames) {
            for (out in 0 until targetChannels) {
                val row = out * sourceChannels
                var sum = 0f
                for (channel in 0 until sourceChannels) {
                    sum += rows[row + channel] * input[inBase + channel]
                }
                output[outBase + out] = sum
            }
            inBase += sourceChannels
            outBase += targetChannels
        }
    }

    /** Channels in source order, as many as fit. A wider target keeps its extra channels silent. */
    private fun passThrough(input: FloatArray, output: FloatArray, frames: Int) {
        if (sourceChannels == targetChannels) {
            input.copyInto(output, destinationOffset = 0, startIndex = 0, endIndex = frames * sourceChannels)
            return
        }
        val copied = min(sourceChannels, targetChannels)
        var inBase = 0
        var outBase = 0
        for (frame in 0 until frames) {
            for (channel in 0 until copied) output[outBase + channel] = input[inBase + channel]
            for (channel in copied until targetChannels) output[outBase + channel] = 0f
            inBase += sourceChannels
            outBase += targetChannels
        }
    }

    /** The one warning this mixer may emit, or null when the layout is known and mixed properly. */
    private fun layoutWarning(): PlaybackWarning? {
        val mask = source.channelLayoutMask
        val kept = "so the first ${min(sourceChannels, targetChannels)} channels pass through"
        val detail = when {
            mask == null && sourceLayout == null ->
                "the stream declared none and $sourceChannels channels has no conventional layout, $kept"

            mask == null ->
                "the stream declared none, so ${sourceLayout?.label} was assumed from the channel count"

            maskedLayout == null && MixLayout.forMask(mask) != null ->
                "mask 0x${mask.toString(16)} is ${MixLayout.forMask(mask)?.label} and the stream has " +
                    "$sourceChannels channels, so neither of the two can be trusted and $kept"

            maskedLayout == null ->
                "mask 0x${mask.toString(16)} is not a layout this build models, $kept"

            matrix == null && sourceChannels != targetChannels ->
                "no matrix maps ${sourceLayout?.label} to $targetChannels channels, $kept"

            else -> return null
        }
        return PlaybackWarning.ChannelLayoutUnknown(sourceChannels, detail)
    }

    internal companion object {
        /** -3 dB as an amplitude factor, which is `1 / sqrt(2)`. */
        const val MINUS_3_DB: Float = 0.70710678f

        /**
         * The matrix for one layout pair, or null when the channels are to be copied instead.
         *
         * Equal channel counts copy: the decoder already produces what the device asked for, and a
         * matrix that reorders known layouts into each other is a Horizon B concern.
         */
        private fun matrixFor(layout: MixLayout?, sourceChannels: Int, targetChannels: Int): FloatArray? {
            if (sourceChannels == targetChannels) return null
            if (targetChannels != 2 || layout == null) return null

            val m = MINUS_3_DB
            return when (layout) {
                // FC
                MixLayout.Mono -> floatArrayOf(
                    1f,
                    1f,
                )
                // FL FR
                MixLayout.Stereo -> floatArrayOf(
                    1f, 0f,
                    0f, 1f,
                )
                // FL FR LFE
                MixLayout.Surround21 -> floatArrayOf(
                    1f, 0f, m,
                    0f, 1f, m,
                )
                // FL FR BL BR
                MixLayout.Quad -> floatArrayOf(
                    1f, 0f, m, 0f,
                    0f, 1f, 0f, m,
                )
                // FL FR FC BL BR
                MixLayout.Surround50 -> floatArrayOf(
                    1f, 0f, m, m, 0f,
                    0f, 1f, m, 0f, m,
                )
                // FL FR FC LFE BL BR
                MixLayout.Surround51 -> floatArrayOf(
                    1f, 0f, m, m, m, 0f,
                    0f, 1f, m, m, 0f, m,
                )
                // FL FR FC LFE SL SR
                MixLayout.Surround51Side -> floatArrayOf(
                    1f, 0f, m, m, m, 0f,
                    0f, 1f, m, m, 0f, m,
                )
                // FL FR FC LFE BC SL SR
                MixLayout.Surround61 -> floatArrayOf(
                    1f, 0f, m, m, m, m, 0f,
                    0f, 1f, m, m, m, 0f, m,
                )
                // FL FR FC LFE BL BR SL SR
                MixLayout.Surround71 -> floatArrayOf(
                    1f, 0f, m, m, m, 0f, m, 0f,
                    0f, 1f, m, m, 0f, m, 0f, m,
                )
            }
        }

    }
}
