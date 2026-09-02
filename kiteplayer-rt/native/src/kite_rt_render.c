/* The real-time translation unit: everything the audio device's own thread executes, and nothing
 * else.
 *
 * WHY THIS IS A SEPARATE FILE FROM kite_rt_ring.c. Plan section 15.2 B1.8's first assertion is a
 * symbol audit: "`nm -u` on the object must yield nothing outside a fixed allowlist". That property
 * is only meaningful, and only achievable, for a unit that CANNOT allocate. `kite_rt_ring.c` holds
 * `kprt_ring_create` and `kprt_ring_destroy`, so its undefined set contains `_malloc` and `_free`,
 * and an audit of it would have to be explained away with "but only in create", which is an
 * argument rather than a property. Splitting the render path out turns the assertion into a fact a
 * script can check with no runtime: the object the device's thread runs inside has no allocator
 * symbol to call, no lock symbol to take and no framework symbol to enter.
 *
 * WHAT IS IN HERE. `kprt_ring_render` and the anchor it publishes, which used to live beside the
 * producer; plus `kprt_render_into`, the body of the device callback. The callback itself,
 * `kprt_render_cb`, is `static` in `kite_rt_coreaudio.c` and does two things: it takes the host tick
 * pair and it unpacks the device's structures. Everything that touches a sample is here.
 *
 * WHAT IS DELIBERATELY NOT IN HERE. Any platform header. `mach_absolute_time` and
 * `mach_timebase_info` are Apple functions and belong to the file that knows about Apple; the ticks
 * arrive here as a `uint64_t` and the timebase arrives as two integers cached at create. That is
 * also why this unit compiles for all seventeen Kotlin/Native targets while the device unit
 * compiles to nothing on sixteen of them.
 *
 * The Kotlin ring at `kiteplayer-core/src/commonMain/.../internal/KotlinAudioRing.kt` is the
 * specification for the ring half of this file, and the differential oracle at
 * `kiteplayer-core/src/nativeTest/.../AudioRingDifferentialTest.kt` is what keeps the two from
 * drifting. The three deliberate differences are listed at the top of `kite_rt_ring.c`.
 */

#include "kite_rt_ring_internal.h"
#include "kite_rt_sink_internal.h"

#include <string.h>

/* ---- Arithmetic, used by both sides of the ring ---- */

int64_t kprt_frames_to_micros(int64_t frames, int32_t sample_rate)
{
    int64_t rate;
    int64_t whole;
    int64_t rest;

    if (sample_rate <= 0)
        return 0;

    /* Divide first. The naive `frames * 1000000 / rate` overflows a signed 64 bit intermediate
     * once frames exceeds about 9.2e12, the same shape as
     * KiteFFmpeg defect D9. This form only overflows once `frames / rate` alone exceeds 9.2e12,
     * which at 48 kHz is a frame count no clock in this universe reaches.
     *
     * Exactness: with frames = whole * rate + rest, the true value is
     * whole * 1000000 + rest * 1000000 / rate, and `rest * 1000000` cannot overflow because
     * |rest| < rate <= INT32_MAX. C truncates division toward zero and takes the remainder with
     * the sign of the dividend, which is also what Kotlin's Long division does, so the two
     * implementations agree for negative deltas as well as positive ones. */
    rate = (int64_t)sample_rate;
    whole = frames / rate;
    rest = frames % rate;
    /* Saturate instead of overflowing, matching the decision add_saturating already took for
     * the anchor (interlude item I-05): `whole * 1000000` was measured overflowing under UBSan
     * through this exported entry point at INT64_MAX frames. A duration that does not fit int64
     * microseconds is already meaningless; what matters is that both implementations of the
     * contract produce the SAME meaningless number, and the differential oracle carries a row
     * at each end of the range to hold them to it. */
    if (whole > INT64_MAX / 1000000)
        return INT64_MAX;
    if (whole < INT64_MIN / 1000000)
        return INT64_MIN;
    return whole * 1000000 + rest * 1000000 / rate;
}

