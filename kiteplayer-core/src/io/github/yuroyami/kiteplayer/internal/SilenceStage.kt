package io.github.yuroyami.kiteplayer.internal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Runs of output a stage made: each from [start] in its output, reading the source from [source] at
 * [slope] source frames per output frame, and playing [rate] media seconds per output second. The
 * audio clock is dated from these, so a change of rate or a jump in the source is placed exactly
 * where the output reaches it.
 */
internal class OutputRuns {
    private var starts = IntArray(INITIAL)
    private var sources = DoubleArray(INITIAL)
    private var slopes = DoubleArray(INITIAL)
    private var rates = DoubleArray(INITIAL)

    var count: Int = 0
        private set

    fun start(index: Int): Int = starts[index]

    fun source(index: Int): Double = sources[index]

    fun slope(index: Int): Double = slopes[index]

    fun rate(index: Int): Double = rates[index]

    fun clear() {
        count = 0
    }

    /** Adds a run. One that starts where the last one did replaces it, because the last one made nothing. */
    fun add(start: Int, source: Double, slope: Double, rate: Double) {
        if (count > 0 && starts[count - 1] == start) count--
        if (count == starts.size) {
            val size = count * 2
            starts = starts.copyOf(size)
            sources = sources.copyOf(size)
            slopes = slopes.copyOf(size)
            rates = rates.copyOf(size)
        }
        starts[count] = start
        sources[count] = source
        slopes[count] = slope
        rates[count] = rate
        count++
    }

    private companion object {
        const val INITIAL = 16
    }
}

/**
 * Skip silence (#429): every pause longer than [LONGEST_PAUSE_SECONDS] is cut down to that, so a
 * podcast or an audiobook loses its dead air and keeps its voice, as Media3's skip silence and the
 * trim silence of podcast players do. A pause shorter than that, between two words, plays whole.
 *
 * A frame is quiet when every channel is below [THRESHOLD_DB], the level Media3 uses. Quiet frames
 * are held until either the sound comes back, and they all play, or there are as many as the longest
 * pause kept. Then the first half plays, fading to nothing over its last [FADE_SECONDS], and the stage
 * keeps only the newest half from there on, dropping each older frame as a newer one arrives. When
 * the sound comes back the kept half plays, fading in from nothing over its first [FADE_SECONDS], and
 * then the sound. Each side of a cut is therefore at zero where it meets the other, so a cut never
 * clicks, and a pause shortens continuously: just past the longest it loses almost nothing.
 *
 * The measure is the output, after the speed: a pause is as long as it is heard, so at 2x a pause
 * of a second is half a second and becomes a fifth of a second as any other does.
 *
 * Nothing is held at the end of the stream: [process] with `ending` plays what is held, the kept
 * half of a cut included, so the last frame out is the stream's last and a short silence at the end of
 * an item never keeps the next one waiting, which is how Media3's version once stalled a queue.
 *
 * Each run of the output names where it came from in the source, traced through the runs of the
 * input, so the clock dates the cut where it is heard. Off with nothing held, [isIdentity] skips the
 * stage, and an ordinary item plays sample for sample as before.
 *
 * Not thread safe, and it does not need to be: the feeder owns it, as it owns the trim.
 */
internal class SilenceStage(val channels: Int, sampleRate: Int) {

    init {
        require(channels >= 1) { "a silence stage needs at least one channel, was $channels" }
        require(sampleRate > 0) { "a silence stage needs a sample rate, was $sampleRate" }
    }

    /** Frames kept on each side of a cut: half the longest pause. */
    private val keepFrames = max(1, (LONGEST_PAUSE_SECONDS * sampleRate / 2).roundToInt())

    /** The longest pause that plays whole, and the most frames held at once. */
    private val longestFrames = 2 * keepFrames

    private val fadeFrames = min(keepFrames, max(1, (FADE_SECONDS * sampleRate).roundToInt()))

    private val threshold = 10.0.pow(THRESHOLD_DB / 20.0).toFloat()

    private var on = false

    /** True once the held frames are the newest half of a pause already cut. */
    private var cutting = false

