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
gradle() { run ./gradlew --console=plain --stacktrace "$@"; }

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
    local binary=kiteplayer-sample/build/bin/macosArm64/debugExecutable/kiteplayer.kexe
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
base() {
    # Gradle validates declared inputs and reuses unchanged compilation outputs.
    gradle checkKitertCoupling checkKotlinAbi \
        :kiteplayer-core:jvmTest :kiteplayer-subtitles:jvmTest
    run kiteplayer-rt/native/scripts/build-host.sh plain
    run kiteplayer-rt/native/scripts/run-c-tests.sh plain
    run kiteplayer-rt/native/scripts/render-audit.sh
    run kiteplayer-rt/native/scripts/source-discipline.sh
    run scan_em_dashes
}
build_logic() { gradle :buildSrc:test; }
publication() {
    gradle checkPublicationReadiness
    run ./scripts/check-dependency-hygiene.sh
}
macos() {
    gradle :kiteplayer-core:macosArm64Test :kiteplayer-subtitles:macosArm64Test \
        :kiteplayer-output:macosArm64Test :kiteplayer-rt:macosArm64Test \
        :kiteplayer-libass:macosArm64Test :kiteplayer-network:macosArm64Test \
        :kiteplayer-ffmpeg:macosArm64Test
}
ios_simulator() { gradle :kiteplayer-view:iosSimulatorArm64Test; }
c_sanitizers() {
    # This reuses the plain build from base. When resuming here, that earlier build must exist.
    run kiteplayer-rt/native/scripts/run-c-tests.sh interpose
    for variant in asan tsan; do
        run kiteplayer-rt/native/scripts/build-host.sh "$variant"
        run kiteplayer-rt/native/scripts/run-c-tests.sh "$variant"
    done
    # The libass C runner builds before running; it requires no native libass installation.
    run kiteplayer-libass/native/scripts/run-c-tests.sh plain
    run kiteplayer-libass/native/scripts/run-c-tests.sh asan
}
jvm() {
    gradle :kiteplayer-output:jvmTest :kiteplayer-view:jvmTest :kiteplayer-network:jvmTest \
        :kiteplayer-ffmpeg:jvmTest :kiteplayer:jvmTest \
        :kiteplayer-compose-video:jvmTest :kiteplayer-compose-ui:jvmTest
}
web() {
    gradle :kiteplayer-core:wasmJsNodeTest :kiteplayer-subtitles:wasmJsNodeTest \
        :kiteplayer-output:wasmJsNodeTest :kiteplayer-ffmpeg:wasmJsNodeTest \
        :kiteplayer-network:wasmJsNodeTest
}
linux() {
    run ./scripts/linux-tests.sh
    run ./scripts/linux-jvm-tests.sh
}
windows() {
    # These are link checks on a Mac. Only the Windows CI job executes Windows tests.
    gradle :kiteplayer-core:linkDebugTestMingwX64 :kiteplayer-subtitles:linkDebugTestMingwX64 \
        :kiteplayer-output:linkDebugTestMingwX64 :kiteplayer-rt:linkDebugTestMingwX64 \
        :kiteplayer-ffmpeg:linkDebugTestMingwX64
}
cross_compile() {
    # Device builds are compile evidence only. The simulator view suite executes tests.
    gradle :kiteplayer-core:compileKotlinIosArm64 :kiteplayer-output:compileKotlinIosArm64 \
        :kiteplayer-view:compileKotlinIosArm64 :kiteplayer-network:compileKotlinIosArm64 \
        :kiteplayer:compileKotlinIosArm64 :kiteplayer-compose-ui:compileKotlinIosArm64 \
        :kiteplayer-core:compileKotlinAndroidNativeArm32 :kiteplayer-sample-android:assembleDebug
}
sample() {
    gradle :kiteplayer-sample:linkDebugExecutableMacosArm64
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
