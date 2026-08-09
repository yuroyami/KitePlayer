package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import kotlinx.atomicfu.atomic
import kotlin.math.abs
import kotlin.math.min

/**
 * The buffer between the engine and the audio device.
 *
 * One producer, the audio feeder coroutine. One consumer, the device's real-time callback. No lock
 * anywhere: the two sides share only monotonically increasing frame counters, which is enough for a
 * single-producer single-consumer ring.
 *
 * Its size makes the device's period invisible to the rest of the player. A device that asks for
 * 512 frames at a time and one that asks for 4096 look identical from the engine's side.
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
internal class AudioRing(
    val format: AudioFormat,
    val capacityFrames: Int,
) {
    private val channels = format.channels
    private val data = FloatArray(capacityFrames * channels)

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

    val underruns: Long get() = underrunCount.value

    /** Frames written but not yet handed to the device. */
    val bufferedFrames: Int get() = (written.value - consumed.value).toInt().coerceAtLeast(0)

    val bufferedUs: Long get() = format.durationOf(bufferedFrames).micros

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
    fun write(source: FloatArray, sourceOffset: Int, frames: Int, pts: Pts?): Int {
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
            val predictedUs = segmentPtsUs[newest] +
                (atFrame - segmentStartFrame[newest]) * 1_000_000L / format.sampleRate
            if (abs(predictedUs - ptsUs) < DISCONTINUITY_TOLERANCE_US) return true
        }
        return appendSegment(ptsUs, atFrame)
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
    fun render(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int {
        val startFrame = consumed.value
        val available = (written.value - startFrame).toInt().coerceAtLeast(0)
        val toRead = min(frames, available)

        if (toRead > 0) {
            var frameIndex = (startFrame % capacityFrames).toInt()
            var writtenSoFar = 0
            while (writtenSoFar < toRead) {
                val runFrames = min(toRead - writtenSoFar, capacityFrames - frameIndex)
                destination.writeInterleaved(
                    source = data,
                    sourceOffset = frameIndex * channels,
                    destinationFrameOffset = writtenSoFar,
                    frames = runFrames,
                )
                frameIndex = (frameIndex + runFrames) % capacityFrames
                writtenSoFar += runFrames
            }
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

            val boundaryPtsUs = basePtsUs + (lastRealFrame + 1 - baseFrame) * 1_000_000L / format.sampleRate
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
    fun anchor(): AudioAnchor? {
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
    fun markEnding() {
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
    fun flush() {
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
    }
}

/**
 * The media timestamp the playhead reaches at a given instant on the monotonic clock.
 *
 * The timestamp is a boundary between two samples, not a sample's own start: everything before it has
 * been heard by [audibleAtNanos], and nothing after it has.
 */
internal data class AudioAnchor(val pts: Pts, val audibleAtNanos: Long)
