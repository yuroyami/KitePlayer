/* The lock-free audio ring: its lifecycle, its producer side, its readers and its flush.
 *
 * The consumer side is NOT here. `kprt_ring_render` and the anchor it publishes live in
 * `kite_rt_render.c`, together with the device callback's body, and that file explains why the split
 * exists: the render unit's undefined symbol list is an assertion of plan section 15.2 B1.8, and a
 * unit holding `kprt_ring_create` has `_malloc` in that list. So this file is the one that may
 * allocate and the other is the one that may run on the device's thread, and no file is both.
 *
 * The Kotlin ring at `kiteplayer-core/src/commonMain/kotlin/.../internal/KotlinAudioRing.kt` is
 * the specification, not a suggestion: the differential oracle drives both through the same
 * scripted sequence and asserts the samples, the frame counts, the published anchor and the
 * underrun counter agree exactly. So every place where this file deliberately DIFFERS is marked,
 * and there are exactly three of them:
 *
 *  1. Who waits. The Kotlin ring makes the real-time thread the reader of a global segment
 *     sequence counter, so it spins with no bound while the feeder is preempted mid-update
 *     Here the segment ring carries one sequence per slot and the
 *     real-time walk never retries: a torn slot ends the walk, the anchor is dated from a
 *     consumer-private cache, and `segment_giveups` records it. The anchor seqlock's writer is
 *     the real-time thread and its reader gives up after a bounded 64 attempts.
 *  2. The writer's shape. `write(array, offset, frames, pts)` becomes
 *     begin, fill, commit, so the caller's floats land in ring storage once instead of twice.
 *  3. Silence and the sample move are `memset` and `memcpy` rather than scalar loops.
 *
 * Everything else, including the discontinuity tolerance, the four segment slots, the retirement
 * rule, the boundary convention and the back-dating of a silence tail, is the same decision made
 * the same way, because the oracle would catch it if it were not.
 *
 * "Would catch it" is a claim about the oracle's rows and not about its existence, and the
 * independent verification of B1.8 proved that by finding two places where it would not have. A
 * tolerance boundary of exactly 1000 microseconds was driven by no row, so `<` becoming `<=` here
 * passed all seven of its tests; and a zero-frame write with a timestamp spent a segment slot in the
 * Kotlin ring and not here, because that ring recorded the timestamp before it looked at the frame
 * count. Both are closed, on both sides, with rows that fail when the difference is reintroduced.
 * The lesson is worth keeping next to the list above: a difference is only "marked" if something
 * drives the input that shows it.
 */

#include "kite_rt_ring_internal.h"

#include <stdlib.h>
#include <string.h>

/* ---- Lifecycle ---- */

/* Rounds a byte count up to a multiple of `to`, which must be a power of two. */
#define KPRT_ALIGN_UP_SIZE(value, to) \
    ((((size_t)(value)) + ((size_t)(to) - 1u)) & ~((size_t)(to) - 1u))

/* Rounds a pointer up to a `to` byte boundary. Through `uintptr_t`, which is the only integer
 * type the standard guarantees can hold a pointer, so this is correct on 32 bit targets too. */
#define KPRT_ALIGN_UP_PTR(pointer, to) \
    ((void *)((((uintptr_t)(pointer)) + ((uintptr_t)(to) - 1u)) & ~((uintptr_t)(to) - 1u)))

