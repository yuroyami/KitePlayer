package io.github.yuroyami.kiteplayer.internal

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * The night mode (#442): a compressor that narrows the distance between the quiet and the loud parts
 * of the sound, so speech rises and explosions fall, as a receiver's night mode does.
 *
 * One level for every channel, the loudest of them, so the picture of the mix never shifts. Above
 * [THRESHOLD_DB] the level grows a third as fast, with a soft knee [KNEE_DB] wide, and [MAKEUP_DB]
 * brings the whole back up, which is what lifts the speech. The make-up fades out below
 * [FLOOR_DB], so the hiss of a quiet passage is not raised with it. The gain falls in [ATTACK_SECONDS]
 * and rises again in [RELEASE_SECONDS], gently enough that it is not heard pumping, and a sample the
 * gain would push past [CEILING] in the moment before it falls is held at it, so the stage never
 * makes the output clip.
 *
 * The gain is worked out every [BLOCK] frames and ramped between, so the stage spends two powers and
 * a logarithm per 16 frames. Off, the gain glides back to unity at the release rate, so turning the
 * mode on or off never clicks, and from there [isIdentity] skips the stage: an ordinary file pays
 * nothing for it and plays sample for sample as before.
 *
 * Not thread safe, and it does not need to be: the feeder owns it, as it owns the trim.
 */
internal class NightStage(val channels: Int, sampleRate: Int) {

    init {
        require(channels >= 1) { "a night stage needs at least one channel, was $channels" }
        require(sampleRate > 0) { "a night stage needs a sample rate, was $sampleRate" }
    }

    private var on = false

    /** The loudest sample lately, rising at once and falling at the release rate. */
    private var peak = 0f

    /** The gain the stage is at, in decibels, and the same as a factor. */
    private var gainDb = 0.0
    private var gain = 1f

    /** Frames into the current block. */
    private var inBlock = 0

    /** How far the gain moves each frame of the current block. */
    private var step = 0f

    private val peakFall = exp(-1.0 / (RELEASE_SECONDS * sampleRate)).toFloat()
    private val attack = 1.0 - exp(-BLOCK / (ATTACK_SECONDS * sampleRate))
    private val release = 1.0 - exp(-BLOCK / (RELEASE_SECONDS * sampleRate))

    /** True while the stage would leave every sample as it is. */
    val isIdentity: Boolean get() = !on && gainDb == 0.0 && gain == 1f

    fun set(next: Boolean) {
        on = next
    }

    /**
     * Compresses the first [frames] sample frames of interleaved [samples] in place. Only those
     * frames, because the pipeline reuses one buffer across calls.
     */
    fun apply(samples: FloatArray, frames: Int) {
        if (frames <= 0 || isIdentity) return
        var base = 0
        for (frame in 0 until frames) {
            var loudest = 0f
            for (channel in 0 until channels) {
                val level = abs(samples[base + channel])
                if (level > loudest) loudest = level
            }
            peak = if (loudest > peak) loudest else peak * peakFall
            if (inBlock == 0) nextBlock()
            inBlock = (inBlock + 1) % BLOCK
            gain += step
            for (channel in 0 until channels) {
                val sample = samples[base + channel]
                val out = sample * gain
                // Only what the gain itself raised past the ceiling is held; a louder input stays.
                samples[base + channel] = if (abs(out) > CEILING && abs(out) > abs(sample)) {
                    if (out > 0f) maxOf(CEILING, sample) else minOf(-CEILING, sample)
                } else {
                    out
                }
            }
            base += channels
        }
    }

    /** Moves the gain toward what the level asks for, and sets the ramp to it over the next block. */
    private fun nextBlock() {
        val target = if (on) targetDb(peak) else 0.0
        gainDb += (target - gainDb) * (if (target < gainDb) attack else release)
        if (!on && abs(gainDb) < SETTLED_DB) gainDb = 0.0
        val next = if (gainDb == 0.0) 1f else 10.0.pow(gainDb / 20.0).toFloat()
        step = (next - gain) / BLOCK
        if (gainDb == 0.0 && abs(next - gain) < 1e-6f) {
            gain = 1f
            step = 0f
        }
    }

    /** The gain a sound whose peak is [level] gets, in decibels. */
    private fun targetDb(level: Float): Double {
        val db = if (level > 1e-9f) 20.0 * log10(level.toDouble()) else -180.0
        val over = db - THRESHOLD_DB
        val compressed = when {
            2 * over < -KNEE_DB -> db
            2 * over > KNEE_DB -> THRESHOLD_DB + over / RATIO
            else -> db + (1 / RATIO - 1) * (over + KNEE_DB / 2).pow(2) / (2 * KNEE_DB)
        }
        val makeup = MAKEUP_DB * ((db - FLOOR_DB) / FLOOR_FADE_DB).coerceIn(0.0, 1.0)
        return compressed - db + makeup
    }

    internal companion object {
        /** Where the compression starts, in decibels below full scale. */
        const val THRESHOLD_DB: Double = -30.0

        /** How much more slowly the level grows above the threshold. */
        const val RATIO: Double = 3.0

        /** How wide the bend into the compression is, in decibels. */
        const val KNEE_DB: Double = 10.0

        /** What brings the compressed whole back up, which lifts the quiet parts. */
        const val MAKEUP_DB: Double = 9.0

        /** Below this level, in decibels, the make-up fades out over [FLOOR_FADE_DB]. */
        const val FLOOR_DB: Double = -70.0
        const val FLOOR_FADE_DB: Double = 10.0

        const val ATTACK_SECONDS: Double = 0.010
        const val RELEASE_SECONDS: Double = 0.300

        /** The largest sample the stage hands on, a little under full scale. */
        const val CEILING: Float = 0.98f

        /** How close to unity an off gain counts as there. */
        const val SETTLED_DB: Double = 0.01

        const val BLOCK: Int = 16
    }
}
