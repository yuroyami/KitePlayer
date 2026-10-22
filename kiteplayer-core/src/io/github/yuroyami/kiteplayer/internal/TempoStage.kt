package io.github.yuroyami.kiteplayer.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plays audio faster or slower, with or without its pitch, and takes a new rate at any buffer
 * without a seam.
 *
 * Three ways of moving through the input, chosen per buffer from [speed] and [preservePitch]:
 *
 * - **Bypass**, at 1.0: the input is the output, sample for sample, with no latency.
 * - **Stretch**, keeping pitch: waveform-similarity overlap-add (WSOLA), the method Chromium's
 *   media renderer and mpv's `scaletempo2` use. The output is built from 20 ms blocks of input that
 *   overlap by half, under a Hann window. Each block is placed where the rate says the input
 *   should be, and is moved by up to 20 ms to the position whose waveform best continues the
 *   output. A block is only moved when the natural continuation has drifted out of that range, so
 *   near 1.0 the stage plays its input straight for seconds at a time and splices rarely. A
 *   splice never repeats or skips an attack: see [spliceTo].
 * - **Fold**, without pitch correction: the input is read at the rate through a windowed-sinc
 *   interpolator, so pitch moves with the rate as on a turntable.
 *
 * ### Changing rate and mode
 *
 * A new [speed] or [preservePitch] applies to the next output this stage makes. Nothing is reset:
 * the stretch keeps its overlap and its search history, and a change of mode first settles on an
 * exact input frame and continues from there. Leaving the stretch plays one last block that is the
 * natural continuation of the one before it, so the waveform never jumps. Leaving the fold walks the
 * read position onto a whole frame over a few milliseconds, at a rate within 0.2 percent of 1.0.
 *
 * ### Where each output frame comes from
 *
 * After every call the stage describes its output as pieces: [pieceCount] runs of frames, each
 * starting at [pieceStart], reading the input from [pieceSource] onward at [pieceSlope] input frames
 * per output frame. The audio path dates the ring from these. The stretch reports the ideal line the
 * rate asks for, not the position of each block, so a rate change is a change of slope and nothing
 * else. The actual blocks stay within 20 ms of that line, and within about 60 ms while a splice
 * waits for an attack to pass. Settling back to 1.0 reports the real position again, which can move
 * the line by that amount once.
 *
 * ### Multichannel
 *
 * One block position serves every channel, so the channels never move against each other. The
 * similarity is the normalised cross-correlation of all channels taken as one signal, which weights
 * each channel by its energy: a quiet surround channel cannot outvote the front, and a channel in
 * opposite polarity to another cannot cancel it.
 *
 * ### Ownership and threading
 *
 * One instance per audio pipeline, owned by the feeder like every other stage. Not thread safe, same
 * rule as [ChannelMixer]. [output] is this stage's own buffer, reused by the next call.
 */