kprt_ring *kprt_ring_create(int32_t sample_rate, int32_t channels, int32_t capacity_frames)
{
    size_t header_bytes;
    size_t sample_bytes;
    size_t total_bytes;
    void *block;
    char *aligned;
    kprt_ring *ring;

    if (sample_rate <= 0 || channels <= 0 || capacity_frames <= 0)
        return NULL;
    /* Cheap sanity bounds on the factors. 1 << 27 frames is over 46 minutes of 48 kHz audio, so
     * neither bound costs anything real. What these do NOT do, and were wrongly documented as
     * doing until the interlude, is remove the overflow of the byte-count multiply: that
     * reasoning held only for a 64 bit size_t, and four of the seventeen shipped targets have a
     * 32 bit one, where an admitted pair like (1 << 27 frames, 32 channels) wraps the byte count
     * and the first ordinary fill writes past the allocation. The PRODUCT bound below is the
     * memory safety; the three _Static_asserts after it prove the arithmetic at every target's
     * own pointer width at compile time, in the build itself. A new target's pointer width must
     * be checked against this guard; CompileKiteRtTask.specFor's comment says so. */
    if (capacity_frames > (1 << 27) || channels > 64)
        return NULL;

    /* The byte-count guard must reserve EVERYTHING the allocation later adds on top of the
     * samples: the cacheline-aligned header (which is far larger than one cache line, the ring
     * carries several _Alignas(64) counters and the segment table) and the alignment pad handed
     * to malloc. Reserving only two cache lines admitted near-boundary capacities, e.g.
     * ((1<<24)-1 frames, 64 ch): sample_bytes = SIZE_MAX-255 on 32 bit, which passed the old
     * guard and then wrapped total_bytes, so the first fill wrote past a tiny allocation. The
     * factor product itself cannot overflow uint64_t: the bounds above cap it at 2^35. */
    header_bytes = KPRT_ALIGN_UP_SIZE(sizeof(kprt_ring), KPRT_CACHELINE);
    if ((uint64_t)capacity_frames * (uint64_t)channels * sizeof(float)
            > (uint64_t)SIZE_MAX - (uint64_t)header_bytes - (uint64_t)KPRT_CACHELINE)
        return NULL;

    /* The guard above, proved at this target's own width: vectors the review measured wrapping
     * on the 32 bit targets must be refused there and admitted on 64 bit. The expressions mirror
     * the guard exactly; if the guard's arithmetic ever changes, these change with it or the
     * build stops. */
#define KPRT_GUARD_LIMIT                                                            \
    ((uint64_t)SIZE_MAX - KPRT_ALIGN_UP_SIZE(sizeof(kprt_ring), KPRT_CACHELINE)     \
        - (uint64_t)KPRT_CACHELINE)
    _Static_assert(
        (uint64_t)(1 << 27) * 8u * sizeof(float) > KPRT_GUARD_LIMIT
            ? sizeof(size_t) == 4 : sizeof(size_t) >= 8,
        "the (1<<27, 8) byte count must overflow exactly on 32 bit size_t");
    _Static_assert(
        (uint64_t)(1 << 27) * 32u * sizeof(float) > KPRT_GUARD_LIMIT
            ? sizeof(size_t) == 4 : sizeof(size_t) >= 8,
        "the (1<<27, 32) byte count must overflow exactly on 32 bit size_t");
    _Static_assert(
        (uint64_t)(1 << 24) * 64u * sizeof(float) > KPRT_GUARD_LIMIT
            ? sizeof(size_t) == 4 : sizeof(size_t) >= 8,
        "the (1<<24, 64) byte count must overflow exactly on 32 bit size_t");
    _Static_assert(
        (uint64_t)((1 << 24) - 1) * 64u * sizeof(float) > KPRT_GUARD_LIMIT
            ? sizeof(size_t) == 4 : sizeof(size_t) >= 8,
        "the near-boundary ((1<<24)-1, 64) byte count must be refused on 32 bit size_t");
#undef KPRT_GUARD_LIMIT

    sample_bytes = (size_t)capacity_frames * (size_t)channels * sizeof(float);
    total_bytes = header_bytes + sample_bytes;

    /* One allocation for the header and the samples together, over-sized by one cache line so
     * the header itself can start at a 64 byte boundary. `malloc` and not `posix_memalign` or
     * `aligned_alloc` because this library builds for mingw too, where neither exists under
     * those names; aligning by hand is portable and costs one add and one mask. */
    block = malloc(total_bytes + KPRT_CACHELINE);
    if (block == NULL)
        return NULL;
    aligned = (char *)KPRT_ALIGN_UP_PTR(block, KPRT_CACHELINE);
    memset(aligned, 0, total_bytes);

    ring = (kprt_ring *)(void *)aligned;
    ring->sample_rate = sample_rate;
    ring->channels = channels;
    ring->capacity_frames = capacity_frames;
    ring->data = (float *)(void *)(aligned + header_bytes);
    ring->block = block;
    ring->nanos_per_frame = 1000000000.0 / (double)sample_rate;
    /* Unity, walked at the same slope KotlinAudioRing computes from the same law: the ramp's
     * duration in frames at this rate, at least one so the slope is finite. `memset` above already
     * zeroed the target bits, and zero bits are 0.0f, so the target must be set explicitly. */
    ring->gain_current = 1.0f;
    ring->gain_slope = 1.0f / (float)kprt_gain_ramp_frames(sample_rate);
    kprt_ring_set_gain(ring, 1.0f);
    return ring;
}

