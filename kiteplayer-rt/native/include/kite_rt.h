/* KitePlayer real-time audio primitives, in C.
 *
 * This header is the whole public surface of `kiteplayer-rt`. It has two subjects. First, a
 * single-producer single-consumer ring of interleaved float samples that also dates those samples
 * on the media timeline and publishes the audio clock's anchor; that part includes only
 * <stdint.h>, so it compiles for every Kotlin/Native target KitePlayer declares. Second, added in
 * B1.8, the audio device itself: the render callback and the whole AudioUnit lifecycle, so that no
 * Kotlin code is on the device's real-time thread and no Kotlin code touches an `AudioUnit`. The
 * device half is declared unconditionally and IMPLEMENTED ON macOS ONLY; everywhere else its
 * entry points answer KPRT_SINK_UNSUPPORTED_PLATFORM rather than failing to link, and a
 * declaration that answers "not here" is not a support claim.
 *
 * WHY THIS EXISTS AT ALL. `kiteplayer-core` already has a correct ring, in Kotlin, at
 * `internal/KotlinAudioRing.kt`. The problem it cannot solve is that on macOS the device's
 * real-time thread enters managed Kotlin on its first instruction, so it becomes a mutator the
 * garbage collector has to stop at a safepoint (register item B1-17). The fix is a callback that
 * never leaves C, and a callback that never leaves C needs a ring that lives in C. In B1.7 this
 * library was built, tested and proved against the Kotlin ring while deliberately NOT on the
 * device path; B1.8 puts it there, which is the one and only time the shipped real-time audio path
 * changes.
 *
 * WHAT REPLACES THE KOTLIN RING, AND WHAT NEVER WILL. Nothing deletes `KotlinAudioRing`.
 * `kiteplayer-core`'s `commonMain` targets js and wasmJs, which can never contain C, and the
 * Kotlin ring is the only oracle this code can be checked against. Two implementations of one
 * contract therefore exist forever (register item B1-20), and the differential oracle at
 * `kiteplayer-core/src/nativeTest/kotlin/.../AudioRingDifferentialTest.kt` is the only thing that
 * keeps them from drifting apart.
 *
 * THREADING CONTRACT, clause by clause, because every clause is load bearing.
 *
 *  - One producer thread, the audio feeder. It owns `kprt_ring_begin_write`,
 *    `kprt_ring_commit_write`, and the segment ring behind them.
 *  - One consumer thread, the device's real-time callback. It owns `kprt_ring_render` and the
 *    anchor it publishes. It never allocates, never takes a lock, never waits and never blocks.
 *  - Any thread may call `kprt_ring_anchor`, `kprt_ring_underruns`,
 *    `kprt_ring_buffered_frames`, `kprt_ring_free_frames`, `kprt_ring_segment_giveups`,
 *    `kprt_ring_anchor_giveups` and `kprt_ring_read_stats`, one at a time, AND ONLY WHILE THE RING IS
 *    ALIVE. In KitePlayer the anchor reader is serialised by `AudioPlayback`'s own lock, and the same
 *    lock is what orders those readers against the teardown that frees the ring: reading a ring
 *    another thread is destroying is a use-after-free that no amount of atomics inside the ring can
 *    prevent, and the B1.8 verification proved it with AddressSanitizer rather than arguing it.
 *  - `kprt_ring_flush` and `kprt_ring_destroy` require both sides quiescent: the callback is
 *    provably out of `kprt_ring_render` and the feeder is not between a begin and a commit. This
 *    is a precondition and not advice, because flush writes the consumer's own counter and drops
 *    the segments the consumer dates its anchor from.
 *
 * WHO WAITS FOR WHOM. The Kotlin ring publishes its segment ring under one sequence counter
 * whose writer is the feeder and whose reader is the real-time thread, so the real-time thread
 * spins with no bound whenever the feeder is preempted mid-update. That is register item B1-16,
 * a priority inversion on a real-time thread, and it has nothing to do with the language: a
 * transliteration would reproduce it. This implementation inverts every such relationship:
 *
 *  - The anchor seqlock's WRITER is the real-time thread, which never waits. Its reader retries
 *    a bounded 64 times and then keeps its previous reading, counted in
 *    `kprt_ring_anchor_giveups`.
 *  - The segment ring carries one sequence number PER SLOT. The real-time thread validates each
 *    slot it reads and never retries: when a read is torn it dates the anchor from a
 *    consumer-private cache instead, and counts that in `kprt_ring_segment_giveups`. A give-up
 *    is visible rather than silent. It publishes nothing, and the clock reads null, only when
 *    the cache is empty too.
 *
 * ALLOCATION. `kprt_ring_create` performs exactly one `malloc` and `kprt_ring_destroy` exactly one
 * `free`. `kprt_sink_create` performs one more and `kprt_sink_destroy` releases it. Those four
 * functions are the only ones in this library that touch an allocator at all: nothing on the
 * real-time path does, which `native/tests/test_ring_alloc.c` and
 * `native/tests/test_sink_callback.c` assert under an allocation interposer rather than by
 * argument, and which `native/scripts/render-audit.sh` proves again without running anything, by
 * reading the shipped object's own symbol table.
 *
 * ARITHMETIC. Frame counts become microseconds through `kprt_frames_to_micros`, which divides
 * before it multiplies. The obvious form, `frames * 1000000 / rate`, overflows a 64 bit
 * intermediate at large frame deltas; that is register item B1-18, the same shape recorded
 * against KiteCodec's timestamp helpers as defect D9. The Kotlin ring had it too and was
 * corrected in the same sub-phase, so the differential oracle compares two correct
 * implementations rather than two matching bugs.
 */

