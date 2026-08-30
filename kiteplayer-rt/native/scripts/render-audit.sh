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
# WHAT IS AUDITED, and why the ring renderer has its own translation unit. `src/kite_rt_render.c`
# holds everything executed after the callback enters `kprt_render_into`: `kprt_ring_render`, the
# anchor it publishes, and the buffer-writing body. It deliberately does NOT hold `kprt_ring_create`,
# because a unit that can allocate has `_malloc` in its undefined set and the audit of it would have
# to be argued away with "but only in create". Here there is nothing to argue: the object has no
# allocator symbol to call.
#
# The callback itself, `kprt_render_cb`, is `static` inside `src/kite_rt_coreaudio.c`, which does have
# a large undefined set because it is the file that opens and disposes the audio unit. So that
# function is audited by name instead: every relocation inside its own address range must equal the
# fixed three-symbol call set. The gated macOS, iOS device and iOS simulator archives are then opened
# and both objects are audited again. Their compiled AudioComponentDescription literals pin
# DefaultOutput on macOS and RemoteIO on both iOS targets.
#
# Usage:
#   ./scripts/render-audit.sh                     audit, and fail on any violation
#   ./scripts/render-audit.sh --prove-it-can-fail  run seven negative controls and require rejection
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
OTOOL="${KPRT_OTOOL:-/usr/bin/otool}"

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
# W-17: the leading underscore is Mach-O's, not C's. ELF spells the same symbol without it, so the
# lists below are written in the SOURCE spelling and given the format's prefix at use. On Mach-O
# that reproduces exactly what this file said before, character for character.
SYMBOL_PREFIX="_"

# Rewrites a space separated symbol list into the current format's spelling.
prefixed() {
    local out=""
    local name
    for name in $1; do out="$out $SYMBOL_PREFIX$name"; done
    printf '%s' "${out# }"
}

# Reads the prefix from the object itself rather than from the host. An ELF object has no leading
# underscore on its C symbols; a Mach-O one does.
detect_symbol_prefix() {
    case "$(file -b "$1" 2>/dev/null)" in
        *Mach-O*) SYMBOL_PREFIX="_" ;;
        *ELF*)    SYMBOL_PREFIX="" ;;
        *)        SYMBOL_PREFIX="_" ;;
    esac
}

ALLOWED_UNDEFINED_NAMES="memcpy memset bzero"

# Everything `kprt_render_cb` may call. Three symbols: the clock it reads twice, the body it forwards
# to, and the counter update that closes the pair.
RENDER_CB_ALLOWED_NAMES="mach_absolute_time kprt_render_into kprt_sink_note_span"

# AudioComponentDescription stores these four-character subtypes in the device object's
# __TEXT,__literal8 section. Pinning the values in the object catches the dangerous direction a
# source-only audit cannot: the branch reads correctly while the wrong target archive was embedded.
DEFAULT_OUTPUT_FOURCC="64656620"
REMOTE_IO_FOURCC="72696f63"

# The named scan of plan section 15.2 B1.8, applied to every symbol either unit's real-time code
# refers to. The undefined-set check above already forbids all of these in the render unit by
# construction; this list is what makes the intent explicit and what covers `kprt_render_cb`, whose
# own unit is allowed to call the device.
FORBIDDEN_EXACT_NAMES="malloc calloc realloc reallocf free valloc posix_memalign aligned_alloc \
objc_msgSend objc_retain objc_release objc_autorelease \
pthread_mutex_lock pthread_mutex_trylock os_unfair_lock_lock \
printf fprintf vfprintf puts AudioConvertHostTimeToNanos"
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
    local item items="$*"
    # The callers pass one or more deliberately space-separated symbol sets.
    # shellcheck disable=SC2086
    for item in $items; do
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
    for token in $(prefixed "$FORBIDDEN_EXACT_NAMES"); do
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
    local forbidden_count
    forbidden_count="$(printf '%s %s\n' "$(prefixed "$FORBIDDEN_EXACT_NAMES")" "$FORBIDDEN_PREFIX" |
        wc -w | tr -d ' ')"
    ok "$label refers to none of the $forbidden_count forbidden names"
    return 0
}

