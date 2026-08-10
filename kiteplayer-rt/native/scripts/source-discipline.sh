#!/usr/bin/env bash
#
# The two rules in this library that no runtime instrument covers, checked against the source text.
#
# WHAT THIS IS AND WHAT IT IS NOT. This is a level 4 instrument under plan section 2: a source level
# check with the ownership and ordering invariant written out. It is deliberately weaker than
# scripts/render-audit.sh, which reads a compiled object and is level 2. It exists because the
# independent verification of B1.8 planted two defects that the whole gate passed:
#
#   1. `kprt_sink_destroy` freeing the ring BEFORE it stops and disposes the audio unit. That is the
#      classic use-after-free in an audio teardown. With the defect in place all eight C suites passed
#      in every variant, the interposer mode passed, render-audit.sh passed, and forty three real
#      device teardowns under AddressSanitizer produced no report, because the free lands in the IO
#      thread's idle time almost every time.
#   2. The release store that publishes `written` in `kprt_ring_commit_write`, and the matching
#      acquire load in `kprt_ring_render`, both downgraded to relaxed. That deletes the happens before
#      edge which publishes the samples and the segment. All eight suites passed under
#      ThreadSanitizer, with TSan proved live by a control that reports an ordinary data race in the
#      same environment. TSan catches missing atomicity; it does not grade ordering strength.
#
# So neither defect had an instrument, and both are one line. A text check is a poor proof of a
# concurrency property and a good guard against an edit that reverses a decision, which is what these
# two defects were. No report may present this script as evidence that the ordering is correct; what
# it proves is that the ordering decisions the design took are still written where the design put
# them.
#
# Usage:
#   ./scripts/source-discipline.sh                     check, and fail on any violation
#   ./scripts/source-discipline.sh --prove-it-can-fail  plant the three defects in copies and require
#                                                      each one to be rejected
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

FAILURES=0
CHECKS=0

say() { printf '%s\n' "$*"; }
ok() { CHECKS=$((CHECKS + 1)); printf 'ok    %s\n' "$*"; }
bad() {
    CHECKS=$((CHECKS + 1))
    FAILURES=$((FAILURES + 1))
    printf 'FAIL  %s\n' "$*"
}

# The body of one function, as numbered lines, from its opening signature to the first line that is a
# closing brace in column one. The signature has to start in column one too, which every definition in
# this library does, so a call to the same name inside another function is not mistaken for it.
function_body() {
    # function_body <file> <name>
    awk -v name="$2" '
        $0 ~ "^[A-Za-z_].*[ *]" name "\\(" { inside = 1 }
        inside { print FNR ": " $0 }
        inside && /^}/ { exit }
    ' "$1"
}

# The FILE line number of the first line in a body matching a pattern, or the empty string.
#
# The file line and not the position within the body, because these numbers end up in a failure
# message and in a report, and "out of order at 8" is only useful if 8 is a line somebody can open.
# `function_body` prefixes every line with its own file line number and the body is in file order, so
# comparing these still compares positions.
line_of() {
    # line_of <body> <pattern>
    printf '%s\n' "$1" | grep -E -- "$2" | head -1 | cut -d: -f1 | tr -d ' '
}

# ---- Rule 1. The teardown order in kprt_sink_destroy ----
#
# Stop, uninitialise, dispose, and only then let go of the ring. Every step in that order, and the
# ring release after all three, is what makes "the callback is provably out" true rather than hoped
# for. The probe that measured `AudioOutputUnitStop` blocking until an in flight callback returns is
# what the order rests on: three runs, stop took 11.1, 13.0 and 12.3 ms while a callback was held for
# 4 ms, and no callback was entered afterwards.
check_teardown_order() {
    # check_teardown_order <src dir>
    local dir="$1"
    local body
    body="$(function_body "$dir/kite_rt_coreaudio.c" kprt_sink_destroy)"
    if [ -z "$body" ]; then
        bad "kprt_sink_destroy was not found in $dir/kite_rt_coreaudio.c, so nothing was checked"
        return 1
    fi

    local stop uninit dispose release destroy freed
    stop="$(line_of "$body" 'AudioOutputUnitStop')"
    uninit="$(line_of "$body" 'AudioUnitUninitialize')"
    dispose="$(line_of "$body" 'AudioComponentInstanceDispose')"
    release="$(line_of "$body" 'atomic_store_explicit\(&sink->ring')"
    destroy="$(line_of "$body" 'kprt_ring_destroy')"
    freed="$(line_of "$body" 'free\(sink\)')"

    local missing=""
    [ -n "$stop" ] || missing="$missing AudioOutputUnitStop"
    [ -n "$uninit" ] || missing="$missing AudioUnitUninitialize"
    [ -n "$dispose" ] || missing="$missing AudioComponentInstanceDispose"
    [ -n "$release" ] || missing="$missing the-ring-release"
    [ -n "$destroy" ] || missing="$missing kprt_ring_destroy"
    [ -n "$freed" ] || missing="$missing free(sink)"
    if [ -n "$missing" ]; then
        bad "kprt_sink_destroy does not contain:$missing"
        return 1
    fi

    if [ "$stop" -lt "$uninit" ] && [ "$uninit" -lt "$dispose" ] &&
        [ "$dispose" -lt "$release" ] && [ "$release" -le "$destroy" ] &&
        [ "$destroy" -lt "$freed" ]; then
        ok "kprt_sink_destroy stops ($stop), uninitialises ($uninit), disposes ($dispose), then releases the ring ($release), destroys it ($destroy) and frees itself ($freed)"
        return 0
    fi
    bad "kprt_sink_destroy is out of order: stop $stop, uninitialise $uninit, dispose $dispose, ring release $release, ring destroy $destroy, free $freed. The ring must be let go only after the dispose, or the device's callback can read freed memory."
    return 1
}

