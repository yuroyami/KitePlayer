#!/usr/bin/env bash
# The local macOS arm64 commit gate. CI owns execution on other host operating systems.
# Usage: ./scripts/check-gate.sh tier1|tier2 [--from=STEP] [--dry-run]
# Tier 3 adds the supervised physical-device run described in CONTRIBUTING.md.
set -euo pipefail

TIER="${1:-}"
case "$TIER" in tier1|tier2) ;; *) echo "usage: $0 tier1|tier2 [--from=STEP] [--dry-run]" >&2; exit 2 ;; esac
shift
DRY_RUN=false
FROM=""
for option in "$@"; do
    case "$option" in
        --dry-run) DRY_RUN=true ;;
        --from=*) FROM="${option#--from=}" ;;
        *) echo "unknown option: $option" >&2; exit 2 ;;
    esac
done

GATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$GATE_ROOT"

run() {
    printf 'gate:'
    printf ' %q' "$@"
    printf '\n'
    if ! "$DRY_RUN"; then "$@"; fi
}
kotlin() { run env KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin "$@"; }

scan_em_dashes() {
    # git grep reads the working copies of tracked files and preserves errors separately from
    # its expected no-match exit code. Add new files to the index before the final commit gate.
    local status
    if git grep -n "$(printf '\342\200\224')"; then
        echo 'Em dash found in tracked source.' >&2
        return 1
    else
        status=$?
        [ "$status" -eq 1 ]
    fi
}

sample_smoke() {
    local binary=build/tasks/_kiteplayer-sample_linkMacosArm64Debug/kiteplayer-sample.kexe
    local clip log status
    mkdir -p build/reports/gate-sample
    # The four original house clips cover regular timing, variable timing, hardware decode,
    # and embedded subtitles. This is a sample smoke; the format matrix is the wider proof.
    for clip in sync1080p30.mp4 truevfr720.mp4 hevc4k10.mp4 subbed.mkv; do
        log="build/reports/gate-sample/$clip.log"
        "$binary" "testmedia/$clip" > "$log" 2>&1
        cat "$log"
        # The sample can finish main after playback fails, so exit zero alone is insufficient.
        grep -Fqx 'done.' "$log"
    done
    log=build/reports/gate-sample/nonexistent.log
    [ ! -e build/reports/gate-sample/does-not-exist.mkv ]
    if "$binary" build/reports/gate-sample/does-not-exist.mkv > "$log" 2>&1; then
        echo 'The nonexistent sample input was accepted.' >&2
        return 1
    else
        status=$?
    fi
    cat "$log"
    [ "$status" -eq 1 ]
    grep -q '^cannot play ' "$log"
}

if ! "$DRY_RUN"; then
    [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] || {
        echo 'The local gate requires a macOS arm64 host; CI covers other hosts.' >&2
        exit 2
    }
fi