# Audits one compiled real-time unit. Returns non-zero when anything was wrong, which is what the
# negative control mode reads.
audit_render_object() {
    # W-17: the spelling of every symbol below follows the object, not the host. The object is the
    # SECOND argument; these functions take the label first.
    detect_symbol_prefix "$2"
    local label="$1" object="$2"
    local before="$FAILURES"
    local undefined symbol

    # Mach-O's `nm -u` prints the bare name; ELF's prints "                 U name". Taking the
    # last field reads both, and an empty line yields nothing rather than a phantom symbol.
    undefined="$("$NM" -u "$object" | awk 'NF { print $NF }' | sort -u)"
    local outside=""
    for symbol in $undefined; do
        in_list "$symbol" "$(prefixed "$ALLOWED_UNDEFINED_NAMES")" || outside="$outside $symbol"
    done
    if [ -n "$outside" ]; then
        bad "$label has undefined symbols outside the allowlist:$outside"
    else
        local undefined_inline
        undefined_inline="$(printf '%s\n' "$undefined" | tr '\n' ' ' | sed 's/ $//')"
        ok "$label undefined set is [$undefined_inline] and the allowlist is [$(prefixed "$ALLOWED_UNDEFINED_NAMES")]"
    fi

    local defined
    defined="$(defined_symbols "$object" | tr '\n' ' ')"
    local escaping=""
    for symbol in $(text_relocations "$object"); do
        case "$symbol" in
            __text|__const|l_*|L*|ltmp*) continue ;;
        esac
        in_list "$symbol" "$(prefixed "$ALLOWED_UNDEFINED_NAMES")" && continue
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

# Audits the `static` callback inside one device object. The call set is equality, not an allowlist:
# a callback which silently stopped rendering would otherwise pass because the empty set is a subset
# of every allowlist.
audit_callback_object() {
    # W-17: the spelling of every symbol below follows the object, not the host. The object is the
    # SECOND argument; these functions take the label first.
    detect_symbol_prefix "$2"
    local label="$1" object="$2"
    local before="$FAILURES"

    if ! "$NM" "$object" | grep -q " ${SYMBOL_PREFIX}kprt_render_cb$"; then
        bad "$label has no kprt_render_cb, so no callback was audited"
        return 1
    fi
    if "$NM" -g "$object" | awk '$2 == "T" { print $3 }' | grep -qx "${SYMBOL_PREFIX}kprt_render_cb"; then
        bad "$label exports kprt_render_cb, so Kotlin could install or call it"
    else
        ok "$label keeps kprt_render_cb local: not exported, not in the header, not in the bindings"
    fi

    local symbols expected
    symbols="$("$OBJDUMP" -d -r "$object" |
        awk -v entry="^[0-9a-f]+ <${SYMBOL_PREFIX}kprt_render_cb>:$" '$0 ~ entry { inside = 1; next }
             /^[0-9a-f]+ <.*>:/ { inside = 0 }
             inside && /ARM64_RELOC|X86_64_RELOC/ { print $NF }' | sort -u | tr '\n' ' ' | sed 's/ $//')"
    expected="$(printf '%s\n' "$(prefixed "$RENDER_CB_ALLOWED_NAMES")" | tr ' ' '\n' |
        sed '/^$/d' | sort -u | tr '\n' ' ' | sed 's/ $//')"
    if [ "$symbols" = "$expected" ]; then
        ok "$label callback call set is exactly [$symbols]"
    else
        bad "$label callback call set is [$symbols], expected exactly [$expected]"
    fi
    scan_forbidden "$label callback" <<< "$(printf '%s\n' "$symbols" | tr ' ' '\n' | sed '/^$/d')"

    [ "$FAILURES" = "$before" ]
}

# Pins the AudioUnit subtype from the compiled object. Both the expected presence and the opposite
# absence matter: checking only for RemoteIO would accept an object which selected both branches.
# Mach-O only, and CoreAudio only: the four-character subtype lives in __TEXT,__literal8, a section
# ELF does not have, and the value it pins is an AudioComponentDescription's. A non-Apple sink has
# no equivalent to check, so this is skipped rather than failed.
audit_device_subtype() {
    local label="$1" object="$2" expected="$3" forbidden="$4"
    local before="$FAILURES"
    local literals
    literals="$("$OTOOL" -s __TEXT __literal8 "$object" 2>/dev/null)"
    if printf '%s\n' "$literals" | grep -Eq "61756f75[[:space:]]+$expected"; then
        ok "$label embeds the expected AudioUnit subtype 0x$expected"
    else
        bad "$label does not embed type Output followed by expected subtype 0x$expected"
    fi
    if printf '%s\n' "$literals" | grep -Eq "[[:space:]]$forbidden([[:space:]]|$)"; then
        bad "$label also embeds the opposite AudioUnit subtype 0x$forbidden"
    else
        ok "$label does not embed the opposite AudioUnit subtype 0x$forbidden"
    fi

    [ "$FAILURES" = "$before" ]
}

