/* The audio device, in C, so that nothing managed is on its real-time thread.
 *
 * WHAT THIS FILE IS FOR. Register item B1-17. Until B1.8 the render callback was a Kotlin lambda
 * whose first instruction was `refCon?.asStableRef<CoreAudioSink>()?.get()`, which made the device's
 * real-time thread a Kotlin mutator: the garbage collector had to stop it at a safepoint, thirteen
 * long-lived objects and up to five transient cinterop views were on that path, and worst measured
 * stop-the-world pauses on the development machine were 63 to 256 microseconds against a 10.67
 * millisecond period. The claim being fixed is not "audio glitches"; it is "the deadline depends on a
 * pause nobody has bounded". After this file, the callback is a `static` C function, its `ref` is a
 * plain struct pointer with no reference counting of any kind, and its exact external call set is
 * `mach_absolute_time`, `kprt_render_into` and `kprt_sink_note_span`. `scripts/render-audit.sh`
 * proves that from the object's own symbol table rather than from this paragraph.
 *
 * WHY THE WHOLE DEVICE LIFECYCLE MOVED AND NOT JUST THE CALLBACK. Because a Kotlin object that owned
 * the `AudioUnit` would still have to be reachable from the callback to be of any use, and the moment
 * it is reachable the `StableRef` is back. So create, negotiate, set the format, install the
 * callback, initialise, start, stop, pause and dispose are all here, and `CoreAudioSink` in
 * `kiteplayer-output` is a thin owner of two opaque handles that never touches an `AudioUnit`.
 *
 * PLATFORM. macOS uses DefaultOutput and iOS uses RemoteIO; both install this same callback body.
 * The iOS owner acquires an activated AVAudioSession before it creates this sink, so session policy
 * stays off the callback thread and outside this C boundary. tvOS, watchOS and every non-Apple target
 * still compile the entry points below as refusals, so an unsupported target gets a runtime verdict
 * rather than a missing symbol at link time.
 *
 * TRANSACTIONAL OPEN, which is defect D23 moved into C. Every failure path in `kprt_sink_create`
 * disposes precisely what it had created and returns a verdict, so a refused open leaves nothing
 * behind and the same sink object can be opened again.
 */

#include "kite_rt.h"

/* Needed by BOTH branches below, which is why it is here rather than inside the supported-Apple
 * one. Measured: with this include inside the guard, unsupported targets failed with nine "use of
 * undeclared identifier 'NULL'" errors, because `kite_rt.h` includes only <stdint.h> and the active
 * branch got NULL for free from AudioToolbox. Compiling one target would never have shown it. */
#include <stddef.h>

#if defined(__APPLE__)
#include <TargetConditionals.h>
#endif

#if defined(__APPLE__) && \
    ((defined(TARGET_OS_OSX) && TARGET_OS_OSX) || \
     (defined(TARGET_OS_IOS) && TARGET_OS_IOS))

#include "kite_rt_sink_internal.h"

#include <AudioToolbox/AudioToolbox.h>
#include <mach/mach_time.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

/* The output bus of an output unit. Fixed by CoreAudio, not a choice. */
#define KPRT_OUTPUT_BUS 0u

/* What CoreAudio typically asks for at 48 kHz on this platform.
 *
 * Reported as the device's period so the engine can size its ring before the device has asked even
 * once. Being wrong here is harmless by construction: the ring is sized at the larger of a multiple
 * of this and the engine's own buffer duration, and `kprt_ring_render` serves any frame count it is
 * given. Reading the true value would mean querying the HAL device behind the unit, which is more
 * surface than the number is worth. */
#define KPRT_DEFAULT_DEVICE_BUFFER_FRAMES 512

/* Channels this sink will accept. Both Apple output units take interleaved 32 bit float at common
 * rates, so the engine's own internal format passes through unchanged and neither side converts
 * anything; the channel count is clamped because everything above the mono and stereo case needs a
 * real channel map, which is defect D30's business and not this file's. */
#define KPRT_MIN_CHANNELS 1
#define KPRT_MAX_CHANNELS 2

static void report_status(int32_t *out_os_status, OSStatus status)
{
    if (out_os_status != NULL)
        *out_os_status = (int32_t)status;
}