#ifndef KITE_RT_H
#define KITE_RT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Compiled with -fvisibility=hidden, so every entry point states its own linkage. Everything
 * without this marker is internal to the library whatever the linker could technically reach. */
#if defined(_WIN32)
#define KPRT_API
#else
#define KPRT_API __attribute__((visibility("default")))
#endif

/* Timestamp segments held at once.
 *
 * Four is what can be in flight: the segment the device is playing, the one it is about to
 * reach, and a discontinuity written on top of both. It is also exactly
 * `KotlinAudioRing.MAX_SEGMENTS`, and it has to stay exactly that, because the differential
 * oracle drives a case in which a fifth segment is refused and both rings must refuse it on the
 * same write. */
#define KPRT_MAX_SEGMENTS 4

/* How far a timestamp may drift from continuity before a segment of its own is opened. Must
 * equal `KotlinAudioRing.DISCONTINUITY_TOLERANCE_US`, for the same reason. */
#define KPRT_DISCONTINUITY_TOLERANCE_US 1000

/* Bounded retry budget for the non-real-time anchor reader. See `kprt_ring_anchor`. */
#define KPRT_ANCHOR_READ_ATTEMPTS 64

/* `kprt_ring_commit_write` verdicts. */
enum {
    /* The samples are published and visible to the next render. */
    KPRT_COMMIT_PUBLISHED = 0,
    /* The timestamp needed a segment of its own and all four still date audio the device has
     * not played. Nothing was published; the caller retries the same samples later, which is
     * what `AudioPlayback.submit` already does when the Kotlin ring returns zero. */
    KPRT_COMMIT_NEEDS_SEGMENT = 1,
    /* The arguments did not match the outstanding reservation. A programming error, reported
     * rather than ignored. */
    KPRT_COMMIT_BAD_ARGUMENT = 2
};

/* Opaque. The layout lives in src/kite_rt_ring_internal.h, which only this library and its own
 * tests include, so the shipped header promises no field and no size. */
typedef struct kprt_ring kprt_ring;

/* What the real-time thread published: the media time at the playhead boundary, and the instant
 * that boundary is reached on the same monotonic clock the engine uses.
 *
 * The timestamp is a boundary between two samples, not a sample's own start. Everything before
 * it has been heard by `audible_at_nanos` and nothing after it has. Pairing a sample's start
 * timestamp with the moment it finishes instead is one sample period of fixed error in the same
 * direction for a whole session. */