# Pins the source selection separately from the object check. The outer guard must compile the real
# device unit for exactly macOS and iOS, and the inner branch must select RemoteIO only for iOS.
audit_source_platform_selection() {
    local label="$1" source="$2"
    local before="$FAILURES"
    local outer_osx outer_ios branch remote otherwise default finish

    outer_osx="$(grep -nF "((defined(TARGET_OS_OSX) && TARGET_OS_OSX) || \\" "$source" |
        head -1 | cut -d: -f1)"
    outer_ios="$(grep -nF '(defined(TARGET_OS_IOS) && TARGET_OS_IOS))' "$source" |
        head -1 | cut -d: -f1)"
    if [ -n "$outer_osx" ] && [ -n "$outer_ios" ] && [ "$outer_osx" -lt "$outer_ios" ]; then
        ok "$label enables the common device unit for macOS and iOS"
    else
        bad "$label does not keep the exact macOS-or-iOS implementation guard"
    fi

    branch="$(grep -nF '#if defined(TARGET_OS_IOS) && TARGET_OS_IOS' "$source" |
        head -1 | cut -d: -f1)"
    remote="$(grep -nF 'description.componentSubType = kAudioUnitSubType_RemoteIO;' "$source" |
        head -1 | cut -d: -f1)"
    otherwise="$(awk -v start="${branch:-0}" 'start > 0 && FNR > start && /^#else$/ { print FNR; exit }' "$source")"
    default="$(grep -nF 'description.componentSubType = kAudioUnitSubType_DefaultOutput;' "$source" |
        head -1 | cut -d: -f1)"
    finish="$(awk -v start="${otherwise:-0}" 'start > 0 && FNR > start && /^#endif$/ { print FNR; exit }' "$source")"
    if [ -n "$branch" ] && [ -n "$remote" ] && [ -n "$otherwise" ] &&
        [ -n "$default" ] && [ -n "$finish" ] &&
        [ "$branch" -lt "$remote" ] && [ "$remote" -lt "$otherwise" ] &&
        [ "$otherwise" -lt "$default" ] && [ "$default" -lt "$finish" ]; then
        ok "$label selects RemoteIO in the iOS branch and DefaultOutput otherwise"
    else
        bad "$label does not keep RemoteIO in the iOS branch and DefaultOutput in the macOS branch"
    fi

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

audit_source_platform_selection "kite_rt_coreaudio.c" "$ROOT/src/kite_rt_coreaudio.c"

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
    audit_callback_object "freshly compiled macOS device object" "$DEVICE_OBJ"
    audit_device_subtype "freshly compiled macOS device object" "$DEVICE_OBJ" \
        "$DEFAULT_OUTPUT_FOURCC" "$REMOTE_IO_FOURCC"
fi

# ---- 5. The objects that actually ship, when Gradle has built them ----
#
# Everything above compiles the sources again. This step audits the archive the cinterop klib
# embeds, which is the only object a consumer ever runs. A clean clone may have none of the three
# optional archives and says so. Once any one exists, all three are required: that makes the S1.b.3
# gate incapable of silently auditing macOS while skipping either phone archive.

SHIPPED_ROOT="$MODULE/build/kiteplayer-rt-c"
MACOS_ARCHIVE="$SHIPPED_ROOT/macos_arm64/libkiteplayerrt.a"
IOS_DEVICE_ARCHIVE="$SHIPPED_ROOT/ios_arm64/libkiteplayerrt.a"
IOS_SIM_ARCHIVE="$SHIPPED_ROOT/ios_simulator_arm64/libkiteplayerrt.a"
SHIPPED_FOUND=0
SHIPPED_MISSING=""
for archive in "$MACOS_ARCHIVE" "$IOS_DEVICE_ARCHIVE" "$IOS_SIM_ARCHIVE"; do
    if [ -f "$archive" ]; then
        SHIPPED_FOUND=$((SHIPPED_FOUND + 1))
    else
        SHIPPED_MISSING="$SHIPPED_MISSING $archive"
    fi
done