/* ---- The real-time callback ----
 *
 * `static`, so it is not in the archive's exported set, is not named by `include/kite_rt.h`, is not
 * in the cinterop bindings and cannot be installed or called by Kotlin. That is the point of it.
 *
 * It validates and bounds the device-owned buffers, takes the entry tick, decides which host time is
 * meaningful, hands the accepted span to `kprt_render_into`, and closes the tick pair. Malformed
 * layouts are made deterministic silence in place. The object audit reads both this unit and
 * `kite_rt_render.c`; validation adds stores and bounded loops, never another callable symbol. */
static OSStatus kprt_render_cb(void *ref,
                               AudioUnitRenderActionFlags *action_flags,
                               const AudioTimeStamp *stamp,
                               UInt32 bus,
                               UInt32 frames,
                               AudioBufferList *data)
{
    kprt_sink *sink = (kprt_sink *)ref;
    uint64_t entered;
    uint64_t host_ticks;
    int32_t estimated;
    int32_t rendered;
    float *destination;
    volatile unsigned char *byte_destination;
    UInt32 buffer_index;
    UInt32 byte_index;
    UInt32 usable_frames;
    UInt32 bytes_per_frame;

    (void)bus;

    /* No StableRef, no reference counting, no null-safe chain through managed objects: a plain cast
     * of the pointer `kprt_sink_create` handed to CoreAudio. The sink outlives every callback
     * because `kprt_sink_destroy` disposes the unit before it frees the sink. */
    if (data == NULL) {
        if (action_flags != NULL)
            *action_flags |= kAudioUnitRenderAction_OutputIsSilence;
        return noErr;
    }

    /* The negotiated stream is exactly one packed interleaved buffer. If CoreAudio supplies another
     * layout, every non-null span is still safe to silence up to the byte size CoreAudio supplied.
     * Volatile byte stores keep the compiler from replacing this real-time fallback with a new
     * memset call; the shipped callback's audited call set therefore stays exact. */
    if (data->mNumberBuffers != 1) {
        if (action_flags != NULL)
            *action_flags |= kAudioUnitRenderAction_OutputIsSilence;
        for (buffer_index = 0; buffer_index < data->mNumberBuffers; buffer_index++) {
            byte_destination =
                (volatile unsigned char *)data->mBuffers[buffer_index].mData;
            if (byte_destination == NULL)
                continue;
            for (byte_index = 0;
                 byte_index < data->mBuffers[buffer_index].mDataByteSize;
                 byte_index++)
                byte_destination[byte_index] = 0;
        }
        return noErr;
    }

    destination = (float *)data->mBuffers[0].mData;
    if (destination == NULL || data->mBuffers[0].mDataByteSize == 0 || frames == 0) {
        if (action_flags != NULL)
            *action_flags |= kAudioUnitRenderAction_OutputIsSilence;
        return noErr;
    }

    /* A null or malformed refCon cannot be rendered, but it must not return the device's previous
     * contents as noise. This path is unreachable for an installed unit and remains deterministic
     * for the host seam and for defensive callers. */
    if (sink == NULL || sink->channels <= 0) {
        if (action_flags != NULL)
            *action_flags |= kAudioUnitRenderAction_OutputIsSilence;
        byte_destination = (volatile unsigned char *)data->mBuffers[0].mData;
        for (byte_index = 0; byte_index < data->mBuffers[0].mDataByteSize; byte_index++)
            byte_destination[byte_index] = 0;
        return noErr;
    }

    /* A short byte size clamps the request to complete interleaved frames. If it cannot hold even
     * one frame, silence every byte that is safe to write and tell CoreAudio the result is silent.
     * No write can cross mDataByteSize. */
    bytes_per_frame = (UInt32)sink->channels * (UInt32)sizeof(float);
    usable_frames = data->mBuffers[0].mDataByteSize / bytes_per_frame;
    if (frames > usable_frames)
        frames = usable_frames;
    if (frames == 0) {
        if (action_flags != NULL)
            *action_flags |= kAudioUnitRenderAction_OutputIsSilence;
        byte_destination = (volatile unsigned char *)data->mBuffers[0].mData;
        for (byte_index = 0; byte_index < data->mBuffers[0].mDataByteSize; byte_index++)
            byte_destination[byte_index] = 0;
        return noErr;
    }

    entered = mach_absolute_time();

    /* CoreAudio says which fields of its timestamp mean anything. Reading a host time it did not
     * flag valid would anchor the audio clock to a number with no meaning, so the fallback is the
     * same clock read a moment earlier, and the substitution is counted.
     *
     * `mach_absolute_time` and not `AudioGetCurrentHostTime`: the former is the direct monotonic
     * clock on both supported targets. `tests/test_sink_timebase.c` measures the macOS conversion,
     * and keeping CoreAudio.framework off this path preserves the exact audited call set. */
    if (stamp != NULL && (stamp->mFlags & kAudioTimeStampHostTimeValid) != 0) {
        host_ticks = stamp->mHostTime;
        estimated = 0;
    } else {
        host_ticks = entered;
        estimated = 1;
    }

    rendered = kprt_render_into(sink, destination, (int32_t)frames, host_ticks, estimated);
    if (rendered == 0 && action_flags != NULL)
        *action_flags |= kAudioUnitRenderAction_OutputIsSilence;

    kprt_sink_note_span(sink, entered, mach_absolute_time());
    return noErr;
}