typedef struct {
    int64_t pts_us;
    int64_t audible_at_nanos;
    /* 0 before the device has played anything, and after a flush. A zero here is a normal
     * answer and not an error: it is what the clock says between a seek and the first audio of
     * the new position. */
    int32_t valid;
    /* 1 when the bounded reader exhausted its retries and this reading is its previous one
     * rather than a fresh one. Reported so a caller can see the degradation instead of
     * guessing; `kprt_ring_anchor_giveups` counts how often it happened. */
    int32_t from_cache;
} kprt_anchor;

/* A granted write reservation: up to two spans of ring storage the caller writes its
 * interleaved floats straight into.
 *
 * Two spans and not one because the ring wraps. `first_frames + second_frames` equals the
 * granted frame count, and `second` is NULL whenever `second_frames` is zero.
 *
 * Writing into ring storage directly is what removes one full copy of every sample: the Kotlin
 * ring's `write` copies from the caller's array into `data`, and a Kotlin caller of that has
 * usually just copied into its own array first. */
typedef struct {
    float *first;
    int32_t first_frames;
    float *second;
    int32_t second_frames;
    /* The absolute frame index the reservation starts at, for a caller that wants to date it. */
    int64_t start_frame;
} kprt_ring_write_window;

/* Everything a diagnostic wants, read in one call so a caller does not have to make six.
 * Consistent field by field, not as a snapshot: the counters are read one after another while
 * both sides keep running. */
typedef struct {
    int64_t written_frames;
    int64_t consumed_frames;
    int64_t underruns;
    int64_t segment_giveups;
    int64_t anchor_giveups;
    int64_t segments_appended;
    int64_t segments_retired;
    int32_t buffered_frames;
    int32_t free_frames;
    int32_t capacity_frames;
    int32_t channels;
    int32_t sample_rate;
    int32_t ending;
} kprt_ring_stats;

/* ---- Lifecycle ---- */

/* Creates a ring for `channels` interleaved float channels at `sample_rate`, holding
 * `capacity_frames` sample frames.
 *
 * One `malloc`, sized for the header and the sample block together, with the sample block
 * placed at a 64 byte boundary inside it. Returns NULL when an argument is out of range or the
 * allocation fails; every other function tolerates a NULL ring by doing nothing, so a caller
 * that ignores this cannot corrupt anything.
 *
 * Not real-time safe, and the only function here that is not. */
KPRT_API kprt_ring *kprt_ring_create(int32_t sample_rate, int32_t channels, int32_t capacity_frames);

/* Releases the ring. One `free`. Requires both sides quiescent. Tolerates NULL. */
KPRT_API void kprt_ring_destroy(kprt_ring *ring);

/* ---- The producer side ---- */

/* Grants a window of at most `frames` frames of ring storage, and returns how many frames it
 * granted, which is fewer than `frames` when the ring is nearly full and zero when it is full.
 *
 * A granted window stays valid until the matching `kprt_ring_commit_write`. Nothing is visible
 * to the consumer until that commit, so a caller that gives up between the two loses only the
 * bytes it wrote into storage the consumer cannot reach.
 *
 * Calling this twice without a commit in between replaces the reservation, which is a
 * programming error rather than a supported idiom; the second call reports the same window. */
KPRT_API int32_t kprt_ring_begin_write(kprt_ring *ring, int32_t frames, kprt_ring_write_window *out);

/* Publishes `frames` frames of the outstanding reservation, and dates them when `has_pts` is
 * non-zero.
 *
 * `frames` may be fewer than the granted count, and must never be more.
 *
 * A timestamp opens a segment of its own only when it disagrees with what continuity predicts
 * by at least KPRT_DISCONTINUITY_TOLERANCE_US. Opening one per buffer instead would make the
 * clock follow the jitter in the container's timestamps.
 *
 * @return one of the KPRT_COMMIT_* verdicts. On KPRT_COMMIT_NEEDS_SEGMENT nothing at all was
 *         published and the reservation stays outstanding, so the caller may simply retry the
 *         commit after the device has consumed something. */
