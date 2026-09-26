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
 * ### The matrices
 *
 * Every downmix is derived speaker by speaker with the rules of FFmpeg's `build_matrix` in
 * `libswresample/rematrix.c`, at its defaults. Any layout made of the eleven speakers below mixes,
 * not only the nine named in [MixLayout]: 5.0 with side surrounds, 3.0, 4.0 and side quad included.
 * `M` is -3 dB, which is `1 / sqrt(2)`, which is 0.70710678. A speaker the target also has passes at
 * unity. A speaker the target lacks is routed like this:
 *
 * - a front centre into the front pair at `M` each, and a front pair into a lone centre at `M` each;
 * - a side or back pair into the other surround pair, at unity when the source has only one pair
 *   and at `M` when it has both, else into the front speaker on its own side at `M`, else into a
 *   lone centre at `M * M` (0.5) each;
 * - a back centre into a surround pair at `M` each, else into the front pair or a lone centre at
 *   `M * M`;
 * - a front-of-centre pair into the front pair at unity, else into a lone centre at `M` each;
 * - the LFE as [io.github.yuroyami.kiteplayer.DownmixConfig.includeLfe] says: dropped by default,
 *   else into a lone centre at unity or into the front pair at `M` each.
 *
 * Into stereo that gives, for example, 5.1 as `L = FL + M*FC + M*BL`, `R = FR + M*FC + M*BR`, and a
 * 6.1 back centre at 0.5 in each speaker. One rule is this engine's own: mono into stereo copies
 * the one channel into both speakers at unity, where FFmpeg lowers it by 3 dB, because a mono
 * source is not quieter than a stereo one.
 *
 * ### What happens to those coefficients before they are used
 *
 * Both corrections come from `DownmixConfig`, and both defaults are FFmpeg's, measured rather than
 * assumed. The LFE column is ZEROED: `ffmpeg -ac 2` turns a 5.1 clip whose only
 * content is an LFE tone into exact silence, which `ReferencePcmTest` now pins, and the engine used
 * to fold it in at -3 dB instead. Normalisation is OFF, also matching FFmpeg for float output, so
 * the coefficients above are what is applied and a passage loud in several channels at once can
 * still sum past full scale; the engine's own pipeline is float and does not clip on it, and a
 * caller shipping to an integer device can turn normalisation on and pay about 7 dB for the
 * guarantee. Normalisation divides the WHOLE matrix by its largest row sum rather than each row by
 * its own, which keeps left and right in balance.
 *
 * ### Equal channel counts are not automatically a copy
 *
 * When the source and the device both name a layout and those layouts differ, the channels are
 * PERMUTED into the device's order. Six channels are 5.1 with side surrounds or 5.1 with back
 * surrounds, and copying one into the other puts the surround content in speakers the mix never
 * meant. A speaker the source does not carry is left silent rather than filled,
 * because inventing content for it would be upmixing and this stage does not upmix. When either
 * side names no layout, or both name the same one, the copy is still right and is still what runs.
 *
 * ### When the layout is not certain
 *
 * Cases where the mix cannot be keyed on a layout the source declared are reported once through
 * the warning callback rather than being papered over:
 *
 * - the stream declared no mask, so the layout is guessed from the channel count and mixed with that
 *   layout's matrix;
 * - the mask disagrees with the channel count, or names a speaker these rules do not cover (a
 *   height speaker, or one half of a pair), in which case the first channels pass through in source
 *   order;
 * - no matrix reaches the target channel count, which is an upmix, with the same pass-through.
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
    private val policy: io.github.yuroyami.kiteplayer.DownmixConfig =
        io.github.yuroyami.kiteplayer.DownmixConfig(),
) {
    init {
        require(source.channels > 0) { "a source format with ${source.channels} channels cannot be mixed" }
        require(target.channels > 0) { "a target format with ${target.channels} channels cannot be mixed" }
    }

    private val sourceChannels: Int = source.channels
    private val targetChannels: Int = target.channels

    /**
     * What the mix keys on: the declared mask when its speaker count agrees with the channel count,
     * or the conventional layout for the count when the stream declared no mask at all. A mask
     * that disagrees with its count is trusted neither way.
     */
    private val sourceMask: Long? = when (val declared = source.channelLayoutMask) {
        null -> MixLayout.forChannelCount(sourceChannels)?.mask
        else -> declared.takeIf { it.countOneBits() == sourceChannels }
    }

    /** The speakers the DEVICE declared, when their count agrees with its channel count. */
    private val targetMask: Long? = target.channelLayoutMask?.takeIf { it.countOneBits() == targetChannels }

    /** Row-major, `targetChannels` rows of `sourceChannels` gains. Null means pass channels through. */
    private val matrix: FloatArray? = matrixFor(sourceMask, targetMask, sourceChannels, targetChannels, policy)

    /**
     * True when the channels are copied rather than mixed, which is either a target that already has
     * the source's channel count or a layout pair with no matrix. A pass-through with UNEQUAL counts
     * still restrides frame by frame in [mix]; only [isIdentity] may skip this stage entirely.
     */
    val isPassThrough: Boolean get() = matrix == null

    /**
     * True when the output would be byte-for-byte the input, so a caller may alias instead of
     * calling [mix]. This is NOT [isPassThrough]: a pass-through with unequal
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
            mask == null && sourceMask == null ->
                "the stream declared none and $sourceChannels channels has no conventional layout, $kept"

            mask == null ->
                "the stream declared none, so ${describe(sourceMask)} was assumed from the channel count"

            sourceMask == null ->
                "mask 0x${mask.toString(16)} names ${mask.countOneBits()} speakers and the stream has " +
                    "$sourceChannels channels, so neither of the two can be trusted and $kept"

            matrix == null && sourceChannels > targetChannels ->
                "mask 0x${mask.toString(16)} has a speaker the downmix rules do not cover, $kept"

            matrix == null && sourceChannels != targetChannels ->
                "no matrix maps ${describe(sourceMask)} to $targetChannels channels, $kept"

            else -> return null
        }
        return PlaybackWarning.ChannelLayoutUnknown(sourceChannels, detail)
    }

    internal companion object {
        /** -3 dB as an amplitude factor, which is `1 / sqrt(2)`. */
        const val MINUS_3_DB: Float = 0.70710678f

        /** A layout's name when it is one of the nine, and its mask otherwise. */
        private fun describe(mask: Long?): String =
            mask?.let { MixLayout.forMask(it)?.label ?: "mask 0x${it.toString(16)}" } ?: "an unknown layout"

        /**
         * The matrix for one layout pair, or null when the channels are to be copied instead.
         *
         * Equal counts are a copy, or a permutation when both sides name different layouts. A
         * smaller target is a downmix derived per speaker. A wider target is an upmix, which this
         * stage does not do, with one exception: mono into stereo.
         */
        private fun matrixFor(
            sourceMask: Long?,
            targetMask: Long?,
            sourceChannels: Int,
            targetChannels: Int,
            policy: io.github.yuroyami.kiteplayer.DownmixConfig,
        ): FloatArray? {
            if (sourceChannels == targetChannels) {
                // Same count, same layout, or a layout either side did not name: a copy is right.
                if (sourceMask == null || targetMask == null || sourceMask == targetMask) return null
                return reorder(sourceMask, targetMask, sourceChannels)
            }
            if (sourceMask == null) return null
            if (targetChannels > sourceChannels) {
                // The one copy into more speakers: a mono source is not quieter than a stereo one.
                return if (sourceChannels == 1 && targetChannels == 2) floatArrayOf(1f, 1f) else null
            }
            // A device's channel count is the authority. Two channels are stereo, and a device that
            // named no mask gets the conventional layout for its count, which is what Android
            // reports by count.
            val resolvedTarget = when {
                targetChannels == 2 -> MixLayout.Stereo.mask
                else -> targetMask ?: MixLayout.forChannelCount(targetChannels)?.mask ?: return null
            }
            return downmix(sourceMask, resolvedTarget, sourceChannels, targetChannels, policy)
        }

        /**
         * Puts [sourceMask]'s channels into [targetMask]'s speakers, by speaker and never by position.
         *
         * A target speaker the source does not carry takes its nearest equivalent, which in
         * practice means the side and back surrounds standing in for each other: a device with back
         * speakers playing a mix authored for side speakers should play the surround content from
         * the back, not go silent. Anything with no equivalent at all is left silent, because
         * filling it would be upmixing and this stage does not upmix.
         *
         * Null when the mapping turns out to be the identity, which keeps the plain copy path.
         */
        private fun reorder(sourceMask: Long, targetMask: Long, channels: Int): FloatArray? {
            val sourceSpeakers = speakersOf(sourceMask)
            val targetSpeakers = speakersOf(targetMask)
            val rows = FloatArray(channels * channels)
            var identity = true
            for (out in targetSpeakers.indices) {
                val speaker = targetSpeakers[out]
                var from = sourceSpeakers.indexOf(speaker)
                if (from < 0) from = sourceSpeakers.indexOf(equivalentOf(speaker))
                if (from < 0) {
                    identity = false
                    continue
                }
                if (from != out) identity = false
                rows[out * channels + from] = 1f
            }
            return if (identity) null else rows
        }

        /**
         * The speaker that stands in for one the source does not have.
         *
         * Only the side and back surrounds, because they are the only pair that carries the same
         * content under two names. Everything else answers with itself, which finds nothing and
         * leaves the speaker silent.
         */
        private fun equivalentOf(speaker: Int): Int = when (speaker) {
            BACK_LEFT_BIT -> SIDE_LEFT_BIT
            BACK_RIGHT_BIT -> SIDE_RIGHT_BIT
            SIDE_LEFT_BIT -> BACK_LEFT_BIT
            SIDE_RIGHT_BIT -> BACK_RIGHT_BIT
            else -> speaker
        }

        /** The speaker bits of a mask, lowest first, which IS the channel order in a buffer. */
        private fun speakersOf(mask: Long): List<Int> =
            (0 until 64).filter { bit -> (mask shr bit) and 1L == 1L }

        private const val FRONT_LEFT_BIT: Int = 0
        private const val FRONT_RIGHT_BIT: Int = 1
        private const val FRONT_CENTER_BIT: Int = 2

        /** The bit for the low-frequency effects channel in the native order mask. */
        private const val LFE_BIT: Int = 3
        private const val BACK_LEFT_BIT: Int = 4
        private const val BACK_RIGHT_BIT: Int = 5
        private const val FRONT_LEFT_OF_CENTER_BIT: Int = 6
        private const val FRONT_RIGHT_OF_CENTER_BIT: Int = 7
        private const val BACK_CENTER_BIT: Int = 8
        private const val SIDE_LEFT_BIT: Int = 9
        private const val SIDE_RIGHT_BIT: Int = 10

        /** The eleven speakers the downmix rules cover, bits 0 to 10. */
        private const val RULED_SPEAKERS: Int = 11

        private fun has(mask: Long, bit: Int): Boolean = (mask shr bit) and 1L == 1L

        /**
         * True when every speaker has a rule and every pair is whole. FFmpeg refuses the same
         * masks: a left speaker without its right has no symmetric place to go.
         */
        private fun ruled(mask: Long): Boolean {
            if (mask shr RULED_SPEAKERS != 0L) return false
            fun whole(left: Int, right: Int) = has(mask, left) == has(mask, right)
            return whole(FRONT_LEFT_BIT, FRONT_RIGHT_BIT) &&
                whole(BACK_LEFT_BIT, BACK_RIGHT_BIT) &&
                whole(FRONT_LEFT_OF_CENTER_BIT, FRONT_RIGHT_OF_CENTER_BIT) &&
                whole(SIDE_LEFT_BIT, SIDE_RIGHT_BIT)
        }

        /**
         * The downmix of [sourceMask] into [targetMask], by FFmpeg's `build_matrix` rules at its
         * defaults: centre and surround levels of -3 dB, no matrix encoding, and an LFE level of 0,
         * or 1 when [policy] includes the LFE. Null when a speaker has no rule, which leaves the
         * channels to pass through.
         */
        private fun downmix(
            sourceMask: Long,
            targetMask: Long,
            sourceChannels: Int,
            targetChannels: Int,
            policy: io.github.yuroyami.kiteplayer.DownmixConfig,
        ): FloatArray? {
            if (!ruled(sourceMask) || !ruled(targetMask)) return null
            val m = MINUS_3_DB
            val lfeLevel = if (policy.includeLfe) 1f else 0f
            // [target speaker][source speaker], by bit.
            val gain = Array(RULED_SPEAKERS) { FloatArray(RULED_SPEAKERS) }
            for (bit in 0 until RULED_SPEAKERS) {
                if (has(sourceMask, bit) && has(targetMask, bit)) gain[bit][bit] = 1f
            }
            fun source(bit: Int) = has(sourceMask, bit)
            fun target(bit: Int) = has(targetMask, bit)
            fun pair(outLeft: Int, outRight: Int, inLeft: Int, inRight: Int, level: Float) {
                gain[outLeft][inLeft] += level
                gain[outRight][inRight] += level
            }
            val unaccounted = sourceMask and targetMask.inv()
            val lost = { bit: Int -> has(unaccounted, bit) }

            if (lost(FRONT_CENTER_BIT)) {
                if (!target(FRONT_LEFT_BIT)) return null
                pair(FRONT_LEFT_BIT, FRONT_RIGHT_BIT, FRONT_CENTER_BIT, FRONT_CENTER_BIT, m)
            }
            if (lost(FRONT_LEFT_BIT)) {
                if (!target(FRONT_CENTER_BIT)) return null
                gain[FRONT_CENTER_BIT][FRONT_LEFT_BIT] += m
                gain[FRONT_CENTER_BIT][FRONT_RIGHT_BIT] += m
                // The centre level times sqrt(2), which is unity at the default level.
                if (source(FRONT_CENTER_BIT)) gain[FRONT_CENTER_BIT][FRONT_CENTER_BIT] = 1f
            }
            if (lost(BACK_CENTER_BIT)) {
                when {
                    target(BACK_LEFT_BIT) -> pair(BACK_LEFT_BIT, BACK_RIGHT_BIT, BACK_CENTER_BIT, BACK_CENTER_BIT, m)
                    target(SIDE_LEFT_BIT) -> pair(SIDE_LEFT_BIT, SIDE_RIGHT_BIT, BACK_CENTER_BIT, BACK_CENTER_BIT, m)
                    target(FRONT_LEFT_BIT) -> pair(FRONT_LEFT_BIT, FRONT_RIGHT_BIT, BACK_CENTER_BIT, BACK_CENTER_BIT, m * m)
                    target(FRONT_CENTER_BIT) -> gain[FRONT_CENTER_BIT][BACK_CENTER_BIT] += m * m
                    else -> return null
                }
            }
            if (lost(BACK_LEFT_BIT)) {
                when {
                    target(BACK_CENTER_BIT) -> {
                        gain[BACK_CENTER_BIT][BACK_LEFT_BIT] += m
                        gain[BACK_CENTER_BIT][BACK_RIGHT_BIT] += m
                    }
                    // A second surround pair joins the first at -3 dB; a lone pair moves at unity.
                    target(SIDE_LEFT_BIT) -> pair(
                        SIDE_LEFT_BIT, SIDE_RIGHT_BIT, BACK_LEFT_BIT, BACK_RIGHT_BIT,
                        if (source(SIDE_LEFT_BIT)) m else 1f,
                    )
                    target(FRONT_LEFT_BIT) -> pair(FRONT_LEFT_BIT, FRONT_RIGHT_BIT, BACK_LEFT_BIT, BACK_RIGHT_BIT, m)
                    target(FRONT_CENTER_BIT) -> {
                        gain[FRONT_CENTER_BIT][BACK_LEFT_BIT] += m * m
                        gain[FRONT_CENTER_BIT][BACK_RIGHT_BIT] += m * m
                    }
                    else -> return null
                }
            }
            if (lost(SIDE_LEFT_BIT)) {
                when {
                    target(BACK_LEFT_BIT) -> pair(
                        BACK_LEFT_BIT, BACK_RIGHT_BIT, SIDE_LEFT_BIT, SIDE_RIGHT_BIT,
                        if (source(BACK_LEFT_BIT)) m else 1f,
                    )
                    target(BACK_CENTER_BIT) -> {
                        gain[BACK_CENTER_BIT][SIDE_LEFT_BIT] += m
                        gain[BACK_CENTER_BIT][SIDE_RIGHT_BIT] += m
                    }
                    target(FRONT_LEFT_BIT) -> pair(FRONT_LEFT_BIT, FRONT_RIGHT_BIT, SIDE_LEFT_BIT, SIDE_RIGHT_BIT, m)
                    target(FRONT_CENTER_BIT) -> {
                        gain[FRONT_CENTER_BIT][SIDE_LEFT_BIT] += m * m
                        gain[FRONT_CENTER_BIT][SIDE_RIGHT_BIT] += m * m
                    }
                    else -> return null
                }
            }
            if (lost(FRONT_LEFT_OF_CENTER_BIT)) {
                when {
                    target(FRONT_LEFT_BIT) -> pair(
                        FRONT_LEFT_BIT, FRONT_RIGHT_BIT, FRONT_LEFT_OF_CENTER_BIT, FRONT_RIGHT_OF_CENTER_BIT, 1f,
                    )
                    target(FRONT_CENTER_BIT) -> {
                        gain[FRONT_CENTER_BIT][FRONT_LEFT_OF_CENTER_BIT] += m
                        gain[FRONT_CENTER_BIT][FRONT_RIGHT_OF_CENTER_BIT] += m
                    }
                    else -> return null
                }
            }
            if (lost(LFE_BIT)) {
                when {
                    target(FRONT_CENTER_BIT) -> gain[FRONT_CENTER_BIT][LFE_BIT] += lfeLevel
                    target(FRONT_LEFT_BIT) -> pair(FRONT_LEFT_BIT, FRONT_RIGHT_BIT, LFE_BIT, LFE_BIT, lfeLevel * m)
                    else -> return null
                }
            }

            val sourceSpeakers = speakersOf(sourceMask)
            val targetSpeakers = speakersOf(targetMask)
            val rows = FloatArray(targetChannels * sourceChannels)
            for (out in targetSpeakers.indices) {
                for (channel in sourceSpeakers.indices) {
                    rows[out * sourceChannels + channel] = gain[targetSpeakers[out]][sourceSpeakers[channel]]
                }
            }
            if (policy.normalize) normalizeAgainstClipping(rows, targetChannels, sourceChannels)
            return rows
        }

        /**
         * The largest row sum is the loudest a full-scale input can drive an output. Divide the
         * WHOLE matrix by it, not each row by its own: scaling rows independently moves the
         * balance between speakers, which is a worse defect than the level.
         */
        private fun normalizeAgainstClipping(rows: FloatArray, outChannels: Int, sourceChannels: Int) {
            var peak = 0f
            for (out in 0 until outChannels) {
                var sum = 0f
                for (channel in 0 until sourceChannels) sum += rows[out * sourceChannels + channel]
                if (sum > peak) peak = sum
            }
            if (peak > 1f) {
                val scale = 1f / peak
                for (i in rows.indices) rows[i] *= scale
            }
        }
    }
}
