/* What `kprt_sink_destroy` does when a teardown step refuses, with no audio unit.
 *
 * The teardown's ordering was already protected by `scripts/source-discipline.sh`, which reads the
 * source text: it checks that the ring is freed AFTER the unit is disposed, because getting that
 * backwards is the classic use-after-free in an audio teardown and every runtime instrument in this
 * repository passed with the defect in place. What no instrument covered is the branch under it.
 * When a step refuses, nothing proves the render callback is out, so destroy must leak the sink and
 * the ring deliberately, leave the ring published so a live callback keeps reading valid storage,
 * and say the teardown is unproven. That branch had no test at all: a host build has no audio unit
 * to make refuse, and the Kotlin suites drive a real device, which cannot be made to refuse either.
 *
 * The seam is `kprt_test_set_teardown_verdicts`, KPRT_TESTING only. It supplies the three verdicts
 * destroy reads from CoreAudio and skips the calls that would produce them, because the `unit` here
 * is a fabricated pointer. Everything under those verdicts, the ordering, the `quiesced`
 * bookkeeping and the fail-closed return, is the shipped code.
 */

#include "harness.h"

#include "../include/kite_rt.h"
#include "../src/kite_rt_sink_internal.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

enum { SAMPLE_RATE = 48000, CHANNELS = 2, CAPACITY = 256 };

/* A sink shaped like one `kprt_sink_create` returns, on the heap because destroy frees it. */
static kprt_sink *make_sink(int owns_ring)
{
    kprt_sink *sink = (kprt_sink *)calloc(1, sizeof(kprt_sink));
    kprt_ring *ring;
    if (sink == NULL)
        return NULL;
    ring = kprt_ring_create(SAMPLE_RATE, CHANNELS, CAPACITY);
    if (ring == NULL) {
        free(sink);
        return NULL;
    }
    sink->sample_rate = SAMPLE_RATE;
    sink->channels = CHANNELS;
    sink->timebase_numer = 1;
    sink->timebase_denom = 1;
    /* Never dereferenced: the seam replaces every call that would touch it. Non-NULL is what puts
     * destroy into the branch under test. */
    sink->unit = (void *)(uintptr_t)0xA0D10;
    sink->initialized = 1;
    sink->owns_ring = owns_ring;
    atomic_store(&sink->running, 1);
    atomic_store(&sink->ring, ring);
    return sink;
}