    // The held quiet frames, oldest first, in a circle of [longestFrames] frames.
    private val held = FloatArray(longestFrames * channels)
    private var heldHead = 0
    private var heldCount = 0

    /** The input frame the oldest held frame is, counted since the last [reset]. */
    private var heldFirst = 0L

    /** Input frames taken in since the last [reset]. */
    private var received = 0L

    // The input's runs, from input frame [inputStarts], kept while a held frame may still need them.
    private var inputStarts = LongArray(8)
    private var inputSources = DoubleArray(8)
    private var inputSlopes = DoubleArray(8)
    private var inputRates = DoubleArray(8)
    private var inputCount = 0

    /** The runs of the last [process]'s output. */
    val runs: OutputRuns = OutputRuns()

    /** The last [process]'s output, interleaved. Only the frames it returned are the answer. */
    var output: FloatArray = FloatArray(0)
        private set

    private var produced = 0

    /** Quiet frames the stage dropped since the last [reset]. */
    var cutFrames: Long = 0
        private set

    /** True while the stage would hand on every frame as it came, and none is held. */
    val isIdentity: Boolean get() = !on && heldCount == 0

    fun set(next: Boolean) {
        on = next
    }

    /**
     * Takes [frames] sample frames of interleaved [input], whose runs are [inputRuns], and answers
     * how many frames of [output] it made, with their [runs]. Off, it hands on what it holds and
     * then the input. [ending] says no audio follows, and hands on what is held.
     */
    fun process(input: FloatArray, frames: Int, inputRuns: OutputRuns, ending: Boolean): Int {
        runs.clear()
        produced = 0
        recordInput(inputRuns)
        val values = (heldCount + frames) * channels
        if (output.size < values) output = FloatArray(values)
        if (!on) {
            release()
            emitInput(input, 0, frames)
        } else {
            // A run of input that plays as it came, from its first frame, or -1.
            var passing = -1
            for (frame in 0 until frames) {
                if (!isQuiet(input, frame)) {
                    if (heldCount > 0) release()
                    if (passing < 0) passing = frame
                    continue
                }
                if (passing >= 0) {
                    emitInput(input, passing, frame)
                    passing = -1
                }
                hold(input, frame)
            }
            if (passing >= 0) emitInput(input, passing, frames)
        }
        received += frames
        if (ending) release()
        pruneInput()
        return produced
    }

    /** Drops everything held, for a seek. The setting stays. */
    fun reset() {
        heldHead = 0
        heldCount = 0
        cutting = false
        received = 0
        inputCount = 0
        cutFrames = 0
        runs.clear()
    }

    private fun isQuiet(input: FloatArray, frame: Int): Boolean {
        val base = frame * channels
        for (channel in 0 until channels) {
            if (abs(input[base + channel]) >= threshold) return false
        }
        return true
    }

    /** Holds the quiet input frame [frame], and cuts once the pause is longer than the longest kept. */
    private fun hold(input: FloatArray, frame: Int) {
        if (cutting && heldCount == keepFrames) {
            heldHead = (heldHead + 1) % longestFrames
            heldCount--
            heldFirst++
            cutFrames++
        }
        if (heldCount == 0) heldFirst = received + frame
        val slot = (heldHead + heldCount) % longestFrames
        input.copyInto(held, slot * channels, frame * channels, (frame + 1) * channels)
        heldCount++
        if (!cutting && heldCount == longestFrames) {
            emitHeld(keepFrames, fadeOut = true, fadeIn = false)
            cutting = true
        }
    }

    /** Hands on everything held: the newest half of a cut fades in, a pause not cut plays as it was. */
    private fun release() {
        if (heldCount > 0) emitHeld(heldCount, fadeOut = false, fadeIn = cutting)
        cutting = false
    }

    private fun emitHeld(count: Int, fadeOut: Boolean, fadeIn: Boolean) {
        compose(produced, heldFirst, count)
        for (index in 0 until count) {
            val slot = (heldHead + index) % longestFrames
            val gain = when {
                fadeOut && index >= count - fadeFrames -> (count - 1 - index).toFloat() / fadeFrames
                fadeIn && index < fadeFrames -> index.toFloat() / fadeFrames
                else -> 1f
            }
            val from = slot * channels
            val to = (produced + index) * channels
            for (channel in 0 until channels) output[to + channel] = held[from + channel] * gain
        }
        produced += count
        heldHead = (heldHead + count) % longestFrames
        heldCount -= count
        heldFirst += count
    }