audit_shipped_archive() {
    # audit_shipped_archive <label> <archive> <expected subtype> <forbidden subtype>
    local label="$1" archive="$2" expected="$3" forbidden="$4"
    local extract="$WORK/shipped-$label"
    mkdir -p "$extract"
    (cd "$extract" && "$AR" x "$archive")

    if [ -f "$extract/kite_rt_render.o" ]; then
        audit_render_object "$label shipped kite_rt_render.o" "$extract/kite_rt_render.o"
    else
        bad "$label shipped archive has no kite_rt_render.o member"
    fi
    if [ -f "$extract/kite_rt_coreaudio.o" ]; then
        if "$NM" "$extract/kite_rt_coreaudio.o" | grep -q "${SYMBOL_PREFIX}kprt_test_invoke_render_callback$"; then
            bad "$label shipped device object contains the host-only callback fixture seam"
        else
            ok "$label shipped device object excludes the host-only callback fixture seam"
        fi
        audit_callback_object "$label shipped kite_rt_coreaudio.o" "$extract/kite_rt_coreaudio.o"
        audit_device_subtype "$label shipped kite_rt_coreaudio.o" \
            "$extract/kite_rt_coreaudio.o" "$expected" "$forbidden"
    else
        bad "$label shipped archive has no kite_rt_coreaudio.o member"
    fi
}

if [ "$SHIPPED_FOUND" -eq 0 ]; then
    say "skip  no gated Apple archive is built yet, so only freshly compiled macOS objects were audited"
    say "      build macosArm64, iosArm64 and iosSimulatorArm64 before the S1.b.3 gate"
elif [ -n "$SHIPPED_MISSING" ]; then
    bad "only $SHIPPED_FOUND of 3 gated Apple archives exist; refusing partial audit. Missing:$SHIPPED_MISSING"
else
    audit_shipped_archive macos-arm64 "$MACOS_ARCHIVE" \
        "$DEFAULT_OUTPUT_FOURCC" "$REMOTE_IO_FOURCC"
    audit_shipped_archive ios-arm64 "$IOS_DEVICE_ARCHIVE" \
        "$REMOTE_IO_FOURCC" "$DEFAULT_OUTPUT_FOURCC"
    audit_shipped_archive ios-simulator-arm64 "$IOS_SIM_ARCHIVE" \
        "$REMOTE_IO_FOURCC" "$DEFAULT_OUTPUT_FOURCC"
fi

# ---- 5b. The ELF arm ----
#
# The same real-time scan, on the cross-compiled linux_arm64 archive, to prove the instrument reads
# the object format it is given rather than the one this machine happens to use. Only the render
# unit is audited: the device half of that archive is kite_rt_sink_unsupported.o, which refuses
# every entry point and has no callback to scan. Skipped with a note when the archive has not been
# built, because a missing cross build is not a defect in the audit.
# Apple's ar cannot read a GNU archive: it treats the long-name member `/39` as a file to create
# and dies. llvm-ar reads both, and konan ships it beside the clang this audit already prefers.
find_llvm_ar() {
    local konan_root="${KONAN_DATA_DIR:-$HOME/.konan}/dependencies"
    if [ -x "$konan_root/$PREFERRED_LLVM/bin/llvm-ar" ]; then
        echo "$konan_root/$PREFERRED_LLVM/bin/llvm-ar"
        return 0
    fi
    find "$konan_root" -maxdepth 3 -type f -path '*/llvm-*/bin/llvm-ar' 2>/dev/null | sort | tail -1
}

LINUX_ARCHIVE="$MODULE/build/kiteplayer-rt-c/linux_arm64/libkiteplayerrt.a"
LLVM_AR="$(find_llvm_ar)"
if [ -f "$LINUX_ARCHIVE" ] && [ -n "$LLVM_AR" ] && [ -x "$LLVM_AR" ]; then
    linux_extract="$WORK/shipped-linux-arm64"
    mkdir -p "$linux_extract"
    (cd "$linux_extract" && "$LLVM_AR" x "$LINUX_ARCHIVE")
    if [ -f "$linux_extract/kite_rt_render.o" ]; then
        audit_render_object "linux-arm64 shipped kite_rt_render.o" "$linux_extract/kite_rt_render.o"
    else
        bad "linux-arm64 shipped archive has no kite_rt_render.o member"
    fi