/* `a + b`, saturating at the ends of the range rather than overflowing.
 *
 * Signed overflow is undefined behaviour, and this addition dates every anchor the media clock is
 * built from, so it is checked rather than assumed. A base timestamp within a few milliseconds of
 * INT64_MAX is not reachable from any real container, and "unreachable" is not an argument about
 * definedness: the independent verification of B1.8 found the sibling site in `record_timestamp` with
 * UBSan, and the differential oracle now carries a row at each end of the range which would otherwise
 * be comparing two wraps and calling the agreement a proof.
 *
 * Saturation is the least wrong answer available. A sum that does not fit is already meaningless as a
 * timestamp; what matters is that both implementations produce the same meaningless number rather than
 * two different ones. `KotlinAudioRing.addSaturating` is the same three lines.
 *
 * Cost on the real-time path: one add, one branch, no call, so the audited undefined symbol set of
 * this translation unit is unchanged. */
static int64_t add_saturating(int64_t a, int64_t b)
{
    int64_t sum;

    if (!__builtin_add_overflow(a, b, &sum))
        return sum;
    return b < 0 ? INT64_MIN : INT64_MAX;
}

/* `a - b`, saturating, the mirror of add_saturating for the one subtraction on the render path:
 * the silence back-dating below, where UBSan measured `INT64_MIN - 1333333 cannot be
 * represented` through the public surface (interlude item I-05). Same cost, same reasoning, and
 * with it the anchor path holds no unchecked signed arithmetic at all. */
static int64_t sub_saturating(int64_t a, int64_t b)
{
    int64_t difference;
    if (__builtin_sub_overflow(a, b, &difference))
        return b > 0 ? INT64_MIN : INT64_MAX;
    return difference;
}


/* Bumps a single-writer statistics counter without a read-modify-write.
 *
 * Every counter this touches has exactly one writer: the device's render thread, in this
 * translation unit. An `atomic_fetch_add` is therefore strength beyond the requirement, and on
 * arm64 without LSE it compiles to an ldxr/stxr conditional retry loop, which breaks the
 * bounded-work guarantee this hard-real-time object promises. A relaxed load plus a
 * relaxed store is wait-free, and readers on other threads still see a monotonic counter because
 * no second writer exists to interleave with. */
static void kprt_counter_bump(_Atomic int64_t *counter)
{
    atomic_store_explicit(counter,
        atomic_load_explicit(counter, memory_order_relaxed) + 1,
        memory_order_relaxed);
}

/* ---- The ring's consumer side ---- */

/* Publishes the media time one sample past `last_real_frame`, reached at `at_nanos`.
 *
 * Runs on the real-time thread, so it never waits: the segment walk is at most
 * KPRT_MAX_SEGMENTS single attempts, and a torn slot ends the walk instead of restarting it.
 *
 * A frame earlier than the oldest live segment is dated by continuity from that segment, which
 * is what the frames written before the first timestamp arrived need. */
