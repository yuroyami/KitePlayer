package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.AudioToolbox.AURenderCallbackStruct
import platform.AudioToolbox.AudioComponentDescription
import platform.AudioToolbox.AudioComponentFindNext
import platform.AudioToolbox.AudioComponentInstance
import platform.AudioToolbox.AudioComponentInstanceDispose
import platform.AudioToolbox.AudioComponentInstanceNew
import platform.AudioToolbox.AudioComponentInstanceVar
import platform.AudioToolbox.AudioOutputUnitStart
import platform.AudioToolbox.AudioOutputUnitStop
import platform.AudioToolbox.AudioUnitInitialize
import platform.AudioToolbox.AudioUnitSetProperty
import platform.AudioToolbox.AudioUnitUninitialize
import platform.AudioToolbox.kAudioUnitManufacturer_Apple
import platform.AudioToolbox.kAudioUnitProperty_SetRenderCallback
import platform.AudioToolbox.kAudioUnitProperty_StreamFormat
import platform.AudioToolbox.kAudioUnitScope_Input
import platform.AudioToolbox.kAudioUnitSubType_DefaultOutput
import platform.AudioToolbox.kAudioUnitType_Output
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioTimeStamp
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioTimeStampHostTimeValid
import platform.darwin.OSStatus
import kotlin.math.min

/**
 * Audio output through CoreAudio.
 *
 * The device pulls. Its render callback runs on a real-time thread CoreAudio owns, and that thread
 * must never be made to wait: it cannot allocate, cannot take a contended lock and cannot suspend.
 * So the callback here does two things only, copy samples out of the engine's ring and record when
 * they will be heard.
 *
 * ### The timestamp that makes synchronisation work
 *
 * CoreAudio hands the callback a timestamp whose host time is when the buffer being filled reaches
 * the device. Converted through [AppleHostClock] and offset by the buffer's own length, that is the
 * instant its last frame becomes audible, which is exactly what the audio clock anchors to. The
 * timestamp says which of its fields mean anything, so the host time is used only when the device
 * flags it valid; otherwise the anchor is estimated from the clock, and the estimate is counted.
 *
 * Nothing in this file estimates a device latency, because nothing needs to. ffplay assumes every
 * device holds exactly two buffer periods, and that assumption is the largest single source of fixed
 * A/V offset in it.
 *
 * ### Nothing the callback uses is created inside the callback
 *
 * One buffer wrapper is built in [open] and handed to every callback for the life of the sink. Per
 * callback it takes two plain field writes, the device's pointer and the frame count, so the
 * real-time thread never asks an allocator for anything. What is left on that path is the views
 * cinterop makes when Kotlin reads a C struct; removing those means writing the callback body in C,
 * which arrives with the shared native ABI (see the roadmap in KPKMP.md section 11).
 *
 * The sink is also the last line for silence. When there is no render callback, or when the callback
 * fills less than the device asked for, the remainder is zeroed here. The engine's ring writes its
 * own silence too, and that duplication is deliberate: an unwritten device buffer plays whatever was
 * left in it.
 *
 * ### [open] is transactional
 *
 * Once the audio unit instance exists there are several ways to fail before the unit is initialised.
 * Each of them hands back everything already created, the instance and the pinned self reference
 * included, before the exception leaves. A failed open therefore leaves a sink that owns nothing and
 * can be opened again.
 */
