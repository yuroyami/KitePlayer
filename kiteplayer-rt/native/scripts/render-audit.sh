#!/usr/bin/env bash
#
# The render audit: assertion 1 of plan section 15.2 B1.8, and the strongest evidence available for
# the claim that nothing on the audio device's real-time path allocates, locks, logs or enters a
# framework.
#
# WHY THIS IS THE FIRST ASSERTION AND NOT THE LAST. It needs no device, no thread and no runtime at
# all: it reads the shipped object's own symbol table and its relocations. A test can only show that
# something did not happen during the run it made; this shows that the code has no way to make it
# happen, on any run. Plan section 15.3 grades it level 2 and says it is stronger than a runtime test
# for what it covers.
#
# WHAT IS AUDITED, and why the render path is its own translation unit. `src/kite_rt_render.c` holds
# every instruction the device's thread executes inside this library: `kprt_ring_render`, the anchor
# it publishes, and `kprt_render_into`, which is the callback's whole body. It deliberately does NOT
# hold `kprt_ring_create`, because a unit that can allocate has `_malloc` in its undefined set and the
# audit of it would have to be argued away with "but only in create". Here there is nothing to argue:
# the object has no allocator symbol to call.
#
# The callback itself, `kprt_render_cb`, is `static` inside `src/kite_rt_coreaudio.c`, which does have
# a large undefined set because it is the file that opens and disposes the audio unit. So that
# function is audited by name instead: every relocation inside its own address range must be one of
# three symbols.
#
# Usage:
#   ./scripts/render-audit.sh                     audit, and fail on any violation
#   ./scripts/render-audit.sh --prove-it-can-fail  build three poisoned units and require rejection
#
# The second mode exists because an assertion that has never rejected anything is not evidence. It
# compiles copies of the render unit with a malloc call, with a variable length array and with the one
# framework call the plan names, and fails unless the audit refuses each of them.
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
MODULE="$(cd "$ROOT/.." && pwd)"

NM="${KPRT_NM:-/usr/bin/nm}"
OBJDUMP="${KPRT_OBJDUMP:-/usr/bin/objdump}"
AR="${KPRT_AR:-/usr/bin/ar}"

# The flag set the shipped archive is built with, copied from
# buildSrc/src/main/kotlin/CompileKiteRtTask.kt. Checked against that file below rather than trusted,
# because an audit compiled with different flags than the shipped object audits nothing.
SHIPPED_FLAGS="-O2 -std=c11 -fvisibility=hidden -fPIC -Wall -Wextra -Werror -Werror=vla"

# The only undefined symbols the real-time unit may have.
#
# `_memcpy` and `_memset` are what plan section 15.2 B1.8 lists. `_bzero` is here because Apple clang
# on arm64 lowers `memset(dst, 0, n)` to `_bzero`, which B1.7 measured and recorded as a warning for
# this sub-phase: an allowlist without it would fail a correct build. The unit is deliberately built
# WITHOUT -fno-builtin-memcpy and -fno-builtin-memset, exactly as the plan says, so that these calls
# stay visible as calls instead of being expanded into something the audit cannot see.
ALLOWED_UNDEFINED="_memcpy _memset _bzero"

# Everything `kprt_render_cb` may call. Three symbols: the clock it reads twice, the body it forwards
# to, and the counter update that closes the pair.
RENDER_CB_ALLOWED="_mach_absolute_time _kprt_render_into _kprt_sink_note_span"

# The named scan of plan section 15.2 B1.8, applied to every symbol either unit's real-time code
# refers to. The undefined-set check above already forbids all of these in the render unit by
# construction; this list is what makes the intent explicit and what covers `kprt_render_cb`, whose
# own unit is allowed to call the device.
FORBIDDEN_EXACT="_malloc _calloc _realloc _reallocf _free _valloc _posix_memalign _aligned_alloc \
_objc_msgSend _objc_retain _objc_release _objc_autorelease \
_pthread_mutex_lock _pthread_mutex_trylock _os_unfair_lock_lock \
_printf _fprintf _vfprintf _puts _AudioConvertHostTimeToNanos"
FORBIDDEN_PREFIX="dispatch_ os_log Kotlin_ kfun: _swift_ ___asan _objc_"