static void publish_anchor(kprt_ring *ring, int64_t last_real_frame, int64_t at_nanos)
{
    int64_t appended = atomic_load_explicit(&ring->segments_appended, memory_order_acquire);
    int64_t retired = atomic_load_explicit(&ring->segments_retired, memory_order_acquire);
    int64_t base_frame = 0;
    int64_t base_pts_us = 0;
    int64_t boundary_pts_us;
    int64_t seq;
    int found = 0;
    int torn = 0;

    if (appended > retired) {
        int64_t index = appended - 1;
        while (index >= retired) {
            int32_t slot = (int32_t)(index % KPRT_MAX_SEGMENTS);
            int64_t opening = atomic_load_explicit(&ring->segments[slot].seq, memory_order_acquire);
            int64_t frame;
            int64_t pts;
            int64_t closing;

            if ((opening & 1) != 0) {
                torn = 1;
                break;
            }
            frame = atomic_load_explicit(&ring->segments[slot].start_frame, memory_order_relaxed);
            pts = atomic_load_explicit(&ring->segments[slot].pts_us, memory_order_relaxed);
            atomic_thread_fence(memory_order_acquire);
            closing = atomic_load_explicit(&ring->segments[slot].seq, memory_order_relaxed);
            if (closing != opening) {
                torn = 1;
                break;
            }
            /* Walk newest first. The oldest live segment is used even when it starts after this
             * frame: the frames written before the first timestamp arrived are dated by
             * continuity backwards from it, which is what `index > retired` stops short of. */
            if (index > retired && frame > last_real_frame) {
                index--;
                continue;
            }
            base_frame = frame;
            base_pts_us = pts;
            found = 1;
            break;
        }
    }

    if (found) {
        atomic_store_explicit(&ring->cache_base_frame, base_frame, memory_order_relaxed);
        atomic_store_explicit(&ring->cache_base_pts_us, base_pts_us, memory_order_relaxed);
        atomic_store_explicit(&ring->cache_valid, 1, memory_order_relaxed);
    } else {
        /* A torn read is counted so the degradation is visible rather than silent. The case where
         * no segment exists at all is not a give-up: it is the honest "nothing has been dated
         * yet" state, and the Kotlin ring publishes nothing there too. */
        if (torn)
            kprt_counter_bump(&ring->segment_giveups);
        if (!atomic_load_explicit(&ring->cache_valid, memory_order_relaxed))
            return;
        base_frame = atomic_load_explicit(&ring->cache_base_frame, memory_order_relaxed);
        base_pts_us = atomic_load_explicit(&ring->cache_base_pts_us, memory_order_relaxed);
    }

    boundary_pts_us = add_saturating(base_pts_us,
        kprt_frames_to_micros(last_real_frame + 1 - base_frame, ring->sample_rate));

    /* Seqlock write with the real-time thread as the writer, so it never waits for its reader.
     * Two sequence stores, three payload stores, one release fence on each side of the payload. */
    seq = atomic_load_explicit(&ring->anchor_seq, memory_order_relaxed);
    atomic_store_explicit(&ring->anchor_seq, seq + 1, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&ring->anchor_pts_us, boundary_pts_us, memory_order_relaxed);
    atomic_store_explicit(&ring->anchor_nanos, at_nanos, memory_order_relaxed);
    atomic_store_explicit(&ring->anchor_valid, 1, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&ring->anchor_seq, seq + 2, memory_order_relaxed);
}

/* The saturator the gain walk folds through above unity.
 *
 * Identity up to the knee, then a rational fold that approaches full scale without ever reaching
 * it: the excess over the knee is mapped through x/(1+x), which is 0 at the knee and tends to 1
 * however hard it is driven. One divide, no libm call, no branch on the hot path beyond the sign,
 * which is what lets it live inside the device callback the render audit polices.
 *
 * Written as separate statements ON PURPOSE. `knee + (1 - knee) * folded` in one expression is a
 * multiply-add a compiler may contract into an FMA, and the Kotlin twin will not be contracted the
 * same way; the differential oracle compares raw bits, so one fused instruction is a failing row.
 * The build also passes -ffp-contract=off for the same reason, and that flag is the guarantee while
 * this shape is the reminder.
 *
 * Identical in order and arithmetic to `KotlinAudioRing.softClip`. */
static float kprt_soft_clip(float x)
{
    const float knee = 0.75f;
    float mag = x < 0.0f ? -x : x;
    float excess;
    float folded;
    if (mag <= knee)
        return x;
    excess = (mag - knee) / (1.0f - knee);
    folded = excess / (1.0f + excess);
    folded = (1.0f - knee) * folded;
    folded = knee + folded;
    return x < 0.0f ? -folded : folded;
}