fixtures() { run ./scripts/testmedia.sh; }
# The native and simulator tests read the clips from the variables that this script sets.
. ./scripts/test-env.sh
base() {
    kotlin check kitertCoupling abi -m kiteplayer-core -m kiteplayer-subtitles -m kiteplayer-io
    kotlin test -p jvm -m kiteplayer-core -m kiteplayer-subtitles -m kiteplayer-io
    run kiteplayer-rt/native/scripts/build-host.sh plain
    run kiteplayer-rt/native/scripts/run-c-tests.sh plain
    run kiteplayer-rt/native/scripts/render-audit.sh
    run kiteplayer-rt/native/scripts/source-discipline.sh
    run scan_em_dashes
}
build_logic() { kotlin test -m build-support; }
publication() {
    # The POM check reads a local publish of every published module.
    local module
    . ./scripts/test-signing-key.sh
    local flags=()
    for module in $(./scripts/published-modules.sh); do flags+=(-m "$module"); done
    kotlin publish mavenLocal --non-transitive "${flags[@]}"
    kotlin check publication
    run ./scripts/check-dependency-hygiene.sh
}
macos() {
    kotlin test -p macosArm64 -m kiteplayer-core -m kiteplayer-subtitles -m kiteplayer-io \
        -m kiteplayer-output -m kiteplayer-rt \
        -m kiteplayer-libass -m kiteplayer-adaptive -m kiteplayer-network \
        -m kiteplayer-ffmpeg -m kiteplayer
}
ios_simulator() {
    kotlin test -p iosSimulatorArm64 -m kiteplayer-view -m kiteplayer-audioviz -m kiteplayer
}
c_sanitizers() {
    # This reuses the plain build from base. When resuming here, that earlier build must exist.
    run kiteplayer-rt/native/scripts/run-c-tests.sh interpose
    for variant in asan tsan; do
        run kiteplayer-rt/native/scripts/build-host.sh "$variant"
        run kiteplayer-rt/native/scripts/run-c-tests.sh "$variant"
    done
    # The libass C runner builds before running; it requires no native libass installation. The
    # wasm32 variant needs emcc, which the web step needs too, and runs the suites where size_t is
    # 32 bits wide.
    run kiteplayer-libass/native/scripts/run-c-tests.sh plain
    run kiteplayer-libass/native/scripts/run-c-tests.sh asan
    run kiteplayer-libass/native/scripts/run-c-tests.sh wasm32
}
jvm() {
    # The audio visualiser's rendering suites take about 25 minutes, so the gate leaves them to
    # ./scripts/audioviz-tests.sh survey.
    kotlin test -p jvm -m kiteplayer-output -m kiteplayer-view -m kiteplayer-adaptive -m kiteplayer-network \
        -m kiteplayer-ffmpeg -m kiteplayer \
        -m kiteplayer-compose-video -m kiteplayer-compose-ui -m kiteplayer-sample-shared
    run ./scripts/audioviz-tests.sh gate
}
web() {
    # Wasm in Node and in headless Chrome, then Kotlin/JS in Node. The JS half compiles the same code
    # to a different engine, with JavaScript's own regular expressions and numbers, so the wasm runs
    # say nothing about it (#537). The toolchain runs no JS tests, and it gives a wasm module only
    # 30 seconds in the browser, so this script runs all three.
    run python3 scripts/web-tests/run.py
}
linux() {
    run ./scripts/linux-tests.sh
    run ./scripts/linux-jvm-tests.sh
}
windows() {
    # These are link checks on a Mac. Only the Windows CI job executes Windows tests.
    kotlin task :kiteplayer-core:linkMingwX64TestDebug :kiteplayer-subtitles:linkMingwX64TestDebug \
        :kiteplayer-output:linkMingwX64TestDebug :kiteplayer-rt:linkMingwX64TestDebug \
        :kiteplayer-ffmpeg:linkMingwX64TestDebug :kiteplayer:linkMingwX64TestDebug
}
cross_compile() {
    # Device builds are compile evidence only. The simulator view suite executes tests.
    kotlin build -p iosArm64 -m kiteplayer-core -m kiteplayer-output \
        -m kiteplayer-view -m kiteplayer-adaptive -m kiteplayer-network \
        -m kiteplayer -m kiteplayer-compose-ui -m kiteplayer-audioviz
    kotlin build -p android -m kiteplayer-audioviz -m kiteplayer-sample-android
    kotlin build -p androidNativeArm32 -m kiteplayer-core
}
sample() {
    kotlin build -m kiteplayer-sample
    run sample_smoke
}

# Generate first: no real-media suite may consume stale fixtures in a full Tier 2 run.
STEPS=(base)
if [ "$TIER" = tier2 ]; then
    STEPS=(fixtures base build_logic publication macos ios_simulator c_sanitizers jvm web linux windows cross_compile sample)
fi
FROM="${FROM:-${STEPS[0]}}"
case " ${STEPS[*]} " in
    *" $FROM "*) ;;
    *) echo "unknown step '$FROM'; available: ${STEPS[*]}" >&2; exit 2 ;;
esac
started=false
for step in "${STEPS[@]}"; do
    if [ "$step" = "$FROM" ]; then started=true; fi
    if "$started"; then
        echo "gate step: $step"
        "$step"
    fi
done

if "$DRY_RUN"; then
    echo "$TIER command plan only; no checks ran."
elif [ "$FROM" != "${STEPS[0]}" ]; then
    echo "$TIER passed from $FROM. Earlier steps did not run; retain their previous evidence."
else
    echo "$TIER passed. Physical-device and browser execution are separate evidence."
fi
