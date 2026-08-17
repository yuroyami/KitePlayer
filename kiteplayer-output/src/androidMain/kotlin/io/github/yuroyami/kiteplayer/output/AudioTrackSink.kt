package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/**
 * The Android audio output: one `AudioTrack` in MODE_STREAM behind the engine's pull contract
 * (S1.c.4, register item S1C-04).
 *
 * `AudioTrack` is a push API while the engine and `KotlinAudioRing` expose a pull callback that
 * owns the audio clock anchor. The adaptation is the one wrapper the `AudioSink` KDoc names for
 * push platforms: a dedicated priority-audio writer thread turns "the device has room" into a
 * pull. Each loop iteration computes the deadline of the LAST frame of the requested block,
 * invokes the engine's [AudioRenderCallback] into a preallocated buffer, silences any tail a
 * short return leaves (nothing above the sink zeroes it, per the callback contract), and loops
 * `write(..., WRITE_BLOCKING)` until the block is fully submitted or a lifecycle signal lands.
 *
 * The sink accepts mono or stereo only and never converts: mixing and resampling remain in the
 * engine, where their latency is known and where they are testable in commonMain.
 *
 * The internal constructor takes the driver factory and the clock so the host suite drives every
 * lifecycle and arithmetic arm with a fake, and so production cannot accidentally pair
 * `AudioTimestamp` (`System.nanoTime` CLOCK_MONOTONIC base) with another time base: the public constructor
 * hard-wires [AndroidMonotonicClock].
 */