FAILURES=0
CHECKS=0

say() { printf '%s\n' "$*"; }
ok() { CHECKS=$((CHECKS + 1)); printf 'ok    %s\n' "$*"; }
bad() {
    CHECKS=$((CHECKS + 1))
    FAILURES=$((FAILURES + 1))
    printf 'FAIL  %s\n' "$*"
}

# ---- The compiler ----
#
# konan's clang when it is there, because that is the compiler that builds the shipped archive and the
# audit is about the shipped object. Apple clang otherwise, so the script still runs on a checkout that
# has never built the Gradle project. Which one was used is printed, because the two are not
# interchangeable evidence.
#
# The preferred package name is CompileKiteRtTask's DEFAULT_LLVM_PACKAGE. Taking the first glob match
# instead picked llvm-19 out of the three packages installed here, which is not the compiler anything
# ships with; measured, not guessed.
PREFERRED_LLVM="llvm-21-aarch64-macos-essentials-97"

pick_compiler() {
    local konan_root="${KONAN_DATA_DIR:-$HOME/.konan}/dependencies"
    local candidate
    if [ -x "$konan_root/$PREFERRED_LLVM/bin/clang" ]; then
        echo "$konan_root/$PREFERRED_LLVM/bin/clang"
        return 0
    fi
    if [ -d "$konan_root" ]; then
        candidate="$(find "$konan_root" -maxdepth 3 -type f -path '*/llvm-*/bin/clang' 2>/dev/null |
            sort -t- -k2 -n | tail -1)"
        if [ -n "$candidate" ] && [ -x "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    fi
    echo "/usr/bin/clang"
}

CC="${KPRT_CC:-$(pick_compiler)}"

# konan's clang carries no default sysroot, so the SDK has to be named explicitly, exactly as
# CompileKiteRtTask does inside its task action. Apple clang finds it on its own and does not mind
# being told.
SDK_PATH="$(xcrun --sdk macosx --show-sdk-path 2>/dev/null || true)"
SYSROOT_ARGS=""
if [ -n "$SDK_PATH" ] && [ -d "$SDK_PATH" ]; then
    SYSROOT_ARGS="-isysroot $SDK_PATH"
fi
WORK="$ROOT/build/render-audit"
rm -rf "$WORK"
mkdir -p "$WORK"

# ---- Helpers over one object file ----

# Every symbol the object's __text section refers to through a relocation, deduplicated.
text_relocations() {
    local object="$1"
    "$OBJDUMP" -r "$object" |
        awk '/RELOCATION RECORDS FOR \[__text\]/ { inside = 1; next }
             /RELOCATION RECORDS FOR/ { inside = 0 }
             inside && $3 != "" && $1 != "OFFSET" { print $3 }' |
        sort -u
}

# Symbols the object defines, so a call inside the unit is not mistaken for a call out of it.
defined_symbols() {
    "$NM" -g "$1" | awk '$1 != "" && $2 != "U" { print $3 }' | sort -u
    "$NM" "$1" | awk '$2 == "t" || $2 == "T" { print $3 }' | sort -u
}

in_list() {
    local needle="$1"
    shift
    local item
    for item in $*; do
        [ "$needle" = "$item" ] && return 0
    done
    return 1
}