int32_t kprt_gain_ramp_frames(int32_t sample_rate)
{
    int64_t frames;
    if (sample_rate <= 0)
        return 1;
    frames = (int64_t)sample_rate * KPRT_GAIN_RAMP_MICROS / 1000000;
    return frames < 1 ? 1 : (int32_t)frames;
}

void kprt_ring_set_gain(kprt_ring *ring, float target)
{
    uint32_t bits;
    if (ring == NULL)
        return;
    /* Clamped rather than refused: this is the real-time path's input and a caller that computed a
     * NaN must not be able to multiply every sample by one. The Kotlin side requires
     * 0..KPRT_GAIN_MAX at its own boundary, so a value arriving here outside it is already a bug
     * being contained. */
    if (!(target >= 0.0f))
        target = 0.0f;
    if (target > KPRT_GAIN_MAX)
        target = KPRT_GAIN_MAX;
    memcpy(&bits, &target, sizeof(bits));
    atomic_store_explicit(&ring->gain_target_bits, bits, memory_order_relaxed);
}

void kprt_ring_destroy(kprt_ring *ring)
{
    void *block;
    if (ring == NULL)
        return;
    block = ring->block;
    /* The header lives inside the block, so nothing may touch `ring` after this point. */
    free(block);
}

/* ---- The segment ring, written by the feeder only ---- */

/* Drops segments the device has played past.
 *
 * A segment is finished once the consumer has taken every frame it dates, which is what the next
 * segment's start says. The segment holding the last consumed frame stays: that is the one a
 * callback still inside render is dating its anchor from. The newest segment stays too, because
 * it dates everything still to be written, which is what `appended - still > 1` enforces.
 *
 * Feeder only, called when a new segment needs a slot, so the ring keeps its single writer. */
static void retire_consumed_segments(kprt_ring *ring)
{
    int64_t consumed_now = atomic_load_explicit(&ring->consumed, memory_order_acquire);
    int64_t appended = atomic_load_explicit(&ring->segments_appended, memory_order_relaxed);
    int64_t retired = atomic_load_explicit(&ring->segments_retired, memory_order_relaxed);
    int64_t still_needed = retired;

    while (appended - still_needed > 1) {
        int32_t next = (int32_t)((still_needed + 1) % KPRT_MAX_SEGMENTS);
        int64_t next_start = atomic_load_explicit(&ring->segments[next].start_frame, memory_order_relaxed);
        if (next_start >= consumed_now)
            break;
        still_needed++;
    }
    if (still_needed == retired)
        return;
    atomic_store_explicit(&ring->segments_retired, still_needed, memory_order_release);
}