#if defined(KPRT_TESTING)
/* Compile-only host seam for malformed AudioBufferList fixtures.
 *
 * The allocation and structure translation live outside kprt_render_cb and outside every shipped
 * build. The callback still receives the real CoreAudio types and the host suite therefore tests
 * its actual guards, flag writes and byte bounds rather than a second rendering implementation. */
int32_t kprt_test_invoke_render_callback(kprt_sink *sink,
                                         uint32_t requested_frames,
                                         uint32_t buffer_count,
                                         const kprt_test_audio_buffer *buffers,
                                         int32_t host_time_valid,
                                         uint64_t host_ticks,
                                         uint32_t *action_flags)
{
    AudioBufferList *list;
    AudioTimeStamp stamp;
    AudioUnitRenderActionFlags native_flags = 0;
    size_t list_bytes;
    uint32_t i;
    OSStatus status;

    _Static_assert(KPRT_TEST_OUTPUT_IS_SILENCE == kAudioUnitRenderAction_OutputIsSilence,
                   "the host fixture's silence bit must be CoreAudio's silence bit");

    if (buffer_count > KPRT_TEST_MAX_AUDIO_BUFFERS ||
        (buffer_count != 0 && buffers == NULL))
        return -1;

    list_bytes = sizeof(AudioBufferList);
    if (buffer_count > 1)
        list_bytes += ((size_t)buffer_count - 1u) * sizeof(AudioBuffer);
    list = (AudioBufferList *)calloc(1, list_bytes);
    if (list == NULL)
        return -2;

    list->mNumberBuffers = (UInt32)buffer_count;
    for (i = 0; i < buffer_count; i++) {
        list->mBuffers[i].mData = buffers[i].data;
        list->mBuffers[i].mDataByteSize = (UInt32)buffers[i].byte_size;
        list->mBuffers[i].mNumberChannels = (UInt32)buffers[i].channels;
    }

    memset(&stamp, 0, sizeof(stamp));
    if (host_time_valid) {
        stamp.mFlags = kAudioTimeStampHostTimeValid;
        stamp.mHostTime = host_ticks;
    }
    if (action_flags != NULL)
        native_flags = (AudioUnitRenderActionFlags)*action_flags;

    status = kprt_render_cb(sink, action_flags != NULL ? &native_flags : NULL,
                            &stamp, KPRT_OUTPUT_BUS, (UInt32)requested_frames, list);
    if (action_flags != NULL)
        *action_flags = (uint32_t)native_flags;
    free(list);
    return (int32_t)status;
}
#endif

/* ---- Lifecycle ---- */