int32_t kprt_ring_render(kprt_ring *ring, float *destination, int32_t frames, int64_t deadline_nanos)
{
    int64_t start;
    int64_t written;
    int64_t available;
    int32_t to_read;
    int32_t channels;

    if (ring == NULL || destination == NULL || frames <= 0)
        return 0;

    channels = ring->channels;
    start = atomic_load_explicit(&ring->consumed, memory_order_relaxed);
    /* Acquire, and this is the one that matters: it is paired with the release on `written` in
     * commit, so seeing a new value here guarantees seeing the samples and the segment too. */
    written = atomic_load_explicit(&ring->written, memory_order_acquire);
    available = written - start;
    if (available < 0)
        available = 0;
    to_read = (int64_t)frames < available ? frames : (int32_t)available;

    if (to_read > 0) {
        int32_t head = (int32_t)(start % ring->capacity_frames);
        int32_t first = ring->capacity_frames - head;
        if (first > to_read)
            first = to_read;
        memcpy(destination,
               ring->data + (size_t)head * (size_t)channels,
               (size_t)first * (size_t)channels * sizeof(float));
        if (to_read > first) {
            memcpy(destination + (size_t)first * (size_t)channels,
                   ring->data,
                   (size_t)(to_read - first) * (size_t)channels * sizeof(float));
        }
        /* The gain, applied to the frames actually rendered and to nothing else. Silence written
         * below stays exact zeroes, and the walk does not advance across it: letting an underrun
         * move the ramp would make the gain depend on how badly the feeder was starved.
         *
         * Identical in order and arithmetic to `KotlinAudioRing.applyGain`, because the
         * differential oracle compares the samples. */
        {
            uint32_t bits = atomic_load_explicit(&ring->gain_target_bits, memory_order_relaxed);
            float wanted;
            float gain = ring->gain_current;
            int32_t total = to_read * channels;
            int32_t i;
            memcpy(&wanted, &bits, sizeof(wanted));
            if (ring->gain_started == 0) {
                ring->gain_started = 1;
                ring->gain_current = wanted;
                gain = wanted;
            }
            if (gain == wanted) {
                if (wanted > 1.0f) {
                    /* Boosting. Fold, so a loud passage cannot leave here squared off. */
                    for (i = 0; i < total; i++) {
                        destination[i] *= wanted;
                        destination[i] = kprt_soft_clip(destination[i]);
                    }
                } else if (wanted != 1.0f) {
                    for (i = 0; i < total; i++)
                        destination[i] *= wanted;
                }
            } else {
                int32_t frame;
                int32_t base = 0;
                float slope = ring->gain_slope;
                for (frame = 0; frame < to_read; frame++) {
                    if (gain < wanted) {
                        gain += slope;
                        if (gain > wanted)
                            gain = wanted;
                    } else {
                        gain -= slope;
                        if (gain < wanted)
                            gain = wanted;
                    }
                    /* Per FRAME and not per buffer: a walk that crosses unity must leave the frames
                     * below it exactly as they were and fold only the ones above. */
                    if (gain > 1.0f) {
                        for (i = 0; i < channels; i++) {
                            destination[base + i] *= gain;
                            destination[base + i] = kprt_soft_clip(destination[base + i]);
                        }
                    } else {
                        for (i = 0; i < channels; i++)
                            destination[base + i] *= gain;
                    }
                    base += channels;
                }
                ring->gain_current = gain;
            }
        }
        atomic_store_explicit(&ring->consumed, start + to_read, memory_order_release);
    }

    if (to_read < frames) {
        /* Exact zeroes, and the counter moves only when the feeder has not said the stream is
         * ending. This is the ONLY place in the whole player
         * where audio silence is written or an underrun is counted. The sink used to do both again
         * as a last line, over a callback that could be absent; there is no absent callback now. */
        memset(destination + (size_t)to_read * (size_t)channels,
               0,
               (size_t)(frames - to_read) * (size_t)channels * sizeof(float));
        if (atomic_load_explicit(&ring->ending, memory_order_relaxed) == 0)
            kprt_counter_bump(&ring->underruns);
    }

    if (to_read > 0) {
        /* The request's last frame lands at `deadline_nanos`. Our last real frame is earlier by
         * however much of the request was silence. Double arithmetic, identical expression to
         * KotlinAudioRing's, because the oracle compares the published instant exactly. */
        int64_t boundary_nanos = sub_saturating(deadline_nanos,
            (int64_t)((double)(frames - to_read) * ring->nanos_per_frame));
        publish_anchor(ring, start + to_read - 1, boundary_nanos);
    }

    return to_read;
}

/* ---- The device callback's body ---- */