/* @return 1 when the segment was appended, 0 when all four slots still date unplayed audio. */
static int append_segment(kprt_ring *ring, int64_t pts_us, int64_t at_frame)
{
    int64_t appended;
    int64_t retired;
    int64_t seq;
    int32_t slot;

    appended = atomic_load_explicit(&ring->segments_appended, memory_order_relaxed);
    retired = atomic_load_explicit(&ring->segments_retired, memory_order_relaxed);
    if (appended - retired >= KPRT_MAX_SEGMENTS) {
        retire_consumed_segments(ring);
        appended = atomic_load_explicit(&ring->segments_appended, memory_order_relaxed);
        retired = atomic_load_explicit(&ring->segments_retired, memory_order_relaxed);
    }
    if (appended - retired >= KPRT_MAX_SEGMENTS)
        return 0;

    slot = (int32_t)(appended % KPRT_MAX_SEGMENTS);
    seq = atomic_load_explicit(&ring->segments[slot].seq, memory_order_relaxed);

    /* Seqlock write, per slot. Odd first so a reader that catches us mid-update knows, then the
     * payload, then the closing even value with a release store so the payload is visible to
     * anybody who sees that value. */
    atomic_store_explicit(&ring->segments[slot].seq, seq + 1, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&ring->segments[slot].start_frame, at_frame, memory_order_relaxed);
    atomic_store_explicit(&ring->segments[slot].pts_us, pts_us, memory_order_relaxed);
    atomic_store_explicit(&ring->segments[slot].seq, seq + 2, memory_order_release);

    atomic_store_explicit(&ring->segments_appended, appended + 1, memory_order_release);
    return 1;
}

/* Is `pts_us` where continuity from `base_pts` plus `micros` says it should be, within the
 * tolerance?
 *
 * Written as its own function because of the overflow, which is the whole reason it is not one
 * expression. Both the sum and the difference are checked with `__builtin_*_overflow` rather than
 * computed and hoped for: signed overflow is undefined behaviour, and UBSan named this exact line
 * during the independent verification of B1.8 with
 * `-9223372036854774474 - 9223372036854775807 cannot be represented in type 'int64_t'`. Negating
 * the difference is checked too, because `-INT64_MIN` is undefined as well and an unchecked
 * negation there would return a negative "distance" that compares below every tolerance.
 *
 * Any of those three overflows means the two timestamps are further apart than any tolerance, so
 * the answer is a discontinuity, which opens a segment and dates the audio exactly. That is the
 * conservative direction: the alternative would be to treat an unrepresentable distance as
 * continuous and let the clock predict from a base 292,000 years away.
 *
 * `KotlinAudioRing.driftWithinTolerance` mirrors this decision for decision, including the
 * `Long.MIN_VALUE` case, so the differential oracle compares two implementations that agree on it
 * rather than two that both wrap. */
static int drift_within_tolerance(int64_t base_pts, int64_t micros, int64_t pts_us)
{
    int64_t predicted;
    int64_t drift;

    if (__builtin_add_overflow(base_pts, micros, &predicted))
        return 0;
    if (__builtin_sub_overflow(predicted, pts_us, &drift))
        return 0;
    if (drift == INT64_MIN)
        return 0;
    if (drift < 0)
        drift = -drift;
    return drift < KPRT_DISCONTINUITY_TOLERANCE_US;
}

/* Records where a media timestamp sits in the frame stream.
 *
 * A segment is opened only when the incoming timestamp disagrees with what continuity predicts,
 * which happens at the start, after a seek, and at a real gap in the audio. Opening one on every
 * buffer instead would make the clock follow the jitter in the container's timestamps.
 *
 * Continuity is predicted from the newest segment alone, because the frame this timestamp lands
 * on is never earlier than the one the newest segment starts at.
 *
 * @return 1 when the timestamp is accounted for, 0 when a segment was needed and none was free. */