# ---- Rule 2. The publication edge ----
#
# Plan section 15.2 B1.7 step 3: fill the segment slot, then its own sequence with a release store,
# then `written` with release. The consumer loads `written` with acquire first, so that one release
# publishes both the samples and the segment. Relaxed on either end is still atomic, so no sanitizer
# complains, and the guarantee is gone.
check_publication_order() {
    # check_publication_order <src dir>
    local dir="$1"
    local rc=0
    local commit render append

    commit="$(function_body "$dir/kite_rt_ring.c" kprt_ring_commit_write)"
    render="$(function_body "$dir/kite_rt_render.c" kprt_ring_render)"
    append="$(function_body "$dir/kite_rt_ring.c" append_segment)"

    if printf '%s\n' "$commit" |
        grep -qE 'atomic_store_explicit\(&ring->written,[^;]*memory_order_release'; then
        ok "kprt_ring_commit_write publishes written with a release store"
    else
        bad "kprt_ring_commit_write does not store written with memory_order_release, so the samples and the segment are not published by it"
        rc=1
    fi

    if printf '%s\n' "$render" |
        grep -qE 'atomic_load_explicit\(&ring->written,[^;]*memory_order_acquire'; then
        ok "kprt_ring_render loads written with an acquire load"
    else
        bad "kprt_ring_render does not load written with memory_order_acquire, so seeing a new value does not guarantee seeing the samples"
        rc=1
    fi

    if printf '%s\n' "$append" |
        grep -qE 'atomic_store_explicit\(&ring->segments\[slot\]\.seq,[^;]*memory_order_release'; then
        ok "append_segment closes a segment slot's sequence with a release store"
    else
        bad "append_segment does not close the slot sequence with memory_order_release"
        rc=1
    fi

    local anchor fences
    anchor="$(function_body "$dir/kite_rt_render.c" publish_anchor)"
    fences="$(printf '%s\n' "$anchor" | grep -c 'atomic_thread_fence(memory_order_release)')"
    if [ "$fences" -ge 2 ]; then
        ok "publish_anchor keeps its two release fences around the payload ($fences found)"
    else
        bad "publish_anchor has $fences release fences and needs two, one on each side of the payload stores"
        rc=1
    fi
    return $rc
}

# ---- The checks, against the real sources ----

say "source-discipline.sh: level 4 source checks over $ROOT/src"
say ""
check_teardown_order "$ROOT/src"
check_publication_order "$ROOT/src"

# ---- The negative controls, which are the only thing that makes the above evidence ----

if [ "${1:-}" = "--prove-it-can-fail" ]; then
    say ""
    say "source-discipline.sh: negative controls. Each planted defect MUST be rejected."
    WORK="$ROOT/build/source-discipline"
    rm -rf "$WORK"
    mkdir -p "$WORK"

    plant_and_check() {
        # plant_and_check <name> <which rule: teardown|publication> <file> <sed program>
        local name="$1" rule="$2" file="$3" program="$4"
        local dir="$WORK/$name"
        mkdir -p "$dir"
        cp "$ROOT/src"/*.c "$ROOT/src"/*.h "$dir/"
        sed "$program" "$ROOT/src/$file" > "$dir/$file.new"
        if cmp -s "$dir/$file.new" "$ROOT/src/$file"; then
            bad "negative control $name did not change $file, so it tests nothing"
            rm -f "$dir/$file.new"
            return
        fi
        mv "$dir/$file.new" "$dir/$file"

        local before_failures="$FAILURES" before_checks="$CHECKS"
        local log="$WORK/$name.log"
        local rejected=0
        if [ "$rule" = teardown ]; then
            check_teardown_order "$dir" > "$log" 2>&1 || rejected=1
        else
            check_publication_order "$dir" > "$log" 2>&1 || rejected=1
        fi
        FAILURES="$before_failures"
        CHECKS="$before_checks"
        if [ "$rejected" = 1 ]; then
            ok "negative control $name was rejected"
        else
            bad "negative control $name PASSED, so this check proves nothing"
        fi
        sed 's/^/      /' "$log"
    }

    # 1. The teardown inversion: let go of the ring first, then stop the device. This is the defect the
    #    verification planted and that every runtime instrument in the gate missed.
    plant_and_check "ring-freed-before-stop" teardown kite_rt_coreaudio.c \
        's|    if (sink->unit != NULL) {|    ring = atomic_load_explicit(\&sink->ring, memory_order_relaxed); if (ring != NULL \&\& sink->owns_ring) kprt_ring_destroy(ring);\n    if (sink->unit != NULL) {|'
    # 2. The publication edge, producer end.
    plant_and_check "written-store-relaxed" publication kite_rt_ring.c \
        's|atomic_store_explicit(&ring->written, start + frames, memory_order_release)|atomic_store_explicit(\&ring->written, start + frames, memory_order_relaxed)|'
    # 3. The publication edge, consumer end.
    plant_and_check "written-load-relaxed" publication kite_rt_render.c \
        's|written = atomic_load_explicit(&ring->written, memory_order_acquire)|written = atomic_load_explicit(\&ring->written, memory_order_relaxed)|'
fi

say ""
if [ "$FAILURES" -eq 0 ]; then
    say "source-discipline.sh: $CHECKS checks, all passed"
    exit 0
fi
say "source-discipline.sh: $CHECKS checks, $FAILURES failed"
exit 1
