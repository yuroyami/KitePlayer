package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import kotlinx.atomicfu.atomic
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The portable implementation of [AudioRingHandle]: the buffer between the engine and the audio
 * device, in Kotlin.
 *
 * One producer, the audio feeder coroutine. One consumer, the device's real-time callback. No mutex
 * anywhere: the two sides share monotonically increasing frame counters plus two sequence-counter
 * publications, which is enough for a single-producer single-consumer ring.
 *
 * Its size makes the device's period invisible to the rest of the player. A device that asks for
 * 512 frames at a time and one that asks for 4096 look identical from the engine's side.
 *
 * ### Where this one runs, and where it does not
 *
 * This is the implementation for every target, and on macOS arm64 from B1.8 onward it is no longer
 * the one the device path uses; there the C ring in `kiteplayer-rt` is. It is not dead code and it
 * cannot be deleted: `commonMain` targets js and wasmJs, which can never contain C, and it is the
 * only oracle the C ring can be checked against. This must be stated
 * plainly rather than left for a reader to discover, and it is stated again in
 * `AudioRingTest`'s KDoc and in the README, because a green total quietly covering a ring the shipped
 * path does not use is exactly the substitution MASTER_PLAN.md forbids. The total is deliberately
 * not quoted here: a number in a comment is one more thing that can go stale, and the point does not
 * depend on which number it is.
 *
 * ### One thing the C ring does differently on purpose
 *
 * [publishAnchor] below makes the real-time thread the READER of [segmentSeq], whose writer is the
 * feeder, so it spins with no bound whenever the feeder is preempted between its two increments.
 * That is a priority inversion on a real-time thread. It is not fixed here: on
 * js and wasmJs there is no second thread for it to matter on, and changing the publication protocol
 * of the oracle would have meant changing the thing the C ring is measured against in the same
 * sub-phase that introduced the C ring. The C implementation inverts every such relationship and
 * counts its give-ups; see `kiteplayer-rt/native/include/kite_rt.h`.
 *
 * ### Why the anchor lives here
 *
 * The audio clock is the master clock, and it must answer "what media timestamp is audible right
 * now". The only code that knows is the device callback: it is told when the buffer it is filling
 * will be heard. So the callback publishes the pair (how far the media has played, the instant it
 * gets there), and the core anchors its clock from that pair directly.
 *
 * This is why there is no device latency subtracted anywhere in this class. The deadline already
 * accounts for it. ffplay instead estimates the latency as two device periods, which is wrong on
 * most backends and produces a fixed audio delay for the whole session that nothing corrects.
 *
 * The pair is published with a sequence counter rather than as an allocated object, because the
 * publisher is a real-time audio thread and must not allocate.
 *
 * ### The boundary convention
 *
 * A sample is an interval, not an instant, so pairing one with a time needs a stated convention, and
 * the two ends of that interval are a sample period apart. The published pair is the media time at
 * the playhead boundary: the timestamp of the last real sample handed over plus one sample period,
 * which reads as "the media has been played up to here", taken at the instant that boundary is
 * reached. Pairing a sample's own start timestamp with the moment it finishes instead is the same
 * class of mistake as guessing the device latency, one sample period of it, in the same direction,
 * for the whole session.
 *
 * ### Why one timestamp mapping is not enough
 *
 * Frame indices become media timestamps through a small ring of ordered segments, each of which says
 * "the frame at this index carries this timestamp, and the frames after it follow at the sample
 * rate". One mapping cannot describe the ring, because the feeder can write a discontinuity while
 * older samples are still queued: a seek landing or a real gap in the file gives it a timestamp that
 * does not continue the previous one, and the queued samples still need the previous one to be
 * dated. Overwriting a single mapping misdates everything the device has not played yet, which shows
 * up as a clock that jumps to the new position a whole buffer early.
 *
 * Four segments is more than a real file needs in flight, the storage is preallocated, and the ring
 * is published under its own sequence counter, because the side that resolves against it is the
 * real-time thread.
 */