int32_t kprt_sink_create(int32_t sample_rate, int32_t channels, kprt_sink **out_sink,
                         kprt_sink_format *out_format, int32_t *out_os_status)
{
    AudioComponentDescription description;
    AudioComponent component;
    AudioComponentInstance instance = NULL;
    AudioStreamBasicDescription asbd;
    AURenderCallbackStruct callback;
    mach_timebase_info_data_t timebase;
    kprt_sink *sink;
    OSStatus status;
    int32_t accepted_channels;
    UInt32 bytes_per_frame;

    report_status(out_os_status, 0);
    if (out_sink == NULL || out_format == NULL)
        return KPRT_SINK_BAD_ARGUMENT;
    *out_sink = NULL;
    memset(out_format, 0, sizeof(*out_format));

    accepted_channels = channels;
    if (accepted_channels < KPRT_MIN_CHANNELS)
        accepted_channels = KPRT_MIN_CHANNELS;
    if (accepted_channels > KPRT_MAX_CHANNELS)
        accepted_channels = KPRT_MAX_CHANNELS;

    /* The sample rate is deliberately NOT validated here. A rate the device refuses must be refused
     * BY THE DEVICE, after the instance exists, because that is the window in which a half open used
     * to leave things behind, and `CoreAudioSinkTest` forces exactly that failure to prove the
     * cleanup path runs. Validating it early would delete the test's subject. */

    memset(&description, 0, sizeof(description));
    description.componentType = kAudioUnitType_Output;
#if defined(TARGET_OS_IOS) && TARGET_OS_IOS
    description.componentSubType = kAudioUnitSubType_RemoteIO;
#else
    description.componentSubType = kAudioUnitSubType_DefaultOutput;
#endif
    description.componentManufacturer = kAudioUnitManufacturer_Apple;
    component = AudioComponentFindNext(NULL, &description);
    if (component == NULL)
        return KPRT_SINK_NO_COMPONENT;

    status = AudioComponentInstanceNew(component, &instance);
    if (status != noErr || instance == NULL) {
        report_status(out_os_status, status);
        return KPRT_SINK_INSTANCE_REFUSED;
    }

    /* From here on every failure disposes the instance before returning. */

    sink = (kprt_sink *)calloc(1, sizeof(kprt_sink));
    if (sink == NULL) {
        AudioComponentInstanceDispose(instance);
        return KPRT_SINK_OUT_OF_MEMORY;
    }

    memset(&asbd, 0, sizeof(asbd));
    bytes_per_frame = (UInt32)accepted_channels * (UInt32)sizeof(float);
    asbd.mSampleRate = (Float64)sample_rate;
    asbd.mFormatID = kAudioFormatLinearPCM;
    asbd.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
    asbd.mBitsPerChannel = 32;
    asbd.mChannelsPerFrame = (UInt32)accepted_channels;
    asbd.mFramesPerPacket = 1;
    asbd.mBytesPerFrame = bytes_per_frame;
    asbd.mBytesPerPacket = bytes_per_frame;
    status = AudioUnitSetProperty(instance, kAudioUnitProperty_StreamFormat,
                                  kAudioUnitScope_Input, KPRT_OUTPUT_BUS,
                                  &asbd, (UInt32)sizeof(asbd));
    if (status != noErr) {
        free(sink);
        AudioComponentInstanceDispose(instance);
        report_status(out_os_status, status);
        return KPRT_SINK_FORMAT_REFUSED;
    }

    /* Everything the callback reads is in place before the callback can be installed. The ring is
     * still NULL, which is a state the callback handles by zeroing its buffer, so even a device that
     * called immediately would produce silence rather than noise. */
    sink->sample_rate = sample_rate;
    sink->channels = accepted_channels;
    sink->device_buffer_frames = KPRT_DEFAULT_DEVICE_BUFFER_FRAMES;
    sink->unit = (void *)instance;
    atomic_store_explicit(&sink->ring, (kprt_ring *)NULL, memory_order_relaxed);

    memset(&timebase, 0, sizeof(timebase));
    if (mach_timebase_info(&timebase) != KERN_SUCCESS || timebase.denom == 0) {
        /* Never observed, and handled anyway. 1 over 1 makes the conversion the identity, which is
         * the least wrong fallback: it keeps the deadline on the host clock's own scale instead of
         * multiplying it by zero. This machine reports 125 over 3, not 1 over 1, which
         * `tests/test_sink_timebase.c` measures rather than assumes. */
        timebase.numer = 1;
        timebase.denom = 1;
    }
    sink->timebase_numer = timebase.numer;
    sink->timebase_denom = timebase.denom;

    memset(&callback, 0, sizeof(callback));
    callback.inputProc = kprt_render_cb;
    callback.inputProcRefCon = sink;
    status = AudioUnitSetProperty(instance, kAudioUnitProperty_SetRenderCallback,
                                  kAudioUnitScope_Input, KPRT_OUTPUT_BUS,
                                  &callback, (UInt32)sizeof(callback));
    if (status != noErr) {
        free(sink);
        AudioComponentInstanceDispose(instance);
        report_status(out_os_status, status);
        return KPRT_SINK_CALLBACK_REFUSED;
    }

    status = AudioUnitInitialize(instance);
    if (status != noErr) {
        free(sink);
        AudioComponentInstanceDispose(instance);
        report_status(out_os_status, status);
        return KPRT_SINK_INITIALIZE_REFUSED;
    }
    sink->initialized = 1;

    out_format->sample_rate = sink->sample_rate;
    out_format->channels = sink->channels;
    out_format->device_buffer_frames = sink->device_buffer_frames;
    *out_sink = sink;
    return KPRT_SINK_OK;
}