KPRT_API int32_t kprt_ring_commit_write(kprt_ring *ring, int32_t frames, int32_t has_pts, int64_t pts_us);

/* Tells the ring that the feeder has finished, so trailing silence is the end of the media
 * rather than a failure to keep up and is not counted as an underrun. Without this distinction
 * every file reports a handful of underruns as it finishes, which makes the counter useless for
 * spotting the real thing. */
KPRT_API void kprt_ring_mark_ending(kprt_ring *ring);

/* ---- The real-time side ---- */

/* Fills `frames` frames of `destination` from the ring, and publishes the anchor.
 *
 * Real-time safe in the strong sense: no allocation, no lock, no syscall, no unbounded loop and
 * no library call other than `memcpy` and `memset`.
 *
 * When the ring runs dry the remainder of `destination` is set to exact zeroes and an underrun
 * is counted, unless the feeder marked the stream ending. A starved device must still be fed
 * something, because stopping would click.
 *
 * @param deadline_nanos when the LAST frame of the whole request becomes audible, on the
 *        engine's monotonic clock. This is what the audio clock is anchored to, and it is the
 *        reason the callback takes a time at all. No device latency is subtracted anywhere,
 *        because the deadline already accounts for it.
 * @return frames of real audio written. The rest of `destination` is silence. */
KPRT_API int32_t kprt_ring_render(kprt_ring *ring, float *destination, int32_t frames, int64_t deadline_nanos);

/* ---- Readers ---- */

/* Reads the last published anchor into `out`.
 *
 * Bounded: at most KPRT_ANCHOR_READ_ATTEMPTS attempts, after which it reports its previous
 * reading with `from_cache` set and increments `kprt_ring_anchor_giveups`. The real-time writer
 * is never made to wait for this, which is the whole point of the inversion. */
KPRT_API void kprt_ring_anchor(kprt_ring *ring, kprt_anchor *out);

KPRT_API int32_t kprt_ring_buffered_frames(const kprt_ring *ring);
KPRT_API int32_t kprt_ring_free_frames(const kprt_ring *ring);
KPRT_API int32_t kprt_ring_capacity_frames(const kprt_ring *ring);
KPRT_API int32_t kprt_ring_channels(const kprt_ring *ring);
KPRT_API int32_t kprt_ring_sample_rate(const kprt_ring *ring);
KPRT_API int64_t kprt_ring_underruns(const kprt_ring *ring);
KPRT_API int64_t kprt_ring_written_frames(const kprt_ring *ring);
KPRT_API int64_t kprt_ring_consumed_frames(const kprt_ring *ring);

/* How often the real-time segment walk read a torn slot and dated the anchor from its private
 * cache instead. Zero on a healthy system; a non-zero value is the visible form of a hazard
 * that the Kotlin ring answers by spinning without a bound. */
KPRT_API int64_t kprt_ring_segment_giveups(const kprt_ring *ring);

/* How often the non-real-time anchor reader exhausted its retry budget and reused its previous
 * reading. */
KPRT_API int64_t kprt_ring_anchor_giveups(const kprt_ring *ring);

KPRT_API void kprt_ring_read_stats(const kprt_ring *ring, kprt_ring_stats *out);

/* ---- Maintenance ---- */

/* Discards everything unplayed and invalidates the anchor. This is the seek path. Requires both
 * sides quiescent; see the threading contract at the top of this file. */
KPRT_API void kprt_ring_flush(kprt_ring *ring);

/* ---- Arithmetic, exported because both implementations of the contract must agree on it ---- */

/* `frames` sample frames at `sample_rate`, in microseconds, truncated toward zero.
 *
 * Divides before it multiplies, so the product that overflows in the naive form never exists.
 * Exact, not approximate: for frames = q * rate + r the answer is q * 1000000 + r * 1000000 /
 * rate, which is the same integer as the unbounded frames * 1000000 / rate.
 *
 * Returns 0 when `sample_rate` is not positive, matching `AudioFormat.durationOf`. */
