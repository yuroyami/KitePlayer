#!/usr/bin/env bash
# Explicit device operation: exports host fixtures, pushes a probe, and runs offscreen Android rendering.
# Does not install the sample or take app focus. Timings include synchronization/readback, not isolated GPU cost.
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?Usage: scripts/check-neonlofi-android.sh SERIAL [FRAMES] [phone|wide]}"
frames="${2:-30}"
view="${3:-phone}"
case "$view" in
    phone) width=1080; height=2400 ;;
    wide) width=960; height=540 ;;
    *) exit 2 ;;
esac
[[ "$frames" =~ ^[0-9]+$ ]] || exit 2
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" ]]; then sdk="$(sed -n 's/^sdk.dir=//p' local.properties)"; fi
adb="$sdk/platform-tools/adb"
"$adb" -s "$serial" get-state
./gradlew --no-parallel --max-workers=1 :kiteplayer-audioviz:jvmTest --rerun \
    --tests '*NeonLoFiRenderTest.musicalRoadAtPhoneAndWideSizesWithBothBackgrounds'
build_tools="$(python3 - "$sdk" <<'PY'
import pathlib,re,sys
paths=[p for p in (pathlib.Path(sys.argv[1])/'build-tools').iterdir() if (p/'d8').is_file()]
print(max(paths,key=lambda p:tuple(map(int,re.findall(r'\d+',p.name)))))
PY
)"
android_jar="$(python3 - "$sdk" <<'PY'
import pathlib,re,sys
paths=list((pathlib.Path(sys.argv[1])/'platforms').glob('android-*/android.jar'))
print(max(paths,key=lambda p:tuple(map(int,re.findall(r'\d+',p.parent.name)))))
PY
)"
work="$(mktemp -d "${TMPDIR:-/tmp}/neonlofi-android.XXXXXX")"
trap 'rm -rf "$work"' EXIT
javac --release 8 -classpath "$android_jar" -d "$work" \
    scripts/probes/OdysseyAndroidProbe.java scripts/probes/NeonLoFiAndroidProbe.java
"$build_tools/d8" --output "$work" "$work"/*.class
fixture="kiteplayer-audioviz/build/neonlofi-preview/gpu-$view"
remote=/data/local/tmp/kiteplayer-neonlofi-probe
"$adb" -s "$serial" shell mkdir -p "$remote"
"$adb" -s "$serial" push "$work/classes.dex" "$remote/probe.dex"
"$adb" -s "$serial" push "$fixture/neonlofi.sksl" "$remote/"
"$adb" -s "$serial" push "$fixture"/neon-* "$remote/"
"$adb" -s "$serial" shell "CLASSPATH=$remote/probe.dex app_process /system/bin NeonLoFiAndroidProbe $remote $width $height $frames"
for mode in 1 2 3 4; do
    "$adb" -s "$serial" pull "$remote/neon-gpu-$mode.png" "$fixture/neon-gpu-$mode.png"
done