static int record_timestamp(kprt_ring *ring, int64_t pts_us, int64_t at_frame)
{
    int64_t appended = atomic_load_explicit(&ring->segments_appended, memory_order_relaxed);
    int64_t retired = atomic_load_explicit(&ring->segments_retired, memory_order_relaxed);

    if (appended > retired) {
        int32_t newest = (int32_t)((appended - 1) % KPRT_MAX_SEGMENTS);
        int64_t base_frame = atomic_load_explicit(&ring->segments[newest].start_frame, memory_order_relaxed);
        int64_t base_pts = atomic_load_explicit(&ring->segments[newest].pts_us, memory_order_relaxed);
        int64_t micros = kprt_frames_to_micros(at_frame - base_frame, ring->sample_rate);
        if (drift_within_tolerance(base_pts, micros, pts_us))
            return 1;
    }
    return append_segment(ring, pts_us, at_frame);
}

/* ---- The producer side ---- */

int32_t kprt_ring_begin_write(kprt_ring *ring, int32_t frames, kprt_ring_write_window *out)
{
    int64_t written;
    int64_t consumed;
    int64_t room;
    int32_t granted;
    int32_t head;
    int32_t first;

    if (out == NULL)
        return 0;
    out->first = NULL;
    out->first_frames = 0;
    out->second = NULL;
    out->second_frames = 0;
    out->start_frame = 0;
    if (ring == NULL || frames <= 0)
        return 0;
    /* Interlude item I-04: a second begin while a reservation is outstanding is REFUSED, with
     * the outstanding reservation untouched. The old behaviour recomputed the grant, and was
     * measured publishing 768 samples of ring poison the caller never wrote when the second
     * grant was larger, and losing a filled buffer to KPRT_COMMIT_BAD_ARGUMENT when it was
     * smaller. Refusing beats clamping because a clamped form leaves a caller believing it
     * holds a window it does not. */
    if (atomic_load_explicit(&ring->has_pending, memory_order_relaxed))
        return 0;

    written = atomic_load_explicit(&ring->written, memory_order_relaxed);
    consumed = atomic_load_explicit(&ring->consumed, memory_order_acquire);
    room = (int64_t)ring->capacity_frames - (written - consumed);
    if (room <= 0) {
        atomic_store_explicit(&ring->has_pending, 0, memory_order_relaxed);
        atomic_store_explicit(&ring->pending_frames, 0, memory_order_relaxed);
        return 0;
    }
    granted = (int64_t)frames < room ? frames : (int32_t)room;

    head = (int32_t)(written % ring->capacity_frames);
    first = ring->capacity_frames - head;
    if (first > granted)
        first = granted;

    out->first = ring->data + (size_t)head * (size_t)ring->channels;
    out->first_frames = first;
    if (granted > first) {
        out->second = ring->data;
        out->second_frames = granted - first;
    }
    out->start_frame = written;

    atomic_store_explicit(&ring->pending_start_frame, written, memory_order_relaxed);
    atomic_store_explicit(&ring->pending_frames, granted, memory_order_relaxed);
    atomic_store_explicit(&ring->has_pending, 1, memory_order_relaxed);
    return granted;
}

int32_t kprt_ring_commit_write(kprt_ring *ring, int32_t frames, int32_t has_pts, int64_t pts_us)
{
    int64_t start;

    if (ring == NULL)
        return KPRT_COMMIT_BAD_ARGUMENT;
    if (!atomic_load_explicit(&ring->has_pending, memory_order_relaxed) || frames < 0 ||
        frames > atomic_load_explicit(&ring->pending_frames, memory_order_relaxed))
        return KPRT_COMMIT_BAD_ARGUMENT;

    start = atomic_load_explicit(&ring->pending_start_frame, memory_order_relaxed);
    if (frames == 0) {
        atomic_store_explicit(&ring->has_pending, 0, memory_order_relaxed);
        atomic_store_explicit(&ring->pending_frames, 0, memory_order_relaxed);
        return KPRT_COMMIT_PUBLISHED;
    }

    /* The timestamp is settled BEFORE `written` moves, exactly as the Kotlin ring settles it
     * before it copies. A refusal here must publish nothing at all, otherwise the consumer would
     * see samples no segment dates. */
    if (has_pts != 0 && !record_timestamp(ring, pts_us, start))
        return KPRT_COMMIT_NEEDS_SEGMENT;

    /* The release on `written` publishes both the samples the caller wrote into the window and
     * the segment that dates them, so a render that sees this value is guaranteed to see both. */
    atomic_store_explicit(&ring->written, start + frames, memory_order_release);
    atomic_store_explicit(&ring->has_pending, 0, memory_order_relaxed);
    atomic_store_explicit(&ring->pending_frames, 0, memory_order_relaxed);
    return KPRT_COMMIT_PUBLISHED;
}