else
    say "note: the ELF arm did not run (archive $LINUX_ARCHIVE, llvm-ar ${LLVM_AR:-not found})"
    say "      run ./gradlew :kiteplayer-rt:compileKotlinLinuxArm64 to build it"
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

    run_negative_audit() {
        # run_negative_audit <name> <audit function> [arguments...]
        local name="$1"
        shift
        local log="$POISON_DIR/$name.audit"
        local before_failures="$FAILURES" before_checks="$CHECKS"
        local rejected=0
        "$@" > "$log" 2>&1 || rejected=1
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

    # 5. Swap the two platform subtypes and require the branch-order source check to reject it.
    SWAPPED_DEVICE_SOURCE="$POISON_DIR/subtypes-swapped.c"
    sed -e 's/kAudioUnitSubType_RemoteIO/kAudioUnitSubType_SWAP_TMP/g' \
        -e 's/kAudioUnitSubType_DefaultOutput/kAudioUnitSubType_RemoteIO/g' \
        -e 's/kAudioUnitSubType_SWAP_TMP/kAudioUnitSubType_DefaultOutput/g' \
        "$ROOT/src/kite_rt_coreaudio.c" > "$SWAPPED_DEVICE_SOURCE"
    if cmp -s "$SWAPPED_DEVICE_SOURCE" "$ROOT/src/kite_rt_coreaudio.c"; then
        bad "negative control subtypes-swapped did not change the device source"
    else
        run_negative_audit "subtypes-swapped-in-source" \
            audit_source_platform_selection "negative control subtype source" "$SWAPPED_DEVICE_SOURCE"
    fi

    # 6. Compile a macOS object whose DefaultOutput arm contains RemoteIO's FourCC. The numeric poison
    # is deliberate: the macOS SDK does not declare the RemoteIO name, and a compiler rejection would
    # test the SDK rather than the object audit.
    SWAPPED_DEVICE_OBJECT_SOURCE="$POISON_DIR/subtype-object-swapped.c"
    SWAPPED_DEVICE_OBJECT="$POISON_DIR/subtype-object-swapped.o"
    MACOS_SUBTYPE='description.componentSubType = kAudioUnitSubType_DefaultOutput;'
    IOS_FOURCC_SUBTYPE='description.componentSubType = (OSType)0x72696f63u;'
    sed "s|$MACOS_SUBTYPE|$IOS_FOURCC_SUBTYPE|" \
        "$ROOT/src/kite_rt_coreaudio.c" > "$SWAPPED_DEVICE_OBJECT_SOURCE"
    # shellcheck disable=SC2086
    if cmp -s "$SWAPPED_DEVICE_OBJECT_SOURCE" "$ROOT/src/kite_rt_coreaudio.c"; then
        bad "negative control subtype-object-swapped did not change the device source"
    elif ! "$CC" $SHIPPED_FLAGS $SYSROOT_ARGS -I "$ROOT/include" -I "$ROOT/src" \
            -c "$SWAPPED_DEVICE_OBJECT_SOURCE" -o "$SWAPPED_DEVICE_OBJECT" \
            2>"$POISON_DIR/subtype-object-swapped.log"; then
        bad "negative control subtype-object-swapped did not compile, so the object audit was not tested"
        sed 's/^/      /' "$POISON_DIR/subtype-object-swapped.log"
    else
        run_negative_audit "subtypes-swapped-in-object" audit_device_subtype \
            "negative control subtype object" "$SWAPPED_DEVICE_OBJECT" \
            "$DEFAULT_OUTPUT_FOURCC" "$REMOTE_IO_FOURCC"
    fi

    # 7. Add one allocator call directly to the static callback. The volatile store keeps the call in
    # the object, and the exact call-set check must reject it even without the forbidden-name scan.
    CALLBACK_CALL_SOURCE="$POISON_DIR/callback-extra-call.c"
    CALLBACK_CALL_OBJECT="$POISON_DIR/callback-extra-call.o"
    CALLBACK_POISON='(void)bus; static void *volatile callback_poison; callback_poison = malloc(1);'
    CALLBACK_POISON="$CALLBACK_POISON if (callback_poison == (void *)data) return noErr;"
    sed "s|(void)bus;|$CALLBACK_POISON|" \
        "$ROOT/src/kite_rt_coreaudio.c" > "$CALLBACK_CALL_SOURCE"
    # shellcheck disable=SC2086
    if cmp -s "$CALLBACK_CALL_SOURCE" "$ROOT/src/kite_rt_coreaudio.c"; then
        bad "negative control callback-extra-call did not change the device source"
    elif ! "$CC" $SHIPPED_FLAGS $SYSROOT_ARGS -I "$ROOT/include" -I "$ROOT/src" \
            -c "$CALLBACK_CALL_SOURCE" -o "$CALLBACK_CALL_OBJECT" \
            2>"$POISON_DIR/callback-extra-call.log"; then
        bad "negative control callback-extra-call did not compile, so the callback audit was not tested"
        sed 's/^/      /' "$POISON_DIR/callback-extra-call.log"
    else
        run_negative_audit "callback-extra-call" audit_callback_object \
            "negative control callback object" "$CALLBACK_CALL_OBJECT"
    fi
fi

say ""
if [ "$FAILURES" -eq 0 ]; then
    say "render-audit.sh: $CHECKS checks, all passed"
    exit 0
fi
say "render-audit.sh: $CHECKS checks, $FAILURES failed"
exit 1
