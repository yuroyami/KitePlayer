package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as WireFormat

/**
 * The ONE internal boundary holding every `javax.sound.sampled` call the audio path makes.
 * [DesktopAudioSink] is written entirely against this seam, which is what lets the host suite
 * drive every lifecycle and arithmetic arm with a fake and no sound card, exactly the way
 * `AudioTrackDriver` does for Android. Production is [PlatformSourceDataLineDriver]; nothing else
 * in this source set may name `SourceDataLine`.
 */
internal interface SourceDataLineDriver {

    /** `SourceDataLine.open(format, bufferSize)`. Throwing is the only failure shape open handles. */
    fun open()

    /** `SourceDataLine.getBufferSize()`, in BYTES. Only meaningful after [open]. */
    val bufferSizeBytes: Int

    /**
     * Called once by the writer thread as its first act. Production raises the thread priority
     * here; the fake only records it, which is also how the host suite proves one writer ever.
     */
    fun onWriterThreadStart()

    fun start()

    /** Stops playback WITHOUT discarding, and unblocks a blocking write; the writer relies on that. */
    fun stop()

    /** Blocks until everything queued has been played. The end-of-media path only. */
    fun drain()

    /** Discards everything written but not yet played, and unblocks a blocking write. */
    fun flush()

    /**
     * Blocking byte write. Returns the number of BYTES written, which the line makes smaller than
     * requested when [stop], [flush] or [close] interrupts it. Zero or negative is a device
     * failure and never a reason to spin.
     */
    fun write(source: ByteArray, offsetBytes: Int, sizeBytes: Int): Int

    /**
     * `SourceDataLine.getLongFramePosition()`: frames the line has PLAYED since it was opened.
     * Already 64 bit, so no wrap extension exists here; see [DesktopAudioSink] for why.
     */
    fun longFramePosition(): Long

    /** `SourceDataLine.available()`: bytes that can be written right now without blocking. */
    fun available(): Int

    /** Releases the device. After this every other call is a caller bug the fake records. */
    fun close()
}

internal fun interface SourceDataLineDriverFactory {
    /** Builds a driver for [accepted]. The sink calls [SourceDataLineDriver.open] itself. */
    fun create(accepted: AudioFormat): SourceDataLineDriver
}

/**
 * The wire encoding the desktop sink speaks, and the reason it is not float.
 *
 * MEASURED on this machine (macOS 15, JDK 21, 2026-08-17) with `AudioSystem.isLineSupported`:
 * `PCM_FLOAT` is refused, 32-bit `PCM_SIGNED` is refused, and every mixer lists 16-bit and 24-bit
 * signed little-endian. 16 bit is the one shape present on every mixer of all three desktop
 * hosts, so the sink packs the engine's floats into it. The `AudioSink` KDoc permits exactly this
 * ("trivial bit packing") and nothing else: no resampling, no remixing.
 */
internal const val WIRE_BYTES_PER_SAMPLE: Int = 2

/**
 * The production driver: one `SourceDataLine` on the default mixer, 16-bit signed little-endian,
 * with a buffer of [BUFFER_FRAMES] frames.
 *
 * `SourceDataLine` has no presentation-timestamp API at all, which is why [DesktopMonotonicClock]
 * is the sink's only "now" and why the sink declares `LatencyQuality.Estimated`.
 */
internal class PlatformSourceDataLineDriver(accepted: AudioFormat) : SourceDataLineDriver {

    private val wire = WireFormat(
        WireFormat.Encoding.PCM_SIGNED,
        accepted.sampleRate.toFloat(),
        WIRE_BYTES_PER_SAMPLE * 8,
        accepted.channels,
        accepted.channels * WIRE_BYTES_PER_SAMPLE,
        accepted.sampleRate.toFloat(),
        false,
    )

    private val line: SourceDataLine =
        AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, wire)) as SourceDataLine

    override fun open() {
        line.open(wire, BUFFER_FRAMES * wire.frameSize)
    }

    override val bufferSizeBytes: Int get() = line.bufferSize

    /* The JVM has no audio thread class, so priority is all there is. Kept on the seam anyway:
     * it is the host suite's marker that exactly one writer thread ever started. */
    override fun onWriterThreadStart() {
        Thread.currentThread().priority = Thread.MAX_PRIORITY
    }

    override fun start(): Unit = line.start()
    override fun stop(): Unit = line.stop()
    override fun drain(): Unit = line.drain()
    override fun flush(): Unit = line.flush()
    override fun close(): Unit = line.close()

    override fun write(source: ByteArray, offsetBytes: Int, sizeBytes: Int): Int =
        line.write(source, offsetBytes, sizeBytes)

    override fun longFramePosition(): Long = line.longFramePosition

    override fun available(): Int = line.available()

    private companion object {
        /* About 42 ms at 48 kHz: small enough that a seek is not audibly late, large enough that
         * an ordinary desktop scheduling hiccup does not underrun. */
        private const val BUFFER_FRAMES = 2048
    }
}