KPRT_API int64_t kprt_frames_to_micros(int64_t frames, int32_t sample_rate);

/* ---- The audio device ----
 *
 * Everything below is implemented in `src/kite_rt_coreaudio.c` and exists so that the render
 * callback can be a `static` C function this header does not name, that Kotlin cannot reach and
 * does not install. Register item B1-17: the callback used to be a Kotlin lambda whose first
 * instruction dereferenced a `StableRef`, which made the device's real-time thread a mutator the
 * garbage collector has to stop at a safepoint. Worst stop-the-world pauses measured on the
 * development machine were 63 to 256 microseconds against a 10.67 millisecond period at 512 frames
 * and 48 kHz. The honest statement is not that audio glitched; it is that the deadline depended on
 * a pause nobody had bounded. It no longer does, because nothing managed is on that thread.
 *
 * The sink owns BOTH handles: the audio unit and the ring the callback reads. That is not tidiness.
 * `kprt_ring_flush` and `kprt_ring_destroy` require the callback to be provably out of
 * `kprt_ring_render`, and only the code that stops the device can promise that, so the code that
 * stops the device is the code that releases the ring.
 *
 * PLATFORM. Implemented for macOS. On every other target these functions are present and answer
 * KPRT_SINK_UNSUPPORTED_PLATFORM, so a caller gets a verdict instead of a link error and no
 * reader can mistake "the symbol exists" for "the device works there".
 *
 * THREADING. `kprt_sink_create`, `kprt_sink_attach_ring`, `kprt_sink_start`, `kprt_sink_stop`,
 * `kprt_sink_set_paused` and `kprt_sink_destroy` belong to one owner thread, which in KitePlayer is
 * the session owner. `kprt_sink_read_stats` and `kprt_sink_ring` are safe from any thread WHILE THE
 * SINK IS ALIVE, and that clause is not a formality: `kprt_sink_destroy` frees the sink and the ring,
 * so a caller that may read from another thread has to order those reads against the destroy itself.
 * Nothing in C can do that ordering for it, because the free is what the caller asked for. The
 * independent verification of B1.8 found the Kotlin side of this unordered and proved it with
 * AddressSanitizer, and `CoreAudioSink` now holds one lock across both, which is where the ordering
 * belongs. The device's real-time thread calls none of these functions.
 */

/* Opaque. The layout lives in src/kite_rt_sink_internal.h, for the same reason the ring's does. */
typedef struct kprt_sink kprt_sink;

/* Verdicts. Every one of them names the step that refused, because "the device would not open" with
 * no further detail is the least useful diagnostic in audio. The CoreAudio `OSStatus` behind a
 * refusal is reported separately, through the `out_os_status` parameters. */
enum {
    KPRT_SINK_OK = 0,
    KPRT_SINK_BAD_ARGUMENT = 1,
    /* Built for a platform whose device glue this library does not implement. Not a failure of the
     * machine it runs on: a statement about this library. */
    KPRT_SINK_UNSUPPORTED_PLATFORM = 2,
    KPRT_SINK_NO_COMPONENT = 3,
    KPRT_SINK_INSTANCE_REFUSED = 4,
    KPRT_SINK_FORMAT_REFUSED = 5,
    KPRT_SINK_CALLBACK_REFUSED = 6,
    KPRT_SINK_INITIALIZE_REFUSED = 7,
    KPRT_SINK_START_REFUSED = 8,
    KPRT_SINK_STOP_REFUSED = 9,
    KPRT_SINK_OUT_OF_MEMORY = 10,
    /* The ring itself refused the negotiated format or the requested capacity. */
    KPRT_SINK_RING_REFUSED = 11,
    KPRT_SINK_ALREADY_HAS_RING = 12
};

/* What the device accepted, reported by `kprt_sink_create` so the caller never has to assume it
 * got what it asked for. */