internal class TempoStage(
    private val channels: Int,
    private val sampleRate: Int,
) {
    init {
        require(channels > 0) { "channels must be positive, was $channels" }
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    }

    /**
     * The rate to play at, as a multiplier of real time. 1.0 is the bypass. A new value applies to
     * the next output frame the stage makes, so a caller may change it between any two buffers.
     */
    var speed: Double = 1.0
        set(value) {
            require(value.isFinite() && value >= STAGE_MIN_SPEED && value <= STAGE_MAX_SPEED) {
                "speed must be within $STAGE_MIN_SPEED..$STAGE_MAX_SPEED, was $value"
            }
            field = value
        }

    /** True keeps pitch through the stretch; false reads the input at the rate, so pitch follows it. */
    var preservePitch: Boolean = true

    /** Input frames handed to [process] or [passThrough] since the last [reset]. */
    var receivedFrames: Long = 0L
        private set

    /** Output frames made since the last [reset]. */
    var emittedFrames: Long = 0L
        private set

    /**
     * The samples the last [process] or [finish] produced, interleaved. Only the first
     * `frames * channels` values are the answer, where `frames` is what the call returned.
     */
    var output: FloatArray = FloatArray(0)
        private set
    private var outputFrames = 0

    /** Runs of output, see "Where each output frame comes from". Valid after every call. */
    var pieceCount: Int = 0
        private set
    private val pieceStarts = IntArray(MAX_PIECES)
    private val pieceSources = DoubleArray(MAX_PIECES)
    private val pieceSlopes = DoubleArray(MAX_PIECES)

    /** The output frame of the last call where piece [index] begins. */
    fun pieceStart(index: Int): Int = pieceStarts[index]

    /** The input position, in frames since the last [reset], of piece [index]'s first output frame. */
    fun pieceSource(index: Int): Double = pieceSources[index]

    /** Input frames per output frame across piece [index]. */
    fun pieceSlope(index: Int): Double = pieceSlopes[index]

    /**
     * True when the next buffer would pass through untouched: 1.0, nothing held. The pipeline then
     * calls [passThrough] and spends no copy on this stage.
     */
    val isBypassing: Boolean
        get() = mode == Mode.Bypass && wantedMode() == Mode.Bypass && position == queueEnd

    // Geometry, fixed per rate. The window is even so that it splits into two equal hops.
    private val window = 2 * max(MIN_HOP, (sampleRate * WINDOW_SECONDS / 2).roundToInt())
    private val hop = window / 2
    private val searchHalf = max(1, (sampleRate * SEARCH_HALF_SECONDS).roundToInt())
    private val excludeHalf = max(1, (sampleRate * EXCLUDE_HALF_SECONDS).roundToInt())
    private val coarseStep = max(1, (sampleRate / COARSE_RATE).roundToInt())
    private val foldReachMax = ceil(FOLD_ZERO_CROSSINGS * STAGE_MAX_SPEED).toInt() + 1

    /** Frames kept behind the read position while bypassing, so a later stretch or fold has history. */
    private val history = max(searchHalf, foldReachMax) + 1

    /** Periodic Hann, so the two halves of overlapping windows sum to exactly one. */
    private val olaWindow = FloatArray(window) { n -> (0.5 - 0.5 * cos(2.0 * PI * n / window)).toFloat() }

    /** The rising half of a Hann twice as long: the move from the natural block to the chosen one. */
    private val transition = FloatArray(window) { n -> (0.5 - 0.5 * cos(PI * n / window)).toFloat() }

    // The input queue, addressed in absolute input frames: queue[0] is frame queueStart.
    private var queue = FloatArray(0)
    private var queueStart = 0L
    private var queueFrames = 0
    private val queueEnd: Long get() = queueStart + queueFrames

    private var mode = Mode.Bypass

    /** Bypass: the next input frame to play. */
    private var position = 0L

    // Stretch state.
    /** The input position the rate says the next output hop starts at. */
    private var ideal = 0.0

    /** The natural continuation of the last block: where the next block would start with no splice. */
    private var target = 0L

    /** The second half of the last block, which the next hop fades out. */
    private val tail = FloatArray(hop * channels)

    /** Right after the stretch begins, the tail is the input itself, still in the queue. */
    private var tailFromQueue = false
    private var tailStart = 0L
    private val block = FloatArray(window * channels)

    /** How far ahead of the ideal line recent attacks came out, in input frames; see [trackOnsetLead]. */
    private var onsetLead = 0.0

    /** The speed [onsetLead] was measured at. */
    private var leadSpeed = 1.0

    /** How far the output may drift from the ideal line while a splice waits for an onset to pass. */
    private val relaxDrift = searchHalf + 2 * window

    // Onset detector state, see [scanOnsets].
    private val onsetBlock = max(MIN_HOP, (sampleRate * ONSET_BLOCK_SECONDS).roundToInt())
    private val onsetGap = (sampleRate * ONSET_GAP_SECONDS).roundToLong()
    private val onsets = LongArray(MAX_ONSETS)
    private var onsetCount = 0
    private var lastOnset = Long.MIN_VALUE / 2
    private var scannedTo = 0L
    private val previousFrame = FloatArray(channels)
    private var blockEnergy = 0.0
    private var blockFill = 0
    private var blockLevel = 0.0
    private var levelPrimed = false

    // Search scratch, grown on first use.
    private var frameEnergy = DoubleArray(0)
    private var coarseTarget = FloatArray(0)
    private var coarseRegion = FloatArray(0)

    // Fold state: the fractional input position of the next output frame.
    private var foldPosition = 0.0
    private val foldSum = FloatArray(channels)

    /**
     * Runs [frames] interleaved frames of [input] through the stage.
     *
     * @return frames written to [output]. Zero is normal right after the stretch begins, while it
     *         gathers the 50 ms of lookahead one hop needs; nothing is lost, the queue carries it.
     */
    fun process(input: FloatArray, frames: Int): Int {
        beginCall()
        if (frames <= 0) return 0
        append(input, frames)
        receivedFrames += frames
        run(finishing = false)
        trimQueue()
        return outputFrames
    }

    /**
     * Counts [frames] of [input] as played straight through, without copying them to [output].
     *
     * For the pipeline's fast path while [isBypassing]: the caller keeps its own buffer as the
     * output. The stage still keeps the last few milliseconds as history, so a later change of
     * rate can start its first block from real audio and not from silence.
     */
    fun passThrough(input: FloatArray, frames: Int) {
        check(isBypassing) { "passThrough is only legal while the stage is bypassing" }
        beginCall()
        if (frames <= 0) return
        addPiece(position.toDouble(), 1.0)
        if (frames >= history) {
            ensureQueue(history)
            input.copyInto(queue, 0, (frames - history) * channels, frames * channels)
            queueFrames = history
            queueStart = position + frames - history
        } else {
            append(input, frames)
            val excess = queueFrames - history
            if (excess > 0) discard(excess)
        }
        position += frames
        receivedFrames += frames
        emittedFrames += frames
        outputFrames = frames
    }

    /**
     * Emits everything still held, for the end of the stream.
     *
     * Both the stretch and the fold carry on at the speed past the last input frame, reading
     * silence after it, until the output has passed that frame. So the end of a stream plays at
     * the speed like the rest of it, and the stage is left bypassing, ready for whatever follows,
     * such as the next queue item.
     *
     * @return frames written to [output], zero when nothing was held.
     */
    fun finish(): Int {
        beginCall()
        when (mode) {
            Mode.Bypass -> Unit
            Mode.Stretch -> stretchToEnd()
            Mode.Fold -> foldToEnd()
        }
        val pending = (queueEnd - position).toInt()
        if (pending > 0) emitCopy(pending)
        trimQueue()
        return outputFrames
    }

    /** Drops everything held and the counters. The seek path, like every stage's reset. */
    fun reset() {
        mode = Mode.Bypass
        queueStart = 0L
        queueFrames = 0
        position = 0L
        receivedFrames = 0L
        emittedFrames = 0L
        tailFromQueue = false
        outputFrames = 0
        pieceCount = 0
        scannedTo = 0L
        onsetCount = 0
        lastOnset = Long.MIN_VALUE / 2
        levelPrimed = false
        blockEnergy = 0.0
        blockFill = 0
    }

    private enum class Mode { Bypass, Stretch, Fold }

    private fun wantedMode(): Mode = when {
        speed == 1.0 -> Mode.Bypass
        preservePitch -> Mode.Stretch
        else -> Mode.Fold
    }

    private fun beginCall() {
        outputFrames = 0
        pieceCount = 0
    }

    /** Makes output until the input runs out or a step cannot proceed. */
    private fun run(finishing: Boolean) {
        while (true) {
            val want = wantedMode()
            when (mode) {
                Mode.Bypass -> when (want) {
                    Mode.Bypass -> {
                        val pending = (queueEnd - position).toInt()
                        if (pending > 0) emitCopy(pending)
                        return
                    }
                    Mode.Stretch -> beginStretch()
                    Mode.Fold -> beginFold()
                }
                Mode.Stretch ->
                    if (want == Mode.Stretch) {
                        if (!stretchHop()) return
                    } else if (!settleStretch(finishing)) {
                        return
                    }
                Mode.Fold ->
                    if (want == Mode.Fold) {
                        if (!foldRun()) return
                    } else if (!settleFold()) {
                        return
                    }
            }
            if (pieceCount == MAX_PIECES) return
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stretch
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts the stretch at the read position. The previous block is taken to be the input itself,
     * so the first hop reproduces the input exactly and the change starts without a seam.
     */
    private fun beginStretch() {
        onsetLead = 0.0
        ideal = position.toDouble()
        target = position
        tailFromQueue = true
        tailStart = position
        mode = Mode.Stretch
    }

    /**
     * Where the search range is centred: half a range against the drift. The natural continuation
     * drifts one way until it leaves the range, and a splice brings it back near the centre, so
     * centring on the ideal line itself would keep the output half a range to one side of it on
     * average. Centred half a range back, the output swings evenly around the line the clock reads.
     */
    private fun searchCenter(): Long {
        val shift = when {
            speed < 1.0 -> -searchHalf / 2.0
            speed > 1.0 -> searchHalf / 2.0
            else -> 0.0
        }
        val lead = if (leadSpeed == speed) onsetLead else 0.0
        return floor(ideal + shift - lead + 0.5).toLong()
    }

    /**
     * Measures how far ahead of the ideal line the attacks the last hop played came out, and
     * moves [onsetLead] a fifth of the way there. Waiting for an attack to pass before a splice
     * lets the output fall behind at a speedup and run ahead at a slowdown, so without this the
     * attacks, which are what a viewer matches to the picture, would land late or early by
     * tens of milliseconds. The search centre moves against [onsetLead], so the next attacks land
     * on the line.
     */
    private fun trackOnsetLead(hopStartTarget: Long, hopStartIdeal: Double) {
        // The lead belongs to one speed: waiting for attacks pulls a speedup and a slowdown
        // opposite ways.
        if (speed != leadSpeed) {
            leadSpeed = speed
            onsetLead = 0.0
        }
        onsetLead *= ONSET_LEAD_DECAY
        for (index in 0 until onsetCount) {
            val onset = onsets[index]
            if (onset < hopStartTarget || onset >= hopStartTarget + hop) continue
            // The hop played the input from its natural continuation, so the attack came out
            // (onset - target) frames into the hop, where the ideal line stood at this position.
            val idealThere = hopStartIdeal + (onset - hopStartTarget) * speed
            val lead = onset - idealThere
            onsetLead += ONSET_LEAD_GAIN * (lead - onsetLead)
            onsetLead = onsetLead.coerceIn(-searchHalf.toDouble(), searchHalf.toDouble())
        }
    }

    /** One output hop. False when the queue does not yet reach far enough ahead. */
    private fun stretchHop(): Boolean {
        val center = searchCenter()
        val high = center + searchHalf
        if (max(target, high) + window > queueEnd) return false
        scanOnsets()
        // Right after the stretch begins the search may reach before the oldest frame kept.
        val low = max(center - searchHalf, queueStart)
        val chosen = if (target in low..high) target else spliceTo(low, high, center)
        // An unspliced hop plays the natural continuation, so its attacks show where the output
        // stands against the ideal line.
        if (chosen == target) trackOnsetLead(target, ideal)
        // The move from the natural block to the chosen one would play an attack inside the chosen
        // block quieter, so such a block overlaps in plainly, like every other hop.
        buildBlock(chosen, blend = chosen != target && !onsetBetween(chosen, chosen + window))
        addPiece(ideal, speed)
        overlapAdd()
        target = chosen + hop
        ideal += hop * speed
        return true
    }

    /**
     * Where the next block starts once the natural continuation has left the search range.
     *
     * An attack must play exactly once and at full strength. So no splice happens while an onset
     * is in the natural block; a slowdown only jumps back to after the last onset already played;
     * a speedup only jumps forward to where the next onset falls in the second half of the new
     * block, which plays without the 20 ms move from the natural block. When that leaves no position in
     * range, the block continues naturally and the splice waits, unless the output has drifted
     * more than [relaxDrift] from the ideal line, where keeping time wins.
     */
    private fun spliceTo(low: Long, high: Long, center: Long): Long {
        if (abs(target - ideal) > relaxDrift) return search(low, high, center)
        if (onsetBetween(target, target + window)) return target
        var from = low
        var to = high
        if (target > high) {
            lastOnsetBefore(target)?.let { from = max(from, it + 1) }
        } else {
            // An attack in the first half of the new block would fade in with it; from the second
            // half on, the next block continues it naturally and it plays at full strength.
            firstOnsetFrom(target)?.let { to = min(to, it - hop) }
        }
        return if (from > to) target else search(from, to, center)
    }

    /**
     * Ends the stretch on the natural continuation of the last block, then reads straight on. At
     * the end of a stream the missing lookahead is silence.
     */
    private fun settleStretch(finishing: Boolean): Boolean {
        if (!finishing && target + window > queueEnd) return false
        val offset = ((target - queueStart) * channels).toInt()
        val available = ((queueEnd - target).coerceIn(0L, window.toLong()) * channels).toInt()
        queue.copyInto(block, 0, offset, offset + available)
        block.fill(0f, available, window * channels)
        addPiece(target.toDouble(), 1.0)
        overlapAdd()
        mode = Mode.Bypass
        position = min(target + hop, queueEnd)
        return true
    }

    /**
     * At the end of a stream: hops on over silence until the output has passed the last input
     * frame, then drops the silence. The last block fades out over its own hop.
     */
    private fun stretchToEnd() {
        val end = queueEnd
        // Silence for every hop's lookahead: the search range, its shift and a whole block.
        val padding = 3 * searchHalf + 2 * window + relaxDrift
        ensureQueue(queueFrames + padding)
        queue.fill(0f, queueFrames * channels, (queueFrames + padding) * channels)
        queueFrames += padding
        var hops = 0
        val limit = ((end - ideal) / (hop * speed)).toInt() + (padding / hop) + 4
        while (target < end && hops < limit && pieceCount < MAX_PIECES) {
            if (!stretchHop()) break
            hops++
        }
        queueFrames -= padding
        // The detector looked at the silence too; what comes next starts at the real end.
        scannedTo = min(scannedTo, end)
        mode = Mode.Bypass
        position = end
        tailFromQueue = false
    }

    /** Fades the tail out and the block in over one hop, then keeps the block's second half. */
    private fun overlapAdd() {
        val start = ensureOutput(hop)
        val tailOffset = if (tailFromQueue) ((tailStart - queueStart) * channels).toInt() else 0
        val source = if (tailFromQueue) queue else tail
        for (frame in 0 until hop) {
            val fadeIn = olaWindow[frame]
            val fadeOut = olaWindow[hop + frame]
            val base = frame * channels
            for (channel in 0 until channels) {
                output[start + base + channel] =
                    source[tailOffset + base + channel] * fadeOut + block[base + channel] * fadeIn
            }
        }
        block.copyInto(tail, 0, hop * channels, window * channels)
        tailFromQueue = false
        outputFrames += hop
        emittedFrames += hop
    }

    /**
     * Copies the block at [chosen] into [block]. A moved block starts as the natural continuation
     * and turns into the chosen one across its length, so the splice is spread over 20 ms.
     */
    private fun buildBlock(chosen: Long, blend: Boolean) {
        val offset = ((chosen - queueStart) * channels).toInt()
        if (!blend) {
            queue.copyInto(block, 0, offset, offset + window * channels)
            return
        }
        val natural = ((target - queueStart) * channels).toInt()
        for (frame in 0 until window) {
            val rise = transition[frame]
            val fall = 1f - rise
            val base = frame * channels
            for (channel in 0 until channels) {
                block[base + channel] = queue[offset + base + channel] * rise + queue[natural + base + channel] * fall
            }
        }
    }

    /**
     * The block start in [low]..[high] whose waveform best continues the output.
     *
     * Normalised cross-correlation against the natural continuation, every channel at once: first
     * on every [coarseStep]-th position with every [coarseStep]-th frame, then at full resolution
     * around the winner. Positions close to the last block are skipped, because repeating almost
     * the same block buzzes. A small preference for [center] keeps the output near the ideal line
     * when two positions match equally well, as every period of a steady tone does.
     */
    private fun search(low: Long, high: Long, center: Long): Long {
        val targetOffset = ((target - queueStart) * channels).toInt()
        val windowValues = window * channels
        var targetEnergy = 0.0
        for (index in targetOffset until targetOffset + windowValues) {
            val value = queue[index].toDouble()
            targetEnergy += value * value
        }
        val candidates = (high - low).toInt() + 1
        val lastBlock = target - hop
        // Silence matches anything; keep to the ideal line.
        if (targetEnergy < SILENCE_ENERGY * windowValues) return center.coerceIn(low, high)

        // Prefix sums of per-frame energy over the region, for every candidate's energy. The last
        // candidate's block ends on the region's last frame.
        val regionFrames = candidates + window - 1
        if (frameEnergy.size < regionFrames + 1) frameEnergy = DoubleArray(regionFrames + 1)
        val regionOffset = ((low - queueStart) * channels).toInt()
        frameEnergy[0] = 0.0
        for (frame in 0 until regionFrames) {
            var energy = 0.0
            val base = regionOffset + frame * channels
            for (channel in 0 until channels) {
                val value = queue[base + channel].toDouble()
                energy += value * value
            }
            frameEnergy[frame + 1] = frameEnergy[frame] + energy
        }

        // Coarse: both the target and the region decimated by the same step, so one contiguous
        // dot product scores each candidate.
        val step = coarseStep
        val coarseFrames = window / step
        val coarseValues = coarseFrames * channels
        if (coarseTarget.size < coarseValues) coarseTarget = FloatArray(coarseValues)
        for (frame in 0 until coarseFrames) {
            queue.copyInto(coarseTarget, frame * channels, targetOffset + frame * step * channels,
                targetOffset + frame * step * channels + channels)
        }
        val regionCoarseFrames = (regionFrames + step - 1) / step
        if (coarseRegion.size < regionCoarseFrames * channels) coarseRegion = FloatArray(regionCoarseFrames * channels)
        for (frame in 0 until regionCoarseFrames) {
            val from = regionOffset + frame * step * channels
            queue.copyInto(coarseRegion, frame * channels, from, from + channels)
        }

        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var candidate = 0
        while (candidate < candidates) {
            val start = low + candidate
            if (abs(start - lastBlock) > excludeHalf) {
                val coarseIndex = candidate / step
                val dot = dot(coarseTarget, 0, coarseRegion, coarseIndex * channels, coarseValues).toDouble() * step
                val score = score(dot, targetEnergy, candidate, start, center)
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            candidate += step
        }
        if (best < 0) return target.coerceIn(low, high)

        // Fine: every position the coarse step skipped around the winner, at full resolution.
        val from = max(0, best - step + 1)
        val to = min(candidates - 1, best + step - 1)
        var fineBest = best
        var fineScore = Double.NEGATIVE_INFINITY
        for (index in from..to) {
            val start = low + index
            if (abs(start - lastBlock) <= excludeHalf) continue
            val dot = dot(queue, targetOffset, queue, regionOffset + index * channels, windowValues).toDouble()
            val score = score(dot, targetEnergy, index, start, center)
            if (score > fineScore) {
                fineScore = score
                fineBest = index
            }
        }
        return low + fineBest
    }

    private fun score(dot: Double, targetEnergy: Double, candidate: Int, start: Long, center: Long): Double {
        val energy = (frameEnergy[candidate + window] - frameEnergy[candidate]).coerceAtLeast(0.0)
        val similarity = dot / sqrt(targetEnergy * energy + SILENCE_ENERGY)
        return similarity - CENTER_PREFERENCE * abs(start - center) / searchHalf
    }

    // ---------------------------------------------------------------------------------------------
    // Onsets
    // ---------------------------------------------------------------------------------------------

    /**
     * Finds attacks in the queued input that the stretch has not looked at yet.
     *
     * An onset is a [onsetBlock] whose high-frequency energy, the energy of the first difference,
     * rises [ONSET_RISE] times above its recent average: a drum hit, a plucked note, a consonant.
     * One pass over each frame, once.
     */
    private fun scanOnsets() {
        if (scannedTo < queueStart) restartOnsets(queueStart)
        var frame = scannedTo
        val end = queueEnd
        while (frame < end) {
            val base = ((frame - queueStart) * channels).toInt()
            var energy = 0.0
            for (channel in 0 until channels) {
                val value = queue[base + channel]
                val step = (value - previousFrame[channel]).toDouble()
                energy += step * step
                previousFrame[channel] = value
            }
            blockEnergy += energy
            blockFill++
            frame++
            if (blockFill == onsetBlock) {
                val start = frame - onsetBlock
                if (levelPrimed && blockEnergy > ONSET_RISE * blockLevel &&
                    blockEnergy > ONSET_FLOOR * onsetBlock && start - lastOnset >= onsetGap
                ) {
                    recordOnset(start)
                }
                blockLevel = if (levelPrimed) blockLevel + ONSET_SMOOTHING * (blockEnergy - blockLevel) else blockEnergy
                levelPrimed = true
                blockEnergy = 0.0
                blockFill = 0
            }
        }
        scannedTo = end
    }

    /** Starts the detector over at [from], forgetting every onset it held. */
    private fun restartOnsets(from: Long) {
        scannedTo = from
        onsetCount = 0
        lastOnset = Long.MIN_VALUE / 2
        blockEnergy = 0.0
        blockFill = 0
        levelPrimed = false
        val base = ((from - queueStart) * channels).toInt()
        for (channel in 0 until channels) {
            previousFrame[channel] = if (from > queueStart) queue[base - channels + channel] else 0f
        }
    }

    private fun recordOnset(at: Long) {
        // Oldest first; the oldest goes when full, and it is long played by then.
        if (onsetCount == onsets.size) {
            onsets.copyInto(onsets, 0, 1, onsetCount)
            onsetCount--
        }
        onsets[onsetCount++] = at
        lastOnset = at
    }

    private fun onsetBetween(from: Long, until: Long): Boolean {
        for (index in 0 until onsetCount) if (onsets[index] in from until until) return true
        return false
    }

    private fun lastOnsetBefore(position: Long): Long? {
        var found: Long? = null
        for (index in 0 until onsetCount) if (onsets[index] < position) found = onsets[index]
        return found
    }

    private fun firstOnsetFrom(position: Long): Long? {
        for (index in 0 until onsetCount) if (onsets[index] >= position) return onsets[index]
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Fold
    // ---------------------------------------------------------------------------------------------

    private fun beginFold() {
        foldPosition = position.toDouble()
        mode = Mode.Fold
    }

    /** Reads the input at [speed] for as far as the queue reaches. False when it made nothing. */
    private fun foldRun(): Boolean {
        val rate = speed
        val cutoff = min(1.0, 1.0 / rate)
        val reach = FOLD_ZERO_CROSSINGS / cutoff
        val first = outputFrames
        addPiece(foldPosition, rate)
        while (floor(foldPosition + reach).toLong() < queueEnd) {
            interpolate(foldPosition, cutoff, reach)
            foldPosition += rate
        }
        val made = outputFrames - first
        if (made == 0) dropEmptyPiece()
        emittedFrames += made
        return made > 0
    }

    /**
     * Brings the read position onto a whole input frame, so the bypass or the stretch can take
     * over without moving the waveform by a fraction of a sample. Walks there at a rate within
     * [ALIGN_RATE] of 1.0, which is a few milliseconds and inaudible.
     */
    private fun settleFold(): Boolean {
        val whole = floor(foldPosition)
        val fraction = foldPosition - whole
        if (fraction < ALIGNED || fraction > 1.0 - ALIGNED) {
            position = foldPosition.roundToLong()
            mode = Mode.Bypass
            return true
        }
        val goal = if (fraction < 0.5) whole else whole + 1.0
        val distance = goal - foldPosition
        val steps = ceil(abs(distance) / ALIGN_RATE).toInt()
        val rate = 1.0 + distance / steps
        val cutoff = min(1.0, 1.0 / rate)
        val reach = FOLD_ZERO_CROSSINGS / cutoff
        if (floor(goal + reach).toLong() >= queueEnd) return false
        addPiece(foldPosition, rate)
        repeat(steps) {
            interpolate(foldPosition, cutoff, reach)
            foldPosition += rate
        }
        emittedFrames += steps
        position = goal.toLong()
        mode = Mode.Bypass
        return true
    }

    /** At the end of a stream: reads on to the last input frame, with silence past it. */
    private fun foldToEnd() {
        val rate = speed
        val cutoff = min(1.0, 1.0 / rate)
        val reach = FOLD_ZERO_CROSSINGS / cutoff
        val first = outputFrames
        addPiece(foldPosition, rate)
        val end = queueEnd.toDouble()
        while (foldPosition < end) {
            interpolate(foldPosition, cutoff, reach)
            foldPosition += rate
        }
        val made = outputFrames - first
        if (made == 0) dropEmptyPiece()
        emittedFrames += made
        position = queueEnd
        mode = Mode.Bypass
    }

    /** One output frame from the input around [at], low-passed to [cutoff] of the input's Nyquist. */
    private fun interpolate(at: Double, cutoff: Double, reach: Double) {
        foldSum.fill(0f)
        val first = ceil(at - reach).toLong()
        val last = floor(at + reach).toLong()
        val kernel = foldKernel
        val scale = FOLD_TABLE_RESOLUTION * cutoff
        for (frame in first..last) {
            if (frame < queueStart || frame >= queueEnd) continue
            val distance = abs(at - frame) * scale
            val index = distance.toInt()
            if (index >= kernel.size - 1) continue
            val fraction = (distance - index).toFloat()
            val weight = (kernel[index] + (kernel[index + 1] - kernel[index]) * fraction) * cutoff.toFloat()
            val base = ((frame - queueStart) * channels).toInt()
            for (channel in 0 until channels) foldSum[channel] += queue[base + channel] * weight
        }
        val start = ensureOutput(1)
        foldSum.copyInto(output, start, 0, channels)
        outputFrames += 1
    }

    // ---------------------------------------------------------------------------------------------
    // Queue, output and pieces
    // ---------------------------------------------------------------------------------------------

    /** Plays [frames] queued frames from the read position straight through. */
    private fun emitCopy(frames: Int) {
        addPiece(position.toDouble(), 1.0)
        val start = ensureOutput(frames)
        val offset = ((position - queueStart) * channels).toInt()
        queue.copyInto(output, start, offset, offset + frames * channels)
        position += frames
        outputFrames += frames
        emittedFrames += frames
    }

    private fun addPiece(source: Double, slope: Double) {
        if (pieceCount > 0) {
            val last = pieceCount - 1
            val predicted = pieceSources[last] + (outputFrames - pieceStarts[last]) * pieceSlopes[last]
            if (pieceSlopes[last] == slope && abs(predicted - source) < PIECE_TOLERANCE) return
            if (pieceStarts[last] == outputFrames) {
                pieceSources[last] = source
                pieceSlopes[last] = slope
                return
            }
        }
        check(pieceCount < MAX_PIECES) { "the stage made more than $MAX_PIECES runs in one call" }
        pieceStarts[pieceCount] = outputFrames
        pieceSources[pieceCount] = source
        pieceSlopes[pieceCount] = slope
        pieceCount++
    }

    private fun dropEmptyPiece() {
        if (pieceCount > 0 && pieceStarts[pieceCount - 1] == outputFrames) pieceCount--
    }

    private fun append(input: FloatArray, frames: Int) {
        ensureQueue(queueFrames + frames)
        input.copyInto(queue, queueFrames * channels, 0, frames * channels)
        queueFrames += frames
    }

    private fun ensureQueue(frames: Int) {
        val values = frames * channels
        if (queue.size < values) queue = queue.copyOf(max(values, queue.size * 2))
    }

    /** Forgets the [frames] oldest queued frames. */
    private fun discard(frames: Int) {
        if (frames <= 0) return
        val remaining = queueFrames - frames
        if (remaining > 0) queue.copyInto(queue, 0, frames * channels, queueFrames * channels)
        queueFrames = max(0, remaining)
        queueStart += frames
    }

    /** Drops what no later step can read. */
    private fun trimQueue() {
        val keepFrom = when (mode) {
            Mode.Bypass -> position - history
            Mode.Stretch -> {
                val searchFloor = searchCenter() - searchHalf
                var earliest = min(target, searchFloor)
                if (tailFromQueue) earliest = min(earliest, tailStart)
                earliest
            }
            Mode.Fold -> floor(foldPosition).toLong() - foldReachMax
        }
        val drop = keepFrom - queueStart
        if (drop > 0) discard(min(drop, queueFrames.toLong()).toInt())
    }

    /** Grows [output] for [frames] more and returns the write position in values. */
    private fun ensureOutput(frames: Int): Int {
        val start = outputFrames * channels
        val needed = start + frames * channels
        if (output.size < needed) output = output.copyOf(max(needed, output.size * 2))
        return start
    }

    companion object {
        /** The supported range. */
        const val MIN_SPEED: Double = 0.25
        const val MAX_SPEED: Double = 4.0

        /**
         * The range one stage takes, an octave past the supported one either way, because a pitch
         * shift asks its stretch for the speed over the pitch (#465): a speed of 4 an octave down
         * stretches at 8.
         */
        const val STAGE_MIN_SPEED: Double = MIN_SPEED / 2
        const val STAGE_MAX_SPEED: Double = MAX_SPEED * 2

        /** Overlap-add block length. 20 ms holds a low voice's period twice and smears no attack. */
        private const val WINDOW_SECONDS = 0.020

        /** How far a block may move from the ideal line to find a better match, either way. */
        private const val SEARCH_HALF_SECONDS = 0.020

        /** Block starts this close to the previous block are not candidates. */
        private const val EXCLUDE_HALF_SECONDS = 0.0017

        /** The coarse search's step is one frame at this rate: four frames at 48 kHz. */
        private const val COARSE_RATE = 12_000.0

        /** How much a full search half-width away from the ideal line costs, in correlation. */
        private const val CENTER_PREFERENCE = 0.05

        /** Energy per value below which a block counts as silence. */
        private const val SILENCE_ENERGY = 1e-10

        private const val MIN_HOP = 8
        private const val MAX_PIECES = 8
        private const val PIECE_TOLERANCE = 1e-6

        /** The onset detector's block, and the shortest time between two onsets. */
        private const val ONSET_BLOCK_SECONDS = 0.002
        private const val ONSET_GAP_SECONDS = 0.025

        /** A block is an onset when its high-frequency energy is this many times its recent average. */
        private const val ONSET_RISE = 8.0

        /** Per-frame high-frequency energy below which nothing counts as an attack, about -60 dB. */
        private const val ONSET_FLOOR = 1e-6

        /** How fast the recent average follows each block: about 20 ms of memory. */
        private const val ONSET_SMOOTHING = 0.1
        private const val MAX_ONSETS = 64

        /** How much of each attack's measured lead the centre takes, and how fast it forgets. */
        private const val ONSET_LEAD_GAIN = 0.4
        private const val ONSET_LEAD_DECAY = 0.999

        /** The fold's kernel reaches this many zero crossings each side of the read position. */
        private const val FOLD_ZERO_CROSSINGS = 16.0
        private const val FOLD_TABLE_RESOLUTION = 512
        private const val FOLD_KAISER_BETA = 9.0

        /** The furthest the fold's settling walk moves the rate from 1.0. */
        private const val ALIGN_RATE = 0.002
        private const val ALIGNED = 1e-9

        /**
         * One side of the fold's Kaiser-windowed sinc, [FOLD_TABLE_RESOLUTION] points per zero
         * crossing. Whole crossings are exact zeros, so at 1.0 on a whole frame the fold copies.
         */
        private val foldKernel: FloatArray by lazy {
            val size = (FOLD_ZERO_CROSSINGS * FOLD_TABLE_RESOLUTION).toInt() + 2
            val denominator = besselI0(FOLD_KAISER_BETA)
            FloatArray(size) { index ->
                val x = index.toDouble() / FOLD_TABLE_RESOLUTION
                when {
                    index == 0 -> 1f
                    x >= FOLD_ZERO_CROSSINGS -> 0f
                    index % FOLD_TABLE_RESOLUTION == 0 -> 0f
                    else -> {
                        val sinc = sin(PI * x) / (PI * x)
                        val ratio = x / FOLD_ZERO_CROSSINGS
                        val kaiser = besselI0(FOLD_KAISER_BETA * sqrt(1.0 - ratio * ratio)) / denominator
                        (sinc * kaiser).toFloat()
                    }
                }
            }
        }

        /** The modified Bessel function of the first kind, order zero, by its power series. */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2.0
            var k = 1
            while (k < 64) {
                term *= (half / k) * (half / k)
                sum += term
                if (term < sum * 1e-12) break
                k++
            }
            return sum
        }

        /** Four accumulators, so the loop is not one long chain of dependent adds. */
        private fun dot(a: FloatArray, aOffset: Int, b: FloatArray, bOffset: Int, length: Int): Float {
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            var s3 = 0f
            var i = 0
            val unrolled = length - 3
            while (i < unrolled) {
                s0 += a[aOffset + i] * b[bOffset + i]
                s1 += a[aOffset + i + 1] * b[bOffset + i + 1]
                s2 += a[aOffset + i + 2] * b[bOffset + i + 2]
                s3 += a[aOffset + i + 3] * b[bOffset + i + 3]
                i += 4
            }
            while (i < length) {
                s0 += a[aOffset + i] * b[bOffset + i]
                i++
            }
            return (s0 + s1) + (s2 + s3)
        }
    }
}