    private fun emitInput(input: FloatArray, from: Int, to: Int) {
        if (to <= from) return
        compose(produced, received + from, to - from)
        input.copyInto(output, produced * channels, from * channels, to * channels)
        produced += to - from
    }

    /** Records the runs of the input that starts at input frame [received]. */
    private fun recordInput(inputRuns: OutputRuns) {
        for (index in 0 until inputRuns.count) {
            val start = received + inputRuns.start(index)
            val source = inputRuns.source(index)
            val slope = inputRuns.slope(index)
            val rate = inputRuns.rate(index)
            if (inputCount > 0) {
                val last = inputCount - 1
                val predicted = inputSources[last] + (start - inputStarts[last]) * inputSlopes[last]
                if (inputSlopes[last] == slope && inputRates[last] == rate && abs(predicted - source) < TOLERANCE) continue
                if (inputStarts[last] == start) inputCount--
            }
            if (inputCount == inputStarts.size) {
                val size = inputCount * 2
                inputStarts = inputStarts.copyOf(size)
                inputSources = inputSources.copyOf(size)
                inputSlopes = inputSlopes.copyOf(size)
                inputRates = inputRates.copyOf(size)
            }
            inputStarts[inputCount] = start
            inputSources[inputCount] = source
            inputSlopes[inputCount] = slope
            inputRates[inputCount] = rate
            inputCount++
        }
    }

    /** Drops the input's runs that no held frame and no later input can fall in. */
    private fun pruneInput() {
        val needed = if (heldCount > 0) heldFirst else received
        var drop = 0
        while (drop + 1 < inputCount && inputStarts[drop + 1] <= needed) drop++
        if (drop == 0) return
        inputStarts.copyInto(inputStarts, 0, drop, inputCount)
        inputSources.copyInto(inputSources, 0, drop, inputCount)
        inputSlopes.copyInto(inputSlopes, 0, drop, inputCount)
        inputRates.copyInto(inputRates, 0, drop, inputCount)
        inputCount -= drop
    }

    /**
     * The runs of [count] input frames from input frame [first], placed from output frame [at]:
     * one per run of the input they cross, and none new where they carry on the last run out, so
     * only a cut or a change of the input's own runs starts one.
     */
    private fun compose(at: Int, first: Long, count: Int) {
        if (inputCount == 0 || count <= 0) return
        var line = inputCount - 1
        while (line > 0 && inputStarts[line] > first) line--
        var position = first
        var out = at
        val end = first + count
        while (true) {
            val source = inputSources[line] + (position - inputStarts[line]) * inputSlopes[line]
            addRun(out, source, inputSlopes[line], inputRates[line])
            if (line + 1 >= inputCount || inputStarts[line + 1] >= end) break
            out += (inputStarts[line + 1] - position).toInt()
            position = inputStarts[line + 1]
            line++
        }
    }

    private fun addRun(at: Int, source: Double, slope: Double, rate: Double) {
        val last = runs.count - 1
        if (last >= 0 && runs.slope(last) == slope && runs.rate(last) == rate &&
            abs(runs.source(last) + (at - runs.start(last)) * slope - source) < TOLERANCE
        ) {
            return
        }
        runs.add(at, source, slope, rate)
    }

    internal companion object {
        /** Below this, on every channel, a frame is quiet: Media3's level, 1024 of 32768. */
        const val THRESHOLD_DB: Double = -30.1

        /** The longest pause that plays whole, and what every longer one is cut down to. */
        const val LONGEST_PAUSE_SECONDS: Double = 0.2

        /** How long each side of a cut takes to fade to nothing, well inside half the pause. */
        const val FADE_SECONDS: Double = 0.01

        /** How far apart, in source frames, two runs may predict a frame and still be one. */
        private const val TOLERANCE = 1e-6
    }
}