void kprt_ring_mark_ending(kprt_ring *ring)
{
    if (ring == NULL)
        return;
    atomic_store_explicit(&ring->ending, 1, memory_order_relaxed);
}

/* ---- Readers ---- */

void kprt_ring_anchor(kprt_ring *ring, kprt_anchor *out)
{
    int attempt;

    if (out == NULL)
        return;
    out->pts_us = 0;
    out->audible_at_nanos = 0;
    out->valid = 0;
    out->from_cache = 0;
    if (ring == NULL)
        return;

    for (attempt = 0; attempt < KPRT_ANCHOR_READ_ATTEMPTS; attempt++) {
        int64_t opening = atomic_load_explicit(&ring->anchor_seq, memory_order_acquire);
        int64_t pts;
        int64_t nanos;
        int64_t closing;
        int32_t valid;

        if ((opening & 1) != 0)
            continue;
        pts = atomic_load_explicit(&ring->anchor_pts_us, memory_order_relaxed);
        nanos = atomic_load_explicit(&ring->anchor_nanos, memory_order_relaxed);
        valid = atomic_load_explicit(&ring->anchor_valid, memory_order_relaxed);
        atomic_thread_fence(memory_order_acquire);
        closing = atomic_load_explicit(&ring->anchor_seq, memory_order_relaxed);
        if (closing != opening)
            continue;

        atomic_store_explicit(&ring->reader_pts_us, pts, memory_order_relaxed);
        atomic_store_explicit(&ring->reader_nanos, nanos, memory_order_relaxed);
        atomic_store_explicit(&ring->reader_valid, valid, memory_order_relaxed);
        out->pts_us = pts;
        out->audible_at_nanos = nanos;
        out->valid = valid;
        return;
    }

    /* The budget is spent. Keep the previous reading rather than spinning on a real-time
     * thread's writes, and say so, both in the counter and in the reported struct. */
    atomic_fetch_add_explicit(&ring->anchor_giveups, 1, memory_order_relaxed);
    out->pts_us = atomic_load_explicit(&ring->reader_pts_us, memory_order_relaxed);
    out->audible_at_nanos = atomic_load_explicit(&ring->reader_nanos, memory_order_relaxed);
    out->valid = atomic_load_explicit(&ring->reader_valid, memory_order_relaxed);
    out->from_cache = 1;
}

int32_t kprt_ring_buffered_frames(const kprt_ring *ring)
{
    int64_t buffered;
    if (ring == NULL)
        return 0;
    buffered = atomic_load_explicit(&ring->written, memory_order_acquire) -
        atomic_load_explicit(&ring->consumed, memory_order_acquire);
    if (buffered < 0)
        buffered = 0;
    return (int32_t)buffered;
}

int32_t kprt_ring_free_frames(const kprt_ring *ring)
{
    if (ring == NULL)
        return 0;
    return ring->capacity_frames - kprt_ring_buffered_frames(ring);
}

int32_t kprt_ring_capacity_frames(const kprt_ring *ring)
{
    return ring == NULL ? 0 : ring->capacity_frames;
}

int32_t kprt_ring_channels(const kprt_ring *ring)
{
    return ring == NULL ? 0 : ring->channels;
}

int32_t kprt_ring_sample_rate(const kprt_ring *ring)
{
    return ring == NULL ? 0 : ring->sample_rate;
}