internal class KotlinAudioRing(
    override val format: AudioFormat,
    val capacityFrames: Int,
) : AudioRingHandle {
    private val channels = format.channels

    init {
        require(capacityFrames > 0) { "capacityFrames must be positive, was $capacityFrames" }
        require(channels > 0) { "channels must be positive, was $channels" }
        // Checked in Long BEFORE the narrowing multiply: a wrapped Int here either throws from
        // FloatArray with a confusing size or, worse, allocates a small array every write then
        // indexes past. This mirrors the C ring's own guard.
        require(capacityFrames.toLong() * channels.toLong() <= Int.MAX_VALUE.toLong()) {
            "ring of $capacityFrames frames x $channels channels does not fit an array"
        }
    }

    private val data = FloatArray(capacityFrames * channels)

    /**
     * Scratch the render scales into, so the gain never writes back into [data].
     *
     * [data] is the producer's storage. Scaling it in place would multiply samples the feeder may
     * still be wrapping around into, and would make a frame's value depend on when it was read.
     * The render copies out, scales the copy, and hands that over.
     *
     * One capacity's worth is always enough: a render can never take more frames than the ring
     * holds, because `toRead` is bounded by what has been written and not yet consumed. Sized once
     * here because the device's thread may not allocate.
     */
    private val renderScratch = FloatArray(capacityFrames * channels)

    /**
     * The gain the device's callback multiplies by, and the slope it walks there at.
     *
     * ### Why the gain lives HERE and not in the pipeline
     *
     * It used to be the last stage of `AudioPipeline`, applied by the feeder as it converted a
     * buffer on its way INTO this ring. That is a write-side gain, and a write-side gain cannot
     * reach audio that is already buffered: every frame in the ring keeps whatever volume was set
     * when it was written. So a volume change was inaudible until the ring drained past it, which
     * is at least 200 ms by the ring's own sizing policy and, on an Android device whose
     * `AudioTrack` buffer sets the depth, three to six times that.
     *
     * Measured before the move, in `VolumeLatencyTest`: lag equals ring depth plus the ramp,
     * exactly, at every depth tested. Read-side gain makes the lag one device period instead,
     * without making the ring shallower, which is what the depth is for.
     *
     * ### The ramp, and why the slope is a field
     *
     * A gain that jumps is a step in the waveform and a step is a click, so the applied gain walks
     * towards the wanted one at a fixed slope: the whole range takes [GAIN_RAMP_DURATION] and a
     * smaller change proportionally less. The slope is what the click depends on, not the duration.
     *
     * [gainTarget] is written by whatever thread calls [setGain] and read by the device's callback,
     * so it is atomic. [gainCurrent] belongs to the callback alone.
     */
    private val gainTarget = atomic(1f)
    private var gainCurrent = 1f

    /**
     * False until the first frame is rendered, and what stops a fresh ring fading IN.
     *
     * A ring opened while muted, or at a low volume, has [gainCurrent] at unity and a target
     * somewhere below it, so its first render would walk down across a whole ramp of real audio.
     * That is a burst of near-full-scale sound at exactly the moment the user asked for silence.
     * `GainStage.adoptRamp` existed for the same reason on the pipeline side and is gone with it.
     *
     * Snapping instead of walking is right here and only here: no audio has been heard yet, so
     * there is no step in the waveform and nothing to click. Consumer-private, and read and written
     * only inside the render, so the engine never races the device for it.
     */
    private var gainStarted = false
    private val gainSlopePerFrame: Float = 1f / gainRampFrames(format.sampleRate)

    /** Total frames ever written by the feeder. Only the feeder advances it. */
    private val written = atomic(0L)

    /** Total frames ever handed to the device. Only the callback advances it. */
    private val consumed = atomic(0L)

    private val underrunCount = atomic(0L)

    /**
     * Set when the feeder knows no more audio is coming.
     *
     * Silence handed to the device after this point is the end of the media, not a failure to keep up,
     * so it is not counted as an underrun. Without this distinction every file reports a handful of
     * underruns as it finishes, which makes the counter useless for spotting the real thing.
     */
    private val ending = atomic(false)

    // The feeder's map from absolute frame index to media timestamp, as up to MAX_SEGMENTS ordered
    // segments. Written by the feeder, read by the callback. Two plain arrays rather than objects,
    // because the side that reads them is a real-time thread that must not allocate: the sequence
    // counter below publishes their contents, exactly as `written` publishes the samples in `data`.
    private val segmentStartFrame = LongArray(MAX_SEGMENTS)
    private val segmentPtsUs = LongArray(MAX_SEGMENTS)

    /** Segments ever appended. The live segments are the indices from [segmentsRetired] up to this. */
    private val segmentsAppended = atomic(0L)

    /** Segments the device has played past. Only the feeder advances it. */
    private val segmentsRetired = atomic(0L)

    /** Odd while the segment ring is being changed, so a reader knows to look again. */
    private val segmentSeq = atomic(0L)

    // The callback's answer: the media time at the playhead boundary, and when that boundary is
    // reached. Written by the callback, read by the core.
    private val anchorSeq = atomic(0L)
    private val anchorPtsUs = atomic(0L)
    private val anchorNanos = atomic(0L)
    private val anchorValid = atomic(false)

    private val nanosPerFrame: Double = if (format.sampleRate > 0) 1_000_000_000.0 / format.sampleRate else 0.0

    override val underruns: Long get() = underrunCount.value

    /** Frames written but not yet handed to the device. */
    override val bufferedFrames: Int get() = (written.value - consumed.value).toInt().coerceAtLeast(0)

    override val bufferedUs: Long get() = format.durationOf(bufferedFrames).micros

    /**
     * Frames the ring can still take.
     *
     * Not on [AudioRingHandle]: `AudioPlayback` never reads it, and the interface holds exactly what
     * `AudioPlayback` uses. It stays here because the ring's own tests and the differential oracle
     * compare it between the two implementations, and both of those hold the concrete types.
     */
    val freeFrames: Int get() = capacityFrames - bufferedFrames

    /**
     * Adds interleaved float samples.
     *
     * @param pts the media timestamp of the first frame in [source], when known.
     * @return frames accepted, which is fewer than [frames] when the ring is nearly full and zero in
     *         two cases: the ring is full, or [pts] needs a segment of its own and all four are still
     *         dating audio the device has not played. The feeder retries the remainder rather than
     *         dropping it, and the wait is the same one in both cases.
     */
    override fun write(source: FloatArray, sourceOffset: Int, frames: Int, pts: Pts?): Int {
        // Nothing to write means nothing is recorded either, and the order of these two lines is the
        // whole content of the rule. It reads like a triviality and it is not: the C ring's
        // `kprt_ring_begin_write` returns zero for a non-positive frame count before it looks at
        // anything else, so a zero-frame write there spends no timestamp segment. This ring used to
        // call `recordTimestamp` first, so four zero-frame writes with four discontinuous timestamps
        // spent all four segment slots and the fifth real write was refused. The independent
        // verification of B1.8 measured both sides and found the difference; it is unreachable from
        // `AudioPlayback.submit`, which never asks for fewer than one frame, and it was a real
        // divergence in a contract the differential oracle claims to pin, so the two now agree and
        // the oracle has a row for it.
        if (frames <= 0) return 0
        val room = freeFrames
        if (room <= 0) return 0
        val toWrite = min(frames, room)

        val startFrame = written.value
        if (pts != null && !recordTimestamp(pts.micros, startFrame)) return 0

        var srcIndex = sourceOffset
        var frameIndex = (startFrame % capacityFrames).toInt()
        var remaining = toWrite
        while (remaining > 0) {
            val runFrames = min(remaining, capacityFrames - frameIndex)
            val destIndex = frameIndex * channels
            source.copyInto(
                destination = data,
                destinationOffset = destIndex,
                startIndex = srcIndex,
                endIndex = srcIndex + runFrames * channels,
            )
            srcIndex += runFrames * channels
            frameIndex = (frameIndex + runFrames) % capacityFrames
            remaining -= runFrames
        }

        written.value = startFrame + toWrite
        return toWrite
    }

    /**
     * Records where a media timestamp sits in the frame stream.
     *
     * A segment is opened only when the incoming timestamp disagrees with what continuity predicts,
     * which happens at the start, after a seek, and at a real gap in the audio. Opening one on every
     * buffer instead would make the clock follow the jitter in the container's timestamps.
     *
     * The feeder is the only writer of the segment ring, so reading the newest segment here needs no
     * retry loop. Continuity is predicted from that newest segment alone, because the frame this
     * timestamp lands on is never earlier than the one the newest segment starts at.
     *
     * @return false when a segment was needed and none could be freed. The caller writes nothing.
     */
    private fun recordTimestamp(ptsUs: Long, atFrame: Long): Boolean {
        val appended = segmentsAppended.value
        if (appended > segmentsRetired.value) {
            val newest = ((appended - 1) % MAX_SEGMENTS).toInt()
            // Through framesToMicros and not `delta * 1_000_000L / sampleRate`, which overflows.
            // The naive product overflows a signed 64 bit intermediate at a large frame delta, which
            // is the shape defect D9 records against KiteFFmpeg's timestamp helpers.
            val micros = framesToMicros(atFrame - segmentStartFrame[newest], format.sampleRate)
            if (driftWithinTolerance(segmentPtsUs[newest], micros, ptsUs)) return true
        }
        return appendSegment(ptsUs, atFrame)
    }

    /**
     * Is [ptsUs] where continuity from [basePtsUs] plus [micros] says it should be, within the
     * tolerance?
     *
     * A function rather than one expression because of the overflow. Kotlin's `Long` arithmetic wraps
     * where C's is undefined, so the naive form is not a crash here, but it is still wrong in the same
     * place and in a worse way for this library: a wrapped difference can land inside the tolerance,
     * and then the ring predicts the clock from a base an eternity away instead of opening a segment.
     * UBSan named the C side of exactly this during the independent verification of B1.8, and the two
     * implementations have to agree at every input or the differential oracle is comparing behaviours
     * rather than checking one.
     *
     * Any of the three overflows means the two timestamps are further apart than any tolerance, so the
     * answer is a discontinuity. [Long.MIN_VALUE] is its own case because `abs(Long.MIN_VALUE)` is
     * negative in Kotlin, which would read as a distance below every tolerance. `kite_rt_ring.c`'s
     * `drift_within_tolerance` is the same three decisions in the same order.
     */
    /**
     * [a] plus [b], saturating at the ends of the range rather than wrapping.
     *
     * Kotlin's `Long` addition wraps where C's is undefined, so this is not a crash on this side. It is
     * still the wrong number, and it dates every anchor the media clock is built from. The two rings
     * have to agree at every input, including the two the differential oracle now drives at the ends of
     * the range, and "both wrapped the same way" is agreement that proves nothing.
     * `add_saturating` in `kite_rt_render.c` is the same three lines.
     */
    private fun addSaturating(a: Long, b: Long): Long {
        val sum = a + b
        if (((a xor sum) and (b xor sum)) < 0) return if (b < 0) Long.MIN_VALUE else Long.MAX_VALUE
        return sum
    }

    private fun driftWithinTolerance(basePtsUs: Long, micros: Long, ptsUs: Long): Boolean {
        val predicted = basePtsUs + micros
        // The sum overflowed when both addends have the sign the result does not.
        if (((basePtsUs xor predicted) and (micros xor predicted)) < 0) return false
        val drift = predicted - ptsUs
        // The difference overflowed when the operands differ in sign and the result takes the
        // subtrahend's.
        if (((predicted xor ptsUs) and (predicted xor drift)) < 0) return false
        if (drift == Long.MIN_VALUE) return false
        return abs(drift) < DISCONTINUITY_TOLERANCE_US
    }

    private fun appendSegment(ptsUs: Long, atFrame: Long): Boolean {
        if (segmentsAppended.value - segmentsRetired.value >= MAX_SEGMENTS) retireConsumedSegments()
        val appended = segmentsAppended.value
        if (appended - segmentsRetired.value >= MAX_SEGMENTS) return false

        val slot = (appended % MAX_SEGMENTS).toInt()
        segmentSeq.incrementAndGet()
        segmentStartFrame[slot] = atFrame
        segmentPtsUs[slot] = ptsUs
        segmentsAppended.value = appended + 1
        segmentSeq.incrementAndGet()
        return true
    }

    /**
     * Drops segments the device has played past.
     *
     * A segment is finished once the callback has consumed every frame it dates, which is what the
     * next segment's start says. The segment holding the last consumed frame stays: that is the one
     * a callback still inside [render] is dating its anchor from, and the newest segment stays too,
     * because it dates everything still to be written.
     *
     * Called by the feeder only, when a new segment needs the slot, so the ring keeps its single
     * writer. A finished segment left in place until then costs nothing, since resolution always
     * takes the newest segment that covers the frame.
     */
    private fun retireConsumedSegments() {
        val consumedNow = consumed.value
        val appended = segmentsAppended.value
        val retired = segmentsRetired.value
        var stillNeeded = retired
        while (appended - stillNeeded > 1) {
            val nextStart = segmentStartFrame[((stillNeeded + 1) % MAX_SEGMENTS).toInt()]
            if (nextStart >= consumedNow) break
            stillNeeded++
        }
        if (stillNeeded == retired) return
        segmentSeq.incrementAndGet()
        segmentsRetired.value = stillNeeded
        segmentSeq.incrementAndGet()
    }

    /**
     * Fills [destination] from the ring. Called on the device's real-time thread.
     *
     * Allocation free, lock free, and it never blocks. When the ring runs dry it writes silence for
     * the remainder and counts an underrun, because a device that is starved must still be fed
     * something: stopping would click.
     *
     * @param deadlineNanos when the last frame of the whole request becomes audible.
     * @return frames of real audio written. The rest of [destination] is silence.
     */
    override fun setGain(target: Float) {
        require(target.isFinite() && target >= 0f && target <= GAIN_MAX) {
            "gain must be between 0 and $GAIN_MAX, was $target"
        }
        gainTarget.value = target
    }

    fun render(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int {
        val startFrame = consumed.value
        val available = (written.value - startFrame).toInt().coerceAtLeast(0)
        val toRead = min(frames, available)

        if (toRead > 0) {
            // Copy out first, then scale the copy. Two steps and not one, because `data` belongs to
            // the feeder: scaling in place would multiply frames it may still wrap into.
            var frameIndex = (startFrame % capacityFrames).toInt()
            var writtenSoFar = 0
            while (writtenSoFar < toRead) {
                val runFrames = min(toRead - writtenSoFar, capacityFrames - frameIndex)
                data.copyInto(
                    destination = renderScratch,
                    destinationOffset = writtenSoFar * channels,
                    startIndex = frameIndex * channels,
                    endIndex = (frameIndex + runFrames) * channels,
                )
                frameIndex = (frameIndex + runFrames) % capacityFrames
                writtenSoFar += runFrames
            }
            applyGain(renderScratch, toRead)
            destination.writeInterleaved(
                source = renderScratch,
                sourceOffset = 0,
                destinationFrameOffset = 0,
                frames = toRead,
            )
            consumed.value = startFrame + toRead
        }

        if (toRead < frames) {
            destination.writeSilence(frameOffset = toRead, frames = frames - toRead)
            if (!ending.value) underrunCount.incrementAndGet()
        }

        if (toRead > 0) {
            // The request's last frame lands at deadlineNanos. Our last real frame is earlier than
            // that by however much of the request was silence.
            val boundaryNanos = deadlineNanos - ((frames - toRead) * nanosPerFrame).toLong()
            publishAnchor(lastRealFrame = startFrame + toRead - 1, atNanos = boundaryNanos)
        }

        return toRead
    }

    /**
     * Multiplies [frames] sample frames of interleaved [samples] in place, walking to the target.
     *
     * Only real frames are walked. Silence written after an underrun is already zero, and advancing
     * the ramp across it would make the gain depend on how badly the feeder was starved.
     *
     * The C ring does the same thing in the same order; the differential oracle compares the
     * samples, so a difference here is a failing row there rather than a surprise on one platform.
     */
    private fun applyGain(samples: FloatArray, frames: Int) {
        val wanted = gainTarget.value
        if (!gainStarted) {
            gainStarted = true
            gainCurrent = wanted
        }
        var gain = gainCurrent
        if (gain == wanted) {
            if (wanted > 1f) {
                // Boosting. Fold, so a loud passage cannot leave here squared off.
                for (i in 0 until frames * channels) {
                    samples[i] *= wanted
                    samples[i] = softClip(samples[i])
                }
            } else if (wanted != 1f) {
                for (i in 0 until frames * channels) samples[i] *= wanted
            }
            return
        }
        var base = 0
        for (frame in 0 until frames) {
            gain = if (gain < wanted) min(wanted, gain + gainSlopePerFrame)
                   else max(wanted, gain - gainSlopePerFrame)
            // Per FRAME and not per buffer: a walk that crosses unity must leave the frames below
            // it exactly as they were and fold only the ones above.
            if (gain > 1f) {
                for (channel in 0 until channels) {
                    samples[base + channel] *= gain
                    samples[base + channel] = softClip(samples[base + channel])
                }
            } else {
                for (channel in 0 until channels) samples[base + channel] *= gain
            }
            base += channels
        }
        gainCurrent = gain
    }

    /**
     * Publishes the media time one sample past [lastRealFrame], reached at [atNanos].
     *
     * Runs on the real-time thread, so it allocates nothing and returns nothing: the segment it needs
     * is found by reading the preallocated ring into local values under the feeder's sequence
     * counter, and the answer goes straight into the anchor's own fields.
     *
     * A frame earlier than the oldest live segment is dated by continuity from that segment, which is
     * what the frames written before the first timestamp arrived need. When no segment exists at all
     * nothing is published, and the clock keeps reading null, which is the honest answer.
     */
    private fun publishAnchor(lastRealFrame: Long, atNanos: Long) {
        while (true) {
            val seq = segmentSeq.value
            if (seq % 2L != 0L) continue
            val appended = segmentsAppended.value
            val retired = segmentsRetired.value
            if (segmentSeq.value != seq) continue
            if (appended <= retired) return

            var index = appended - 1
            while (index > retired && segmentStartFrame[(index % MAX_SEGMENTS).toInt()] > lastRealFrame) {
                index--
            }
            val slot = (index % MAX_SEGMENTS).toInt()
            val baseFrame = segmentStartFrame[slot]
            val basePtsUs = segmentPtsUs[slot]
            if (segmentSeq.value != seq) continue

            // The same overflow again, and this is the site that matters most: it dates every
            // anchor the audio clock is built from.
            val boundaryPtsUs =
                addSaturating(basePtsUs, framesToMicros(lastRealFrame + 1 - baseFrame, format.sampleRate))
            anchorSeq.incrementAndGet()
            anchorPtsUs.value = boundaryPtsUs
            anchorNanos.value = atNanos
            anchorValid.value = true
            anchorSeq.incrementAndGet()
            return
        }
    }

    /**
     * How far the media has been played, and the instant it reaches that point.
     *
     * Read by the core to anchor the audio clock. Null before the device has played anything, and
     * after a flush.
     */
    override fun anchor(): AudioAnchor? {
        while (true) {
            val seq = anchorSeq.value
            if (seq % 2L != 0L) continue
            if (!anchorValid.value) return null
            val pts = anchorPtsUs.value
            val nanos = anchorNanos.value
            if (anchorSeq.value != seq) continue
            return AudioAnchor(Pts(pts), nanos)
        }
    }

    /** Tells the ring that the feeder has finished, so trailing silence is not an underrun. */
    override fun markEnding() {
        ending.value = true
    }

    /**
     * Discards everything unplayed.
     *
     * Precondition, and not advice: the sink is stopped and both sides are quiescent, meaning the
     * device callback is provably out of [render] and the feeder is not inside [write]. This is
     * required because [flush] writes [consumed], which is the callback's own counter, and drops the
     * segments the callback dates its anchor from. A device still pulling from a ring being cleared
     * would play a mixture of the old position and the new one, and would date it from either.
     *
     * The seek sequence stops the sink and waits for the feeder to acknowledge quiescence before
     * calling this, for exactly that reason.
     */
    override fun flush() {
        ending.value = false
        anchorValid.value = false
        segmentSeq.incrementAndGet()
        segmentsRetired.value = 0
        segmentsAppended.value = 0
        segmentSeq.incrementAndGet()
        consumed.value = written.value
    }

    internal companion object {
        /** How far a timestamp may drift from continuity before a segment of its own is opened. */
        const val DISCONTINUITY_TOLERANCE_US: Long = 1_000

        /**
         * Timestamp segments held at once.
         *
         * Four covers what can be in flight: the segment the device is playing, the one it is about
         * to reach, and a discontinuity written on top of both. A file that needs more than that
         * between two device callbacks is not a file anyone can play.
         */
        const val MAX_SEGMENTS: Int = 4

        /**
         * The saturator the gain walk folds through above unity.
         *
         * Identity up to the knee, then a rational fold that approaches full scale without ever
         * reaching it: the excess over the knee is mapped through x/(1+x), which is 0 at the knee
         * and tends to 1 however hard it is driven. So a boosted passage compresses instead of
         * squaring off, and a boost can never produce a sample outside full scale.
         *
         * Written as separate statements ON PURPOSE, matching `kprt_soft_clip` in
         * `kite_rt_render.c` statement for statement. The C side compiles with -ffp-contract=off so
         * its multiply-add cannot become an FMA; this side must not be written in a shape that
         * invites the same fusion, because the differential oracle compares the two rings by raw
         * bits and one fused instruction is a failing row rather than a rounding difference.
         */
        internal fun softClip(x: Float): Float {
            val knee = 0.75f
            val mag = if (x < 0f) -x else x
            if (mag <= knee) return x
            val excess = (mag - knee) / (1f - knee)
            var folded = excess / (1f + excess)
            folded = (1f - knee) * folded
            folded = knee + folded
            return if (x < 0f) -folded else folded
        }
    }
}