# NEVER call this on the right-hand side of a pipe. Bash runs that side in a subshell, so every
# increment of CHECKS and FAILURES made there is discarded when the subshell exits, and a FAIL this
# function printed would not reach the exit code. That is exactly what happened until the independent
# verification of B1.8 measured it: the script printed 15 result lines and reported "12 checks, all
# passed", the three missing ones were the three `scan_forbidden` calls, and a planted `_malloc`
# printed its FAIL line while the summary counted only the two checks that were not behind a pipe. The
# call sites now use a here-string and a process substitution, both of which run the reader in this
# shell. The header comment further down already recorded this bug being fixed once in the negative
# control path; this is the same bug in the same script, in the two places the fix missed.
scan_forbidden() {
    # scan_forbidden <label> <symbol list on stdin>
    local label="$1"
    local symbols
    symbols="$(cat)"
    local hits=""
    local token
    for token in $FORBIDDEN_EXACT; do
        if printf '%s\n' "$symbols" | grep -qx -- "$token"; then
            hits="$hits $token"
        fi
    done
    for token in $FORBIDDEN_PREFIX; do
        if printf '%s\n' "$symbols" | grep -q -- "$token"; then
            hits="$hits $token"
        fi
    done
    if [ -n "$hits" ]; then
        bad "$label refers to forbidden symbols:$hits"
        return 1
    fi
    ok "$label refers to none of the $(echo $FORBIDDEN_EXACT $FORBIDDEN_PREFIX | wc -w | tr -d ' ') forbidden names"
    return 0
}

# Audits one compiled real-time unit. Returns non-zero when anything was wrong, which is what the
# negative control mode reads.
audit_render_object() {
    local label="$1" object="$2"
    local before="$FAILURES"
    local undefined symbol

    undefined="$("$NM" -u "$object" | sort -u)"
    local outside=""
    for symbol in $undefined; do
        in_list "$symbol" "$ALLOWED_UNDEFINED" || outside="$outside $symbol"
    done
    if [ -n "$outside" ]; then
        bad "$label has undefined symbols outside the allowlist:$outside"
    else
        ok "$label undefined set is [$(echo $undefined)] and the allowlist is [$ALLOWED_UNDEFINED]"
    fi

    local defined
    defined="$(defined_symbols "$object" | tr '\n' ' ')"
    local escaping=""
    for symbol in $(text_relocations "$object"); do
        case "$symbol" in
            __text|__const|l_*|L*|ltmp*) continue ;;
        esac
        in_list "$symbol" "$ALLOWED_UNDEFINED" && continue
        in_list "$symbol" "$defined" && continue
        escaping="$escaping $symbol"
    done
    if [ -n "$escaping" ]; then
        bad "$label calls out of its own unit to:$escaping"
    else
        ok "$label calls nothing outside itself but the allowlist"
    fi

    # Relocations AND the object's own definitions, and the second half is the other correction the
    # verification asked for. `scan_forbidden` used to see relocation targets only, so a forbidden name
    # DEFINED inside the audited unit was invisible twice over: an intra-section direct branch emits no
    # relocation, and the escape check above skips anything the unit defines. Measured: a
    # `void objc_msgSend(void)` defined in kite_rt_render.c and called from kprt_ring_render left
    # `T _objc_msgSend` in the object's symbol table while the audit reported it referred to none of
    # the forbidden names. Feeding both lists closes it.
    scan_forbidden "$label" < <(
        text_relocations "$object"
        defined_symbols "$object"
    )

    [ "$FAILURES" = "$before" ]
}

# ---- 1. The shipped flag set really is the shipped flag set ----

TASK="$MODULE/../buildSrc/src/main/kotlin/CompileKiteRtTask.kt"
if [ ! -f "$TASK" ]; then
    bad "cannot find CompileKiteRtTask.kt at $TASK, so the audit cannot prove it used the shipped flags"
else
    missing=""
    for flag in $SHIPPED_FLAGS; do
        grep -q -- "\"$flag\"" "$TASK" || missing="$missing $flag"
    done
    if [ -n "$missing" ]; then
        bad "CompileKiteRtTask does not carry these flags this audit compiles with:$missing"
    else
        ok "every flag this audit compiles with is in CompileKiteRtTask's COMPILER_FLAGS"
    fi