int64_t kprt_ring_underruns(const kprt_ring *ring)
{
    return ring == NULL ? 0 : atomic_load_explicit(&ring->underruns, memory_order_relaxed);
}

int64_t kprt_ring_written_frames(const kprt_ring *ring)
{
    return ring == NULL ? 0 : atomic_load_explicit(&ring->written, memory_order_acquire);
}

int64_t kprt_ring_consumed_frames(const kprt_ring *ring)
{
    return ring == NULL ? 0 : atomic_load_explicit(&ring->consumed, memory_order_acquire);
}

int64_t kprt_ring_segment_giveups(const kprt_ring *ring)
{
    return ring == NULL ? 0 : atomic_load_explicit(&ring->segment_giveups, memory_order_relaxed);
}

int64_t kprt_ring_anchor_giveups(const kprt_ring *ring)
{
    return ring == NULL ? 0 : atomic_load_explicit(&ring->anchor_giveups, memory_order_relaxed);
}

void kprt_ring_read_stats(const kprt_ring *ring, kprt_ring_stats *out)
{
    if (out == NULL)
        return;
    memset(out, 0, sizeof(*out));
    if (ring == NULL)
        return;
    out->written_frames = atomic_load_explicit(&ring->written, memory_order_acquire);
    out->consumed_frames = atomic_load_explicit(&ring->consumed, memory_order_acquire);
    out->underruns = atomic_load_explicit(&ring->underruns, memory_order_relaxed);
    out->segment_giveups = atomic_load_explicit(&ring->segment_giveups, memory_order_relaxed);
    out->anchor_giveups = atomic_load_explicit(&ring->anchor_giveups, memory_order_relaxed);
    out->segments_appended = atomic_load_explicit(&ring->segments_appended, memory_order_relaxed);
    out->segments_retired = atomic_load_explicit(&ring->segments_retired, memory_order_relaxed);
    out->buffered_frames = kprt_ring_buffered_frames(ring);
    out->free_frames = ring->capacity_frames - out->buffered_frames;
    out->capacity_frames = ring->capacity_frames;
    out->channels = ring->channels;
    out->sample_rate = ring->sample_rate;
    out->ending = atomic_load_explicit(&ring->ending, memory_order_relaxed);
}

/* ---- Maintenance ---- */

void kprt_ring_flush(kprt_ring *ring)
{
    int64_t seq;

    if (ring == NULL)
        return;

    atomic_store_explicit(&ring->ending, 0, memory_order_relaxed);

    /* Clear the anchor under its own seqlock, so even a caller that ignored the quiescence
     * precondition cannot read a half cleared anchor as a consistent one. */
    seq = atomic_load_explicit(&ring->anchor_seq, memory_order_relaxed);
    atomic_store_explicit(&ring->anchor_seq, seq + 1, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&ring->anchor_valid, 0, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&ring->anchor_seq, seq + 2, memory_order_relaxed);

    /* Both caches go with it. This is not tidiness: leaving either would let the next render
     * date fresh samples from the position that was abandoned, which is precisely the "clock
     * jumps to the new position a whole buffer early" symptom the segment ring exists to
     * prevent, and the Kotlin ring publishes nothing at all in that state. */
    atomic_store_explicit(&ring->reader_valid, 0, memory_order_relaxed);
    atomic_store_explicit(&ring->cache_valid, 0, memory_order_relaxed);

    atomic_store_explicit(&ring->segments_retired, 0, memory_order_relaxed);
    atomic_store_explicit(&ring->segments_appended, 0, memory_order_relaxed);

    atomic_store_explicit(&ring->consumed,
                          atomic_load_explicit(&ring->written, memory_order_relaxed),
                          memory_order_release);

    atomic_store_explicit(&ring->has_pending, 0, memory_order_relaxed);
    atomic_store_explicit(&ring->pending_frames, 0, memory_order_relaxed);
}