typedef struct {
    int32_t sample_rate;
    int32_t channels;
    /* The device's own period, in sample frames. What the engine sizes its ring against. */
    int32_t device_buffer_frames;
} kprt_sink_format;

/* Everything the sink can say about itself, read in one call.
 *
 * `worst_callback_nanos` is the measured wall time of the slowest single callback body, taken from a
 * `mach_absolute_time` pair around it. It is the number that says whether the real-time deadline is
 * being met: at 512 frames and 48 kHz the period is 10,666,666 ns and B10's budget is half of it. */
typedef struct {
    int64_t callbacks;
    /* Callbacks whose timestamp did not carry a valid host time, so the anchor was taken from the
     * clock instead. Counted rather than logged, because a real-time thread may not log. */
    int64_t estimated_anchors;
    /* Callbacks that found no ring and zeroed the whole buffer. After B1.8 that is teardown and
     * nothing else: register item B1-19 collapsed every other silence case into
     * `kprt_ring_render`. */
    int64_t zero_filled_callbacks;
    int64_t worst_callback_nanos;
    /* When the last frame of the most recently filled buffer becomes audible, on the same
     * monotonic clock the engine reads. This is the sink's whole contribution to the clock. */
    int64_t last_deadline_nanos;
    int32_t running;
    int32_t has_ring;
} kprt_sink_stats;

/* Opens the device and installs the render callback, transactionally.
 *
 * Either this returns KPRT_SINK_OK with a sink that owns an initialised audio unit, or it returns a
 * verdict having disposed everything it created. That is defect D23's transactional-open rule, moved
 * from Kotlin into C along with the device.
 *
 * The device is opened but NOT started, and no ring is attached yet, so a callback that arrives
 * before `kprt_sink_attach_ring` finds no ring and zeroes its buffer. The order exists because the
 * ring's capacity depends on the format the device accepted, which is only known once the device has
 * accepted it.
 *
 * @param out_sink   receives the sink on success and NULL otherwise. Required.
 * @param out_format receives the accepted format. Required, because a caller that ignored it would
 *                   be guessing at the rate its samples have to be in.
 * @param out_os_status receives the CoreAudio status behind a refusal, or 0. May be NULL. */
KPRT_API int32_t kprt_sink_create(int32_t sample_rate, int32_t channels, kprt_sink **out_sink,
                                  kprt_sink_format *out_format, int32_t *out_os_status);

/* Creates a ring at the accepted format and publishes it to the callback.
 *
 * The publication is a release store the callback reads with acquire, so a callback that sees the
 * ring sees a fully constructed one. */
KPRT_API int32_t kprt_sink_attach_ring(kprt_sink *sink, int32_t capacity_frames);

/* The ring the callback reads, or NULL. The caller does NOT own it: see the note above about who
 * may promise the callback is out. */
KPRT_API kprt_ring *kprt_sink_ring(const kprt_sink *sink);

KPRT_API int32_t kprt_sink_start(kprt_sink *sink, int32_t *out_os_status);

/* Stops the device. When this returns the render callback is out and will not be entered again
 * until a start, which is the precondition `kprt_ring_flush` claims. */
KPRT_API int32_t kprt_sink_stop(kprt_sink *sink, int32_t *out_os_status);

/* Stops or starts the unit without discarding anything, which is what a pause is on CoreAudio.
 * Returns KPRT_SINK_OK when the sink is now in the requested state. */
KPRT_API int32_t kprt_sink_set_paused(kprt_sink *sink, int32_t paused, int32_t *out_os_status);

/* Stops, uninitialises, disposes, and only then lets go of the ring.
 *
 * The order is the whole point: after the dispose no callback can be running, so clearing and
 * freeing the ring cannot race one. Tolerates NULL. */
KPRT_API void kprt_sink_destroy(kprt_sink *sink);

KPRT_API void kprt_sink_read_stats(const kprt_sink *sink, kprt_sink_stats *out);

#ifdef __cplusplus
}
#endif

#endif /* KITE_RT_H */
