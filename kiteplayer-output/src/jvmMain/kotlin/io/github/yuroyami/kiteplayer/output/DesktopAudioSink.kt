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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The desktop JVM audio output: one `javax.sound.sampled.SourceDataLine` behind the engine's pull
 * contract (register item W-04, decision W-D2).
 *
 * `SourceDataLine` is a PUSH device while the engine exposes a PULL callback that owns the audio
 * clock anchor. The adaptation is the one wrapper the `AudioSink` KDoc names for push platforms:
 * a dedicated writer thread turns "the line has room" into a pull. Each loop iteration computes
 * the deadline of the LAST frame of the block, invokes the engine's [AudioRenderCallback] into a
 * preallocated buffer, silences any tail a short return leaves (nothing above the sink zeroes it,
 * per the callback contract), packs the floats to the wire encoding, and loops `write` until the
 * block is fully submitted or a lifecycle signal lands. This is `AudioTrackSink`'s proven writer
 * machine, because the audit rows it closed apply here word for word.
 *
 * The sink accepts mono or stereo and never converts: mixing and resampling stay in the engine.
 * The one thing it does do is the packing the SPI explicitly allows, F32 to 16-bit signed
 * little-endian, because the JDK's own mixers refuse float lines (see [WIRE_BYTES_PER_SAMPLE]).
 *
 * The internal constructor takes the driver factory and the clock so the host suite drives every
 * lifecycle and arithmetic arm with a fake; the public constructor hard-wires
 * [DesktopMonotonicClock], so production cannot pair the deadlines with another time base.
 */