int main(void)
{
    kt_suite_begin("test_sink_teardown");

    kt_case("a teardown whose every step succeeds frees the ring and the sink");
    {
        kt_alloc_counts before;
        kprt_sink *sink = make_sink(1);
        KT_NOT_NULL(sink);
        kt_alloc_snapshot(&before);
        kprt_test_set_teardown_verdicts(1, 1, 1, 1);
        KT_EQ_INT(kprt_sink_destroy(sink), KPRT_SINK_OK);
        kprt_test_set_teardown_verdicts(0, 1, 1, 1);
        if (kt_alloc_active()) {
            /* The ring and the sink, both released: one free each, nothing allocated. */
            KT_EQ_I64(kt_alloc_live_delta(&before), -2);
        } else {
            kt_partial("allocation accounting is not live in this variant");
        }
        kt_detail("verdicts=ok,ok,ok result=OK");
    }

    kt_case("a refused stop leaves the ring published and the sink alive");
    {
        kprt_sink *sink = make_sink(1);
        kprt_ring *ring;
        KT_NOT_NULL(sink);
        kprt_test_set_teardown_verdicts(1, 0, 1, 1);
        KT_EQ_INT(kprt_sink_destroy(sink), KPRT_SINK_TEARDOWN_UNPROVEN);
        kprt_test_set_teardown_verdicts(0, 1, 1, 1);
        /* Nothing was freed and nothing was cleared: a callback still running reads valid storage
         * through a ring pointer that is still published. That is the whole point of the branch. */
        KT_NOT_NULL(sink->unit);
        ring = atomic_load(&sink->ring);
        KT_NOT_NULL(ring);
        KT_EQ_I64(kprt_ring_buffered_frames(ring), 0);
        /* The suite owns what destroy deliberately leaked. */
        kprt_ring_destroy(ring);
        free(sink);
        kt_detail("verdicts=refused,ok,ok result=UNPROVEN leaked=ring+sink");
    }

    kt_case("a refused uninitialise is unproven too");
    {
        kprt_sink *sink = make_sink(1);
        kprt_ring *ring;
        KT_NOT_NULL(sink);
        kprt_test_set_teardown_verdicts(1, 1, 0, 1);
        KT_EQ_INT(kprt_sink_destroy(sink), KPRT_SINK_TEARDOWN_UNPROVEN);
        kprt_test_set_teardown_verdicts(0, 1, 1, 1);
        KT_NOT_NULL(sink->unit);
        ring = atomic_load(&sink->ring);
        KT_NOT_NULL(ring);
        kprt_ring_destroy(ring);
        free(sink);
        kt_detail("verdicts=ok,refused,ok result=UNPROVEN");
    }

    kt_case("a refused dispose is unproven too");
    {
        kprt_sink *sink = make_sink(1);
        kprt_ring *ring;
        KT_NOT_NULL(sink);
        kprt_test_set_teardown_verdicts(1, 1, 1, 0);
        KT_EQ_INT(kprt_sink_destroy(sink), KPRT_SINK_TEARDOWN_UNPROVEN);
        kprt_test_set_teardown_verdicts(0, 1, 1, 1);
        KT_NOT_NULL(sink->unit);
        ring = atomic_load(&sink->ring);
        KT_NOT_NULL(ring);
        kprt_ring_destroy(ring);
        free(sink);
        kt_detail("verdicts=ok,ok,refused result=UNPROVEN");
    }

    kt_case("a stopped sink is not asked to stop, so a refusal it cannot reach is not read");
    {
        /* `running` zero means the stop verdict is never consulted. A teardown that read it anyway
         * would call a stopped unit's refusal a reason to leak. */
        kprt_sink *sink = make_sink(1);
        KT_NOT_NULL(sink);
        atomic_store(&sink->running, 0);
        kprt_test_set_teardown_verdicts(1, 0, 1, 1);
        KT_EQ_INT(kprt_sink_destroy(sink), KPRT_SINK_OK);
        kprt_test_set_teardown_verdicts(0, 1, 1, 1);
        kt_detail("running=0 stopVerdict=refused result=OK");
    }

    kt_case("an adopted ring outlives the sink that used it");
    {
        /* owns_ring 0 is the attach case: the ring belongs to the caller, so a successful teardown
         * frees the sink and leaves the ring alone. */
        kt_alloc_counts before;
        kprt_sink *sink = make_sink(0);
        kprt_ring *ring;
        KT_NOT_NULL(sink);
        ring = atomic_load(&sink->ring);
        kt_alloc_snapshot(&before);
        kprt_test_set_teardown_verdicts(1, 1, 1, 1);
        KT_EQ_INT(kprt_sink_destroy(sink), KPRT_SINK_OK);
        kprt_test_set_teardown_verdicts(0, 1, 1, 1);
        if (kt_alloc_active()) {
            KT_EQ_I64(kt_alloc_live_delta(&before), -1);
        } else {
            kt_partial("allocation accounting is not live in this variant");
        }
        /* Still usable, which is what "the caller's ring" means. */
        KT_EQ_I64(kprt_ring_buffered_frames(ring), 0);
        kprt_ring_destroy(ring);
        kt_detail("owns_ring=0 freed=sink only");
    }

    kt_case("destroying nothing is not an error");
    {
        KT_EQ_INT(kprt_sink_destroy(NULL), KPRT_SINK_OK);
        kt_detail("sink=null result=OK");
    }

    return kt_suite_end();
}
