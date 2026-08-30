package io.github.yuroyami.kiteplayer.internal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Changes how long audio takes to play without changing what it sounds like.
 *
 * This is the tempo stage the speed control was waiting for: time-domain, pitch-synchronous
 * overlap-add, the same family of algorithm mpv's scaletempo and ExoPlayer's Sonic sit in. The
 * idea is old and sound: audio is locally periodic, so removing or repeating whole pitch periods
 * shortens or lengthens it without shifting its pitch, and a linear crossfade at each splice
 * hides the seam. Nothing here is a Fourier transform; the cost is a few comparisons per pitch
 * period, which is why every real-time player uses this family.
 *
 * ### How the ratio is kept honest
 *
 * The stage never trusts an operation mix to average out. It counts every frame consumed and
 * every frame emitted, and before each operation asks one question: given what has been emitted,
 * how much SHOULD have been consumed at the wanted speed? The answer picks the operation that
 * moves the real figure toward the ideal one:
 *
 * - within half a period of ideal: copy a period straight through,
 * - consumed too little: overlap-add two periods into one (consumes 2P, emits P),
 * - consumed far too little: drop a whole period outright (the splice is healed by the next
 *   overlap-add),
 * - consumed too much: emit the head period twice with a crossfaded join (consumes P, emits 2P),
 * - consumed far too much: re-emit the head period without consuming at all.
 *
 * The error therefore stays bounded by about two periods at any speed in [MIN_SPEED, MAX_SPEED],
 * which is what makes the pts arithmetic in `AudioPlayback` exact rather than approximate: the
 * caller may date output frames as `emitted / speed` and the real position never drifts away
 * from that line.
 *
 * ### Pitch detection
 *
 * Average magnitude difference over one period window, coarse pass on a decimated signal and a
 * refining pass at full rate, searched between 65 Hz and 400 Hz where speech and song
 * fundamentals live. On unpitched audio the minimum is arbitrary, and that is fine: splicing
 * noise at any period sounds like the same noise.
 *
 * ### Ownership and threading
 *
 * One instance per audio pipeline, owned by the feeder like every other stage. Not thread safe,
 * same rule as [ChannelMixer] and [GainStage]. [output] is this stage's own buffer, reused by
 * the next [process], never handed anywhere.
 */