@OptIn(ExperimentalForeignApi::class)
public class CoreAudioSink(
    private val clock: MonotonicClock = AppleHostClock,
) : AudioSink {

    init {
        require(clock === AppleHostClock) {
            "CoreAudioSink must be given AppleHostClock, and was given $clock. The instant this sink " +
                "publishes is a CoreAudio host time converted by that object, so an engine measuring " +
                "time from any other base would sit at a constant offset from the device that no " +
                "correction could find, because both sides would believe they were right. A clock on " +
                "another base needs a sink that translates between the two bases, which is why the " +
                "parameter exists and why it is checked instead of ignored."
        }
    }

    private var unit: AudioComponentInstance? = null
    private var selfRef: StableRef<CoreAudioSink>? = null
    private var negotiated: AudioFormat? = null
    private var running = false

    /** Read by the real-time callback, so it is set once during open and never mutated after. */
    private var render: AudioRenderCallback? = null

    /** The one wrapper the callback reuses. Built in [open], released in [close]. */
    private var deviceBuffer: DeviceBuffer? = null

    private val eventFlow = MutableSharedFlow<AudioSinkEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<AudioSinkEvent> = eventFlow.asSharedFlow()

    /** When the last frame of the most recently filled buffer becomes audible. */
    private val lastDeadlineNanos = atomic(0L)
    private val everRendered = atomic(false)

    /** Counted in the callback, because the real-time thread may not log. See [estimatedAnchors]. */
    private val estimatedAnchorCount = atomic(0L)

    override val deviceBufferFrames: Int get() = DEFAULT_DEVICE_BUFFER_FRAMES

    /**
     * `Estimated`, not `Exact`, and the distinction is deliberate.
     *
     * The host time CoreAudio reports covers the audio unit's own buffer accurately. The full signal
     * path can add more: the device's safety offset, and on a Bluetooth or AirPlay route a large and
     * variable transport delay. Summing those means reading several device properties and following
     * route changes. Until this sink does that, `Estimated` is the honest answer, and the engine
     * widens its tolerances rather than trusting a figure that may be short by tens of milliseconds.
     */
    override val latencyQuality: LatencyQuality = LatencyQuality.Estimated

    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        check(unit == null) { "this sink is already open" }

        // CoreAudio takes interleaved 32-bit float at any common rate, so the engine's own internal
        // format passes through unchanged and neither side converts anything.
        val channels = request.channels.coerceIn(1, 2)
        val format = AudioFormat(
            sampleRate = request.sampleRate,
            channels = channels,
            sampleFormat = SampleFormat.F32,
            channelLayout = ChannelLayout.forChannelCount(channels),
        )

        // Either this block returns an instance this sink now owns, or it throws having created
        // nothing at all. Everything after it needs the cleanup path.
        val instance = memScoped {
            val description = alloc<AudioComponentDescription>().apply {
                componentType = kAudioUnitType_Output
                componentSubType = kAudioUnitSubType_DefaultOutput
                componentManufacturer = kAudioUnitManufacturer_Apple
                componentFlags = 0u
                componentFlagsMask = 0u
            }
            val component = AudioComponentFindNext(null, description.ptr)
                ?: error("no default audio output component is available")

            val holder = alloc<AudioComponentInstanceVar>()
            AudioComponentInstanceNew(component, holder.ptr).requireOk("AudioComponentInstanceNew")
            holder.value ?: error("the audio unit instance came back null")
        }

        var ref: StableRef<CoreAudioSink>? = null
        try {
            // The sink's own state and the pinned reference come first, before anything is asked of
            // the device. Everything the callback reads is then in place well before the callback can
            // be installed, and the one failure a caller can force from outside, a format the device
            // refuses, runs the whole cleanup path rather than half of it.
            this.render = render
            negotiated = format
            deviceBuffer = DeviceBuffer(format)

            ref = StableRef.create(this)
            selfRef = ref

            memScoped {
                val bytesPerFrame = (format.channels * 4).toUInt()
                val asbd = alloc<AudioStreamBasicDescription>().apply {
                    mSampleRate = format.sampleRate.toDouble()
                    mFormatID = kAudioFormatLinearPCM
                    mFormatFlags = kAudioFormatFlagIsFloat or kAudioFormatFlagIsPacked
                    mBitsPerChannel = 32u
                    mChannelsPerFrame = format.channels.toUInt()
                    mFramesPerPacket = 1u
                    mBytesPerFrame = bytesPerFrame
                    mBytesPerPacket = bytesPerFrame
                }
                AudioUnitSetProperty(
                    instance,
                    kAudioUnitProperty_StreamFormat,
                    kAudioUnitScope_Input,
                    OUTPUT_BUS,
                    asbd.ptr,
                    sizeOf<AudioStreamBasicDescription>().toUInt(),
                ).requireOk("set stream format")
            }

            memScoped {
                val callback = alloc<AURenderCallbackStruct>().apply {
                    inputProc = staticCFunction { refCon, _, timeStamp, _, frames, data ->
                        val sink = refCon?.asStableRef<CoreAudioSink>()?.get() ?: return@staticCFunction OK
                        val stamp = timeStamp?.pointed ?: return@staticCFunction OK
                        val buffers = data?.pointed ?: return@staticCFunction OK
                        sink.renderFromDevice(stamp, frames.toInt(), buffers)
                    }
                    inputProcRefCon = ref.asCPointer()
                }
                AudioUnitSetProperty(
                    instance,
                    kAudioUnitProperty_SetRenderCallback,
                    kAudioUnitScope_Input,
                    OUTPUT_BUS,
                    callback.ptr,
                    sizeOf<AURenderCallbackStruct>().toUInt(),
                ).requireOk("set render callback")
            }

            // The last step that can fail. Nothing below it throws, so a failure here always means
            // the unit was never initialised and disposing it is the whole of the cleanup.
            AudioUnitInitialize(instance).requireOk("AudioUnitInitialize")

            unit = instance
            return format
        } catch (failure: Throwable) {
            AudioComponentInstanceDispose(instance)
            ref?.dispose()
            selfRef = null
            deviceBuffer = null
            negotiated = null
            this.render = null
            throw failure
        }
    }

    override suspend fun start() {
        val instance = unit ?: error("start was called before open")
        if (running) return
        AudioOutputUnitStart(instance).requireOk("AudioOutputUnitStart")
        running = true
    }

    override suspend fun stop() {
        val instance = unit ?: return
        if (running) {
            AudioOutputUnitStop(instance).requireOk("AudioOutputUnitStop")
            running = false
        }
        everRendered.value = false
        lastDeadlineNanos.value = 0
    }

    /**
     * CoreAudio has no drain of its own. Once the callback stops supplying samples the device plays
     * what it already holds and then silence, so waiting out the buffer already handed over is
     * exactly the drain the engine needs before declaring the end of the media.
     */
    override suspend fun drain() {
        val remaining = lastDeadlineNanos.value - clock.nanos()
        if (remaining > 0) delay(remaining / 1_000_000 + 1)
        stop()
    }

    /** Stopping the unit keeps the device open, so nothing buffered is lost and resuming is quick. */
    override suspend fun setPaused(paused: Boolean): Boolean {
        val instance = unit ?: return false
        if (paused && running) {
            AudioOutputUnitStop(instance).requireOk("pause")
            running = false
        } else if (!paused && !running) {
            AudioOutputUnitStart(instance).requireOk("resume")
            running = true
        }
        return true
    }

    override fun latencyNanos(): Long {
        if (!everRendered.value) return 0
        return (lastDeadlineNanos.value - clock.nanos()).coerceAtLeast(0)
    }

    override fun close() {
        unit?.let { instance ->
            if (running) AudioOutputUnitStop(instance)
            AudioUnitUninitialize(instance)
            AudioComponentInstanceDispose(instance)
        }
        unit = null
        running = false
        render = null
        negotiated = null
        deviceBuffer = null
        selfRef?.dispose()
        selfRef = null
    }

    /**
     * How many anchors had to be estimated because CoreAudio said its host time was not valid.
     *
     * Kept for the appleTest that proves the flag is honoured, and as the note this sink can make
     * about anchor quality: [latencyQuality] already says `Estimated` for every anchor, so there is
     * no quality left to downgrade, and a real-time thread may not log. Counts the whole life of the
     * sink; [stop] does not reset it.
     */
    internal val estimatedAnchors: Long get() = estimatedAnchorCount.value

    /**
     * What this sink owns right now: the audio unit, the pinned self reference, the negotiated
     * format, the render callback and the one buffer wrapper. Five when open, zero when closed and
     * zero after a failed [open].
     *
     * Only appleTest reads it, and only to prove a failed open kept nothing.
     */
    internal fun retainedResources(): Int {
        var count = 0
        if (unit != null) count++
        if (selfRef != null) count++
        if (negotiated != null) count++
        if (render != null) count++
        if (deviceBuffer != null) count++
        return count
    }

    /**
     * The real-time path. Called by CoreAudio, never by the engine.
     *
     * No allocation, no lock, no suspension, no logging, and no exception may escape. Everything it
     * touches was prepared during [open].
     */
    internal fun renderFromDevice(
        timeStamp: AudioTimeStamp,
        frames: Int,
        bufferList: AudioBufferList,
    ): OSStatus = fillDeviceBuffer(render, timeStamp, frames, bufferList)

    /**
     * The body of the real-time path, with the callback passed in rather than read from the field.
     *
     * Splitting it this way is what lets appleTest drive the path with its own memory and prove the
     * absent-callback fill, a state a live device can only reach by racing [close]. The production
     * entry point differs from this by one field read.
     */
    internal fun fillDeviceBuffer(
        callback: AudioRenderCallback?,
        timeStamp: AudioTimeStamp,
        frames: Int,
        bufferList: AudioBufferList,
    ): OSStatus {
        val format = negotiated ?: return OK
        // No wrapper means close already disposed the unit, so this memory is not ours to write.
        val buffer = deviceBuffer ?: return OK
        if (frames <= 0) return OK

        buffer.target = bufferList.mBuffers[0].mData?.reinterpret()
        buffer.capacityFrames = frames

        val sampleRate = format.sampleRate
        val bufferNanos = if (sampleRate > 0) frames.toLong() * 1_000_000_000L / sampleRate else 0L
        val hostTimeIsValid = (timeStamp.mFlags and kAudioTimeStampHostTimeValid) != 0u
        val bufferStartNanos = if (hostTimeIsValid) {
            AppleHostClock.hostTimeToNanos(timeStamp.mHostTime)
        } else {
            estimatedAnchorCount.incrementAndGet()
            clock.nanos()
        }
        val deadline = bufferStartNanos + bufferNanos

        val written = (callback?.onRender(buffer, frames, deadline) ?: 0).coerceIn(0, frames)
        if (written < frames) buffer.writeSilence(written, frames - written)

        // The wrapper outlives the callback but the device's memory does not, so a stale pointer is
        // dropped here rather than left for whoever holds the buffer to write through later.
        buffer.target = null
        buffer.capacityFrames = 0

        lastDeadlineNanos.value = deadline
        everRendered.value = true
        return OK
    }

    /**
     * Writes straight into the buffer CoreAudio provided, so nothing is copied twice.
     *
     * One instance serves the whole life of the sink. The two fields below are what the callback
     * updates, and they are plain fields on purpose: a real-time thread that allocates is a real-time
     * thread that eventually waits for a lock inside the allocator.
     *
     * Writes are clamped to [capacityFrames]. The device's buffer is exactly as large as the device
     * said, and a ring whose arithmetic slipped would otherwise write past the end of a buffer
     * CoreAudio owns, which is memory corruption rather than a wrong sample.
     */
    private class DeviceBuffer(override val format: AudioFormat) : AudioSinkBuffer {

        /** Where this callback's samples go, valid only for the duration of one callback. */
        var target: CPointer<FloatVar>? = null

        /** How many frames [target] holds, which is what the device asked for this callback. */
        var capacityFrames: Int = 0

        override fun writeInterleaved(
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ) {
            val destination = target ?: return
            val channels = format.channels
            val base = destinationFrameOffset * channels
            val count = writable(destinationFrameOffset, frames) * channels
            var i = 0
            while (i < count) {
                destination[base + i] = source[sourceOffset + i]
                i++
            }
        }

        override fun writePlane(
            channel: Int,
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ) {
            val destination = target ?: return
            val channels = format.channels
            val count = writable(destinationFrameOffset, frames)
            var i = 0
            while (i < count) {
                destination[(destinationFrameOffset + i) * channels + channel] = source[sourceOffset + i]
                i++
            }
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            val destination = target ?: return
            val channels = format.channels
            var i = frameOffset * channels
            val end = i + writable(frameOffset, frames) * channels
            while (i < end) {
                destination[i] = 0f
                i++
            }
        }

        /** Frames that really fit at [frameOffset], so a wrong count cannot leave the buffer. */
        private fun writable(frameOffset: Int, frames: Int): Int {
            if (frameOffset < 0 || frames <= 0) return 0
            return min(frames, capacityFrames - frameOffset).coerceAtLeast(0)
        }
    }

    private companion object {
        const val OUTPUT_BUS: UInt = 0u

        /**
         * What CoreAudio typically asks for at 48 kHz. Used only to size the engine's ring before the
         * device has asked once, and the ring is generous enough that being wrong here is harmless.
         */
        const val DEFAULT_DEVICE_BUFFER_FRAMES: Int = 512
    }
}

/**
 * Creates [CoreAudioSink] instances.
 *
 * Reached through [AppleOutputBackend], which is what goes into `PlayerConfig.backends.output`: that
 * object pairs this factory with the clock the engine reads, so the two cannot disagree. Constructing
 * one directly is for a test or a custom assembly, and the clock still has to be [AppleHostClock]; see
 * the check in [CoreAudioSink].
 */
public class CoreAudioSinkFactory(
    private val clock: MonotonicClock = AppleHostClock,
) : AudioSinkFactory {
    override val name: String = "CoreAudio"
    override suspend fun create(): AudioSink = CoreAudioSink(clock)
}

/** CoreAudio's success status. `noErr` in the platform headers is unsigned; OSStatus is not. */
private const val OK: OSStatus = 0

private fun OSStatus.requireOk(what: String) {
    if (this != OK) error("$what failed with CoreAudio status $this")
}