public class DesktopAudioSink internal constructor(
    private val driverFactory: SourceDataLineDriverFactory,
    private val clock: MonotonicClock,
) : AudioSink {

    public constructor() : this(
        SourceDataLineDriverFactory { accepted -> PlatformSourceDataLineDriver(accepted) },
        DesktopMonotonicClock,
    )

    /* Lock discipline, inherited from the Android sink's own shipped deadlock: the writer takes
     * [positionLock] for the played-position law and NOTHING else; lifecycle methods take
     * [lifecycle] for state transitions but ALWAYS release it before joining the writer, because
     * a join under any lock the writer can want is a deadlock. */
    private val lifecycle = Any()
    private val positionLock = Any()

    private var driver: SourceDataLineDriver? = null
    private var accepted: AudioFormat? = null
    private var render: AudioRenderCallback? = null
    private var blockFrames = 0
    private var frameBytes = 0
    private var blockBuffer = FloatArray(0)
    private var wireBuffer = ByteArray(0)
    private var blockAdapter: BlockBuffer? = null

    private var writer: Thread? = null

    /* The interrupted block held across a pause. Writer-confined: the pause path's
     * join and the resume path's thread start are the only handovers. Cleared by stop's flush and
     * by open, because a flush discards exactly what this holds. */
    private var heldBlockBytes = 0
    private var heldBlockOffset = 0
    private var heldBlockShort = false
    @Volatile private var writerRun = false
    @Volatile private var draining = false
    private var closed = false

    /** Frames handed to the line since the last stop, written only by the writer thread. */
    @Volatile private var submittedFrames = 0L

    /** SOL-A2: set by the writer on device failure, cleared by the recovery arm of start. */
    private var writerFailed = false

    /**
     * SOL-A3, the desktop half. `getLongFramePosition()` is ALREADY 64 bit: at 48 kHz it needs
     * about six million years to overflow, so the 32-bit wrap extension the Android sink carries
     * has nothing to extend here and copying it would be strictly harmful (it would fold a real
     * position past 2^32 back to a small one and invent a queue that is not there).
     *
     * What DOES need a law is the origin. The JDK counts the position from `open()`, and a run
     * that stops and starts again resets [submittedFrames] while the line's counter keeps going,
     * so the two must be re-zeroed together. This is that one base, held for the line's life and
     * re-taken wherever the submitted count is. Guarded by [positionLock] because the writer
     * reads it per block and public [latencyNanos] may read it from any thread.
     */
    private var positionBase = 0L

    private val eventFlow = MutableSharedFlow<AudioSinkEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<AudioSinkEvent> get() = eventFlow

    override val deviceBufferFrames: Int
        get() {
            val d = driver ?: return 0
            return if (frameBytes <= 0) 0 else d.bufferSizeBytes / frameBytes
        }

    /**
     * Honestly [LatencyQuality.Estimated], and it can never be better.
     *
     * `javax.sound.sampled` has no presentation-timestamp API at all: there is no pairing of a
     * frame with the instant it was heard, the way `AudioTimestamp` gives on Android and
     * `AudioTimeStamp` gives on CoreAudio. All this sink has is a frame counter, and that counter
     * measures what the JAVA MIXER has consumed, not what the DAC has played, so everything below
     * the mixer (CoreAudio's buffers, an ALSA period chain, the WASAPI endpoint buffer) is
     * invisible to it and is missing from every figure here. It is not [LatencyQuality.Unreliable]
     * either: the counter does advance at the true playback rate, so the figure tracks reality and
     * is usable once filtered. Estimated is exactly what it is.
     */
    override val latencyQuality: LatencyQuality get() = LatencyQuality.Estimated

    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        synchronized(lifecycle) {
            check(!closed) { "DesktopAudioSink is closed" }
            check(driver == null) { "DesktopAudioSink is already open" }
            /* Fail before the line exists: the sink never converts, so a request it cannot
             * satisfy honestly must be refused here rather than negotiated dishonestly. */
            require(request.sampleRate > 0) { "invalid sample rate ${request.sampleRate}" }
            require(request.channels >= 1) { "a request with no channels cannot be opened" }
            val format = AudioFormat(
                sampleRate = request.sampleRate,
                /* Measured on this machine: the JDK's mixers list mono and stereo source lines
                 * and nothing wider, so anything else falls to stereo, which the engine's mixer
                 * can always produce. Multichannel desktop output is SOL-A6's business. */
                channels = if (request.channels == 1) 1 else 2,
                /* F32 is what the ENGINE writes through AudioSinkBuffer. The 16-bit wire below
                 * is this sink's own packing and is not the engine's concern. */
                sampleFormat = SampleFormat.F32,
            )
            val opened = driverFactory.create(format)
            /* Two failure shapes, one law: whatever went wrong, nothing half-built survives.
             * `SourceDataLine.open` throws when another application owns the device. */
            try {
                opened.open()
            } catch (failure: Throwable) {
                opened.close()
                throw failure
            }
            /* A refused open closes the partially created line and leaves no writer. */
            if (opened.bufferSizeBytes <= 0) {
                opened.close()
                throw IllegalStateException(
                    "SourceDataLine reported a ${opened.bufferSizeBytes}-byte buffer; the open failed",
                )
            }
            driver = opened
            accepted = format
            this.render = render
            frameBytes = format.channels * WIRE_BYTES_PER_SAMPLE
            /* The callback block is exactly min(deviceBufferFrames, 512) frames, and the float
             * array, the wire array and the AudioSinkBuffer adapter are allocated HERE, never in
             * the loop. */
            blockFrames = minOf(opened.bufferSizeBytes / frameBytes, 512)
            blockBuffer = FloatArray(512 * format.channels)
            wireBuffer = ByteArray(512 * frameBytes)
            blockAdapter = BlockBuffer(format, blockBuffer)
            clearHeldBlock()
            submittedFrames = 0L
            rebasePosition(opened)
            return format
        }
    }

    override suspend fun start() {
        recoverIfFailed()
        synchronized(lifecycle) {
            val d = driver ?: error("start before open")
            if (writerRun) return
            d.start()
            startWriterLocked()
        }
    }

    /**
     * SOL-A2's recovery arm: after a write failure the line is dead, so the next start closes it
     * and opens a fresh one for the accepted format. The submitted count and the position base
     * restart with the new line; the DeviceLost event already told the application WHY. Outside
     * [lifecycle] for the join, like every writer join here.
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
            dead?.close()
            val fresh = driverFactory.create(format)
            fresh.open()
            driver = fresh
            clearHeldBlock()
            submittedFrames = 0L
            rebasePosition(fresh)
            writerFailed = false
        }
    }

    override suspend fun stop() {
        /* Signal, unblock the blocking write, join, THEN flush: flushing while the writer still
         * writes would interleave discarded and live frames. The join runs outside [lifecycle]
         * per the lock discipline above. */
        val d = synchronized(lifecycle) {
            val d = driver ?: return
            writerRun = false
            d.stop()
            d
        }
        joinWriterOutsideLock()
        synchronized(lifecycle) {
            d.flush()
            submittedFrames = 0L
            rebasePosition(d)
        }
        /* The flush discarded exactly what the held block was; after the join the writer
         * is gone, so this clear races nothing. */
        clearHeldBlock()
    }

    override suspend fun drain() {
        val d = synchronized(lifecycle) { driver ?: return }
        draining = true
        /* The writer keeps pulling until the callback's first short return, submits that final
         * tail and exits; then the LINE's own drain plays the queue out, which is the whole
         * reason javax.sound.sampled has one. */
        joinWriterOutsideLock()
        d.drain()
        synchronized(lifecycle) {
            draining = false
            writerRun = false
            d.stop() /* without flush: this is the end-of-media path */
            /* Audit F-AUD3: everything submitted has been heard, so the counters reset with it.
             * A stale count read minutes of pending audio out of a later latencyNanos. */
            submittedFrames = 0L
            rebasePosition(d)
        }
        clearHeldBlock()
    }

    override suspend fun setPaused(paused: Boolean): Boolean {
        if (paused) {
            /* Signal, stop the line to unblock a blocking write, join WITHOUT flushing: a pause
             * discards nothing. Join outside the lock, as always. */
            synchronized(lifecycle) {
                val d = driver ?: return true
                writerRun = false
                d.stop()
            }
            joinWriterOutsideLock()
        } else {
            recoverIfFailed()
            synchronized(lifecycle) {
                val d = driver ?: return true
                d.start()
                startWriterLocked()
            }
        }
        return true
    }

    override fun latencyNanos(): Long {
        val d = driver ?: return 0
        val format = accepted ?: return 0
        val queued = submittedFrames - playedFrames(d)
        return if (queued <= 0) 0 else framesToNanos(queued, format.sampleRate)
    }

    override fun close() {
        val d = synchronized(lifecycle) {
            if (closed) return
            closed = true
            val d = driver ?: return
            writerRun = false
            d.stop()
            d
        }
        joinWriterOutsideLock()
        synchronized(lifecycle) {
            d.flush()
            /* Close only after the join: no close can race a write, and no lifecycle call enters
             * the line afterwards. The fake driver records a violation of either as a hard
             * failure, and the host suite's negative control proves it can. */
            d.close()
            driver = null
        }
    }

    /* ── The writer ─────────────────────────────────────────────────────────────────────────── */

    private fun startWriterLocked() {
        /* SOL-A2: one writer, ever. A duplicate resume used to start a second thread over the
         * same driver and the same block buffer. */
        if (writer?.isAlive == true && writerRun) return
        writerRun = true
        val thread = Thread({ writerLoop() }, "kiteplayer-sourcedataline-writer")
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
        var wasDry = false
        while (writerRun) {
            /* Audit F-AUD2: an interrupted block's unwritten tail was already pulled from the
             * ring, so a resumed writer submits the REMAINDER first instead of dropping up to a
             * block of decoded audio at every pause. The held state is writer-confined: the join
             * in pause and the thread start in resume are its happens-before edges. */
            val resuming = heldBlockBytes > 0
            val short: Boolean
            val startBytes: Int
            val totalBytes: Int
            if (resuming) {
                short = heldBlockShort
                startBytes = heldBlockOffset
                totalBytes = heldBlockBytes
            } else {
                val written = callback.onRender(adapter, blockFrames, deadlineForBlock(d, format))
                short = written < blockFrames
                if (short) {
                    /* The tail is the sink's own obligation: nothing above this line zeroes it. */
                    adapter.writeSilence(written.coerceAtLeast(0), blockFrames - written.coerceAtLeast(0))
                }
                packBlock(blockFrames * channels)
                startBytes = 0
                totalBytes = blockFrames * frameBytes
            }
            var offsetBytes = startBytes
            var failed = false
            while (offsetBytes < totalBytes) {
                val n = d.write(wireBuffer, offsetBytes, totalBytes - offsetBytes)
                if (n > 0) {
                    offsetBytes += n
                    /* Audit F-AUD1: a short POSITIVE count is also how a line hands a write back
                     * at an interrupt. Re-entering the blocking write here on a stopped, full
                     * line was a writer nothing could join. */
                    if (!writerRun) break
                    continue
                }
                if (!writerRun) break /* stop, pause or close interrupted the blocking write */
                /* Zero or negative with the writer still live is a device failure, never a busy
                 * loop. SOL-A2: a dead line marks the machine FAILED and drops writerRun, so the
                 * sink is startable again (start recovers) instead of wedged behind a true
                 * writerRun with no writer. State BEFORE the event: the event is what wakes a
                 * listener that immediately calls start, and start must see the failure. */
                synchronized(lifecycle) {
                    writerFailed = true
                    writerRun = false
                }
                eventFlow.tryEmit(
                    AudioSinkEvent.DeviceLost("SourceDataLine.write returned $n mid-block"),
                )
                failed = true
                break
            }
            /* SOL-A1: count what the line actually took. A stop or pause that interrupts the
             * blocking write mid-block, and a device failure partway, both leave a partial count;
             * claiming the whole block made latency and every deadline lie by up to one block.
             * Full blocks land on exactly the old arithmetic; a resumed remainder counts only its
             * own newly written part. */
            submittedFrames += ((offsetBytes - startBytes) / frameBytes).toLong()
            if (offsetBytes < totalBytes && !failed) {
                heldBlockBytes = totalBytes
                heldBlockOffset = offsetBytes
                heldBlockShort = short
            } else {
                clearHeldBlock()
            }
            if (failed) break
            /* The honest underrun: the engine had no samples and we fed the line silence. The
             * line's own emptiness is NOT the signal, because a blocking writer keeps the line
             * full by construction; `available()` only reports how close the line came, which is
             * what an application reading this event wants to know. Edge triggered, so one dry
             * spell is one event instead of ninety a second. */
            val dry = short && !draining
            if (dry && !wasDry) {
                eventFlow.tryEmit(
                    AudioSinkEvent.Underrun(
                        "render ran dry; ${d.available()} of ${d.bufferSizeBytes} bytes free in the line",
                    ),
                )
            }
            wasDry = dry
            if (draining && short) {
                /* The drain contract: keep pulling until the callback's first short return,
                 * silence and submit that final tail, then exit. */
                break
            }
        }
    }

    private fun clearHeldBlock() {
        heldBlockBytes = 0
        heldBlockOffset = 0
        heldBlockShort = false
    }

    /** F32 to 16-bit signed little-endian, the one conversion the `AudioSink` contract allows. */
    private fun packBlock(samples: Int) {
        var b = 0
        for (i in 0 until samples) {
            val v = (blockBuffer[i] * 32767f).toInt().coerceIn(-32768, 32767)
            wireBuffer[b++] = (v and 0xFF).toByte()
            wireBuffer[b++] = ((v shr 8) and 0xFF).toByte()
        }
    }

    /* ── Deadline and position arithmetic ───────────────────────────────────────────────────── */

    /**
     * When the LAST frame of the block we are about to render becomes audible, on
     * `System.nanoTime`.
     *
     * The derivation, and it is the only one this API allows. `getLongFramePosition()` says how
     * many frames the line has PLAYED; [submittedFrames] says how many we have HANDED OVER. The
     * difference is what is still queued ahead of this block, so this block's own last frame is
     * heard after that queue plus this block has played:
     *
     *     deadline = now + duration(queued + blockFrames), queued = submitted - played
     *
     * There is no timestamp to prefer over it, unlike Android and CoreAudio, which is why this
     * sink declares [latencyQuality] Estimated and never claims better.
     */
    private fun deadlineForBlock(d: SourceDataLineDriver, format: AudioFormat): Long {
        val queued = (submittedFrames - playedFrames(d)).coerceAtLeast(0)
        return clock.nanos() + framesToNanos(queued + blockFrames, format.sampleRate)
    }

    /** Frames played in THIS run: the line's own 64-bit counter minus the run's base. */
    private fun playedFrames(d: SourceDataLineDriver): Long {
        val raw = d.longFramePosition()
        return synchronized(positionLock) { (raw - positionBase).coerceAtLeast(0) }
    }

    private fun rebasePosition(d: SourceDataLineDriver) {
        val raw = d.longFramePosition()
        synchronized(positionLock) { positionBase = raw }
    }

    internal companion object {
        internal fun framesToNanos(frames: Long, sampleRate: Int): Long =
            if (sampleRate <= 0) 0 else frames * 1_000_000_000L / sampleRate
    }

    /**
     * The preallocated [AudioSinkBuffer] over the block array. `writePlane` is rejected because
     * the wire is interleaved; nothing in the engine calls it, and a caller that does must hear
     * about it loudly rather than play garbage.
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
            "SourceDataLine is interleaved; writePlane is rejected by contract",
        )

        override fun writeSilence(frameOffset: Int, frames: Int) {
            data.fill(0f, frameOffset * channels, (frameOffset + frames) * channels)
        }
    }
}

/** Creates [DesktopAudioSink]s for the engine. One sink per playback session. */
public class DesktopAudioSinkFactory : AudioSinkFactory {
    override suspend fun create(): AudioSink = DesktopAudioSink()
    override val name: String get() = "SourceDataLine"
}