fi

# -Werror=vla in both flag sets. A variable length array is an allocation no symbol list can show, so
# the compiler has to be the one refusing it, in the shipped build and in the host build alike.
if grep -q -- '"-Werror=vla"' "$TASK" 2>/dev/null; then
    ok "the shipped build refuses a variable length array (-Werror=vla in CompileKiteRtTask)"
else
    bad "-Werror=vla is missing from CompileKiteRtTask, so a VLA could reach a real-time path"
fi
if grep -q -- '-Werror=vla' "$HERE/build-host.sh"; then
    ok "the host build refuses a variable length array (-Werror=vla in build-host.sh)"
else
    bad "-Werror=vla is missing from build-host.sh"
fi

# ---- 2. No alloca anywhere on the real-time path ----

# The pattern is the IDENTIFIER and not the word: an earlier draft grepped for `alloca` and reported
# eleven hits, every one of them the English word "allocation" inside a comment explaining that
# nothing here allocates. A check that cries wolf on its own documentation gets switched off.
ALLOCA_HITS="$(grep -nE '(alloca[[:space:]]*\()|__builtin_alloca|_alloca\b' \
    "$ROOT/src"/*.c "$ROOT/src"/*.h "$ROOT/include"/*.h 2>/dev/null || true)"
if [ -n "$ALLOCA_HITS" ]; then
    bad "alloca appears in the library sources:"
    printf '%s\n' "$ALLOCA_HITS"
else
    ok "no source in include/ or src/ mentions alloca"
fi

# ---- 3. The real-time unit, compiled with the shipped flags ----

say ""
say "render-audit.sh: compiler $CC"
say "                $("$CC" --version | head -1)"
say "                flags $SHIPPED_FLAGS $SYSROOT_ARGS"
say ""

RENDER_OBJ="$WORK/kite_rt_render.o"
# shellcheck disable=SC2086
if ! "$CC" $SHIPPED_FLAGS $SYSROOT_ARGS -I "$ROOT/include" -I "$ROOT/src" \
        -c "$ROOT/src/kite_rt_render.c" -o "$RENDER_OBJ" 2>"$WORK/render.log"; then
    bad "the real-time unit does not compile with the shipped flags"
    cat "$WORK/render.log"
else
    ok "the real-time unit compiles with the shipped flag set"
    audit_render_object "kite_rt_render.o" "$RENDER_OBJ"
fi

# ---- 4. The device callback, by name, inside a unit that is allowed to touch the device ----

DEVICE_OBJ="$WORK/kite_rt_coreaudio.o"
# shellcheck disable=SC2086
if ! "$CC" $SHIPPED_FLAGS $SYSROOT_ARGS -I "$ROOT/include" -I "$ROOT/src" \
        -c "$ROOT/src/kite_rt_coreaudio.c" -o "$DEVICE_OBJ" 2>"$WORK/device.log"; then
    bad "the device unit does not compile with the shipped flags"
    cat "$WORK/device.log"
else
    ok "the device unit compiles with the shipped flag set"

    if ! "$NM" "$DEVICE_OBJ" | grep -q ' _kprt_render_cb$'; then
        bad "kprt_render_cb is not in the device object at all, so nothing was audited"
    else
        # The callback must not be an exported symbol: Kotlin must have no way to reach or install it.
        if "$NM" -g "$DEVICE_OBJ" | awk '$2 == "T" { print $3 }' | grep -qx '_kprt_render_cb'; then
            bad "kprt_render_cb is externally visible, so Kotlin could install or call it"
        else
            ok "kprt_render_cb is a local symbol: not exported, not in the header, not in the bindings"
        fi

        CB_SYMBOLS="$("$OBJDUMP" -d -r "$DEVICE_OBJ" |
            awk '/^[0-9a-f]+ <_kprt_render_cb>:/ { inside = 1; next }
                 /^[0-9a-f]+ <.*>:/ { inside = 0 }
                 inside && /ARM64_RELOC|X86_64_RELOC/ { print $NF }' | sort -u)"
        outside=""
        for symbol in $CB_SYMBOLS; do
            in_list "$symbol" "$RENDER_CB_ALLOWED" || outside="$outside $symbol"
        done
        if [ -n "$outside" ]; then
            bad "kprt_render_cb calls symbols outside its allowlist:$outside"
        else
            ok "kprt_render_cb calls exactly [$(echo $CB_SYMBOLS)], allowlist [$RENDER_CB_ALLOWED]"
        fi
        scan_forbidden "kprt_render_cb" <<< "$CB_SYMBOLS"
    fi