int64_t kprt_sink_ticks_to_nanos(const kprt_sink *sink, uint64_t ticks)
{
    uint64_t numer;
    uint64_t denom;
    uint64_t whole;
    uint64_t rest;

    if (sink == NULL)
        return 0;
    numer = (uint64_t)sink->timebase_numer;
    denom = (uint64_t)sink->timebase_denom;
    if (numer == 0 || denom == 0)
        return (int64_t)ticks;
    if (numer == denom)
        return (int64_t)ticks;

    /* Divide first, exactly as `kprt_frames_to_micros` does. `ticks * numer` overflows 64 bits at
     * uptimes this machine reaches in weeks, and a wrapped deadline would move the audio clock by
     * centuries rather than by microseconds. `rest * numer` cannot overflow because rest is below
     * denom and both fit in 32 bits. */
    whole = ticks / denom;
    rest = ticks % denom;
    return (int64_t)(whole * numer + rest * numer / denom);
}

/* `frames` sample frames at `rate`, in nanoseconds, exact and truncated toward zero.
 *
 * The same split rescale as `kprt_frames_to_micros`, for the same reason, and integer rather than
 * the ring's double because this value dates the anchor the whole media clock is built on. */
static int64_t frames_to_nanos(int32_t frames, int32_t rate)
{
    int64_t whole;
    int64_t rest;

    if (rate <= 0 || frames <= 0)
        return 0;
    whole = (int64_t)frames / (int64_t)rate;
    rest = (int64_t)frames % (int64_t)rate;
    return whole * 1000000000LL + rest * 1000000000LL / (int64_t)rate;
}

int32_t kprt_render_into(kprt_sink *sink, float *destination, int32_t frames,
                         uint64_t host_ticks, int32_t host_ticks_estimated)
{
    kprt_ring *ring;
    int64_t deadline;
    int32_t real;

    if (sink == NULL || destination == NULL || frames <= 0)
        return 0;

    /* Acquire, paired with the release in `kprt_sink_attach_ring`: a callback that sees a ring here
     * sees every field of a fully constructed one. */
    ring = atomic_load_explicit(&sink->ring, memory_order_acquire);
    kprt_counter_bump(&sink->callbacks);

    if (ring == NULL) {
        /* Teardown, and the only case left in which the device buffer is zeroed from outside
         * `kprt_ring_render`. An unwritten device buffer plays whatever was
         * left in it, which is a burst of noise, so the whole thing goes to exact zeroes. */
        memset(destination, 0, (size_t)frames * (size_t)sink->channels * sizeof(float));
        kprt_counter_bump(&sink->zero_filled_callbacks);
        return 0;
    }

    if (host_ticks_estimated != 0)
        kprt_counter_bump(&sink->estimated_anchors);

    /* The device's timestamp is when the FIRST frame of this buffer reaches it; the anchor wants the
     * instant its LAST frame becomes audible, which is one buffer later. No device latency is
     * subtracted anywhere, because the deadline already accounts for it: that is the whole reason
     * the callback takes a time at all rather than the engine estimating one. */
    deadline = kprt_sink_ticks_to_nanos(sink, host_ticks) +
        frames_to_nanos(frames, sink->sample_rate);

    /* The deadline publishes BEFORE the render consumes the ring, with release. A
     * drain polls "ring empty?" and then reads this deadline; with the old order (store after
     * the consume, relaxed) it could pair an empty ring with the PREVIOUS callback's deadline
     * and declare the audio finished one buffer early. Store-first plus release means any
     * observer that sees this callback's consumption also sees this callback's deadline. */
    atomic_store_explicit(&sink->last_deadline_nanos, deadline, memory_order_release);

    real = kprt_ring_render(ring, destination, frames, deadline);
    return real;
}

void kprt_sink_note_span(kprt_sink *sink, uint64_t entered_ticks, uint64_t left_ticks)
{
    int64_t spent;
    int64_t worst;

    if (sink == NULL || left_ticks < entered_ticks)
        return;
    spent = kprt_sink_ticks_to_nanos(sink, left_ticks - entered_ticks);
    worst = atomic_load_explicit(&sink->worst_callback_nanos, memory_order_relaxed);
    /* A plain compare and store, not a compare-and-swap loop. One writer, the device thread, so
     * there is nothing to lose a race to, and a bounded store is what a real-time path wants. */
    if (spent > worst)
        atomic_store_explicit(&sink->worst_callback_nanos, spent, memory_order_relaxed);
}
