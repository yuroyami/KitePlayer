/* Implementation of the C test harness declared in harness.h. */

#include "harness.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define KT_NAME_MAX 200
#define KT_DETAIL_MAX 400

static const char *suite_name = "unnamed";
static char case_name[KT_NAME_MAX];
static char case_detail[KT_DETAIL_MAX];
static int case_open;
static long cases_total;
static long cases_partial;
static int require_alloc_accounting;

static void print_case_line(const char *verdict)
{
    if (case_detail[0] != '\0')
        printf("%-6s %4ld  %s  [%s]\n", verdict, cases_total, case_name, case_detail);
    else
        printf("%-6s %4ld  %s\n", verdict, cases_total, case_name);
    fflush(stdout);
}

static void close_case(void)
{
    if (!case_open)
        return;
    case_open = 0;
    print_case_line("ok");
}

void kt_suite_begin(const char *suite)
{
    const char *require;
    suite_name = (suite != NULL && suite[0] != '\0') ? suite : "unnamed";
    require = getenv("KPRT_REQUIRE_ALLOC_ACCOUNTING");
    require_alloc_accounting = (require != NULL && require[0] == '1');
    printf("suite %s\n", suite_name);
    fflush(stdout);
    /* Probe once here, so the probe's own allocation never lands inside a case window. */
    (void)kt_alloc_active();
    if (require_alloc_accounting && !kt_alloc_active()) {
        printf("FAIL   %s: KPRT_REQUIRE_ALLOC_ACCOUNTING=1 but the allocation interposer is not "
               "effective in this build variant\n", suite_name);
        fflush(stdout);
        exit(1);
    }
}

int kt_suite_end(void)
{
    close_case();
    if (cases_total == 0) {
        printf("FAIL   %s ran no cases\n", suite_name);
        fflush(stdout);
        return 1;
    }
    if (cases_partial > 0)
        printf("%s: %ld cases, %ld passed, %ld with a property this variant cannot observe\n",
               suite_name, cases_total, cases_total, cases_partial);
    else
        printf("%s: %ld cases, %ld passed\n", suite_name, cases_total, cases_total);
    fflush(stdout);
    return 0;
}

void kt_case(const char *fmt, ...)
{
    va_list args;
    close_case();
    cases_total++;
    case_open = 1;
    case_detail[0] = '\0';
    va_start(args, fmt);
    vsnprintf(case_name, sizeof(case_name), fmt, args);
    va_end(args);
}

void kt_detail(const char *fmt, ...)
{
    va_list args;
    size_t used = strlen(case_detail);
    if (used + 2 >= sizeof(case_detail))
        return;
    if (used > 0) {
        case_detail[used] = ' ';
        used++;
        case_detail[used] = '\0';
    }
    va_start(args, fmt);
    vsnprintf(case_detail + used, sizeof(case_detail) - used, fmt, args);
    va_end(args);
}

void kt_partial(const char *fmt, ...)
{
    va_list args;
    char reason[KT_DETAIL_MAX];
    va_start(args, fmt);
    vsnprintf(reason, sizeof(reason), fmt, args);
    va_end(args);
    if (require_alloc_accounting)
        kt_fail_at(__FILE__, __LINE__, "a property was skipped while it was required: %s", reason);
    cases_partial++;
    kt_detail("partial: %s", reason);
}

void kt_note(const char *fmt, ...)
{
    va_list args;
    printf("       |  ");
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);
    printf("\n");
    fflush(stdout);
}

_Noreturn void kt_fail_at(const char *file, int line, const char *fmt, ...)
{
    va_list args;
    if (!case_open) {
        cases_total++;
        snprintf(case_name, sizeof(case_name), "%s", "(outside any case)");
    }
    case_open = 0;
    print_case_line("FAIL");
    printf("       |  at %s:%d\n", file, line);
    printf("       |  ");
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);
    printf("\n");
    printf("FAIL   %s failed at case %ld\n", suite_name, cases_total);
    fflush(stdout);
    exit(1);
}

long kt_first_nonzero_f32(const float *start, size_t count)
{
    size_t i;
    if (start == NULL)
        return (count == 0) ? -1 : 0;
    for (i = 0; i < count; i++) {
        if (start[i] != 0.0f)
            return (long)i;
    }
    return -1;
}

long kt_first_diff_f32(const float *a, const float *b, size_t count)
{
    size_t i;
    if (a == NULL || b == NULL)
        return (count == 0) ? -1 : 0;
    for (i = 0; i < count; i++) {
        if (a[i] != b[i])
            return (long)i;
    }
    return -1;
}

/* ---- Allocation accounting ----
 *
 * The probe has to live here rather than in interpose_alloc.c. dyld does not apply an interpose
 * section to the image that carries it, which is exactly why the interposer's own wrappers can
 * call the real malloc without recursing, and exactly why a probe compiled into the interposer
 * would always report zero. This translation unit is part of the test executable, so its
 * allocations do go through the interposed entry points. */

static int alloc_probe_done;
static int alloc_probe_result;
static void *volatile probe_sink;

__attribute__((noinline)) static int probe_interposer(void)
{
    kt_alloc_counts before;
    kt_alloc_counts after;
    volatile size_t size = 32;
    void *block;
    kt_alloc_snapshot(&before);
    block = malloc(size);
    if (block == NULL)
        return 0;
    memset(block, 0, size);
    probe_sink = block;
    free(block);
    probe_sink = NULL;
    kt_alloc_snapshot(&after);
    return (after.malloc_calls > before.malloc_calls) && (after.free_calls > before.free_calls);
}

int kt_alloc_active(void)
{
    if (!alloc_probe_done) {
        alloc_probe_result = probe_interposer();
        alloc_probe_done = 1;
    }
    return alloc_probe_result;
}

static long long new_calls(const kt_alloc_counts *counts)
{
    return counts->malloc_calls + counts->calloc_calls + counts->posix_memalign_calls
        + counts->aligned_alloc_calls + counts->valloc_calls + counts->realloc_calls;
}

long long kt_alloc_live_delta(const kt_alloc_counts *before)
{
    kt_alloc_counts now;
    kt_alloc_snapshot(&now);
    return now.live_blocks - before->live_blocks;
}

long long kt_alloc_new_delta(const kt_alloc_counts *before)
{
    kt_alloc_counts now;
    kt_alloc_snapshot(&now);
    return new_calls(&now) - new_calls(before);
}

long long kt_alloc_free_delta(const kt_alloc_counts *before)
{
    kt_alloc_counts now;
    kt_alloc_snapshot(&now);
    return now.free_calls - before->free_calls;
}

long long kt_alloc_mmap_delta(const kt_alloc_counts *before)
{
    kt_alloc_counts now;
    kt_alloc_snapshot(&now);
    return now.mmap_calls - before->mmap_calls;
}