fi

# ---- 5. The object that actually ships, when Gradle has built it ----
#
# Everything above compiles the sources again. This step audits the archive the cinterop klib
# embeds, which is the only object a consumer ever runs. It is skipped rather than failed when the
# Gradle build has not run, and the skip is printed, because an audit that silently checks nothing is
# worse than one that says so.

SHIPPED_ARCHIVE="$MODULE/build/kiteplayer-rt-c/macos_arm64/libkiteplayerrt.a"
if [ -f "$SHIPPED_ARCHIVE" ]; then
    EXTRACT="$WORK/shipped"
    mkdir -p "$EXTRACT"
    (cd "$EXTRACT" && "$AR" x "$SHIPPED_ARCHIVE")
    if [ -f "$EXTRACT/kite_rt_render.o" ]; then
        audit_render_object "shipped kite_rt_render.o (from $(basename "$SHIPPED_ARCHIVE"))" \
            "$EXTRACT/kite_rt_render.o"
    else
        bad "the shipped archive has no kite_rt_render.o member"
    fi
else
    say "skip  the shipped archive is not built yet, so only freshly compiled objects were audited"
    say "      build it with: ./gradlew :kiteplayer-rt:compileKiteRtCForMacosArm64"
fi

# ---- 6. The negative control, which is the only thing that makes any of the above evidence ----