int32_t kprt_sink_attach_ring(kprt_sink *sink, int32_t capacity_frames)
{
    kprt_ring *ring;

    if (sink == NULL)
        return KPRT_SINK_BAD_ARGUMENT;
    if (atomic_load_explicit(&sink->ring, memory_order_relaxed) != NULL)
        return KPRT_SINK_ALREADY_HAS_RING;

    ring = kprt_ring_create(sink->sample_rate, sink->channels, capacity_frames);
    if (ring == NULL)
        return KPRT_SINK_RING_REFUSED;

    sink->owns_ring = 1;
    /* Release, paired with the acquire in `kprt_render_into`. Without it a callback could see the
     * pointer before the zeroed sample block, and would read whatever `malloc` last held there. */
    atomic_store_explicit(&sink->ring, ring, memory_order_release);
    return KPRT_SINK_OK;
}

kprt_ring *kprt_sink_ring(const kprt_sink *sink)
{
    if (sink == NULL)
        return NULL;
    return atomic_load_explicit(&sink->ring, memory_order_acquire);
}

int32_t kprt_sink_start(kprt_sink *sink, int32_t *out_os_status)
{
    OSStatus status;

    report_status(out_os_status, 0);
    if (sink == NULL || sink->unit == NULL)
        return KPRT_SINK_BAD_ARGUMENT;
    if (sink->running)
        return KPRT_SINK_OK;

    status = AudioOutputUnitStart((AudioComponentInstance)sink->unit);
    if (status != noErr) {
        report_status(out_os_status, status);
        return KPRT_SINK_START_REFUSED;
    }
    sink->running = 1;
    return KPRT_SINK_OK;
}

int32_t kprt_sink_stop(kprt_sink *sink, int32_t *out_os_status)
{
    OSStatus status;

    report_status(out_os_status, 0);
    if (sink == NULL || sink->unit == NULL)
        return KPRT_SINK_BAD_ARGUMENT;
    if (!sink->running)
        return KPRT_SINK_OK;

    /* `AudioOutputUnitStop` does not return until the unit has stopped rendering, which is what
     * makes the quiescence `kprt_ring_flush` requires provable rather than hoped for. The engine's
     * seek path depends on it: it stops the device before it clears the ring, because a device still
     * pulling from a ring being cleared would play a mixture of the old position and the new one
     * (defect D25). */
    status = AudioOutputUnitStop((AudioComponentInstance)sink->unit);
    if (status != noErr) {
        report_status(out_os_status, status);
        return KPRT_SINK_STOP_REFUSED;
    }
    sink->running = 0;
    return KPRT_SINK_OK;
}

int32_t kprt_sink_set_paused(kprt_sink *sink, int32_t paused, int32_t *out_os_status)
{
    /* Stopping the unit keeps the device open, so nothing buffered is lost and resuming is quick.
     * That is why this is not `stop` plus `start`: those are the seek path and this is not. */
    if (paused != 0)
        return kprt_sink_stop(sink, out_os_status);
    return kprt_sink_start(sink, out_os_status);
}

void kprt_sink_destroy(kprt_sink *sink)
{
    kprt_ring *ring;

    if (sink == NULL)
        return;

    if (sink->unit != NULL) {
        AudioComponentInstance instance = (AudioComponentInstance)sink->unit;
        if (sink->running)
            (void)AudioOutputUnitStop(instance);
        if (sink->initialized)
            (void)AudioUnitUninitialize(instance);
        (void)AudioComponentInstanceDispose(instance);
        sink->unit = NULL;
    }
    sink->running = 0;
    sink->initialized = 0;

    /* Only now. The callback is out and cannot be entered again, because the unit it was installed
     * on no longer exists, so clearing and freeing the ring cannot race a render. Doing this in the
     * other order is the classic use-after-free in an audio teardown. */
    ring = atomic_load_explicit(&sink->ring, memory_order_relaxed);
    atomic_store_explicit(&sink->ring, (kprt_ring *)NULL, memory_order_release);
    if (ring != NULL && sink->owns_ring)
        kprt_ring_destroy(ring);
    sink->owns_ring = 0;

    free(sink);
}

