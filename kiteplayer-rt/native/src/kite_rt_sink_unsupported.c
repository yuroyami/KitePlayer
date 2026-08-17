/* The device sink on a platform that has no backend yet.
 *
 * Every `kprt_sink_*` entry point exists here and refuses. A refusal is better than a missing
 * symbol for one measurable reason: `kiteplayer-core` declares seventeen native targets and its
 * `nativeMain` is shared across all of them, so a link error here would be a link error for a
 * target nobody had built yet. It is also better than silence, because KPRT_SINK_UNSUPPORTED_PLATFORM
 * says which of the two possible things went wrong. Compiling on a target is level 7 evidence in the
 * terms of plan section 2 and this file makes no claim beyond it.
 *
 * WHY IT IS ITS OWN FILE (phase W, register item W-08). It used to be the `#else` arm of
 * `kite_rt_coreaudio.c`, which meant every non-Apple target linked its device layer out of a file
 * named after CoreAudio, and a new backend would have arrived as another arm of that same `#if`.
 * A sink is per platform, so each platform gets a file. The guard below is the exact complement of
 * the CoreAudio one: whenever a real backend for a platform lands, its own file takes that platform
 * out of this guard and nothing else moves.
 */

#include "kite_rt.h"

/* TARGET_OS_* only exist on Apple, and the guard below reads them, so the header that defines them
 * has to come first there. Interlude item I-19's lesson in the CoreAudio file applies here too. */
#if defined(__APPLE__)
#include <TargetConditionals.h>
#endif

#if !(defined(__APPLE__) && \
      ((defined(TARGET_OS_OSX) && TARGET_OS_OSX) || \
       (defined(TARGET_OS_IOS) && TARGET_OS_IOS)))

#include <stddef.h>

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

int32_t kprt_sink_destroy(kprt_sink *sink)
{
    (void)sink;
    return KPRT_SINK_OK;
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

#endif /* no device backend for this platform */