internal class TempoStage(
    private val channels: Int,
    sampleRate: Int,
) {
    init {
        require(channels > 0) { "channels must be positive, was $channels" }
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    }

    /**
     * The rate to play at, as a multiplier of real time. 1.0 bypasses the stage entirely: input
     * is copied to [output] untouched, so a player at normal speed pays nothing for this stage
     * existing.
     *
     * Owned by the feeder like the stage itself. The engine changes speed through a flush, so a
     * value set here never has to splice two speeds inside one buffer.
     */
    var speed: Double = 1.0
        set(value) {
            require(value.isFinite() && value >= MIN_SPEED && value <= MAX_SPEED) {
                "speed must be within $MIN_SPEED..$MAX_SPEED, was $value"
            }
            field = value
        }

    /**
     * True while splice lookahead sits in the stage. The pipeline routes around the stage
     * entirely at 1.0 speed with nothing queued, so normal playback pays no copy for the stage
     * existing; this is the half of that condition only the stage can answer.
     */
    val hasQueuedInput: Boolean get() = queuedFrames > 0

    /** Frames consumed from callers across the stage's lifetime since the last [reset]. */
    var consumedFrames: Long = 0L
        private set

    /** Frames emitted into [output] across the stage's lifetime since the last [reset]. */
    var emittedFrames: Long = 0L
        private set

    private val minPeriod = max(1, sampleRate / MAX_PITCH_HZ)
    private val maxPeriod = max(minPeriod + 1, sampleRate / MIN_PITCH_HZ)
    private val coarseStep = max(1, sampleRate / COARSE_RATE_HZ)

    /** Queued input, interleaved. The stage needs two periods of lookahead to splice. */
    private var queue = FloatArray(0)
    private var queuedFrames = 0

    /** Downmixed scratch for pitch detection, sized for two periods. */
    private var mono = FloatArray(0)

    /**
     * The samples the last [process] produced, interleaved. Only the first
     * `frames * channels` values are the answer, where `frames` is what [process] returned.
     */
    var output: FloatArray = FloatArray(0)
        private set
    private var outputFrames = 0

    /**
     * Runs [frames] interleaved frames of [input] through the stage.
     *
     * @return frames written to [output]. Zero is normal while the stage accumulates the two
     *         periods of lookahead an operation needs; nothing is lost, the queue carries it.
     */
    fun process(input: FloatArray, frames: Int): Int {
        if (frames <= 0) return 0
        if (speed == 1.0 && queuedFrames == 0) {
            // The bypass. No queue, no detection, one copy.
            output = grown(output, frames * channels)
            input.copyInto(output, 0, 0, frames * channels)
            consumedFrames += frames
            emittedFrames += frames
            return frames
        }

        enqueue(input, frames)
        outputFrames = 0

        // Two periods and the crossfade's reach: below this an operation would read past the queue.
        val needed = 2 * maxPeriod
        while (queuedFrames >= needed) {
            if (speed == 1.0) {
                // A speed set back to 1.0 with a queue still standing: drain it straight through.
                emitCopy(queuedFrames)
                consume(queuedFrames)
                continue
            }
            val period = detectPeriod()
            // How much input SHOULD have been consumed for what has been emitted, at this speed.
            // Every branch strictly shrinks the error, so the loop can never spin in place: a
            // repeat emits without consuming (error falls by P times speed), a drop consumes
            // without emitting (error rises by P), and the rest sit between the two.
            val ideal = emittedFrames * speed
            val error = consumedFrames - ideal
            when {
                // Far too much consumed: repeat the head period without consuming. The floor of
                // the reachable ratio, for the deepest slowdowns.
                error > 2.0 * period -> emitRepeat(period)
                // Too much consumed: play the head period twice with a crossfaded join.
                error > period / 2.0 -> emitInsert(period)
                // Far too little: drop a period outright. The next overlap-add heals the splice.
                error < -2.0 * period -> consume(period)
                // Too little: two periods become one.
                error < -period / 2.0 -> emitSkip(period)
                // On the line: pass a period through untouched.
                else -> {
                    emitCopy(period)
                    consume(period)
                }
            }
        }
        return outputFrames
    }

    /**
     * Advances both counters by [frames] without touching any buffer.
     *
     * For the pipeline's fast path: at 1.0 speed with nothing queued the samples never enter
     * this stage, but the emitted counter still dates the ring's pts, so the frames must count
     * as if they had passed through.
     */
    fun countBypassed(frames: Int) {
        consumedFrames += frames
        emittedFrames += frames
    }

    /**
     * Emits everything still queued, for the end of the stream.
     *
     * [process] only acts once two periods are queued, because every operation reads a period ahead
     * of the one it writes. At the end of a stream that lookahead never arrives, so up to two
     * periods of real audio sit here with nothing to trigger them, and [reset] used to be the only
     * way out: it dropped them. On a short clip that is the last audible fraction of a second going
     * missing.
     *
     * The remainder is emitted straight through rather than spliced. A splice needs the period
     * after the one it cuts, and at the end of a stream there is no such period; stretching the
     * final fragment would mean inventing it. What this costs is that the last few tens of
     * milliseconds play at their recorded length instead of the requested speed, which is below
     * what anyone can hear and is what every player in this family does at flush.
     *
     * @return frames written to [output], zero when nothing was queued.
     */
    fun finish(): Int {
        outputFrames = 0
        if (queuedFrames <= 0) return 0
        // Both counters move exactly as an ordinary copy moves them, so the pts law upstairs dates
        // this fragment on the same axis as every buffer before it.
        emitCopy(queuedFrames)
        consume(queuedFrames)
        return outputFrames
    }

    /** Drops everything queued and the ratio counters. The seek path, like every stage's reset. */
    fun reset() {
        queuedFrames = 0
        consumedFrames = 0
        emittedFrames = 0
    }

    private fun enqueue(input: FloatArray, frames: Int) {
        val neededValues = (queuedFrames + frames) * channels
        if (queue.size < neededValues) queue = queue.copyOf(max(neededValues, queue.size * 2))
        input.copyInto(queue, queuedFrames * channels, 0, frames * channels)
        queuedFrames += frames
    }

    /**
     * Retires [frames] from the queue head, sliding the remainder down.
     *
     * The ONLY place the consumed counter moves. Emission counts at each emit operation, and
     * retirement counts here, so no operation can count its half twice or forget it.
     */
    private fun consume(frames: Int) {
        val remaining = (queuedFrames - frames) * channels
        if (remaining > 0) queue.copyInto(queue, 0, frames * channels, frames * channels + remaining)
        queuedFrames -= frames
        consumedFrames += frames
    }

    /** Copies [frames] from the queue head to the output. The caller retires them separately. */
    private fun emitCopy(frames: Int) {
        appendOutput(queue, 0, frames)
        emittedFrames += frames
    }

    /** Overlap-adds the head's two periods into one: consumes 2P, emits P. */
    private fun emitSkip(period: Int) {
        val start = ensureOutput(period)
        for (frame in 0 until period) {
            val fadeOut = (period - frame).toFloat() / period
            val fadeIn = frame.toFloat() / period
            for (channel in 0 until channels) {
                val a = queue[frame * channels + channel]
                val b = queue[(frame + period) * channels + channel]
                output[start + frame * channels + channel] = a * fadeOut + b * fadeIn
            }
        }
        outputFrames += period
        emittedFrames += period
        consume(2 * period)
    }

    /** Plays the head period, then a crossfade from it into the next: consumes P, emits 2P. */
    private fun emitInsert(period: Int) {
        appendOutput(queue, 0, period)
        emittedFrames += period
        val start = ensureOutput(period)
        for (frame in 0 until period) {
            val fadeOut = (period - frame).toFloat() / period
            val fadeIn = frame.toFloat() / period
            for (channel in 0 until channels) {
                val a = queue[frame * channels + channel]
                val b = queue[(frame + period) * channels + channel]
                output[start + frame * channels + channel] = a * fadeOut + b * fadeIn
            }
        }
        outputFrames += period
        emittedFrames += period
        consume(period)
    }

    /** Re-emits the head period consuming nothing. Period-aligned, so the loop point is quiet. */
    private fun emitRepeat(period: Int) {
        appendOutput(queue, 0, period)
        emittedFrames += period
    }

    private fun appendOutput(source: FloatArray, sourceFrame: Int, frames: Int) {
        val start = ensureOutput(frames)
        source.copyInto(output, start, sourceFrame * channels, (sourceFrame + frames) * channels)
        outputFrames += frames
    }

    /** Grows [output] for [frames] more and returns the write position in values. */
    private fun ensureOutput(frames: Int): Int {
        val start = outputFrames * channels
        val needed = start + frames * channels
        if (output.size < needed) output = output.copyOf(max(needed, output.size * 2))
        return start
    }

    /**
     * The pitch period at the queue head, in frames.
     *
     * Average magnitude difference over one candidate period, on a mono downmix: coarse pass on a
     * decimated signal to bound the cost, then a refinement at full resolution around the coarse
     * winner. The window compares `[0, p)` against `[p, 2p)`, which is exactly the pair of
     * periods the splice operations will blend, so the detector optimises the seam it is about
     * to cut.
     */
    private fun detectPeriod(): Int {
        val window = 2 * maxPeriod
        if (mono.size < window) mono = FloatArray(window)
        for (frame in 0 until window) {
            var sum = 0f
            val base = frame * channels
            for (channel in 0 until channels) sum += queue[base + channel]
            mono[frame] = sum / channels
        }

        var bestPeriod = minPeriod
        var bestDiff = Float.MAX_VALUE
        var candidate = minPeriod
        while (candidate <= maxPeriod) {
            var diff = 0f
            var count = 0
            var frame = 0
            while (frame < candidate) {
                diff += abs(mono[frame] - mono[frame + candidate])
                count++
                frame += coarseStep
            }
            val normalized = diff / count
            if (normalized < bestDiff) {
                bestDiff = normalized
                bestPeriod = candidate
            }
            candidate += coarseStep
        }

        // Refine at full resolution around the coarse winner.
        val low = max(minPeriod, bestPeriod - coarseStep)
        val high = min(maxPeriod, bestPeriod + coarseStep)
        var refined = bestPeriod
        var refinedDiff = Float.MAX_VALUE
        for (exact in low..high) {
            var diff = 0f
            for (frame in 0 until exact) {
                diff += abs(mono[frame] - mono[frame + exact])
            }
            val normalized = diff / exact
            if (normalized < refinedDiff) {
                refinedDiff = normalized
                refined = exact
            }
        }
        return refined
    }

    private fun grown(buffer: FloatArray, values: Int): FloatArray =
        if (buffer.size >= values) buffer else FloatArray(values)

    companion object {
        /** The supported range. Below and above, splices dominate the signal and honesty says no. */
        const val MIN_SPEED: Double = 0.25
        const val MAX_SPEED: Double = 4.0

        /** Fundamentals below speech: the longest period the detector will pick. */
        private const val MIN_PITCH_HZ = 65

        /** Above this a "period" is shorter than the crossfade can hide. */
        private const val MAX_PITCH_HZ = 400

        /** The decimated rate of the coarse detection pass. */
        private const val COARSE_RATE_HZ = 4_000
    }
}