if [ "${1:-}" = "--prove-it-can-fail" ]; then
    say ""
    say "render-audit.sh: negative controls. Each poisoned unit MUST be rejected."
    POISON_DIR="$WORK/poison"
    mkdir -p "$POISON_DIR"

    poison_and_audit() {
        # poison_and_audit <name> <sed program>
        local name="$1" program="$2"
        local source="$POISON_DIR/$name.c"
        local object="$POISON_DIR/$name.o"
        sed "$program" "$ROOT/src/kite_rt_render.c" > "$source"
        if ! cmp -s "$source" "$ROOT/src/kite_rt_render.c"; then
            : # the poison really changed something
        else
            bad "negative control $name did not change the source, so it tests nothing"
            return
        fi
        # shellcheck disable=SC2086
        if ! "$CC" $SHIPPED_FLAGS $SYSROOT_ARGS -I "$ROOT/include" -I "$ROOT/src" \
                -c "$source" -o "$object" 2>"$POISON_DIR/$name.log"; then
            # A poison the COMPILER refuses is also a rejection, and for the VLA that is the whole
            # point: -Werror=vla is the instrument, because no symbol list can show a stack
            # allocation.
            ok "negative control $name was rejected by the compiler: $(grep -m1 'error:' "$POISON_DIR/$name.log" | sed 's|.*error: ||')"
            return
        fi
        # Redirected to a file rather than captured in `$(...)`, because command substitution runs a
        # subshell and every counter the audit moved there would be lost on the way back. That bug made
        # a control the audit HAD rejected report as having passed, which is the most dangerous
        # direction for a self-check to be wrong in.
        local log="$POISON_DIR/$name.audit"
        local before_failures="$FAILURES" before_checks="$CHECKS"
        local rejected=0
        audit_render_object "negative control $name" "$object" > "$log" 2>&1 || rejected=1
        FAILURES="$before_failures"
        CHECKS="$before_checks"
        if [ "$rejected" = 1 ]; then
            ok "negative control $name was rejected by the audit"
        else
            bad "negative control $name PASSED the audit, so the audit proves nothing"
        fi
        sed 's/^/      /' "$log"
    }

    # 1. An allocation on the real-time path, which is the defect the whole assertion is about.
    #
    # The declaration is written out rather than pulled in with a header, and that detail matters: an
    # earlier draft just called `malloc`, which -Werror refused as an implicit declaration, so the
    # control proved that -Werror works rather than that the AUDIT works. This version compiles
    # cleanly and has to be caught by the symbol table, which is the instrument under test.
    # The allocation's RESULT is stored through a volatile pointer, and that detail was measured rather
    # than reasoned about. Two earlier versions of this control were deleted by -O2 before the audit
    # ever saw them: `void *poison = malloc(16); (void)poison;` because nothing reads the result, and
    # then a version that copied four bytes OUT of the block, because reading uninitialised memory is
    # undefined behaviour so the optimiser folded the value and the allocation with it. A volatile store
    # is a side effect the compiler must keep, so the call survives to be caught.
    #
    # What that says about the instrument, stated plainly: this audit cannot see an allocation the
    # optimiser removed. That is not a hole, because code the compiler deleted does not run on any
    # thread; it is a limit worth writing down so nobody reads a passing audit as "the source contains
    # no malloc" when what it means is "the shipped object performs none".
    poison_and_audit "malloc-in-render" \
        's|int32_t to_read;|int32_t to_read; extern void *malloc(unsigned long); static void *volatile poison_sink; poison_sink = malloc(16); if (poison_sink == destination) return 0;|'
    # 2. The framework call plan section 15.2 B1.8 step 1 names and forbids. Declared the same way and
    #    for the same reason, so this too must be caught by the audit and not by the compiler.
    poison_and_audit "framework-call-in-render" \
        's|deadline = kprt_sink_ticks_to_nanos(sink, host_ticks) +|extern unsigned long long AudioConvertHostTimeToNanos(unsigned long long); deadline = (int64_t)AudioConvertHostTimeToNanos(host_ticks) +|'
    # 3. A variable length array. This one the COMPILER has to catch, and that is the point of it: a
    #    stack allocation appears in no symbol table and in no relocation, so -Werror=vla is the only
    #    instrument that can see it. The control proves the flag is really in force.
    poison_and_audit "vla-in-render" \
        's|int32_t channels;|int32_t channels; float scratch[frames]; (void)scratch;|'
    # 4. A forbidden name DEFINED inside the audited unit and called from the render path. This control
    #    exists because of two defects the independent verification of B1.8 found in this script, and it
    #    is the only control that can prove either one is fixed. The name is not in the undefined set,
    #    because the unit defines it; the call is an intra-section direct branch, so it emits no
    #    relocation and the escape check skips it; and it is caught only by `scan_forbidden`, whose
    #    verdict used to be thrown away in a pipeline subshell. A pass here means the forbidden-name scan
    #    both sees the definition and can fail the audit.
    #    The definition has external linkage on purpose. A `static` one would be inlined away by -O2
    #    and the symbol with it, which would make the control prove nothing, in the same way two earlier
    #    drafts of the malloc control did.
    poison_and_audit "objc-defined-in-render" \
        's|^int32_t kprt_ring_render|void objc_msgSend(void) { } int32_t kprt_ring_render|; s|int32_t channels;|int32_t channels; objc_msgSend();|'
fi

say ""
if [ "$FAILURES" -eq 0 ]; then
    say "render-audit.sh: $CHECKS checks, all passed"
    exit 0
fi
say "render-audit.sh: $CHECKS checks, $FAILURES failed"
exit 1
