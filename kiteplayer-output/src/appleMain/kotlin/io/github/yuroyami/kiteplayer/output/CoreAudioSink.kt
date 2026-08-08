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
import platform.darwin.OSStatus

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
 * instant its last frame becomes audible, which is exactly what the audio clock anchors to.
 *
 * Nothing in this file estimates a device latency, because nothing needs to. ffplay assumes every
 * device holds exactly two buffer periods, and that assumption is the largest single source of fixed
 * A/V offset in it.
 */
@OptIn(ExperimentalForeignApi::class)
public class CoreAudioSink(
    private val clock: MonotonicClock = AppleHostClock,
) : AudioSink {

    private var unit: AudioComponentInstance? = null
    private var selfRef: StableRef<CoreAudioSink>? = null
    private var negotiated: AudioFormat? = null
    private var running = false

    /** Read by the real-time callback, so it is set once during open and never mutated after. */
    private var render: AudioRenderCallback? = null

    private val eventFlow = MutableSharedFlow<AudioSinkEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<AudioSinkEvent> = eventFlow.asSharedFlow()

    /** When the last frame of the most recently filled buffer becomes audible. */
    private val lastDeadlineNanos = atomic(0L)
    private val everRendered = atomic(false)

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

        this.render = render
        val ref = StableRef.create(this)
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

        AudioUnitInitialize(instance).requireOk("AudioUnitInitialize")

        unit = instance
        negotiated = format
        return format
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
        selfRef?.dispose()
        selfRef = null
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
    ): OSStatus {
        val format = negotiated ?: return OK
        val callback = render ?: return OK

        val bufferStartNanos = AppleHostClock.hostTimeToNanos(timeStamp.mHostTime)
        val bufferNanos = frames.toLong() * 1_000_000_000L / format.sampleRate
        val deadline = bufferStartNanos + bufferNanos

        callback.onRender(CoreAudioSinkBuffer(format, bufferList), frames, deadline)

        lastDeadlineNanos.value = deadline
        everRendered.value = true
        return OK
    }

    /** Writes straight into the buffer CoreAudio provided, so nothing is copied twice. */
    private class CoreAudioSinkBuffer(
        override val format: AudioFormat,
        bufferList: AudioBufferList,
    ) : AudioSinkBuffer {

        private val target: CPointer<FloatVar>? = bufferList.mBuffers[0].mData?.reinterpret()

        override fun writeInterleaved(
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ) {
            val destination = target ?: return
            val channels = format.channels
            val base = destinationFrameOffset * channels
            val count = frames * channels
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
            var i = 0
            while (i < frames) {
                destination[(destinationFrameOffset + i) * channels + channel] = source[sourceOffset + i]
                i++
            }
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            val destination = target ?: return
            val channels = format.channels
            var i = frameOffset * channels
            val end = i + frames * channels
            while (i < end) {
                destination[i] = 0f
                i++
            }
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

/** Creates [CoreAudioSink] instances. Supply this in `PlayerConfig.backends.audioSink`. */
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
