#!/usr/bin/env bash
# Stage exact checkout publications, then prove automatic transport from an isolated consumer.
# The endpoint must serve nonempty bytes. iOS starts only the explicitly selected simulator.
set -euo pipefail

probe_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
probe_consumer="$probe_root/verification/network-consumer"
probe_repository="$probe_root/build/verification-maven"
probe_version="$(sed -n 's/^VERSION[[:space:]]*=[[:space:]]*//p' "$probe_root/gradle.properties" | tr -d '\r')"
probe_stage=false
probe_run=false
probe_target=all
probe_url=http://127.0.0.1:8765/media
probe_simulator=
probe_node=node
probe_android_serial=
probe_refresh=true
probe_adb=adb
probe_sdk="$(sed -n 's/^sdk.dir=//p' "$probe_root/local.properties" 2>/dev/null || true)"
if [[ -x "$probe_sdk/platform-tools/adb" ]]; then probe_adb="$probe_sdk/platform-tools/adb"; fi

usage() {
    cat <<'USAGE'
Usage: scripts/verify-network-distribution.sh --stage
       scripts/verify-network-distribution.sh --run [--target jvm|macos|ios|wasm|android|all]
              [--url URL] [--simulator UDID] [--node PATH] [--android-serial SERIAL] [--adb PATH]
              [--reuse-staged-cache]

--stage and --run may be combined. Runs both without-network and with-network consumers.
The default URL is http://127.0.0.1:8765/media and must serve nonempty bytes.
The iOS probe requires an explicit simulator UDID and waits for that device to boot.
The Wasm probe requires a Node version supporting Kotlin's current Wasm output.
Android requires --android-serial; "all" includes Android only when that flag is supplied.
Use --url http://10.0.2.2:8765/media for an emulator to reach a server on the host.
The Android app is a non-debuggable release shrunk by R8 and signed for local installation.
--reuse-staged-cache skips dependency refresh only for repeat runs against unchanged staged bytes.
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --stage) probe_stage=true; shift ;;
        --run) probe_run=true; shift ;;
        --reuse-staged-cache) probe_refresh=false; shift ;;
        --target|--url|--simulator|--node|--android-serial|--adb)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            case "$1" in
                --target) probe_target="$2" ;;
                --url) probe_url="$2" ;;
                --simulator) probe_simulator="$2" ;;
                --node) probe_node="$2" ;;
                --android-serial) probe_android_serial="$2" ;;
                --adb) probe_adb="$2" ;;
            esac
            shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
[[ "$probe_stage" == true || "$probe_run" == true ]] || { usage >&2; exit 2; }
if [[ "$probe_stage" == true && "$probe_refresh" == false ]]; then
    echo 'Do not reuse dependency caches while staging new bytes.' >&2; exit 2
fi
[[ -n "$probe_version" && "$probe_version" != *$'\n'* ]] || { echo 'Expected one root VERSION property' >&2; exit 2; }
case "$probe_target" in jvm|macos|ios|wasm|android|all) ;; *) usage >&2; exit 2 ;; esac

selected() { [[ "$probe_target" == all || "$probe_target" == "$1" ]]; }
android_selected() {
    [[ "$probe_target" == android || ( "$probe_target" == all && -n "$probe_android_serial" ) ]]
}
if [[ "$probe_run" == true ]]; then
    if selected ios; then
        [[ -n "$probe_simulator" ]] || { echo 'Pass --simulator UDID for the iOS probe' >&2; exit 2; }
        command -v xcrun >/dev/null
    fi
    if selected wasm; then command -v "$probe_node" >/dev/null; fi
    if android_selected; then
        [[ -n "$probe_android_serial" ]] || { echo 'Pass --android-serial SERIAL for Android' >&2; exit 2; }
        command -v "$probe_adb" >/dev/null
    fi
fi

if [[ "$probe_stage" == true ]]; then
    probe_publications=()
    for probe_module in kiteplayer-core kiteplayer-network; do
        for probe_platform in KotlinMultiplatform Jvm MacosArm64 IosSimulatorArm64 WasmJs Android; do
            probe_publications+=(":$probe_module:publish${probe_platform}PublicationToVerificationRepository")
        done
    done
    for probe_platform in KotlinMultiplatform MacosArm64 IosSimulatorArm64; do
        probe_publications+=(":kiteplayer-rt:publish${probe_platform}PublicationToVerificationRepository")
    done
    "$probe_root/gradlew" -p "$probe_root" --no-configuration-cache --no-parallel \
        -I "$probe_consumer/stage.init.gradle.kts" "${probe_publications[@]}"
fi
[[ "$probe_run" == true ]] || exit 0
[[ -d "$probe_repository" ]] || { echo 'Run --stage before --run' >&2; exit 2; }