public class AudioTrackSink internal constructor(
    private val driverFactory: AudioTrackDriverFactory,
    private val clock: MonotonicClock,
) : AudioSink {

    public constructor() : this(
        AudioTrackDriverFactory { accepted -> PlatformAudioTrackDriver(accepted) },
        AndroidMonotonicClock,
    )

    /* Lock discipline, learned from a real deadlock this file shipped for eleven minutes: the
     * writer thread takes [headLock] on every loop (head extension) and NOTHING else; lifecycle
     * methods take [lifecycle] for state transitions but ALWAYS release it before joining the
     * writer, because a join taken under any lock the writer can want is a deadlock. */
    private val lifecycle = Any()
    private val headLock = Any()

    private var driver: AudioTrackDriver? = null
    private var accepted: AudioFormat? = null
    private var render: AudioRenderCallback? = null
    private var blockFrames = 0
    private var blockBuffer = FloatArray(0)
    private var blockAdapter: BlockBuffer? = null

    private var writer: Thread? = null

    /* The interrupted block held across a pause (F-AUD2). Writer-confined: the pause path's
     * join and the resume path's thread start are the only handovers. Cleared by stop's flush
     * and by open, because a flush discards exactly what this holds. */
    private var heldBlockFloats = 0
    private var heldBlockOffset = 0
    private var heldBlockShort = false
    @Volatile private var writerRun = false
    @Volatile private var draining = false
    private var closed = false

    /** Frames handed to the device since the last stop, written only by the writer thread. */
    @Volatile private var submittedFrames = 0L

    /** SOL-A2: set by the writer on device failure, cleared by the recovery arm of start. */
    private var writerFailed = false

    /* Timestamp acceptance state (S1.c.4 step 6), written only by the writer thread. */
    @Volatile private var lastAcceptedTimestampFrames = -1L
    @Volatile private var lastAcceptedTimestampNanos = Long.MIN_VALUE
    @Volatile private var timestampSourceObserved: String = "none"

    /* Unsigned 32-bit playback-head extension, written under [lifecycle] or by the writer. */
    private var headLastRaw = 0L
    private var headWrapBase = 0L

    private val eventFlow = MutableSharedFlow<AudioSinkEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<AudioSinkEvent> get() = eventFlow

    override val deviceBufferFrames: Int
        get() = driver?.bufferSizeInFrames ?: 0

    override val latencyQuality: LatencyQuality
        /* The playback-head fallback is an estimate even on the runs where timestamps are
         * usually present, so the honest declared quality is the weaker of the two (step 6). */
        get() = LatencyQuality.Estimated

    /** Which deadline source the writer last used: "timestamp", "head" or "none". Test seam. */
    internal val observedDeadlineSource: String get() = timestampSourceObserved

    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        synchronized(lifecycle) {
            check(!closed) { "AudioTrackSink is closed" }
            check(driver == null) { "AudioTrackSink is already open" }
            /* Fail before device creation: the sink never converts, so a request it cannot
             * satisfy honestly must be refused here rather than negotiated dishonestly. */
            require(request.sampleRate > 0) { "invalid sample rate ${request.sampleRate}" }
            require(request.channels >= 1) { "a request with no channels cannot be opened" }
            val format = AudioFormat(
                sampleRate = request.sampleRate,
                /* SOL-A6: the counts AudioTrack has masks for pass through unchanged, so a 5.1
                 * or 7.1 source plays every channel instead of a forced downmix; anything else
                 * falls to stereo, which the pipeline's mixer can always produce. */
                channels = when (request.channels) {
                    1, 2, 6, 8 -> request.channels
                    else -> 2
                },
                sampleFormat = SampleFormat.F32,
            )
            val opened = driverFactory.open(format)
            /* Failed open releases the partially created driver and leaves no writer (step 7). */
            if (opened.bufferSizeInFrames <= 0) {
                opened.release()
                throw IllegalStateException(
                    "AudioTrack reported a ${opened.bufferSizeInFrames}-frame buffer; the device open failed",
                )
            }
            driver = opened
            accepted = format
            this.render = render
            /* The callback block is exactly min(deviceBufferFrames, 512) frames, and both the
             * float array and its AudioSinkBuffer adapter are allocated HERE, never in the loop
             * (step 4). */
            blockFrames = minOf(opened.bufferSizeInFrames, 512)
            blockBuffer = FloatArray(512 * format.channels)
            blockAdapter = BlockBuffer(format, blockBuffer)
            heldBlockFloats = 0
            heldBlockOffset = 0
            heldBlockShort = false
            submittedFrames = 0L
            resetTimestampState()
            return format
        }
    }

    override suspend fun start() {
        recoverIfFailed()
        synchronized(lifecycle) {
            val d = driver ?: error("start before open")
            if (writerRun) return
            d.play()
            startWriterLocked()
        }
    }

    /**
     * SOL-A2's recovery arm: after a device failure the old AudioTrack is dead, so the next
     * start releases it and opens a fresh one for the accepted format. The submitted count and
     * timestamp state restart with the new device; the DeviceLost event already told the
     * application WHY. Outside [lifecycle] for the join, like every writer join here.
     */
    private fun recoverIfFailed() {
        val dead = synchronized(lifecycle) {
            if (!writerFailed) return
            driver
        }
        joinWriterOutsideLock()
        synchronized(lifecycle) {
            if (!writerFailed) return
            val format = accepted ?: return
            dead?.release()
            driver = driverFactory.open(format)
            submittedFrames = 0L
            resetTimestampState()
            writerFailed = false
        }
    }

    override suspend fun stop() {
        /* Signal, unblock the blocking write, join, THEN flush: flushing while the writer still
         * writes would interleave discarded and live frames (step 7's ordering). The join runs
         * outside [lifecycle] per the lock discipline above. */
        val d = synchronized(lifecycle) {
            val d = driver ?: return
            writerRun = false
            d.pause()
            d.stop()
            d
        }
        joinWriterOutsideLock()
        synchronized(lifecycle) {
            d.flush()
            submittedFrames = 0L
            resetTimestampState()
        }
        /* The flush discarded exactly what the held block was (F-AUD2); after the join the
         * writer is gone, so this clear races nothing. */
        heldBlockFloats = 0
        heldBlockOffset = 0
        heldBlockShort = false
    }

    override suspend fun drain() {
        val d = synchronized(lifecycle) { driver ?: return }
        synchronized(lifecycle) {
            draining = true
        }
        joinWriterOutsideLock()
        /* Bounded poll until everything submitted is audible: the queue is at most the device
         * buffer plus one block, so the bound is that duration plus scheduling slack. */
        val format = accepted ?: return
        val queuedLimitNanos = framesToNanos(
            (d.bufferSizeInFrames + blockFrames).toLong(),
            format.sampleRate,
        ) + 500_000_000L
        /* The bound counts real sleeps rather than reading [clock]: the injected test clock is
         * deliberately frozen, and a bound on a frozen clock is no bound at all (caught by the
         * host suite hanging here, 2026-08-12). */
        var polls = (queuedLimitNanos / 10_000_000L).coerceAtLeast(1)
        while (extendedHead(d) < submittedFrames && polls-- > 0) {
            Thread.sleep(10)
        }
        synchronized(lifecycle) {
            draining = false
            writerRun = false
            d.stop() /* without flush: this is the end-of-media path */
            /* Mirror stop's accounting reset (audit F-AUD3): everything submitted has been
             * heard, and a later latencyNanos against a fresh head read minutes of pending
             * audio out of the stale counter. */
            submittedFrames = 0L
            resetTimestampState()
        }
    }

    override suspend fun setPaused(paused: Boolean): Boolean {
        if (paused) {
            /* Signal, pause the driver to unblock a blocking write, join WITHOUT flushing:
             * pause discards nothing (step 7). Join outside the lock, as always. */
            synchronized(lifecycle) {
                val d = driver ?: return true
                writerRun = false
                d.pause()
            }
            joinWriterOutsideLock()
        } else {
            recoverIfFailed()
            synchronized(lifecycle) {
                val d = driver ?: return true
                d.play()
                startWriterLocked()
            }
        }
        return true
    }

    override fun latencyNanos(): Long {
        val d = driver ?: return 0
        val format = accepted ?: return 0
        val played = newestPlayedPosition(d)
        val queued = submittedFrames - played
        return if (queued <= 0) 0 else framesToNanos(queued, format.sampleRate)
    }

    override fun close() {
        val d = synchronized(lifecycle) {
            if (closed) return
            closed = true
            val d = driver ?: return
            writerRun = false
            d.pause()
            d.stop()
            d
        }
        joinWriterOutsideLock()
        synchronized(lifecycle) {
            d.flush()
            /* Release only after the join: no release can race a write, and no lifecycle call
             * enters AudioTrack after release (step 7). The fake driver records a violation of
             * either as a hard failure, and the host suite's negative control proves it can. */
            d.release()
            driver = null
        }
    }

    /* ── The writer (step 5) ────────────────────────────────────────────────────────────── */

    private fun startWriterLocked() {
        /* SOL-A2: one writer, ever. A duplicate resume used to start a second thread over the
         * same driver and block buffer. */
        if (writer?.isAlive == true && writerRun) return
        writerRun = true
        val thread = Thread({ writerLoop() }, "kiteplayer-audiotrack-writer")
        writer = thread
        thread.start()
    }

    private fun joinWriterOutsideLock() {
        val t = synchronized(lifecycle) { writer }
        t?.join()
        synchronized(lifecycle) { if (writer === t) writer = null }
    }

    private fun writerLoop() {
        val d = driver ?: return
        d.onWriterThreadStart()
        val format = accepted ?: return
        val callback = render ?: return
        val adapter = blockAdapter ?: return
        val channels = format.channels
        while (writerRun) {
            /* Audit F-AUD2: an interrupted block's unwritten tail was already pulled from the
             * ring, so a resumed writer submits the REMAINDER first instead of dropping up to a
             * block of decoded audio at every pause. The held state is writer-confined: the
             * join in pause and the thread start in resume are its happens-before edges. */
            val resuming = heldBlockFloats > 0
            val short: Boolean
            val startFloats: Int
            val totalFloats: Int
            if (resuming) {
                short = heldBlockShort
                startFloats = heldBlockOffset
                totalFloats = heldBlockFloats
            } else {
                val deadline = deadlineForBlock(d, format)
                val written = callback.onRender(adapter, blockFrames, deadline)
                short = written < blockFrames
                if (short) {
                    /* The tail is the sink's own obligation: nothing above this line zeroes it. */
                    adapter.writeSilence(written.coerceAtLeast(0), blockFrames - written.coerceAtLeast(0))
                }
                startFloats = 0
                totalFloats = blockFrames * channels
            }
            var offsetFloats = startFloats
            var failed = false
            while (offsetFloats < totalFloats) {
                val n = d.write(blockBuffer, offsetFloats, totalFloats - offsetFloats)
                if (n > 0) {
                    offsetFloats += n
                    /* Audit F-AUD1: a short POSITIVE count is also how the platform hands a
                     * write back at an interrupt. Re-entering the blocking write here on a
                     * paused, full track was a writer nothing could join. */
                    if (!writerRun) break
                    continue
                }
                if (!writerRun) break /* pause or stop interrupted the blocking write */
                /* Zero or negative with the writer still live is a device failure, never a
                 * busy loop (step 5). */
                /* SOL-A2: a dead device marks the machine FAILED and drops writerRun, so the
                 * sink is startable again (start recovers) instead of wedged behind a true
                 * writerRun with no writer. State BEFORE the event: the event is what wakes a
                 * listener that immediately calls start, and start must see the failure. */
                synchronized(lifecycle) {
                    writerFailed = true
                    writerRun = false
                }
                eventFlow.tryEmit(
                    AudioSinkEvent.DeviceLost("AudioTrack.write returned $n mid-block"),
                )
                failed = true
                break
            }
            /* SOL-A1: count what the device actually took. A pause or stop that interrupts
             * the blocking write mid-block, and a device failure partway, both leave a partial
             * count; claiming the whole block made latency and the head fallback lie by up to
             * one block. Full blocks land on exactly the old arithmetic; a resumed remainder
             * counts only its own newly written part. */
            submittedFrames += ((offsetFloats - startFloats) / channels).toLong()
            if (offsetFloats < totalFloats && !failed) {
                heldBlockFloats = totalFloats
                heldBlockOffset = offsetFloats
                heldBlockShort = short
            } else {
                heldBlockFloats = 0
                heldBlockOffset = 0
                heldBlockShort = false
            }
            if (failed) break
            if (draining && short) {
                /* The drain contract: keep pulling until the callback's first short return,
                 * silence and submit that final tail, then exit (step 7). */
                break
            }
        }
    }

    /* ── Deadline and clock arithmetic (step 6), package-private for the host clock suite. ─ */

    private fun deadlineForBlock(d: AudioTrackDriver, format: AudioFormat): Long {
        val ts = readTimestamp(d)
        if (ts != null && acceptTimestamp(ts)) {
            timestampSourceObserved = "timestamp"
            return timestampDeadline(
                ts.nanoTime,
                ts.framePosition,
                submittedFrames,
                blockFrames,
                format.sampleRate,
            )
        }
        timestampSourceObserved = "head"
        val queued = submittedFrames - extendedHead(d)
        return clock.nanos() + framesToNanos(queued.coerceAtLeast(0) + blockFrames, format.sampleRate)
    }

    /**
     * SOL-A3: the one place a driver timestamp is read. Legacy HALs feed AudioTimestamp from a
     * 32-bit counter, so the position wraps at about 24.85 hours at 48 kHz exactly like the
     * playback head; the same extension law covers it. A position already past 32 bits is a
     * genuine 64-bit counter and passes through untouched. The driver's holder is scratch by
     * contract, so the extension writes in place and nothing allocates per poll.
     */
    private fun readTimestamp(d: AudioTrackDriver): DriverTimestamp? {
        val ts = d.timestamp() ?: return null
        /* Under headLock like the head's own wrap state (audit F-AUD4): the writer reads this
         * per block and the public latencyNanos may read it from any thread. */
        synchronized(headLock) {
            ts.framePosition = extendTimestampFrames(ts.framePosition, tsState)
        }
        return ts
    }

    private val tsState = WrapState()

    private fun acceptTimestamp(ts: DriverTimestamp): Boolean {
        /* Reject a timestamp ahead of submitted data or behind the prior accepted one: either
         * shape would anchor the master clock to a fiction (step 6). */
        if (ts.framePosition < 0 || ts.framePosition > submittedFrames) return false
        if (ts.framePosition < lastAcceptedTimestampFrames) return false
        if (ts.nanoTime < lastAcceptedTimestampNanos) return false
        lastAcceptedTimestampFrames = ts.framePosition
        lastAcceptedTimestampNanos = ts.nanoTime
        return true
    }

    private fun extendedHead(d: AudioTrackDriver): Long {
        val raw = d.playbackHeadPosition().toLong() and 0xFFFF_FFFFL
        synchronized(headLock) {
            if (raw < headLastRaw && headLastRaw - raw > 0x8000_0000L) {
                headWrapBase += 1L shl 32
            }
            headLastRaw = raw
            return headWrapBase + raw
        }
    }

    private fun newestPlayedPosition(d: AudioTrackDriver): Long {
        val ts = readTimestamp(d)
        return if (ts != null && ts.framePosition in 0..submittedFrames) {
            ts.framePosition
        } else {
            extendedHead(d)
        }
    }

    private fun resetTimestampState() {
        lastAcceptedTimestampFrames = -1L
        lastAcceptedTimestampNanos = Long.MIN_VALUE
        tsState.lastRaw = 0L
        tsState.wrapBase = 0L
        synchronized(headLock) {
            headLastRaw = 0L
            headWrapBase = 0L
        }
        /* timestampSourceObserved deliberately survives a stop: it is a diagnostic of the last
         * source actually used, and the device test reads it after stopping. */
    }

    internal companion object {

        /** `deadline = ts.nanoTime + duration(submitted + requested - ts.framePosition)`. */
        internal fun timestampDeadline(
            timestampNanos: Long,
            timestampFrames: Long,
            submittedFrames: Long,
            requestedFrames: Int,
            sampleRate: Int,
        ): Long = timestampNanos +
            framesToNanos(submittedFrames + requestedFrames - timestampFrames, sampleRate)

        internal fun framesToNanos(frames: Long, sampleRate: Int): Long =
            if (sampleRate <= 0) 0 else frames * 1_000_000_000L / sampleRate

        /** SOL-A3's wrap law, pure for the host suite: same shape as the head extension. */
        internal fun extendTimestampFrames(raw: Long, state: WrapState): Long {
            if (raw > 0xFFFF_FFFFL || raw < 0) return raw
            if (raw < state.lastRaw && state.lastRaw - raw > 0x8000_0000L) {
                state.wrapBase += 1L shl 32
            }
            state.lastRaw = raw
            return state.wrapBase + raw
        }
    }

    /** Mutable wrap-extension state for [extendTimestampFrames]; writer-thread confined. */
    internal class WrapState {
        var lastRaw: Long = 0
        var wrapBase: Long = 0
    }

    /**
     * The preallocated [AudioSinkBuffer] over the block array. `writePlane` is rejected because
     * AudioTrack is interleaved; nothing in the engine calls it, and a caller that does must
     * hear about it loudly rather than play garbage.
     */
    private class BlockBuffer(
        override val format: AudioFormat,
        private val data: FloatArray,
    ) : AudioSinkBuffer {

        private val channels = format.channels

        override fun writeInterleaved(
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ) {
            source.copyInto(
                destination = data,
                destinationOffset = destinationFrameOffset * channels,
                startIndex = sourceOffset,
                endIndex = sourceOffset + frames * channels,
            )
        }

        override fun writePlane(
            channel: Int,
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ): Unit = throw UnsupportedOperationException(
            "AudioTrack is interleaved; writePlane is rejected by contract (S1.c.4 step 4)",
        )

        override fun writeSilence(frameOffset: Int, frames: Int) {
            data.fill(0f, frameOffset * channels, (frameOffset + frames) * channels)
        }
    }
}

/** Creates [AudioTrackSink]s for the engine. One sink per playback session. */
public class AudioTrackSinkFactory() : AudioSinkFactory {
    override suspend fun create(): AudioSink = AudioTrackSink()
    override val name: String get() = "AudioTrack"
}