void kprt_sink_read_stats(const kprt_sink *sink, kprt_sink_stats *out)
{
    if (out == NULL)
        return;
    memset(out, 0, sizeof(*out));
    if (sink == NULL)
        return;
    out->callbacks = atomic_load_explicit(&sink->callbacks, memory_order_relaxed);
    out->estimated_anchors = atomic_load_explicit(&sink->estimated_anchors, memory_order_relaxed);
    out->zero_filled_callbacks =
        atomic_load_explicit(&sink->zero_filled_callbacks, memory_order_relaxed);
    out->worst_callback_nanos =
        atomic_load_explicit(&sink->worst_callback_nanos, memory_order_relaxed);
    out->last_deadline_nanos =
        atomic_load_explicit(&sink->last_deadline_nanos, memory_order_relaxed);
    out->running = sink->running;
    out->has_ring = atomic_load_explicit(&sink->ring, memory_order_acquire) != NULL ? 1 : 0;
}

#else /* neither macOS nor iOS */

/* Every entry point, present and refusing.
 *
 * A refusal is better than a missing symbol for one measurable reason: `kiteplayer-core` declares
 * seventeen native targets and its `nativeMain` is shared across all of them, so a link error here
 * would be a link error for a target nobody had built yet. It is also better than silence, because
 * KPRT_SINK_UNSUPPORTED_PLATFORM says which of the two possible things went wrong. Compiling on a
 * target is level 7 evidence in the terms of plan section 2 and this file makes no claim beyond it. */

int32_t kprt_sink_create(int32_t sample_rate, int32_t channels, kprt_sink **out_sink,
                         kprt_sink_format *out_format, int32_t *out_os_status)
{
    (void)sample_rate;
    (void)channels;
    if (out_sink != NULL)
        *out_sink = NULL;
    if (out_format != NULL) {
        out_format->sample_rate = 0;
        out_format->channels = 0;
        out_format->device_buffer_frames = 0;
    }
    if (out_os_status != NULL)
        *out_os_status = 0;
    return KPRT_SINK_UNSUPPORTED_PLATFORM;
}

int32_t kprt_sink_attach_ring(kprt_sink *sink, int32_t capacity_frames)
{
    (void)sink;
    (void)capacity_frames;
    return KPRT_SINK_UNSUPPORTED_PLATFORM;
}

kprt_ring *kprt_sink_ring(const kprt_sink *sink)
{
    (void)sink;
    return NULL;
}

int32_t kprt_sink_start(kprt_sink *sink, int32_t *out_os_status)
{
    (void)sink;
    if (out_os_status != NULL)
        *out_os_status = 0;
    return KPRT_SINK_UNSUPPORTED_PLATFORM;
}

int32_t kprt_sink_stop(kprt_sink *sink, int32_t *out_os_status)
{
    (void)sink;
    if (out_os_status != NULL)
        *out_os_status = 0;
    return KPRT_SINK_UNSUPPORTED_PLATFORM;
}

int32_t kprt_sink_set_paused(kprt_sink *sink, int32_t paused, int32_t *out_os_status)
{
    (void)sink;
    (void)paused;
    if (out_os_status != NULL)
        *out_os_status = 0;
    return KPRT_SINK_UNSUPPORTED_PLATFORM;
}

void kprt_sink_destroy(kprt_sink *sink)
{
    (void)sink;
}

void kprt_sink_read_stats(const kprt_sink *sink, kprt_sink_stats *out)
{
    (void)sink;
    if (out != NULL) {
        out->callbacks = 0;
        out->estimated_anchors = 0;
        out->zero_filled_callbacks = 0;
        out->worst_callback_nanos = 0;
        out->last_deadline_nanos = 0;
        out->running = 0;
        out->has_ring = 0;
    }
}

#endif /* macOS or iOS */