# Check the success marker as well as process status: an async entry that exits before its probe
# finishes is not proof. pipefail preserves failures even though output is also saved in the log.
run_probe() {
    local probe_log="$1"
    shift
    "$@" 2>&1 | tee "$probe_log"
    rg -q "^NETWORK_PROBE_OK expectedIo=$probe_enabled observedIo=$probe_enabled bytes=" "$probe_log"
}

for probe_enabled in false true; do
    if [[ "$probe_enabled" == true ]]; then probe_variant=with-network; else probe_variant=without-network; fi
    probe_build="$probe_consumer/build/$probe_variant"
    mkdir -p "$probe_build/probe-logs"
    probe_gradle=("$probe_root/gradlew" -p "$probe_consumer" --no-configuration-cache --no-parallel
        "-PwithNetwork=$probe_enabled" "-PkiteplayerVersion=$probe_version"
        "-PverificationRepository=$probe_repository" "-PprobeUrl=$probe_url" "-PexpectNetwork=$probe_enabled")
    if [[ "$probe_refresh" == true ]]; then probe_gradle+=(--refresh-dependencies); fi
    # The isolated build has no local.properties of its own; reuse the maintainer's configured SDK.
    if [[ -d "$probe_sdk" ]]; then probe_gradle=(env "ANDROID_HOME=$probe_sdk" "${probe_gradle[@]}"); fi
    if selected jvm; then
        run_probe "$probe_build/probe-logs/jvm.log" "${probe_gradle[@]}" runJvmProbe
    fi
    if selected macos; then
        "${probe_gradle[@]}" linkReleaseExecutableMacosArm64
        run_probe "$probe_build/probe-logs/macos.log" \
            "$probe_build/bin/macosArm64/releaseExecutable/network-probe.kexe" "$probe_url" "$probe_enabled"
    fi
    if selected ios; then
        "${probe_gradle[@]}" linkReleaseExecutableIosSimulatorArm64
        xcrun simctl bootstatus "$probe_simulator" -b
        run_probe "$probe_build/probe-logs/ios.log" xcrun simctl spawn "$probe_simulator" \
            "$probe_build/bin/iosSimulatorArm64/releaseExecutable/network-probe.kexe" "$probe_url" "$probe_enabled"
    fi
    if selected wasm; then
        "${probe_gradle[@]}" wasmJsProductionExecutableCompileSync
        # Kotlin prefixes generated names with project paths. Identify the executable by the
        # actual startup call; imported helpers and library modules are not runnable entry points.
        probe_entries=()
        while IFS= read -r probe_entry; do probe_entries+=("$probe_entry"); done < <(
            rg -l 'exports\._start\(\)' \
                "$probe_build/compileSync/wasmJs/main/productionExecutable/optimized" --glob '*.mjs'
        )
        [[ ${#probe_entries[@]} -eq 1 ]] || { echo 'Expected one optimized Wasm entry point' >&2; exit 1; }
        run_probe "$probe_build/probe-logs/wasm.log" "$probe_node" "${probe_entries[0]}" \
            "$probe_url" "$probe_enabled"
    fi
    if android_selected; then
        "${probe_gradle[@]}" -PwithAndroid=true :android-app:assembleRelease
        probe_apk="$probe_build/android-app/outputs/apk/release/android-app-release.apk"
        # An actual R8 mapping must exist; a debug or unminified build is not this proof.
        [[ -s "$probe_build/android-app/outputs/mapping/release/mapping.txt" ]] || {
            echo 'The Android release did not produce an R8 mapping' >&2; exit 1;
        }
        probe_app=io.github.yuroyami.kiteplayer.verification.network
        probe_run_id="$(date +%s)-$$-$probe_enabled"
        probe_encoded_url="$(printf '%s' "$probe_url" | base64 | tr -d '\n')"
        "$probe_adb" -s "$probe_android_serial" install -r "$probe_apk"
        "$probe_adb" -s "$probe_android_serial" shell am force-stop "$probe_app"
        # Base64 keeps URL punctuation out of the remote shell's argument parser.
        "$probe_adb" -s "$probe_android_serial" shell am start -W \
            -n "$probe_app/.ProbeActivity" --es urlBase64 "$probe_encoded_url" \
            --es expectedIo "$probe_enabled" --es runId "$probe_run_id"
        probe_log="$probe_build/probe-logs/android.log"
        probe_android_passed=false
        for probe_attempt in {1..45}; do
            "$probe_adb" -s "$probe_android_serial" logcat -d -v raw -s KiteNetworkProbe:I > "$probe_log"
            if rg -q "NETWORK_PROBE_ANDROID_FAILED run=$probe_run_id " "$probe_log"; then
                cat "$probe_log"; exit 1
            fi
            if rg -q "NETWORK_PROBE_ANDROID_OK run=$probe_run_id expectedIo=$probe_enabled" "$probe_log"; then
                probe_android_passed=true
                break
            fi
            sleep 1
        done
        cat "$probe_log"
        [[ "$probe_android_passed" == true ]] || { echo 'Android probe did not complete' >&2; exit 1; }
    fi
done
