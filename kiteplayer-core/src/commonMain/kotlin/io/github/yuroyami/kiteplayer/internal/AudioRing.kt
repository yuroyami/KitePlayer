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
 * will be heard. So the callback publishes the pair (media timestamp, the instant it becomes
 * audible), and the core anchors its clock from that pair directly.
 *
 * This is why there is no device latency subtracted anywhere in this class. The deadline already
 * accounts for it. ffplay instead estimates the latency as two device periods, which is wrong on
 * most backends and produces a fixed audio delay for the whole session that nothing corrects.
 *
 * The pair is published with a sequence counter rather than as an allocated object, because the
 * publisher is a real-time audio thread and must not allocate.
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

    /** Bumped by the feeder on a flush, so the callback stops handing out stale audio. */
    private val epoch = atomic(0L)

    // The feeder's mapping from absolute frame index to media timestamp. Written by the feeder,
    // read by the callback, so it is published under its own sequence counter.
    private val mapSeq = atomic(0L)
    private val mapPtsUs = atomic(0L)
    private val mapFrame = atomic(0L)
    private val mapValid = atomic(false)

    // The callback's answer: the media timestamp of the last frame handed over, and when it is
    // audible. Written by the callback, read by the core.
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
     * @return frames accepted, which is fewer than [frames] when the ring is nearly full. The feeder
     *         retries the remainder rather than dropping it.
     */
    fun write(source: FloatArray, sourceOffset: Int, frames: Int, pts: Pts?): Int {
        val room = freeFrames
        if (room <= 0) return 0
        val toWrite = min(frames, room)

        val startFrame = written.value
        anchorMapping(pts, startFrame)

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
     * Re-anchored only when the incoming timestamp disagrees with what continuity predicts, which
     * happens at the start, after a seek, and at a real gap in the audio. Anchoring on every buffer
     * instead would make the clock follow the jitter in the container's timestamps.
     */
    private fun anchorMapping(pts: Pts?, atFrame: Long) {
        if (pts == null) return
        if (mapValid.value) {
            val predicted = ptsOfFrameOrNull(atFrame)
            if (predicted != null && abs(predicted.micros - pts.micros) < DISCONTINUITY_TOLERANCE_US) return
        }
        mapSeq.incrementAndGet()
        mapPtsUs.value = pts.micros
        mapFrame.value = atFrame
        mapValid.value = true
        mapSeq.incrementAndGet()
    }

    private fun ptsOfFrameOrNull(absoluteFrame: Long): Pts? {
        while (true) {
            val seq = mapSeq.value
            if (seq % 2L != 0L) continue
            if (!mapValid.value) return null
            val basePts = mapPtsUs.value
            val baseFrame = mapFrame.value
            if (mapSeq.value != seq) continue
            val delta = absoluteFrame - baseFrame
            return Pts(basePts + delta * 1_000_000L / format.sampleRate)
        }
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
            val lastRealFrameNanos = deadlineNanos - ((frames - toRead) * nanosPerFrame).toLong()
            val lastRealFramePts = ptsOfFrameOrNull(startFrame + toRead - 1)
            if (lastRealFramePts != null) {
                anchorSeq.incrementAndGet()
                anchorPtsUs.value = lastRealFramePts.micros
                anchorNanos.value = lastRealFrameNanos
                anchorValid.value = true
                anchorSeq.incrementAndGet()
            }
        }

        return toRead
    }

    /**
     * What media timestamp is audible, and at which instant.
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
     * Called on a seek, after the device has been stopped. Ordering matters: flushing while the
     * device is still pulling would let it play a mixture of old and new audio.
     */
    fun flush() {
        epoch.incrementAndGet()
        ending.value = false
        anchorValid.value = false
        mapValid.value = false
        consumed.value = written.value
    }

    internal companion object {
        /** How far a timestamp may drift from continuity before the mapping is re-anchored. */
        const val DISCONTINUITY_TOLERANCE_US: Long = 1_000
    }
}

/** The media timestamp that becomes audible at a given instant on the monotonic clock. */
internal data class AudioAnchor(val pts: Pts, val audibleAtNanos: Long)
